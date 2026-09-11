package com.codex.lockertest.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.codex.lockertest.model.FeatureAvailability;
import com.codex.lockertest.model.UnlockMethod;
import com.codex.lockertest.ui.zip.ZipBrand;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class HomeView extends FrameLayout {
    public interface Listener {
        void onMethodSelected(UnlockMethod method);

        void onAdminRequested();
    }

    private static final long ADMIN_HOLD_MILLIS = 5_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clockFormat =
            new SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.CHINA);
    private final FeatureAvailability availability;
    private final HoldGestureTracker holdTracker;
    private final TextView clock;
    private final Runnable clockTicker;
    private final Runnable adminHold;

    private Listener listener;

    public HomeView(Context context, FeatureAvailability availability) {
        super(context);
        if (availability == null) {
            throw new IllegalArgumentException("Feature availability is required");
        }
        this.availability = availability;
        holdTracker = new HoldGestureTracker(
                ADMIN_HOLD_MILLIS, UiKit.dp(context, 18));

        addView(new TechBackgroundView(context), match());

        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(
                UiKit.dp(context, 20), UiKit.dp(context, 10),
                UiKit.dp(context, 20), UiKit.dp(context, 9));
        addView(page, match());

        LinearLayout header = buildHeader(context);
        page.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(context, 66)));
        clock = (TextView) header.getChildAt(2);
        clockTicker = new Runnable() {
            @Override
            public void run() {
                clock.setText(clockFormat.format(new Date()));
                handler.postDelayed(this, 1_000L);
            }
        };
        adminHold = () -> {
            if (holdTracker.onTime(SystemClock.uptimeMillis())) {
                Listener current = listener;
                if (current != null) {
                    current.onAdminRequested();
                }
            }
        };
        bindHiddenAdminGesture();

        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyParams.topMargin = UiKit.dp(context, 10);
        page.addView(body, bodyParams);

        LinearLayout.LayoutParams venueParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.31f);
        venueParams.rightMargin = UiKit.dp(context, 13);
        body.addView(buildVenuePanel(context), venueParams);
        body.addView(buildMethodsPanel(context), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 0.69f));

        LinearLayout footer = buildFooter(context);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(context, 35));
        footerParams.topMargin = UiKit.dp(context, 5);
        page.addView(footer, footerParams);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.removeCallbacks(clockTicker);
        clockTicker.run();
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(clockTicker);
        handler.removeCallbacks(adminHold);
        holdTracker.onUpOrCancel();
        super.onDetachedFromWindow();
    }

    private LinearLayout buildHeader(Context context) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(
                UiKit.dp(context, 22), 0, UiKit.dp(context, 18), 0);
        header.setBackground(UiKit.roundedGradient(
                context, Color.rgb(15, 105, 221), Color.rgb(23, 153, 226), 9));

        TextView leftBalance = UiKit.text(context, "", 12, Color.WHITE, Typeface.NORMAL);
        header.addView(leftBalance, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        TextView title = UiKit.text(
                context, ZipBrand.HOME_TITLE, 30, Color.WHITE, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1.8f));

        TextView time = UiKit.text(context, "", 21, Color.WHITE, Typeface.BOLD);
        time.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        time.setContentDescription("日期时间");
        header.addView(time, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return header;
    }

    private void bindHiddenAdminGesture() {
        clock.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    holdTracker.onDown(
                            event.getEventTime(), event.getX(), event.getY());
                    handler.removeCallbacks(adminHold);
                    handler.postDelayed(adminHold, ADMIN_HOLD_MILLIS);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    holdTracker.onMove(event.getX(), event.getY());
                    handler.removeCallbacks(adminHold);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    holdTracker.onUpOrCancel();
                    handler.removeCallbacks(adminHold);
                    return true;
                default:
                    return true;
            }
        });
    }

    private LinearLayout buildVenuePanel(Context context) {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(
                UiKit.dp(context, 22), UiKit.dp(context, 18),
                UiKit.dp(context, 22), UiKit.dp(context, 18));
        panel.setBackground(UiKit.roundedSolid(
                context, Color.argb(219, 239, 250, 255), 14,
                Color.argb(155, 103, 207, 238), 1));

        TextView venue = UiKit.text(
                context, "跃享游泳健身", 27, UiKit.NAVY, Typeface.BOLD);
        venue.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.addView(venue, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(context, 48)));

        TextView usage = UiKit.text(
                context, "使用数量：暂无本机数据", 20,
                Color.rgb(37, 111, 185), Typeface.BOLD);
        usage.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams usageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(context, 38));
        usageParams.topMargin = UiKit.dp(context, 4);
        panel.addView(usage, usageParams);

        LinearLayout progress = new LinearLayout(context);
        progress.setOrientation(LinearLayout.HORIZONTAL);
        progress.setPadding(UiKit.dp(context, 2), UiKit.dp(context, 2),
                UiKit.dp(context, 2), UiKit.dp(context, 2));
        progress.setBackground(UiKit.roundedSolid(
                context, Color.rgb(224, 236, 249), 12, Color.WHITE, 1));
        View used = new View(context);
        used.setBackground(UiKit.roundedGradient(
                context, Color.rgb(25, 190, 142), Color.rgb(47, 207, 184), 9));
        progress.addView(used, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 24f));
        progress.addView(new View(context), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 26f));
        panel.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(context, 20)));

        LinearLayout advert = new LinearLayout(context);
        advert.setOrientation(LinearLayout.VERTICAL);
        advert.setGravity(Gravity.CENTER);
        advert.setPadding(UiKit.dp(context, 12), UiKit.dp(context, 12),
                UiKit.dp(context, 12), UiKit.dp(context, 12));
        advert.setBackground(UiKit.roundedGradient(
                context, Color.rgb(14, 137, 171), Color.rgb(34, 195, 143), 12));
        TextView advertTitle = UiKit.text(
                context, "广告展示区域", 25, Color.WHITE, Typeface.BOLD);
        advertTitle.setGravity(Gravity.CENTER);
        TextView advertEnglish = UiKit.text(
                context, "ADVERTISEMENT", 13,
                Color.argb(210, 255, 255, 255), Typeface.BOLD);
        advertEnglish.setGravity(Gravity.CENTER);
        advert.addView(advertTitle);
        LinearLayout.LayoutParams englishParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        englishParams.topMargin = UiKit.dp(context, 8);
        advert.addView(advertEnglish, englishParams);
        LinearLayout.LayoutParams advertParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        advertParams.topMargin = UiKit.dp(context, 18);
        panel.addView(advert, advertParams);
        return panel;
    }

    private LinearLayout buildMethodsPanel(Context context) {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(
                UiKit.dp(context, 13), UiKit.dp(context, 13),
                UiKit.dp(context, 13), UiKit.dp(context, 13));
        panel.setBackground(UiKit.roundedSolid(
                context, Color.argb(206, 235, 249, 255), 14,
                Color.argb(160, 100, 204, 239), 1));

        LinearLayout firstRow = methodRow(context);
        addMethodCard(firstRow, context, UnlockMethod.FACE, "人脸解锁", "FACE");
        addMethodCard(firstRow, context, UnlockMethod.PALM, "掌纹解锁", "PALM");
        addMethodCard(firstRow, context, UnlockMethod.PHONE, "手机号解锁", "PHONE");
        panel.addView(firstRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout secondRow = methodRow(context);
        addMethodCard(secondRow, context, UnlockMethod.PASSWORD, "密码解锁", "PASSWORD");
        addMethodCard(secondRow, context, UnlockMethod.QR, "二维码解锁", "QR CODE");
        addWeightedCell(secondRow, buildTips(context), context);
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        secondParams.topMargin = UiKit.dp(context, 10);
        panel.addView(secondRow, secondParams);
        return panel;
    }

    private LinearLayout methodRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private void addMethodCard(
            LinearLayout row,
            Context context,
            UnlockMethod method,
            String title,
            String english) {
        boolean enabled = availability.isEnabled(method);
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(
                UiKit.dp(context, 10), UiKit.dp(context, 10),
                UiKit.dp(context, 10), UiKit.dp(context, 10));
        card.setTag(method);

        int cardColor = enabled ? enabledColor(method) : Color.rgb(157, 173, 181);
        card.setBackground(enabled
                ? UiKit.pressableSolid(context, cardColor, 12)
                : UiKit.roundedSolid(context, cardColor, 12,
                        Color.rgb(188, 200, 205), 1));

        TextView titleView = UiKit.text(
                context, title, 24, Color.WHITE, Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER);
        TextView englishView = UiKit.text(
                context, english, 11,
                Color.argb(205, 255, 255, 255), Typeface.BOLD);
        englishView.setGravity(Gravity.CENTER);
        TextView statusView = UiKit.text(
                context,
                enabled ? "点击进入" : "设备暂未接入",
                enabled ? 14 : 16,
                enabled ? Color.argb(225, 255, 255, 255) : Color.rgb(245, 250, 251),
                Typeface.BOLD);
        statusView.setGravity(Gravity.CENTER);

        card.addView(titleView);
        LinearLayout.LayoutParams englishParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        englishParams.topMargin = UiKit.dp(context, 5);
        card.addView(englishView, englishParams);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = UiKit.dp(context, 13);
        card.addView(statusView, statusParams);

        card.setContentDescription(title + "，" + statusView.getText());
        if (enabled) {
            card.setClickable(true);
            card.setOnClickListener(view -> {
                Listener current = listener;
                if (current != null) {
                    current.onMethodSelected(method);
                }
            });
        } else {
            card.setClickable(false);
            card.setEnabled(false);
        }
        addWeightedCell(row, card, context);
    }

    private void addWeightedCell(LinearLayout row, View view, Context context) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (row.getChildCount() > 0) {
            params.leftMargin = UiKit.dp(context, 10);
        }
        row.addView(view, params);
    }

    private LinearLayout buildTips(Context context) {
        LinearLayout tips = new LinearLayout(context);
        tips.setOrientation(LinearLayout.VERTICAL);
        tips.setGravity(Gravity.CENTER_VERTICAL);
        tips.setPadding(
                UiKit.dp(context, 16), UiKit.dp(context, 12),
                UiKit.dp(context, 13), UiKit.dp(context, 12));
        tips.setBackground(UiKit.roundedSolid(
                context, Color.argb(232, 248, 249, 220), 12,
                Color.argb(150, 235, 206, 102), 1));
        TextView title = UiKit.text(
                context, "温馨提示", 19, UiKit.AMBER, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView detail = UiKit.text(
                context,
                "1、请选择手机号或密码解锁\n2、输入正确后将自动开启柜门\n3、请及时关闭柜门",
                14, Color.rgb(80, 104, 73), Typeface.NORMAL);
        detail.setLineSpacing(0, 1.18f);
        tips.addView(title);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = UiKit.dp(context, 8);
        tips.addView(detail, detailParams);
        return tips;
    }

    private LinearLayout buildFooter(Context context) {
        LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = UiKit.text(
                context, ZipBrand.FOOTER, 16,
                Color.argb(225, 23, 86, 122), Typeface.BOLD);
        footer.addView(brand, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView version = UiKit.text(
                context, ZipBrand.VERSION, 13, Color.WHITE, Typeface.BOLD);
        version.setGravity(Gravity.CENTER);
        version.setPadding(UiKit.dp(context, 15), UiKit.dp(context, 4),
                UiKit.dp(context, 15), UiKit.dp(context, 4));
        version.setBackground(UiKit.roundedSolid(
                context, Color.rgb(43, 190, 141), 18, Color.TRANSPARENT, 0));
        footer.addView(version);
        return footer;
    }

    private static int enabledColor(UnlockMethod method) {
        if (method == UnlockMethod.PASSWORD) {
            return Color.rgb(35, 191, 143);
        }
        if (method == UnlockMethod.PHONE) {
            return Color.rgb(43, 105, 224);
        }
        if (method == UnlockMethod.FACE) {
            return Color.rgb(25, 159, 218);
        }
        if (method == UnlockMethod.PALM) {
            return Color.rgb(54, 112, 220);
        }
        return Color.rgb(35, 178, 178);
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
