# SMS Hub

把自己多部安卓手机新收到的验证码短信，集中到 Vue 网页查看。

项目包含 **Vue 3 网页、Node.js 后端、原生安卓 App**。网页和后端在同一个服务中部署，十来部手机共用一个服务。无需连接本任务以外的 PostgreSQL 数据库。

## 第一次使用

1. 部署网页和后端，取得可用的 **HTTPS 根地址**。
2. 用管理员密码登录网页，点击「添加手机」，填写设备备注，生成一次性配对码。
3. 在对应手机安装 `sms-hub-0.1.0.apk`，打开 App，填写服务器地址、配对码、本机卡 1／卡 2 号码。
4. 确认同步范围并配对，允许接收短信。双卡手机同时允许识别 SIM 卡槽。
5. 按手机系统要求允许自启动、后台运行，并将电池策略设置为不限制。
6. 给两个号码分别发送新的测试验证码，检查网页显示的手机号、来源卡、内容与实际一致；再验证锁屏和断网恢复。

完成设置后，手机接收符合识别规则的新短信，自动上传到你的服务器，网页每 3 秒刷新。手机可以通过 Wi-Fi 或流量联网，不要求在同一个局域网。

## 功能与边界

- 手机配对码 10 分钟有效、仅可使用一次；每台设备有独立且可撤销的上传凭据。
- 按号码、设备、平台或内容筛选；验证码一键复制，保留原文供核对。
- 双卡分别标记；无法识别来源卡时显示「来源卡待确认」，不会猜号码。
- 手机号码是人工配置的标签，不依赖 SIM 卡能否自动读出本机号码。换卡后要重新核对并保存。
- 在取得卡槽识别权限后记录订阅标识；标识变化时不继续沿用旧号码标签。
- 只采集启用后的新验证码短信；不扫描历史收件箱，不读取联系人和通话记录，不发送短信。
- 识别依据是本机关键词和规则（验证码、校验码、安全码、OTP、code 等），不调用 AI，也不向其他供应商发送短信。非常规格式可能不匹配；有关键词但无法确定验证码时上传原文供人工查看。
- 手机队列加密保存，断网自动重试；服务器按设备和事件去重。暂停期间不采集新短信，暂停前的待传数据保留，恢复后继续同步。
- 手机待传队列保留 24 小时；服务器默认保留 24 小时，可配置 1–168 小时。自动清理时间不是验证码有效期。
- 网页显示最近连接时间。系统可能延迟 15 分钟的周期任务，不能将其当作实时在线探针。
- 服务端加密保存短信内容与号码；手机待传队列和上传令牌加密保存，号码配置保存在应用私有目录。网络使用 HTTPS。设备令牌只能上传，不能读取其他手机短信。
- 网页为单管理员模式，登录会话 12 小时；无默认线上密码。

### 安卓兼容性

最低 Android 8.0（API 26），目标 API 35，不需要 Google Play 服务。编译产物不包含 CPU 原生库，适用于常见 ARM、ARM64 和 x86 设备。

**APK 已编译不等于所有型号已经实测兼容。** 国产系统的后台限制、安装来源的短信权限限制，以及新系统对部分验证码的保护，可能造成权限不可授予或收不到部分验证码。当前版本使用系统 `SMS_RECEIVED` 广播，**不是默认短信应用**，不会伪装为系统应用或绕过系统限制。若某台手机无法授权或收不到受保护短信，需要针对该机型评估受支持的接入方式，不能承诺安装后全部验证码都能即时读取。

Android 官方文档：

