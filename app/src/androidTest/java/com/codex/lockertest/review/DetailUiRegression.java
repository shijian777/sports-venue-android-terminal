package com.codex.lockertest.review;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import com.codex.lockertest.bootstrap.BootstrapSnapshot;
import com.codex.lockertest.runtime.CredentialAdmission;
import com.codex.lockertest.ui.BootstrapHomePresentation;
import com.codex.lockertest.ui.TerminalReadiness;
import com.codex.lockertest.ui.ZipHomeView;

/** Long real server messages must remain readable, with the recovery action outside the scroll. */
final class DetailUiRegression {
    static void check(Context context) throws Exception {
        for (String font : new String[]{"qg-regular.ttf", "qg-semibold.ttf"}) {
            try (java.io.InputStream input = context.getAssets().open(font)) {
                require(input.available() > 100000, "APK missing full custom font " + font);
            }
            require(android.graphics.Typeface.createFromAsset(context.getAssets(), font) != null,
                    "Android cannot load custom font " + font);
        }
        java.lang.reflect.Method state = BootstrapSnapshot.class.getDeclaredMethod("state", long.class,
                BootstrapSnapshot.Phase.class, BootstrapSnapshot.Endpoint.class,
                BootstrapSnapshot.FailureReason.class, String.class);
        state.setAccessible(true);
        BootstrapSnapshot idle = (BootstrapSnapshot) state.invoke(null, 1L, BootstrapSnapshot.Phase.IDLE,
                BootstrapSnapshot.Endpoint.NONE, BootstrapSnapshot.FailureReason.NONE, "");
        ZipHomeView home = new ZipHomeView(context,
                (method, value) -> CredentialAdmission.rejected("Fixture"), method -> true,
                BootstrapHomePresentation.create(TerminalReadiness.localDemoReady(), idle));
        StringBuilder longMessage = new StringBuilder();
        for (int i = 0; i < 15; i++) longMessage.append("无法确认柜门是否已打开，请先检查柜门，并联系工作人员。\n");
        longMessage.append("结束标记，请勿重复开柜。");
        home.showValidationError(longMessage.toString());
        OnlineBusinessRegression.savePreview(context, home, "detail-long-error.png");
        TextView detail = (TextView) find(home, "操作提示，", true);
        require(detail != null, "Long server message disappeared");
        ScrollView scroll = null;
        for (android.view.ViewParent p = detail.getParent(); p != null; p = p.getParent())
            if (p instanceof ScrollView) { scroll = (ScrollView) p; break; }
        require(scroll != null, "Long server error has no scrollable viewport and overlaps recovery");
        require(detail.getEllipsize() == null, "Long server response was silently ellipsized");
        require(detail.getLayout().getLineEnd(detail.getLayout().getLineCount() - 1) == detail.getText().length(),
                "Last part of server message cannot be reached");
        View action = find(home, "确认提示并返回输入", false);
        require(action != null && action.isEnabled(), "Long message lost recovery action");
        require(!descendant(scroll, action), "Recovery action scrolls away with message");
        scroll.fullScroll(View.FOCUS_DOWN);
        action.performClick();
        require(find(home, "数字键盘1", false).isEnabled(), "Acknowledgment did not restore keypad");
        home.showValidationError("您无法使用该区域柜子，请联系前台确认会员权限后重试。");
        OnlineBusinessRegression.savePreview(context, home, "detail-home-notice.png");
    }
    private static boolean descendant(ViewGroup parent, View child) {
        for (int i=0;i<parent.getChildCount();i++) {
            View v=parent.getChildAt(i);
            if (v==child || v instanceof ViewGroup && descendant((ViewGroup)v,child)) return true;
        }
        return false;
    }
    private static View find(View root,String description,boolean prefix) {
        if (root.getContentDescription()!=null && (prefix
                ? root.getContentDescription().toString().startsWith(description)
                : root.getContentDescription().toString().equals(description))) return root;
        if (root instanceof ViewGroup) for (int i=0;i<((ViewGroup)root).getChildCount();i++) {
            View result=find(((ViewGroup)root).getChildAt(i),description,prefix);if(result!=null)return result;
        }
        return null;
    }
    private static void require(boolean value,String message) {if(!value)throw new AssertionError(message);}
}
