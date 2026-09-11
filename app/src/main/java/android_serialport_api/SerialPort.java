package android_serialport_api;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class SerialPort {
    private FileDescriptor mFd;
    private FileInputStream mFileInputStream;
    private FileOutputStream mFileOutputStream;

    public SerialPort(
            File device,
            int baudRate,
            int parity,
            int dataBits,
            int stopBits,
            int flags) throws IOException {
        ensureReadableAndWritable(device);
        mFd = open(
                device.getAbsolutePath(),
                baudRate,
                parity,
                dataBits,
                stopBits,
                flags);
        if (mFd == null) {
            throw new IOException("JNI 打开或配置串口失败（native open returns null）");
        }
        mFileInputStream = new FileInputStream(mFd);
        mFileOutputStream = new FileOutputStream(mFd);
    }

    public InputStream getInputStream() {
        return mFileInputStream;
    }

    public OutputStream getOutputStream() {
        return mFileOutputStream;
    }

    private static void ensureReadableAndWritable(File device) throws IOException {
        if (!device.exists()) {
            throw new IOException("串口设备不存在: " + device.getAbsolutePath());
        }
        if (device.canRead() && device.canWrite()) {
            return;
        }

        Process process = null;
        try {
            process = Runtime.getRuntime().exec("/system/bin/su");
            OutputStream commandStream = process.getOutputStream();
            String command = "chmod 666 " + device.getAbsolutePath() + "\nexit\n";
            commandStream.write(command.getBytes("UTF-8"));
            commandStream.flush();
            commandStream.close();
            int exitCode = process.waitFor();
            if (exitCode != 0 || !device.canRead() || !device.canWrite()) {
                throw new SecurityException("串口无读写权限: " + device.getAbsolutePath());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("串口授权被中断", exception);
        } catch (SecurityException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SecurityException("串口无读写权限: " + device.getAbsolutePath(), exception);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static native FileDescriptor open(
            String path,
            int baudRate,
            int parity,
            int dataBits,
            int stopBits,
            int flags);

    public native void close();

    static {
        System.loadLibrary("serial_port");
    }
}
