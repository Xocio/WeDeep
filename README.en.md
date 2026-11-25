<p align="center">
  <img src=".github/logo.png" width="128" alt="WeDeep" />
</p>

<h1 align="center">WeDeep</h1>

<p align="center">
  View, search, export and edit your own WeChat chat history copy, on your device
</p>

<p align="center">
  <a href="README.md">简体中文</a> | <a href="README.zh-Hant.md">繁體中文</a> | <b>English</b>
</p>

<p align="center">
  Built on <a href="https://github.com/Xocio/ZeroUI">ZeroUI</a>
</p>

<p align="center">
  <a href="https://github.com/Xocio/WeDeep/releases/latest"><img src="https://img.shields.io/github/v/release/Xocio/WeDeep?label=release" alt="Release" /></a>
  <a href="https://github.com/Xocio/WeDeep/releases"><img src="https://img.shields.io/github/downloads/Xocio/WeDeep/total?label=downloads" alt="Downloads" /></a>
  <a href="https://github.com/Xocio/WeDeep/stargazers"><img src="https://img.shields.io/github/stars/Xocio/WeDeep?style=flat" alt="Stars" /></a>
  <a href="https://github.com/Xocio/WeDeep/commits"><img src="https://img.shields.io/github/last-commit/Xocio/WeDeep" alt="Last commit" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-blue" alt="License" /></a>
  <img src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white" alt="Android" />
  <img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/JDK-21-ED8B00?logo=openjdk&logoColor=white" alt="JDK 21" />
  <img src="https://img.shields.io/badge/data-processed%20on%20device%20only-2EA44F" alt="Local only" />
</p>

WeDeep is an Android app for viewing, searching, exporting and (optionally) editing a copy of your own WeChat chat history on your own device. Chats are processed locally and never uploaded to any WeDeep service.

> WeDeep is not an official WeChat product and has no affiliation, partnership or authorization from Tencent or WeChat. Reading and decrypting WeChat databases may violate WeChat's terms of service. Only handle data that you own or are authorized to access, and use it at your own risk.

## Features

- **Accounts and decryption**: read WeChat data directly on a rooted device, or import an account directory zip or an `EnMicroMsg.db` file. Decryption runs offline on the device.
- **Chat browsing**: conversation list, chat details, images, voice, video and files, with search over text messages and card titles.
- **Export**: export one or all conversations to JSON and HTML, optionally with media files, or export only new messages.
- **Chat editing**: add, delete and modify messages in the in-app copy, and generate a re-encrypted database or screenshots, without affecting WeChat itself.
- **Write back to WeChat**: on a rooted device, write the edited result into the WeChat database. The original database is backed up first.
- **AI analysis**: send selected chat content to a model provider that you configure yourself. Content does not pass through any WeDeep service.

## What is public

This repository publishes all source code except these two parts:

- Database decryption
- Chat editing and write-back to WeChat

They are kept private to prevent misuse: publishing the decryption and write-back implementations would lower the bar for stealing, forging or tampering with other people's chat records.

Placeholder implementations stand in for them in this repository (`ZeroUI/app/src/stub`). A build from this repository compiles and runs, but cannot decrypt accounts, so it cannot browse, export or analyze chats, and does not offer chat editing. For the full feature set, use the official build from [Releases](https://github.com/Xocio/WeDeep/releases).

## Privacy and networking

- Chats, contacts, account information, decryption keys and files are never sent to any WeDeep service.
- Official builds connect to the WeDeep service to read feature status, announcements and the latest version number. Requests carry no device identifier, account information or chat content. Usage statistics are off by default and, after you consent, are sent at most once a day. See the in-app user agreement for details.
- Builds from source do not connect to the WeDeep service by default, and include no announcements, update checks or usage statistics.
- AI analysis only connects to the model provider you configure.

## Build

Requirements:

- JDK 21
- Android SDK 37
- A device running Android 7.0 (API 24) or later; some visual effects need Android 13

```bash
cd ZeroUI
./gradlew :app:assembleDebug
```

The APK is written to `ZeroUI/app/build/outputs/apk/debug/`.

Run unit tests, lint and builds (same as CI):

```bash
cd ZeroUI
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

## Layout

| Path | Contents |
| --- | --- |
| `ZeroUI/app` | The WeDeep app: UI, account and data channels, export, AI analysis |
| `ZeroUI/app/src/stub` | Placeholder implementations of the non-public parts |
| `ZeroUI/wedeep-core` | Pure JVM core library: message model, media paths, export |
| `ZeroUI/kit` | UI component library: theme, components, navigation, motion and Liquid Glass effects |
| `ZeroUI/baselineprofile` | Baseline Profile generation and startup benchmarks |

## License

The source code in this repository is released under the [GNU General Public License v3.0](LICENSE). Licenses of third-party components are listed in [ZeroUI/THIRD_PARTY_NOTICES.md](ZeroUI/THIRD_PARTY_NOTICES.md).

Official builds in Releases include non-public components and are provided under the in-app user agreement.

By submitting a Pull Request, you agree that the author may use your submitted code in official builds under licenses other than GPL-3.0.
