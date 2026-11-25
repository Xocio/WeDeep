<p align="center">
  <img src="ZeroUI/app/src/main/res/drawable-nodpi/ic_logo.png" width="128" alt="WeDeep" />
</p>

<h1 align="center">WeDeep</h1>

<p align="center">
  在本机查看、搜索、导出并编辑自己的微信聊天记录副本
</p>

<p align="center">
  <a href="https://github.com/Xocio/WeDeep/releases/latest"><img src="https://img.shields.io/github/v/release/Xocio/WeDeep?label=%E4%B8%8B%E8%BD%BD" alt="Release" /></a>
  <a href="https://github.com/Xocio/WeDeep/releases"><img src="https://img.shields.io/github/downloads/Xocio/WeDeep/total?label=%E4%B8%8B%E8%BD%BD%E9%87%8F" alt="Downloads" /></a>
  <a href="https://github.com/Xocio/WeDeep/stargazers"><img src="https://img.shields.io/github/stars/Xocio/WeDeep?style=flat" alt="Stars" /></a>
  <a href="https://github.com/Xocio/WeDeep/commits"><img src="https://img.shields.io/github/last-commit/Xocio/WeDeep" alt="Last commit" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="License" /></a>
  <img src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white" alt="Android" />
  <img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/JDK-21-ED8B00?logo=openjdk&logoColor=white" alt="JDK 21" />
  <img src="https://img.shields.io/badge/%E6%95%B0%E6%8D%AE-%E4%BB%85%E6%9C%AC%E6%9C%BA%E5%A4%84%E7%90%86-2EA44F" alt="Local only" />
</p>

WeDeep 是一款 Android 应用，用于在本人设备上查看、搜索、导出并（可选）编辑自己的微信聊天记录副本。聊天记录只在本机处理，不会上传到 WeDeep 服务。

> WeDeep 不是微信官方产品，与腾讯公司及微信没有任何隶属、合作或授权关系。读取、解密微信数据库可能违反微信相关服务条款，请仅处理你本人拥有或已获授权的数据，并自行承担使用后果。

## 功能

- **账号与解密**：在已 Root 的设备上直接读取微信数据，或导入账号目录的 zip 包、`EnMicroMsg.db` 文件。解密在本机离线完成。
- **聊天浏览**：会话列表、聊天详情、图片、语音、视频与文件，支持搜索文本消息与卡片标题。
- **导出**：将单个或全部会话导出为 JSON 与 HTML，可附带媒体文件，支持仅导出新消息。
- **聊天记录编辑**：在应用内的副本上增删改消息，生成重新加密的数据库或截图，不影响微信本身。
- **写回微信**：在已 Root 的设备上把编辑结果写入微信数据库，写入前保留原数据库备份。
- **AI 分析**：把选中的聊天内容发送到你自行配置的模型服务商进行分析，内容不经过 WeDeep 服务。

## 公开范围

本仓库公开除以下两部分之外的全部源代码：

- 数据库解密
- 聊天记录编辑与写回微信

这两部分不公开，是为了防止被不法分子利用：公开解密与写回的实现，会降低他人窃取他人聊天记录、伪造或篡改聊天记录的门槛。

这两部分在本仓库中以占位实现代替（`ZeroUI/app/src/stub`）。从本仓库构建的版本可以编译和运行，但无法解密账号，因而无法浏览、导出或分析聊天记录，也不提供聊天记录编辑。完整功能请使用 [Releases](https://github.com/Xocio/WeDeep/releases) 中的正式版本。

## 隐私与联网

- 聊天记录、联系人、账号信息、解密密钥与任何文件都不会发送到 WeDeep 服务。
- 正式版本会连接 WeDeep 服务读取功能状态、公告与最新版本号，请求不携带设备标识、账号信息或聊天内容；使用统计默认不发送，仅在同意后每天至多一次。完整说明见应用内的用户协议。
- 从源代码构建的版本默认不连接 WeDeep 服务，也不包含公告、检查更新与使用统计。
- AI 分析只连接你自行配置的模型服务商。

## 构建

环境要求：

- JDK 21
- Android SDK 37
- 运行设备为 Android 7.0（API 24）及以上，部分视觉效果需要 Android 13

```bash
cd ZeroUI
./gradlew :app:assembleDebug
```

生成的 APK 位于 `ZeroUI/app/build/outputs/apk/debug/`。

运行单元测试、Lint 与构建（与 CI 相同）：

```bash
cd ZeroUI
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

## 目录结构

| 路径 | 内容 |
| --- | --- |
| `ZeroUI/app` | WeDeep 应用：界面、账号与数据通道、导出、AI 分析 |
| `ZeroUI/app/src/stub` | 未公开部分的占位实现 |
| `ZeroUI/wedeep-core` | 纯 JVM 核心库：消息模型、媒体路径、导出 |
| `ZeroUI/kit` | 界面组件库：主题、组件、导航、动效与液态玻璃效果 |
| `ZeroUI/baselineprofile` | Baseline Profile 生成与启动性能测试 |

## 许可

本仓库的源代码以 [GNU General Public License v3.0](LICENSE) 发布。第三方组件的许可见 [ZeroUI/THIRD_PARTY_NOTICES.md](ZeroUI/THIRD_PARTY_NOTICES.md)。

Releases 中的正式版本包含未公开的组件，按应用内的用户协议提供。

提交 Pull Request 即表示你同意作者在 GPL-3.0 之外，以其他许可在正式版本中使用你提交的代码。
