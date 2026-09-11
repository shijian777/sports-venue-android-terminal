package com.codex.lockertest.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.codex.lockertest.layout.LockerLayoutSnapshot;
import com.codex.lockertest.model.LockerTarget;
import com.codex.lockertest.model.LockerZone;
import com.codex.lockertest.ui.zip.ZipLockerScreenRouter;
import com.codex.lockertest.ui.zip.ZipLockerScreenRouter.SelectorState;
import com.codex.lockertest.ui.zip.ZipPixelShell;
import com.codex.lockertest.ui.zip.ZipScreenAsset;

import java.util.List;

/** ZIP pixel selector backed exclusively by the current layout snapshot. */
public final class LockerSelectionView extends FrameLayout {
    private static final int[] CELL_X = {222, 329, 436, 543, 650, 757, 864, 971};
    private static final int[] CELL_Y = {185, 261, 337, 413};
    private static final int[] AREA_X = {274, 452, 630, 808};

    /** @deprecated Temporary integer callback bridge for pre-zone callers. */
    @Deprecated
    public interface Listener {
        void onConfirmLocker(int lockerNumber);

        void onCancel();
    }

    public interface TargetListener {
        void onConfirmLocker(LockerTarget target);

        void onCancel();
    }

    private final LockerSelectionModel model;
    private final ZipPixelShell shell;
    private final FrameLayout body;
    private final Button areaLeftButton;
    private final Button[] areaButtons =
            new Button[LockerGridPresentation.MAX_VISIBLE_AREAS];
    private final Button areaRightButton;
    private final Button pageLeftButton;
    private final TextView pageCounter;
    private final Button pageRightButton;
    private final Button[][] lockerButtons = new Button[
            LockerGridPresentation.ROWS][LockerGridPresentation.COLUMNS];
    private final Button cancelButton;
    private final Button confirmButton;
    private final TextView instruction;
    private final FaceSecurityBanner securityBanner;

    private final FrameLayout promptBlocker;
    private final FrameLayout promptCard;
    private final TextView promptTitle;
    private final TextView promptDetail;
    private final TextView promptSupporting;
    private final FrameLayout promptActionCover;
    private final Button promptLeftButton;
    private final Button promptRightButton;
    private final Button promptCenterButton;

    private Listener listener;
    private TargetListener targetListener;
    private String selectionError = "";
    private int areaWindowPage;
    private boolean discoveryVisualRequested;
    private boolean promptActionTaken;
    private SelectorState lastSelectorState;

