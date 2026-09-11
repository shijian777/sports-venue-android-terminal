package com.codex.lockertest.ui.business;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.ScrollView;

import com.codex.lockertest.business.journey.OnlineCustomerSession;
import com.codex.lockertest.business.journey.OnlineCustomerSnapshot;
import com.codex.lockertest.ui.UiKit;
import com.codex.lockertest.ui.KioskPolish;
import com.codex.lockertest.ui.ZipKioskShell;
import com.codex.lockertest.ui.zip.ZipPixelShell;
import com.codex.lockertest.ui.zip.ZipScreenAsset;

import java.util.List;

/** Server-backed display. Physical opening is exposed only as a selected server cabinet id. */
public final class OnlineCabinetView extends FrameLayout {
    public interface Listener {
        void onRegion(long areaId);
        void onPage(int page);
        void onRetry();
        void onHome();
        default void onConfirmOpen(long fcId) { }
        default void onConfirmReturn(long fcId) { }
        default void onInteraction() { }
    }

    private final ZipPixelShell shell;
    private final FrameLayout body;
    private final Listener listener;
    private final boolean configuredOpening;
    private final View identityBackdrop;
    private OnlineCustomerSnapshot snapshot;
    private int areaWindow;
    private int usedPage;
    private long selectedId;
    private FrameLayout blockingModal;

    public OnlineCabinetView(Context context, Listener listener) {
        this(context, listener, false);
    }

    public OnlineCabinetView(Context context, Listener listener, boolean configuredOpening) {
        this(context, listener, configuredOpening, null);
    }

    public OnlineCabinetView(Context context, Listener listener, boolean configuredOpening,
            View identityBackdrop) {
        super(context);
        if (listener == null) throw new IllegalArgumentException("Listener required");
        this.listener = listener;
        this.configuredOpening = configuredOpening;
        this.identityBackdrop = identityBackdrop;
        if (identityBackdrop != null) disableBackdrop(identityBackdrop);
        shell = new ZipPixelShell(context, ZipScreenAsset.LOCKER_UNSELECTED);
        shell.setOnSafeHomeRequested(listener::onHome);
        addView(shell, new LayoutParams(-1, -1));
        body = new FrameLayout(context);
        shell.contentLayer().addView(body, new LayoutParams(-1, -1));
    }

    public void render(OnlineCustomerSnapshot next) {
        if (next == null) throw new IllegalArgumentException("Snapshot required");
        if (snapshot != next) {
            selectedId = 0;
            usedPage = 0;
        }
        snapshot = next;
        renderContent();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (blockingModal == null && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            listener.onInteraction();
        }
        return super.dispatchTouchEvent(event);
    }

    public void showPhysicalUnavailableModal() {
        if (blockingModal != null) return;
        FrameLayout overlay = new FrameLayout(getContext());
        overlay.setBackgroundColor(Color.argb(176, 0, 0, 0));
        overlay.setClickable(true);
        overlay.setFocusable(true);

        FrameLayout card = new FrameLayout(getContext());
        card.setBackground(UiKit.roundedSolid(getContext(), Color.WHITE, 12,
                Color.rgb(214, 220, 221), 1));
        placeIn(overlay, card, 360, 210, 560, 330);

        TextView title = text("网络或设备暂不可用", 27, UiKit.GREEN);
        placeIn(card, title, 35, 35, 490, 55);
        TextView detail = text("未分配柜门，也未发送开柜指令。\n请返回首页后检查网络或联系工作人员。",
                19, UiKit.TEXT);
        detail.setMaxLines(4);
        placeIn(card, detail, 45, 105, 470, 95);
        Button home = button("返回首页", "返回首页并清除认证会话", UiKit.GREEN);
        home.setOnClickListener(v -> listener.onHome());
        placeIn(card, home, 165, 235, 230, 52);

        blockingModal = overlay;
        addView(overlay, new LayoutParams(-1, -1));
        overlay.bringToFront();
    }

