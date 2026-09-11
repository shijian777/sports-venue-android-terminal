package com.codex.lockertest.ui.zip;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Lookup-only catalog with startup validation instead of an unsafe visual fallback. */
public final class ZipScreenCatalog {
    private static final Map<Integer, ZipScreenAsset> BY_ID;
    private static final Map<String, ZipScreenAsset> BY_DRAWABLE_NAME;

    static {
        Map<Integer, ZipScreenAsset> byId = new HashMap<Integer, ZipScreenAsset>();
        Map<String, ZipScreenAsset> byDrawableName = new HashMap<String, ZipScreenAsset>();
        for (ZipScreenAsset asset : ZipScreenAsset.values()) {
            if (byId.put(asset.id(), asset) != null) {
                throw new IllegalStateException("Duplicate ZIP screen id: " + asset.id());
            }
            if (byDrawableName.put(asset.drawableName(), asset) != null) {
                throw new IllegalStateException("Duplicate ZIP drawable name: " + asset.drawableName());
            }
        }
        BY_ID = Collections.unmodifiableMap(byId);
        BY_DRAWABLE_NAME = Collections.unmodifiableMap(byDrawableName);
    }

    private ZipScreenCatalog() { }

    public static ZipScreenAsset require(int id) {
        ZipScreenAsset asset = BY_ID.get(id);
        if (asset == null) {
            throw new IllegalArgumentException("Unknown ZIP screen id: " + id);
        }
        return asset;
    }

    public static ZipScreenAsset fromDrawableName(String drawableName) {
        ZipScreenAsset asset = BY_DRAWABLE_NAME.get(drawableName);
        if (asset == null) {
            throw new IllegalArgumentException("Unknown ZIP drawable name: " + drawableName);
        }
        return asset;
    }
}
