# nanobot-client → nanobotkt (Compose) Migration Design

**Date:** 2026-08-05
**Status:** Approved (pending user spec review)
**Scope:** Full migration of `C:\Users\Administrator\Desktop\code\nanobot-client` (Expo SDK 57 + React Native 0.86) into `C:\Users\Administrator\AndroidStudioProjects\nanobotkt` (Kotlin + Jetpack Compose). UI need not be pixel-perfect; interaction logic must be 1:1 with the source client, including the WebSocket streaming protocol, dual-epoch authentication, connection recovery, and stream-fold state machine.

## 1. Architecture skeleton & package layout

The Kotlin package layout mirrors nanobot-client's `src/features/<feature>/` boundary convention: features only expose public surface through `index.kt`, internal subfolders may grow freely; cross-feature imports of deep files are disallowed.

```
com/example/nanobotkt/
├── MainActivity.kt            # Compose entry; LocaleGate -> AppShell
├── NanobotApp.kt              # Application: init DataStore + EncryptedSharedPreferences
├── core/                      # No feature/app dependencies
│   ├── net/      ApiClient · SocketTransport · BootstrapClient · ServerUrlResolver
│   ├── persist/  LocalPreferences (DataStore) · SecureStore (EncryptedSharedPrefs) · DevSecretLoader
│   ├── auth/     AuthStore (bootstrap orchestration, sessionEpoch owner) + AuthError codes
│   ├── serial/   Wire-format types (InboundEvent/OutboundFrame sealed class + all chat/settings types)
│   ├── i18n/     LocaleManager · LocalLocale (CompositionLocal)
│   ├── theme/    Palette · Type · NanobotTheme (Material3 ColorScheme)
│   └── log/      DebugLog · DebugOverlay
├── connection/                # Written by lead, serially
│   ├── transport/   transport state + queue flush + reconnect
│   ├── reconnect/   SocketReconnectPolicy
│   ├── recovery/    ConnectionRecoveryPolicy (ConnectivityManager + Lifecycle)
│   ├── outbound/    SocketOutboundQueue
│   ├── inbound/     SocketInboundRouter (JSON -> sealed event dispatch)
│   ├── pending/     SocketPendingRegistry (new_chat/message/system/transcription)
│   └── store/       ConnectionStore (status, knownChats, reconnectReason, sessionEpoch, tokenGeneration)
├── chat/                      # Written by lead, serially (interaction core)
│   ├── store/    ChatStore · stream-runtime · inbound-event-handler · message-reconciliation
│   ├── stream/   StreamFoldState + 4 reducers (reasoning/answer/completion/assistant)
│   ├── composer/ ComposerController · SlashCommandModel · FileRefModel
│   ├── activity/ ToolActivityCluster · FileEditRow · FileEditGroup · ToolRowModel
│   ├── attachments/ ImageEncoder · AttachmentLimits · AttachmentValidation · native picker
│   ├── voice/    VoiceRecorder · RecorderLifecycle · AudioMode
│   ├── hooks/    useChatThreadModel · useComposerController · useChatScroll · useMessageActions · useFilePreview · useVoiceRecorder
│   └── components/ ChatThread · ChatHeader · MessageRow · UserMessageBody · ChatModals · Composer
├── sidebar/                   # Written by lead, serially (entry surface)
│   ├── store/    SidebarStore
│   ├── hooks/    useSidebarController
│   └── components/ Sidebar · TopicRow · NewTopicSheet · SearchSheet · ArchivedSheet
├── app/                       # Top-level composition
│   ├── AppShell · ReadyAppShell · AppModals · AppUtilityRouter · AppUtilityWorkspace
│   ├── hooks/   useAppBootstrap · useConnectionRecovery · useReadyData · useSocketLifecycle · useAuthBootstrap · useAppNavigation · useAppSessionCommands · useAppModelSelection · useAppPreferences
│   └── nav/     NavHost (ModalNavigationDrawer + Compose Navigation)
├── auth/  workspaces/  settings/  skills/  automations/  channels/  capabilities/  security/
```

### Dependency direction (matches source)

```
app -> connection -> core
app -> chat -> connection -> core
app -> sidebar -> chat -> connection -> core
app -> <feature> -> core
app -> auth/workspaces/settings/skills/automations/channels/capabilities/security -> connection -> core
```