    private void renderContent() {
        // Replace only the original native data area, leaving original header/footer/brand intact.
        body.removeAllViews();
        if (identityBackdrop != null && (snapshot.state() == OnlineCustomerSnapshot.State.AUTHENTICATING
                || snapshot.state() == OnlineCustomerSnapshot.State.FAILED)) {
            body.addView(identityBackdrop, new FrameLayout.LayoutParams(-1, -1));
            renderStatus();
            return;
        }
        boolean open = snapshot.state() == OnlineCustomerSnapshot.State.BROWSING_OPEN;
        if (open) KioskPolish.addBackdrop(body, false);
        TextView cover = text("", 18, UiKit.TEXT);
        cover.setBackgroundColor(Color.WHITE);
        place(cover, open ? 37 : 26, open ? 137 : 108, open ? 1206 : 1228, open ? 587 : 612);
        boolean used = snapshot.state() == OnlineCustomerSnapshot.State.BROWSING_USED;
        TextView title = text(titleText(), 32, Color.WHITE);
        title.setBackgroundColor(UiKit.GREEN);
        place(title, open ? 351 : 322, open ? 36 : 30, open ? 578 : 636, open ? 61 : 72);
        if (open) {
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit(36));
            title.setBackgroundColor(KioskPolish.GREEN);
        } else place(text(used ? "用柜详情 · 请选择需要归还的柜门" : "请选择您要使用的柜子", 18, UiKit.GREEN),
                    280, 110, 720, 34);
        Button back = button("返回首页", "返回首页并清除认证会话", Color.rgb(128, 140, 141));
        back.setOnClickListener(v -> listener.onHome());
        if (open) {
            back.setText("取　消");
            back.setTextColor(UiKit.MUTED);
            back.setBackground(KioskPolish.surface(getContext(), Color.rgb(232, 236, 234), Color.TRANSPARENT, 25));
            back.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit(27));
            place(back, 304, 657, 192, 46);
        } else if (used) place(back, 362, 654, 230, 48);
        if (open) renderGrid();
        else if (used) renderUsed();
        else renderStatus();
        if (open || used) {
            String noticeText;
            if (used) {
                noticeText = selectedId == 0
                        ? "请选择本人名下的柜门后确认还柜"
                        : "已选择：" + selectedUsedCabinetName();
            } else if (!configuredOpening) {
                noticeText = "当前仅查询柜门，开柜操作暂未开放";
            } else if (!snapshot.physicalOpenEnabled()) {
                noticeText = "开柜设备当前不可用，请返回首页后联系工作人员";
            } else if (selectedId == 0) {
                noticeText = "请选择一个空闲柜门后确认使用";
            } else {
                noticeText = selectedDisplayText();
            }
            TextView notice = text(noticeText, open ? 20 : 16, UiKit.TEXT);
            if (open) {
                notice.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
                notice.setSingleLine(true);
                notice.setEllipsize(null);
                android.widget.HorizontalScrollView noticeScroll = new android.widget.HorizontalScrollView(getContext());
                noticeScroll.setFillViewport(true);
                noticeScroll.addView(notice, new FrameLayout.LayoutParams(-2, -1));
                place(noticeScroll, 164, 611, 735, 37);
                addRandomSelection();
            } else place(notice, 160, 610, 1040, 31);
            if (used) {
                Button confirm = button("确认还柜", "确认还柜所选柜门",
                        selectedId != 0 ? UiKit.GREEN : Color.rgb(180, 190, 192));
                boolean enabled = selectedId != 0;
                final long confirmedId = selectedId;
                confirm.setEnabled(enabled);
                confirm.setClickable(enabled);
                confirm.setOnClickListener(v -> {
                    if (!confirm.isEnabled() || confirmedId == 0 || selectedId != confirmedId
                            || snapshot.state() != OnlineCustomerSnapshot.State.BROWSING_USED) return;
                    confirm.setEnabled(false);
                    listener.onConfirmReturn(confirmedId);
                });
                place(confirm, 652, 654, 260, 48);
            } else if (open && configuredOpening) {
                Button confirm = button("确认使用", "确认使用所选柜门",
                        selectedId != 0 && snapshot.physicalOpenEnabled()
                                ? UiKit.GREEN : Color.rgb(180, 190, 192));
                boolean enabled = selectedId != 0 && snapshot.physicalOpenEnabled();
                final long confirmedId = selectedId;
                confirm.setEnabled(enabled);
                confirm.setClickable(enabled);
                confirm.setOnClickListener(v -> {
                    if (!confirm.isEnabled() || confirmedId == 0 || selectedId != confirmedId
                            || snapshot.state() != OnlineCustomerSnapshot.State.BROWSING_OPEN) return;
                    confirm.setEnabled(false);
                    listener.onConfirmOpen(confirmedId);
                });
                confirm.setText("确　认");
                confirm.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit(28));
                confirm.setBackground(KioskPolish.surface(getContext(), enabled ? KioskPolish.GREEN : Color.rgb(180,190,192), Color.TRANSPARENT,25));
                place(confirm, 543, 657, 192, 46);
            } else {
                Button disabled = button("确认开柜（未接入）",
                        snapshot.physicalActionUnavailableReason(), Color.rgb(180, 190, 192));
                disabled.setEnabled(false);
                disabled.setClickable(false);
                if (open) place(disabled, 543, 657, 192, 46);
                else place(disabled, 652, 654, 260, 48);
            }
        }
    }

    private void renderGrid() {
        List<List<OnlineCustomerSnapshot.Slot>> rows = snapshot.displayRows();
        renderRegions();
        place(text("□ 空闲", 18, KioskPolish.GREEN), 822, 144, 80, 42);
        place(text("■ 选中", 18, KioskPolish.GREEN), 912, 144, 80, 42);
        place(text("■ 使用中", 18, Color.rgb(172,91,25)), 1002, 144, 103, 42);
        place(text("■ 不可用", 18, Color.rgb(190,50,68)), 1115, 144, 103, 42);
        for (int row = 0; row < OnlineCustomerSnapshot.MAX_DISPLAY_ROWS; row++) {
            List<OnlineCustomerSnapshot.Slot> slots = row < rows.size() ? rows.get(row) : null;
            View floor = new View(getContext());
            floor.setBackground(KioskPolish.floorFrame(getContext()));
            place(floor, 161, 205 + row * 103, 1062, 90);
            place(text(layerLabel(slots), 25, KioskPolish.GREEN), 77, 220 + row * 103, 62, 60);
            for (int column = 0; column < OnlineCustomerSnapshot.SLOTS_PER_DISPLAY_ROW; column++) {
                OnlineCustomerSnapshot.Slot slot = slots != null && column < slots.size() ? slots.get(column) : null;
                boolean selected = slot != null && slot.fcId() == selectedId;
                // Read-only mode can inspect occupied cabinets: never hide their server status.
                String status = selected && slot.status() == 0 ? "已选"
                        : slot == null ? "" : statusLabel(slot.status());
                String label = slot == null ? "—" : slot.cabinetLabel() + "\n" + status;
                int fill = slot == null ? Color.rgb(243, 247, 246)
                        : selected && slot.status() == 0 ? KioskPolish.GREEN
                        : slot.status() == 0 ? Color.WHITE
                        : slot.status() >= 4 ? Color.rgb(249, 233, 236) : Color.rgb(255, 241, 222);
                int ink = selected && slot != null && slot.status() == 0 ? Color.WHITE : slot == null ? Color.rgb(180, 197, 192)
                        : slot.status() == 0 ? Color.rgb(0, 120, 102)
                        : slot.status() >= 4 ? Color.rgb(186, 47, 67) : Color.rgb(157, 79, 10);
                int stroke = slot == null ? Color.rgb(221, 232, 229) : selected || slot.status() == 0
                        ? Color.rgb(87, 187, 162) : slot.status() >= 4
                        ? Color.rgb(237, 152, 163) : Color.rgb(244, 177, 105);
                boolean selectable = slot != null && (!configuredOpening || slot.status() == 0);
                Button cell = button(label, slot == null ? "空位置" : "柜门 " + slot.cabinetLabel()
                        + "，" + status
                        + (configuredOpening && slot.status() == 0 ? "，可选择" : "，仅可查看"), fill);
                cell.setBackground(KioskPolish.surface(getContext(), fill, stroke, 8));
                cell.setTextColor(ink);
                cell.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit(31));
                cell.setIncludeFontPadding(false);
                int x = 171 + Math.round(column * 131.5f);
                int right = 171 + Math.round((column + 1) * 131.5f) - 10;
                if (slot != null) {
                    android.text.TextPaint labelPaint = new android.text.TextPaint(cell.getPaint());
                    int labelSize = 32;
                    int labelWidth = unit(right - x) - cell.getPaddingLeft() - cell.getPaddingRight();
                    labelPaint.setTextSize(unit(labelSize));
                    if (labelPaint.measureText(slot.cabinetLabel()) > labelWidth) {
                        labelSize = 21;
                        labelPaint.setTextSize(unit(labelSize));
                    }
                    String displayLabel = TextUtils.ellipsize(slot.cabinetLabel(), labelPaint,
                            labelWidth, TextUtils.TruncateAt.END).toString();
                    android.text.SpannableString caption = new android.text.SpannableString(KioskPolish.twoLines(getContext(),
                            displayLabel, "  " + status, labelSize, 13, ink));
                    int mark = displayLabel.length() + 1;
                    caption.setSpan(new android.text.style.ImageSpan(KioskPolish.statusIcon(getContext(),slot.status(),selected,ink),
                            android.text.style.ImageSpan.ALIGN_BASELINE),mark,mark+1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    cell.setText(caption);
                }
                cell.setMaxLines(2);
                cell.setEnabled(selectable);
                if (selectable) cell.setOnClickListener(v -> { selectedId = slot.fcId(); renderContent(); });
                place(cell, x, 216 + row * 103, right - x, 68);
            }
        }
        if (rows.isEmpty()) place(text("此页暂无柜门", 22, UiKit.MUTED), 340, 344, 640, 96);
        List<Integer> pages = snapshot.pages();
        int index = pages.indexOf(snapshot.selectedPage());
        Button previous = button("上一页", "上一页柜门", KioskPolish.GREEN);
        previous.setEnabled(index > 0);
        previous.setOnClickListener(v -> listener.onPage(pages.get(index - 1)));
        previous.setTextColor(KioskPolish.buttonInk(KioskPolish.INK));
        previous.setBackground(KioskPolish.buttonSurface(getContext(),Color.rgb(247,251,248),Color.rgb(180,214,200),5));
        place(previous, 964, 613, 96, 34);
        place(text((index < 0 ? "—" : String.valueOf(index + 1)) + " / " + pages.size(), 21, UiKit.TEXT),
                1065, 613, 56, 34);
        Button next = button("下一页", "下一页柜门", KioskPolish.GREEN);
        next.setEnabled(index >= 0 && index + 1 < pages.size());
        next.setOnClickListener(v -> listener.onPage(pages.get(index + 1)));
        next.setTextColor(KioskPolish.buttonInk(KioskPolish.INK));
        next.setBackground(KioskPolish.buttonSurface(getContext(),Color.rgb(247,251,248),Color.rgb(180,214,200),5));
        place(next, 1125, 613, 96, 34);
    }

    private void renderRegions() {
        List<OnlineCustomerSession.Region> regions = snapshot.regions();
        areaWindow = Math.min(areaWindow, Math.max(0, (regions.size() - 1) / 4));
        Button previous = button("‹", "上一组区域", UiKit.NAVY);
        previous.setEnabled(areaWindow > 0);
        previous.setOnClickListener(v -> { areaWindow--; renderContent(); });
        if (regions.size() > 4) place(previous, 77, 144, 36, 42);
        for (int i = 0; i < 4; i++) {
            int index = areaWindow * 4 + i;
            if (index >= regions.size()) break;
            OnlineCustomerSession.Region region = regions.get(index);
            boolean selected = snapshot.selectedRegion() != null && region.id() == snapshot.selectedRegion().id();
            Button button = button(region.name(), "柜区 " + region.name(),
                    selected ? Color.rgb(234, 247, 242) : Color.WHITE);
            button.setTextColor(selected ? Color.rgb(0, 124, 104) : UiKit.MUTED);
            button.setOnClickListener(v -> listener.onRegion(region.id()));
            button.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit(region.name().length()>5 ? 20 : 25));
            place(button, 161 + 151 * i, 144, 138, 45);
            if (selected) { View underline = new View(getContext());underline.setBackgroundColor(KioskPolish.GREEN);place(underline,174+151*i,187,112,3); }
        }
        Button next = button("›", "下一组区域", UiKit.NAVY);
        next.setEnabled((areaWindow + 1) * 4 < regions.size());
        next.setOnClickListener(v -> { areaWindow++; renderContent(); });
        if (regions.size() > 4) place(next, 776, 144, 36, 42);
    }

    /** Choose only a displayed free cabinet; the existing confirmation is the sole open action. */
    private void addRandomSelection() {
        java.util.ArrayList<OnlineCustomerSnapshot.Slot> free = new java.util.ArrayList<>();
        for (List<OnlineCustomerSnapshot.Slot> row : snapshot.displayRows()) {
            for (OnlineCustomerSnapshot.Slot slot : row) if (slot.status() == 0) free.add(slot);
        }
        Button random = button("随机开柜", "随机选择本页空闲柜门，确认后开柜", UiKit.BLUE);
        random.setEnabled(configuredOpening && snapshot.physicalOpenEnabled() && !free.isEmpty());
        random.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit(27));
        random.setOnClickListener(v -> {
            if (!random.isEnabled() || snapshot.state() != OnlineCustomerSnapshot.State.BROWSING_OPEN) return;
            selectedId = free.get(new java.util.Random().nextInt(free.size())).fcId();
            renderContent();
        });
        random.setTextColor(KioskPolish.buttonInk(Color.WHITE));
        random.setBackground(KioskPolish.buttonSurface(getContext(),Color.rgb(16,125,203),Color.TRANSPARENT,25));
        place(random, 782, 657, 192, 46);
    }

    private void renderUsed() {
        List<OnlineCustomerSnapshot.UsedCabinetView> used = snapshot.usedCabinets();
        usedPage = Math.min(usedPage, Math.max(0, (used.size() - 1) / 4));
        if (used.isEmpty()) place(text("当前没有登记在您名下的柜门", 24, UiKit.TEXT), 290, 280, 700, 100);
        else place(text("柜门 / 开始时间 / 使用时长", 19, UiKit.GREEN), 220, 156, 840, 42);
        for (int row = 0; row < 4; row++) {
            int index = usedPage * 4 + row;
            if (index >= used.size()) break;
            OnlineCustomerSnapshot.UsedCabinetView cabinet = used.get(index);
            Button item = button(cabinet.name() + "　已使用 " + cabinet.useTimeMinutes()
                    + " 分钟\n开始时间：" + cabinet.startUse(),
                    "本人柜门 " + cabinet.name() + "，选择还柜",
                    cabinet.fcId() == selectedId ? UiKit.GREEN : Color.rgb(235, 246, 243));
            item.setTextColor(cabinet.fcId() == selectedId ? Color.WHITE : UiKit.TEXT);
            item.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit(18));
            item.setMaxLines(2);
            item.setOnClickListener(v -> { selectedId = cabinet.fcId(); renderContent(); });
            place(item, 220, 211 + row * 82, 840, 70);
        }
        Button previous = button("上一页", "上一页本人柜门", UiKit.NAVY);
        previous.setEnabled(usedPage > 0);
        previous.setOnClickListener(v -> { usedPage--; renderContent(); });
        place(previous, 220, 558, 110, 40);
        int count = Math.max(1, (used.size() + 3) / 4);
        place(text("第 " + (usedPage + 1) + " / " + count + " 页", 16, UiKit.TEXT), 520, 558, 240, 40);
        Button next = button("下一页", "下一页本人柜门", UiKit.NAVY);
        next.setEnabled((usedPage + 1) * 4 < used.size());
        next.setOnClickListener(v -> { usedPage++; renderContent(); });
        place(next, 950, 558, 110, 40);
    }

    private void renderStatus() {
        String message;
        switch (snapshot.state()) {
            case AUTHENTICATING: message = "正在通过服务器验证身份…"; break;
            case LOADING_PREVIEW: message = "正在读取服务器柜门布局…"; break;
            case LOADING_USED: message = "正在查询本人柜门…"; break;
            case AUTHORIZING_OPEN: message = "正在向服务器确认所选柜门，请稍候"; break;
            case OPENING: message = openingText(); break;
            case OPEN_SUCCESS:
                message = (TextUtils.isEmpty(snapshot.selectedCabinetLabel())
                        ? "" : "柜门：" + snapshot.selectedCabinetLabel() + "\n")
                        + "柜门已打开，请存放物品后关闭柜门";
                break;
            case OPEN_FAILED: message = openFailureText(); break;
            case RETURNING:
                message = "正在向服务器提交所选柜门的还柜请求，请勿重复操作";
                break;
            case RETURN_SUCCEEDED:
                message = (TextUtils.isEmpty(snapshot.selectedCabinetLabel())
                        ? "" : "柜门：" + snapshot.selectedCabinetLabel() + "\n")
                        + "服务器已确认还柜成功";
                break;
            case RETURN_FAILED: message = returnFailureText(); break;
            default: message = failureText(snapshot.failure()); break;
        }
        if (snapshot.isBusy() && !snapshot.displayRows().isEmpty()
                && snapshot.purpose() != com.codex.lockertest.business.journey.OnlineCustomerCoordinator.Purpose.RETURN_CABINET) renderGrid();
        for (int i=0;i<body.getChildCount();i++) disableBackdrop(body.getChildAt(i));
        FrameLayout overlay = new FrameLayout(getContext());
        overlay.setBackgroundColor(snapshot.isBusy() ? Color.argb(184,16,41,50) : Color.argb(112,20,35,49));
        overlay.setClipChildren(false);
        overlay.setClickable(true);
        place(overlay, 0, 0, 1280, 800);
        if (snapshot.isBusy()) {
            View indicator=KioskPolish.busyIndicator(getContext());
            indicator.setContentDescription("服务器操作进行中");
            placeIn(overlay,indicator,560,333,160,28);
            placeIn(overlay,text(titleText()+"，请稍候",28,Color.WHITE),330,379,620,48);
            TextView busyDetail=text(message+"\n处理中，请勿重复点击",20,Color.WHITE);
            busyDetail.setMaxLines(3);
            placeIn(overlay,busyDetail,330,439,620,82);
            Button cancel=button("取消并返回首页","返回首页并清除认证会话",Color.rgb(66,100,110));
            cancel.setOnClickListener(v -> listener.onHome());
            placeIn(overlay,cancel,520,548,240,46);
            return;
        }
        FrameLayout card = new FrameLayout(getContext());
        card.setBackground(KioskPolish.promptSurface());
        KioskPolish.decoratePrompt(card);
        placeIn(overlay, card, 330, 225, 620, 400);
        TextView heading = text(snapshot.isBusy() ? "请稍候" : "温馨提示", 30, UiKit.GREEN);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_PX,unit(35));
        placeIn(card, heading, 180, 54, 262, 62);
        TextView subheading = text(titleText(), 26, Color.rgb(77,97,88));
        placeIn(card, subheading, 40, 128, 540, 42);
        TextView detail = text(message, 21, Color.rgb(99,119,108));
        detail.setMaxLines(Integer.MAX_VALUE);
        detail.setEllipsize(null);
        detail.setLineSpacing(unit(7),1f);
        ScrollView messageScroll=new ScrollView(getContext());
        messageScroll.setFillViewport(true);
        messageScroll.addView(detail,new ScrollView.LayoutParams(-1,-2));
        placeIn(card,messageScroll,40,178,540,92);
        Button home = button(snapshot.isBusy() ? "取消并返回首页" : "返回首页",
                "返回首页并清除认证会话", UiKit.GREEN);
        home.setOnClickListener(v -> listener.onHome());
        if (snapshot.isBusy()) {
            ProgressBar progress = new ProgressBar(getContext());
            progress.setIndeterminate(true);
            progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Color.rgb(23, 119, 216)));
            progress.setContentDescription("服务器操作进行中");
            placeIn(card, progress, 292, 265, 46, 46);
        }
        if (snapshot.state() == OnlineCustomerSnapshot.State.FAILED
                || snapshot.state() == OnlineCustomerSnapshot.State.RETURN_FAILED) {
            boolean returnFailure = snapshot.state() == OnlineCustomerSnapshot.State.RETURN_FAILED;
            Button retry = button(returnFailure ? "重新查询本人柜门" : "重新尝试",
                    returnFailure ? "重新查询本人柜门" : "重新尝试服务器查询", UiKit.GREEN);
            retry.setOnClickListener(v -> listener.onRetry());
            home.setBackground(UiKit.roundedSolid(getContext(), Color.rgb(220, 231, 232), 24, Color.TRANSPARENT, 0));
            home.setTextColor(UiKit.TEXT);
            placeIn(card, home, 70, 313, 230, 48);
            placeIn(card, retry, 330, 313, 230, 48);
            placeIn(card, text("8 秒后自动返回首页", 17, UiKit.TEXT), 100, 273, 430, 30);
        } else if (snapshot.state() == OnlineCustomerSnapshot.State.OPEN_SUCCESS
                || snapshot.state() == OnlineCustomerSnapshot.State.OPEN_FAILED
                || snapshot.state() == OnlineCustomerSnapshot.State.RETURN_SUCCEEDED) {
            placeIn(card, text("8 秒后自动返回首页", 17, UiKit.TEXT), 100, 273, 430, 30);
            placeIn(card, home, 175, 313, 280, 48);
        } else {
            placeIn(card, home, 175, 313, 280, 48);
        }
    }

    private String titleText() {
        switch (snapshot.state()) {
            case AUTHENTICATING: return snapshot.purpose() == com.codex.lockertest.business.journey.OnlineCustomerCoordinator.Purpose.RETURN_CABINET
                    ? "离场还柜 · 身份验证" : "正在验证身份";
            case FAILED: return "本次操作未完成";
            case LOADING_PREVIEW: return "正在读取柜区";
            case LOADING_USED: return "正在查询本人柜门";
            case BROWSING_OPEN: return "请选择您要使用的柜子";
            case BROWSING_USED: return "本人正在使用的柜门";
            case AUTHORIZING_OPEN: return "正在确认柜门权限";
            case OPENING: return "正在开柜";
            case OPEN_SUCCESS: return "开柜成功";
            case OPEN_FAILED: return openFailureTitle();
            case RETURNING: return "正在确认还柜";
            case RETURN_SUCCEEDED: return "还柜成功";
            case RETURN_FAILED: return "未确认还柜结果";
            default: return "服务器柜门列表";
        }
    }

    private String selectedDisplayText() {
        String cabinet = "已选择柜门";
        for (OnlineCustomerSnapshot.Row row : snapshot.rows()) {
            for (OnlineCustomerSnapshot.Slot slot : row.slots()) {
                if (slot.fcId() == selectedId) {
                    cabinet = "已选择：" + slot.cabinetLabel();
                    break;
                }
            }
        }
        String area = snapshot.selectedRegion() == null ? ""
                : "　柜区：" + snapshot.selectedRegion().name();
        return cabinet + area;
    }

    private String selectedUsedCabinetName() {
        for (OnlineCustomerSnapshot.UsedCabinetView cabinet : snapshot.usedCabinets()) {
            if (cabinet.fcId() == selectedId) return cabinet.name();
        }
        return "本人柜门";
    }

    private String openingText() {
        if (snapshot.physicalState() == null) return "正在准备开柜";
        switch (snapshot.physicalState()) {
            case WAITING_ACK: return "等待锁板返回";
            case CONNECTING: return "正在连接开柜设备";
            case QUIETING: return "正在准备开柜设备";
            default: return "正在准备开柜";
        }
    }

    private String openFailureTitle() {
        switch (snapshot.failure()) {
            case PHYSICAL_OPEN_FAILED:
            case TIMEOUT:
                return "未确认开柜结果";
            case OPEN_AUTHORIZATION_FAILED:
                return "柜门分配未通过";
            case CONFIGURATION:
            case INVALID_SERVER_LAYOUT:
                return "开柜配置不可用";
            default:
                return "开柜未完成";
        }
    }

    private String openFailureText() {
        switch (snapshot.failure()) {
            case PHYSICAL_OPEN_FAILED:
            case TIMEOUT:
                return "无法确认柜门是否已打开，请先检查柜门；如有疑问请联系工作人员。\n请勿重复分配柜门。";
            case OPEN_AUTHORIZATION_FAILED:
                return snapshot.physicalDetail();
            case CONFIGURATION:
            case INVALID_SERVER_LAYOUT:
                return "柜门开柜配置不完整，请联系工作人员处理。";
            default:
                return "本次开柜未完成，请返回首页后联系工作人员。";
        }
    }

    private String returnFailureText() {
        if (snapshot.failure() == OnlineCustomerSnapshot.Failure.REMOTE_REJECTED) {
            return "服务器未通过还柜请求。请重新查询本人柜门后再决定是否重试。";
        }
        return "无法确认服务器是否已完成还柜。请先重新查询本人柜门；查询前请勿再次提交还柜。";
    }

    public static String failureText(OnlineCustomerSnapshot.Failure failure) {
        return failure.message();
    }

    private static String statusLabel(int status) {
        switch (status) {
            case 0: return "空闲";
            case 1: return "使用中";
            case 2: return "预留";
            case 3: return "租赁";
            default: return "维修";
        }
    }

    private String layerLabel(List<OnlineCustomerSnapshot.Slot> slots) {
        if (slots == null || slots.isEmpty()) return "";
        for (OnlineCustomerSnapshot.Row row : snapshot.rows()) {
            if (row.layerKey() > 0 && row.slots().containsAll(slots)) return row.layerKey() + "层";
        }
        return "";
    }

    private static void disableBackdrop(View view) {
        view.setEnabled(false);
        view.setClickable(false);
        view.setFocusable(false);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) disableBackdrop(group.getChildAt(index));
        }
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit(size));
        view.setTypeface(KioskPolish.typeface(getContext(), size>=24));
        view.setIncludeFontPadding(false);
        view.setGravity(Gravity.CENTER);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setMaxLines(3);
        view.setContentDescription(value);
        return view;
    }

    private Button button(String value, String description, int color) {
        Button button = UiKit.button(getContext(), value, 18, color, Color.WHITE, 8);
        button.setTypeface(KioskPolish.typeface(getContext(),true));
        button.setIncludeFontPadding(false);
        button.setStateListAnimator(null);
        button.setElevation(0f);
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit(18));
        button.setPadding(unit(4), 0, unit(4), 0);
        // Fixed kiosk buttons must lay out within their real width, not a scrolling text canvas.
        button.setHorizontallyScrolling(false);
        button.setMaxLines(1);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setContentDescription(description);
        return button;
    }

    private int unit(int value) { return ZipKioskShell.unit(getContext(), value); }
    private void place(View view, int x, int y, int width, int height) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(unit(width), unit(height));
        params.leftMargin = unit(x); params.topMargin = unit(y);
        body.addView(view, params);
    }
    private void placeIn(FrameLayout parent, View view, int x, int y, int width, int height) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(unit(width), unit(height));
        params.leftMargin = unit(x); params.topMargin = unit(y);
        parent.addView(view, params);
    }
}
