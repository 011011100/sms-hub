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
                if (method.getName().equals("getLong") || method.getName().equals("getString"))
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
        for (int i = 0; i < 20; i++) diagnostics.record("步骤 " + i);
        check(diagnostics.history().split("\n").length == 16, "Diagnostic history must remain bounded");
        check(diagnostics.history().split("\n")[0].endsWith("步骤 19"), "Newest event must be first");
        System.out.println("SMS diagnostic checks passed");
    }
}