- [短信权限](https://developer.android.com/reference/android/Manifest.permission#RECEIVE_SMS)
- [短信广播](https://developer.android.com/reference/android/provider/Telephony.Sms.Intents#SMS_RECEIVED_ACTION)
- [部分受保护短信的访问限制](https://developer.android.com/reference/android/provider/Telephony.Sms)

强制停止后需要手动打开 App；重启后首次解锁才能访问加密队列。系统在未授予权限、强制停止或限制应用期间没有投递的短信，当前版本不能从历史收件箱补回。首次安装后请逐台做收码检查。

## 在 Zeabur 部署

使用仓库根目录的 Dockerfile，把网页和后端部署为 **一个服务、一个副本**。

1. 在 Zeabur 选择 GitHub 仓库 `011011100/sms-hub` 的 `main` 分支，构建上下文为仓库根目录。确认使用 Dockerfile。
2. 给服务添加持久存储卷，挂载到 `/app/data`。SQLite、设备凭据哈希和登录会话均保存在这个目录。
3. 配置以下环境变量（密码和密钥需要自行生成，不能使用示例字符串）：

| 变量 | 值 |
|---|---|
| `HOST` | `0.0.0.0` |
| `PORT` | `8787` |
| `DATA_DIR` | `/app/data` |
| `PUBLIC_URL` | 最终 HTTPS 域名，例如 `https://你的域名.zeabur.app` |
| `ADMIN_PASSWORD` | 至少 12 位的独立随机管理密码 |
| `DATA_KEY` | 32 字节随机值的 Base64 编码 |
| `RETENTION_HOURS` | `24` |

本地执行 `npm run setup` 会生成 `.env`，其中包含随机密码和密钥。把值填入 Zeabur 变量；不要提交 `.env`，不要把管理密码填入安卓 App。

4. 在网络设置绑定域名，HTTP 服务端口指向 **8787**，与 `PORT` 一致。Zeabur 提供 HTTPS，App 使用这个 HTTPS 根地址。
5. 确认 `https://你的域名/api/health` 返回 `{"ok":true}`，再检查网页登录、配对和同步。健康检查不代表真机收码已经验证。

必须保留同一个 `DATA_KEY`。已有数据库不能随意换密钥，否则历史短信和设备详情无法解密。备份时将数据卷与密钥分别保存。不要水平扩容多个独立 SQLite 实例。

Docker 启动脚本会初始化数据卷目录的所有权，然后以非 root 用户运行服务，适配新挂载的 Zeabur 数据卷。容器重启会保留绑定信息，前提是卷正确挂载。

[Zeabur Dockerfile 文档](https://zeabur.com/docs/en-US/deploy/methods/dockerfile) · [持久存储文档](https://zeabur.com/docs/en-US/data-management/volumes)

## 本地运行

要求 Node.js 24.14 或更高的 24.x 版本。

```bash
npm ci
npm run setup
npm run build
npm start
```

打开 `http://localhost:8787`，使用 `.env` 中的 `ADMIN_PASSWORD`。这个 HTTP 地址仅供本机预览；安卓端只接受有效证书的 HTTPS。

开发 Vue 页面时，后端设置 `NODE_ENV=development` 后启动，再运行 `npm run dev`。开发代理只放行本机 5173 端口的来源，生产不放行。

自有服务器可使用 `compose.yaml` 和 Caddy：将 `.env` 中 `SMS_DOMAIN` 设置为域名，`PUBLIC_URL` 设置为对应 HTTPS 地址，开放 80/443 后运行 `docker compose up -d --build`。Zeabur 不需要部署该 Compose 或 Caddy 服务。

## 编译安卓 APK

App 使用 Java 原生界面和 Android 标准 API，没有第三方运行时依赖。需要 JDK 17、Android SDK Platform 35、Build Tools 35.0.0，以及 `zip`。可从 Android Studio 的 SDK Manager 安装 SDK。

```bash
export JAVA_HOME="你的 JDK 目录"
export ANDROID_JAR="你的 SDK/platforms/android-35/android.jar"
export ANDROID_BUILD_TOOLS="你的 SDK/build-tools/35.0.0"
npm run android:build
```

产物：`android/build/sms-hub-0.1.0.apk`，构建过程会验证 APK 签名。

默认构建生成本机测试签名 `android/build/local.keystore`。长期使用应指定 `APK_KEYSTORE`、`APK_KEYSTORE_PASSWORD`、`APK_KEY_ALIAS`。**保留原签名密钥才能覆盖更新已安装的 APK。** 密钥和安装包均不会进入 Git 提交；发布版安装包单独提供。CI 每次生成的临时签名只用于构建检查，不适合当作持续更新渠道。

本次交付的 APK 使用独立发布签名。本机签名文件位于 `android/signing/`（已忽略，不上传 GitHub），请自行备份该目录。更新发布包时加载该目录的 `signing.env`，再运行构建脚本。不要把这个目录或其中的密码上传到公开仓库。

## 验证

```bash
npm test
npm run build
mkdir -p android/build/test
javac -encoding UTF-8 -d android/build/test android/src/com/smshub/app/Otp.java android/test/OtpTest.java
java -cp android/build/test OtpTest
```

后端集成检查覆盖会话、来源校验、一次性配对、配对过期、多设备与双卡、幂等补传、设备撤销、短信加密、过期清理、整批校验和来源卡未知。GitHub Actions 同时构建网页、运行这些检查并编译 APK。

上线前真机检查：分别向卡 1／卡 2 发送短信；锁屏后收码；断网收码后联网；重启解锁；暂停／恢复；换卡后重新绑定；从网页停用设备。请用测试账号的验证码完成初次验证。

## 目录

```text
web/          Vue 网页
server/       登录、设备配对、加密存储、同步接口
android/      原生安卓 App、APK 构建脚本
test/         后端集成检查
scripts/      本地配置生成
```
