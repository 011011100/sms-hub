package com.smshub.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Config config;
    private LinearLayout content;
    private TextView status;
    private TextView diagnostics;
    private AlertDialog historyDialog;
    private boolean renderedEnabled;
    private EditText url, code, phone0, phone1;
    private boolean processing;
    private final Runnable ticker = new Runnable() {
        @Override public void run() { updateStatus(); handler.postDelayed(this, 3000); }
    };
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); config = new Config(this); render();
        if (config.enabled()) { SyncEngine.schedule(this); new Thread(() -> SyncEngine.sync(getApplicationContext())).start(); }
    }
    @Override protected void onResume() {
        super.onResume();
        if (config.paired() && renderedEnabled != config.enabled()) render();
        SmsMonitorService.start(this); handler.post(ticker);
    }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(ticker); }
    private void render() {
        status = null;
        diagnostics = null;
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Color.rgb(244, 246, 250));
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(25), dp(28), dp(25), dp(36));
        scroll.addView(content); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom()); return insets.consumeSystemWindowInsets();
        });
        text("SMS Hub", 30, true, Color.rgb(24, 46, 81));
        text("把验证码集中到你的网页", 15, false, Color.rgb(107, 126, 153));
        if (config.paired()) renderPaired(); else renderPairing();
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
        text("普通聊天短信不上传；历史短信不读取。可随时暂停。", 13, false, Color.rgb(102, 120, 147));
        button("配对并允许接收短信", true, view -> {
            if (!consent.isChecked()) { toast("请先确认短信同步范围"); return; }
            if (processing) return;
            try {
                String root = Network.normalizeUrl(url.getText().toString());
                String pairingCode = code.getText().toString().replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
                if (!pairingCode.matches("[A-F0-9]{16}")) throw new Exception("请输入网页生成的 16 位配对码");
                String first = number(phone0), second = number(phone1);
                if (first.isEmpty() && second.isEmpty()) throw new Exception("至少填写一个手机号");
                JSONObject details = config.details().put("paused", false);
                JSONArray lines = new JSONArray();
                if (!first.isEmpty()) lines.put(new JSONObject().put("slot", 0).put("number", first));
                if (!second.isEmpty()) lines.put(new JSONObject().put("slot", 1).put("number", second));
                details.put("lines", lines);
                processing = true; view.setEnabled(false); ((Button)view).setText("正在配对…");
                new Thread(() -> {
                    try {
                        JSONObject result = Network.post(root, "/api/device/pair", null, new JSONObject().put("code", pairingCode).put("details", details));
                        config.paired(root, result, first, second);
                        runOnUiThread(() -> { processing = false; render(); requestSmsPermission(); SyncEngine.schedule(this); });
                    } catch (Exception error) { runOnUiThread(() -> { processing = false; view.setEnabled(true); ((Button)view).setText("配对并允许接收短信"); showError(error instanceof Network.ApiException ? error.getMessage() : "配对失败，请检查 HTTPS 地址、网络与配对码。若配对码已使用，请在网页重新生成。"); }); }
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
        renderedEnabled = config.enabled();
        section(config.name());
        text(config.url(), 14, false, Color.rgb(93, 115, 150));
        status = text("正在检查…", 15, false, Color.rgb(43, 91, 143));
        button("检查并允许短信权限", true, view -> requestSmsPermission());
        button("允许识别 SIM 卡槽", false, view -> {
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) { toast("卡槽识别权限已允许"); return; }
            new AlertDialog.Builder(this).setTitle("识别来自哪张 SIM 卡").setMessage("电话状态权限用于区分卡 1 与卡 2。不会读取联系人或通话记录；手机号仍由你填写。")
                .setPositiveButton("允许", (dialog, which) -> requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, 2)).setNegativeButton("暂不", null).show();
        });
        button("立即同步并检查连接", false, view -> syncNow());
        button(config.enabled() ? "暂停同步" : "恢复同步", false, view -> {
            boolean next = !config.enabled();
            config.setEnabled(next);
            if (next) { SyncEngine.schedule(this); SmsMonitorService.start(this); syncNow(); }
            else { SyncEngine.cancel(this); new Thread(() -> SyncEngine.sendState(getApplicationContext())).start(); }
            render();
        });
        section("后台收码");
        text("同步开启后会运行常驻通知服务，不需要一直打开本页面。可从通知或本页面暂停。系统的自启动和后台运行设置仍需允许。", 13, false, Color.rgb(102, 120, 147));
        if (Build.VERSION.SDK_INT >= 33) button("允许后台收码通知", false, view -> {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) { toast("通知权限已允许"); SmsMonitorService.start(this); }
            else requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 4);
        });
        button(config.inboxEnabled() && config.inboxPermission() ? "关闭兼容收码" : "开启兼容收码（收件箱补查）", false, view -> {
            if (config.inboxEnabled() && config.inboxPermission()) {
                config.prefs.edit().putBoolean("inboxEnabled", false).putString("inboxError", "").commit();
                SmsMonitorService.start(this); render(); return;
            }
            new AlertDialog.Builder(this).setTitle("开启兼容收码？")
                .setMessage("需要额外允许读取短信。开启后会从系统收件箱检查新收到的短信，在本机筛选验证码并上传到你配对的服务器。不会补传开启前的历史短信，也不会修改或删除手机短信。暂停期间的新短信不补传。后台会显示收码通知。")
                .setPositiveButton("同意并开启", (dialog, which) -> {
                    if (config.inboxPermission()) enableInbox();
                    else requestPermissions(new String[]{Manifest.permission.READ_SMS}, 3);
                }).setNegativeButton("取消", null).show();
        });
        text("系统短信通知不稳定时可启用兼容收码；它会监听收件箱变化，并在服务运行时每 30 秒补查一次。如果系统拒绝读取，也会在诊断中显示。", 13, false, Color.rgb(102, 120, 147));
        section("短信接收诊断 · " + config.appVersion());
        diagnostics = text("正在检查…", 14, false, Color.rgb(43, 91, 143));
        text("连接成功只表示服务器可访问。请保持本应用打开，先接收一条普通短信，再接收一条含「验证码」的新短信，观察下面的记录。", 13, false, Color.rgb(102, 120, 147));
        button("查看接收记录", false, view -> {
            historyDialog = new AlertDialog.Builder(this).setTitle("最近接收记录")
                .setMessage(new SmsDiagnostics(config.prefs).history()).setPositiveButton("关闭", null).create();
            historyDialog.setOnDismissListener(dialog -> historyDialog = null); historyDialog.show();
        });
        text("诊断记录仅在本机保存最近 16 步的时间和处理状态，不包含短信内容、号码或验证码。升级前的接收过程无法补记。", 12, false, Color.rgb(102, 120, 147));
        section("本机手机号"); numberInputs();
        text("换卡或调整卡槽后，请重新核对并保存。无法确定来源卡时，网页会显示「来源卡待确认」。", 13, false, Color.rgb(102, 120, 147));
        button("保存号码", false, view -> {
            try {
                String first = number(phone0), second = number(phone1);
                if (first.isEmpty() && second.isEmpty()) throw new Exception("至少填写一个手机号");
                config.prefs.edit().putString("phone0", first).putString("phone1", second).commit();
                config.rememberSubscriptions();
                new Thread(() -> SyncEngine.sendState(getApplicationContext())).start(); toast("号码已保存");
            } catch (Exception error) { showError(error.getMessage()); }
        });
        section("保持后台可用");
        text("在系统应用设置中允许自启动和后台运行；电池设置选择不限制。部分机型还需要在最近任务中锁定本应用。", 14, false, Color.rgb(102, 120, 147));
        button("打开本应用设置", false, view -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        text("被系统强制停止后，需手动打开一次。重启后请先解锁。断网待传短信在本机加密保存，超过 24 小时自动清理；暂停时不采集新短信。", 13, false, Color.rgb(102, 120, 147));
        button("解除本机配对", false, view -> new AlertDialog.Builder(this).setTitle("解除配对？")
            .setMessage("会删除本机连接凭据和待上传短信。网页中的已有记录保留，请在网页设备管理中停用本设备。")
            .setPositiveButton("解除", (dialog, which) -> {
                config.setEnabled(false); SyncEngine.cancel(this);
                new Thread(() -> {
                    SyncEngine.sendState(getApplicationContext());
                    try (PendingMessages queue = new PendingMessages(getApplicationContext())) { queue.clear(); }
                    config.prefs.edit().clear().commit(); runOnUiThread(this::render);
                }).start();
            }).setNegativeButton("取消", null).show());
        updateStatus();
    }
    private void requestSmsPermission() {
        if (config.smsPermission()) { toast("短信权限已允许"); SmsMonitorService.start(this); syncNow(); return; }
        requestPermissions(new String[]{Manifest.permission.RECEIVE_SMS}, 1);
    }
    private void enableInbox() {
        config.prefs.edit().putBoolean("inboxEnabled", true).putLong("inboxSince", System.currentTimeMillis())
            .putString("inboxError", "").putInt("inboxRows", 0).putLong("inboxCheckedAt", 0).commit();
        new SmsDiagnostics(config.prefs).record("已授权兼容收码，仅检查此后新收到的短信");
        SmsMonitorService.start(this); render(); syncNow();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 1 && (grants.length == 0 || grants[0] != PackageManager.PERMISSION_GRANTED))
            showError("短信权限尚未允许，暂时无法自动同步。可在系统应用设置中允许；若系统不提供该权限，需要针对机型检查兼容性。");
        if (request == 2) config.rememberSubscriptions();
        if (request == 3) {
            if (config.inboxPermission()) enableInbox();
            else showError("读取短信未获允许，兼容收码尚未开启。原有短信接收方式保留。");
        }
        if (request == 4 && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            toast("通知未允许，系统可能只在活动应用中显示后台服务。可在应用设置中开启通知。");
        SmsMonitorService.start(this);
        syncNow(); updateStatus();
    }
    private void syncNow() {
        if (processing || !config.paired()) return;
        processing = true;
        SmsMonitorService.start(this);
        SyncEngine.schedule(this);
        new Thread(() -> {
            if (config.enabled()) SyncEngine.sync(getApplicationContext()); else SyncEngine.sendState(getApplicationContext());
            runOnUiThread(() -> { processing = false; updateStatus(); toast(config.prefs.getString("error", "").isEmpty() ? "连接检查完成" : "连接尚未成功，请查看状态"); });
        }).start();
    }
    private void updateStatus() {
        if (status == null || !config.paired()) return;
        String value = (config.enabled() ? "同步已开启" : "同步已暂停") + "\n短信权限：" + (config.smsPermission() ? "已允许" : "未允许");
        value += "\n后台收码：" + (SmsMonitorService.running ? "服务运行中" : "未运行");
        value += "\n兼容收码：" + (config.inboxEnabled() ? (config.inboxPermission() ? "已开启" : "需要读取短信权限") : "未开启");
        try (PendingMessages queue = new PendingMessages(this)) { value += "\n等待上传：" + queue.count() + " 条"; }
        catch (Exception error) { value += "\n本机队列读取失败"; }
        long last = config.prefs.getLong("lastSync", 0);
        value += "\n最近连接：" + (last == 0 ? "尚未成功" : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(new Date(last)));
        String error = config.prefs.getString("error", ""); if (!error.isEmpty()) value += "\n" + error;
        String inboxError = config.prefs.getString("inboxError", ""); if (!inboxError.isEmpty()) value += "\n" + inboxError;
        status.setText(value);
        if (diagnostics != null) diagnostics.setText(new SmsDiagnostics(config.prefs).summary());
        if (historyDialog != null && historyDialog.isShowing()) historyDialog.setMessage(new SmsDiagnostics(config.prefs).history());
    }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private void showError(String value) { new AlertDialog.Builder(this).setTitle("请检查").setMessage(value).setPositiveButton("知道了", null).show(); }
}
