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
        if (!config.paired()) return;
        SmsDiagnostics diagnostics = new SmsDiagnostics(config.prefs);
        diagnostics.broadcast();
        if (!config.enabled()) { diagnostics.record("同步已暂停，未采集短信"); return; }
        if (!config.smsPermission()) { diagnostics.record("短信权限未允许，未采集短信"); return; }
        try {
            SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
            if (parts == null || parts.length == 0) { diagnostics.record("系统通知中没有短信内容"); return; }
            StringBuilder body = new StringBuilder();
            SmsMessage first = null;
            for (SmsMessage part : parts) if (part != null) {
                if (first == null) first = part;
                if (part.getMessageBody() != null) body.append(part.getMessageBody());
            }
            if (first == null || body.length() == 0) { diagnostics.record("系统通知中的短信内容为空或无法解析"); return; }
            String text = body.toString();
            if (!Otp.isVerification(text)) { diagnostics.record("短信不含验证码关键词，已跳过"); return; }
            if (text.length() > 8000) { diagnostics.record("短信长度超限，已跳过"); return; }
            diagnostics.record("已识别验证码短信");
            int cardSlot = slot(context, intent);
            int subscription = intent.getIntExtra("android.telephony.extra.SUBSCRIPTION_INDEX", intent.getIntExtra("subscription", -1));
            String sender = first.getOriginatingAddress();
            long timestamp = first.getTimestampMillis(), receivedAt = System.currentTimeMillis();
            PendingResult pending = goAsync();
            new Thread(() -> {
                try (PendingMessages queue = new PendingMessages(context)) {
                    if (!config.enabled()) { diagnostics.record("保存前已暂停同步，未采集短信"); return; }
                    String identity = sender + "|" + timestamp + "|" + cardSlot + "|" + text;
                    byte[] hash = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
                    StringBuilder id = new StringBuilder(); for (byte value : hash) id.append(String.format("%02x", value & 0xff));
                    JSONObject message = new JSONObject().put("id", id.toString()).put("receivedAt", receivedAt)
                        .put("sender", sender == null || sender.isEmpty() ? "未知" : sender).put("body", text).put("code", Otp.extract(text))
                        .put("slot", cardSlot).put("phone", config.verifiedPhone(cardSlot, subscription));
                    queue.add(message);
                    diagnostics.saved(cardSlot);
                    try { SyncEngine.schedule(context); }
                    catch (Exception error) { diagnostics.failed("后台重试任务未能安排，继续尝试直接上传", error); }
                    SyncEngine.sync(context);
                } catch (Exception error) {
                    diagnostics.failed("本机保存或处理短信失败", error);
                    config.error("本机处理短信失败，请查看接收诊断。");
                }
                finally { pending.finish(); }
            }, "sms-received").start();
        } catch (Exception error) {
            diagnostics.failed("解析系统短信通知失败", error);
            config.error("短信解析失败，请查看接收诊断。");
        }
    }
}
