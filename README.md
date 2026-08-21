# Power Nap — Android App

Alarm-clock countdown timer with Bluetooth audio routing. Built in Kotlin, styled with the Jarvis design system.

---

## Setup

### Prerequisites
- Android Studio Hedgehog (2023.1) or newer
- JDK 17+
- Android SDK with API 26–34 installed

### Open in Android Studio
1. **File → Open** → select the `PowerNap/` folder
2. Let Gradle sync (it downloads dependencies automatically)
3. Connect a device or start an emulator (API 26+)
4. **Run → Run 'app'**

### First-time Gradle wrapper
If you open the project outside Android Studio:
```bash
cd PowerNap
gradle wrapper --gradle-version 8.4
./gradlew assembleDebug
```

---

## Features

| Feature | Detail |
|---|---|
| Quick-add buttons | 1 · 5 · 10 / 25 · 50 · 90 min, two rows |
| Timer display | MM:SS, large monospace, with progress bar |
| Alarm sounds | 4 sounds from system RingtoneManager (alarm tones first, notification fallback) |
| Bluetooth routing | Auto-detects A2DP/SCO device; routes `USAGE_MEDIA` to BT, `USAGE_ALARM` to speaker |
| Background timer | Foreground service — fires even when screen is locked |
| Snooze | 9 minutes, button appears only when alarm rings |
| Wake screen | Acquires `SCREEN_BRIGHT_WAKE_LOCK` on alarm |

---

## Design system

Styled with **Jarvis**, a personal dark-mode design system (precise, high-contrast, one accent color reserved for what matters):

| Token | Hex | Role |
|---|---|---|
| Void | `#080C14` | App background |
| Slate | `#111826` | Cards / button fill |
| Signal | `#00C8FF` | Primary accent, active states |
| Frost | `#E8EFF8` | Primary text |
| Mist | `#8A9BB5` | Secondary text |
| Border | `#1E2D45` | Dividers, button outlines |

Font: system `monospace` (timer display) + system `sans-serif` (body text). JetBrains Mono and Inter can be substituted via `res/font/` downloadable font resources.

---

## File map

```
app/src/main/
├── java/com/deansak/powernap/
│   ├── MainActivity.kt      — UI, state management, BT detection
│   └── TimerService.kt      — Foreground service: countdown, audio, notifications
├── res/
│   ├── layout/activity_main.xml
│   ├── values/
│   │   ├── colors.xml       — Jarvis tokens
│   │   ├── strings.xml
│   │   └── themes.xml       — Button styles, Material theme override
│   └── drawable/
│       ├── circle_dot.xml           — BT status indicator (inactive)
│       └── circle_dot_active.xml    — BT status indicator (active)
└── AndroidManifest.xml
```

---

## Permissions requested

| Permission | When | Why |
|---|---|---|
| `FOREGROUND_SERVICE` | Always | Background countdown |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | API 34+ | Service type declaration |
| `WAKE_LOCK` | Always | Wake screen on alarm |
| `POST_NOTIFICATIONS` | API 33+ | Timer + alarm notifications |
| `BLUETOOTH_CONNECT` | API 31+ | Enumerate BT audio devices |
| `BLUETOOTH` | API ≤30 | BT device check |

---

## Known limitations

- Sound names in the chip buttons come from `RingtoneManager` — they vary by device and ROM.
- On emulators without a Bluetooth stack the BT status always shows "PHONE SPEAKER" (expected).
- `FULL_WAKE_LOCK` (used for screen wake) is deprecated; the behaviour is unchanged on current Android versions.

---

## License

MIT — see [LICENSE](LICENSE).
