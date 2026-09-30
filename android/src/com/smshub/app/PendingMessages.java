package com.smshub.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;

final class PendingMessages extends SQLiteOpenHelper {
    PendingMessages(Context context) { super(context, "pending.db", null, 2); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE pending(id TEXT PRIMARY KEY, received_at INTEGER NOT NULL, sealed TEXT NOT NULL)");
        createInboxSeen(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { if (oldVersion < 2) createInboxSeen(db); }
    private void createInboxSeen(SQLiteDatabase db) { db.execSQL("CREATE TABLE IF NOT EXISTS inbox_seen(id TEXT PRIMARY KEY, received_at INTEGER NOT NULL)"); }
    private void prune() {
        String[] before = {String.valueOf(System.currentTimeMillis() - 24 * 3600000L)};
        getWritableDatabase().delete("pending", "received_at < ?", before);
        getWritableDatabase().delete("inbox_seen", "received_at < ?", before);
    }
    boolean hasInboxEvent(String id) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT 1 FROM inbox_seen WHERE id=?", new String[]{id})) { return cursor.moveToFirst(); }
    }
    boolean addInbox(JSONObject message) throws Exception {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            String id = message.getString("id");
            if (hasInboxEvent(id)) return false;
            add(message);
            db.execSQL("INSERT INTO inbox_seen(id,received_at) VALUES(?,?)", new Object[]{id, message.getLong("receivedAt")});
            db.setTransactionSuccessful(); return true;
        } finally { db.endTransaction(); }
    }
    void add(JSONObject message) throws Exception {
        prune();
        ContentValues row = new ContentValues();
        row.put("id", message.getString("id")); row.put("received_at", message.getLong("receivedAt")); row.put("sealed", Vault.encrypt(message.toString()));
        getWritableDatabase().execSQL("INSERT OR IGNORE INTO pending(id,received_at,sealed) VALUES(?,?,?)",
            new Object[]{row.getAsString("id"), row.getAsLong("received_at"), row.getAsString("sealed")});
    }
    JSONArray batch() throws Exception {
        prune();
        JSONArray result = new JSONArray();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT sealed FROM pending ORDER BY received_at LIMIT 32", null)) {
            while (cursor.moveToNext()) result.put(new JSONObject(Vault.decrypt(cursor.getString(0))));
        }
        return result;
    }
    int count() {
        prune();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM pending", null)) { cursor.moveToFirst(); return cursor.getInt(0); }
    }
    void acknowledge(JSONArray ids) throws Exception {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try { for (int i = 0; i < ids.length(); i++) db.delete("pending", "id=?", new String[]{ids.getString(i)}); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
    void clear() {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try { db.delete("pending", null, null); db.delete("inbox_seen", null, null); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }
}
