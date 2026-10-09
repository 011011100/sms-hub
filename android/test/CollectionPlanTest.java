package com.smshub.app;

public final class CollectionPlanTest {
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    public static void main(String[] args) {
        long now = 1700000000000L, old = now - 60000;
        CollectionPlan first = CollectionPlan.create(false, false, 0, true, true, true, now);
        check(first.canStart && first.readInbox && first.inboxSince == now, "One start must enable collection and inbox together");
        check(!InboxPolicy.allows(now - 1, first.inboxSince, now), "First start cannot collect historical SMS");
        CollectionPlan resumed = CollectionPlan.create(false, true, old, true, true, true, now);
        check(resumed.canStart && resumed.readInbox && resumed.inboxSince == now, "Paused compatibility mode must resume with a fresh boundary");
        check(!InboxPolicy.allows(now - 1000, resumed.inboxSince, now), "Paused interval must stay excluded");
        CollectionPlan repeated = CollectionPlan.create(true, true, old, true, true, true, now);
        check(repeated.inboxSince == old, "Repairing permissions or retrying start while active cannot skip existing eligible SMS");
        CollectionPlan upgrade = CollectionPlan.create(true, false, 0, true, true, true, now);
        check(upgrade.readInbox && upgrade.inboxSince == now, "An upgrade from broadcast-only mode cannot import history");
        CollectionPlan declinedRead = CollectionPlan.create(false, true, old, true, true, false, now);
        check(declinedRead.canStart && !declinedRead.readInbox, "Denying READ_SMS must retain permitted broadcast collection");
        CollectionPlan noPermissions = CollectionPlan.create(false, true, old, true, false, false, now);
        check(!noPermissions.canStart && !noPermissions.readInbox, "Consent alone is not permission to start");
        CollectionPlan noConsent = CollectionPlan.create(false, false, 0, false, false, true, now);
        check(!noConsent.canStart && !noConsent.readInbox, "Granted READ_SMS alone cannot replace in-app scope consent");
        CollectionPlan inboxOnly = CollectionPlan.create(false, false, 0, true, false, true, now);
        check(inboxOnly.canStart && inboxOnly.readInbox, "Inbox collection can work without system broadcasts");
        System.out.println("One-tap collection permission and resume checks passed");
    }
}
