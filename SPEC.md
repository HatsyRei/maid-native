# Maid Native — Android Port Specification

Status: **In progress — working vertical slice on-device (M0–M3 largely done; M1 persistence and some M4/M5 pending)**
Owner: hatsyrei
Target: native Android (Kotlin + Jetpack Compose), Android-only, side-by-side with the existing React Native `maid` app during migration.

> **Progress at a glance (2026-07-29):** Buildable/installable Compose app streaming real chat against an OpenAI-compatible endpoint on a physical device. Conversation-tree logic + reasoning ported with passing unit tests; Room persistence with incremental diff writes; DataStore settings; OkHttp SSE streaming + model listing; full chat UI with message controls (regenerate/revise/modify/copy/delete), branch navigation, a navigation drawer (select/rename/delete conversations), Markdown rendering (library-based, incl. user messages, with an incremental streaming path), a model-selector pill, endpoint subnet scan, chat export/import (RN-compatible), a draggable scroll thumb, and a real launcher icon (Maid Ai monogram). Signed release APK ≈ **1.7 MB** (was ≈1.3 MB before the 2026-07-29 Compose/renderer bump — see §11.2). Remaining big rocks: on-device parity sign-off against the RN app, collapsible-reasoning/markdown-image polish, and the parity backlog in §10.

---

## 1. Motivation

1. **Decouple from upstream.** The RN app is coupled to Expo/React Native release cadence. Every SDK bump (e.g. the Expo 56 migration) drags in regressions we don't own: edge-to-edge enforcement breaking popover positioning, `expo-system-ui` reappearing, Gradle-10 deprecations from `node_modules`, keyboard-controller not emitting `height=0` on API≥30 resume. Native lets us own the entire stack and adopt platform features on our schedule.
2. **Kill RN-specific tech debt.** Several documented, currently-unfixable bugs are RN-induced and simply do not exist natively:
   - Keyboard-inset-stuck + drawer-unpainted after returning from a file picker (upstream `react-native-keyboard-controller` + `react-native-drawer-layout`).
   - Swipe-gesture-vs-native-ripple conflict (RNGH pan hitSlop suppressing `android_ripple`).
   - ~~`<Markdown>` re-parsing the whole growing message every ~80 ms during streaming (O(n²) per response).~~ **Retracted 2026-07-29.** This one is *not* RN-induced and did **not** disappear in the native port — the Compose implementation reproduced it, and worse (once per token rather than throttled to ~80 ms). Measured and documented in §11.1. **Genuinely fixed 2026-07-29 (§11.2)**, not by the port itself but by adopting the renderer's incremental `StreamingMarkdownState`.
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
| Persistence (messages) | `expo-sqlite` + hand-written incremental diff | Room (KSP) — **done** (`data/db/`: entity/DAO/database + `MessageRepository` incremental diff, WAL); legacy JSON snapshot migrated in on first launch |
| Preferences | `@react-native-async-storage` + `use-stored-*` hooks | Jetpack DataStore (Preferences) — **done** |
| HTTP / streaming | `openai` SDK over `expo/fetch` | OkHttp (SSE) — **done** |
| Markdown | `@novastera-oss/react-native-markdown-display` | **Done: `com.mikepenz:multiplatform-markdown-renderer-m3` v0.43.0** (pure Compose, Material 3). `ui/markdown/Markdown.kt` wraps it as `MarkdownText` (settled messages, parsed once via the synchronous `parseMarkdown()` and cached) and `StreamingMarkdownText` (in-flight replies, incrementally parsed via `StreamingMarkdownState`), body pinned to `bodyMedium`. See §11.2. |
| Images (markdown) | expo-image / Glide | Coil |
| Clipboard | expo-clipboard | `ClipboardManager` / Compose `ClipboardManager` |
| File pick / export | expo-document-picker | Storage Access Framework (`ActivityResultContracts`) |
| Keyboard insets | react-native-keyboard-controller | Compose `imePadding()` / `WindowInsets` |
| Gestures / drawer | RNGH + react-native-drawer-layout | `ModalNavigationDrawer` + Compose gestures |
| Async | Promises | Coroutines + Flow |
| DI (optional) | — | Hilt (or manual — keep minimal early) |

