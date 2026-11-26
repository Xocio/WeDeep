<p align="center">
  <img src=".github/logo.png" width="128" alt="WeDeep" />
</p>

<h1 align="center">WeDeep</h1>

<p align="center">
  在本機檢視、搜尋、匯出並編輯自己的微信聊天記錄副本
</p>

<p align="center">
  <a href="README.md">简体中文</a> | <b>繁體中文</b> | <a href="README.en.md">English</a>
</p>

<p align="center">
  基於 <a href="https://github.com/Xocio/ZeroUI">ZeroUI</a> 開發
</p>

<p align="center">
  <a href="https://pan.quark.cn/s/cfff69d4b768"><img src="https://img.shields.io/badge/%E4%B8%8B%E8%BC%89-%E5%A4%B8%E5%85%8B%E7%B6%B2%E7%9B%A4-0A84FF" alt="Download" /></a>
  <a href="https://github.com/Xocio/WeDeep/stargazers"><img src="https://img.shields.io/github/stars/Xocio/WeDeep?style=flat" alt="Stars" /></a>
  <a href="https://github.com/Xocio/WeDeep/commits"><img src="https://img.shields.io/github/last-commit/Xocio/WeDeep" alt="Last commit" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="License" /></a>
  <img src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white" alt="Android" />
  <img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/JDK-21-ED8B00?logo=openjdk&logoColor=white" alt="JDK 21" />
  <img src="https://img.shields.io/badge/%E8%B3%87%E6%96%99-%E5%83%85%E6%9C%AC%E6%A9%9F%E8%99%95%E7%90%86-2EA44F" alt="Local only" />
</p>

WeDeep 是一款 Android 應用程式，用於在本人裝置上檢視、搜尋、匯出並（可選）編輯自己的微信聊天記錄副本。聊天記錄只在本機處理，不會上傳到 WeDeep 服務。

> WeDeep 不是微信官方產品，與騰訊公司及微信沒有任何隸屬、合作或授權關係。讀取、解密微信資料庫可能違反微信相關服務條款，請僅處理你本人擁有或已獲授權的資料，並自行承擔使用後果。

## 功能

- **帳號與解密**：在已 Root 的裝置上直接讀取微信資料，或匯入帳號目錄的 zip 包、`EnMicroMsg.db` 檔案。解密在本機離線完成。
- **聊天瀏覽**：會話列表、聊天詳情、圖片、語音、影片與檔案，支援搜尋文字訊息與卡片標題。
- **匯出**：將單個或全部會話匯出為 JSON 與 HTML，可附帶媒體檔案，支援僅匯出新訊息。
- **聊天記錄編輯**：在應用程式內的副本上新增、刪除、修改訊息，產生重新加密的資料庫或截圖，不影響微信本身。
- **寫回微信**：在已 Root 的裝置上把編輯結果寫入微信資料庫，寫入前保留原資料庫備份。
- **AI 分析**：把選取的聊天內容傳送到你自行設定的模型服務商進行分析，內容不經過 WeDeep 服務。

## 公開範圍

本倉庫公開除以下兩部分之外的全部原始碼：

- 資料庫解密
- 聊天記錄編輯與寫回微信

這兩部分不公開，是為了防止被不法分子利用：公開解密與寫回的實作，會降低他人竊取他人聊天記錄、偽造或竄改聊天記錄的門檻。

這兩部分在本倉庫中以佔位實作代替（`ZeroUI/app/src/stub`）。從本倉庫建置的版本可以編譯和執行，但無法解密帳號，因而無法瀏覽、匯出或分析聊天記錄，也不提供聊天記錄編輯。完整功能請從[夸克網盤](https://pan.quark.cn/s/cfff69d4b768)下載正式版本。

## 隱私與連網

- 聊天記錄、聯絡人、帳號資訊、解密金鑰與任何檔案都不會傳送到 WeDeep 服務。
- 正式版本會連接 WeDeep 服務讀取功能狀態、公告與最新版本號，請求不攜帶裝置識別碼、帳號資訊或聊天內容；使用統計預設不傳送，僅在同意後每天至多一次。完整說明見應用程式內的使用者協議。
- 從原始碼建置的版本預設不連接 WeDeep 服務，也不包含公告、檢查更新與使用統計。
- AI 分析只連接你自行設定的模型服務商。

## 建置

環境需求：

- JDK 21
- Android SDK 37
- 執行裝置為 Android 7.0（API 24）及以上，部分視覺效果需要 Android 13

```bash
cd ZeroUI
./gradlew :app:assembleDebug
```

產生的 APK 位於 `ZeroUI/app/build/outputs/apk/debug/`。

執行單元測試、Lint 與建置（與 CI 相同）：

```bash
cd ZeroUI
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

## 目錄結構

| 路徑 | 內容 |
| --- | --- |
| `ZeroUI/app` | WeDeep 應用程式：介面、帳號與資料通道、匯出、AI 分析 |
| `ZeroUI/app/src/stub` | 未公開部分的佔位實作 |
| `ZeroUI/wedeep-core` | 純 JVM 核心程式庫：訊息模型、媒體路徑、匯出 |
| `ZeroUI/kit` | 介面元件庫：主題、元件、導覽、動效與液態玻璃效果 |
| `ZeroUI/baselineprofile` | Baseline Profile 產生與啟動效能測試 |

## 授權

本倉庫的原始碼以 [GNU General Public License v3.0](LICENSE) 發布。第三方元件的授權見 [ZeroUI/THIRD_PARTY_NOTICES.md](ZeroUI/THIRD_PARTY_NOTICES.md)。

網盤中的正式版本包含未公開的元件，依應用程式內的使用者協議提供。

提交 Pull Request 即表示你同意作者在 GPL-3.0 之外，以其他授權在正式版本中使用你提交的程式碼。
