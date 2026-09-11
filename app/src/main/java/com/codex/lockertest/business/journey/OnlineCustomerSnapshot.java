package com.codex.lockertest.business.journey;

import com.codex.lockertest.business.journey.OnlineCustomerCoordinator.Purpose;
import com.codex.lockertest.unlock.UnlockCoordinator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable, command-free presentation state for the production customer journey. */
public final class OnlineCustomerSnapshot {
    public static final int MAX_DISPLAY_ROWS = 4;
    public static final int SLOTS_PER_DISPLAY_ROW = 8;
    public static final int DISPLAY_PAGE_CAPACITY = MAX_DISPLAY_ROWS * SLOTS_PER_DISPLAY_ROW;
    public static final String PHYSICAL_ACTION_UNAVAILABLE_REASON =
            "服务器开柜/还柜事务闭环尚未确认，当前仅可查看。";

    public enum State {
        IDLE,
        AUTHENTICATING,
        LOADING_PREVIEW,
        LOADING_USED,
        BROWSING_OPEN,
        BROWSING_USED,
        AUTHORIZING_OPEN,
        OPENING,
        OPEN_SUCCESS,
        OPEN_FAILED,
        RETURNING,
        RETURN_SUCCEEDED,
        RETURN_FAILED,
        FAILED,
        CANCELLED,
        CLOSED
    }

    public enum Failure {
        NONE,
        INVALID_SESSION,
        CUSTOMER_IDENTITY_REQUIRED,
        NO_ENTRY_RECORD,
        REMOTE_REJECTED,
        SERVICE_UNAVAILABLE,
        SERVER_ERROR,
        TIMEOUT,
        INVALID_SERVER_LAYOUT,
        CONFIGURATION,
        OPEN_AUTHORIZATION_FAILED,
        PHYSICAL_OPEN_FAILED,
        CANCELLED;

        public String message() {
            switch (this) {
                case CUSTOMER_IDENTITY_REQUIRED: return "身份验证未通过，请返回首页重新输入";
                case NO_ENTRY_RECORD: return "暂无进场记录，请联系前台确认入场后重试";
                case REMOTE_REJECTED: return "服务器未通过本次请求，请核对信息或联系前台";
                case TIMEOUT: return "服务器响应超时，请重新尝试";
                case INVALID_SERVER_LAYOUT: return "服务器返回的数据格式不完整，请联系管理员";
                case SERVER_ERROR: return "服务器处理请求异常，请联系前台稍后重试";
                case INVALID_SESSION: return "终端连接已变化，请返回首页重新验证";
                case CONFIGURATION: return "此功能接入配置尚未完成";
                case CANCELLED: return "操作已取消，请返回首页";
                default: return "服务器暂不可用，请检查网络后重试";
            }
        }
    }

    private final State state;
    private final Failure failure;
    private final Purpose purpose;
    private final long bootstrapGeneration;
    private final List<OnlineCustomerSession.Region> regions;
    private final OnlineCustomerSession.Region selectedRegion;
    private final List<Integer> pages;
    private final int selectedPage;
    private final List<Row> rows;
    private final List<List<Slot>> displayRows;
    private final List<UsedCabinetView> usedCabinets;
    private final boolean pendingCredential;
    private final boolean physicalOpenEnabled;
    private final UnlockCoordinator.State physicalState;
    private final String physicalDetail;
    private final String selectedCabinetLabel;

    OnlineCustomerSnapshot(
            State state,
            Failure failure,
            Purpose purpose,
            long bootstrapGeneration,
            List<OnlineCustomerSession.Region> regions,
            OnlineCustomerSession.Region selectedRegion,
            List<Integer> pages,
            int selectedPage,
            List<Row> rows,
            List<UsedCabinetView> usedCabinets,
            boolean pendingCredential,
            boolean physicalOpenEnabled,
            UnlockCoordinator.State physicalState,
            String physicalDetail,
            String selectedCabinetLabel) {
        if (state == null || failure == null || regions == null || pages == null
                || rows == null || usedCabinets == null
                || physicalDetail == null || selectedCabinetLabel == null) {
            throw new IllegalArgumentException("Customer snapshot is invalid");
        }
        this.state = state;
        this.failure = failure;
        this.purpose = purpose;
        this.bootstrapGeneration = bootstrapGeneration;
        this.regions = immutableCopy(regions);
        this.selectedRegion = selectedRegion;
        this.pages = immutableCopy(pages);
        this.selectedPage = selectedPage;
        this.rows = immutableCopy(rows);
        this.displayRows = projectDisplayRows(this.rows);
        this.usedCabinets = immutableCopy(usedCabinets);
        this.pendingCredential = pendingCredential;
        this.physicalOpenEnabled = physicalOpenEnabled;
        this.physicalState = physicalState;
        this.physicalDetail = physicalDetail;
        this.selectedCabinetLabel = selectedCabinetLabel;
    }

    static OnlineCustomerSnapshot idle() {
        return new OnlineCustomerSnapshot(
                State.IDLE, Failure.NONE, null, 0L,
                Collections.<OnlineCustomerSession.Region>emptyList(), null,
                Collections.<Integer>emptyList(), 0,
                Collections.<Row>emptyList(),
                Collections.<UsedCabinetView>emptyList(), false,
                false, null, "", "");
    }

