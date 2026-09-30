package com.smshub.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.JSONObject;

public final class SmsReceiver extends BroadcastReceiver {
    private int slot(Context context, Intent intent) {
        int subscription = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", intent.getIntExtra("subscription", -1));
        if (subscription >= 0 && context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            try {
                SubscriptionInfo info = context.getSystemService(SubscriptionManager.class).getActiveSubscriptionInfo(subscription);
                if (info != null && info.getSimSlotIndex() >= 0 && info.getSimSlotIndex() <= 1) return info.getSimSlotIndex();
            } catch (Exception ignored) {}
        }
        for (String key : new String[]{"android.telephony.extra.SLOT_INDEX", "slot", "slot_id", "simSlot"}) {
            int value = intent.getIntExtra(key, -1);
            if (value >= 0 && value <= 1) return value;
        }
        return -1;
    }
    @Override public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        Config config = new Config(context);
        if (!config.enabled() || !config.smsPermission()) return;
        SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (parts == null || parts.length == 0) return;
        StringBuilder body = new StringBuilder();
        for (SmsMessage part : parts) if (part != null && part.getMessageBody() != null) body.append(part.getMessageBody());
        String text = body.toString();
        if (!Otp.isVerification(text) || text.length() > 8000) return;
        int cardSlot = slot(context, intent);
        int subscription = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", intent.getIntExtra("subscription", -1));
        String sender = parts[0].getOriginatingAddress();
        long timestamp = parts[0].getTimestampMillis(), receivedAt = System.currentTimeMillis();
        PendingResult pending = goAsync();
        new Thread(() -> {
            try (PendingMessages queue = new PendingMessages(context)) {
                if (!config.enabled()) return;
                String identity = sender + "|" + timestamp + "|" + cardSlot + "|" + text;
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
                StringBuilder id = new StringBuilder(); for (byte value : hash) id.append(String.format("%02x", value & 0xff));
                JSONObject message = new JSONObject().put("id", id.toString()).put("receivedAt", receivedAt)
                    .put("sender", sender == null || sender.isEmpty() ? "未知" : sender).put("body", text).put("code", Otp.extract(text))
                    .put("slot", cardSlot).put("phone", config.verifiedPhone(cardSlot, subscription));
                queue.add(message);
                config.prefs.edit().putLong("lastReceived", receivedAt).apply();
                SyncEngine.schedule(context);
                SyncEngine.sync(context);
            } catch (Exception error) { config.error("本机保存短信失败，请打开 App 检查存储空间。"); }
            finally { pending.finish(); }
        }, "sms-received").start();
    }
}
