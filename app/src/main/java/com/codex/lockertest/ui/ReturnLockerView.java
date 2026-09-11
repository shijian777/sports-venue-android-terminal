package com.codex.lockertest.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.codex.lockertest.returnflow.ReturnLocker;
import com.codex.lockertest.ui.zip.ReturnScreenPresentation;
import com.codex.lockertest.ui.zip.ZipPixelShell;
import com.codex.lockertest.ui.zip.ZipScreenAsset;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Server-snapshot-only native list over ZIP return pages 31-34. */
public final class ReturnLockerView extends FrameLayout {
    private static final int[] ROW_Y = {272, 327, 382, 437};

    public interface Listener {
        void onLockerSelected(ReturnLocker locker);
        void onLockerConfirmed(ReturnLocker locker);
        void onCancelRequested();
        default void onHomeRequested() { }
    }

    private final ZipPixelShell shell;
    private final FrameLayout content;
    private final FrameLayout listCard;
    private final Button[] rowButtons = new Button[ROW_Y.length];
    private final Button confirmButton;
    private final Button previousButton;
    private final Button nextButton;
    private final Button homeButton;
    private final Button safeReturnButton;
    private final TextView status;
    private final FrameLayout accessibilityBlocker;
    private List<ReturnLocker> visibleLockers = Collections.emptyList();
    private ReturnLocker selectedLocker;
    private ReturnScreenPresentation presentation;
    private Listener listener;
    private int pageIndex;

    public ReturnLockerView(Context context) {
        super(context);
        shell = new ZipPixelShell(context, ZipScreenAsset.RETURN_LOCKER_UNSELECTED);
        shell.setOnSafeHomeRequested(this::notifySafeHome);
        addView(shell, match());
        content = shell.contentLayer();

        listCard = new FrameLayout(context);
        listCard.setBackground(UiKit.roundedSolid(
                context, Color.rgb(247, 251, 252), designDp(context, 12),
                Color.rgb(60, 187, 207), designDp(context, 2)));
        listCard.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        listCard.setContentDescription(
                "这里只显示服务器登记在您名下的柜门，多个柜门将按顺序逐个办理");
        TextView title = nativeText(context, "选择要归还的柜门", 24,
                UiKit.NAVY, Typeface.BOLD);
        title.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, unit(context, 16), 0, 0);
        listCard.addView(title, match());
        place(content, listCard, 270, 190, 740, 360);

        for (int index = 0; index < ROW_Y.length; index++) {
            final int slot = index;
            Button row = opaqueButton(
                    context, "", 18, Color.rgb(235, 244, 246));
            row.setTextColor(UiKit.TEXT);
            row.setOnClickListener(view -> selectSlot(slot));
            rowButtons[index] = row;
            place(content, row, 325, ROW_Y[index], 630, 44);
        }

        previousButton = opaqueButton(context, "上一页", 14, UiKit.NAVY);
        previousButton.setOnClickListener(view -> changePage(-1));
        place(content, previousButton, 325, 494, 90, 36);
        nextButton = opaqueButton(context, "下一页", 14, UiKit.NAVY);
        nextButton.setOnClickListener(view -> changePage(1));
        place(content, nextButton, 865, 494, 90, 36);

        confirmButton = opaqueButton(context, "确认还柜", 18, UiKit.GREEN);
        confirmButton.setContentDescription("确认打开服务器授权的所选柜门");
        confirmButton.setOnClickListener(view -> confirmSelected());
        place(content, confirmButton, 550, 495, 180, 42);

        status = nativeText(context, "请选择本人柜门", 19,
                UiKit.TEXT, Typeface.BOLD);
        status.setGravity(Gravity.CENTER);
        status.setBackground(UiKit.roundedSolid(
                context, Color.rgb(250, 252, 252), designDp(context, 12),
                Color.rgb(159, 214, 219), designDp(context, 1)));
        status.setContentDescription("还柜柜门选择状态");
        place(content, status, 355, 225, 570, 250);
        status.setVisibility(GONE);

        homeButton = opaqueButton(context, "返回首页", 17, UiKit.GREEN);
        homeButton.setOnClickListener(view -> notifyHome());
        place(content, homeButton, 565, 480, 150, 46);

        accessibilityBlocker = new FrameLayout(context);
        accessibilityBlocker.setBackground(UiKit.roundedSolid(
                context, Color.rgb(250, 252, 252), designDp(context, 12),
                Color.rgb(159, 214, 219), designDp(context, 1)));
        accessibilityBlocker.setClickable(true);
        accessibilityBlocker.setFocusable(true);
        accessibilityBlocker.setImportantForAccessibility(
                IMPORTANT_FOR_ACCESSIBILITY_YES);
        accessibilityBlocker.setContentDescription("正在处理所选柜门，请勿重复操作");
        place(content, accessibilityBlocker, 355, 225, 570, 330);

