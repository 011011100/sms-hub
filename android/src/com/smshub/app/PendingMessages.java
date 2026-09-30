package com.smshub.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;

final class PendingMessages extends SQLiteOpenHelper {
    PendingMessages(Context context) { super(context, "pending.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) { db.execSQL("CREATE TABLE pending(id TEXT PRIMARY KEY, received_at INTEGER NOT NULL, sealed TEXT NOT NULL)"); }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}
    private void prune() { getWritableDatabase().delete("pending", "received_at < ?", new String[]{String.valueOf(System.currentTimeMillis() - 24 * 3600000L)}); }
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
    void clear() { getWritableDatabase().delete("pending", null, null); }
}
