package com.smshub.app;

import android.content.SharedPreferences;
import java.text.DateFormat;
import java.util.Date;

/** Local stage/timestamp records only: never pass message content or credentials here. */
final class SmsDiagnostics {
    private static final Object LOCK = new Object();
    private final SharedPreferences prefs;
    SmsDiagnostics(SharedPreferences prefs) { this.prefs = prefs; }
    void broadcast() {
        synchronized (LOCK) {
            prefs.edit().putLong("smsBroadcastAt", System.currentTimeMillis())
                .putLong("smsBroadcastCount", prefs.getLong("smsBroadcastCount", 0) + 1).apply();
            record("收到系统短信通知");
        }
    }
    void record(String stage) {
        synchronized (LOCK) {
            String line = time(System.currentTimeMillis()) + "  " + stage;
            String[] old = prefs.getString("smsDiagnosticLog", "").split("\n");
            StringBuilder history = new StringBuilder(line);
            for (int i = 0; i < Math.min(old.length, 15); i++) if (!old[i].isEmpty()) history.append('\n').append(old[i]);
            prefs.edit().putString("smsDiagnosticLog", history.toString()).putString("smsStage", stage).apply();
        }
    }
    void saved(int slot) {
        prefs.edit().putLong("lastReceived", System.currentTimeMillis()).apply();
        record("验证码已保存，等待上传（" + (slot < 0 ? "来源卡待确认" : "卡 " + (slot + 1)) + "）");
    }
    void uploaded(int count) {
        if (count == 0) return;
        prefs.edit().putLong("smsUploadedAt", System.currentTimeMillis()).apply();
        record("服务器已确认接收 " + count + " 条");
    }
    void failed(String stage, Exception error) { record(stage + "（" + error.getClass().getSimpleName() + "）"); }
    String summary() {
        return "系统短信通知：" + prefs.getLong("smsBroadcastCount", 0) + " 次（0.1.1 起累计）"
            + "\n最近收到通知：" + time(prefs.getLong("smsBroadcastAt", 0))
            + "\n最近保存验证码：" + time(prefs.getLong("lastReceived", 0))
            + "\n最近确认上传：" + time(prefs.getLong("smsUploadedAt", 0))
            + "\n处理状态：" + prefs.getString("smsStage", "等待系统发送新的短信通知")
            + (prefs.getBoolean("inboxEnabled", false) ? "\n最近补查收件箱：" + time(prefs.getLong("inboxCheckedAt", 0))
                + "\n已补查保存验证码：" + prefs.getLong("inboxCaptured", 0) + " 条" : "");
    }
    String history() { return prefs.getString("smsDiagnosticLog", "尚无记录。请保持本应用打开，再接收一条新的测试短信。"); }
    private static String time(long value) {
        return value == 0 ? "尚无记录" : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(new Date(value));
    }
}
