# GateDelay

Opens PalGate gates in a timed sequence:

- **ENTER**: Main gate → wait 10 s → Entry gate
- **EXIT**: Exit gate → wait 27 s → Main gate

Gates, order and delays are configurable. There are small buttons to open a single gate, a home-screen widget, and launcher shortcuts.
Android now; iOS is step 2 (needs a Mac). See [PLAN.md](PLAN.md) for the design and the iOS/widget roadmap.

> Unofficial. It uses PalGate's private API (ported from [ha-palgate](https://github.com/doron1/ha-palgate) / [pylgate](https://github.com/DonutByte/pylgate)). PalGate may change the API at any time.

---

## 1. Build & install (Windows, Android Studio)

**From Android Studio:** *File → Open* → select the `gate_delay` folder → wait for Gradle sync → choose the **composeApp** run configuration → select your phone → ▶ Run.

**From a terminal:**
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"
.\gradlew :composeApp:assembleDebug          # APK: composeApp\build\outputs\apk\debug\composeApp-debug.apk
.\gradlew :composeApp:installDebug           # install on the connected phone (USB debugging on)
.\gradlew :shared:jvmTest                    # protocol + sequence tests
```

**Give it to other people (Android):**
1. In Android Studio: *Build → Generate Signed App Bundle / APK → APK*, then create a keystore. Keep the keystore and its passwords safe: future updates must be signed with the same key.
2. Send the `.apk` (WhatsApp, Drive, …). On their phone: open it → allow "Install unknown apps" for that app → Install.
3. Alternatively, use Google Play Console → *Internal testing*, which supports up to 100 testers by email. This needs a one-time $25 developer account.

## 2. First-time setup (each user, on their own phone)

**Before you start:** your PalGate user must be allowed to use *Linked Devices* on each gate. The gate admin enables it in the PalGate app: *Gate Settings → Manager Options → Users → (you) → Linked Device*. Each account can have about **2** linked devices.

1. Open GateDelay → **⚙ Settings → Link account**. A QR code appears.
2. The PalGate app can't scan its own screen, so show the QR somewhere else. Tap **Show on another screen** and send it to a laptop, tablet or a family member's phone, then open it there.
3. On **your** phone open the PalGate app → menu → **Device Linking** (*Linked Devices* on Android) → **Link a device** → scan the QR.
4. Switch back to GateDelay. It shows "✓ Linked" within a few seconds.
5. **Gates**: tap **Choose** for **Main**, **Entry** and **Exit** and pick from the list. Gates with two outputs appear as separate entries, e.g. "(output 2)". Tap **Test** on each one and check that the right gate moves.
6. **Sequences**: check ENTER (Main → 10 → Entry) and EXIT (Exit → 27 → Main) and adjust them if needed.
7. Done. Use **ENTER** / **EXIT** on the main screen. While a sequence runs you can tap **Open now** to skip the wait, or **Cancel**.

*Alternative to steps 1–4:* **Import manually**, using phone, token and token type from `python tools/link_probe.py link`, pylgate or a Home Assistant setup.

**Several accounts:** link each one the same way. Each gate remembers which account it opens through.

## 3. Widget & shortcuts

- **Widget:** long-press the home screen → *Widgets* → **GateDelay** → drag it out. It has big **ENTER** / **EXIT** buttons, small **Main / Entry / Exit** buttons and a status line ("EXIT: opening Main in 12s"). Sequences run in the background, so you don't need to open the app.
- **Shortcuts:** long-press the GateDelay icon → ENTER / EXIT / Open Main gate. You can drag these to the home screen too.
- While a sequence runs, a notification shows progress with a **Cancel** button.

## 4. Troubleshooting

| Message | Fix |
|---|---|
| *Linked devices are not allowed for this user…* | The gate admin must enable **Linked Device** for your user (see above). |
| *Not authorized – re-link this account* | The link was removed in PalGate. Remove the account in Settings and link it again. |
| Linking keeps waiting | Make sure you used **Device Linking → Link a device** in PalGate, not "Add new device". Also check that you have a free linked-device slot: remove old ones in PalGate → Device Linking. |
| *The gate controller is offline ("check device")* | The PalGate controller has no connection. Try again later or use the PalGate app. |
| Gate list is empty | Tap **Refresh** in Settings → Gates. If an account shows an error, it's printed above the list. |
| Second gate didn't open | If the first gate fails, the second one is deliberately not opened. The status card shows why. |

## 5. Project layout

```
shared/       Kotlin Multiplatform logic (no UI): PalGate protocol, token generator, storage interfaces, SequenceRunner
  commonTest/ token test vectors from pylgate + sequence tests (mock HTTP, virtual time)
composeApp/   Compose Multiplatform UI (commonMain) + Android: service, Glance widget, shortcuts, Keystore storage
tools/        link_probe.py – PalGate linking test / credential tool (Python)
```
