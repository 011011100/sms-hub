import { randomBytes } from 'node:crypto'
import { writeFileSync } from 'node:fs'

const config = `HOST=127.0.0.1\nPORT=8787\nPUBLIC_URL=http://localhost:8787\nADMIN_PASSWORD=${randomBytes(24).toString('base64url')}\nDATA_KEY=${randomBytes(32).toString('base64')}\nDATA_DIR=./data\nRETENTION_HOURS=24\n`
try {
  writeFileSync('.env', config, { flag: 'wx', mode: 0o600 })
  console.log('已生成 .env。登录密码在 ADMIN_PASSWORD 中。连接安卓前，请部署 HTTPS 并修改 PUBLIC_URL。')
} catch (error) {
  if (error.code === 'EEXIST') console.log('.env 已存在，保留原配置。')
  else throw error
}
