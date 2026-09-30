package com.smshub.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** A consent boundary and stable row identity, independent of SMS text or sender timestamps. */
final class InboxPolicy {
    static long since(long enabledAt, long now) { return enabledAt <= 0 ? Long.MAX_VALUE : Math.max(enabledAt, now - 24 * 3600000L); }
    static boolean allows(long receivedAt, long enabledAt, long now) {
        return enabledAt > 0 && receivedAt >= since(enabledAt, now) && receivedAt <= now;
    }
    static String eventId(long rowId, long receivedAt) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(("sms-hub-inbox-v1|" + rowId + "|" + receivedAt).getBytes(StandardCharsets.UTF_8));
        StringBuilder id = new StringBuilder();
        for (byte value : hash) id.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return id.toString();
    }
    private InboxPolicy() {}
}
