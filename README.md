# PeekPreview

A background Android utility that shows a floating message preview when you long-press a conversation row in supported messaging apps — without opening the chat. Similar to iOS Messages' long-press peek.

Built with Kotlin, Jetpack Compose, and Material 3 Expressive (M3 1.4+). No root required. Sideload-friendly.

**Supported apps**
- Google Messages (`com.google.android.apps.messaging`)
- Facebook Messenger (`com.facebook.orca`)
- Instagram Direct (`com.instagram.android`)

---

## Screenshots

> Add screenshots here once the app is running on a device.

---

## How it works

1. An `AccessibilityService` watches the supported apps for a long-press event.
2. On trigger, it walks the accessibility node tree of the pressed row and extracts sender + message snippet.
3. A `ComposeView` hosted in a `WindowManager` overlay (`TYPE_APPLICATION_OVERLAY`) draws the peek bubble on screen.
4. The bubble auto-dismisses after 3 seconds (or on `TYPE_WINDOW_CONTENT_CHANGED`).
5. A low-priority foreground service keeps a persistent "PeekPreview is running" notification so the user knows the feature is active.

---

## Requirements

| Tool | Version |
|------|---------|
| Android Studio | Ladybug (2024.2) or newer |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.1.0 |
| Compile SDK | 35 |
| Target SDK | 34 |
| Min SDK | 26 (Android 8.0) |
| Material3 | 1.4.0 (expressive APIs) |

> **Note on Material 3 Expressive:** The expressive APIs (`MaterialShapes`, expressive shape/motion tokens) require `androidx.compose.material3:material3` **1.4.0 or newer**. This version is pinned explicitly in `gradle/libs.versions.toml` — it is **not** pulled from the Compose BOM — so the expressive `@OptIn` annotations work project-wide.

---

## Building

### 1. Clone the repo

```bash
git clone https://github.com/Prannsss/PeekPreview.git
cd PeekPreview
```

### 2. Open in Android Studio

File → Open → select the `PeekPreview` folder. Android Studio will:
- Generate the Gradle wrapper automatically
- Sync and download all dependencies
- Index the project

Or build from the command line (requires JDK 17+ on PATH):

```bash
./gradlew assembleDebug
# APK lands at: app/build/outputs/apk/debug/app-debug.apk
```

### 3. Install on a device

Connect a device with USB debugging enabled, then:

```bash
./gradlew installDebug
# or manually:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> The app is designed for sideloading. It does not need a Play Store signing config.

### 4. Run the unit tests

```bash
./gradlew test
```

Tests live in `app/src/test/` and cover the text-extraction heuristics (`NodeExtractionUtils`).

---

## Granting permissions

The app walks you through all three permissions on first launch. You can also grant them manually:

### Accessibility Service
Settings → Accessibility → Installed apps → PeekPreview → Enable

Or via ADB (useful for testing):
```bash
adb shell settings put secure enabled_accessibility_services \
  com.peekpreview/com.peekpreview.service.PeekAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

### Display over other apps
Settings → Apps → PeekPreview → Display over other apps → Allow

Or via ADB:
```bash
adb shell appops set com.peekpreview SYSTEM_ALERT_WINDOW allow
```

### Notifications (Android 13+)
Settings → Apps → PeekPreview → Notifications → Allow

---

## Project structure

```
app/src/main/
  AndroidManifest.xml
  java/com/peekpreview/
    MainActivity.kt                     # entry point, permission lifecycle
    data/
      PeekPreferences.kt                # DataStore: master toggle + per-app booleans
    service/
      PeekAccessibilityService.kt       # long-press detection, surface gating
      PeekForegroundService.kt          # persistent status notification
      OverlayManager.kt                 # WindowManager + ComposeView bubble
    ui/
      theme/
        Color.kt                        # M3 curated indigo palette (pre-Android-12 fallback)
        Type.kt                         # expressive type scale overrides
        Shape.kt                        # expressive corner shape tokens
        Theme.kt                        # dynamic color on 12+, fallback below
      screens/
        HomeScreen.kt                   # master toggle, per-app switches, permissions
        PermissionSetupScreen.kt        # 3-step onboarding pager
      components/
        ToggleCard.kt                   # hero spring-animated toggle card
        PermissionStatusRow.kt          # check/warning row with Fix deep-link
        PeekOverlayPreview.kt           # PeekBubble composable + in-app demo
    util/
      TargetApps.kt                     # SUPPORTED_APPS list + package constants
      PermissionUtils.kt                # permission checks + Settings deep-links
      NodeExtractionUtils.kt            # node tree walker + structural-label filter
  res/
    xml/accessibility_service_config.xml
    drawable/                           # adaptive icon assets
    values/strings.xml
```

