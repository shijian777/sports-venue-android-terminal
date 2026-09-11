package com.codex.lockertest.ui;

import com.codex.lockertest.layout.LockerArea;
import com.codex.lockertest.layout.LockerLayoutSnapshot;
import com.codex.lockertest.layout.LockerPage;
import com.codex.lockertest.layout.LockerSlot;
import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.runtime.InitialLayoutPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class LockerSelectionModel {
    public enum DiscoveryState {
        DETECTING,
        READY,
        NO_ZONES
    }

    private DiscoveryState discoveryState;
    private List<LockerZone> onlineZones;
    private LockerLayoutSnapshot layoutSnapshot;
    private int activeAreaIndex = -1;
    private int activePageIndex = -1;
    private LockerSlot selectedSlot;
    private LockerTarget selectedTarget;
    private boolean sending;
    private boolean legacyCompatibilityLayout;
    private final InitialLayoutPolicy layoutPolicy;

    public LockerSelectionModel(InitialLayoutPolicy layoutPolicy) {
        if (layoutPolicy == null) {
            throw new IllegalArgumentException("Layout policy is required");
        }
        this.layoutPolicy = layoutPolicy;
        onlineZones = Collections.emptyList();
        discoveryState = DiscoveryState.NO_ZONES;
        LockerLayoutSnapshot initial = layoutPolicy.initialLayout();
        if (initial != null) {
            installLayout(initial);
        }
    }

    public boolean beginDiscovery() {
        if (sending) {
            return false;
        }
        layoutSnapshot = null;
        activeAreaIndex = -1;
        activePageIndex = -1;
        selectedSlot = null;
        selectedTarget = null;
        onlineZones = Collections.emptyList();
        discoveryState = DiscoveryState.DETECTING;
        legacyCompatibilityLayout = false;
        return true;
    }

    public boolean applyDiscoverySnapshot(List<LockerZone> discoveredZones) {
        if (sending) {
            return false;
        }
        LockerLayoutSnapshot compatibilityLayout =
                layoutPolicy.layoutForDiscovery(discoveredZones);
        if (compatibilityLayout == null) {
            layoutSnapshot = null;
            activeAreaIndex = -1;
            activePageIndex = -1;
            clearSelectedTarget();
            onlineZones = Collections.emptyList();
            discoveryState = DiscoveryState.NO_ZONES;
            legacyCompatibilityLayout = false;
            return true;
        }
        List<LockerZone> normalized = new ArrayList<>();
        for (LockerZone fixedZone : LockerZone.values()) {
            if (discoveredZones.contains(fixedZone)) {
                normalized.add(fixedZone);
            }
        }
        onlineZones = immutableZones(normalized);
        installLayout(compatibilityLayout);
        legacyCompatibilityLayout = true;
        return true;
    }

    public boolean applyLayoutSnapshot(LockerLayoutSnapshot snapshot) {
        if (sending) {
            return false;
        }
        if (snapshot == null) {
            throw new IllegalArgumentException("Layout snapshot cannot be null");
        }
        onlineZones = Collections.emptyList();
        installLayout(snapshot);
        legacyCompatibilityLayout = false;
        return true;
    }

    public LockerLayoutSnapshot layoutSnapshot() {
        return layoutSnapshot;
    }

    public LockerArea activeArea() {
        if (layoutSnapshot == null
                || activeAreaIndex < 0
                || activeAreaIndex >= layoutSnapshot.areas().size()) {
            return null;
        }
        return layoutSnapshot.areas().get(activeAreaIndex);
    }

    public int activeAreaIndex() {
        return activeArea() == null ? -1 : activeAreaIndex;
    }

    public boolean isAreaEnabled(String areaId) {
        int areaIndex = findAreaIndex(areaId);
        return areaIndex >= 0 && containsEnabledSlot(layoutSnapshot.areas().get(areaIndex));
    }

    public boolean selectArea(String areaId) {
        if (sending || discoveryState != DiscoveryState.READY) {
            return false;
        }
        int areaIndex = findAreaIndex(areaId);
        if (areaIndex < 0 || !containsEnabledSlot(layoutSnapshot.areas().get(areaIndex))) {
            return false;
        }
        if (areaIndex == activeAreaIndex) {
            return true;
        }
        activeAreaIndex = areaIndex;
        activePageIndex = 0;
        clearSelectedTarget();
        return true;
    }

    public LockerPage activePage() {
        LockerArea area = activeArea();
        if (area == null
                || activePageIndex < 0
                || activePageIndex >= area.pages().size()) {
            return null;
        }
        return area.pages().get(activePageIndex);
    }

    public int activePageIndex() {
        return activePage() == null ? -1 : activePageIndex;
    }

    public boolean hasPreviousPage() {
        return activePage() != null && activePageIndex > 0;
    }

    public boolean hasNextPage() {
        LockerArea area = activeArea();
        return activePage() != null && activePageIndex + 1 < area.pages().size();
    }

    public boolean previousPage() {
        if (sending || discoveryState != DiscoveryState.READY || !hasPreviousPage()) {
            return false;
        }
        activePageIndex--;
        clearSelectedTarget();
        return true;
    }

    public boolean nextPage() {
        if (sending || discoveryState != DiscoveryState.READY || !hasNextPage()) {
            return false;
        }
        activePageIndex++;
        clearSelectedTarget();
        return true;
    }

    public boolean selectSlot(String slotId) {
        if (sending || discoveryState != DiscoveryState.READY || slotId == null) {
            return false;
        }
        LockerPage page = activePage();
        if (page == null) {
            return false;
        }
        for (LockerSlot slot : page.slots()) {
            if (slot.id().equals(slotId) && slot.enabled()) {
                selectedSlot = slot;
                selectedTarget = slot.target();
                return true;
            }
        }
        return false;
    }

    public LockerSlot selectedSlot() {
        return selectedSlot;
    }

    public DiscoveryState discoveryState() {
        return discoveryState;
    }

    public String availabilityMessage() {
        if (discoveryState == DiscoveryState.DETECTING) {
            return "正在检测可用柜区，请稍候";
        }
        if (discoveryState == DiscoveryState.NO_ZONES) {
            return "暂无可用柜区，请联系管理员";
        }
        return "";
    }

    public List<LockerZone> onlineZones() {
        return onlineZones;
    }

    public boolean isZoneOnline(LockerZone zone) {
        return legacyCompatibilityLayout && zone != null && onlineZones.contains(zone);
    }

    public LockerZone activeZone() {
        if (!legacyCompatibilityLayout) {
            return null;
        }
        LockerArea area = activeArea();
        if (area == null) {
            return null;
        }
        for (LockerZone zone : LockerZone.values()) {
            if (legacyAreaId(zone).equals(area.id())) {
                return zone;
            }
        }
        return null;
    }

    public boolean selectZone(LockerZone zone) {
        if (!legacyCompatibilityLayout || sending
                || discoveryState != DiscoveryState.READY || !isZoneOnline(zone)) {
            return false;
        }
        return selectArea(legacyAreaId(zone));
    }

    public boolean selectLocalLock(int localLock) {
        LockerZone zone = activeZone();
        if (!legacyCompatibilityLayout || sending || discoveryState != DiscoveryState.READY
                || !isZoneOnline(zone) || !isValidLocalLock(localLock)) {
            return false;
        }
        return selectSlot(legacySlotId(zone, localLock));
    }

    public LockerTarget selectedTarget() {
        return selectedTarget;
    }

    public boolean restoreTarget(LockerTarget target) {
        if (sending || discoveryState != DiscoveryState.READY
                || target == null || layoutSnapshot == null) {
            return false;
        }
        for (int areaIndex = 0; areaIndex < layoutSnapshot.areas().size(); areaIndex++) {
            LockerArea area = layoutSnapshot.areas().get(areaIndex);
            for (int pageIndex = 0; pageIndex < area.pages().size(); pageIndex++) {
                LockerPage page = area.pages().get(pageIndex);
                for (LockerSlot slot : page.slots()) {
                    if (slot.enabled() && sameTarget(slot.target(), target)) {
                        activeAreaIndex = areaIndex;
                        activePageIndex = pageIndex;
                        selectedSlot = slot;
                        selectedTarget = target;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public String customerLabelForLocalLock(int localLock) {
        if (!isValidLocalLock(localLock)) {
            throw new IllegalArgumentException("localLock must be between 1 and 12");
        }
        LockerZone zone = activeZone();
        LockerArea area = activeArea();
        if (zone == null || area == null) {
            return "—";
        }
        String slotId = legacySlotId(zone, localLock);
        for (LockerPage page : area.pages()) {
            for (LockerSlot slot : page.slots()) {
                if (slot.id().equals(slotId)) {
                    return slot.displayLabel();
                }
            }
        }
        return "—";
    }

    public boolean canConfirm() {
        return !sending
                && discoveryState == DiscoveryState.READY
                && selectedSlot != null
                && selectedSlot.enabled()
                && selectedTarget != null
                && sameTarget(selectedSlot.target(), selectedTarget);
    }

    public boolean beginSending() {
        if (!canConfirm()) {
            return false;
        }
        sending = true;
        return true;
    }

    public void finishSending() {
        sending = false;
    }

    public boolean isInteractionEnabled() {
        return !sending;
    }

    public void clearSelection() {
        clearSelectedTarget();
        sending = false;
    }

    /** @deprecated Temporary integer bridge for pre-zone callers. */
    @Deprecated
    public boolean select(int lockerNumber) {
        return selectLocalLock(lockerNumber);
    }

    /** @deprecated Temporary integer bridge for pre-zone callers. */
    @Deprecated
    public int selectedLocker() {
        return !legacyCompatibilityLayout || selectedTarget == null
                ? 0 : selectedTarget.localLock();
    }

    /** @deprecated Temporary display bridge for the pre-zone selector. */
    @Deprecated
    public String displayLabel(int lockerNumber) {
        if (!isValidLocalLock(lockerNumber)) {
            throw new IllegalArgumentException("lockerNumber must be between 1 and 12");
        }
        return String.format(Locale.US, "%03d", lockerNumber);
    }

    private void installLayout(LockerLayoutSnapshot snapshot) {
        layoutSnapshot = snapshot;
        activeAreaIndex = -1;
        activePageIndex = -1;
        clearSelectedTarget();
        for (int areaIndex = 0; areaIndex < snapshot.areas().size(); areaIndex++) {
            if (containsEnabledSlot(snapshot.areas().get(areaIndex))) {
                activeAreaIndex = areaIndex;
                activePageIndex = 0;
                discoveryState = DiscoveryState.READY;
                return;
            }
        }
        discoveryState = DiscoveryState.NO_ZONES;
    }

    private int findAreaIndex(String areaId) {
        if (layoutSnapshot == null || areaId == null) {
            return -1;
        }
        for (int index = 0; index < layoutSnapshot.areas().size(); index++) {
            if (layoutSnapshot.areas().get(index).id().equals(areaId)) {
                return index;
            }
        }
        return -1;
    }

    private void clearSelectedTarget() {
        selectedSlot = null;
        selectedTarget = null;
    }

    private static boolean containsEnabledSlot(LockerArea area) {
        for (LockerPage page : area.pages()) {
            for (LockerSlot slot : page.slots()) {
                if (slot.enabled()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean sameTarget(LockerTarget first, LockerTarget second) {
        return first.zone() == second.zone()
                && first.boardAddress() == second.boardAddress()
                && first.localLock() == second.localLock()
                && first.feedbackPolarity() == second.feedbackPolarity();
    }

    private static String legacyAreaId(LockerZone zone) {
        return "legacy-area-" + zone.name();
    }

    private static String legacySlotId(LockerZone zone, int localLock) {
        return "legacy-slot-" + zone.name() + "-"
                + (localLock < 10 ? "0" + localLock : String.valueOf(localLock));
    }

    private static boolean isValidLocalLock(int localLock) {
        return localLock >= 1 && localLock <= 12;
    }

    private static List<LockerZone> immutableZones(List<LockerZone> zones) {
        return Collections.unmodifiableList(new ArrayList<>(zones));
    }
}
