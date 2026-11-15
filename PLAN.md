# GateDelay — Plan & Instructions

A small Kotlin Multiplatform (Android + iOS) app that opens PalGate gates **in a timed sequence**:

- **ENTER**: open *Main* gate → wait **10 s** → open *Entry* gate
- **EXIT**: open *Exit* gate → wait **27 s** → open *Main* gate

The gates, their order and the delays can all be changed in Settings. Several PalGate accounts are supported.
The integration is a Kotlin port of the protocol used by [doron1/ha-palgate](https://github.com/doron1/ha-palgate).

> ⚠️ This uses PalGate's **unofficial** API, the same one the Home Assistant integration uses. PalGate can change it at any time. Use at your own risk.

## Status (2026-11-15)
- ✅ Phase 0: token generator ported to Kotlin and verified against 6 pylgate vectors. Project scaffolded (Kotlin 2.2.10, AGP 8.12.1, Compose Multiplatform 1.9.3).
- ✅ Phase 1 (Android): linking (QR + share + manual import), gate picker, sequences, foreground service, Keystore token storage. Installed and launched on a Galaxy S22 (Android 16). Linking and real gate opening are **not yet tested with a real account**.
- ✅ Phase 2 (Android part): two Glance home-screen widgets (ENTER blue, EXIT orange, with status line) and launcher shortcuts.
- ⏳ Next: test linking and opening on real gates, and confirm multi-output gate fields from `/devices`.
- ⏳ iOS (needs a Mac): add iOS targets, `iosApp` host, Keychain store, WidgetKit/App Intents.

---

## 1. How the PalGate integration works (from ha-palgate)

| Concern | What ha-palgate does | What we do in Kotlin |
|---|---|---|
| **Account linking** | Generates a UUID and shows a QR with `{"id":"<uuid>"}`. The user scans it in the PalGate app under *Linked Devices* (Android) or *Device Linking* (iOS). Meanwhile it long-polls `GET https://api1.pal-es.com/v1/bt/un/secondary/init/<uuid>`. The response has `user.id` (phone, used as the user ID), `user.token` (32 hex chars = 16 bytes) and `secondary` (token type 1 or 2). | Same flow. We generate the QR with the `qrose` library and poll with Ktor. |
| **Auth header** | Each request sends `x-bt-token` = `generate_token(token, phone, tokenType)`. This is a custom AES-128 derivation over the current Unix time + 2 s, so a token is valid for only a few seconds. Code: `pylgate/token_generator.py`, `_aes.py`, `_constants.py`. | We port it byte-for-byte to **pure Kotlin in `commonMain`** with no platform crypto. Unit tests compare its output to the Python version for fixed timestamps. |
| **List gates** | `GET /v1/bt/devices` returns `devices[]` with `id`, `name1`, `customName1`, `address`, … | Same. Used by the gate picker. |
| **Open a gate** | `GET /v1/bt/device/{id}/open-gate?openBy=100&outputNum={n}`. A device with several outputs is addressed as `id:n`. | Same. A *gate* in our app = (account, deviceId, outputNum). |
| **Errors** | 401 = unauthorized. 400 with `"Secondary device not authorized!"` = the gate admin hasn't allowed *Linked Device* for that user. | We map these to clear messages (see Troubleshooting). |

Constraints we inherit:
- PalGate usually allows **2 linked devices per account**. Our app uses one slot on each phone. If you already link Home Assistant, only one slot is left.
- The derived token depends on time, so the phone clock must be roughly correct. We read the server's HTTP `Date` header, keep a clock offset, and retry once if a request is rejected.
- The API doesn't report gate position. We only send "open" pulses, which is all a sequence needs.

---

## 2. Product scope

### Main screen
- Two large buttons: **ENTER** and **EXIT**. They're disabled with a "Configure in Settings" hint until the needed gates are assigned.
- While a sequence runs, the screen shows its progress: `✓ Main opened → 7 s… → Entry`. It also has **Open now** (skip the wait) and **Cancel** buttons.
- The screen stays on while a sequence runs, and the phone gives haptic feedback on each step.
- Below the two big buttons is a row of **small single-gate buttons**: `Main` · `Entry` · `Exit`. Each one opens just that gate, for when you missed one. They're much smaller than ENTER/EXIT, show a quick ✓ or ✗, and stay usable while a sequence is running.

```
┌─────────────────────────────┐
│                             │
│   ┌───────────────────────┐ │
│   │        ENTER          │ │
│   └───────────────────────┘ │
│   ┌───────────────────────┐ │
│   │         EXIT          │ │
│   └───────────────────────┘ │
│                             │
│   [Main]  [Entry]  [Exit]   │  ← small, single gate
│                         ⚙   │
└─────────────────────────────┘
```

### Settings
1. **Accounts**: link a PalGate account (QR flow), see the linked accounts, remove one. There's also an *Advanced → Import manually* option that takes phone + token + type, as a fallback (see §6).
2. **Gates**: three slots: **Main**, **Entry**, **Exit**. Each slot uses a picker that lists every gate from every linked account, shown as `Gate name · address · via +972…`. For multi-output devices, each output is listed separately. A **Test** button opens that single gate.
3. **Sequences**: one card each for ENTER and EXIT: `[First gate ▼]  wait [10] s  then  [Second gate ▼]`.
   Defaults: ENTER = Main → 10 s → Entry; EXIT = Exit → 27 s → Main.

### Multiple users / accounts
- **Each person** installs the app on their own phone and links their own PalGate account. There's no server, and nothing is shared between users.
- **One phone can hold several accounts.** Each gate slot remembers which account it goes through. For example, the Main gate can go through one person's account and the Entry gate through another's, if that's how the permissions are set up.

### Out of scope for v1
Gate position or status, user management, access logs, cloud sync, geofencing.

---

## 3. Architecture

```
gate_delay/
├── shared/                         ← KMP library: all non-UI logic (also used by iOS widget in Phase 2)
│   └── src/
│       ├── commonMain/kotlin/app/gatedelay/
│       │   ├── palgate/
│       │   │   ├── PalgateAes.kt          port of pylgate/_aes.py + _constants.py
│       │   │   ├── TokenGenerator.kt      port of pylgate/token_generator.py
│       │   │   ├── PalgateApi.kt          Ktor: listDevices(), openGate(), clock-offset handling
│       │   │   └── DeviceLinker.kt        UUID + long-poll /un/secondary/init/{uuid}
│       │   ├── model/                     Account, GateRef, GateRole, SequenceConfig, AppConfig
│       │   ├── storage/
│       │   │   ├── ConfigStore.kt         JSON config (DataStore / multiplatform-settings)
│       │   │   └── SecretStore.kt         tokens → Android Keystore / iOS Keychain (KVault)
│       │   └── sequence/SequenceRunner.kt StateFlow<RunState>: open A → countdown → open B; cancel/skip
│       ├── commonTest/                    TokenGeneratorTest (vectors from Python), SequenceRunnerTest (fake API, virtual time)
│       ├── androidMain/                   Ktor OkHttp engine
│       └── iosMain/                       Ktor Darwin engine
├── composeApp/                     ← Compose Multiplatform UI (shared screens)
│   └── src/commonMain/.../ui/  HomeScreen, SettingsScreen, AccountsScreen, LinkAccountScreen, GatePicker, SequenceEditor
│   └── src/androidMain/        MainActivity, SequenceService (foreground, short-service)
│   └── src/iosMain/            MainViewController, background-task wrapper
└── iosApp/                         ← Xcode project (SwiftUI host; later the WidgetKit extension)
```

**Libraries:** Kotlin 2.x, Compose Multiplatform, Ktor client, kotlinx.serialization, kotlinx.coroutines, `qrose` (QR codes in Compose), KVault (Keychain/Keystore), multiplatform-settings or DataStore KMP. There's no DI framework; a small manual `AppGraph` object is enough.

**Data model**
```kotlin
data class Account(val phone: String, val displayName: String, val tokenType: Int)  // token itself lives in SecretStore
data class GateRef(val accountPhone: String, val deviceId: String, val outputNum: Int, val label: String)
enum class GateRole { MAIN, ENTRY, EXIT }
data class SequenceConfig(val first: GateRole, val delaySec: Int, val second: GateRole)
data class AppConfig(
    val gates: Map<GateRole, GateRef>,
    val enter: SequenceConfig = SequenceConfig(GateRole.MAIN, 10, GateRole.ENTRY),
    val exit:  SequenceConfig = SequenceConfig(GateRole.EXIT, 27, GateRole.MAIN),
)
```

**Sequence rules**
- Generate a fresh token right before each request, never earlier, because tokens expire within seconds.
- If step 1 fails, stop and show the error. The second gate must not open if the first didn't.
- Network errors get one retry after 1 s. 401/400 errors aren't retried; they're shown to the user.
- Only one sequence can run at a time, so a double tap is ignored.
- Background behaviour:
  - **Android**: the runner lives in a *foreground service* of type `shortService` (Android 14+ allows ~3 min), so the 27 s wait survives a locked screen or switching apps.
  - **iOS**: `beginBackgroundTask` gives about 30 s, which is too close to 27 s to rely on. v1 keeps the screen awake (`isIdleTimerDisabled`) and asks the user to keep the app open during EXIT. Phase 2 revisits this.

---

## 4. Phases

### Phase 0 — Spike (1–2 days) ⟵ do first, it de-risks everything
1. Port the token generator and test it against Python output:
   `python -c "from pylgate.token_generator import generate_token; print(generate_token(bytes.fromhex('00112233445566778899aabbccddeeff'), 972501234567, 1, timestamp_ms=1700000000))"`
   Save about 5 such vectors and assert equality in `TokenGeneratorTest`.
2. Check the linking flow on a real phone with `tools/link_probe.py` (see §7):
   - `timeout`: how long the server holds the waiting request open
   - `resume`: whether re-requesting the same uuid after the scan still returns the token
   - `link`: link through a second screen and get credentials for manual import
3. Call `/devices` with a real account and save the JSON. Confirm the fields for multi-output devices (`name2`, `customName2`, …) so the picker can list outputs correctly.
4. Open one gate from a test build.

### Phase 1 — MVP app (≈1 week)
1. Project scaffold (KMP wizard), `shared` + `composeApp` + `iosApp`
2. `palgate/*`, `storage/*`, `SequenceRunner` + tests
3. Screens: Home → Settings (Accounts / Gates / Sequences) → Link Account (QR + waiting spinner + manual import)
4. Android foreground service; iOS keep-awake
5. Error mapping, haptics, countdown UI, "Test gate" buttons
6. Internal distribution (see §5.4)

### Phase 2 — Home-screen widgets & shortcuts
- **Android (Jetpack Glance widget)**: a 2×1 widget with **ENTER | EXIT** buttons. Tapping one sends an intent to `SequenceService`, which runs in the background. The widget shows "Opening Main… 7 s" and then "Done ✓" or an error. A 1×1 variant per action can come later, and a larger 4×2 widget can also hold the small single-gate buttons (Main / Entry / Exit). *This fits well: it's the same runner and the same service, and no app UI opens.*
  Extras: a Quick Settings tile and long-press app shortcuts (ENTER/EXIT), both cheap to add.
- **iOS (WidgetKit + App Intents, iOS 17+ interactive widgets)**: the widget extension is written in Swift and calls the `shared` KMP framework. Config and tokens are shared through an **App Group** and a **Keychain access group**, which is why `shared` is a separate module from day one.
  Catch: iOS gives widget intents only a few seconds of runtime, which isn't enough for a 27 s wait. So we would either:
  (a) have the widget button launch the app with the sequence auto-starting (`openAppWhenRun`), which is reliable and recommended, or
  (b) have the intent open gate 1 and schedule a time-sensitive notification "Tap to open Main", which is clunky.
  The same App Intents also give **Siri / Shortcuts / Control Center (iOS 18) / Action Button** support at no extra cost. "Hey Siri, Enter gate" is a nice result.

### Phase 3 — Nice to have
Android Auto / CarPlay buttons, geofence suggestion ("you're near the gate"), per-sequence step lists (more than 2 gates), backup/restore of settings (without tokens).

---

## 5. Instructions — developer

### 5.1 Tools
| Need | Windows (this PC) | Mac |
|---|---|---|
| Android build | Android Studio (latest) + Kotlin Multiplatform plugin, JDK 17+ | same |
| iOS build | ❌ **not possible on Windows** | Xcode (latest) + Android Studio/Fleet + KMP plugin |

iOS **requires macOS**. Options: a Mac, a rented cloud Mac (MacinCloud, etc.), or GitHub Actions `macos` runners that build and upload to TestFlight with fastlane. You can do all Android and shared-code work on Windows and build iOS only when needed.

### 5.2 Create & run
1. Generate the project with the KMP wizard (kmp.jetbrains.com): Android + iOS, "Share UI" (Compose Multiplatform). Then add a `shared` library module.
2. Android: open in Android Studio → choose the `composeApp` run configuration → run on a phone (enable USB debugging) or an emulator.
3. iOS (on a Mac): open `iosApp/iosApp.xcodeproj` → set your Team under *Signing & Capabilities* → run on an iPhone.
4. Tests: `./gradlew :shared:allTests`

### 5.3 Generate token test vectors (Phase 0)
```bash
pip install pylgate          # or copy pylgate/ from ha-palgate
python -c "from pylgate.token_generator import generate_token; \
print(generate_token(bytes.fromhex('00112233445566778899aabbccddeeff'), 972501234567, 1, timestamp_ms=1700000000))"
```

### 5.4 Distributing to other users (family / neighbours)
- **Android**: build a signed release APK (`Build → Generate Signed App Bundle/APK`) and send it, or use Play Console **Internal testing**, which supports up to 100 testers by email.
- **iOS**: Apple Developer Program ($99/yr) → **TestFlight**, which supports up to 10,000 testers by email/link. Without a paid account, Xcode can install on your own phone, but the app expires after 7 days.
- Don't publish publicly on the stores. An unofficial API plus another company's trademark will likely be rejected.

---

## 6. Instructions — end user (in-app setup)

**Before you start (once per account):** the gate **administrator** must allow *Linked Device* for your user. In the PalGate app: *Gate Settings → Manager Options → Users → (you) → Linked Device*. Without it, opening fails with "Secondary device not authorized".

1. Install GateDelay → open **Settings → Accounts → Link PalGate account**. A QR code appears.
2. On the phone that has your PalGate account, open the PalGate app → *Linked Devices* (Android) or *Device Linking* (iOS) → scan the QR.
   - The QR is on the same phone you need to scan with, so tap **Share QR** in GateDelay and show it on another screen: a laptop, tablet or a family member's phone. Then scan it with your phone's PalGate app and switch back to GateDelay. It finishes linking automatically.
   - The exact flow depends on the Phase 0 results. If the PalGate app supports scanning from a photo, a screenshot is enough.
3. The account appears in the list. Repeat for more accounts if needed.
4. **Settings → Gates**: assign **Main**, **Entry** and **Exit** from the list. Tap **Test** on each one and check that the right gate moves.
5. **Settings → Sequences**: check ENTER (Main → 10 s → Entry) and EXIT (Exit → 27 s → Main) and adjust the delays.
6. Back on the home screen, use **ENTER** / **EXIT**. On iPhone, keep the app open until the sequence finishes.

### Troubleshooting
| Message | Meaning / fix |
|---|---|
| Secondary device not authorized | The gate admin must enable *Linked Device* for your user (see above). |
| Unauthorized (401) | The link was removed in the PalGate app, or the token is invalid. Remove the account and link it again. |
| Linking never completes | Make sure you used *Linked Devices* / *Device Linking* in PalGate, not the regular share. Also check that the account has a free linked-device slot (max 2). |
| Gate opens, but only sometimes | Check that the phone's clock is set to automatic. |
| The second gate didn't open on iPhone | The app was sent to the background during the wait. Keep it on screen (fixed by widget/launch flow in Phase 2). |

---

## 7. Risk deep-dive: "the QR code is on the same phone"

### What we know (sources: ha-palgate `config_flow.py`, pylgate `examples/generate_linked_device_session_token.py`, OpenApp PalGate docs, ha-palgate issues #12/#15/#32)
- Linking works like a meeting point. The app makes up a random uuid, shows it as a QR code, and calls `GET un/secondary/init/{uuid}`. That call **waits until someone scans the code** and then returns the token. Neither ha-palgate nor pylgate sets a timeout.
- Whoever sends that request gets the token. Nothing ties it to the device showing the QR. So the QR can be shown **anywhere**: a PC screen, a tablet, a printout. **A second screen will definitely work.** Every known integration (HA, Homebridge, pylgate CLI, OpenApp) shows the QR on one device and scans it with the phone.
- No source mentions **scanning from an image or gallery** in the PalGate app. Every guide says to point the camera at the code, so **assume it can't scan a screenshot**.
- OpenApp's integration polls every 1–2 s, and its sessions expire after about 15 min. It also offers **"Enter credentials manually"** as a fallback, which confirms manual import is a normal fallback.
- Security: until the scan happens, the uuid works like a password. Generate the QR **locally** and never send the uuid to an online QR service.

### What's still unknown (`tools/link_probe.py` answers both)
1. How long the server keeps the waiting request open (`timeout` mode).
2. Whether re-requesting the same uuid after the scan still returns the token (`resume` mode). This matters most on **iOS**, where our app is suspended about 5 s after you switch to the PalGate app.

### Linking options in the app, best first
| # | Flow | Works on | Depends on |
|---|---|---|---|
| A | **Second screen.** GateDelay shows the QR and a "Show on another screen" share button (image file or AirDrop). You scan it with your phone's PalGate app, then return to GateDelay. | Android ✅ (the waiting request keeps running in a foreground service). iOS ✅ if `resume` works, or if the wait runs in a **background `URLSession`** download task, which iOS completes even while the app is suspended. | a second screen |
| B | **Another GateDelay phone links for you.** A family member's GateDelay shows the QR. Your PalGate app scans it. Their phone receives your credentials and shows a one-time transfer QR, which your GateDelay scans with its camera. | both | a second phone with the app; more code; the token briefly sits on another phone |
| C | **Manual import.** Phone number + token + token type, pasted from `link_probe.py link`, the pylgate CLI or an existing Home Assistant setup. | both | a PC |

**Decision:** v1 ships **A + C**. B goes to Phase 3, and only if A turns out to be painful. Whatever the probe shows, linking has a working path. It costs at most one extra step.

### Run the probe (≈10 min, uses one linked-device slot)
```powershell
pip install requests qrcode pillow
python tools/link_probe.py timeout   # don't scan; just wait and report the time
python tools/link_probe.py resume    # scan the QR shown on the PC when asked
```
Afterwards, remove the test link in PalGate (menu → *Device Linking*) if you want the slot back. You can also keep it: `link` mode gives credentials you can import into the app later.
