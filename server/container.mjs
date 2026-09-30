import { mkdirSync, chownSync, chmodSync } from 'node:fs'
import { resolve } from 'node:path'

// Hosted volumes may start owned by root. Prepare the mount, then drop privileges.
const directory = resolve(process.env.DATA_DIR ?? '/app/data')
mkdirSync(directory, { recursive: true, mode: 0o700 })
if (process.getuid?.() === 0) {
  chownSync(directory, 1000, 1000)
  chmodSync(directory, 0o700)
  process.setgid(1000)
  process.setuid(1000)
}
await import('./index.mjs')