---

## Debugging / tuning Meta apps

Messenger and Instagram are React Native and expose accessibility trees that vary significantly between app versions. A **Developer** section appears in the Home Screen on debug builds with a **Log node tree to Logcat** toggle.

1. Build and install the debug APK.
2. Enable **Log node tree to Logcat** in the Developer section.
3. Open the target app (Messenger or Instagram Direct), long-press a conversation row.
4. Filter logcat:
   ```bash
   adb logcat -s PeekPreview
   ```
5. You'll see the full node tree printed — class names, resource IDs, text, contentDescriptions, and clickability flags — for whatever the current installed version exposes.
6. If previews come back empty, look at the dump and adjust:
   - **`NodeExtractionUtils.isStructuralLabel`** — if real text is being filtered out, loosen the timestamp/emoji patterns.
   - **`INSTAGRAM_DM_HINTS`** in `PeekAccessibilityService` — if Instagram DM surface detection is failing (peek doesn't fire at all on IG), add whatever `viewIdResourceName` or class name appears in the dump near the DM inbox/thread.
   - **`selectSenderAndBody`** — if sender and body are swapped, the ordering heuristic (first piece = sender, longest-of-rest = body) may need a package-specific override.

---

## Known limitations

- **Preview quality depends on what each app exposes.** Google Messages tends to work most reliably. Messenger and Instagram DM previews depend on the accessibility tree each app version surfaces — this is not a stable contract and can change with any app update.
- **Long-press detection** relies on a two-tier fallback (see `PeekAccessibilityService`): `TYPE_VIEW_LONG_CLICKED` first, then `TYPE_VIEW_ACCESSIBILITY_FOCUSED` on a clickable row, because RecyclerView rows frequently swallow the long-click event. Behavior may vary by device/OEM.
- **Bubble dismissal is timeout-based** (3 seconds). An accessibility service cannot observe raw touch-up events on another app's views, so there's no reliable "finger released" signal. `TYPE_WINDOW_CONTENT_CHANGED` also triggers a dismiss.
- **No Play Store distribution.** The `FOREGROUND_SERVICE_SPECIAL_USE` type (required on Android 14+ for this use case) requires Play policy justification. This app is intended for personal/sideloaded use.

---

## Forking & contributing

Contributions are welcome. Some good starting points:

### Add a new target app
1. Add a `TargetApp` entry to `SUPPORTED_APPS` in `util/TargetApps.kt`.
2. Add the package name to `packageNames` in `res/xml/accessibility_service_config.xml`.
3. If the app is navigation-heavy (like Instagram), add a `isDmSurface` case in `PeekAccessibilityService` with hints specific to that app's DM surface.
4. Test with the node-tree debug log.

### Make the target app list user-configurable
The comment in `accessibility_service_config.xml` explains the approach: remove the `packageNames` attribute entirely and instead set `AccessibilityServiceInfo.packageNames` at runtime from a user-editable list stored in `PeekPreferences`. That keeps the APK static while letting users add apps without a rebuild.

### Improve Meta app extraction
The heuristics in `NodeExtractionUtils` are intentionally conservative. If you've confirmed a better extraction strategy for a specific Messenger or Instagram version, open a PR with the dump that motivated the change as a comment.

### UI / theme changes
The M3 Expressive theme is in `ui/theme/`. The shape tokens (`ToggleCardShape`, `PeekBubbleShape`) and the type-scale overrides in `Type.kt` are the main knobs. Dynamic color is on by default for Android 12+; the fallback palette seed is `#4C5BD4` (calm indigo) in `Color.kt`.

### Fork workflow
```bash
# 1. Fork on GitHub, then:
git clone https://github.com/<your-username>/PeekPreview.git
cd PeekPreview

# 2. Create a feature branch
git checkout -b feature/my-improvement

# 3. Make changes, run tests
./gradlew test

# 4. Push and open a PR against main
git push origin feature/my-improvement
```

---

## License

MIT — do whatever you want, just don't remove the attribution.
