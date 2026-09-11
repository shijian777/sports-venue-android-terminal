package com.codex.lockertest.bootstrap;

import com.codex.lockertest.server.JsonNumber;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BaseSettingResponseParser
        extends CentralControlResponseParser<BaseSettingSnapshot> {
    private static final int MAX_GENERAL_TEXT_LENGTH = 32768;
    private static final int MAX_MEDIA_TEXT_LENGTH = 2048;
    private static final int MAX_AREA_NAME_LENGTH = 256;
    private static final int MAX_ACTIVATION_CODE_LENGTH = 256;
    private static final int MAX_VOICE_FILES = 32;
    private static final int MAX_VOICE_LABEL_LENGTH = 128;

    @Override
    protected BaseSettingSnapshot parseSuccessData(Map<String, Object> data) {
        optionalKeys(data,
                "venue_name", "version", "recognition_type", "use_notice",
                "return_notice", "logo", "company", "cozy_tips",
                "advertisement_type", "advertisement_list", "background_img",
                "palm_print_img", "area_list");
        int advertisementType = canonicalInt(data.get("advertisement_type"), 0, 1);
        return new BaseSettingSnapshot(
                generalText(data, "venue_name"),
                generalText(data, "version"),
                recognitionTypes(data.get("recognition_type")),
                noticeText(data, "use_notice"),
                noticeText(data, "return_notice"),
                mediaText(data, "logo"),
                generalText(data, "company"),
                noticeText(data, "cozy_tips"),
                advertisementType,
                advertisements(data.get("advertisement_list"), advertisementType),
                mediaText(data, "background_img"),
                mediaText(data, "palm_print_img"),
                areas(data.get("area_list")),
                verificationStatus(data),
                data.containsKey("activation_code")
                        ? text(data.get("activation_code"),
                                MAX_ACTIVATION_CODE_LENGTH, true) : "",
                data.containsKey("voice")
                        ? voiceFiles(data.get("voice"))
                        : Collections.<String, String>emptyMap());
    }

    private static List<String> recognitionTypes(Object value) {
        List<Object> raw = array(value);
        List<String> result = new ArrayList<>(raw.size());
        Set<String> unique = new HashSet<>();
        for (Object item : raw) {
            String type = text(item, 1, false);
            if (type.charAt(0) < '1' || type.charAt(0) > '5' || !unique.add(type)) {
                throw contract();
            }
            result.add(type);
        }
        return result;
    }

    private static List<BaseSettingSnapshot.Advertisement> advertisements(
            Object value, int advertisementType) {
        List<Object> raw = array(value);
        int maximum = advertisementType == 0 ? 32 : 1;
        if (raw.size() > maximum) throw contract();
        List<BaseSettingSnapshot.Advertisement> result = new ArrayList<>(raw.size());
        for (Object item : raw) {
            Map<String, Object> advertisement = object(item);
            exactKeys(advertisement, "image", "video");
            result.add(new BaseSettingSnapshot.Advertisement(
                    text(advertisement.get("image"), MAX_MEDIA_TEXT_LENGTH, true),
                    text(advertisement.get("video"), MAX_MEDIA_TEXT_LENGTH, true)));
        }
        return result;
    }

    private static List<BaseSettingSnapshot.Area> areas(Object value) {
        List<Object> raw = array(value);
        List<BaseSettingSnapshot.Area> result = new ArrayList<>(raw.size());
        for (Object item : raw) {
            Map<String, Object> area = object(item);
            exactKeys(area, "area_id", "name");
            result.add(new BaseSettingSnapshot.Area(
                    areaId(area.get("area_id")),
                    text(area.get("name"), MAX_AREA_NAME_LENGTH, false)));
        }
        return result;
    }

    private static int areaId(Object value) {
        if (value instanceof JsonNumber) {
            return canonicalInt(value, 1, Integer.MAX_VALUE);
        }
        String raw = text(value, 10, false);
        if (raw.charAt(0) < '1' || raw.charAt(0) > '9') throw contract();
        long result = 0;
        for (int index = 0; index < raw.length(); index++) {
            char digit = raw.charAt(index);
            if (digit < '0' || digit > '9') throw contract();
            result = result * 10 + digit - '0';
            if (result > Integer.MAX_VALUE) throw contract();
        }
        return (int) result;
    }

    private static String lockerStatus(Object value) {
        if (value instanceof JsonNumber) {
            return Integer.toString(canonicalInt(value, 0, 2));
        }
        String status = text(value, 1, false);
        if (!"0".equals(status) && !"1".equals(status) && !"2".equals(status)) {
            throw contract();
        }
        return status;
    }

    private static String verificationStatus(Map<String, Object> data) {
        boolean renamed = data.containsKey("verification_result_status");
        boolean legacy = data.containsKey("locker_check_status");
        if (!renamed && !legacy) throw contract();
        String renamedStatus = renamed
                ? lockerStatus(data.get("verification_result_status")) : null;
        String legacyStatus = legacy
                ? lockerStatus(data.get("locker_check_status")) : null;
        if (renamed && legacy && !renamedStatus.equals(legacyStatus)) throw contract();
        return renamed ? renamedStatus : legacyStatus;
    }

    private static Map<String, String> voiceFiles(Object value) {
        if (value instanceof List) {
            if (!array(value).isEmpty()) throw contract();
            return Collections.emptyMap();
        }
        Map<String, Object> raw = object(value);
        if (raw.size() > MAX_VOICE_FILES) throw contract();
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String label = text(entry.getKey(), MAX_VOICE_LABEL_LENGTH, false);
            String url = text(entry.getValue(), MAX_MEDIA_TEXT_LENGTH, false);
            result.put(label, url);
        }
        return result;
    }

    private static void optionalKeys(Map<String, Object> data, String... required) {
        Set<String> allowed = new HashSet<>(Arrays.asList(required));
        allowed.add("activation_code");
        allowed.add("voice");
        allowed.add("verification_result_status");
        allowed.add("locker_check_status");
        for (String key : required) {
            if (!data.containsKey(key)) throw contract();
        }
        for (String key : data.keySet()) {
            if (!allowed.contains(key)) throw contract();
        }
    }

    private static String generalText(Map<String, Object> data, String key) {
        return text(data.get(key), MAX_GENERAL_TEXT_LENGTH, true);
    }

    private static String noticeText(Map<String, Object> data, String key) {
        return formattedText(data.get(key), MAX_GENERAL_TEXT_LENGTH, true);
    }

    private static String mediaText(Map<String, Object> data, String key) {
        return text(data.get(key), MAX_MEDIA_TEXT_LENGTH, true);
    }
}
