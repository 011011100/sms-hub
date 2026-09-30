package com.smshub.app;

public class InboxPolicyTest {
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    public static void main(String[] args) throws Exception {
        long now = 1700000000000L, enabled = now - 60000;
        check(!InboxPolicy.allows(now, 0, now), "No consent boundary must disable collection");
        check(!InboxPolicy.allows(enabled - 1, enabled, now), "Pre-consent history must not be imported");
        check(InboxPolicy.allows(enabled, enabled, now), "Boundary message must be included");
        check(!InboxPolicy.allows(now + 1, enabled, now), "Future messages must be ignored");
        check(!InboxPolicy.allows(now - 25 * 3600000L, now - 48 * 3600000L, now), "Expired history must be excluded");
        check(!InboxPolicy.allows(now - 1000, now, now), "Resetting boundary on resume must exclude paused interval");
        String first = InboxPolicy.eventId(10, now), repeatedScan = InboxPolicy.eventId(10, now), second = InboxPolicy.eventId(11, now);
        check(first.equals(repeatedScan), "Repeated scans of the same provider row must deduplicate");
        check(!first.equals(second), "Two different SMS rows must remain distinct even with identical content and timestamps");
        check(!first.equals(InboxPolicy.eventId(10, now + 1000)), "A reused row ID must not hide a later message");
        check(first.matches("[a-f0-9]{64}"), "Inbox event IDs must match the upload contract");
        System.out.println("Inbox consent and identity checks passed");
    }
}
