# Maid Native — Android Port Specification

Status: **In progress — working vertical slice on-device (M0–M3 largely done; M1 persistence and some M4/M5 pending)**
Owner: hatsyrei
Target: native Android (Kotlin + Jetpack Compose), Android-only, side-by-side with the existing React Native `maid` app during migration.

> **Progress at a glance (2026-07-23):** Buildable/installable Compose app streaming real chat against an OpenAI-compatible endpoint on a physical device. Conversation-tree logic + reasoning ported with passing unit tests; DataStore settings; OkHttp SSE streaming + model listing; full chat UI with message controls (regenerate/revise/modify/copy/delete), branch navigation, a navigation drawer (select/rename/delete conversations), and Markdown rendering. Signed release APK ≈ **1.3 MB**. Remaining big rocks: Room persistence (interim JSON store today), endpoint scan, custom headers/params, export/import, model-selector pill, icon/splash, and swapping the interim Markdown renderer for a library.

---

## 1. Motivation

1. **Decouple from upstream.** The RN app is coupled to Expo/React Native release cadence. Every SDK bump (e.g. the Expo 56 migration) drags in regressions we don't own: edge-to-edge enforcement breaking popover positioning, `expo-system-ui` reappearing, Gradle-10 deprecations from `node_modules`, keyboard-controller not emitting `height=0` on API≥30 resume. Native lets us own the entire stack and adopt platform features on our schedule.
2. **Kill RN-specific tech debt.** Several documented, currently-unfixable bugs are RN-induced and simply do not exist natively:
   - Keyboard-inset-stuck + drawer-unpainted after returning from a file picker (upstream `react-native-keyboard-controller` + `react-native-drawer-layout`).
   - Swipe-gesture-vs-native-ripple conflict (RNGH pan hitSlop suppressing `android_ripple`).
   - `<Markdown>` re-parsing the whole growing message every ~80 ms during streaming (O(n²) per response).
3. **Size & battery.** Drop Hermes, the RN runtime, Reanimated/Worklets `.so`s, and the JS bundle. Target a **~5–8 MB APK** (from ~20 MB) and remove per-token JS↔native bridge crossings during streaming.
4. **iOS is explicitly out of scope.** The shipping pipeline is already Android-arm64-only.

## 2. Non-goals

- No iOS / web targets.
- No local on-device model inference (already removed from the RN app).
- No feature expansion during the port — reach **behavioral parity first**, then iterate.

## 3. Target stack

| Concern | RN today | Native target |
|---|---|---|
| Language / UI | TypeScript + React Native + Expo | Kotlin + Jetpack Compose |
| Design system | Material 3 (hand-rolled tokens) | Compose Material 3 (`androidx.compose.material3`) |
| Navigation | expo-router (file-based) | `navigation-compose` (or a small sealed-class nav) |
| Persistence (messages) | `expo-sqlite` + hand-written incremental diff | Room (KSP) — **interim in prototype: JSON snapshot file** (`data/store/MessageStore.kt`), to be replaced |
| Preferences | `@react-native-async-storage` + `use-stored-*` hooks | Jetpack DataStore (Preferences) — **done** |
| HTTP / streaming | `openai` SDK over `expo/fetch` | OkHttp (SSE) — **done** |
| Markdown | `@novastera-oss/react-native-markdown-display` | **Decided: `com.mikepenz:multiplatform-markdown-renderer-m3`** (pure Compose, Material 3, actively maintained). Prototype currently ships an interim hand-rolled Compose renderer (`ui/markdown/Markdown.kt`) to be replaced. Note: pass `retainState = true` for streaming to avoid a loading flash. |
| Images (markdown) | expo-image / Glide | Coil |
| Clipboard | expo-clipboard | `ClipboardManager` / Compose `ClipboardManager` |
| File pick / export | expo-document-picker | Storage Access Framework (`ActivityResultContracts`) |
| Keyboard insets | react-native-keyboard-controller | Compose `imePadding()` / `WindowInsets` |
| Gestures / drawer | RNGH + react-native-drawer-layout | `ModalNavigationDrawer` + Compose gestures |
| Async | Promises | Coroutines + Flow |
| DI (optional) | — | Hilt (or manual — keep minimal early) |

