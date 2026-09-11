package com.codex.lockertest.bootstrap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BaseSettingSnapshot {
    private final String venueName;
    private final String version;
    private final List<String> recognitionTypes;
    private final String useNotice;
    private final String returnNotice;
    private final String logo;
    private final String company;
    private final String cozyTips;
    private final int advertisementType;
    private final List<Advertisement> advertisements;
    private final String backgroundImage;
    private final String palmPrintImage;
    private final List<Area> areas;
    private final String lockerCheckStatus;
    private final String activationCode;
    private final Map<String, String> voiceFiles;

    BaseSettingSnapshot(
            String venueName,
            String version,
            List<String> recognitionTypes,
            String useNotice,
            String returnNotice,
            String logo,
            String company,
            String cozyTips,
            int advertisementType,
            List<Advertisement> advertisements,
            String backgroundImage,
            String palmPrintImage,
            List<Area> areas,
            String lockerCheckStatus) {
        this(venueName, version, recognitionTypes, useNotice, returnNotice,
                logo, company, cozyTips, advertisementType, advertisements,
                backgroundImage, palmPrintImage, areas, lockerCheckStatus,
                "", Collections.<String, String>emptyMap());
    }

    BaseSettingSnapshot(
            String venueName,
            String version,
            List<String> recognitionTypes,
            String useNotice,
            String returnNotice,
            String logo,
            String company,
            String cozyTips,
            int advertisementType,
            List<Advertisement> advertisements,
            String backgroundImage,
            String palmPrintImage,
            List<Area> areas,
            String lockerCheckStatus,
            String activationCode,
            Map<String, String> voiceFiles) {
        if (venueName == null || version == null || recognitionTypes == null
                || useNotice == null || returnNotice == null || logo == null
                || company == null || cozyTips == null || advertisements == null
                || backgroundImage == null || palmPrintImage == null || areas == null
                || lockerCheckStatus == null || activationCode == null
                || voiceFiles == null) {
            throw new IllegalArgumentException("Missing base setting");
        }
        this.venueName = venueName;
        this.version = version;
        this.recognitionTypes = immutableCopy(recognitionTypes);
        this.useNotice = useNotice;
        this.returnNotice = returnNotice;
        this.logo = logo;
        this.company = company;
        this.cozyTips = cozyTips;
        this.advertisementType = advertisementType;
        this.advertisements = immutableCopy(advertisements);
        this.backgroundImage = backgroundImage;
        this.palmPrintImage = palmPrintImage;
        this.areas = immutableCopy(areas);
        this.lockerCheckStatus = lockerCheckStatus;
        this.activationCode = activationCode;
        this.voiceFiles = Collections.unmodifiableMap(new LinkedHashMap<>(voiceFiles));
    }

    public String venueName() { return venueName; }
    public String version() { return version; }
    public List<String> recognitionTypes() { return recognitionTypes; }
    public String useNotice() { return useNotice; }
    public String returnNotice() { return returnNotice; }
    public String logo() { return logo; }
    public String company() { return company; }
    public String cozyTips() { return cozyTips; }
    public int advertisementType() { return advertisementType; }
    public List<Advertisement> advertisements() { return advertisements; }
    public String backgroundImage() { return backgroundImage; }
    public String palmPrintImage() { return palmPrintImage; }
    public List<Area> areas() { return areas; }
    public String lockerCheckStatus() { return lockerCheckStatus; }
    public String activationCode() { return activationCode; }
    public Map<String, String> voiceFiles() { return voiceFiles; }

    private static <T> List<T> immutableCopy(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    public static final class Advertisement {
        private final String image;
        private final String video;

        Advertisement(String image, String video) {
            if (image == null || video == null) {
                throw new IllegalArgumentException("Missing advertisement value");
            }
            this.image = image;
            this.video = video;
        }

        public String image() { return image; }
        public String video() { return video; }
    }

    public static final class Area {
        private final int areaId;
        private final String areaName;

        Area(int areaId, String areaName) {
            if (areaName == null) throw new IllegalArgumentException("Missing area name");
            this.areaId = areaId;
            this.areaName = areaName;
        }

        public int areaId() { return areaId; }
        public String areaName() { return areaName; }
    }
}