### Toolchain (prototype, verified locally)
- JDK 21 (`~/.local/jdks/jdk-21`), compiling to JVM 17 bytecode (no separate toolchain provisioning).
- Android SDK: compileSdk **37**, targetSdk **36**, minSdk **24** (matches the RN app's minSdk). compileSdk is the floor imposed by markdown-renderer `0.43.0`; targetSdk is held at 36 deliberately, since compiling against newer APIs is independent of opting in to new runtime behaviour.
- Gradle **9.5.0**, AGP **9.3.1**, Kotlin **2.4.10**, KSP **2.3.10**, Compose BOM **2026.06.01**.
- AGP 9 supplies **built-in Kotlin**, so `org.jetbrains.kotlin.android` is no longer applied (it is incompatible with AGP 9's new DSL). AGP pins KGP/KSP to its own baseline, so our higher versions are declared on the root `buildscript` classpath (`kotlin-gradle-plugin`, `symbol-processing-gradle-plugin`).
- `androidx.compose.material:material-icons-core` is now an explicit dependency; recent `material3` no longer brings it in transitively.
- `applicationId = com.hatsyrei.maidnative` (distinct from `com.hatsyrei.maid`) → installs **side-by-side**.

## 4. Feature inventory (parity checklist)

Derived from the current RN app. Each item is a parity target for the native app.

### 4.1 Endpoint & model
- [x] OpenAI-compatible base URL (default `https://api.openai.com/v1`), editable, with a reset-to-default button.
- [x] API key (required only for the official OpenAI endpoint; `local-openai-compatible` placeholder allowed otherwise).
- [–] ~~Custom default headers (key/value map).~~ **Dropped 2026-07-29** — unused in practice. The RN app exposes an editor for it; neither of us has ever populated it. Not worth the settings-screen surface area.
- [–] ~~Custom request parameters (arbitrary map merged into the completion body; UUID-keyed rows in the editor).~~ **Dropped 2026-07-29** — same reason. Note that `OpenAiClient` still *accepts* a parameters map, so only the editor UI is cancelled; wiring a caller back up later is cheap if a real need appears.
- [x] Model list via `GET /models`, refreshed on endpoint change; auto-select first / preserve valid stored selection. *(Refresh-on-focus not wired; refresh is manual + on endpoint change.)*
- [x] Endpoint auto-discovery: subnet scan for OpenAI-compatible hosts. *(`data/remote/EndpointScanner.kt`, port of RN `scan-endpoint.ts`: probes `http://<ip>:8080/v1/models` across the local /24 then extended /21, 400ms timeout, 64-way concurrency. Scan button next to Base URL field validates the current URL first, then scans; shows spinner while scanning and a check on success.)*

### 4.2 Chat / streaming
- [x] Streaming chat completions (SSE), incremental token append.
- [x] Stop / abort mid-stream.
- [–] ~~Retry (maxRetries=3) parity.~~ **Dropped 2026-07-29** — the RN app inherits `maxRetries: 3` from the `openai` SDK default rather than choosing it. Against a local endpoint a failure is almost always "server is down", where silent retries just delay the error and burn radio; against the official API, a hard failure surfacing immediately is the more honest behaviour. Manual resend is one tap.
- [x] Drop trailing empty assistant placeholder before sending (prevents llama.cpp assistant-prefix corruption).
- [x] "Reasoning" content: parsed (`domain/Reasoning.kt`) and rendered in a collapsible section (default collapsed, chevron header).
- [~] Streaming render throttle: using `Flow.buffer()`; conflation/sampling not yet tuned. Fixed a separate bug where org.json returned literal `"null"` for the opening `content:null` delta. **Update 2026-07-29:** a render-path throttle is no longer the priority lever it was. The quadratic markdown re-parse it would have masked is gone — the streaming bubble now parses incrementally (§11.2), and `snapshotFlow` conflation there already collapses token bursts that outrun the parser.

### 4.3 Conversation tree (the high-risk port)
- [x] Port `message-nodes` (`domain/tree/`): nodes with `id/role/content/root/parent/child/metadata`, branching, `getConversation`, `hasNode`, sibling navigation, delete/subtree, makeRoot, etc.
- [x] Edit-and-resend (revise), edit-in-place (modify), regenerate (branch + stream), delete (node/subtree), copy.
- [x] Branch navigation controls (prev/counter/next), hidden when a single sibling.
- [x] **Test-first**: Kotlin test suites (`MessageTreeTest`, `ReasoningTest`) mirror the JS behavior; green.

### 4.4 Persistence
- [x] Room schema mirroring the `messages` table (`id, role, content, root, parent, child, metadata`), WAL. *(`data/db/`: `MessageEntity`/`MessageDao`/`MaidDatabase`; WAL journal mode enabled. One-time migration seeds Room from the legacy `filesDir/messages.json` snapshot, then retires the file.)*
- [x] Incremental diff writes (only changed rows upsert, vanished ids delete). *(`MessageRepository` diffs each save against a last-persisted snapshot: `@Upsert` only changed/new nodes, delete only vanished ids, and skip the DB entirely when nothing changed. Writes are coalesced through a CONFLATED channel with a single consumer so bursts collapse to one write and diff state is never touched concurrently.)*
- [~] Structural-vs-content save distinction: persists on structural change and at stream end; per-token content is **not** persisted (churn suppressed) — but that means a force-close mid-stream loses the partial reply.
- [x] Hydrate on load (root restored from stored mappings). *(Mid-stream crash resilience pending — see above.)*

### 4.5 UI / UX (Material 3 parity)
- [x] Hardcoded dark theme seeded from `#2196F3` (Compose M3 color scheme). *(Dynamic color optional/later.)*
- [x] Composer pill (rounded, borderless multiline input, filled send/stop button, enabled/disabled transition).
- [~] Conversation list on a single tonal container; role labels; Markdown body + code/blockquote styling. *(Markdown via `multiplatform-markdown-renderer-m3`; both user and assistant messages render as Markdown; markdown **image** rule pending — needs Coil.)*
- [x] Long-press message menu (revise/modify/copy/delete; regenerate for assistant). *(Anchored to the touch point; trailing M3 icons; delete behind a confirm dialog.)*
- [x] Model selector pill + dropdown menu in the top bar (RN parity). *(Pill hidden when no models available; dropdown menu centered on the pill.)*
- [~] Navigation drawer: conversation list, rename, delete (with confirm dialog), constrained width (right sliver), keyboard dismissed on open. *(Export (per-chat), import (multi-file), and backup-all done via SAF; RN-compatible JSON format.)*
- [x] Custom scroll thumb. *(`ui/chat/DraggableScrollbar.kt`: draggable scroll thumb for the conversation view.)*
- [x] Edge-to-edge with correct status/nav bar insets. *(Fixed keyboard double-inset via `windowSoftInputMode=adjustResize`. Auto-scroll removed 2026-07-27; instead a bottom spacer (`viewport − 96dp`, a trailing `Spacer` item under `BoxWithConstraints`) lets the user scroll the last message up near the top and scroll ahead to watch streaming text — mirrors RN commit `dd8fb76`.)*
- [x] App icon + Android 12 splash. *(Real adaptive launcher icon — Maid Ai monogram foreground with padding. The splash is the **platform-generated** one: API 31+ builds it automatically from the adaptive icon plus the theme's `windowBackground`, which `Theme.MaidNative` sets to `@color/ic_launcher_background` so the two match and the transition reads as deliberate. No `androidx.core:core-splashscreen` and no `windowSplashScreenAnimatedIcon`/`postSplashScreenTheme` — API 24–30 therefore gets a plain coloured window rather than an icon splash, which is accepted.)*

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
2. **M1 — Tree core:** ✅ **Done.** `message-nodes` Kotlin port + test parity ✅. Room schema + incremental diff persistence ✅ (`data/db/`, WAL, one-time migration off the legacy JSON snapshot). Remaining gap: partial replies are not persisted mid-stream, so a force-close during generation loses the in-flight reply (§4.4).
3. **M2 — Streaming:** ✅ **Done.** OpenAI client (models + SSE completions + abort) ✅, settings (DataStore) ✅, model selection ✅, endpoint scan ✅. Retry parity dropped (§4.2).
4. **M3 — Chat UI:** 🟡 **Mostly done.** Message list, Markdown (`multiplatform-markdown-renderer-m3`, user + assistant, incremental while streaming), reasoning (collapsible), composer, long-press menu, branch navigation, model-selector pill, draggable scroll thumb ✅. Markdown images pending (needs Coil).
5. **M4 — Drawer & data ops:** ✅ **Done.** Drawer conversation list + rename + delete ✅. Export / import / backup-all ✅ (SAF, RN-compatible format + `validateMappings` port). Endpoint scan ✅. Custom headers/params editors dropped (§4.1).
6. **M5 — Polish & parity sign-off:** 🟡 **In progress.** Edge-to-edge + keyboard-inset + scroll-hijack fixes, real launcher icon, Android 12 splash ✅. Size verification ✅ (1.7 MB signed arm64 vs ~20 MB RN — §11.2). Battery audit ✅ (§11/§11.1/§11.2: retry loop, per-token map copy, and the quadratic markdown re-parse all fixed; remaining items assessed and accepted). Outstanding: dynamic theming pass, composer font parity, and **on-device A/B against the RN app**, which is the real gate for this milestone.

### 7.1 Immediate next steps (next session)
1. Persist partial replies so a mid-stream force-close does not lose the in-flight response (§4.4).
2. Markdown images (Coil), composer font parity, dynamic theming pass.
3. On-device A/B against the RN app to close M5.

*(Done: Markdown renderer swap, Room persistence, endpoint scan, export/import, model-selector pill, draggable scroll thumb, real launcher icon, collapsible reasoning, Android 12 splash, on-device verification of the §11.2 rework, scroll-position-after-Settings fix. Dropped: custom headers/params editors, retry parity.)*

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

## 10. Known issues & parity backlog (opened 2026-07-23, maintained since)

Concrete bugs and visual-parity gaps noted while exercising the prototype on-device. Resolved items are kept with their fix notes rather than deleted, so the reasoning stays discoverable.

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
- **Endpoint search:** DONE — search/scan button next to the Base URL field validates the current URL, then scans the local subnet (§4.1).

### Chat list
- [x] **Scroll position resets after visiting Settings — FIXED 2026-07-29 (verified on-device).** Scroll down into a long conversation, open Settings, come back — the list jumped back to the top (index 0). **Two independent causes, both had to be fixed:**
  1. `MainActivity`'s `AnimatedContent` swaps on a `Screen` enum and **disposes** `ChatScreen` once the transition ends. `rememberLazyListState` is `rememberSaveable`-backed, but `AnimatedContent` does **not** wrap its content in a `SaveableStateHolder` (verified against `animation-android 1.11.4` bytecode — zero saveable references in `AnimatedContentKt`), so the saved position had nowhere to live. Fixed by adding a `rememberSaveableStateHolder()` in `MaidNativeApp` and wrapping each branch in `SaveableStateProvider(target.name)`. Keyed by `name` rather than the enum itself because the holder's map is written into the activity `Bundle`, whose keys must be Bundle-storable types.
  2. `ChatScreen`'s `LaunchedEffect(state.root)` — which clears the markdown cache and scrolls to item 0 on conversation switch — re-fired on every recomposition of the recreated screen, so it would have discarded the restored position anyway. Now guarded by a `rememberSaveable` `settledRoot` that records the root the effect last acted on, so it fires only on a genuine chat switch. This also stops the markdown parse cache being needlessly dropped every time the user glances at Settings.

  Not addressed: expanded-reasoning toggles still collapse, because `MessageItem` uses `remember(node.id)` rather than `rememberSaveable`. That state is already lost on scroll-out (`LazyColumn` only preserves *saveable* item state), so it is a separate pre-existing nitpick.

### Deferred enhancements (post-parity, not RN parity items)
- **Customizable user / assistant display names — wanted, low priority.** Role labels are currently hardcoded to the node's `role`. Intent is user-settable names (per-app, possibly per-conversation later) rendered in the `titleMedium` role label. Deliberately *not* scheduled: §2 puts feature expansion after behavioural parity, and this touches settings storage, the message header, and export/import format compatibility. Revisit once M5 signs off.

### Dependencies / tech debt
- [x] **[TECH DEBT — SECURITY] `multiplatform-markdown-renderer` upgraded to the latest release — RESOLVED 2026-07-29 (`0.33.0` → `0.43.0`).** The pin existed because `0.33.0` was the newest version binary-compatible with Kotlin `2.1.0` / Compose BOM `2024.12.01`; releases past it are built with newer Kotlin, and **Kotlin metadata is a hard blocker** (a `2.1.0` compiler cannot read it without `-Xskip-metadata-version-check`, which we will not use). Clearing it required the coordinated bump recorded in §3: Gradle `9.5.0`, AGP `9.3.1` (incl. the built-in-Kotlin migration), Kotlin `2.4.10`, KSP `2.3.10`, Compose BOM `2026.06.01`, Room `2.8.4`, compileSdk `37`. Both payoffs were taken on arrival — `parseMarkdown()` and `StreamingMarkdownState`, see §11.2. The historical analysis of the version floor is preserved in §11.1.
  - Note: the previously recorded floor of Kotlin `2.2.x` was wrong, and the corrected estimate of `2.3.x` was also low. `0.43.0` ships `kotlin-stdlib 2.4.0`.

## 11. Battery usage audit (2026-07-27)

Quick static audit of energy-relevant code paths. No continuous background work exists — the app has no services, no `WAKE_LOCK`/`FOREGROUND_SERVICE` permissions, no polling loops or timers, and no location/sensor usage (`INTERNET` is the only permission). Work is entirely user- or stream-driven. Findings and their dispositions (after review 2026-07-27) below.

### Worth exploring

- [x] **Model-fetch retry loop — FIXED 2026-07-28.** `ChatViewModel`'s settings collector auto-fetched models whenever `models.isEmpty() || changedEndpoint`. A **failed** fetch leaves `models` empty, so the condition stayed true and re-fired `refreshModels()` on every subsequent settings emission — repeatedly re-connecting to an unreachable endpoint in the background. Resolved with a one-shot `autoFetchedModels` flag: the app now fetches models exactly once per launch (plus once per endpoint change) and **never auto-retries after a failure**. The user re-triggers a fetch manually via the Settings refresh/scan buttons.

- [x] **Whole-map copy per token — FIXED 2026-07-27 (was the one genuine inefficiency).** Previously `MessageTree.updateContent` deep-copied the full `Mappings` (`LinkedHashMap` via `copyOf`) and emitted a fresh `ChatUiState` on **every** token → O(N) allocation churn per delta for large conversations. Resolved by **decoupling the streaming buffer from the tree** (option 2 below): `ChatUiState` now carries a lightweight `streamingId`/`streamingText` pair, `appendToResponse` only appends to that string per token, and the `conversation` getter overlays it onto the streaming node for real-time display. The tree map is left untouched during streaming and rewritten **exactly once** in `finishStreaming` (via `MessageTree.setContent`), followed by the single existing persist. (Disk writes were already once-at-end — `appendToResponse` never persisted — so only the in-memory copy needed fixing.) Real-time streaming output is preserved; only the latest message re-parses markdown since its `key`-ed list item is the only one whose content changes.
  - Alternatives considered: (1) **persistent map** (`kotlinx.collections.immutable`, order-preserving `PersistentMap`) — O(log N) `put` with structural sharing, near drop-in for `copyOf`, but still touches the tree per token; (3) **coalesce/throttle tokens** — fewer emissions but still O(N) per batch. Option 2 was chosen because it removes per-token tree churn entirely while matching the requirement that copying happen only once after the stream completes or is stopped.

- [x] **Markdown re-parse on scroll — FIXED 2026-07-28, re-audited and reworked 2026-07-29.** `MarkdownText` (`ui/markdown/Markdown.kt`) re-parsed the **entire** message (AST build + reference-link lookup) on every recomposition. Because `LazyColumn` disposes off-screen items, scrolling a long chat re-parsed each bubble every time it re-entered the viewport. Resolved with a module-level access-ordered LRU (`MarkdownParseCache`) keyed by content string → parsed `State.Success`, rendered via the m3 `Markdown(state = …)` overload. Styling (typography/padding/annotator) is still applied fresh at render, so appearance is unchanged; parsing is delegated to the library so reference-link resolution is preserved. The cache is a process-lifetime object, so it is cleared on conversation switch (`clearMarkdownParseCache()` from `ChatScreen`'s `LaunchedEffect(state.root)`) to release the previous chat's retained ASTs. **Measured audit in §11.1** reclassified this as a scroll-smoothness fix rather than a battery fix and flagged the entry-count bound; **§11.2** records the resulting rework (character-budget eviction, single-`remember` parse via `parseMarkdown()`, and a separate incremental path for the streaming bubble).

- [x] **`getChildren` O(nodes²)/frame on scroll — FIXED 2026-07-28.** `ChatScreen`'s `items` lambda called `MessageTree.getChildren` (an O(nodes) `mappings.values.filter`) per node to find siblings for branch arrows → O(nodes²) per recomposition in long chats. Replaced with a single `remember(state.mappings) { mappings.values.groupBy { it.parent } }` grouping. `state.mappings` is a new instance only on structural edits (edit/delete/regenerate) and reference-equal during streaming, so the grouping is correctly invalidated on tree changes and skipped (O(1)) during streaming.


### Accepted / intentional (no action)

- **No read/idle timeout on the streaming socket.** `OpenAiClient`'s **streaming** client sets `readTimeout(0)` **intentionally** so a slow model isn't cut off. A stalled/half-open connection can keep the socket (and radio) awake until the user taps Stop; accepted as a deliberate trade-off for reliable long generations. *(FIXED 2026-07-28: this now applies only to the streaming client. The non-streaming models GET previously shared the same `readTimeout(0)` client and could hang forever on a half-open connection; it now uses a separate client with finite `readTimeout(5s)` + `callTimeout(5s)`. Connect timeout also cut 30s→5s.)*
- **Streaming continues while backgrounded.** The stream runs in `viewModelScope`, not tied to UI visibility, so generation keeps running off-screen. **Intentional** — a reply should not be lost because the user briefly leaves the app.
- **Subnet scan burst.** `EndpointScanner.scanForEndpoint` fires up to `CONCURRENCY = 64` concurrent probes across a /24 (≈254 hosts) and, on miss, an extended /21 (up to ~2046 hosts) at a 400 ms timeout — a short, intense radio + CPU spike. **Intentional and bounded:** triggered only by explicit user action (never periodically in the background), batched, and cancels remaining probes once a match is found.
- **Scrollbar `computeMetrics` O(N)/frame — assessed, not changed (2026-07-28).** `DraggableScrollbar.computeMetrics` sums cached item sizes across all items (and the pre-thumb prefix) each frame while scrolling. This is cheap float/`HashMap` arithmetic (no parsing/allocation) and only runs during active scroll; even at extreme message counts the cost is sub-percent of a core. A true O(1) fix needs prefix-sum caching that stays consistent as sizes populate — disproportionate complexity/regression-risk for the carefully-tuned thumb geometry, so deferred until profiling shows it matters.

### Positives (no action needed)

- No wake locks, foreground services, alarms, or `keepScreenOn`.
- No background polling, timers, or `while(true)` loops; all coroutines are event- or stream-scoped.
- Persistence is cheap: writes are coalesced through a `CONFLATED` channel with a single consumer, and `MessageRepository` writes only the diff (skipping the DB entirely when nothing changed).
- Scanner and streaming clients cancel their in-flight work correctly (`cancelChildren`, `EventSource.cancel`, `awaitClose`).
- The scrollbar fade uses a single idle `delay` gated on scroll events, not a running animation loop.

## 11.1 Markdown parse-cost audit (2026-07-29)

Follow-up audit of the `MarkdownParseCache` change from §11, this time **measured** rather than reasoned about. Method: a throwaway JUnit harness against `org.jetbrains:markdown:0.7.3` (the parser the renderer delegates to), run on the desktop JVM. Numbers are therefore JIT-warm x86-64; on-device ART/ARM is roughly **3–6× slower**. Content samples are synthetic assistant replies (headings, prose, bullets, fenced code, blockquotes).

### Confirmed: the library really does re-parse per recomposition

`rememberMarkdownState(content, immediate = true)` re-parses on **every composition and recomposition**, for two independent reasons:

1. `state.parseBlocking()` is called unconditionally in the composable body — there is no `remember`/`LaunchedEffect` guard around it.
2. `remember(input)` can never hit either: `Input` is a `data class`, but its `flavour` / `parser` / `referenceLinkHandler` defaults are freshly allocated on each call and none of those types override `equals`.

So the original premise was correct. The *mechanism* differs from what §11 recorded, though: it is not primarily that `LazyColumn` disposes off-screen items — that only sets the **frequency**, because `MarkdownText` is otherwise skippable (`String`/`Modifier`/`Boolean` params are all stable).

### Measured: parse cost

| content | AST nodes | parse (desktop) | est. on-device |
|---|---|---|---|
| 406 ch | 88 | 0.15 ms | ~0.5–0.9 ms |
| 1,194 ch | 248 | 0.22 ms | ~0.7–1.3 ms |
| 3,164 ch | 648 | 0.59 ms | ~1.8–3.5 ms |
| 7,952 ch | 1,608 | 0.67 ms | ~2.0–4.0 ms |

### Reclassification: this is a jank fix, not a battery fix

Scroll re-entry only happens **with the display on and a finger on the screen**, where display + GPU draw ~two orders of magnitude more power than a few ms of parsing. The cache does **not** meaningfully move idle or reading battery.

It is still a worthwhile change, just under a different heading: 2–4 ms of avoided main-thread work is a real slice of an 8.3 ms frame budget at 120 Hz. **Treat `MarkdownParseCache` as a scroll-smoothness optimisation.**

### Concern: `MAX_ENTRIES = 480` is bounded on the wrong axis

Retained heap scales linearly with content — measured at **≈9.5 bytes of AST per content character**, consistent across all four sample sizes.

| avg message size | 480 entries retain |
|---|---|
| 406 ch | 2.0 MB |
| 1,194 ch | 5.4 MB |
| 3,164 ch | **13.9 MB** |
| 7,952 ch | **34.2 MB** |

Because `clearMarkdownParseCache()` fires on every conversation switch, the working set is only the *current* chat (typically 10–60 messages). So the 480 cap is **inert in the common case and only binds in exactly the pathological case where it permits 14–34 MB** — no protection where it is needed, no benefit where it is not. Retaining tens of MB shrinks ART's GC headroom (more frequent concurrent GCs, which *is* real battery) and raises the odds of an LMK kill while backgrounded, whose cold restart costs more than every parse the cache will ever save.

**Recommendation:** re-bound by summed content characters rather than entry count — ~512 KB of content ≈ 5 MB retained, using the measured 9.5 B/char. *(Adopted 2026-07-29 — see §11.2.)*

### Minor findings

- On a cache **miss**, `remember(markdown) { cache.get() }` pins `null` for that composable's lifetime, so the item keeps the re-parsing path even after `LaunchedEffect` populates the cache. The benefit only lands on the next dispose/re-enter cycle.
- The miss path adds a `collectAsState()` subscription per uncached bubble that the hit path does not need.
- `@Synchronized` on the cache is unnecessary (composition and `LaunchedEffect` both run on the main thread), but harmless.
- Content-string keying is sound: `String.hashCode` is memoized, and keys are usually the same instance held in `mappings`, so `equals` short-circuits on reference.

### Accepted / intentional: the quadratic streaming re-parse

`streamingText` grows per chunk, so the streaming bubble gets a new content `String` per token → recomposition → unguarded `parseBlocking()` over the **whole reply so far**. Cumulative cost for a single response (desktop; multiply by 3–6 for device):

| final reply | tokens | cumulative parse | AST nodes allocated |
|---|---|---|---|
| 406 ch | 101 | 9.2 ms | 4,923 |
| 1,194 ch | 298 | 19.6 ms | 38,387 |
| 3,164 ch | 790 | **96.6 ms** | **259,628** |
| 7,952 ch | 1,987 | **449.8 ms** | **1.61 M** |

Cleanly quadratic (2.5× content → 4.7× cost). `MessageItem` passed `cache = !(busy && isLatest)`, so this path was deliberately excluded from the cache.

**Disposition at time of audit: known and accepted.** The cache was scoped on purpose to idle/reading use — the concern was a user scrolling back through a long conversation, not generation. Streaming is bounded (it ends when the reply ends), screen-on, and already the moment the user expects the device to be working. It was nevertheless the largest remaining CPU/allocation item in the UI, and it retracts the §1.2 claim that this RN defect does not exist natively. Levers, cheapest first:

1. **Coalesce/sample token updates** (`Flow.sample(~40–60 ms)` on the render path only, leaving `streamingText` accumulation untouched). No dependency change; caps re-parses at ~20/s instead of ~30–60/s.
2. **`StreamingMarkdownState`** — see below. The actual fix.

**Superseded 2026-07-29:** lever 2 was implemented (§11.2), which removes the quadratic term outright rather than reducing its constant. Lever 1 was not needed.

### Investigated: `StreamingMarkdownState` (renderer `v0.42.0`, PR #575)

Checked 2026-07-29 at the user's suggestion. **What it is:** an append-only incremental parser built on `org.intellij.markdown.parser.StreamingMarkdownFile` / `EmptyStreamingMarkdownFile`, new in `org.jetbrains:markdown` **0.7.5** (the renderer bumped 0.7.3 → 0.7.5 in that PR specifically to get it). It keeps a `StringBuilder` of accumulated content and exposes a `Snapshot(stableAst, unstableAstTail)`; `append(chunk)` re-parses only the trailing unfinished block, making a full response **O(n) instead of O(n²)**. Stable node identity is preserved across appends (the library's own tests assert `assertSame` on a completed paragraph), so Compose can skip re-rendering already-finalised blocks — a second win on top of the parsing one. API surface: `rememberStreamingMarkdownState()`, `Flow<String>.collectAsStreamingMarkdownState()`, and an m3 `Markdown(streamingMarkdownState = …)` overload. The same PR **deprecates** `Flow<String>.asMarkdownState()` with precisely our diagnosis: *"reparses every emitted String and is not suitable for streaming content."*

**Verdict: right tool, different problem.** It targets the streaming path (§ above), **not** the idle/scrolling path that motivated `MarkdownParseCache`:

- `rememberStreamingMarkdownState` is a `remember`, so scrolling a finished message out of the `LazyColumn` and back still destroys and rebuilds it. It has no cross-disposal persistence.
- It cannot be seeded with existing content — `append(chunk)` is the only mutator. Calling `append(wholeMessage)` once degenerates to an ordinary full parse.
- Its `Snapshot` holds `List<ASTNode>` for the finished message, i.e. the same retained footprint as the current cache. No memory advantage either.

**Also required on our side:** our streaming text lives in `ChatViewModel` as an accumulated `streamingText: String` in `ChatUiState`, not as a `Flow<String>` of deltas at the UI layer. Using `collectAsStreamingMarkdownState` means exposing the chunk flow to the composable and handling branch switching, regeneration and process death against an append-only object. That is an architectural change, not a drop-in. *(Resolved 2026-07-29: the chunk flow was avoided entirely by deriving deltas from the accumulated text — §11.2, decision 2.)*

**Blocked on the same toolchain bump as everything else** (Kotlin `2.3.x` metadata — see §10 Dependencies / tech debt). Until then, lever 1 (sampling) is the only option, and it is deliberately not taken. *(Unblocked and adopted 2026-07-29; the real Kotlin floor turned out to be 2.4 — see §11.2.)*

**Caveats if/when we do adopt it (feature is young — merged and released June):** the default `StreamingMarkdownSuccess` renders into a plain `Column` iterating all `stableAst` nodes with no `key(…)`, so slot reuse across tail changes is unguarded; the `LazyMarkdownSuccess` variant keys on `startOffset` alone (collision risk); and `MarkdownElementInternal` keys its `remember` on the **mutable** `StringBuilder` by reference, so the remembered model can go stale. All three were raised in automated review on the PR and left as-is.

## 11.2 Markdown rework (2026-07-29)

Implementation of the §11.1 recommendations, unblocked by the toolchain bump recorded in §3. Two separate problems, now on two separate code paths.

### Settled messages: `MarkdownText`

The parse now happens inside a **single `remember(markdown)`**, using `0.43.0`'s synchronous top-level `parseMarkdown()`:

```kotlin
val state = remember(markdown) {
    MarkdownParseCache.get(markdown)
        ?: parseMarkdown(markdown).also {
            if (it is State.Success) MarkdownParseCache.put(markdown, it)
        }
}
```

This replaces the `rememberMarkdownState(immediate = true)` + `collectAsState()` + `LaunchedEffect` arrangement and resolves all three "minor findings" from §11.1 at once: there is no longer a miss branch that pins `null` for the composable's lifetime, no extra `collectAsState()` subscription on uncached bubbles, and the hit and miss paths are the same expression.

The `cache: Boolean` parameter is gone. It existed solely to keep the streaming bubble out of the cache; the streaming bubble no longer routes through `MarkdownText` at all.

### Cache bound: characters, not entries

`MAX_ENTRIES = 480` → `MAX_CHARS = 512 * 1024`, with eviction driven by a running sum of key lengths. At the measured ~9.5 B of AST per content character that is ~5 MB retained, and it holds regardless of message size — which was the whole problem with the entry count.

Eviction is an explicit loop in `put` rather than `removeEldestEntry`, because the latter evicts at most one entry per insertion and cannot recover when a single large message pushes the total well past the budget. The loop stops at `size > 1`, so the entry just inserted is never evicted even if it alone exceeds the budget — it is the one being rendered.

### Streaming replies: `StreamingMarkdownText`

The actively streaming bubble now renders from an append-only `StreamingMarkdownState`, so each token re-parses only the trailing unfinished block: **O(n) per response instead of O(n²)**, eliminating the 450 ms / 1.6 M-node worst case in §11.1. Stable nodes keep their identity across appends, so Compose also skips re-rendering finalised blocks.

Two integration decisions worth recording, both addressing §11.1's objections:

1. **The state is hoisted above the `LazyColumn`**, in `ChatScreen`, keyed by `state.streamingId`. §11.1 correctly noted that `rememberStreamingMarkdownState` dies with its composable and the parser cannot be re-seeded, which would be fatal if it lived inside the list item — `LazyColumn` disposes items that scroll out of view. Hoisting sidesteps that entirely; keying on `streamingId` discards the state on a new stream, branch switch, or regeneration.

2. **Deltas are derived from the accumulated text, not from a chunk flow.** `ChatUiState.streamingText` remains the single source of truth; `rememberChatStreamingMarkdownState` tracks how much it has already appended and feeds the parser `text.substring(appended)` from inside a `snapshotFlow { … }.collect { … }`. This avoids exposing a `SharedFlow` of chunks to the UI (which would drop tokens emitted before collection starts) and makes conflation *safe by construction*: `snapshotFlow` conflates, so a burst of tokens arriving faster than the parser simply collapses into one larger delta. Correctness does not depend on observing every intermediate value — which is exactly why the §11.1 lever-1 throttle became unnecessary.

When a stream completes, `streamingId` clears and the bubble falls back to `MarkdownText`, which parses the finished reply once and populates the LRU. That single full parse is the cost of the handoff and is what makes the message a cache hit for all subsequent scrolling.

### Known caveats carried forward

The three upstream quality issues listed at the end of §11.1 are still present in `0.43.0` and we are now exposed to them. None is a correctness problem for our usage as far as we can tell — the keyless `Column` and the by-reference `StringBuilder` `remember` key both behave because stable nodes have fixed offsets and the unstable tail is rebuilt on each append — but they are the first place to look if streaming rendering ever misbehaves.

### Verification

`:app:assembleDebug`, `:app:assembleRelease` (R8 + resource shrinking) and `:app:testDebugUnitTest` all pass on the new toolchain. Remaining compile warnings are pre-existing deprecations unrelated to this work (`LocalClipboardManager`, non-auto-mirrored `KeyboardArrowLeft`/`Right`).

Signed release APK (arm64-v8a) grew ≈1.3 MB → **≈1.7 MB**, attributable to the Compose 1.7.6 → 1.10.x and renderer 0.33.0 → 0.43.0 jumps. Accepted: still an order of magnitude under the ~20 MB React Native build and well inside the ~5–8 MB target in §1.

**Verified on-device 2026-07-29** on two handsets, **Android 11 (API 30)** and **Android 16**, spanning the pre- and post-`StreamingMarkdownState` platform range that matters to us — API 30 also exercises the non-platform-splash path noted in §4.5. No streaming-render regressions observed, so the upstream caveats above have not bitten in practice.

