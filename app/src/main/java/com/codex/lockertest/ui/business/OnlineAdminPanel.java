package com.codex.lockertest.ui.business;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputFilter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.codex.lockertest.business.BusinessService;
import com.codex.lockertest.business.OnlineAdminController;
import com.codex.lockertest.business.mqtt.MqttSettingsStore;
import com.codex.lockertest.business.mqtt.MqttProbe;
import com.codex.lockertest.ui.UiKit;
import com.codex.lockertest.ui.ZipKioskShell;
import com.codex.lockertest.ui.zip.ZipPixelShell;
import com.codex.lockertest.ui.zip.ZipScreenAsset;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Server-authorized maintenance menu inside the original administrator canvas. */
public final class OnlineAdminPanel extends FrameLayout implements AutoCloseable {
    /** The host must check live authorization in its maintenance activity and revoke on background exit. */
    public interface MaintenanceActions {
        void onSerialRequested(BooleanSupplier authorized, Runnable revokeSession);
        void onFaceSdkRequested(BooleanSupplier authorized, Runnable revokeSession);
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AndroidBusinessScheduler scheduler = new AndroidBusinessScheduler(handler);
    private final FrameLayout body;
    private final OnlineCustomerHost.ScanSourceResolver sources;
    private final OnlineAdminController controller;
    private final OnlineAdminNavigation navigation;
    private final MaintenanceActions maintenanceActions;
    private final Runnable home;
    private final boolean dynamicRequired;
    private volatile boolean closed;
    private boolean saving;
    private TextView status;
    private EditText username;
    private EditText password;
    private EditText dynamic;
    private Button login;
    private final MqttSettingsStore mqttStore;
    private final MqttProbe mqttProbe;
    private OnlineMqttPanel mqttPanel;

