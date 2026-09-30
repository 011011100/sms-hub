package com.smshub.app;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import org.json.JSONArray;
import org.json.JSONObject;

final class Config {
    final SharedPreferences prefs;
    private final Context context;
    Config(Context context) { this.context = context; prefs = context.getSharedPreferences("hub", Context.MODE_PRIVATE); }
    boolean paired() { return !prefs.getString("token", "").isEmpty(); }
    boolean enabled() { return paired() && prefs.getBoolean("enabled", false); }
    boolean smsPermission() { return context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED; }
    String url() { return prefs.getString("url", ""); }
    String name() { return prefs.getString("name", ""); }
    String phone(int slot) { return slot < 0 ? "" : prefs.getString("phone" + slot, ""); }
    void rememberSubscriptions() {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return;
        try {
            java.util.List<SubscriptionInfo> infos = context.getSystemService(SubscriptionManager.class).getActiveSubscriptionInfoList();
            SharedPreferences.Editor editor = prefs.edit().remove("sub0").remove("sub1");
            if (infos != null) for (SubscriptionInfo info : infos) editor.putInt("sub" + info.getSimSlotIndex(), info.getSubscriptionId());
            editor.commit();
        } catch (Exception ignored) {}
    }
    String verifiedPhone(int slot, int subscription) {
        int bound = prefs.getInt("sub" + slot, -1);
        if (bound >= 0 && subscription >= 0 && bound != subscription) {
            error("检测到 SIM 卡变化，请重新核对并保存手机号。"); return "";
        }
        return phone(slot);
    }
    String token() throws Exception { return Vault.decrypt(prefs.getString("token", "")); }
    void paired(String url, JSONObject result, String phone0, String phone1) throws Exception {
        String encryptedToken = Vault.encrypt(result.getString("token"));
        if (!prefs.edit().putString("url", url).putString("token", encryptedToken).putString("name", result.getString("name"))
            .putString("phone0", phone0).putString("phone1", phone1).putBoolean("enabled", true).putString("error", "").commit())
            throw new Exception("无法保存配对信息");
        rememberSubscriptions();
    }
    void error(String value) { prefs.edit().putString("error", value).apply(); }
    JSONObject details() throws Exception {
        JSONArray lines = new JSONArray();
        for (int slot = 0; slot < 2; slot++) if (!phone(slot).isEmpty()) lines.put(new JSONObject().put("slot", slot).put("number", phone(slot)));
        return new JSONObject().put("lines", lines).put("model", Build.MANUFACTURER + " " + Build.MODEL)
            .put("androidVersion", Build.VERSION.RELEASE).put("appVersion", "0.1.0")
            .put("paused", !enabled()).put("smsPermission", smsPermission());
    }
}
