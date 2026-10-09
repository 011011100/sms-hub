package com.smshub.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DateFormat;
import java.util.Date;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Config config;
    private LinearLayout content;
    private TextView status;
    private TextView diagnostics;
    private AlertDialog historyDialog;
    private Button collectionButton;
    private int page;
    private boolean starting;
    private boolean permissionPending;
    private static final int START_PERMISSIONS = 10;
    private EditText url, code, phone0, phone1;
    private boolean processing;
    private final Runnable ticker = new Runnable() {
        @Override public void run() { updateStatus(); handler.postDelayed(this, 3000); }
    };
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); config = new Config(this);
        if (state != null) { page = state.getInt("page", 0); permissionPending = state.getBoolean("permissionPending", false); starting = permissionPending; }
        render();
        if (config.enabled()) { SyncEngine.schedule(this); new Thread(() -> SyncEngine.sync(getApplicationContext())).start(); }
    }
    @Override protected void onResume() {
        super.onResume();
        SmsMonitorService.start(this); handler.post(ticker);
    }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(ticker); }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("page", page); state.putBoolean("permissionPending", permissionPending); super.onSaveInstanceState(state);
    }
    @Override public void onBackPressed() {
        if (config.paired() && page != 0) { page = page == 2 ? 1 : 0; render(); }
        else super.onBackPressed();
    }
    private void render() {
        status = null;
        diagnostics = null;
        collectionButton = null;
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Color.rgb(244, 246, 250));
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(25), dp(28), dp(25), dp(36));
        scroll.addView(content); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom()); return insets.consumeSystemWindowInsets();
        });
        text("SMS Hub", 30, true, Color.rgb(24, 46, 81));
        text("把验证码集中到你的网页", 15, false, Color.rgb(107, 126, 153));
        if (!config.paired()) renderPairing();
        else if (page == 1) renderSettings();
        else if (page == 2) renderDiagnostics();
        else renderPaired();
    }
    private TextView text(String value, int size, boolean bold, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(5), 0, dp(8)); content.addView(view); return view;
    }
    private void section(String title) { TextView view = text(title, 19, true, Color.rgb(30, 50, 80)); view.setPadding(0, dp(25), 0, dp(10)); }
    private EditText input(String label, String value, String hint, int type) {
        text(label, 14, true, Color.rgb(66, 87, 116));
        EditText field = new EditText(this); field.setText(value); field.setHint(hint); field.setTextSize(16); field.setInputType(type); field.setSingleLine(true);
        field.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.WHITE); background.setCornerRadius(dp(8)); background.setStroke(dp(1), Color.rgb(214, 224, 238)); field.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(15); content.addView(field, params); return field;
    }
    private Button button(String title, boolean primary, View.OnClickListener listener) {
        Button button = new Button(this); button.setText(title); button.setTextSize(15); button.setAllCaps(false); button.setMinHeight(dp(50));
        button.setTextColor(primary ? Color.WHITE : Color.rgb(53, 82, 126));
        GradientDrawable background = new GradientDrawable(); background.setColor(primary ? Color.rgb(36, 87, 237) : Color.rgb(230, 237, 248)); background.setCornerRadius(dp(8)); button.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(12); content.addView(button, params); button.setOnClickListener(listener); return button;
    }
    private void renderPairing() {
        section("1. 连接你的网页");
        text("在网页点击「添加手机」生成配对码。每部手机使用一个配对码。", 14, false, Color.rgb(102, 120, 147));
        url = input("服务器地址", config.url(), "https://sms.example.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        code = input("一次性配对码", "", "网页上的 16 位配对码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        section("2. 填写本机号码"); numberInputs();
        text("号码由你填写，请按手机设置中的 SIM 卡顺序核对。只有一张卡时，另一栏留空。", 14, false, Color.rgb(102, 120, 147));
        CheckBox consent = new CheckBox(this); consent.setText("我同意将本机新收到的验证码短信、发送方和号码，通过加密连接同步到上方服务器。"); consent.setTextSize(14); consent.setPadding(0, dp(18), 0, dp(8)); content.addView(consent);
        text("启动后会接收新短信，并读取启动之后的系统收件箱，在本机筛选验证码。普通聊天短信不上传；启动前的历史及暂停期间的短信不补传。电话状态权限仅用于区分卡槽，通知权限用于显示后台收码。不会发送、修改或删除短信，可随时暂停。", 13, false, Color.rgb(102, 120, 147));
        button("配对并启动收码", true, view -> {
            if (!consent.isChecked()) { toast("请先确认短信同步范围"); return; }
            if (processing) return;
            try {
                String root = Network.normalizeUrl(url.getText().toString());
                String pairingCode = code.getText().toString().replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
                if (!pairingCode.matches("[A-F0-9]{16}")) throw new Exception("请输入网页生成的 16 位配对码");
                String first = number(phone0), second = number(phone1);
                if (first.isEmpty() && second.isEmpty()) throw new Exception("至少填写一个手机号");
                JSONObject details = config.details().put("paused", true);
                JSONArray lines = new JSONArray();
                if (!first.isEmpty()) lines.put(new JSONObject().put("slot", 0).put("number", first));
                if (!second.isEmpty()) lines.put(new JSONObject().put("slot", 1).put("number", second));
                details.put("lines", lines);
                processing = true; view.setEnabled(false); ((Button)view).setText("正在配对…");
                new Thread(() -> {
                    try {
                        JSONObject result = Network.post(root, "/api/device/pair", null, new JSONObject().put("code", pairingCode).put("details", details));
                        config.paired(root, result, first, second);
                        runOnUiThread(() -> { processing = false; page = 0; render(); beginStart(); });
                    } catch (Exception error) { runOnUiThread(() -> { processing = false; view.setEnabled(true); ((Button)view).setText("配对并启动收码"); showError(error instanceof Network.ApiException ? error.getMessage() : "配对失败，请检查 HTTPS 地址、网络与配对码。若配对码已使用，请在网页重新生成。"); }); }
                }, "pair-device").start();
            } catch (Exception error) { showError(error.getMessage()); }
        });
    }
    private void numberInputs() {
        phone0 = input("卡 1 手机号", config.phone(0), "例如 13800138000", InputType.TYPE_CLASS_PHONE);
        phone1 = input("卡 2 手机号（可留空）", config.phone(1), "没有第二张卡则留空", InputType.TYPE_CLASS_PHONE);
    }
    private String number(EditText input) throws Exception {
        String value = input.getText().toString().trim();
        if (!value.isEmpty() && !value.matches("\\+?[0-9 ()-]{3,24}")) throw new Exception("请检查手机号格式");
        return value;
    }
    private void renderPaired() {
        section(config.name());
        text(config.url(), 13, false, Color.rgb(107, 126, 153));
        status = text("正在检查…", 17, true, Color.rgb(43, 91, 143));
        collectionButton = button("一键启动收码", true, view -> {
            if (starting) return;
            if (readyToPause()) {
                config.setEnabled(false); SyncEngine.cancel(this);
                new SmsDiagnostics(config.prefs).record("已暂停收码，暂停期间的新短信不补传");
                new Thread(() -> SyncEngine.sendState(getApplicationContext()), "sms-pause").start();
                updateStatus();
            } else beginStart();
        });
        text("启动后自动接收验证码并补查新短信，允许返回桌面或锁屏。首次启动请按系统提示授权。", 14, false, Color.rgb(102, 120, 147));
        button("设备设置与诊断", false, view -> { page = 1; render(); });
        text("SMS Hub " + config.appVersion(), 12, false, Color.rgb(107, 126, 153));
        updateStatus();
    }
    private void renderSettings() {
        button("返回收码首页", false, view -> { page = 0; render(); });
        section("本机手机号"); numberInputs();
        text("按实际卡槽填写。换卡后请核对并保存，无法识别来源卡时不猜测号码。", 13, false, Color.rgb(102, 120, 147));
        button("保存号码", false, view -> {
            try {
                String first = number(phone0), second = number(phone1);
                if (first.isEmpty() && second.isEmpty()) throw new Exception("至少填写一个手机号");
                config.prefs.edit().putString("phone0", first).putString("phone1", second).commit();
                config.rememberSubscriptions();
                new Thread(() -> SyncEngine.sendState(getApplicationContext())).start(); toast("号码已保存");
            } catch (Exception error) { showError(error.getMessage()); }
        });
        section("后台运行设置");
        text(backgroundHelp(), 14, false, Color.rgb(102, 120, 147));
        text("这些系统开关需要你手动允许，App 无法代为修改。系统强制停止后请重新打开 App；重启后先解锁。", 13, false, Color.rgb(102, 120, 147));
        button("打开系统应用设置", false, view -> openAppSettings());
        section("排查问题");
        button("查看收码诊断", false, view -> { page = 2; render(); });
        button("重新授权并启动收码", false, view -> beginStart());
        section("设备绑定");
        button("解除本机配对", false, view -> new AlertDialog.Builder(this).setTitle("解除配对？")
            .setMessage("会删除本机连接凭据和待上传短信。网页中的已有记录保留，请在网页设备管理中停用本设备。")
            .setPositiveButton("解除", (dialog, which) -> {
                config.setEnabled(false); SyncEngine.cancel(this);
                new Thread(() -> {
                    SyncEngine.sendState(getApplicationContext());
                    try (PendingMessages queue = new PendingMessages(getApplicationContext())) { queue.clear(); }
                    config.prefs.edit().clear().commit(); runOnUiThread(() -> { page = 0; render(); });
                }).start();
            }).setNegativeButton("取消", null).show());
    }
    private String backgroundHelp() {
        String maker = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(java.util.Locale.ROOT);
        if (maker.contains("huawei") || maker.contains("honor"))
            return "华为 / 荣耀：在手机管家的「应用启动管理」中找到 SMS Hub，改为手动管理，允许自启动、关联启动和后台活动。菜单名称可能随系统版本变化。";
        if (maker.contains("xiaomi") || maker.contains("redmi"))
            return "小米 / 红米：在系统应用设置中允许自启动，将省电策略设为「无限制」。如有单独的验证码读取权限，也需要允许。";
        if (maker.contains("vivo") || maker.contains("iqoo"))
            return "vivo / iQOO：在系统权限管理中允许自启动，在后台耗电管理中允许后台高耗电。如果仍读不到验证码，请查看下方诊断；系统的验证码保护也可能限制读取。";
        if (maker.contains("oppo") || maker.contains("realme") || maker.contains("oneplus"))
            return "在系统应用设置中允许自启动和后台活动，将耗电策略设为允许后台运行。具体菜单名称随系统版本变化。";
        return "在系统应用设置中允许自启动和后台活动，将电池策略设为不限制。部分机型需要在最近任务中锁定 SMS Hub。";
    }
    private void renderDiagnostics() {
        button("返回设备设置", false, view -> { page = 1; render(); });
        section("收码诊断 · " + config.appVersion());
        status = text("正在检查…", 14, false, Color.rgb(43, 91, 143));
        diagnostics = text("正在检查…", 14, false, Color.rgb(43, 91, 143));
        text("先保持 App 打开，接收一条普通短信，再接收一条含「验证码」的新短信。普通短信不上传网页。补查只处理本次启动之后的新短信。", 13, false, Color.rgb(102, 120, 147));
        button("检查连接与补查状态", false, view -> syncNow(true));
        button("查看最近记录", false, view -> {
            historyDialog = new AlertDialog.Builder(this).setTitle("最近接收记录")
                .setMessage(new SmsDiagnostics(config.prefs).history()).setPositiveButton("关闭", null).create();
            historyDialog.setOnDismissListener(dialog -> historyDialog = null); historyDialog.show();
        });
        button("复制诊断信息", false, view -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("SMS Hub 收码诊断", diagnosticReport()));
            toast("已复制，不含短信内容、号码或连接凭据");
        });
        text("诊断只包含版本、型号、权限、时间、数量和处理状态。最近记录仅在本机保留 16 步，不包含短信原文、手机号或验证码。", 12, false, Color.rgb(102, 120, 147));
        updateStatus();
    }
    private boolean granted(String permission) { return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    private boolean readyToPause() {
        return config.enabled();
    }
    private void beginStart() {
        if (starting || !config.paired()) return;
        starting = true; updateStatus();
        if (config.collectionConsent()) { requestStartPermissions(); return; }
        new AlertDialog.Builder(this).setTitle("启动验证码收码")
            .setMessage("启动后将接收新短信，并读取启动之后的系统收件箱，在本机筛选验证码，上传到你已配对的服务器。普通聊天短信不上传，启动前的历史及暂停期间的短信不补传。电话状态权限仅用于区分卡槽，通知权限用于显示后台收码。不会读取联系人、通话记录，也不会发送、修改或删除短信，可随时暂停。")
            .setPositiveButton("同意并启动", (dialog, which) -> {
                config.prefs.edit().putBoolean("collectionConsent", true).commit(); requestStartPermissions();
            }).setNegativeButton("取消", (dialog, which) -> cancelStart())
            .setOnCancelListener(dialog -> cancelStart()).show();
    }
    private void cancelStart() { starting = false; updateStatus(); }
    private void requestStartPermissions() {
        ArrayList<String> missing = new ArrayList<>();
        for (String permission : new String[]{Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS, Manifest.permission.READ_PHONE_STATE})
            if (!granted(permission)) missing.add(permission);
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) missing.add(Manifest.permission.POST_NOTIFICATIONS);
        if (missing.isEmpty()) finishStart();
        else { permissionPending = true; requestPermissions(missing.toArray(new String[0]), START_PERMISSIONS); }
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == START_PERMISSIONS) finishStart();
    }
    private void finishStart() {
        starting = false; permissionPending = false;
        if (!config.startCollection()) {
            config.setEnabled(false); SyncEngine.cancel(this); page = 0; render();
            new AlertDialog.Builder(this).setTitle("尚未启动收码")
                .setMessage("短信接收和读取权限尚未允许，或本机设置未能保存。请在系统应用设置中允许短信权限，再点击一键启动。")
                .setPositiveButton("打开系统设置", (dialog, which) -> openAppSettings()).setNegativeButton("稍后", null).show();
            return;
        }
        config.rememberSubscriptions();
        new SmsDiagnostics(config.prefs).record(config.inboxEnabled() ? "已启动收码，自动接收并补查新短信" : "已启动实时收码；读取权限未允许，未启用补查");
        SmsMonitorService.start(this);
        try { SyncEngine.schedule(this); }
        catch (Exception error) { new SmsDiagnostics(config.prefs).failed("后台重试任务未能安排", error); }
        page = 0; render(); syncNow(false);
    }
    private void openAppSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }
    private void syncNow(boolean showResult) {
        if (processing || !config.paired()) return;
        processing = true;
        SmsMonitorService.start(this);
        new Thread(() -> {
            boolean active = config.enabled(), busy = SyncEngine.isRunning();
            long previousSync = config.prefs.getLong("lastSync", 0);
            if (active) SyncEngine.sync(getApplicationContext()); else SyncEngine.sendState(getApplicationContext());
            runOnUiThread(() -> {
                processing = false; updateStatus();
                if (!showResult) return;
                String error = config.prefs.getString("error", "");
                if (!error.isEmpty()) toast("连接尚未成功，请查看状态");
                else if (config.prefs.getLong("lastSync", 0) > previousSync)
                    toast(active ? "服务器连接成功，请查看下方补查状态" : "服务器连接成功；收码仍处于暂停状态");
                else toast(busy || SyncEngine.isRunning() ? "已有检查任务在运行，请查看诊断" : "检查尚未完成，请查看诊断");
            });
        }, "sms-manual-check").start();
    }
    private String permissionSummary() {
        return "接收短信权限：" + (config.smsPermission() ? "已允许" : "未允许")
            + "\n读取短信权限：" + (config.inboxPermission() ? "已允许" : "未允许")
            + "\n识别卡槽权限：" + (granted(Manifest.permission.READ_PHONE_STATE) ? "已允许" : "未允许")
            + "\n收码通知：" + (getSystemService(NotificationManager.class).areNotificationsEnabled() ? "已允许" : "未允许");
    }
    private String diagnosticReport() {
        return "SMS Hub " + config.appVersion() + "\n" + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE
            + "\n" + (config.enabled() ? "收码已开启" : "收码已暂停")
            + "\n后台服务：" + (SmsMonitorService.running ? "运行中" : "未运行")
            + "\n" + permissionSummary() + "\n" + new SmsDiagnostics(config.prefs).summary()
            + "\n同步任务：" + SyncEngine.status() + "\n\n" + new SmsDiagnostics(config.prefs).history();
    }
    private void updateStatus() {
        if (!config.paired()) return;
        String value;
        if (starting) value = "正在准备收码，请完成系统授权";
        else if (!config.enabled()) value = "已暂停，当前不会收码";
        else if (!config.smsPermission() && !(config.inboxEnabled() && config.inboxPermission())) value = "收码已开启，但缺少短信权限，请到设备设置重新授权";
        else if (!SmsMonitorService.running) value = "收码已开启，但后台服务未运行，请到设备设置查看诊断";
        else value = "收码已开启 · 后台服务运行中";
        if (config.enabled()) value += config.inboxEnabled() && config.inboxPermission() ? "\n自动补查：已开启" : "\n自动补查：未开启，可在设备设置中重新授权";
        try (PendingMessages queue = new PendingMessages(this)) { value += "\n等待上传：" + queue.count() + " 条"; }
        catch (Exception error) { value += "\n本机队列读取失败"; }
        long last = config.prefs.getLong("lastSync", 0);
        value += "\n最近连接：" + (last == 0 ? "尚未成功" : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(new Date(last)));
        if (config.enabled() && !getSystemService(NotificationManager.class).areNotificationsEnabled()) value += "\n收码通知未允许，请在系统应用设置开启";
        String error = config.prefs.getString("error", ""); if (!error.isEmpty()) value += "\n" + error;
        String inboxError = config.prefs.getString("inboxError", ""); if (config.enabled() && !inboxError.isEmpty()) value += "\n" + inboxError;
        if (status != null) status.setText(value);
        if (collectionButton != null) {
            collectionButton.setEnabled(!starting);
            collectionButton.setText(starting ? "正在准备…" : (readyToPause() ? "暂停收码" : "一键启动收码"));
        }
        if (diagnostics != null) diagnostics.setText(permissionSummary() + "\n\n" + new SmsDiagnostics(config.prefs).summary() + "\n同步任务：" + SyncEngine.status());
        if (historyDialog != null && historyDialog.isShowing()) historyDialog.setMessage(new SmsDiagnostics(config.prefs).history());
    }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private void showError(String value) { new AlertDialog.Builder(this).setTitle("请检查").setMessage(value).setPositiveButton("知道了", null).show(); }
}
