# Toddler Playground

Android kiosk app: floating emojis with real physics, locked with Lock Task Mode,
exit via a parent multiplication gate.

## Build & run
Open the folder in Android Studio (Ladybug or newer) → let Gradle sync → Run.
Command line: generate the wrapper once with `gradle wrapper`, then `./gradlew installDebug`.

## Modes
**Screen pinning (default):** tap "Start locked play session" and confirm the system dialog.
In system settings enable "Ask for PIN before unpinning".

**Device owner (dedicated tablet, full lock):** device must have no accounts.
    adb install -t app-debug.apk
    adb shell dpm set-device-owner com.example.kiosk/.KioskAdminReceiver
Remove (debug builds are testOnly):
    adb shell dpm remove-active-admin com.example.kiosk/.KioskAdminReceiver

## Layout
- `physics/EmojiWorld.kt` – pure-Kotlin engine (no Android deps, unit-testable)
- `EmojiPlayground.kt` – frame loop, multi-touch input, rendering
- `ParentalGate.kt` – exit button + multiplication keypad dialog
- `KioskApp.kt` – start screen / locked playground
- `KioskActivity.kt` – lock task, device-owner policies, Do Not Disturb