        safeReturnButton = opaqueButton(
                context, "安全返回", 16, Color.rgb(121, 137, 139));
        safeReturnButton.setOnClickListener(view -> notifyCancel());
        place(shell.overlayLayer(), safeReturnButton, 1050, 110, 176, 38);

        setListVisibility(false);
        accessibilityBlocker.setVisibility(GONE);
        homeButton.setVisibility(GONE);
        safeReturnButton.setVisibility(GONE);
    }

    public void setListener(Listener listener) { this.listener = listener; }

    public void render(ReturnScreenPresentation presentation,
            List<ReturnLocker> lockers, ReturnLocker selected,
            CharSequence statusMessage, CharSequence countdownText) {
        if (presentation == null
                || presentation.surface() != ReturnScreenPresentation.Surface.LOCKER_LIST) {
            throw new IllegalArgumentException("locker-list presentation is required");
        }
        validateLockers(lockers);
        this.presentation = presentation;
        shell.setScreenAsset(presentation.asset());
        visibleLockers = Collections.unmodifiableList(new ArrayList<>(lockers));
        selectedLocker = selected != null && visibleLockers.contains(selected)
                ? selected : null;
        normalizePageForSelection();

        boolean listPage = presentation.asset() == ZipScreenAsset.RETURN_LOCKER_UNSELECTED
                || presentation.asset() == ZipScreenAsset.RETURN_LOCKER_SELECTED;
        boolean processing = presentation.asset() == ZipScreenAsset.RETURN_LOCKER_PROCESSING;
        boolean empty = presentation.asset() == ZipScreenAsset.RETURN_LOCKER_EMPTY;
        setListVisibility(listPage);
        status.setVisibility((processing || empty) ? VISIBLE : GONE);
        CharSequence normalizedStatus = normalize(statusMessage,
                empty ? "未查询到需要归还的柜门"
                        : "正在处理所选柜门，请勿重复操作…");
        status.setText(empty
                ? appendCountdown(normalizedStatus.toString(), countdownText)
                : normalizedStatus);
        status.setContentDescription(status.getText());

        accessibilityBlocker.setVisibility(processing ? VISIBLE : GONE);
        listCard.setImportantForAccessibility(processing
                ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : IMPORTANT_FOR_ACCESSIBILITY_YES);
        if (processing) accessibilityBlocker.bringToFront();

        refreshRows();
        boolean confirm = listPage && presentation.canConfirmLocker()
                && selectedLocker != null;
        confirmButton.setVisibility(listPage ? VISIBLE : GONE);
        setActionState(confirmButton, confirm);
        homeButton.setVisibility(presentation.canHome() ? VISIBLE : GONE);
        setActionState(homeButton, presentation.canHome());
        safeReturnButton.setVisibility(presentation.canBack() ? VISIBLE : GONE);
        setActionState(safeReturnButton, presentation.canBack());
    }

    public void render(ReturnScreenPresentation presentation,
            List<ReturnLocker> lockers, ReturnLocker selected,
            CharSequence statusMessage) {
        render(presentation, lockers, selected, statusMessage, null);
    }

    /** Compatibility overload. MainActivity uses the typed overload. */
    public void render(List<ReturnLocker> lockers,
            ReturnLocker selected, boolean busy, CharSequence statusMessage) {
        validateLockers(lockers);
        visibleLockers = Collections.unmodifiableList(new ArrayList<>(lockers));
        selectedLocker = selected != null && visibleLockers.contains(selected)
                ? selected : null;
        status.setText(normalize(statusMessage,
                busy ? "正在处理，请勿重复操作…" : "请选择一个柜门"));
    }

    public List<ReturnLocker> visibleLockers() { return visibleLockers; }

    private void refreshRows() {
        int start = pageIndex * ROW_Y.length;
        for (int slot = 0; slot < rowButtons.length; slot++) {
            Button row = rowButtons[slot];
            int index = start + slot;
            if (index >= visibleLockers.size()) {
                row.setText("空白");
                row.setContentDescription("空白柜门行，不可选择");
                row.setVisibility(INVISIBLE);
                setActionState(row, false);
                continue;
            }
            ReturnLocker locker = visibleLockers.get(index);
            boolean selected = locker.equals(selectedLocker);
            row.setVisibility(VISIBLE);
            row.setText(locker.areaDisplayName() + "  ·  " + locker.displayLabel());
            row.setTextColor(selected ? Color.WHITE : UiKit.TEXT);
            row.setBackground(UiKit.roundedSolid(
                    getContext(), selected ? UiKit.GREEN : Color.rgb(235, 244, 246),
                    designDp(getContext(), 8),
                    selected ? UiKit.DARK_GREEN : Color.rgb(179, 208, 213),
                    designDp(getContext(), 1)));
            row.setContentDescription(locker.areaDisplayName() + " "
                    + locker.displayLabel() + (selected ? "，已选择" : "，点击选择"));
            setActionState(row, presentation != null && presentation.canSelect());
        }
        int pageCount = Math.max(1,
                (visibleLockers.size() + ROW_Y.length - 1) / ROW_Y.length);
        boolean listEnabled = presentation != null && presentation.canSelect();
        previousButton.setVisibility(pageCount > 1 ? VISIBLE : GONE);
        nextButton.setVisibility(pageCount > 1 ? VISIBLE : GONE);
        setActionState(previousButton, listEnabled && pageIndex > 0);
        setActionState(nextButton, listEnabled && pageIndex + 1 < pageCount);
    }

    private void selectSlot(int slot) {
        if (presentation == null || !presentation.canSelect()) return;
        int index = pageIndex * ROW_Y.length + slot;
        if (index < 0 || index >= visibleLockers.size()) return;
        Listener current = listener;
        if (current != null) current.onLockerSelected(visibleLockers.get(index));
    }

    private void confirmSelected() {
        if (presentation == null || !presentation.canConfirmLocker()
                || selectedLocker == null) return;
        Listener current = listener;
        if (current != null) current.onLockerConfirmed(selectedLocker);
    }

    private void changePage(int delta) {
        if (presentation == null || !presentation.canSelect()) return;
        int pageCount = Math.max(1,
                (visibleLockers.size() + ROW_Y.length - 1) / ROW_Y.length);
        pageIndex = Math.max(0, Math.min(pageCount - 1, pageIndex + delta));
        refreshRows();
    }

    private void normalizePageForSelection() {
        int pageCount = Math.max(1,
                (visibleLockers.size() + ROW_Y.length - 1) / ROW_Y.length);
        if (selectedLocker != null) {
            pageIndex = visibleLockers.indexOf(selectedLocker) / ROW_Y.length;
        } else if (pageIndex >= pageCount) {
            pageIndex = pageCount - 1;
        }
    }

    private void setListVisibility(boolean visible) {
        int visibility = visible ? VISIBLE : GONE;
        listCard.setVisibility(visibility);
        for (Button row : rowButtons) row.setVisibility(visibility);
        previousButton.setVisibility(visibility);
        nextButton.setVisibility(visibility);
        confirmButton.setVisibility(visibility);
    }

    private void notifyCancel() {
        if (presentation == null || !presentation.canBack()) return;
        Listener current = listener;
        if (current != null) current.onCancelRequested();
    }

    private void notifyHome() {
        if (presentation == null || !presentation.canHome()) return;
        Listener current = listener;
        if (current != null) current.onHomeRequested();
    }

    private void notifySafeHome() {
        if (presentation != null && presentation.canBack()) notifyCancel();
        else if (presentation != null && presentation.canHome()) notifyHome();
    }

    private static void validateLockers(List<ReturnLocker> lockers) {
        if (lockers == null) throw new IllegalArgumentException("lockers are required");
        for (ReturnLocker locker : lockers) {
            if (locker == null) throw new IllegalArgumentException("locker is required");
        }
    }

    private static String appendCountdown(String message, CharSequence countdown) {
        return countdown == null || countdown.toString().trim().isEmpty()
                ? message : message + "\n" + countdown;
    }

    private static void setActionState(View action, boolean enabled) {
        action.setEnabled(enabled);
        action.setClickable(enabled);
        action.setFocusable(enabled);
        action.setImportantForAccessibility(enabled
                ? IMPORTANT_FOR_ACCESSIBILITY_YES
                : IMPORTANT_FOR_ACCESSIBILITY_NO);
        action.setAlpha(1f);
    }

    private static CharSequence normalize(CharSequence value, CharSequence fallback) {
        return value == null || value.toString().trim().isEmpty() ? fallback : value;
    }

    private static Button opaqueButton(
            Context context, String label, float size, int background) {
        return ZipKioskShell.scaleButton(UiKit.button(
                context, label, size, background, Color.WHITE,
                designDp(context, 9)), size);
    }

    private static TextView nativeText(Context context, String value,
            float size, int color, int style) {
        return ZipKioskShell.scaledText(
                UiKit.text(context, value, size, color, style), size);
    }

    private static void place(FrameLayout parent, View child,
            int x, int y, int width, int height) {
        LayoutParams params = new LayoutParams(
                unit(parent.getContext(), width), unit(parent.getContext(), height));
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.leftMargin = unit(parent.getContext(), x);
        params.topMargin = unit(parent.getContext(), y);
        parent.addView(child, params);
    }

    private static int unit(Context context, float value) {
        return ZipKioskShell.unit(context, value);
    }

    private static float designDp(Context context, float value) {
        return ZipKioskShell.designDp(context, value);
    }

    private static LayoutParams match() {
        return new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