    public LockerSelectionView(Context context, LockerSelectionModel sharedModel) {
        super(context);
        model = requireModel(sharedModel);

        shell = new ZipPixelShell(context, ZipScreenAsset.LOCKER_DISCOVERING);
        addView(shell, match());
        FrameLayout content = shell.contentLayer();
        body = content;

        instruction = scaledText(UiKit.text(
                context, "请选择一个可用柜门", 15, UiKit.TEXT, Typeface.BOLD), 15);
        instruction.setGravity(Gravity.CENTER);
        instruction.setContentDescription("选柜状态");
        FrameLayout selectionHeader = new FrameLayout(context);
        selectionHeader.setBackground(UiKit.roundedSolid(
                context, Color.rgb(239, 249, 250), designDp(context, 8),
                Color.rgb(137, 205, 196), designDp(context, 1)));
        selectionHeader.addView(instruction, match());
        securityBanner = new FaceSecurityBanner(context);
        securityBanner.setMinHeight(unit(context, 32));
        securityBanner.setVisibility(View.GONE);
        selectionHeader.addView(securityBanner, match());
        body.addView(selectionHeader, positioned(context, 222, 493, 500, 34));

        pageLeftButton = navigationButton(context, "‹", "上一页柜门");
        pageLeftButton.setOnClickListener(view -> movePage(false));
        place(content, pageLeftButton, 770, 493, 44, 34);

        pageCounter = opaqueText(context, "第 0 / 0 页", 14, UiKit.TEXT, Typeface.BOLD);
        pageCounter.setContentDescription("柜门页码");
        place(content, pageCounter, 822, 493, 176, 34);

        pageRightButton = navigationButton(context, "›", "下一页柜门");
        pageRightButton.setOnClickListener(view -> movePage(true));
        place(content, pageRightButton, 1006, 493, 44, 34);

        for (int row = 0; row < LockerGridPresentation.ROWS; row++) {
            for (int column = 0; column < LockerGridPresentation.COLUMNS; column++) {
                final int modelRow = row + 1;
                final int modelColumn = column + 1;
                Button button = cellButton(context);
                button.setOnClickListener(view -> selectCell(modelRow, modelColumn));
                lockerButtons[row][column] = button;
                place(content, button, CELL_X[column], CELL_Y[row], 100, 64);
            }
        }

        confirmButton = actionButton(
                context, "确认开柜", "确认开启已选择柜门", UiKit.GREEN, Color.WHITE);
        confirmButton.setOnClickListener(view -> confirm());
        place(content, confirmButton, 550, 535, 180, 42);

        cancelButton = actionButton(
                context, "返回首页", "取消选柜并返回首页",
                Color.rgb(226, 231, 232), UiKit.TEXT);
        cancelButton.setOnClickListener(view -> cancel());
        place(content, cancelButton, 304, 655, 192, 47);

        areaLeftButton = navigationButton(context, "‹", "上一组区域");
        areaLeftButton.setOnClickListener(view -> moveAreaWindow(-1));
        place(content, areaLeftButton, 222, 582, 44, 34);

        for (int index = 0; index < areaButtons.length; index++) {
            final int visibleIndex = index;
            Button button = actionButton(context, "", "", UiKit.GREEN, Color.WHITE);
            button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, unit(context, 14));
            button.setSingleLine(true);
            button.setEllipsize(TextUtils.TruncateAt.END);
            button.setOnClickListener(view -> selectVisibleArea(visibleIndex));
            areaButtons[index] = button;
            place(content, button, AREA_X[index], 582, 170, 34);
        }

        areaRightButton = navigationButton(context, "›", "下一组区域");
        areaRightButton.setOnClickListener(view -> moveAreaWindow(1));
        place(content, areaRightButton, 986, 582, 44, 34);

        FrameLayout overlay = shell.overlayLayer();
        promptBlocker = new FrameLayout(context);
        promptBlocker.setClickable(true);
        promptBlocker.setFocusable(true);
        promptBlocker.setBackgroundColor(Color.TRANSPARENT);
        place(overlay, promptBlocker, 0, 0, 1280, 800);

        promptCard = new FrameLayout(context);
        promptCard.setClickable(true);
        promptCard.setBackground(UiKit.roundedGradient(
                context, Color.rgb(255, 244, 252), Color.rgb(216, 247, 255),
                designDp(context, 28)));
        place(overlay, promptCard, 355, 225, 570, 330);

        promptTitle = opaqueText(context, "温馨提示", 26, UiKit.GREEN, Typeface.BOLD);
        place(overlay, promptTitle, 440, 335, 400, 48);

        promptDetail = opaqueText(context, "", 18, UiKit.TEXT, Typeface.BOLD);
        place(overlay, promptDetail, 430, 386, 420, 40);

        promptSupporting = opaqueText(context, "", 15, UiKit.TEXT, Typeface.NORMAL);
        place(overlay, promptSupporting, 430, 422, 420, 35);

        promptActionCover = new FrameLayout(context);
        promptActionCover.setBackgroundColor(Color.rgb(224, 246, 249));
        promptActionCover.setClickable(false);
        promptActionCover.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        place(overlay, promptActionCover, 430, 466, 420, 118);

        promptLeftButton = actionButton(
                context, "再次尝试", "再次尝试选柜", UiKit.GREEN, Color.WHITE);
        promptLeftButton.setOnClickListener(view -> retryPrompt());
        place(overlay, promptLeftButton, 470, 480, 150, 46);

        promptRightButton = actionButton(
                context, "返回首页", "返回首页",
                Color.rgb(226, 231, 232), UiKit.TEXT);
        promptRightButton.setOnClickListener(view -> homeFromPrompt());
        place(overlay, promptRightButton, 660, 480, 150, 46);

