package com.codex.lockertest.ui.zip;

import com.codex.lockertest.ui.LockerSelectionModel;

/** Android-free mapping from existing locker business state to v16 visual assets. */
public final class ZipLockerScreenRouter {
    public enum SelectorState {
        DISCOVERING,
        NO_AREA,
        UNSELECTED,
        SELECTED,
        SELECTION_ERROR
    }

    public enum ResultState {
        VALIDATING,
        CONNECTING,
        WAITING_RESPONSE,
        SUCCESS,
        BOARD_REJECTED,
        DEVICE_CONNECTION_FAILED,
        SEND_FAILED,
        COMMUNICATION_TIMEOUT
    }

    public enum ResultActions {
        NONE,
        HOME,
        RETRY_AND_HOME
    }

    private ZipLockerScreenRouter() {
    }

    public static SelectorState selectorState(
            LockerSelectionModel.DiscoveryState discoveryState,
            boolean hasSelectableArea,
            boolean hasSelection,
            CharSequence selectionError) {
        if (discoveryState == null) {
            throw new IllegalArgumentException("discoveryState must not be null");
        }
        if (!isBlank(selectionError)) {
            return SelectorState.SELECTION_ERROR;
        }
        switch (discoveryState) {
            case DETECTING:
                return SelectorState.DISCOVERING;
            case NO_ZONES:
                return SelectorState.NO_AREA;
            case READY:
                if (!hasSelectableArea) {
                    return SelectorState.NO_AREA;
                }
                return hasSelection
                        ? SelectorState.SELECTED : SelectorState.UNSELECTED;
            default:
                throw new IllegalArgumentException(
                        "Unsupported discovery state: " + discoveryState);
        }
    }

    public static ZipScreenAsset assetFor(SelectorState state) {
        if (state == null) {
            throw new IllegalArgumentException("selector state must not be null");
        }
        switch (state) {
            case DISCOVERING:
                return ZipScreenAsset.LOCKER_DISCOVERING;
            case NO_AREA:
                return ZipScreenAsset.LOCKER_NO_AREA;
            case UNSELECTED:
                return ZipScreenAsset.LOCKER_UNSELECTED;
            case SELECTED:
                return ZipScreenAsset.LOCKER_SELECTED;
            case SELECTION_ERROR:
                return ZipScreenAsset.LOCKER_SELECTION_ERROR;
            default:
                throw new IllegalArgumentException("Unsupported selector state: " + state);
        }
    }

    public static ZipScreenAsset assetFor(ResultState state) {
        if (state == null) {
            throw new IllegalArgumentException("result state must not be null");
        }
        switch (state) {
            case VALIDATING:
                return ZipScreenAsset.UNLOCK_VALIDATING;
            case CONNECTING:
                return ZipScreenAsset.UNLOCK_CONNECTING;
            case WAITING_RESPONSE:
                return ZipScreenAsset.UNLOCK_WAITING_RESPONSE;
            case SUCCESS:
                return ZipScreenAsset.UNLOCK_SUCCESS;
            case BOARD_REJECTED:
                return ZipScreenAsset.UNLOCK_BOARD_REJECTED;
            case DEVICE_CONNECTION_FAILED:
                return ZipScreenAsset.UNLOCK_DEVICE_CONNECTION_FAILED;
            case SEND_FAILED:
                return ZipScreenAsset.UNLOCK_SEND_FAILED;
            case COMMUNICATION_TIMEOUT:
                return ZipScreenAsset.UNLOCK_COMMUNICATION_TIMEOUT;
            default:
                throw new IllegalArgumentException("Unsupported result state: " + state);
        }
    }

    public static ResultActions actionsFor(ResultState state) {
        if (state == null) {
            throw new IllegalArgumentException("result state must not be null");
        }
        switch (state) {
            case VALIDATING:
            case CONNECTING:
            case WAITING_RESPONSE:
                return ResultActions.NONE;
            case SUCCESS:
                return ResultActions.HOME;
            case BOARD_REJECTED:
            case DEVICE_CONNECTION_FAILED:
            case SEND_FAILED:
            case COMMUNICATION_TIMEOUT:
                return ResultActions.RETRY_AND_HOME;
            default:
                throw new IllegalArgumentException("Unsupported result state: " + state);
        }
    }

    public static String messageFor(ResultState state, String displayLabel) {
        if (state == null) {
            throw new IllegalArgumentException("result state must not be null");
        }
        switch (state) {
            case VALIDATING:
                return "正在验证权限，请稍候…";
            case CONNECTING:
                return "正在连接设备，请稍候…";
            case WAITING_RESPONSE:
                return "正在等待锁板返回，请勿重复操作…";
            case SUCCESS:
                return requireDisplayLabel(displayLabel)
                        + "号柜门已打开，请存放物品后关闭柜门。";
            case BOARD_REJECTED:
                return requireDisplayLabel(displayLabel)
                        + "号柜门开启失败，请再次尝试。";
            case DEVICE_CONNECTION_FAILED:
                return "设备连接失败，请联系管理员。";
            case SEND_FAILED:
                return "开柜指令发送失败，请再次尝试。";
            case COMMUNICATION_TIMEOUT:
                return "设备无响应，请再次尝试。";
            default:
                throw new IllegalArgumentException("Unsupported result state: " + state);
        }
    }

    private static String requireDisplayLabel(String displayLabel) {
        if (isBlank(displayLabel)) {
            throw new IllegalArgumentException("displayLabel must not be blank");
        }
        return displayLabel.trim();
    }

    private static boolean isBlank(CharSequence value) {
        if (value == null || value.length() == 0) {
            return true;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isWhitespace(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }
}
