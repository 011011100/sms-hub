import { DatabaseSync } from 'node:sqlite'
import { createCipheriv, createDecipheriv, createHash, randomBytes, randomUUID } from 'node:crypto'
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'

export const digest = value => createHash('sha256').update(value).digest('hex')

export function createStore({ directory, key, retentionHours = 24, now = Date.now }) {
  mkdirSync(directory, { recursive: true, mode: 0o700 })
  const db = new DatabaseSync(join(directory, 'sms-hub.sqlite'))
  db.exec(`PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON; PRAGMA secure_delete=ON;
    CREATE TABLE IF NOT EXISTS devices (
      id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE,
      created_at INTEGER NOT NULL, last_seen INTEGER, revoked INTEGER NOT NULL DEFAULT 0,
      details TEXT NOT NULL DEFAULT '{}'
    );
    CREATE TABLE IF NOT EXISTS pairings (
      code_hash TEXT PRIMARY KEY, name TEXT NOT NULL, expires_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS sessions (hash TEXT PRIMARY KEY, expires_at INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS messages (
      device_id TEXT NOT NULL REFERENCES devices(id), event_id TEXT NOT NULL,
      received_at INTEGER NOT NULL, uploaded_at INTEGER NOT NULL, sealed TEXT NOT NULL,
      PRIMARY KEY(device_id, event_id)
    );
    CREATE INDEX IF NOT EXISTS messages_time ON messages(received_at DESC);`)

  function seal(value) {
    const nonce = randomBytes(12)
    const cipher = createCipheriv('aes-256-gcm', key, nonce)
    const encrypted = Buffer.concat([cipher.update(JSON.stringify(value), 'utf8'), cipher.final()])
    return Buffer.concat([nonce, cipher.getAuthTag(), encrypted]).toString('base64')
  }
  function unseal(value) {
    const data = Buffer.from(value, 'base64')
    const cipher = createDecipheriv('aes-256-gcm', key, data.subarray(0, 12))
    cipher.setAuthTag(data.subarray(12, 28))
    return JSON.parse(Buffer.concat([cipher.update(data.subarray(28)), cipher.final()]).toString('utf8'))
  }
  function transaction(callback) {
    db.exec('BEGIN IMMEDIATE')
    try { const result = callback(); db.exec('COMMIT'); return result }
    catch (error) { db.exec('ROLLBACK'); throw error }
  }
  function cleanup() {
    const time = now()
    db.prepare('DELETE FROM messages WHERE received_at < ?').run(time - retentionHours * 3600000)
    db.prepare('DELETE FROM pairings WHERE expires_at <= ?').run(time)
    db.prepare('DELETE FROM sessions WHERE expires_at <= ?').run(time)
  }
  return {
    cleanup,
    close: () => db.close(),
    pair(name) {
      cleanup()
      const code = randomBytes(8).toString('hex').toUpperCase()
      const expiresAt = now() + 10 * 60000
      db.prepare('INSERT INTO pairings VALUES (?, ?, ?)').run(digest(code), name, expiresAt)
      return { code, expiresAt }
    },
    redeem(code, details) {
      return transaction(() => {
        const pairing = db.prepare('SELECT * FROM pairings WHERE code_hash=? AND expires_at>?').get(digest(code), now())
        if (!pairing) return null
        const id = randomUUID(), token = randomBytes(32).toString('base64url')
        db.prepare('INSERT INTO devices(id,name,token_hash,created_at,last_seen,details) VALUES(?,?,?,?,?,?)')
          .run(id, pairing.name, digest(token), now(), now(), seal(details))
        db.prepare('DELETE FROM pairings WHERE code_hash=?').run(digest(code))
        return { id, name: pairing.name, token }
      })
    },
    device(token) { return db.prepare('SELECT id,name FROM devices WHERE token_hash=? AND revoked=0').get(digest(token)) },
    devices() {
      return db.prepare('SELECT id,name,created_at,last_seen,revoked,details FROM devices ORDER BY created_at DESC').all()
        .map(row => ({ id: row.id, name: row.name, createdAt: row.created_at, lastSeen: row.last_seen,
          revoked: Boolean(row.revoked), ...unseal(row.details) }))
    },
    revoke(id) { return db.prepare('UPDATE devices SET revoked=1 WHERE id=?').run(id).changes > 0 },
    rename(id, name) { return db.prepare('UPDATE devices SET name=? WHERE id=?').run(name, id).changes > 0 },
    ingest(deviceId, messages, details) {
      return transaction(() => {
        cleanup()
        db.prepare('UPDATE devices SET last_seen=?,details=? WHERE id=?').run(now(), seal(details), deviceId)
        const insert = db.prepare('INSERT OR IGNORE INTO messages VALUES(?,?,?,?,?)')
        const oldest = now() - retentionHours * 3600000
        for (const message of messages) {
          // Acknowledge expired events too, so an offline phone can discard them safely.
          if (message.receivedAt >= oldest) insert.run(deviceId, message.id, message.receivedAt, now(), seal(message))
        }
        return messages.map(message => message.id)
      })
    },
    messages() {
      cleanup()
      return db.prepare(`SELECT m.*,d.name FROM messages m JOIN devices d ON d.id=m.device_id
        ORDER BY m.received_at DESC, m.uploaded_at DESC LIMIT 1000`).all()
        .map(row => ({ ...unseal(row.sealed), deviceId: row.device_id, deviceName: row.name, uploadedAt: row.uploaded_at }))
    },
    clearMessages() { db.exec('DELETE FROM messages; PRAGMA wal_checkpoint(TRUNCATE)') },
    session() {
      const token = randomBytes(32).toString('base64url')
      db.prepare('INSERT INTO sessions VALUES (?,?)').run(digest(token), now() + 12 * 3600000)
      return token
    },
    hasSession(token) { return Boolean(token && db.prepare('SELECT 1 FROM sessions WHERE hash=? AND expires_at>?').get(digest(token), now())) },
    logout(token) { if (token) db.prepare('DELETE FROM sessions WHERE hash=?').run(digest(token)) },
  }
}