        promptCenterButton = actionButton(
                context, "返回首页", "返回首页", UiKit.GREEN, Color.WHITE);
        promptCenterButton.setOnClickListener(view -> homeFromPrompt());
        place(overlay, promptCenterButton, 565, 480, 150, 46);

        ensureActiveAreaWindowVisible();
        render();
    }

    public void setSecurityBanner(CharSequence text, boolean visible) {
        securityBanner.setBannerText(text);
        securityBanner.setVisibility(visible ? View.VISIBLE : View.GONE);
        instruction.setVisibility(visible ? View.GONE : View.VISIBLE);
    }

    /** @deprecated Temporary integer callback bridge for pre-zone callers. */
    @Deprecated
    public void setListener(Listener listener) {
        this.listener = listener;
        targetListener = null;
    }

    public void setTargetListener(TargetListener listener) {
        targetListener = listener;
        this.listener = null;
    }

    public void showDiscoveryLoading() {
        discoveryVisualRequested = true;
        selectionError = "";
        areaWindowPage = 0;
        render();
    }

    public void applyDiscoverySnapshot(List<LockerZone> zones) {
        if (model.applyDiscoverySnapshot(zones)) {
            discoveryVisualRequested = false;
            selectionError = "";
            ensureActiveAreaWindowVisible();
            render();
        }
    }

    public void applyLayoutSnapshot(LockerLayoutSnapshot snapshot) {
        if (model.applyLayoutSnapshot(snapshot)) {
            discoveryVisualRequested = false;
            selectionError = "";
            ensureActiveAreaWindowVisible();
            render();
        }
    }

    public void refreshFromModel() {
        discoveryVisualRequested = false;
        render();
    }

    public LockerTarget selectedTarget() {
        return model.selectedTarget();
    }

    /** @deprecated Temporary integer bridge for pre-zone callers. */
    @Deprecated
    public int selectedLocker() {
        return model.selectedLocker();
    }

    public void setSending(boolean sending) {
        if (sending) {
            model.beginSending();
        } else {
            model.finishSending();
        }
        render();
    }

    public void showSelectionError(String error) {
        discoveryVisualRequested = false;
        selectionError = error == null ? "" : error;
        render();
    }

    public void resetAfterFailure(LockerTarget target) {
        model.finishSending();
        model.restoreTarget(target);
        discoveryVisualRequested = false;
        selectionError = "";
        ensureActiveAreaWindowVisible();
        render();
    }

    /** @deprecated Temporary integer bridge for pre-zone callers. */
    @Deprecated
    public void resetAfterFailure(int lockerNumber) {
        model.finishSending();
        model.select(lockerNumber);
        discoveryVisualRequested = false;
        selectionError = "";
        ensureActiveAreaWindowVisible();
        render();
    }

    private void moveAreaWindow(int direction) {
        if (!model.isInteractionEnabled() || isPromptVisible()) {
            return;
        }
        LockerGridPresentation current = presentation();
        if (direction < 0 && current.canPreviousAreaWindow()) {
            areaWindowPage--;
            render();
        } else if (direction > 0 && current.canNextAreaWindow()) {
            areaWindowPage++;
            render();
        }
    }

    private void selectVisibleArea(int visibleIndex) {
        if (!model.isInteractionEnabled() || isPromptVisible()) {
            return;
        }
        LockerGridPresentation current = presentation();
        if (visibleIndex < 0 || visibleIndex >= current.visibleAreas().size()) {
            return;
        }
        LockerGridPresentation.AreaItem area = current.visibleAreas().get(visibleIndex);
        if (area.clickable() && model.selectArea(area.id())) {
            selectionError = "";
            render();
        }
    }

    private void movePage(boolean forward) {
        if (!model.isInteractionEnabled() || isPromptVisible()) {
            return;
        }
        LockerGridPresentation current = presentation();
        boolean moved = forward
                ? current.canNextPage() && model.nextPage()
                : current.canPreviousPage() && model.previousPage();
        if (moved) {
            selectionError = "";
            render();
        }
    }

    private void selectCell(int row, int column) {
        if (!model.isInteractionEnabled() || isPromptVisible()) {
            return;
        }
        LockerGridPresentation.Cell cell = presentation().cellAt(row, column);
        if (cell.clickable() && cell.slotId() != null && model.selectSlot(cell.slotId())) {
            selectionError = "";
            render();
        }
    }

    private void confirm() {
        if (!model.isInteractionEnabled() || isPromptVisible()) {
            return;
        }
        LockerTarget target = model.selectedTarget();
        if (target == null || !model.canConfirm()) {
            selectionError = "请先选择一个柜门";
            render();
            return;
        }
        selectionError = "";
        render();

        TargetListener currentTargetListener = targetListener;
        if (currentTargetListener != null) {
            currentTargetListener.onConfirmLocker(target);
            return;
        }
        Listener currentListener = listener;
        if (currentListener != null) {
            currentListener.onConfirmLocker(target.localLock());
        }
    }

    private void cancel() {
        if (!model.isInteractionEnabled()) {
            return;
        }
        TargetListener currentTargetListener = targetListener;
        if (currentTargetListener != null) {
            currentTargetListener.onCancel();
            return;
        }
        Listener currentListener = listener;
        if (currentListener != null) {
            currentListener.onCancel();
        }
    }

    private void retryPrompt() {
        if (promptActionTaken) {
            return;
        }
        promptActionTaken = true;
        selectionError = "";
        render();
    }

    private void homeFromPrompt() {
        if (promptActionTaken) {
            return;
        }
        promptActionTaken = true;
        cancel();
    }

    private LockerGridPresentation presentation() {
        return LockerGridPresentation.create(model, areaWindowPage);
    }

    private void render() {
        LockerGridPresentation current = presentation();
        areaWindowPage = current.areaWindowPage();
        SelectorState state = selectorState();
        if (state != lastSelectorState) {
            promptActionTaken = false;
            lastSelectorState = state;
        }
        shell.setScreenAsset(ZipLockerScreenRouter.assetFor(state));

        boolean promptVisible = state == SelectorState.NO_AREA
                || state == SelectorState.SELECTION_ERROR;
        boolean selectorEnabled = !promptVisible
                && state != SelectorState.DISCOVERING
                && current.interactionEnabled();

        renderAreas(current, selectorEnabled);
        renderPageControls(current, selectorEnabled);
        renderCells(current, selectorEnabled);
        renderPrompt(state, promptVisible);

        LockerTarget selected = model.selectedTarget();
        String status = selected == null
                ? "请选择一个可用柜门"
                : "已选择：" + selectedDisplayLabel();
        instruction.setText(status);
        instruction.setContentDescription(status);

        confirmButton.setVisibility(VISIBLE);
        confirmButton.setEnabled(selectorEnabled && model.canConfirm());
        confirmButton.setAlpha(1f);
        cancelButton.setVisibility(VISIBLE);
        cancelButton.setEnabled(!promptVisible && current.interactionEnabled());
        cancelButton.setAlpha(1f);
        body.setImportantForAccessibility(promptVisible
                ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : IMPORTANT_FOR_ACCESSIBILITY_YES);
        shell.setOnSafeHomeRequested(current.interactionEnabled()
                ? this::cancel : null);
    }

    private SelectorState selectorState() {
        if (discoveryVisualRequested && isBlank(selectionError)) {
            return SelectorState.DISCOVERING;
        }
        return ZipLockerScreenRouter.selectorState(
                model.discoveryState(), model.activeArea() != null,
                model.selectedTarget() != null, selectionError);
    }

    private void renderAreas(LockerGridPresentation current, boolean selectorEnabled) {
        styleNavigation(areaLeftButton,
                selectorEnabled && current.canPreviousAreaWindow());
        styleNavigation(areaRightButton,
                selectorEnabled && current.canNextAreaWindow());
        for (int index = 0; index < areaButtons.length; index++) {
            Button button = areaButtons[index];
            button.setVisibility(VISIBLE);
            button.setAlpha(1f);
            if (index >= current.visibleAreas().size()) {
                button.setText("");
                button.setEnabled(false);
                button.setContentDescription(null);
                button.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
                button.setBackground(UiKit.roundedSolid(
                        getContext(), Color.rgb(235, 239, 240),
                        designDp(getContext(), 8), Color.rgb(173, 188, 194),
                        designDp(getContext(), 1)));
                continue;
            }
            LockerGridPresentation.AreaItem item = current.visibleAreas().get(index);
            button.setText(item.text());
            button.setContentDescription(item.contentDescription());
            button.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            button.setEnabled(selectorEnabled && item.clickable());
            int fill = item.selected() ? UiKit.DARK_GREEN
                    : item.available() ? UiKit.GREEN : Color.rgb(173, 188, 194);
            button.setTextColor(Color.WHITE);
            button.setBackground(UiKit.roundedSolid(
                    getContext(), fill, designDp(getContext(), 8),
                    Color.rgb(119, 155, 158), designDp(getContext(), 1)));
        }
    }

    private void renderPageControls(
            LockerGridPresentation current, boolean selectorEnabled) {
        pageCounter.setText(current.pageCounterText());
        pageCounter.setContentDescription("柜门页码，" + current.pageCounterText());
        styleNavigation(pageLeftButton, selectorEnabled && current.canPreviousPage());
        styleNavigation(pageRightButton, selectorEnabled && current.canNextPage());
    }

    private void renderCells(LockerGridPresentation current, boolean selectorEnabled) {
        for (int row = 0; row < LockerGridPresentation.ROWS; row++) {
            for (int column = 0; column < LockerGridPresentation.COLUMNS; column++) {
                Button button = lockerButtons[row][column];
                LockerGridPresentation.Cell cell = current.cellAt(row + 1, column + 1);
                boolean emptyOrUnconfigured =
                        cell.state() == LockerGridPresentation.CellState.EMPTY
                        || cell.state() == LockerGridPresentation.CellState.UNCONFIGURED;
                boolean selected = cell.state() == LockerGridPresentation.CellState.SELECTED;
                boolean disabled = cell.state() == LockerGridPresentation.CellState.DISABLED;

                button.setVisibility(VISIBLE);
                button.setText(emptyOrUnconfigured ? "" : cell.text());
                button.setEnabled(cell.clickable());
                if (!selectorEnabled) {
                    button.setEnabled(false);
                }
                button.setClickable(button.isEnabled());
                button.setFocusable(button.isEnabled());
                button.setAlpha(1f);
                button.setContentDescription(emptyOrUnconfigured
                        ? null : cell.contentDescription());
                button.setImportantForAccessibility(emptyOrUnconfigured
                        ? IMPORTANT_FOR_ACCESSIBILITY_NO
                        : IMPORTANT_FOR_ACCESSIBILITY_YES);

                int fill = selected ? UiKit.GREEN
                        : disabled ? Color.rgb(235, 239, 240) : Color.WHITE;
                int border = disabled || emptyOrUnconfigured
                        ? Color.rgb(173, 188, 194) : UiKit.GREEN;
                button.setTextColor(selected ? Color.WHITE
                        : disabled ? UiKit.MUTED : UiKit.DARK_GREEN);
                button.setBackground(UiKit.roundedSolid(
                        getContext(), fill, designDp(getContext(), 9), border,
                        designDp(getContext(), 2)));
            }
        }
    }

    private void renderPrompt(SelectorState state, boolean visible) {
        int visibility = visible ? VISIBLE : GONE;
        promptBlocker.setVisibility(visibility);
        promptCard.setVisibility(visibility);
        promptTitle.setVisibility(visibility);
        promptDetail.setVisibility(visibility);
        promptSupporting.setVisibility(visibility);
        promptActionCover.setVisibility(visibility);
        promptLeftButton.setVisibility(GONE);
        promptRightButton.setVisibility(GONE);
        promptCenterButton.setVisibility(GONE);
        if (!visible) {
            return;
        }

        if (state == SelectorState.NO_AREA) {
            promptTitle.setText("暂无可用柜区");
            promptDetail.setText("当前没有服务器配置的可用柜门");
            promptSupporting.setText("请返回首页或联系管理员");
            promptCenterButton.setVisibility(VISIBLE);
            promptCenterButton.setEnabled(!promptActionTaken);
        } else if (state == SelectorState.SELECTION_ERROR) {
            promptTitle.setText("无法完成选柜");
            promptDetail.setText(selectionError.trim());
            promptSupporting.setText("柜区与柜门数据保持不变，可再次选择");
            promptLeftButton.setVisibility(VISIBLE);
            promptRightButton.setVisibility(VISIBLE);
            promptLeftButton.setEnabled(!promptActionTaken);
            promptRightButton.setEnabled(!promptActionTaken);
        } else {
            throw new IllegalArgumentException("Unsupported prompt selector state: " + state);
        }
        promptCard.setContentDescription(
                promptTitle.getText() + "，" + promptDetail.getText()
                        + "，" + promptSupporting.getText());
    }

    private String selectedDisplayLabel() {
        return model.selectedSlot() == null
                ? "—" : model.selectedSlot().displayLabel();
    }

    private boolean isPromptVisible() {
        SelectorState state = selectorState();
        return state == SelectorState.NO_AREA || state == SelectorState.SELECTION_ERROR;
    }

    private void ensureActiveAreaWindowVisible() {
        int activeIndex = model.activeAreaIndex();
        areaWindowPage = activeIndex >= 0
                ? activeIndex / LockerGridPresentation.MAX_VISIBLE_AREAS : 0;
    }

    private void styleNavigation(Button button, boolean enabled) {
        button.setVisibility(VISIBLE);
        button.setEnabled(enabled);
        button.setClickable(enabled);
        button.setFocusable(enabled);
        button.setAlpha(1f);
        button.setTextColor(enabled ? Color.WHITE : UiKit.MUTED);
        button.setBackground(UiKit.roundedSolid(
                getContext(), enabled ? UiKit.GREEN : Color.rgb(226, 231, 232),
                designDp(getContext(), 8), Color.rgb(173, 188, 194),
                designDp(getContext(), 1)));
    }

    private static Button cellButton(Context context) {
        Button button = scaleButton(UiKit.button(
                context, "", 16, Color.WHITE, UiKit.DARK_GREEN,
                designDp(context, 9)), 16);
        button.setAllCaps(false);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        return button;
    }

    private static Button navigationButton(
            Context context, String text, String contentDescription) {
        Button button = scaleButton(UiKit.button(
                context, text, 22, UiKit.GREEN, Color.WHITE,
                designDp(context, 8)), 22);
        button.setContentDescription(contentDescription);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    private static Button actionButton(
            Context context, String text, String contentDescription,
            int background, int textColor) {
        Button button = scaleButton(UiKit.button(
                context, text, 16, background, textColor,
                designDp(context, 10)), 16);
        button.setAllCaps(false);
        button.setSingleLine(true);
        button.setContentDescription(contentDescription);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        return button;
    }

    private static TextView opaqueText(Context context, String text,
            float size, int color, int style) {
        TextView view = scaledText(UiKit.text(context, text, size, color, style), size);
        view.setGravity(Gravity.CENTER);
        view.setBackgroundColor(Color.rgb(240, 250, 250));
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private static void place(FrameLayout parent, View child,
            int x, int y, int width, int height) {
        parent.addView(child, positioned(parent.getContext(), x, y, width, height));
    }

    private static LayoutParams positioned(Context context,
            int x, int y, int width, int height) {
        LayoutParams params = new LayoutParams(unit(context, width), unit(context, height));
        params.leftMargin = unit(context, x);
        params.topMargin = unit(context, y);
        return params;
    }

    private static LockerSelectionModel requireModel(LockerSelectionModel model) {
        if (model == null) {
            throw new IllegalArgumentException("Shared locker selection model is required");
        }
        return model;
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

    private static int unit(Context context, float designUnits) {
        return ZipKioskShell.unit(context, designUnits);
    }

    private static float designDp(Context context, float designUnits) {
        return ZipKioskShell.designDp(context, designUnits);
    }

    private static <T extends TextView> T scaledText(T view, float designTextPixels) {
        return ZipKioskShell.scaledText(view, designTextPixels);
    }

    private static Button scaleButton(Button button, float designTextPixels) {
        return ZipKioskShell.scaleButton(button, designTextPixels);
    }

    private static LayoutParams match() {
        return new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