### Build dependencies (libs.versions.toml)

- Kotlin 1.9.25, Compose BOM 2024.10.01, Material3, Navigation-Compose 2.8.4
- OkHttp 4.12.0 (HTTP + WebSocket together; lighter than Ktor, consistent stack)
- kotlinx-serialization-json 1.6.3
- androidx.datastore:datastore-preferences 1.1.1
- androidx.security:security-crypto 1.1.0-alpha06
- io.coil-kt:coil-compose 2.7.0
- Markdown: try `eu.wewox:compose-markdown` first; fall back to self-rolled `AnnotatedString` + `prism4k` 1.0.0 if code highlighting / Markdown behavior diverges from source
- androidx.lifecycle:lifecycle-viewmodel-compose / lifecycle-runtime-compose
- androidx.compose.material:material-icons-extended
- `compileSdk 37`, `minSdk 24`, JVM 17 target (bumped from 11 to unlock newer Kotlin/Compose compilers)

## 2. Cross-cutting patterns (state / hooks / stores / persistence / i18n / theme / navigation)

### Hooks style (option A, approved)

Compose-native hooks match React's mental model 1:1. Each feature exposes a store plus a group of actions, named after React's `useFooStore()` / `useFooActions()`:

```kotlin
// chat/store/ChatStore.kt
object ChatStore : ViewModel() {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()
    fun appendMessage(...) { _state.update { ... } }
}

// chat/hooks/useChatThreadModel.kt
@Composable
fun useChatThreadModel(chatId: String): ChatThreadModel {
    val state by ChatStore.state.collectAsStateWithLifecycle()
    val actions = remember(chatId) { ChatActions(chatId) }
    return ChatThreadModel(state.byChat(chatId), actions)
}
```

Mapping: `remember(key) { ... }` ↔ `useMemo`; `LaunchedEffect(key)` ↔ `useEffect`; `derivedStateOf` ↔ `useMemo(deps)`; custom `@Composable fun` ↔ custom React hook.

### Store

Each feature has one `object FooStore : ViewModel()`. State in `MutableStateFlow`, public read-only `StateFlow`. Actions live on the same object — no separate ViewModel. The "Zustand per-feature store" semantic is carried by "feature boundary = object".

### Persistence

- Ordinary preferences (theme / language / density / activity mode / file-edit display): `DataStore Preferences`; keys mirror `local-preferences-store.ts` 1:1.
- Sensitive (bootstrap secret / token): `EncryptedSharedPreferences` at path `nanobot_secure_prefs`.
- `EXPO_PUBLIC_NANOBOT_SERVER_URL` becomes `BuildConfig.NANOBOT_SERVER_URL` (Gradle-injected, build-time override).
- `dev-secret.ts` becomes an `EncryptedSharedPreferences` key, written at runtime, gitignored, never committed.

### i18n

`res/values/strings.xml` (en) plus `values-es/values-fr/values-id/values-ja/values-ko/values-pt-rBR/values-vi/values-zh-rCN/values-zh-rTW`. `LocaleManager.applyLocale(activity)` runs as the first line of `MainActivity.onCreate`, applying the saved language. `LocalLocale` exposes the current locale via `CompositionLocal` for any code that needs programmatic locale checks.

### Theme

`Color(0xFFFAFAF9)` background, `Color(0xFF208AEF)` primary, dark variant `Color(0xFF121212)` background with the same accent lifted. `MaterialTheme` from material3; custom `ColorScheme` for light/dark; Typography mirrors RN's type scale. `automatic` mode follows `isSystemInDarkTheme()`.

### Navigation

- Top-level `NavHost`; primary route `/chat/{chatId}` plus `/settings/*`, `/skills`, `/apps`, `/automations`, `/workspaces`.
- Sidebar uses Material3 `ModalNavigationDrawer` (native, not hand-drawn).
- Modals (new topic, search, archive, assistant quote, file preview, session info) use Material3 `ModalBottomSheet`.
- Top app bars use Material3 `TopAppBar` (no custom top bars).

## 3. Connection lifecycle (dual-epoch auth + recovery + inbound routing)

Direct port of the source architecture doc sections 4 and 5.

### Dual-epoch

