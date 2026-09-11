package com.codex.lockertest.serial;

import android_serialport_api.SerialPort;

import com.codex.lockertest.integration.OnlineUnlockDispatchPermit;
import com.codex.lockertest.protocol.HexCodec;
import com.codex.lockertest.runtime.RuntimeSerialLog;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

public final class SerialGateway {
    public interface DisposeCallback {
        void onDisposed(boolean safelyReleased);
    }

    public interface Listener {
        void onConnectionChanged(boolean connected, String detail);

        void onDiagnostic(String detail);

        void onSent(byte[] bytes);

        void onReceived(byte[] bytes);

        void onSendFailed(byte[] payload, String detail);
    }

    public static final class Subscription {
        private final SerialGateway parent;
        private final IdentityListenerRegistry.Subscription<Listener> delegate;

        private Subscription(
                SerialGateway parent,
                IdentityListenerRegistry.Subscription<Listener> delegate) {
            this.parent = parent;
            this.delegate = delegate;
        }

        public boolean unsubscribe() {
            return parent.unsubscribe(this);
        }
    }

    private static final long READER_TERMINATION_TIMEOUT_MILLIS = 5_000L;

    private final Object resourceLock = new Object();
    private final IdentityListenerRegistry<Listener> listeners =
            new IdentityListenerRegistry<>();
    private final RuntimeSerialLog runtimeLog = RuntimeSerialLog.shared();
    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA);
    private final SerialSessionState state = new SerialSessionState();
    private final CloseReopenCoordinator closeReopen =
            new CloseReopenCoordinator(state);
    private final ReaderGeneration<ReaderLoop> readerGenerations =
            new ReaderGeneration<>();
    private final ExecutorService serialExecutor = Executors.newSingleThreadExecutor();
    private final SerialSendDispatcher sendDispatcher = new SerialSendDispatcher(
            serialExecutor::execute,
            new SerialSendDispatcher.Events() {
                @Override
                public void onSent(byte[] payload) {
                    notifySent(payload);
                }

                @Override
                public void onSendFailed(byte[] payload, String detail) {
                    notifySendFailed(payload, detail);
                }
            });
    private final GatewayDisposalCoordinator disposalCoordinator =
            new GatewayDisposalCoordinator(
                    new GatewayDisposalCoordinator.Queue() {
                        @Override
                        public void execute(Runnable task) {
                            serialExecutor.execute(task);
                        }

                        @Override
                        public void shutdownAfterQueuedTasks() {
                            serialExecutor.shutdown();
                        }
                    });
    private final List<ReaderTerminationGate> pendingReaderTerminations =
            new ArrayList<>();

    private PortResources currentResources;

    public SerialGateway() {
    }

    public Subscription subscribe(Listener listener) {
        return new Subscription(this, listeners.replace(listener));
    }

    public boolean unsubscribe(Subscription expected) {
        return expected != null
                && expected.parent == this
                && listeners.remove(expected.delegate);
    }

    public boolean open(SerialConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("串口配置不能为空");
        }
        if (!state.beginOpen()) {
            return false;
        }
        try {
            serialExecutor.execute(() -> openInternal(config));
            return true;
        } catch (RuntimeException rejection) {
            state.markOpenFailed();
            notifyConnectionChanged(false, "打开失败：串口任务队列不可用");
            return false;
        }
    }

    public boolean closePort() {
        long closeToken = closeReopen.beginClose();
        if (closeToken == 0L) {
            return false;
        }
        try {
            serialExecutor.execute(() -> closePortInternal(closeToken));
            return true;
        } catch (RuntimeException rejection) {
            state.runIfClosing(closeToken, () -> notifyConnectionChanged(
                    false, "串口关闭失败：任务队列不可用，禁止重新打开"));
            return false;
        }
    }

    public boolean send(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("发送数据不能为空");
        }
        PortResources accepted = currentResources();
        if (!state.canSend() || accepted == null) {
            return false;
        }
        return sendDispatcher.dispatch(bytes, new SerialSendDispatcher.Resource() {
            @Override
            public boolean isCurrent() {
                return state.canSend() && SerialGateway.this.isCurrent(accepted);
            }

            @Override
            public void writeAndFlush(byte[] payload) throws Throwable {
                accepted.outputStream.write(payload);
                accepted.outputStream.flush();
            }
        });
    }

    public boolean sendAuthorized(
            byte[] bytes,
            OnlineUnlockDispatchPermit permit,
            BooleanSupplier ready) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("发送数据不能为空");
        }
        if (permit == null || ready == null) {
            throw new IllegalArgumentException("授权发送依赖不能为空");
        }
        PortResources accepted = currentResources();
        if (!state.canSend() || accepted == null) {
            return false;
        }
        return sendDispatcher.dispatch(bytes, new SerialSendDispatcher.Resource() {
            @Override
            public boolean isCurrent() {
                return state.canSend()
                        && SerialGateway.this.isCurrent(accepted)
                        && permit.active()
                        && readySafely(ready);
            }

            @Override
            public void writeAndFlush(byte[] payload) throws Throwable {
                boolean written = permit.write(
                        payload,
                        () -> state.canSend()
                                && SerialGateway.this.isCurrent(accepted)
                                && readySafely(ready),
                        () -> {
                            accepted.outputStream.write(payload);
                            accepted.outputStream.flush();
                        });
                if (!written) {
                    throw new IOException("authorized serial write revoked before execution");
                }
            }
        });
    }

    public boolean isConnected() {
        return state.canSend();
    }

    public SerialSessionState.ConnectionAction connectionAction() {
        return state.connectionAction();
    }

    public SerialSessionState.Phase phase() {
        return state.phase();
    }

    public SerialConfig getActiveConfig() {
        PortResources resources = currentResources();
        return resources == null ? null : resources.config;
    }

    public void dispose() {
        dispose(null);
    }

    public void dispose(DisposeCallback onDisposed) {
        disposalCoordinator.dispose(
                this::detachForDisposal,
                onDisposed == null ? null : onDisposed::onDisposed);
    }

    private void openInternal(SerialConfig config) {
        File device = new File(config.getPath());
        String diagnostic = SerialPlatformDiagnostics.inspect(device);
        notifyConnectionChanged(false, "正在打开 " + config.describe());
        notifyDiagnostic(diagnostic);
        SerialPort opened = null;
        try {
            opened = new SerialPort(
                    device,
                    config.getBaudRate(),
                    config.getParity(),
                    config.getDataBits(),
                    config.getStopBits(),
                    config.getFlags());
            final SerialPort candidate = opened;
            final InputStream candidateInput = candidate.getInputStream();
            final OutputStream candidateOutput = candidate.getOutputStream();
            final OpenPublication publication = new OpenPublication();
            if (!state.commitOpen(() -> publication.reader = publishResources(
                    candidate,
                    candidateInput,
                    candidateOutput,
                    config))) {
                closeQuietly(opened);
                return;
            }
            if (state.canSend() && readerGenerations.isCurrent(publication.reader)) {
                notifyConnectionChanged(true, "已打开 " + config.describe());
            }
        } catch (Throwable throwable) {
            closeQuietly(opened);
            state.markOpenFailed();
            notifyConnectionChanged(
                    false,
                    "打开失败：" + readableMessage(throwable)
                            + "\n" + diagnostic
                            + "\n请彻底退出其他串口应用后重试");
        }
    }

    private ReaderGeneration.Lease<ReaderLoop> publishResources(
            SerialPort port,
            InputStream inputStream,
            OutputStream outputStream,
            SerialConfig config) {
        ReaderLoop loop = new ReaderLoop(inputStream, ReaderTerminationGate.running());
        synchronized (resourceLock) {
            if (currentResources != null) {
                throw new IllegalStateException("previous serial resources are still owned");
            }
            ReaderGeneration.Lease<ReaderLoop> reader =
                    readerGenerations.activate(loop);
            Thread thread = createReaderThread(reader);
            loop.thread = thread;
            currentResources = new PortResources(
                    port, outputStream, config, reader);
            pendingReaderTerminations.add(loop.termination);
            try {
                thread.start();
            } catch (Throwable failure) {
                currentResources = null;
                readerGenerations.clear(reader);
                pendingReaderTerminations.remove(loop.termination);
                loop.termination.signalStopped();
                throw failure;
            }
            return reader;
        }
    }

    private Thread createReaderThread(ReaderGeneration.Lease<ReaderLoop> reader) {
        Thread thread = new Thread(() -> runReader(reader), "serial-assistant-reader");
        thread.setDaemon(true);
        return thread;
    }

    private void runReader(ReaderGeneration.Lease<ReaderLoop> reader) {
        ReaderLoop loop = reader.resource();
        try {
            byte[] buffer = new byte[512];
            while (state.canSend() && readerGenerations.isCurrent(reader)) {
                int length = loop.inputStream.read(buffer);
                if (length < 0) {
                    throw new IOException("串口读取结束");
                }
                if (length == 0) {
                    continue;
                }
                byte[] chunk = Arrays.copyOf(buffer, length);
                if (state.canSend() && readerGenerations.isCurrent(reader)) {
                    notifyReceived(chunk);
                }
            }
        } catch (Throwable throwable) {
            requestReaderFailureClose(reader, throwable);
        } finally {
            loop.termination.signalStopped();
            synchronized (resourceLock) {
                pendingReaderTerminations.remove(loop.termination);
                if (loop.thread == Thread.currentThread()) {
                    loop.thread = null;
                }
            }
        }
    }

    private void requestReaderFailureClose(
            ReaderGeneration.Lease<ReaderLoop> reader,
            Throwable failure) {
        if (state.isDisposed()) {
            return;
        }
        long[] closeToken = {0L};
        boolean current = readerGenerations.runIfCurrent(
                reader,
                () -> closeToken[0] = closeReopen.beginClose());
        if (!current || closeToken[0] == 0L) {
            return;
        }
        try {
            serialExecutor.execute(() -> closeAfterReaderFailure(
                    closeToken[0], reader, failure));
        } catch (RuntimeException rejection) {
            state.runIfClosing(closeToken[0], () -> notifyConnectionChanged(
                    false,
                    "串口读取失败且清理任务无法启动："
                            + readableMessage(failure)
                            + "，禁止重新打开"));
        }
    }

    private void closePortInternal(long closeToken) {
        DetachedResources resources = detachCurrentResources();
        finishClose(
                closeToken,
                resources,
                "串口已关闭",
                "串口关闭失败：读取线程未能安全停止，禁止重新打开");
    }

    private void closeAfterReaderFailure(
            long closeToken,
            ReaderGeneration.Lease<ReaderLoop> reader,
            Throwable failure) {
        DetachedResources resources = detachResourcesFor(reader);
        if (resources == null) {
            return;
        }
        String readable = readableMessage(failure);
        finishClose(
                closeToken,
                resources,
                "串口读取失败：" + readable,
                "串口读取失败且读取线程未能安全停止："
                        + readable + "，禁止重新打开");
    }

    private void finishClose(
            long closeToken,
            DetachedResources resources,
            String closedDetail,
            String failedDetail) {
        CloseReopenCoordinator.Result result = closeReopen.finishCloseThen(
                closeToken,
                resources,
                () -> notifyConnectionChanged(false, closedDetail));
        if (result == CloseReopenCoordinator.Result.READER_STILL_RUNNING) {
            state.runIfClosing(closeToken, () ->
                    notifyConnectionChanged(false, failedDetail));
        }
    }

    private void notifyConnectionChanged(boolean connected, String detail) {
        String safeDetail = detail == null ? "" : detail;
        boolean status = connected
                || safeDetail.startsWith("正在")
                || safeDetail.startsWith("串口已关闭");
        appendRuntimeLog((status ? "[Status] " : "[Error] ") + safeDetail);
        listeners.deliver(listener -> {
            try {
                listener.onConnectionChanged(connected, detail);
            } catch (Throwable ignored) {
                // A stale UI subscriber cannot disrupt the process-owned serial session.
            }
        });
    }

    private void notifyDiagnostic(String detail) {
        appendRuntimeLog("[Diagnostic] " + (detail == null ? "" : detail));
        listeners.deliver(listener -> {
            try {
                listener.onDiagnostic(detail);
            } catch (Throwable ignored) {
                // Diagnostic UI failures cannot stop serial work.
            }
        });
    }

    private void notifySent(byte[] bytes) {
        byte[] safePayload = Arrays.copyOf(bytes, bytes.length);
        appendRuntimeLog("[Send] " + HexCodec.format(
                Arrays.copyOf(safePayload, safePayload.length)));
        listeners.deliver(listener -> {
            try {
                listener.onSent(Arrays.copyOf(safePayload, safePayload.length));
            } catch (Throwable ignored) {
                // UI callback failures cannot stop serial work.
            }
        });
    }

    private void notifyReceived(byte[] bytes) {
        byte[] safePayload = Arrays.copyOf(bytes, bytes.length);
        appendRuntimeLog("[Read] " + HexCodec.format(
                Arrays.copyOf(safePayload, safePayload.length)));
        listeners.deliver(listener -> {
            try {
                listener.onReceived(Arrays.copyOf(safePayload, safePayload.length));
            } catch (Throwable ignored) {
                // UI callback failures cannot stop the reader.
            }
        });
    }

    private void notifySendFailed(byte[] payload, String detail) {
        byte[] safePayload = Arrays.copyOf(payload, payload.length);
        String safeDetail = detail == null ? "" : detail;
        appendRuntimeLog("[Error] [SendFailed] " + HexCodec.format(
                Arrays.copyOf(safePayload, safePayload.length)) + "  " + safeDetail);
        listeners.deliver(listener -> {
            try {
                listener.onSendFailed(
                        Arrays.copyOf(safePayload, safePayload.length), safeDetail);
            } catch (Throwable ignored) {
                // UI callback failures cannot stop serial work.
            }
        });
    }

    private synchronized void appendRuntimeLog(String message) {
        runtimeLog.append(timeFormat.format(new Date()) + "  " + message);
    }

    private PortResources currentResources() {
        synchronized (resourceLock) {
            return currentResources;
        }
    }

    private boolean isCurrent(PortResources candidate) {
        synchronized (resourceLock) {
            return currentResources == candidate
                    && readerGenerations.isCurrent(candidate.reader);
        }
    }

    private DetachedResources detachCurrentResources() {
        synchronized (resourceLock) {
            PortResources resources = currentResources;
            currentResources = null;
            if (resources == null) {
                return DetachedResources.empty();
            }
            readerGenerations.clear(resources.reader);
            ReaderLoop loop = resources.reader.resource();
            return new DetachedResources(
                    resources.serialPort,
                    loop.thread,
                    Collections.singletonList(loop.termination));
        }
    }

    private DetachedResources detachResourcesFor(
            ReaderGeneration.Lease<ReaderLoop> expectedReader) {
        synchronized (resourceLock) {
            PortResources resources = currentResources;
            if (resources == null
                    || resources.reader != expectedReader
                    || !readerGenerations.isCurrent(expectedReader)) {
                return null;
            }
            currentResources = null;
            readerGenerations.clear(expectedReader);
            ReaderLoop loop = expectedReader.resource();
            return new DetachedResources(
                    resources.serialPort,
                    loop.thread,
                    Collections.singletonList(loop.termination));
        }
    }

    private GatewayDisposalCoordinator.ResourceSnapshot detachForDisposal() {
        state.dispose();
        SerialPort port = null;
        Thread reader = null;
        List<ReaderTerminationGate> terminations;
        synchronized (resourceLock) {
            PortResources resources = currentResources;
            currentResources = null;
            if (resources != null) {
                readerGenerations.clear(resources.reader);
                ReaderLoop loop = resources.reader.resource();
                port = resources.serialPort;
                reader = loop.thread;
            }
            terminations = new ArrayList<>(pendingReaderTerminations);
            pendingReaderTerminations.clear();
        }
        return new DetachedResources(port, reader, terminations);
    }

    private static final class OpenPublication {
        ReaderGeneration.Lease<ReaderLoop> reader;
    }

    private static final class ReaderLoop {
        final InputStream inputStream;
        final ReaderTerminationGate termination;
        volatile Thread thread;

        ReaderLoop(InputStream inputStream, ReaderTerminationGate termination) {
            this.inputStream = inputStream;
            this.termination = termination;
        }
    }

    private static final class PortResources {
        final SerialPort serialPort;
        final OutputStream outputStream;
        final SerialConfig config;
        final ReaderGeneration.Lease<ReaderLoop> reader;

        PortResources(
                SerialPort serialPort,
                OutputStream outputStream,
                SerialConfig config,
                ReaderGeneration.Lease<ReaderLoop> reader) {
            this.serialPort = serialPort;
            this.outputStream = outputStream;
            this.config = config;
            this.reader = reader;
        }
    }

    private static final class DetachedResources
            implements CloseReopenCoordinator.DetachedResources,
            GatewayDisposalCoordinator.ResourceSnapshot {
        private final SerialPort serialPort;
        private final Thread reader;
        private final List<ReaderTerminationGate> terminations;

        DetachedResources(
                SerialPort serialPort,
                Thread reader,
                List<ReaderTerminationGate> terminations) {
            this.serialPort = serialPort;
            this.reader = reader;
            this.terminations = terminations;
        }

        static DetachedResources empty() {
            return new DetachedResources(null, null, Collections.emptyList());
        }

        @Override
        public void closeAndInterrupt() {
            closeQuietly(serialPort);
            if (reader != null && reader != Thread.currentThread()) {
                reader.interrupt();
            }
        }

        @Override
        public boolean awaitTermination() {
            long deadlineNanos = System.nanoTime()
                    + READER_TERMINATION_TIMEOUT_MILLIS * 1_000_000L;
            for (ReaderTerminationGate termination : terminations) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L
                        || !termination.awaitStopped(Math.max(
                                1L, remainingNanos / 1_000_000L))) {
                    return false;
                }
            }
            return true;
        }
    }

    private static void closeQuietly(SerialPort port) {
        if (port != null) {
            try {
                port.close();
            } catch (Throwable ignored) {
                // A native descriptor can already be detached by the driver.
            }
        }
    }

    private static boolean readySafely(BooleanSupplier ready) {
        try {
            return ready.getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String readableMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return throwable.getClass().getSimpleName();
        }
        return message;
    }
}
