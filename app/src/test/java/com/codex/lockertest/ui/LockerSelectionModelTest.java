package com.codex.lockertest.ui;

import com.codex.lockertest.layout.LockerArea;
import com.codex.lockertest.layout.LockerLayoutSnapshot;
import com.codex.lockertest.layout.LockerPage;
import com.codex.lockertest.layout.LockerSlot;
import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.protocol.FeedbackPolarity;
import com.codex.lockertest.runtime.InitialLayoutPolicy;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class LockerSelectionModelTest {
    @Test
    public void nullInitialLayoutInstallsNothing() {
        LockerSelectionModel model = new LockerSelectionModel(layoutPolicy(null, null));

        assertNull(model.layoutSnapshot());
        assertNull(model.activeArea());
        assertNull(model.activePage());
        assertEquals(LockerSelectionModel.DiscoveryState.NO_ZONES, model.discoveryState());
        assertTrue(model.onlineZones().isEmpty());
    }

    @Test
    public void nullDiscoveryLayoutMeansNoZonesWithoutLegacyFallback() {
        final int[] calls = {0};
        final List<?>[] received = {null};
        InitialLayoutPolicy policy = new InitialLayoutPolicy() {
            @Override
            public LockerLayoutSnapshot initialLayout() {
                return null;
            }

            @Override
            public LockerLayoutSnapshot layoutForDiscovery(List<LockerZone> onlineZones) {
                calls[0]++;
                received[0] = onlineZones;
                return null;
            }
        };
        LockerSelectionModel model = new LockerSelectionModel(policy);
        List<LockerZone> discovered = Collections.singletonList(LockerZone.B);

        assertTrue(model.applyDiscoverySnapshot(discovered));

        assertEquals(1, calls[0]);
        assertSame(discovered, received[0]);
        assertNull(model.layoutSnapshot());
        assertEquals(LockerSelectionModel.DiscoveryState.NO_ZONES, model.discoveryState());
        assertNull(model.activeArea());
        assertNull(model.activePage());
        assertTrue(model.onlineZones().isEmpty());
        assertFalse(model.selectZone(LockerZone.B));
    }

    @Test
    public void initialPolicyLayoutInstallsWithoutInferringLegacyDiscoveryState() {
        LockerSelectionModel model = legacyModel();

        assertEquals(LockerSelectionModel.DiscoveryState.READY, model.discoveryState());
        assertTrue(model.onlineZones().isEmpty());
        assertNull(model.activeZone());
        assertNotNull(model.layoutSnapshot());
        assertEquals(3, model.layoutSnapshot().areas().size());
        assertEquals("legacy-area-A", model.activeArea().id());
        assertEquals(0, model.activeAreaIndex());
        assertEquals("legacy-page-A-1", model.activePage().id());
        assertEquals(0, model.activePageIndex());
        assertEquals(12, model.activePage().slots().size());

        assertFalse(model.select(12));
        assertTrue(model.selectSlot("legacy-slot-A-12"));

        LockerSlot selectedSlot = model.activePage().slotAt(4, 3);
        assertSame(selectedSlot, model.selectedSlot());
        assertSame(selectedSlot.target(), model.selectedTarget());
        assertEquals(0, model.selectedLocker());
        assertEquals("—", model.customerLabelForLocalLock(12));
        assertEquals("012", model.displayLabel(12));
        assertTrue(model.canConfirm());
    }

    @Test
    public void beginDiscoveryClearsAllTopologyUnlessSendingFreezesIt() {
        LockerSelectionModel model = readyLegacy(LockerZone.A);
        assertTrue(model.selectLocalLock(3));
        assertTrue(model.beginSending());
        LockerLayoutSnapshot frozenLayout = model.layoutSnapshot();
        LockerArea frozenArea = model.activeArea();
        LockerPage frozenPage = model.activePage();
        LockerSlot frozenSlot = model.selectedSlot();
        LockerTarget frozenTarget = model.selectedTarget();

        assertFalse(model.beginDiscovery());

        assertSame(frozenLayout, model.layoutSnapshot());
        assertSame(frozenArea, model.activeArea());
        assertSame(frozenPage, model.activePage());
        assertSame(frozenSlot, model.selectedSlot());
        assertSame(frozenTarget, model.selectedTarget());
        assertFalse(model.isInteractionEnabled());

        model.finishSending();
        assertTrue(model.beginDiscovery());

        assertEquals(LockerSelectionModel.DiscoveryState.DETECTING, model.discoveryState());
        assertEquals("正在检测可用柜区，请稍候", model.availabilityMessage());
        assertNull(model.layoutSnapshot());
        assertNull(model.activeArea());
        assertEquals(-1, model.activeAreaIndex());
        assertNull(model.activePage());
        assertEquals(-1, model.activePageIndex());
        assertNull(model.selectedSlot());
        assertNull(model.selectedTarget());
        assertTrue(model.onlineZones().isEmpty());
        assertNull(model.activeZone());
        assertFalse(model.canConfirm());
    }

    @Test
    public void legacyDiscoveryNormalizesOrderOwnsItsListAndKeepsDisabledAreasVisible() {
        LockerSelectionModel model = detectingModel();
        List<LockerZone> callerZones = new ArrayList<>(
                Arrays.asList(LockerZone.C, LockerZone.B, LockerZone.C));

        assertTrue(model.applyDiscoverySnapshot(callerZones));
        callerZones.clear();

        assertEquals(Arrays.asList(LockerZone.B, LockerZone.C), model.onlineZones());
        assertEquals(LockerZone.B, model.activeZone());
        assertEquals("legacy-area-B", model.activeArea().id());
        assertEquals(1, model.activeAreaIndex());
        assertEquals(LockerSelectionModel.DiscoveryState.READY, model.discoveryState());
        assertEquals("", model.availabilityMessage());
        assertFalse(model.layoutSnapshot().areas().get(0).pages().get(0).slots().get(0).enabled());
        assertTrue(model.layoutSnapshot().areas().get(1).pages().get(0).slots().get(0).enabled());
        expectUnsupported(new Runnable() {
            @Override public void run() { model.onlineZones().add(LockerZone.A); }
        });

        assertTrue(model.beginDiscovery());
        assertTrue(model.applyDiscoverySnapshot(Collections.<LockerZone>emptyList()));

        assertEquals(LockerSelectionModel.DiscoveryState.NO_ZONES, model.discoveryState());
        assertEquals("暂无可用柜区，请联系管理员", model.availabilityMessage());
        assertEquals(3, model.layoutSnapshot().areas().size());
        assertNull(model.activeArea());
        assertNull(model.activePage());
        assertTrue(model.onlineZones().isEmpty());
    }

    @Test
    public void dynamicSnapshotPreservesSourceOrderAndActivatesFirstEnabledAreaAtPageZero() {
        LockerSlot disabledFirst = slot("disabled-first", "停用一号", 2, 3, false,
                LockerZone.A, 1);
        LockerPage disabledPage = page("disabled-page", 8, 2, 3,
                Collections.singletonList(disabledFirst));
        LockerArea disabledArea = area("source-z", "西区", disabledPage);

        LockerSlot disabledOnPageZero = slot("alpha-disabled", "A-停用", 2, 3, false,
                LockerZone.B, 1);
        LockerPage alphaPageZero = page("alpha-page-nine", 9, 2, 3,
                Collections.singletonList(disabledOnPageZero));
        LockerSlot enabledOnPageOne = slot("alpha-enabled", "泳道-17", 1, 2, true,
                LockerZone.B, 2);
        LockerPage alphaPageOne = page("alpha-page-two", 2, 1, 2,
                Collections.singletonList(enabledOnPageOne));
        LockerArea alpha = area("alpha-id", "会员区", alphaPageZero, alphaPageOne);

        LockerArea beta = area("beta-id", "访客区",
                page("beta-first", 1, 1, 1,
                        Collections.singletonList(slot("beta-slot", "访客柜", 1, 1, true,
                                LockerZone.C, 1))));
        LockerLayoutSnapshot snapshot = snapshot(73, disabledArea, alpha, beta);
        LockerSelectionModel model = legacyModel();

        assertTrue(model.applyLayoutSnapshot(snapshot));

        assertSame(snapshot, model.layoutSnapshot());
        assertEquals(Arrays.asList(disabledArea, alpha, beta), model.layoutSnapshot().areas());
        assertSame(alpha, model.activeArea());
        assertEquals(1, model.activeAreaIndex());
        assertSame(alphaPageZero, model.activePage());
        assertEquals(0, model.activePageIndex());
        assertNull(model.activePage().slotAt(1, 1));
        assertSame(disabledOnPageZero, model.activePage().slotAt(2, 3));
        assertTrue(model.isAreaEnabled("alpha-id"));
        assertTrue(model.isAreaEnabled("beta-id"));
        assertFalse(model.isAreaEnabled("source-z"));
        assertNull(model.activeZone());
        assertEquals(LockerSelectionModel.DiscoveryState.READY, model.discoveryState());
    }

    @Test
    public void dynamicSnapshotWithLegacyIdsNeverEnablesCompatibilityBridges() {
        LockerSlot collidingSlot = slot("legacy-slot-A-01", "服务器一号柜", 1, 1, true,
                LockerZone.A, 1);
        LockerArea collidingArea = area("legacy-area-A", "服务器自定义区",
                page("legacy-page-A-1", 1, 1, 1,
                        Collections.singletonList(collidingSlot)));
        LockerSelectionModel model = legacyModel();

        assertTrue(model.applyLayoutSnapshot(snapshot(74, collidingArea)));

        assertTrue(model.onlineZones().isEmpty());
        assertNull(model.activeZone());
        assertFalse(model.isZoneOnline(LockerZone.A));
        assertFalse(model.selectZone(LockerZone.A));
        assertFalse(model.selectLocalLock(1));
        assertTrue(model.selectArea("legacy-area-A"));
        assertTrue(model.selectSlot("legacy-slot-A-01"));
        assertSame(collidingArea, model.activeArea());
        assertSame(collidingSlot, model.selectedSlot());
        assertSame(collidingSlot.target(), model.selectedTarget());
    }

    @Test
    public void allDisabledDynamicSnapshotRemainsVisibleWithoutAnActiveAreaOrPage() {
        LockerArea first = area("d1", "一层",
                page("d1p", 1, 2, 2,
                        Collections.singletonList(slot("d1s", "不可用一", 1, 1, false,
                                LockerZone.A, 5))));
        LockerArea second = area("d2", "二层",
                page("d2p", 1, 1, 1,
                        Collections.singletonList(slot("d2s", "不可用二", 1, 1, false,
                                LockerZone.B, 5))));
        LockerLayoutSnapshot snapshot = snapshot(90, first, second);
        LockerSelectionModel model = legacyModel();

        assertTrue(model.applyLayoutSnapshot(snapshot));

        assertSame(snapshot, model.layoutSnapshot());
        assertEquals(2, model.layoutSnapshot().areas().size());
        assertEquals(LockerSelectionModel.DiscoveryState.NO_ZONES, model.discoveryState());
        assertNull(model.activeArea());
        assertEquals(-1, model.activeAreaIndex());
        assertNull(model.activePage());
        assertEquals(-1, model.activePageIndex());
        assertFalse(model.isAreaEnabled("d1"));
        assertFalse(model.isAreaEnabled("d2"));
        assertFalse(model.selectArea("d1"));
        assertFalse(model.selectSlot("d1s"));
    }

    @Test
    public void sameAreaRetainsPageAndSelectionWhileRealAreaChangeResetsBoth() {
        LockerSlot a1 = slot("a-one", "A一号", 1, 1, true, LockerZone.A, 1);
        LockerSlot a2 = slot("a-two", "A二号", 1, 1, true, LockerZone.A, 2);
        LockerArea alpha = area("alpha", "甲区",
                page("a-page-1", 1, 1, 1, Collections.singletonList(a1)),
                page("a-page-2", 2, 1, 1, Collections.singletonList(a2)));
        LockerSlot b1 = slot("b-one", "B一号", 1, 1, true, LockerZone.B, 1);
        LockerArea beta = area("beta", "乙区",
                page("b-page", 1, 1, 1, Collections.singletonList(b1)));
        LockerArea disabled = area("disabled", "停用区",
                page("disabled-page", 1, 1, 1,
                        Collections.singletonList(slot("disabled-slot", "停用", 1, 1, false,
                                LockerZone.C, 1))));
        LockerSelectionModel model = readyDynamic(snapshot(1, alpha, beta, disabled));
        assertTrue(model.nextPage());
        assertTrue(model.selectSlot("a-two"));

        assertTrue(model.selectArea("alpha"));
        assertEquals(1, model.activePageIndex());
        assertSame(a2, model.selectedSlot());
        assertSame(a2.target(), model.selectedTarget());

        assertFalse(model.selectArea(null));
        assertFalse(model.selectArea("missing"));
        assertFalse(model.selectArea("disabled"));
        assertEquals(1, model.activePageIndex());
        assertSame(a2, model.selectedSlot());

        assertTrue(model.selectArea("beta"));
        assertSame(beta, model.activeArea());
        assertEquals(1, model.activeAreaIndex());
        assertEquals(0, model.activePageIndex());
        assertSame(beta.pages().get(0), model.activePage());
        assertNull(model.selectedSlot());
        assertNull(model.selectedTarget());
    }

    @Test
    public void pageNavigationUsesSourceOrderClearsSelectionAndHasAtomicBoundaries() {
        LockerSlot first = slot("first", "第一页", 1, 1, true, LockerZone.A, 3);
        LockerSlot second = slot("second", "第二页", 1, 1, true, LockerZone.A, 4);
        LockerSlot third = slot("third", "第三页", 1, 1, true, LockerZone.A, 5);
        LockerArea area = area("pages", "分页区",
                page("page-30", 30, 1, 1, Collections.singletonList(first)),
                page("page-10", 10, 1, 1, Collections.singletonList(second)),
                page("page-20", 20, 1, 1, Collections.singletonList(third)));
        LockerSelectionModel model = readyDynamic(snapshot(2, area));
        assertTrue(model.selectSlot("first"));

        assertFalse(model.hasPreviousPage());
        assertTrue(model.hasNextPage());
        assertFalse(model.previousPage());
        assertSame(first, model.selectedSlot());

        assertTrue(model.nextPage());
        assertEquals("page-10", model.activePage().id());
        assertEquals(1, model.activePageIndex());
        assertNull(model.selectedSlot());
        assertTrue(model.selectSlot("second"));
        assertTrue(model.nextPage());
        assertEquals("page-20", model.activePage().id());
        assertNull(model.selectedSlot());
        assertFalse(model.hasNextPage());
        assertFalse(model.nextPage());
        assertEquals(2, model.activePageIndex());

        assertTrue(model.previousPage());
        assertEquals(1, model.activePageIndex());
        assertTrue(model.previousPage());
        assertEquals(0, model.activePageIndex());
        assertFalse(model.previousPage());
    }

    @Test
    public void slotSelectionUsesExactIdLabelAndTargetWhileRejectionsAreAtomic() {
        LockerSlot exact = slot("opaque-slot-id", "南区-007", 1, 1, true,
                LockerZone.B, 6);
        LockerSlot disabled = slot("disabled-id", "停用-008", 1, 3, false,
                LockerZone.B, 7);
        LockerSlot offPage = slot("off-page-id", "下一页", 1, 1, true,
                LockerZone.B, 8);
        LockerPage first = page("sparse", 1, 2, 3, Arrays.asList(exact, disabled));
        LockerPage second = page("other", 2, 1, 1, Collections.singletonList(offPage));
        LockerSelectionModel model = readyDynamic(snapshot(3, area("slots", "选柜区", first, second)));

        assertNull(model.activePage().slotAt(1, 2));
        assertTrue(model.selectSlot("opaque-slot-id"));
        assertSame(exact, model.selectedSlot());
        assertEquals("opaque-slot-id", model.selectedSlot().id());
        assertEquals("南区-007", model.selectedSlot().displayLabel());
        assertSame(exact.target(), model.selectedTarget());

        assertFalse(model.selectSlot(null));
        assertFalse(model.selectSlot("sparse-coordinate-1-2"));
        assertFalse(model.selectSlot("disabled-id"));
        assertFalse(model.selectSlot("off-page-id"));
        assertSame(exact, model.selectedSlot());
        assertSame(exact.target(), model.selectedTarget());
    }

    @Test
    public void sendingFreezesEveryRefreshNavigationAndSelectionUntilExplicitlyFinished() {
        LockerSlot first = slot("freeze-first", "冻结一", 1, 1, true,
                LockerZone.A, 6);
        LockerSlot next = slot("freeze-next", "冻结二", 1, 1, true,
                LockerZone.A, 7);
        LockerArea primary = area("freeze-primary", "主区",
                page("freeze-page-1", 1, 1, 1, Collections.singletonList(first)),
                page("freeze-page-2", 2, 1, 1, Collections.singletonList(next)));
        LockerArea other = area("freeze-other", "另一区",
                page("freeze-other-page", 1, 1, 1,
                        Collections.singletonList(slot("freeze-other-slot", "另一个", 1, 1, true,
                                LockerZone.B, 9))));
        LockerSelectionModel model = readyDynamic(snapshot(4, primary, other));
        assertTrue(model.selectSlot("freeze-first"));
        assertTrue(model.beginSending());
        LockerLayoutSnapshot frozenLayout = model.layoutSnapshot();
        LockerArea frozenArea = model.activeArea();
        LockerPage frozenPage = model.activePage();
        LockerSlot frozenSlot = model.selectedSlot();
        LockerTarget frozenTarget = model.selectedTarget();

        assertFalse(model.beginDiscovery());
        assertFalse(model.applyLayoutSnapshot(snapshot(5, other)));
        assertFalse(model.applyDiscoverySnapshot(Collections.singletonList(LockerZone.C)));
        assertFalse(model.selectArea("freeze-other"));
        assertFalse(model.nextPage());
        assertFalse(model.previousPage());
        assertFalse(model.selectSlot("freeze-next"));
        assertFalse(model.selectZone(LockerZone.A));
        assertFalse(model.selectLocalLock(2));

        assertSame(frozenLayout, model.layoutSnapshot());
        assertSame(frozenArea, model.activeArea());
        assertSame(frozenPage, model.activePage());
        assertSame(frozenSlot, model.selectedSlot());
        assertSame(frozenTarget, model.selectedTarget());
        assertFalse(model.isInteractionEnabled());

        model.finishSending();

        assertTrue(model.isInteractionEnabled());
        assertTrue(model.canConfirm());
        assertSame(frozenSlot, model.selectedSlot());
        assertSame(frozenTarget, model.selectedTarget());
        model.clearSelection();
        assertTrue(model.isInteractionEnabled());
        assertNull(model.selectedSlot());
        assertNull(model.selectedTarget());
    }

    @Test
    public void structuralRestoreAcrossModelsMovesToRealAreaAndPageButRetainsCallerTarget() {
        LockerLayoutSnapshot sourceSnapshot = restoreSnapshot();
        LockerLayoutSnapshot destinationSnapshot = restoreSnapshot();
        LockerSelectionModel source = readyDynamic(sourceSnapshot);
        assertTrue(source.selectArea("restore-beta"));
        assertTrue(source.nextPage());
        assertTrue(source.selectSlot("restore-c4"));
        LockerTarget callerTarget = source.selectedTarget();
        LockerSelectionModel destination = readyDynamic(destinationSnapshot);
        assertEquals("restore-alpha", destination.activeArea().id());

        assertTrue(destination.restoreTarget(callerTarget));

        assertEquals("restore-beta", destination.activeArea().id());
        assertEquals(1, destination.activeAreaIndex());
        assertEquals("restore-beta-page-2", destination.activePage().id());
        assertEquals(1, destination.activePageIndex());
        assertEquals("restore-c4", destination.selectedSlot().id());
        assertSame(destinationSnapshot.areas().get(1).pages().get(1).slots().get(0),
                destination.selectedSlot());
        assertSame(callerTarget, destination.selectedTarget());
        assertFalse(callerTarget == destination.selectedSlot().target());
        assertTrue(destination.canConfirm());
    }

    @Test
    public void restoreRejectsNullMissingDisabledAndPolarityMismatchAtomically() {
        LockerSlot chosen = slot("chosen", "保留", 1, 1, true,
                LockerZone.B, 10);
        LockerSlot disabled = slot("restore-disabled", "停用", 1, 2, false,
                LockerZone.B, 11);
        LockerArea area = area("restore-atomic", "恢复区",
                page("restore-atomic-page", 1, 1, 2, Arrays.asList(chosen, disabled)));
        LockerSelectionModel model = readyDynamic(snapshot(6, area));
        assertTrue(model.selectSlot("chosen"));
        LockerArea originalArea = model.activeArea();
        LockerPage originalPage = model.activePage();
        LockerSlot originalSlot = model.selectedSlot();
        LockerTarget originalTarget = model.selectedTarget();

        assertFalse(model.restoreTarget(null));
        assertFalse(model.restoreTarget(target(LockerZone.C, 12,
                FeedbackPolarity.SHORT_WHEN_LOCKED)));
        assertFalse(model.restoreTarget(target(LockerZone.B, 11,
                FeedbackPolarity.SHORT_WHEN_LOCKED)));
        assertFalse(model.restoreTarget(target(LockerZone.B, 10,
                FeedbackPolarity.SHORT_WHEN_OPEN)));

        assertSame(originalArea, model.activeArea());
        assertSame(originalPage, model.activePage());
        assertSame(originalSlot, model.selectedSlot());
        assertSame(originalTarget, model.selectedTarget());
    }

    @Test
    public void legacyZoneAndLockSelectionReuseCompatibilitySlotsAndLabels() {
        LockerSelectionModel model = readyLegacy(LockerZone.B, LockerZone.C);

        assertTrue(model.selectZone(LockerZone.C));
        assertEquals("legacy-area-C", model.activeArea().id());
        assertTrue(model.selectLocalLock(12));
        LockerSlot c12 = model.activePage().slotAt(4, 3);
        assertSame(c12, model.selectedSlot());
        assertSame(c12.target(), model.selectedTarget());
        assertEquals("C12", model.customerLabelForLocalLock(12));

        assertTrue(model.selectZone(LockerZone.C));
        assertSame(c12, model.selectedSlot());
        assertFalse(model.selectZone(LockerZone.A));
        assertFalse(model.selectZone(null));
        assertSame(c12, model.selectedSlot());

        assertTrue(model.selectZone(LockerZone.B));
        assertNull(model.selectedSlot());
        assertNull(model.selectedTarget());
        assertFalse(model.selectLocalLock(0));
        assertFalse(model.selectLocalLock(13));
    }

    private static LockerSelectionModel detectingModel() {
        LockerSelectionModel model = legacyModel();
        assertTrue(model.beginDiscovery());
        return model;
    }

    private static LockerSelectionModel legacyModel() {
        return new LockerSelectionModel(RuntimePolicyFixtures.legacyLayoutPolicy());
    }

    private static LockerSelectionModel readyLegacy(LockerZone... zones) {
        LockerSelectionModel model = detectingModel();
        assertTrue(model.applyDiscoverySnapshot(Arrays.asList(zones)));
        return model;
    }

    private static LockerSelectionModel readyDynamic(LockerLayoutSnapshot snapshot) {
        LockerSelectionModel model = legacyModel();
        assertTrue(model.applyLayoutSnapshot(snapshot));
        return model;
    }

    private static LockerLayoutSnapshot restoreSnapshot() {
        LockerArea alpha = area("restore-alpha", "前区",
                page("restore-alpha-page", 1, 1, 1,
                        Collections.singletonList(slot("restore-a1", "A一号", 1, 1, true,
                                LockerZone.A, 12))));
        LockerArea beta = area("restore-beta", "后区",
                page("restore-beta-page-1", 1, 1, 1,
                        Collections.singletonList(slot("restore-b1", "B一号", 1, 1, true,
                                LockerZone.B, 12))),
                page("restore-beta-page-2", 2, 1, 1,
                        Collections.singletonList(slot("restore-c4", "客户四号", 1, 1, true,
                                LockerZone.C, 4))));
        return snapshot(7, alpha, beta);
    }

    private static LockerLayoutSnapshot snapshot(long version, LockerArea... areas) {
        return new LockerLayoutSnapshot(version, Arrays.asList(areas));
    }

    private static InitialLayoutPolicy layoutPolicy(
            LockerLayoutSnapshot initial, LockerLayoutSnapshot discovery) {
        return new InitialLayoutPolicy() {
            @Override
            public LockerLayoutSnapshot initialLayout() {
                return initial;
            }

            @Override
            public LockerLayoutSnapshot layoutForDiscovery(List<LockerZone> onlineZones) {
                return discovery;
            }
        };
    }

    private static LockerArea area(String id, String label, LockerPage... pages) {
        return new LockerArea(id, label, Arrays.asList(pages));
    }

    private static LockerPage page(String id, int pageNumber, int rows, int columns,
            List<LockerSlot> slots) {
        return new LockerPage(id, pageNumber, rows, columns, slots);
    }

    private static LockerSlot slot(String id, String label, int row, int column,
            boolean enabled, LockerZone zone, int localLock) {
        return new LockerSlot(id, label, row, column, enabled,
                target(zone, localLock, FeedbackPolarity.SHORT_WHEN_LOCKED));
    }

    private static LockerTarget target(LockerZone zone, int localLock,
            FeedbackPolarity polarity) {
        return new LockerTarget(zone, zone.boardAddress(), localLock, polarity);
    }

    private static void expectUnsupported(Runnable action) {
        try {
            action.run();
            fail("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // Expected immutable view.
        }
    }
}
