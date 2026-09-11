package com.codex.lockertest.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** An immutable named collection of layout pages. */
public final class LockerArea {
    private final String id;
    private final String displayName;
    private final List<LockerPage> pages;

    public LockerArea(String id, String displayName, List<LockerPage> pages) {
        this.id = LockerSlot.requireName(id, "Area id", 0);
        this.displayName = LockerSlot.requireName(displayName, "Area display name", 20);
        if (pages == null) {
            throw new IllegalArgumentException("Area pages are required");
        }
        List<LockerPage> copy = new ArrayList<>(pages.size());
        Set<String> ids = new HashSet<>();
        Set<Integer> numbers = new HashSet<>();
        for (LockerPage page : pages) {
            if (page == null) {
                throw new IllegalArgumentException("Area pages cannot contain null");
            }
            if (!ids.add(page.id())) {
                throw new IllegalArgumentException("Page ids must be unique within an area");
            }
            if (!numbers.add(page.pageNumber())) {
                throw new IllegalArgumentException("Page numbers must be unique within an area");
            }
            copy.add(page);
        }
        this.pages = Collections.unmodifiableList(copy);
    }

    public String id() { return id; }
    public String getId() { return id; }
    public String displayName() { return displayName; }
    public String getDisplayName() { return displayName; }
    public List<LockerPage> pages() { return pages; }
    public List<LockerPage> getPages() { return pages; }
}
