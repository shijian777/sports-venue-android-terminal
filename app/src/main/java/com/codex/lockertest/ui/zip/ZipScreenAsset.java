package com.codex.lockertest.ui.zip;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Immutable visual asset contract for every v16 kiosk state. */
public enum ZipScreenAsset {
    HOME_WAITING(1, "zip_screen_01_home_waiting", ZipTemplate.HOME, ZipDynamicRegion.HOME_INPUTS, ZipActionRole.FACE, ZipActionRole.PALM, ZipActionRole.OPEN, ZipActionRole.RETURN_LOCKER, ZipActionRole.ENROLL),
    HOME_READING_CREDENTIAL(2, "zip_screen_02_home_reading_credential", ZipTemplate.HOME, ZipDynamicRegion.CREDENTIAL_STATUS, ZipActionRole.CANCEL),
    HOME_UNREGISTERED_COUNTDOWN(3, "zip_screen_03_home_unregistered_countdown", ZipTemplate.HOME, ZipDynamicRegion.CREDENTIAL_STATUS, ZipActionRole.RETRY),
    HOME_CREDENTIAL_ERROR(4, "zip_screen_04_home_credential_error", ZipTemplate.HOME, ZipDynamicRegion.CREDENTIAL_STATUS, ZipActionRole.CONFIRM),
    FACE_PREPARING(5, "zip_screen_05_face_preparing", ZipTemplate.BIOMETRIC, ZipDynamicRegion.FACE_PREVIEW, ZipActionRole.BACK),
    FACE_DETECTING(6, "zip_screen_06_face_detecting", ZipTemplate.BIOMETRIC, ZipDynamicRegion.FACE_PREVIEW, ZipActionRole.BACK),
    FACE_UPLOADING(7, "zip_screen_07_face_uploading", ZipTemplate.BIOMETRIC, ZipDynamicRegion.FACE_PREVIEW, ZipActionRole.BACK),
    FACE_CAMERA_PERMISSION_TEMPORARY(8, "zip_screen_08_face_camera_permission_temporary", ZipTemplate.PROMPT, ZipDynamicRegion.FACE_PREVIEW, ZipActionRole.RETRY, ZipActionRole.BACK),
    FACE_CAMERA_PERMISSION_PERMANENT(9, "zip_screen_09_face_camera_permission_permanent", ZipTemplate.PROMPT, ZipDynamicRegion.FACE_PREVIEW, ZipActionRole.HOME),
    FACE_RETRYABLE_FAILURE(10, "zip_screen_10_face_retryable_failure", ZipTemplate.PROMPT, ZipDynamicRegion.FACE_PREVIEW, ZipActionRole.RETRY, ZipActionRole.BACK),
    FACE_COMPONENT_UNAVAILABLE(11, "zip_screen_11_face_component_unavailable", ZipTemplate.PROMPT, ZipDynamicRegion.FACE_PREVIEW, ZipActionRole.BACK),
    PALM_GUIDE(12, "zip_screen_12_palm_guide", ZipTemplate.BIOMETRIC, ZipDynamicRegion.FACE_PREVIEW, ZipActionRole.CONFIRM, ZipActionRole.BACK),
    PALM_DEVICE_UNAVAILABLE(13, "zip_screen_13_palm_device_unavailable", ZipTemplate.PROMPT, ZipDynamicRegion.FACE_PREVIEW, ZipActionRole.BACK),
    LOCKER_DISCOVERING(14, "zip_screen_14_locker_discovering", ZipTemplate.LOCKER, ZipDynamicRegion.LOCKER_GRID, ZipActionRole.BACK),
    LOCKER_NO_AREA(15, "zip_screen_15_locker_no_area", ZipTemplate.PROMPT, ZipDynamicRegion.LOCKER_GRID, ZipActionRole.BACK),
    LOCKER_UNSELECTED(16, "zip_screen_16_locker_unselected", ZipTemplate.LOCKER, ZipDynamicRegion.LOCKER_GRID, ZipActionRole.BACK, ZipActionRole.SELECT, ZipActionRole.CONFIRM),
    LOCKER_SELECTED(17, "zip_screen_17_locker_selected", ZipTemplate.LOCKER, ZipDynamicRegion.LOCKER_GRID, ZipActionRole.BACK, ZipActionRole.SELECT, ZipActionRole.CONFIRM),
    LOCKER_SELECTION_ERROR(18, "zip_screen_18_locker_selection_error", ZipTemplate.PROMPT, ZipDynamicRegion.LOCKER_GRID, ZipActionRole.RETRY, ZipActionRole.BACK),
    UNLOCK_VALIDATING(19, "zip_screen_19_unlock_validating", ZipTemplate.PROMPT, ZipDynamicRegion.LOCKER_GRID, ZipActionRole.NONE),
    UNLOCK_CONNECTING(20, "zip_screen_20_unlock_connecting", ZipTemplate.PROMPT, ZipDynamicRegion.LOCKER_GRID, ZipActionRole.NONE),
    UNLOCK_WAITING_RESPONSE(21, "zip_screen_21_unlock_waiting_response", ZipTemplate.PROMPT, ZipDynamicRegion.LOCKER_GRID, ZipActionRole.NONE),
    UNLOCK_SUCCESS(22, "zip_screen_22_unlock_success", ZipTemplate.PROMPT, ZipDynamicRegion.LOCKER_GRID, ZipActionRole.HOME),
    UNLOCK_BOARD_REJECTED(23, "zip_screen_23_unlock_board_rejected", ZipTemplate.PROMPT, ZipDynamicRegion.LOCKER_GRID, ZipActionRole.RETRY, ZipActionRole.HOME),
    UNLOCK_DEVICE_CONNECTION_FAILED(24, "zip_screen_24_unlock_device_connection_failed", ZipTemplate.PROMPT, ZipDynamicRegion.LOCKER_GRID, ZipActionRole.RETRY, ZipActionRole.HOME),
    UNLOCK_SEND_FAILED(25, "zip_screen_25_unlock_send_failed", ZipTemplate.PROMPT, ZipDynamicRegion.LOCKER_GRID, ZipActionRole.RETRY, ZipActionRole.HOME),
    UNLOCK_COMMUNICATION_TIMEOUT(26, "zip_screen_26_unlock_communication_timeout", ZipTemplate.PROMPT, ZipDynamicRegion.LOCKER_GRID, ZipActionRole.RETRY, ZipActionRole.HOME),
    RETURN_AUTH_READY(27, "zip_screen_27_return_auth_ready", ZipTemplate.AUTH, ZipDynamicRegion.RETURN_AUTH, ZipActionRole.FACE, ZipActionRole.PALM, ZipActionRole.BACK),
    RETURN_AUTH_QUERYING(28, "zip_screen_28_return_auth_querying", ZipTemplate.PROMPT, ZipDynamicRegion.RETURN_AUTH, ZipActionRole.NONE),
    RETURN_AUTH_FAILED(29, "zip_screen_29_return_auth_failed", ZipTemplate.PROMPT, ZipDynamicRegion.RETURN_AUTH, ZipActionRole.RETRY, ZipActionRole.HOME),
    RETURN_AUTH_NETWORK_ERROR(30, "zip_screen_30_return_auth_network_error", ZipTemplate.PROMPT, ZipDynamicRegion.RETURN_AUTH, ZipActionRole.RETRY, ZipActionRole.HOME),
    RETURN_LOCKER_UNSELECTED(31, "zip_screen_31_return_locker_unselected", ZipTemplate.LOCKER, ZipDynamicRegion.RETURN_LIST, ZipActionRole.BACK, ZipActionRole.SELECT),
    RETURN_LOCKER_SELECTED(32, "zip_screen_32_return_locker_selected", ZipTemplate.LOCKER, ZipDynamicRegion.RETURN_LIST, ZipActionRole.BACK, ZipActionRole.SELECT, ZipActionRole.RETURN_LOCKER),
    RETURN_LOCKER_PROCESSING(33, "zip_screen_33_return_locker_processing", ZipTemplate.PROMPT, ZipDynamicRegion.RETURN_LIST, ZipActionRole.NONE),
    RETURN_LOCKER_EMPTY(34, "zip_screen_34_return_locker_empty", ZipTemplate.DETAIL, ZipDynamicRegion.RETURN_LIST, ZipActionRole.HOME),
    RETURN_OPENING(35, "zip_screen_35_return_opening", ZipTemplate.PROMPT, ZipDynamicRegion.RETURN_PROGRESS, ZipActionRole.NONE),
    RETURN_WAITING_FOR_CLOSE(36, "zip_screen_36_return_waiting_for_close", ZipTemplate.PROMPT, ZipDynamicRegion.RETURN_PROGRESS, ZipActionRole.CONFIRM),
    RETURN_COMMITTING(37, "zip_screen_37_return_committing", ZipTemplate.PROMPT, ZipDynamicRegion.RETURN_PROGRESS, ZipActionRole.NONE),
    RETURN_SUCCESS(38, "zip_screen_38_return_success", ZipTemplate.PROMPT, ZipDynamicRegion.RETURN_PROGRESS, ZipActionRole.HOME),
    RETURN_FAILED(39, "zip_screen_39_return_failed", ZipTemplate.PROMPT, ZipDynamicRegion.RETURN_PROGRESS, ZipActionRole.RETRY, ZipActionRole.HOME),
    ADMIN_PIN_ENTRY(40, "zip_screen_40_admin_pin_entry", ZipTemplate.AUTH, ZipDynamicRegion.ADMIN_PIN, ZipActionRole.CONFIRM, ZipActionRole.BACK),
    ADMIN_PIN_ERROR(41, "zip_screen_41_admin_pin_error", ZipTemplate.AUTH, ZipDynamicRegion.ADMIN_PIN, ZipActionRole.CONFIRM, ZipActionRole.BACK),
    ADMIN_FUNCTIONS(42, "zip_screen_42_admin_functions", ZipTemplate.ADMIN, ZipDynamicRegion.ADMIN_PANEL, ZipActionRole.SERIAL_ADMIN, ZipActionRole.FACE_SDK_ADMIN, ZipActionRole.ENROLL, ZipActionRole.HOME),
    ADMIN_SERIAL_DISCONNECTED(43, "zip_screen_43_admin_serial_disconnected", ZipTemplate.ADMIN, ZipDynamicRegion.SERIAL_PANEL, ZipActionRole.SERIAL_OPEN, ZipActionRole.BACK),
    ADMIN_SERIAL_OPENING(44, "zip_screen_44_admin_serial_opening", ZipTemplate.ADMIN, ZipDynamicRegion.SERIAL_PANEL, ZipActionRole.NONE),
    ADMIN_SERIAL_CONNECTED(45, "zip_screen_45_admin_serial_connected", ZipTemplate.ADMIN, ZipDynamicRegion.SERIAL_PANEL, ZipActionRole.SERIAL_CLOSE, ZipActionRole.SERIAL_TEST, ZipActionRole.BACK),
    ADMIN_SERIAL_SUCCESS(46, "zip_screen_46_admin_serial_success", ZipTemplate.PROMPT, ZipDynamicRegion.SERIAL_PANEL, ZipActionRole.CONFIRM),
    ADMIN_SERIAL_FAILURE(47, "zip_screen_47_admin_serial_failure", ZipTemplate.PROMPT, ZipDynamicRegion.SERIAL_PANEL, ZipActionRole.CONFIRM, ZipActionRole.BACK),
    FACE_SDK_CHECKING_LICENSE(48, "zip_screen_48_face_sdk_checking_license", ZipTemplate.ADMIN, ZipDynamicRegion.SDK_PANEL, ZipActionRole.BACK),
    FACE_SDK_AWAITING_ACTIVATION(49, "zip_screen_49_face_sdk_awaiting_activation", ZipTemplate.AUTH, ZipDynamicRegion.SDK_PANEL, ZipActionRole.SDK_ACTIVATE, ZipActionRole.BACK),
    FACE_SDK_ACTIVATING(50, "zip_screen_50_face_sdk_activating", ZipTemplate.PROMPT, ZipDynamicRegion.SDK_PANEL, ZipActionRole.NONE),
    FACE_SDK_LICENSED(51, "zip_screen_51_face_sdk_licensed", ZipTemplate.PROMPT, ZipDynamicRegion.SDK_PANEL, ZipActionRole.NONE),
    FACE_SDK_INITIALIZING(52, "zip_screen_52_face_sdk_initializing", ZipTemplate.PROMPT, ZipDynamicRegion.SDK_PANEL, ZipActionRole.NONE),
    FACE_SDK_READY(53, "zip_screen_53_face_sdk_ready", ZipTemplate.ADMIN, ZipDynamicRegion.SDK_PANEL, ZipActionRole.LIVENESS_TOGGLE, ZipActionRole.BACK),
    FACE_SDK_ERROR(54, "zip_screen_54_face_sdk_error", ZipTemplate.PROMPT, ZipDynamicRegion.SDK_PANEL, ZipActionRole.RETRY, ZipActionRole.BACK),
    ENROLLMENT_CHOICE(55, "zip_screen_55_enrollment_choice", ZipTemplate.ADMIN, ZipDynamicRegion.ENROLLMENT_PANEL, ZipActionRole.FACE, ZipActionRole.PALM, ZipActionRole.BACK),
    FACE_ENROLLMENT_CAPTURING(56, "zip_screen_56_face_enrollment_capturing", ZipTemplate.BIOMETRIC, ZipDynamicRegion.FACE_PREVIEW, ZipActionRole.BACK),
    PALM_ENROLLMENT_UNAVAILABLE(57, "zip_screen_57_palm_enrollment_unavailable", ZipTemplate.PROMPT, ZipDynamicRegion.ENROLLMENT_PANEL, ZipActionRole.BACK);

    private final int id;
    private final String drawableName;
    private final ZipTemplate template;
    private final ZipDynamicRegion dynamicRegion;
    private final Set<ZipActionRole> actions;

    ZipScreenAsset(int id, String drawableName, ZipTemplate template,
            ZipDynamicRegion dynamicRegion, ZipActionRole... actions) {
        this.id = id;
        this.drawableName = drawableName;
        this.template = template;
        this.dynamicRegion = dynamicRegion;
        this.actions = Collections.unmodifiableSet(EnumSet.copyOf(Arrays.asList(actions)));
    }

    public int id() { return id; }

    public String drawableName() { return drawableName; }

    public ZipTemplate template() { return template; }

    public ZipDynamicRegion dynamicRegion() { return dynamicRegion; }

    public Set<ZipActionRole> actions() { return actions; }
}
