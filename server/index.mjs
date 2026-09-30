import { resolve } from 'node:path'
import { createApp } from './app.mjs'

const app = createApp({
  password: process.env.ADMIN_PASSWORD,
  key: Buffer.from(process.env.DATA_KEY ?? '', 'base64'),
  publicUrl: process.env.PUBLIC_URL ?? 'http://localhost:8787',
  directory: resolve(process.env.DATA_DIR ?? 'data'),
  staticDirectory: resolve('dist'),
  retentionHours: process.env.RETENTION_HOURS ?? 24,
  devOrigins: process.env.NODE_ENV === 'development' ? ['http://localhost:5173', 'http://127.0.0.1:5173'] : [],
})
app.server.listen(Number(process.env.PORT ?? 8787), process.env.HOST ?? '127.0.0.1', () => console.log('SMS Hub 已启动'))
for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, async () => { await app.close(); process.exit(0) })