    public State state() { return state; }
    public Failure failure() { return failure; }
    public Purpose purpose() { return purpose; }
    public long bootstrapGeneration() { return bootstrapGeneration; }
    public List<OnlineCustomerSession.Region> regions() { return regions; }
    public OnlineCustomerSession.Region selectedRegion() { return selectedRegion; }
    public List<Integer> pages() { return pages; }
    public int selectedPage() { return selectedPage; }
    /** Physical layers, keys and positions in the exact server-provided order. */
    public List<Row> rows() { return rows; }
    /** Screen-only row grouping; each slot still carries its original physical position. */
    public List<List<Slot>> displayRows() { return displayRows; }
    public List<UsedCabinetView> usedCabinets() { return usedCabinets; }
    public boolean hasPendingCredential() { return pendingCredential; }
    public boolean physicalOpenEnabled() { return physicalOpenEnabled; }
    public UnlockCoordinator.State physicalState() { return physicalState; }
    public String physicalDetail() { return physicalDetail; }
    public String selectedCabinetLabel() { return selectedCabinetLabel; }
    public boolean isBusy() {
        return state == State.AUTHENTICATING
                || state == State.LOADING_PREVIEW
                || state == State.LOADING_USED
                || state == State.AUTHORIZING_OPEN
                || state == State.OPENING
                || state == State.RETURNING;
    }
    public String physicalActionUnavailableReason() {
        return PHYSICAL_ACTION_UNAVAILABLE_REASON;
    }

    @Override public String toString() {
        return "OnlineCustomerSnapshot{state=" + state
                + ", failure=" + failure
                + ", generation=" + bootstrapGeneration
                + ", regions=" + regions.size()
                + ", pages=" + pages.size()
                + ", rows=" + rows.size()
                + ", usedCabinets=" + usedCabinets.size()
                + ", pendingCredential=" + pendingCredential
                + ", physicalOpenEnabled=" + physicalOpenEnabled
                + ", physicalState=" + physicalState + "}";
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static List<List<Slot>> projectDisplayRows(List<Row> physicalRows) {
        List<List<Slot>> projected = new ArrayList<>();
        List<Slot> allSlots = new ArrayList<>();
        for (Row row : physicalRows) {
            allSlots.addAll(row.slots());
            // Keep existing sparse 4x8 layouts, including explicitly empty physical rows.
            if (row.slots().isEmpty()) projected.add(Collections.<Slot>emptyList());
            appendDisplayRows(projected, row.slots());
        }
        if (projected.size() > MAX_DISPLAY_ROWS) {
            // Physical layer boundaries are metadata, not a reason to hide valid cabinets.
            projected.clear();
            appendDisplayRows(projected, allSlots);
        }
        return immutableCopy(projected);
    }

    private static void appendDisplayRows(List<List<Slot>> destination, List<Slot> slots) {
        for (int offset = 0; offset < slots.size(); offset += SLOTS_PER_DISPLAY_ROW) {
            destination.add(immutableCopy(slots.subList(
                    offset, Math.min(offset + SLOTS_PER_DISPLAY_ROW, slots.size()))));
        }
    }

    public static final class Row {
        private final int layerKey;
        private final List<Slot> slots;

        Row(int layerKey, List<Slot> slots) {
            this.layerKey = layerKey;
            this.slots = immutableCopy(slots);
        }

        public int layerKey() { return layerKey; }
        public List<Slot> slots() { return slots; }
    }

    public static final class Slot {
        private final int position;
        private final long fcId;
        private final long channelId;
        private final String cabinetLabel;
        private final int status;
        private final long areaId;
        private final int checkStatus;

        Slot(
                int position,
                long fcId,
                long channelId,
                String cabinetLabel,
                int status,
                long areaId,
                int checkStatus) {
            this.position = position;
            this.fcId = fcId;
            this.channelId = channelId;
            this.cabinetLabel = cabinetLabel;
            this.status = status;
            this.areaId = areaId;
            this.checkStatus = checkStatus;
        }

        public int position() { return position; }
        public long fcId() { return fcId; }
        public long channelId() { return channelId; }
        public String cabinetLabel() { return cabinetLabel; }
        public int status() { return status; }
        public long areaId() { return areaId; }
        public int checkStatus() { return checkStatus; }
    }

    public static final class UsedCabinetView {
        private final long recordId;
        private final long fcId;
        private final String name;
        private final String startUse;
        private final int useTimeMinutes;
        private final String useNotice;

        UsedCabinetView(
                long recordId,
                long fcId,
                String name,
                String startUse,
                int useTimeMinutes,
                String useNotice) {
            this.recordId = recordId;
            this.fcId = fcId;
            this.name = name;
            this.startUse = startUse;
            this.useTimeMinutes = useTimeMinutes;
            this.useNotice = useNotice;
        }

        public long recordId() { return recordId; }
        public long fcId() { return fcId; }
        public String name() { return name; }
        public String startUse() { return startUse; }
        public int useTimeMinutes() { return useTimeMinutes; }
        public String useNotice() { return useNotice; }
    }
}
