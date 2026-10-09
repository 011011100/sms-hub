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
    void inboxStarted(long now) {
        prefs.edit().putLong("inboxAttemptAt", now).putLong("inboxFinishedAt", 0).apply();
    }
    void inboxCompleted(int rows, int matched, int captured, long now) {
        boolean first = prefs.getLong("inboxCheckedAt", 0) == 0;
        boolean recovered = !prefs.getString("inboxError", "").isEmpty();
        prefs.edit().putLong("inboxCheckedAt", now).putLong("inboxFinishedAt", now)
            .putInt("inboxRows", rows).putInt("inboxMatched", matched)
            .putLong("inboxCaptured", prefs.getLong("inboxCaptured", 0) + captured).putString("inboxError", "").apply();
        if (first || recovered || captured > 0) record("补查完成：系统返回 " + rows + " 条，识别验证码 " + matched + " 条，新保存 " + captured + " 条");
    }
    void inboxFailed(String message) {
        if (!message.equals(prefs.getString("inboxError", ""))) record(message);
        prefs.edit().putLong("inboxFinishedAt", System.currentTimeMillis()).putString("inboxError", message).apply();
    }
    void inboxCancelled() { prefs.edit().putLong("inboxFinishedAt", System.currentTimeMillis()).apply(); }
    private String inboxStatus() {
        if (!prefs.getBoolean("enabled", false)) return "已暂停，当前不会补查";
        long attempt = prefs.getLong("inboxAttemptAt", 0), finished = prefs.getLong("inboxFinishedAt", 0);
        if (attempt > 0 && finished == 0) return System.currentTimeMillis() - attempt > 15000
            ? "最近一次补查尚未完成（超过 15 秒），请复制诊断信息" : "正在补查";
        String error = prefs.getString("inboxError", "");
        if (!error.isEmpty()) return error;
        if (prefs.getLong("inboxCheckedAt", 0) == 0) return "尚未完成补查";
        return prefs.getInt("inboxRows", 0) == 0 ? "检查完成，系统未返回此时间范围内的短信" : "检查完成";
    }
    String summary() {
        return "系统短信通知：" + prefs.getLong("smsBroadcastCount", 0) + " 次（0.1.1 起累计）"
            + "\n最近收到通知：" + time(prefs.getLong("smsBroadcastAt", 0))
            + "\n最近保存验证码：" + time(prefs.getLong("lastReceived", 0))
            + "\n最近确认上传：" + time(prefs.getLong("smsUploadedAt", 0))
            + "\n处理状态：" + prefs.getString("smsStage", "等待系统发送新的短信通知")
            + (prefs.getBoolean("inboxEnabled", false) ? "\n补查状态：" + inboxStatus()
                + "\n本次收码起点：" + time(prefs.getLong("inboxSince", 0))
                + "\n最近开始补查：" + time(prefs.getLong("inboxAttemptAt", 0))
                + "\n最近完成补查：" + time(prefs.getLong("inboxCheckedAt", 0))
                + "\n上次系统返回：" + prefs.getInt("inboxRows", 0) + " 条短信，其中识别验证码 " + prefs.getInt("inboxMatched", 0) + " 条"
                + "\n累计补查保存验证码：" + prefs.getLong("inboxCaptured", 0) + " 条" : "\n自动补查未开启");
    }
    String history() { return prefs.getString("smsDiagnosticLog", "尚无记录。请保持本应用打开，再接收一条新的测试短信。"); }
    private static String time(long value) {
        return value == 0 ? "尚无记录" : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(new Date(value));
    }
}