    public OnlineAdminPanel(Context context, BusinessService service, OnlineCustomerHost.ScanSourceResolver sources,
            String deviceSerial, boolean dynamicRequired, Runnable home) {
        this(context, service, sources, deviceSerial, dynamicRequired, home, null);
    }
    public OnlineAdminPanel(Context context, BusinessService service, OnlineCustomerHost.ScanSourceResolver sources,
            String deviceSerial, boolean dynamicRequired, Runnable home, MaintenanceActions maintenanceActions) {
        this(context, service, sources, deviceSerial, dynamicRequired, home,
                new AndroidMqttSettingsStore(context), new AndroidMqttProbe(), maintenanceActions);
    }
    public OnlineAdminPanel(Context context, BusinessService service, OnlineCustomerHost.ScanSourceResolver sources,
            String deviceSerial, boolean dynamicRequired, Runnable home, MqttSettingsStore mqttStore, MqttProbe mqttProbe) {
        this(context, service, sources, deviceSerial, dynamicRequired, home, mqttStore, mqttProbe, null);
    }
    public OnlineAdminPanel(Context context, BusinessService service, OnlineCustomerHost.ScanSourceResolver sources,
            String deviceSerial, boolean dynamicRequired, Runnable home, MqttSettingsStore mqttStore, MqttProbe mqttProbe,
            MaintenanceActions maintenanceActions) {
        super(context);
        this.mqttStore = mqttStore; this.mqttProbe = mqttProbe;
        this.sources = sources; this.dynamicRequired = dynamicRequired; this.home = home;
        this.maintenanceActions = maintenanceActions;
        ZipPixelShell shell = new ZipPixelShell(context, ZipScreenAsset.ADMIN_FUNCTIONS);
        addView(shell, new LayoutParams(-1, -1));
        body = new FrameLayout(context);
        shell.contentLayer().addView(body, new LayoutParams(-1, -1));
        shell.setOnSafeHomeRequested(this::leave);
        controller = new OnlineAdminController(service, deviceSerial, scheduler, SystemClock::elapsedRealtime,
                state -> handler.post(() -> updateState()));
        navigation = new OnlineAdminNavigation(controller::authorized, this::leave);
        showLogin();
    }
    private void background() {
        body.removeAllViews();
        TextView cover = text("", 18); cover.setBackgroundColor(Color.WHITE);
        place(cover, 20, 105, 1240, 605);
        Button back = button("返回首页", "退出管理员设置");
        back.setOnClickListener(v -> leave()); place(back, 950, 645, 225, 48);
        status = text("", 18); place(status, 165, 577, 950, 48);
    }
    private void showLogin() {
        background();
        TextView title = text("登录管理后台", 32);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); title.setTextColor(UiKit.DARK_GREEN);
        place(title, 340, 132, 600, 46);
        TextView subtitle = text("Log in to the management backend", 14);
        subtitle.setTextColor(UiKit.MUTED); place(subtitle, 340, 180, 600, 24);
        FrameLayout card = new FrameLayout(getContext());
        card.setContentDescription("管理员登录表单");
        card.setBackground(UiKit.roundedSolid(getContext(), Color.rgb(248, 252, 251), 12,
                Color.rgb(83, 202, 169), 2));
        place(card, 340, 218, 600, dynamicRequired ? 322 : 260);
        username = input("服务器管理员账号", false, 256); place(username, 410, 250, 460, 54);
        password = input("服务器管理员密码", true, 4096); place(password, 410, 320, 460, 54);
        dynamic = input("管理员动态验证码", true, 256);
        if (dynamicRequired) place(dynamic, 410, 390, 460, 54);
        login = button("登录", "服务器管理员登录");
        login.setOnClickListener(v -> submit()); place(login, 410, dynamicRequired ? 460 : 400, 460, 56);
        status.setText(dynamicRequired ? "请输入后台管理员账号、密码和动态验证码" : "使用服务器管理员账号登录，不使用本地默认密码");
    }
    private void submit() {
        if (closed || controller.state() == OnlineAdminController.State.AUTHENTICATING) return;
        String name = username.getText().toString();
        String pass = password.getText().toString();
        String code = dynamicRequired ? dynamic.getText().toString() : null;
        if (dynamicRequired && (code == null || code.trim().isEmpty())) { status.setText("请填写管理员动态验证码"); return; }
        if (!controller.login(name, pass, code)) { status.setText("请完整填写登录信息，或稍后重试"); return; }
        password.setText(""); dynamic.setText(""); updateState();
    }
    private void updateState() {
        if (closed) return;
        switch (controller.state()) {
            case AUTHENTICATING:
                login.setEnabled(false); username.setEnabled(false); password.setEnabled(false); dynamic.setEnabled(false);
                status.setText("正在连接服务器验证管理员…"); break;
            case READY:
                if (navigation.page() == OnlineAdminNavigation.Page.LOGIN) resumeMenu();
                break;
            case FAILED:
                login.setEnabled(true); username.setEnabled(true); password.setEnabled(true); dynamic.setEnabled(true);
                status.setText("登录未通过，请检查网络、账号或动态验证码后重试"); break;
            case IDLE: leave(); break;
        }
    }
    /** Returns from a maintenance activity without extending or replacing the server session. */
    public void resumeMenu() {
        if (closed || saving || !navigation.showMenu()) return;
        if (mqttPanel != null) { mqttPanel.close(); mqttPanel = null; }
        background();
        place(text("管理员维护菜单", 28), 330, 148, 620, 48);
        place(text("请选择需要维护的设备功能", 20), 240, 208, 800, 42);
        Button serial = button("串口设置", "串口设置");
        serial.setEnabled(maintenanceActions != null);
        serial.setOnClickListener(v -> requestMaintenance(OnlineAdminNavigation.Page.SERIAL));
        place(serial, 210, 282, 380, 80);
        Button face = button("百度人脸 SDK 设置", "百度人脸SDK设置");
        face.setEnabled(maintenanceActions != null);
        face.setOnClickListener(v -> requestMaintenance(OnlineAdminNavigation.Page.FACE));
        place(face, 690, 282, 380, 80);
        Button scanner = button("扫码 / 读卡来源设置", "扫码读卡来源设置");
        scanner.setOnClickListener(v -> {
            if (!closed && !saving && navigation.open(OnlineAdminNavigation.Page.SOURCES)) showSources();
        });
        place(scanner, 210, 414, 380, 80);
        Button mqtt = button("MQTT 连接设置", "MQTT连接设置");
        mqtt.setOnClickListener(v -> showMqtt());
        place(mqtt, 690, 414, 380, 80);
        place(text("本页仅提供设备维护；管理员开柜、清柜和刷卡登录尚未接入。", 18), 150, 516, 980, 42);
        status.setText("本次登录 60 秒后退出；维护返回无需再次登录，退出或切到后台会撤销权限。");
    }
    private void requestMaintenance(OnlineAdminNavigation.Page page) {
        if (closed || saving || maintenanceActions == null || !navigation.open(page)) return;
        try {
            if (page == OnlineAdminNavigation.Page.SERIAL)
                maintenanceActions.onSerialRequested(navigation::authorized, this::leave);
            else if (page == OnlineAdminNavigation.Page.FACE)
                maintenanceActions.onFaceSdkRequested(navigation::authorized, this::leave);
        } catch (RuntimeException | LinkageError failure) {
            resumeMenu();
            if (!closed) status.setText("维护页面暂时不可用，请稍后重试");
        }
    }
    private void showSources() {
        if (closed || saving) return;
        if (!navigation.authorized()) { leave(); return; }
        if (navigation.page() != OnlineAdminNavigation.Page.SOURCES) return;
        background();
        place(text("扫码器 / 读卡器来源设置", 28), 300, 137, 680, 48);
        place(text("请按实际设备指定用途；配置只保存设备标识，不保存卡号或二维码。", 18), 135, 193, 1010, 40);
        ScrollView scroll = new ScrollView(getContext());
        LinearLayout rows = new LinearLayout(getContext()); rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows); place(scroll, 120, 250, 1040, 310);
        List<OnlineCustomerHost.ScanInputDevice> devices;
        try { devices = sources.devices(); }
        catch (RuntimeException failure) { devices = java.util.Collections.emptyList(); }
        if (devices.isEmpty()) {
            TextView empty = text("未发现实体输入设备，请接入扫码器/读卡器后刷新。", 21);
            rows.addView(empty, new LinearLayout.LayoutParams(-1, unit(80)));
        }
        for (OnlineCustomerHost.ScanInputDevice device : devices) {
            LinearLayout row = new LinearLayout(getContext()); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView label = text(device.label, 18); label.setGravity(Gravity.CENTER_VERTICAL); label.setMaxLines(2);
            row.addView(label, new LinearLayout.LayoutParams(0, unit(80), 1));
            Spinner role = new Spinner(getContext()); role.setContentDescription("识别来源 " + device.id);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item,
                    new String[]{"未配置", "二维码扫码器", "IC/ID卡读卡器"});
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); role.setAdapter(adapter);
            role.setSelection(device.type == 2 ? 1 : device.type == 4 ? 2 : 0);
            row.addView(role, new LinearLayout.LayoutParams(unit(245), unit(64)));
            Button save = button(device.configurable ? "保存" : "标识冲突", "保存识别来源 " + device.id);
            save.setEnabled(device.configurable); role.setEnabled(device.configurable);
            save.setOnClickListener(v -> save(device, role.getSelectedItemPosition() == 1 ? 2
                    : role.getSelectedItemPosition() == 2 ? 4 : 0, save));
            row.addView(save, new LinearLayout.LayoutParams(unit(130), unit(54)));
            rows.addView(row, new LinearLayout.LayoutParams(-1, unit(88)));
        }
        Button refresh = button("刷新设备", "刷新识别设备"); refresh.setOnClickListener(v -> showSources());
        place(refresh, 130, 645, 225, 48);
        Button menu = button("返回维护菜单", "返回维护菜单"); menu.setOnClickListener(v -> resumeMenu());
        place(menu, 460, 645, 360, 48);
        status.setText("设置会保存；本次登录 60 秒后退出，返回维护菜单不会延长会话。");
    }
    private void showMqtt() {
        if (closed || saving || mqttPanel != null || !navigation.open(OnlineAdminNavigation.Page.MQTT)) return;
        body.removeAllViews();
        mqttPanel = new OnlineMqttPanel(getContext(), mqttStore, mqttProbe, navigation::authorized, () -> {
            mqttPanel = null;
            resumeMenu();
        });
        body.addView(mqttPanel, new LayoutParams(-1, -1));
    }
    private void save(OnlineCustomerHost.ScanInputDevice device, int role, Button button) {
        if (closed || saving || navigation.page() != OnlineAdminNavigation.Page.SOURCES) return;
        if (!navigation.authorized()) { leave(); return; }
        saving = true; button.setEnabled(false); status.setText("正在保存设备来源…");
        try {
            scheduler.submit(() -> {
                boolean success = false;
                try { if (!closed && navigation.authorized()) success = sources.assign(device, role); }
                catch (RuntimeException failure) { /* Configuration error, never a credential log. */ }
                final boolean saved = success;
                handler.post(() -> {
                    if (closed) return;
                    if (!navigation.authorized()) { leave(); return; }
                    saving = false; showSources();
                    status.setText(saved ? "来源配置已保存，返回首页后可刷卡/扫码验证" : "保存失败：设备断开或标识冲突请刷新；存储不可用请重启应用后重试");
                });
            });
        } catch (RuntimeException failure) { saving = false; button.setEnabled(true); status.setText("保存未开始，请重试"); }
    }
    private EditText input(String description, boolean secret, int maximum) {
        EditText input = new EditText(getContext()); input.setSingleLine(true);
        input.setContentDescription(description); input.setHint(description);
        input.setInputType(secret ? AdminCredentialInputPolicy.secretInputType()
                : AdminCredentialInputPolicy.accountInputType());
        input.setImeOptions(input.getImeOptions() | AdminCredentialInputPolicy.imeOptions());
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maximum)});
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit(20)); input.setTextColor(UiKit.TEXT);
        input.setHintTextColor(Color.rgb(126, 148, 146)); input.setGravity(Gravity.CENTER_VERTICAL);
        input.setPadding(unit(18), 0, unit(18), 0);
        input.setSaveEnabled(false); input.setBackground(UiKit.roundedSolid(getContext(), Color.WHITE, 8,
                Color.rgb(155, 215, 198), 1)); return input;
    }
    private TextView text(String value, int size) {
        TextView view = UiKit.text(getContext(), value, 18, UiKit.TEXT, Typeface.NORMAL);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit(size)); view.setGravity(Gravity.CENTER); return view;
    }
    private Button button(String value, String description) {
        Button button = UiKit.button(getContext(), value, 18, UiKit.GREEN, Color.WHITE,
                unit(14) / getResources().getDisplayMetrics().density);
        button.setContentDescription(description); button.setTextSize(TypedValue.COMPLEX_UNIT_PX, unit(21)); return button;
    }
    private int unit(int size) { return ZipKioskShell.unit(getContext(), size); }
    private void place(View view, int left, int top, int width, int height) {
        LayoutParams params = new LayoutParams(unit(width), unit(height)); params.leftMargin = unit(left); params.topMargin = unit(top); body.addView(view, params);
    }
    private void leave() { if (closed) return; close(); home.run(); }
    @Override public void close() {
        if (closed) return; closed = true;
        navigation.close();
        if (mqttPanel != null) { mqttPanel.close(); mqttPanel = null; }
        if (password != null) password.setText(""); if (dynamic != null) dynamic.setText(""); if (username != null) username.setText("");
        controller.cancel(); scheduler.close(); handler.removeCallbacksAndMessages(null);
    }
}