### Toolchain (prototype, verified locally)
- JDK 21 (`~/.local/jdks/jdk-21`), compiling to JVM 17 bytecode (no separate toolchain provisioning).
- Android SDK: compileSdk/targetSdk **36**, minSdk **24** (matches the RN app's minSdk).
- Gradle **9.3.1** (wrapper reused from the RN project), AGP **8.13.0**, Kotlin **2.1.0**, Compose BOM.
- `applicationId = com.hatsyrei.maidnative` (distinct from `com.hatsyrei.maid`) → installs **side-by-side**.

## 4. Feature inventory (parity checklist)

Derived from the current RN app. Each item is a parity target for the native app.

### 4.1 Endpoint & model
- [x] OpenAI-compatible base URL (default `https://api.openai.com/v1`), editable, with a reset-to-default button.
- [x] API key (required only for the official OpenAI endpoint; `local-openai-compatible` placeholder allowed otherwise).
- [ ] Custom default headers (key/value map).
- [ ] Custom request parameters (arbitrary map merged into the completion body; UUID-keyed rows in the editor). *(Client already accepts a parameters map; no editor UI yet.)*
- [x] Model list via `GET /models`, refreshed on endpoint change; auto-select first / preserve valid stored selection. *(Refresh-on-focus not wired; refresh is manual + on endpoint change.)*
- [ ] Endpoint auto-discovery: subnet scan for OpenAI-compatible hosts.

### 4.2 Chat / streaming
- [x] Streaming chat completions (SSE), incremental token append.
- [x] Stop / abort mid-stream.
- [ ] Retry (maxRetries=3) parity.
- [x] Drop trailing empty assistant placeholder before sending (prevents llama.cpp assistant-prefix corruption).
- [x] "Reasoning" content: parsed (`domain/Reasoning.kt`) and rendered in a collapsible section (default collapsed, chevron header).
- [~] Streaming render throttle: using `Flow.buffer()`; conflation/sampling not yet tuned. Fixed a separate bug where org.json returned literal `"null"` for the opening `content:null` delta.

### 4.3 Conversation tree (the high-risk port)
- [x] Port `message-nodes` (`domain/tree/`): nodes with `id/role/content/root/parent/child/metadata`, branching, `getConversation`, `hasNode`, sibling navigation, delete/subtree, makeRoot, etc.
- [x] Edit-and-resend (revise), edit-in-place (modify), regenerate (branch + stream), delete (node/subtree), copy.
- [x] Branch navigation controls (prev/counter/next), hidden when a single sibling.
- [x] **Test-first**: Kotlin test suites (`MessageTreeTest`, `ReasoningTest`) mirror the JS behavior; green.

### 4.4 Persistence
- [ ] Room schema mirroring the `messages` table (`id, role, content, root, parent, child, metadata`), WAL. *(Interim: whole-map JSON snapshot in `filesDir/messages.json`, atomic temp+rename.)*
- [ ] Incremental diff writes (only changed rows upsert, vanished ids delete).
- [~] Structural-vs-content save distinction: persists on structural change and at stream end; per-token content is **not** persisted (churn suppressed) — but that means a force-close mid-stream loses the partial reply.
- [x] Hydrate on load (root restored from stored mappings). *(Mid-stream crash resilience pending — see above.)*

### 4.5 UI / UX (Material 3 parity)
- [x] Hardcoded dark theme seeded from `#2196F3` (Compose M3 color scheme). *(Dynamic color optional/later.)*
- [x] Composer pill (rounded, borderless multiline input, filled send/stop button, enabled/disabled transition).
- [~] Conversation list on a single tonal container; role labels; Markdown body + code/blockquote styling. *(Markdown done via interim renderer; markdown **image** rule pending — needs Coil.)*
- [x] Long-press message menu (revise/modify/copy/delete; regenerate for assistant). *(Anchored to the touch point; trailing M3 icons; delete behind a confirm dialog.)*
- [x] Model selector pill + dropdown menu in the top bar (RN parity).
- [~] Navigation drawer: conversation list, rename, delete (with confirm dialog), constrained width (right sliver), keyboard dismissed on open. *(Export and import/backup-all pending.)*
- [ ] Custom scroll thumb (or drop it — decide during UI pass).
- [x] Edge-to-edge with correct status/nav bar insets. *(Fixed keyboard double-inset via `windowSoftInputMode=adjustResize`. Auto-scroll removed 2026-07-27; instead a bottom spacer (`viewport − 96dp`, a trailing `Spacer` item under `BoxWithConstraints`) lets the user scroll the last message up near the top and scroll ahead to watch streaming text — mirrors RN commit `dd8fb76`.)*
- [ ] App icon + Android 12 splash (reuse existing `assets/images/*`). *(Placeholder adaptive icon only.)*

## 5. Architecture (native)

```
com.hatsyrei.maidnative
├── data
│   ├── db          (Room: MessageEntity, MessageDao, MaidDatabase)
│   ├── prefs       (DataStore: endpoint, apiKey, model, headers, parameters)
│   └── remote      (OpenAI client: models list, streaming completions, endpoint scan)
├── domain
│   ├── tree        (MessageNode + branching ops, ported from message-nodes)
│   └── model       (domain types)
├── ui
│   ├── theme       (Color, Theme, Type — M3)
│   ├── chat        (ChatScreen, ChatViewModel, composer, message list, menus)
│   ├── settings    (SettingsScreen, fields)
│   └── drawer      (conversation list, rename/export/import)
└── MainActivity    (edge-to-edge, NavHost)
```

State: `ViewModel` + `StateFlow`; streaming via `Flow<String>` collected in the VM. No global singletons beyond DB/DataStore/HTTP client (manual DI or Hilt).

## 6. Migration / coexistence strategy

- The native app lives in its **own repository** (`maid-native-experimental`) as a standalone Gradle project with its own wrapper, independent of the RN `maid` repo. (It was originally prototyped inside `maid/native/` and split out once the vertical slice was working.)
- Distinct `applicationId` (`com.hatsyrei.maidnative`) → both APKs install and run side-by-side on one device for A/B comparison during the port.
- **Data note:** the two apps have separate sandboxes; the native app does **not** read the RN app's SQLite DB. If we want to carry conversations over at cutover, add a one-time import (read the RN DB via an exported backup file through SAF). Out of scope for the prototype.
- Cutover: once parity + on-device sign-off is reached, promote the native app to `com.hatsyrei.maid` (or keep the new id and treat as a fresh install).

## 7. Milestones

1. **M0 — Prototype:** ✅ **Done.** Buildable/installable Compose skeleton, dark M3, side-by-side id, signing.
2. **M1 — Tree core:** 🟡 **Partial.** `message-nodes` Kotlin port + test parity ✅. Room schema + incremental persistence ❌ (interim JSON snapshot store in place).
3. **M2 — Streaming:** ✅ **Done.** OpenAI client (models + SSE completions + abort) ✅, settings (DataStore) ✅, model selection ✅. *(Retry parity + endpoint scan not yet.)*
4. **M3 — Chat UI:** 🟡 **Mostly done.** Message list, Markdown (interim renderer), reasoning (inline), composer, long-press menu, branch navigation ✅. Collapsible reasoning, model-selector pill, markdown images pending.
5. **M4 — Drawer & data ops:** 🟡 **Partial.** Drawer conversation list + rename + delete ✅. Export/import/backup, endpoint scan, custom headers/params ❌.
6. **M5 — Polish & parity sign-off:** 🟡 **Started.** Edge-to-edge + keyboard-inset + scroll-hijack fixes ✅. Real icon/splash, dynamic theming pass, on-device A/B vs RN, size/battery verification ❌.

### 7.1 Immediate next steps (next session)
1. Swap the interim Markdown renderer for **`com.mikepenz:multiplatform-markdown-renderer-m3`** (use `retainState = true` for streaming); delete `ui/markdown/Markdown.kt` once parity confirmed.
2. Room persistence (schema mirror + incremental diff), replacing `data/store/MessageStore.kt`; persist partial replies so a mid-stream crash survives.
3. Endpoint scan, custom headers/params editors, export/import, model-selector pill, collapsible reasoning, real icon/splash.
4. Work through the on-device parity backlog in §10 (drawer width, keyboard-on-drawer, menu styling/anchoring, delete-confirm, composer typography).

## 8. Risks

- **Tree logic regressions** — mitigate with test-first port (M1).
- **Markdown fidelity** — Markwon vs Compose renderer differ from the RN lib; budget a styling pass.
- **SSE edge cases** — reconnect/retry/`[DONE]`/partial-chunk framing re-owned; cover with tests against a mock server.
- **Version drift** — AGP/Gradle/Compose pinned in `gradle/libs.versions.toml`; upgrades are deliberate.

## 9. Prototype build/deploy

```bash
./build.sh                       # clean debug build (auto-detects toolchain)
./build.sh install               # clean debug build + adb install
# or drive Gradle directly:
./gradlew assembleDebug          # or installDebug with a device attached
# APK: app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```
Requires `ANDROID_HOME` (or a `local.properties` with `sdk.dir`) and JDK 17+ (JDK 21 used locally). See `README.md`.

## 10. Known issues & parity backlog (observed on-device, 2026-07-23)

Concrete bugs and visual-parity gaps noted while exercising the prototype. To be triaged next session.

### Composer
- [x] **Keyboard lift overshoot (FIXED 2026-07-23):** the composer was raised by ~one extra nav-bar height because the content `Column` applied both the Scaffold's bottom inset (`.padding(padding)`, which includes the navigation bar) and `.imePadding()` (whose IME inset also spans the nav-bar region under `adjustResize`) — double-counting the nav bar. Fixed by inserting `.consumeWindowInsets(padding)` between them so `imePadding()` only adds the height beyond the already-consumed nav-bar inset. Verified on-device.
- [x] **Message typography parity (FIXED 2026-07-27):** the role label and message body sizes were swapped. Corrected to the RN scale (`utilities/typography.ts`, standard M3): role label = `titleMedium` in `primary`; message/markdown body = `bodyMedium`; reasoning = `bodyMedium` italic in `onSurfaceVariant`; reasoning toggle = `labelLarge` in `primary`.
- **Font parity:** composer *input* font family/size still differs from the RN app. Match the RN typography (family + size + weight).
- [x] **Model selector pill (FIXED 2026-07-27):** added a centered model-selector pill + dropdown in the top bar (hamburger | pill | settings), mirroring the RN layout. Model selection still also available in Settings.

### Navigation drawer
- [x] **Drawer width (FIXED 2026-07-27):** `ModalDrawerSheet` constrained to 85% width so a right-hand sliver of the chat shows behind it (RN parity).
- [x] **New-chat creates an entry (FIXED 2026-07-27):** the New-chat (+) action now creates the `system` root node up front (`ChatViewModel.newChat`, mirroring `drawer-content.tsx` `createChat`) and makes it the active chat, so the conversation shows in the drawer immediately; the first `submit` attaches to that existing root.
- [x] **Keyboard dismiss on open (FIXED 2026-07-27):** opening the drawer now clears focus and hides the IME so it no longer overlays the conversation list.
- [x] **Back button parity (FIXED 2026-07-27):** a `BackHandler` closes the open drawer (and Settings returns to chat) instead of the system Back leaving the app.
- [x] **Drawer stays open on interaction (FIXED 2026-07-27):** selecting a chat or tapping New-chat no longer auto-closes the drawer; it switches the active chat behind the open drawer and persists until the user swipes it away.

### Menus
- [x] **Long-press menu position (FIXED 2026-07-27):** message and drawer long-press menus now pop up centered on the touch point via a raw `Popup` + custom `PopupPositionProvider` (`TapContextMenu`), mirroring the RN app's zero-size anchor at `pageX/pageY`. (The earlier `DropdownMenu(offset=…)` approach drifted to screen edges for wide anchors.)
- [x] **Chat entry menu trigger (FIXED 2026-07-27):** drawer chat entries now open their menu via long-press (custom `DrawerChatItem` with a selected-state pill), matching RN; the three-dot icon was removed.
- [x] **Menu styling (FIXED 2026-07-27):** pop-up menus now use rounded corners and trailing M3 icons (Regenerate/Refresh, Modify/Edit, Revise/Send, Copy, Delete/Delete-red; Rename/Edit in the drawer), with a divider before Delete. Width tightened (`widthIn` ~168–172dp) + column padding so the trailing icon sits near the label and content clears the rounded edges; the model-picker dropdown got extra horizontal item padding too.
- [x] **Delete confirmation (FIXED 2026-07-27):** both message-delete and conversation-delete now go through a confirm `AlertDialog`.

### Settings
- [x] **Reset-to-default endpoint (FIXED 2026-07-27):** added a "Reset to default" chip next to the Base URL save action.
- **Missing endpoint search:** Settings lacks the "search/scan for local server" button next to the Base URL field (RN parity). Wire to the endpoint scan (also tracked in §4.1).