- `sessionEpoch: Long` increments on identity change (login / logout / no-session recovery); business state (chats, settings, …) resets with epoch.
- `tokenGeneration: Long` increments on every successful bootstrap token; connection layer and token-derived timers rebuild on generation change.
- Silent renewal does NOT trigger reset.

### Bootstrap flow (in `core.auth.AuthStore.bootstrap()`)

1. `ServerUrlResolver.resolve()` order: BuildConfig override → EncryptedSharedPreferences dev secret → `__DEV__` Android → `localhost:8765` (assumes `adb reverse tcp:8765 tcp:8765`) → default `http://192.168.55.147:8765`.
2. `GET <base>/webui/bootstrap` with header `X-Nanobot-Auth: <secret>`.
3. 401/403 → `BootstrapAuthRequiredError` → auth UI.
4. Non-JSON / HTML → `BootstrapResponseError(code)` → translated to user-facing copy in the auth feature.
5. Success → receive `token / ws_path / api_token` → `deriveWsUrl()` (HTTP→ws://, HTTPS→wss://, query `token=...`).

### SocketTransport (singleton per epoch)

- States: `disconnected / connecting / open / closing / reconnecting`.
- API: `connect()` / `disconnect()` / `reconnectNow()` / `setReauthenticate(fn)` / `onStatus(cb)` / `onEvent(cb)` / `onRunStatus(cb)` / `onTransportError(cb)`.
- `knownChats: Map<chatId, Boolean>`: recorded on `attach`; replayed uniformly by the transport after reconnect.
- `outboundQueue: Map<turnId, OutboundFrame>`: frames buffered while socket is not open; flushed on open.
- `pendingRegistry`: tracks `new_chat / message / system / transcription` `Deferred`s.
- `attach` does NOT enter the outbound queue — it relies on `knownChats` replay after open.

### Reconnect + recovery

- `SocketReconnectPolicy`: exponential backoff with cap; parameters match `socket-reconnect-policy.ts`.
- `ConnectionRecoveryPolicy`:
  - ConnectivityManager offline → notify transport immediately; do not reconnect; reject non-suspendable pending requests.
  - Network restored AND app in foreground → `reconnectNow()`.
  - Background → foreground after stale threshold / socket not open / activity stale → `reconnectNow()`.
  - `reconnectReason` recorded in `ConnectionStore`; business layer decides whether to refetch canonical history.
- `transport.reconnectNow()` does NOT accept business reasons — it only closes the socket, replaces it, reconnects. This split matches the source boundary.

### Inbound router

JSON parsed into `InboundEvent` sealed class; routed by `event` field:

| event                          | routed to                                                                                |
| ------------------------------ | ---------------------------------------------------------------------------------------- |
| `ready` / `attached`           | transport internals                                                                      |
| `message_accepted`             | resolve message pending promise                                                          |
| `message(kind=…)`              | ChatStore append activity                                                                 |
| `delta` / `reasoning_delta` / `reasoning_end` / `stream_end` | stream-fold reducer                                  |
| `turn_end`                     | finalize turn + `latency_ms` + `goal_state`                                              |
| `file_edit`                    | activity append                                                                          |
| `session_updated`              | update `workspace_scope`                                                                 |
| `transcription_result` / `transcription_error` | resolve transcription pending                                              |
| `goal_status`                  | notify run-status listener                                                               |
| `runtime_model_updated` / `turn_model_updated` | settings update                                                              |
| `error`                        | bubble to ErrorBoundary                                                                  |

## 4. Chat surface (stream-fold + composer + rendering)

### Stream-fold

- `StreamFoldState`: active `stream_id`, `closed_ids`, active segment kind (reasoning / answer / tool).
- Reducer is pure: `(state, event) → state`; prefers returning new collections (immutable updates).
- Every reducer must validate `turn_id` to prevent cross-thread event interleaving.

Four reducer files mirror their source counterparts:
- `AssistantReasoningReducer` (← `assistant-reasoning-events.ts`)
- `AssistantAnswerReducer` (← `assistant-answer-events.ts`)
- `AssistantCompletionReducer` (← `assistant-completion-events.ts`)
- `AssistantEventsAdapter` (← `assistant-events.ts`, compat entry)

### Composer

- `useComposerController(text, attachments, mode)`: returns `(text, setText)` + send/stop + slash popup + @-mention popup.
- IME action triggers send; Enter triggers send; Shift+Enter inserts newline.
- Attachment menu (icon button sheet): system PhotoPicker / camera / system SAF file picker.
- Long-press mic → `VoiceRecorder` (AudioRecord → WAV); release sends transcription (`transcribe_audio` outbound frame).

### MessageRow

- Branches by `role`: `user` / `assistant` / `tool` / `system`.
- Markdown: prefer `compose-markdown`; fall back to self-rolled `AnnotatedString` + `prism4k` lexer if code highlighting or Markdown behavior diverges from `prism-react-renderer`.
- File edits: `FileEditRow` (`summary` / `diff` / `collapsed_diff`) folded into `FileEditGroup`.
- Media: `AsyncImage` from coil-compose.
- Tool activity: `ToolActivityCluster` (tap to expand/collapse) → internal `ToolRowModel` + `tool-helpers` decide display.

## 5. Execution plan (11-feature split)

### Phase 1 — Lead, serial (~10 PR-equivalents)

1. Gradle + libs.versions.toml + Theme/Type/ColorScheme + Application/MainActivity + LocaleGate
2. `core/persist` (LocalPreferences + SecureStore + DevSecretLoader)
3. `core/serial` full wire-format (chat/events/messages/commands/media/…/settings/*)
4. `core/net` (ApiClient + ServerUrlResolver + BootstrapClient + fetchBootstrap)
5. `core/auth` (AuthStore + bootstrap orchestration + AuthError codes)
6. `connection/*` (transport/reconnect/recovery/outbound/inbound/pending/store)
7. `chat/store` + `chat/stream` + `chat/activity/model/*` + `chat/store/stream-runtime` + `chat/store/inbound-event-handler`
8. `chat/components/*` (ChatThread/MessageRow/UserMessageBody/ActivityMessage/AgentActivityCluster/FileEdit*)
9. `chat/composer/*` + `chat/attachments/*` + `chat/voice/*` + `chat/hooks/*`
10. `sidebar/*` + `app/*` (AppShell + hooks + nav)
11. Integration smoke: connect to nanobot Python gateway; send/receive streaming message

### Phase 2 — Sub-agents, parallel (one feature per agent, each in its own worktree)

- `auth/` — login screen + auth UI (bootstrap orchestration already lives in `core/auth`)
- `workspaces/` — workspace picker + scope persistence
- `settings/` — appearance / models / providers / OAuth / channels / security sub-pages
- `skills/` — list / detail / enable / raw content
- `automations/` — list / detail / enable / trigger history
- `channels/` — Feishu and similar channel bindings
- `apps/` — apps list / detail / OAuth / uninstall / restore
- `capabilities/` — capability toggles
- `security/` — security policies

Each agent receives the established `core/` + `connection/` plus its feature's dependency map. Each writes store + hooks + components, exposing public API only via `index.kt` so the dependency direction is preserved.

### Phase 3 — Lead, integration + verification

- Merge sub-agent patches; fix Gradle / package / import style.
- `./gradlew assembleDebug`.
- adb install + on-device stream round-trip + ConnectivityManager toggle + foreground/background toggle verification.

## 6. Verification

- Unit: JUnit + Turbine assertions on StateFlow state machines. Stream-fold reducers reuse the source's existing Vitest fixtures, ported to Kotlin fixtures, and run round-trip.
- Sequential integration: connect to nanobot Python gateway; send message → receive stream → fold → render.
- `verify:android:recovery` equivalent script (PowerShell): start/stop socket, toggle airplane mode, push to background — assert reconnect reason and canonical-history refetch behavior.
- UI screenshots: port the 45 acceptance scenarios from `docs/verification/full-sweep-2026-08-01/` to the new build and compare.

## 7. Out of scope (this iteration)

- Web target (`expo start --web`): Android only.
- iOS: not in scope.
- Sign / store / GitHub Release pipeline: keep the existing `scripts/release.sh` Android-release logic out of this PR; revisit after the migration ships.
- iOS-specific quirks from source: ignore.

## 8. Open questions

None at design-approval time. Sub-agents may surface feature-level decisions in their PR descriptions; those are reviewed in Phase 3.
