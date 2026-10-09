package com.smshub.app;

/** One start action selects the available, consented collection path and its time boundary. */
final class CollectionPlan {
    final boolean canStart, readInbox;
    final long inboxSince;
    private CollectionPlan(boolean canStart, boolean readInbox, long inboxSince) {
        this.canStart = canStart; this.readInbox = readInbox; this.inboxSince = inboxSince;
    }
    static CollectionPlan create(boolean wasEnabled, boolean wasReadingInbox, long previousSince,
            boolean consent, boolean receivePermission, boolean readPermission, long now) {
        boolean readInbox = consent && readPermission;
        long since = readInbox && (!wasEnabled || !wasReadingInbox || previousSince <= 0) ? now : previousSince;
        return new CollectionPlan(receivePermission || readInbox, readInbox, since);
    }
}
