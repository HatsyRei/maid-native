<div align="center">

<img src="Logo.svg" width="128" height="128" alt="Maid Native Logo" />

# Maid Native

A lightweight, Material 3 themed native Android client for OpenAI-compatible
endpoints. No account, no telemetry, no ads — just a ~2.0 MB APK and your own
server.

[![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/kotlin-%237F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge)](LICENSE)
[![Latest Release](https://img.shields.io/github/v/release/HatsyRei/maid-native?style=for-the-badge&color=orange)](https://github.com/HatsyRei/maid-native/releases)

<table align="center">
<tr>
<td width="25%"><a href="docs/screenshots/chat-multimodal.png"><img src="docs/screenshots/chat-multimodal.png" width="100%" alt="Chat with an image attachment, a Markdown reply and its generation-stats footnote" /></a></td>
<td width="25%"><a href="docs/screenshots/drawer.png"><img src="docs/screenshots/drawer.png" width="100%" alt="Chat drawer with saved conversations, a New chat action and an overflow menu" /></a></td>
<td width="25%"><a href="docs/screenshots/settings-endpoint.png"><img src="docs/screenshots/settings-endpoint.png" width="100%" alt="Endpoint, model, sampling, penalty, tools, MCP and thinking settings" /></a></td>
<td width="25%"><a href="docs/screenshots/settings-theme.png"><img src="docs/screenshots/settings-theme.png" width="100%" alt="App language, accent, composer background and profile settings" /></a></td>
</tr>
<tr>
<td align="center"><sub><b>Multimodal chat</b></sub></td>
<td align="center"><sub><b>Chat drawer</b></sub></td>
<td align="center"><sub><b>Endpoint &amp; model</b></sub></td>
<td align="center"><sub><b>Personalisation</b></sub></td>
</tr>
</table>

<sub>Click any screenshot to view it full size.</sub>

</div>

---

## Features

- **Streaming chat** with Markdown rendering — incrementally parsed while the
  reply streams, so long answers stay smooth.
- **Branching conversation trees** — edit, revise or regenerate any message and
  step between siblings with `‹ n/N ›` instead of a linear log.
- **Media attachments** — images, audio and text files, gated on the model's
  reported modalities.
- **Chat import / export** — per-chat, multi-file import, and backup-all via the
  Storage Access Framework, in the RN-compatible JSON format.
- **LAN endpoint discovery** — scan your subnet (configurable port and prefix)
  to find a local llama.cpp / OpenAI-compatible server without typing an IP.
- **API keys encrypted at rest** with an AndroidKeyStore AES/GCM key that never
  leaves the device, and kept out of cloud backups.
- **Works offline** — history lives in Room, so browsing and reading need no
  network at all.
- **Collapsible reasoning** — `<think>` output is rendered separately and folds
  away, and a **Thinking** toggle asks the endpoint to turn the model's
  reasoning trace on or off (endpoints that don't support the request ignore
  it).
- **Generation stats** — a footnote under each reply with its token count,
  generation time and tokens/s, plus a per-chat **Properties** dialog
  summarising the active thread: conversation size, average speed, response
  time and time to first token, plus the thread's linked files. Counts come
  from the server's `usage` chunk and llama.cpp `timings`; edited or stopped
  replies are reported as bounds rather than guessed at.
- **Sampling and penalty controls** — temperature, top P, top K, min P, and the
  frequency and presence penalties. Every field is off by default and an off
  field is left out of the request entirely, so your server's flags and the
  sampling llama.cpp reads from the model's metadata keep deciding it.
- **Tool calling** — opt-in, on-device tools the model can call mid-reply:
  **Date & time** (the phone's local time, weekday and zone), a **Dice
  roller** (`2d6`, `1d20+5`, …) for tabletop sessions, **Fetch URL** (reads a
  web page or text file as plain text, local network included; the model
  pages through long content 8k characters at a time by default, choosing its
  own offset and length) and **JavaScript** (runs code in a sandbox for exact
  math and data work). Calls show inline as collapsible rows, and **Modify**
  can edit or remove a call's arguments and result. Each tool is **Off**,
  **Ask** or **Allow**: a call to an **Ask** tool waits in the chat with its
  arguments and **Deny** / **Allow** (plus **Allow all** when the model makes
  several at once, and **Don't ask again**); a denied call tells the model so
  and the reply carries on. Needs a model and endpoint with tool-call support.
- **Linked files** — with **Files** on in the Tools dialog, the `+` menu can link an
  existing text file or create a new one (any extension) to send with a
  message. The model can then read it by line, search it, append to it or make
  targeted edits in place for the rest of the thread — handy for document work
  or a running notes file. Uses the system file picker, so the app needs no
  storage permission and only ever sees the files you pick. Linked files show
  on their message and in **Properties**; tap one to view it, or unlink it.
- **MCP servers** — the **MCP** chip beside Tools adds remote [Model Context
  Protocol](https://modelcontextprotocol.io) servers, alongside the built-in
  tools:
  - **Streamable HTTP** with an optional bearer token (encrypted at rest like
    API keys). Point it at a server on your network, in the cloud, or on the
    phone itself (e.g. in Termux, at `http://127.0.0.1`).
  - **Tools are listed as soon as the URL is entered** and cached, so a reply
    never waits on the server just to know what it offers; **Refresh tools**
    picks up changes.
  - **Per-tool control** — each tool is **Off**, **Ask** (the default for MCP)
    or **Allow**, tagged with what the server says it does (network access,
    writes). **Trust this server** lets all of its tools run without asking.
  - **Clash-free names** — the model sees `server__tool`, so tools from
    different servers never collide with each other or the built-ins.
  - **Results** — text and structured content go back to the model; images
    and other binary content are noted rather than sent. Server errors and
    tool failures reach the model as errors it can react to.
  - Not supported: the older HTTP+SSE transport, local `stdio` servers and
    OAuth sign-in.
- **Endpoint presets and a model-picker pill** for switching servers and models
  in a couple of taps.
- **Personalisation** — AMOLED-true-black theme with a custom accent colour,
  composer nameplate art (bundled or your own image), and custom user /
  assistant display names and profile pictures.
- **English and Simplified Chinese** interface, switchable in Settings or the
  system's per-app language screen (Android 13+). The Chinese translation is
  based on [@billertwerson-gif](https://github.com/billertwerson-gif)'s work in
  [#2](https://github.com/HatsyRei/maid-native/pull/2).

---

Maid Native (`com.hatsyrei.maidnative`) is a standalone Kotlin/Compose 
reimplementation of the Maid Android app. All credit for the original design
and behaviour it mirrors goes to:

- [Mobile-Artificial-Intelligence/maid](https://github.com/Mobile-Artificial-Intelligence/maid)
  — the original React Native app.
- [HatsyRei/maid](https://github.com/HatsyRei/maid) — the React Native fork this
  port was made from, and the parity reference used throughout.

The Simplified Chinese translation is based on the catalog contributed by
[@billertwerson-gif](https://github.com/billertwerson-gif) in
[#2](https://github.com/HatsyRei/maid-native/pull/2).

See [SPEC.md](SPEC.md) for the full port specification and the record of the
migration (now closed — milestones M0–M5 complete). This repo is a self-contained
Gradle project, split out from the RN `maid` repo so the native port can evolve
independently.

## Prerequisites

- Android SDK (`ANDROID_HOME` set, or a `local.properties` with `sdk.dir=...`).
  Requires platform `android-37` (compileSdk; targetSdk is 36) and a matching
  build-tools release.
- JDK 21 (used locally at `~/.local/jdks/jdk-21`). Note that `JAVA_HOME` must
  point at a **JDK**, not a JRE — if `./gradlew` is invoked directly with a JRE
  on `PATH` the build fails; `build.sh` handles this for you.

Gradle 9.8.2 is pinned by the wrapper in
`gradle/wrapper/gradle-wrapper.properties`; the rest of the toolchain is pinned
in `gradle/libs.versions.toml`: AGP 9.4.1, Kotlin 2.4.21, KSP 2.3.12, Compose
BOM 2026.09.00. AGP 9 supplies
built-in Kotlin support, so the `org.jetbrains.kotlin.android` plugin is not
applied; the Kotlin and KSP plugin versions are declared on the root
`buildscript` classpath instead.

## Build & deploy

The `build.sh` helper does a **clean** build every time and auto-detects the
local toolchain (falls back to `~/.local/jdks/jdk-21` and `~/android-sdk` if
`JAVA_HOME` / `ANDROID_HOME` are not already exported):

```bash
./build.sh            # clean + release APK (same as `./build.sh release`)
./build.sh debug      # clean + debug APK -> app/build/outputs/apk/debug/
./build.sh release    # clean + release APK (arm64-v8a, minified; signed if keystore.properties exists)
./build.sh test       # clean + unit tests
./build.sh install    # clean + release APK + adb install to a connected device
```

Or drive Gradle directly:

```bash
./gradlew assembleDebug      # debug APK -> app/build/outputs/apk/debug/
./gradlew installDebug       # install to a connected device/emulator
./gradlew assembleRelease    # unsigned release APK (minified, arm64-v8a only)
```

If `ANDROID_HOME` is not exported, create `local.properties`:

```properties
sdk.dir=/home/<you>/android-sdk
```

### Signing

Gradle always builds the release APK unsigned (which is how F-Droid builds it);
`./build.sh release` then signs it with `apksigner`, when a git-ignored
`keystore.properties` exists at the repo root. Copy
[`keystore.properties.example`](keystore.properties.example) and point it at the
release keystore. Without it the release APK stays unsigned, and
`./build.sh install` refuses to run. No keystore or credential lives in this
repo.

Release APKs are signed with one key, so each updates the last in place. Its
certificate SHA-256 fingerprint is:

```
9B:67:9E:75:9B:F8:50:90:53:ED:F2:5B:60:3C:D7:69:1D:4C:77:87:0A:30:2E:D7:4F:26:EE:92:2D:4A:E6:BC
```

Check a download with `apksigner verify --print-certs app-release.apk`, or with
AppVerifier on the device.

## Status

**Port complete.** Behavioural parity with the React Native app has been reached
and signed off on-device (SPEC §7): streaming chat against an OpenAI-compatible
endpoint, conversation-tree logic (with unit tests), Room persistence, settings,
a chat UI with message controls + branch navigation, a navigation drawer, and
Markdown rendering (incremental while streaming). Signed release APK is ~2.0 MB,
against ~20 MB for the React Native build. Remaining items are post-parity
enhancements, listed in [SPEC.md](SPEC.md) §7.1.

## License

[MIT](LICENSE) © 2026 HatsyRei.
