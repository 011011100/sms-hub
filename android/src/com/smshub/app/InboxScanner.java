package com.smshub.app;

import android.content.Context;
import android.database.Cursor;
import android.provider.Telephony;
import org.json.JSONObject;

final class InboxScanner {
    static void capture(Context context, PendingMessages queue) {
        Config config = new Config(context);
        if (!config.enabled() || !config.inboxEnabled()) return;
        SmsDiagnostics diagnostics = new SmsDiagnostics(config.prefs);
        if (!config.inboxPermission()) { failure(config, diagnostics, "读取短信权限未允许，请在 App 开启兼容收码"); return; }
        long now = System.currentTimeMillis(), enabledAt = config.prefs.getLong("inboxSince", 0);
        if (enabledAt <= 0) { failure(config, diagnostics, "缺少兼容收码授权时间，请关闭后重新开启"); return; }
        String[] columns = {"_id", "date", "address", "body", "sub_id"};
        int rows = 0, captured = 0;
        try (Cursor cursor = context.getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI, columns,
                "date >= ? AND date <= ?", new String[]{String.valueOf(InboxPolicy.since(enabledAt, now)), String.valueOf(now)}, "date ASC, _id ASC")) {
            if (cursor == null) throw new IllegalStateException("No SMS provider cursor");
            while (cursor.moveToNext()) {
                // Recheck consent when the user pauses or disables the feature during a query.
                if (!config.enabled() || !config.inboxEnabled() || config.prefs.getLong("inboxSince", 0) != enabledAt) return;
                rows++;
                long receivedAt = cursor.getLong(1);
                if (!InboxPolicy.allows(receivedAt, enabledAt, now)) continue;
                String id = InboxPolicy.eventId(cursor.getLong(0), receivedAt);
                if (queue.hasInboxEvent(id)) continue;
                String body = cursor.getString(3);
                if (body == null || body.length() > 8000 || !Otp.isVerification(body)) continue;
                int subscription = cursor.isNull(4) ? -1 : cursor.getInt(4);
                int slot = config.slotForSubscription(subscription);
                String sender = cursor.getString(2);
                JSONObject message = new JSONObject().put("id", id).put("receivedAt", receivedAt)
                    .put("sender", sender == null || sender.isEmpty() ? "未知" : sender).put("body", body).put("code", Otp.extract(body))
                    .put("slot", slot).put("phone", config.verifiedPhone(slot, subscription));
                if (queue.addInbox(message)) { captured++; diagnostics.saved(slot); }
            }
            config.prefs.edit().putLong("inboxCheckedAt", System.currentTimeMillis()).putInt("inboxRows", rows)
                .putLong("inboxCaptured", config.prefs.getLong("inboxCaptured", 0) + captured).putString("inboxError", "").apply();
            if (captured > 0) diagnostics.record("从系统收件箱补查并保存 " + captured + " 条验证码");
        } catch (Exception error) {
            failure(config, diagnostics, "收件箱补查失败（" + error.getClass().getSimpleName() + "）");
        }
    }
    private static void failure(Config config, SmsDiagnostics diagnostics, String message) {
        if (!message.equals(config.prefs.getString("inboxError", ""))) diagnostics.record(message);
        config.prefs.edit().putString("inboxError", message).apply();
    }
    private InboxScanner() {}
}
