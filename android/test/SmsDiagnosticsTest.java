package com.smshub.app;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

public class SmsDiagnosticsTest {
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    public static void main(String[] args) {
        Map<String, Object> values = new HashMap<>();
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
            SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, arguments) -> {
                if (method.getName().startsWith("put")) { values.put((String) arguments[0], arguments[1]); return proxy; }
                if (method.getName().equals("apply")) return null;
                if (method.getName().equals("commit")) return true;
                throw new UnsupportedOperationException(method.getName());
            });
        SharedPreferences prefs = (SharedPreferences) Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, arguments) -> {
                if (method.getName().equals("edit")) return editor;
                if (method.getName().equals("getLong") || method.getName().equals("getString") || method.getName().equals("getBoolean") || method.getName().equals("getInt"))
                    return values.getOrDefault((String) arguments[0], arguments[1]);
                throw new UnsupportedOperationException(method.getName());
            });
        SmsDiagnostics diagnostics = new SmsDiagnostics(prefs);
        check(diagnostics.summary().contains("系统短信通知：0 次"), "Fresh install must not claim receipt");
        diagnostics.broadcast();
        diagnostics.record("短信不含验证码关键词，已跳过");
        check(prefs.getLong("smsBroadcastCount", 0) == 1, "Filtered SMS must still be visible in receipt count");
        check(prefs.getLong("lastReceived", 0) == 0, "Filtered SMS must not count as captured OTP");
        diagnostics.uploaded(0);
        check(prefs.getLong("smsUploadedAt", 0) == 0, "Empty heartbeat must not claim an SMS upload");
        diagnostics.failed("本机保存失败", new IllegalStateException("private-message-body"));
        prefs.edit().putString("error", "").putLong("lastSync", System.currentTimeMillis()).apply();
        check(diagnostics.summary().contains("本机保存失败"), "Successful heartbeat must not erase reception failures");
        check(!diagnostics.history().contains("private-message-body"), "Exception messages may contain sensitive data");
        diagnostics.saved(1);
        diagnostics.uploaded(1);
        check(prefs.getLong("lastReceived", 0) > 0 && prefs.getLong("smsUploadedAt", 0) > 0, "Capture and upload timestamps must be recorded separately");
        prefs.edit().putBoolean("enabled", true).putBoolean("inboxEnabled", true).apply();
        diagnostics.inboxStarted(System.currentTimeMillis() - 20000);
        check(diagnostics.summary().contains("超过 15 秒"), "A stuck provider query must not look like an empty inbox");
        diagnostics.inboxFailed("收件箱补查失败（SecurityException）");
        check(diagnostics.summary().contains("SecurityException"), "Provider failures must be visible in diagnostic summary");
        diagnostics.inboxStarted(System.currentTimeMillis());
        check(diagnostics.summary().contains("补查状态：正在补查"), "A retry must be distinguished from a completed query");
        diagnostics.inboxCompleted(0, 0, 0, System.currentTimeMillis());
        check(diagnostics.summary().contains("系统未返回此时间范围内的短信"), "An empty query must report completion without claiming privacy rejection");
        check(!diagnostics.summary().contains("SecurityException"), "A successful query must clear a previous provider error");
        long uploaded = prefs.getLong("smsUploadedAt", 0);
        diagnostics.inboxCompleted(4, 2, 1, System.currentTimeMillis());
        check(diagnostics.summary().contains("4 条短信，其中识别验证码 2 条"), "Ordinary SMS, matching SMS and new saves must be distinguishable");
        check(prefs.getLong("smsUploadedAt", 0) == uploaded, "Successful inbox checks cannot claim a server upload");
        prefs.edit().putBoolean("enabled", false).apply();
        check(diagnostics.summary().contains("已暂停，当前不会补查"), "Compatibility enabled with master paused must say why checks are stopped");
        for (int i = 0; i < 20; i++) diagnostics.record("步骤 " + i);
        check(diagnostics.history().split("\n").length == 16, "Diagnostic history must remain bounded");
        check(diagnostics.history().split("\n")[0].endsWith("步骤 19"), "Newest event must be first");
        System.out.println("SMS diagnostic checks passed");
    }
}
