# Changelog

## 1.0.1

- **Retries when there's no connection.** If a gate command fails because the phone has no internet, for example while it reconnects to cellular between gates, the app retries every 2 seconds for up to 60 seconds. The screen, widget and notification show "retrying". PalGate errors such as "not authorized" are still not retried.
- Releases are now signed with the same key as the first builds that were shared directly, so they install as an update without unpairing.

## 1.0.0

First release.

- **ENTER** (Main → 10 s → Entry) and **EXIT** (Exit → 27 s → Main) sequences, with a live countdown, **Open now** and **Cancel**.
- Small single-gate buttons for Main, Entry and Exit.
- Home-screen widgets: a blue **ENTER** widget and an orange **EXIT** widget.
- App shortcuts: long-press the app icon.
- Several PalGate accounts, linked by QR (Linked Device) or imported manually.
- Sequences keep running in the background, with a notification that has a Cancel button.
- Tokens are stored encrypted with the Android Keystore.
