# Vory for Android

A from-scratch Android port of **[matt0975/vory](https://github.com/matt0975/vory)** (MIT) — a native
remote for a self-hosted [Hermes Agent](https://hermes-agent.nousresearch.com) gateway. The agent runs
on **your** machine; this app is a full client over the dashboard's REST API and the `/api/ws`
JSON-RPC socket. Nothing is hardcoded: on first launch you enter the URL of your own
`hermes serve` / `hermes dashboard` and how you authenticate to it.

Wire protocol: [`../PROTOCOL.md`](../PROTOCOL.md), extracted from the upstream repo on 2026-10-05.
Endpoint names and auth flows follow it exactly — no invented endpoints.

## Stack

- Kotlin 2.0 + Jetpack Compose (Material3), single activity, MVVM (ViewModel + StateFlow)
- OkHttp (REST + WebSocket), `org.json` (one JSON approach, dependency-light)
- DataStore (prefs) + `androidx.security:security-crypto` EncryptedSharedPreferences (tokens)
- Coil (attachment thumbnails), Navigation Compose
- `applicationId dev.vory.android`, minSdk 34, targetSdk 36, compileSdk 36, AGP 8.10.1
  (first stable AGP line with explicit API 36 support; needs Gradle 8.11.1+)

## Build

Requires **Android Studio Ladybug+** and **JDK 17**.

```bash
cd builds/vory-android
./gradlew assembleDebug        # first run downloads the Gradle 8.10.2 distribution
```

The wrapper jar (`gradle/wrapper/gradle-wrapper.jar`) is intentionally not committed; the
`gradlew` scripts fetch the Gradle 8.11.1 distribution on first use per
`gradle/wrapper/gradle-wrapper.properties` (minimum required by AGP 8.10.1).
Open the project root in Android Studio and sync — the version catalog is in
`gradle/libs.versions.toml`.

## Install on a Galaxy A34

1. On the phone: Settings → About phone → Software information → tap **Build number** 7×,
   then Developer options → enable **USB debugging** (or **Wireless debugging**).
2. `adb devices` → accept the prompt on the phone.
3. `./gradlew installDebug` (or Run in Android Studio).

## One UI 8.5 design notes (target: Galaxy A34, 1080×2340, Exynos 1280 / 6 GB)

- Large collapsing titles per screen (Samsung Settings style), 20dp screen padding,
  24dp rounded cards, 4-tab bottom navigation (Home, Chats, Bots, Settings), edge-to-edge with insets.
- Dark = true black `#000000` (AMOLED); light = One UI greys (`#F7F7F7` background, white cards).
- Accent Samsung Blue `#0381FE`, user-pickable per Appearance settings (per-bot colours too).
- System font only, large titles 32sp bold.
- Motion is cheap: no heavy blur/shadow overdraw, stable keys in lazy lists, streamed text renders
  only the tail bubble, Coil memory cache kept modest. WebSocket backoff 1s→2s→4s… max 30s;
  aggressive retry pauses in Doze and resumes on user return. No polling loops — socket-driven only.
- System reduced-motion is honoured everywhere (bot faces go still except blinking).
- All touch targets ≥ 48dp.

## Feature checklist vs the original

| Area | Status |
|---|---|
| Gateway wizard (name, URL normalisation, 3 auth modes, CF Access, 3-leg test) | ✅ |
| Multiple saved gateways | ✅ |
| Chats list (search, swipe pin/archive/delete, Needs-you badge, project chips) | ✅ (pin/archive best-effort via `session.pin`/`session.archive`) |
| Chat (resume/create, `prompt.submit`, `message.delta` streaming, tool cards, todo checklist, `message.complete`, per-turn stats, approvals Once/Session/Always/Deny, clarify/sudo/secret, attachments → `@file:`, model picker session-scoped, slash commands + `commands.catalog`, reply quotes, bot-to-bot notices) | ✅ |
| Home (greeting, Overview 7/30/90 + 13-week blocks + cost, Bots row, Pick up, reorder/hide cards) | ✅ ("Since you were here" folded into Activity) |
| Bots (faces, status, model; sheet with colour, description, default model, SOUL.md) | ✅ |
| Files (paged browser, dot-file toggle, download + open, multipart upload) | ✅ |
| Settings API map (model, config deep-merge, env, tools, skills, MCP, approvals, cron, sessions, channels RO, system + doctor, maintenance + action tail, plugins RO) | ✅ |
| Appearance (accent, dark override, face motion, start tab, chat display) + Notifications settings | ✅ |
| Code-drawn bot faces (blink/glance/working tilt/`!`/error eyes, no scaling, reduced-motion) | ✅ |
| Local notifications (approvals with Approve/Deny, turn-done with Reply, `vory://chat/<id>` deep links; no FCM) | ✅ |
| Encrypted token storage, redacted logs, plain-http private-network warning, 503 restart banner | ✅ |
| Projects (`projects.*`, hidden on `-32601`) | ✅ (filter chips + `groups.create` rooms) |
| OIDC browser flow | ⚠️ thin: PKCE + system browser + `vory://oauth` callback; degrades gracefully |
| Push relay / Live Activities / widgets | ❌ out of scope (no FCM dependency by design) |

## Project layout

```
app/src/main/java/dev/vory/android/
├── MainActivity.kt            # nav graph, theme wiring, deep links, restart banner
├── VoryApp.kt                 # Application: store, repository, Doze-aware socket lifecycle
├── data/
│   ├── models.kt              # Gateway, BotProfile, ChatSession, ChatItem, PendingCard, …
│   ├── GatewayStore.kt        # DataStore prefs + EncryptedSharedPreferences secrets
│   ├── HermesRestClient.kt    # REST: ?profile= scoping, auth headers, 401 refresh, HTML detection
│   ├── HermesSocketClient.kt  # JSON-RPC 2.0 over /api/ws: id→continuation map, backoff
│   └── AppRepository.kt       # app-scoped: clients, profiles, card registry, 503 probe
├── ui/
│   ├── theme/Theme.kt         # One UI colours/typography
│   ├── components/
│   │   ├── BotFace.kt         # canvas-drawn bot faces + motion vocabulary
│   │   └── OneUi.kt           # large-title scaffold, cards, rows, pills, prefs
│   └── screens/               # Setup, Home, Chats, Chat, Bots, Files, Settings + 16 sub-screens
├── vm/                        # ViewModels per screen
├── notifications/             # local notifications + action receiver
└── util/                      # time/format/network helpers
```

## Security model

- Tokens live only in EncryptedSharedPreferences; DataStore JSON never holds a secret.
- Log output is redacted (token-shaped values masked).
- Plain `http://` is allowed for LAN use, with a warning when the host isn't private.
- No hardcoded servers, no Vory account, no middleman cloud.

## Attribution

Protocol and feature design: [matt0975/vory](https://github.com/matt0975/vory), MIT licence.
This Android port is a community reimplementation; see the upstream repo for the iOS original.
