# Aevra for Android

A small **Kotlin** Android app for remembering what you saw and which apps you used. Inspired by Screenpipe's local capture → searchable history workflow; implemented independently for Android rather than porting its desktop recorder.

## Included

- Full-resolution, on-device Latin-script OCR of screenshots, with debounced screen-change capture and periodic sampling while unlocked. OCR runs on a worker thread; exact unchanged screens reuse recognized text. Sparse/failed OCR can use visible accessibility text, with source and quality labels.
- Local SQLite history with Unicode token-prefix search, day selection, individual screenshot/text review, and a non-destructive upgrade from version 1.
- Sampled app-time estimates (not exact usage tracking).
- Gemini questions and summaries with structured answers, validated clickable observation references, explicit inferences and evidence gaps, cancellation, and bounded retries for rate limits/server errors.
- Start/pause, a capture notification with Pause, configurable app exclusions and capture interval.
- Seven-day default auto-delete, with 1/7/14/30/60/90-day and Never options. Screenshots use WebP with selectable 480p/720p/1080p short-edge resolution or Original, optional grayscale, after full-resolution OCR; identical image assets share storage. Consecutive unchanged moments coalesce while retaining first/last timestamps and sample counts. A 500 MB screenshot cap evicts old images while keeping searchable text until retention expires.
- Individual/all-history deletion, JSON export, and all-screenshot ZIP export with a history manifest through Android's file picker. Diagnostics export contains only configuration/counts, never OCR, screenshots or keys.
- Dedicated Gemini settings with model ID, entered-key visibility, [Get from AI Studio](https://aistudio.google.com/app/apikey), and a synthetic connection test.
- System/light/dark appearance, optional biometric/device-credential app lock, selected-app capture, and optional foreground capture service. Exclusions always override selected apps.
- API key encryption using Android Keystore; no cloud backup; the app's own screen is protected and excluded.

## Build

Requires Android Studio with JDK 17 and Android SDK 35. Minimum device version is Android 11 (API 30).

Open this folder in Android Studio, let Gradle sync, and run `app`, or:

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. Install it on your own phone with Android Studio or `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## First use

1. Open Aevra and read the capture disclosure under **Start capture**.
2. Enable **Aevra capture** in Android Accessibility settings. For sideloaded apps Android may require App info → menu → Allow restricted settings first.
3. Return to the app. In **Settings**, add package fragments for private/banking apps you want excluded. The defaults are conservative but cannot recognize every sensitive app.
4. Tap **Start capture** and allow notifications. Capture requires notifications enabled. Use other apps for at least one capture interval (30 seconds by default).
5. Return to **Timeline**. Select a day, search a word/app, or open a moment.
6. Add your Gemini API key in **Settings**. The model is configurable (default `gemini-3.5-flash-lite`); select a model supported by your account. Use the Home **Search your memories** bar for a natural-language question across all retained history, or choose the selected-day/keyword scope. Review the evidence preview and confirm sending. The Search screen also supports selected-day scope, while Timeline provides local keyword filters. Tap **Get from AI Studio** to create/copy your key in your browser; sign-in may be needed. **Say hello to Gemini** tests the saved model/key using synthetic text only.

Do not paste your API key into source files or Git. This personal app calls Gemini directly with your key. A public production app should use a backend/Firebase AI Logic and app attestation instead of distributing an embedded credential.

## Capture behavior and limits

The user explicitly enables Android's accessibility service. It is paused initially. After you tap Start capture, that choice is saved synchronously and survives service restart/reboot until you explicitly pause; capture resumes only with saved consent and available notifications. Android 14+ uses `takeScreenshotOfWindow` for the active window. Android 11–13 uses display screenshots; overlays may appear. The app skips locked screens, its own UI, settings/system UI, configured excluded packages, visible excluded windows, and accessibility trees containing password fields. Secure Android screens may reject screenshots and are not bypassed. Detection cannot guarantee that every sensitive screen is filtered: pause capture when needed. No microphone, audio, key logging, or automated taps.

Capture is a sample, not full video. Short activities between samples may be missed. OCR currently recognizes Latin script; it may not read every language or image correctly. The optional foreground service runs only during user-started capture and stops on pause; it does not guarantee survival of manufacturer background restrictions. Privacy mode locks the app when it goes into the background and requires configured biometrics or a device screen lock. A device manufacturer may stop background accessibility services; check the app's capture status. Retention is enforced on opening the app, during recorder maintenance, and by a persisted Android JobScheduler task approximately every 15 minutes. Android may delay background jobs; deletion is not guaranteed at an exact time. Never disables age-based text/image deletion but keeps the 500 MB image cap. The image cap is also checked after capture. Grayscale/resolution changes apply only to new captures; existing screenshots remain unchanged. Changing capture settings invalidates in-flight screenshots, applies the new options, and resumes if capture was already running. Grayscale often reduces file size, but the savings depend on image content. The default is color at 720p. Source/quality labels are heuristics, not OCR confidence scores. Text is in app-private SQLite; screenshots are in app-private files. They are not independently encrypted at rest beyond Android device storage encryption. Exported files and text sent to Gemini are outside local deletion control.

Gemini sends **text only**, on demand after confirmation, capped at 160 selected moments and 40,000 characters. Candidates combine coverage across the chosen time range with question-relevant matches; excerpts prioritize relevant lines and are sent chronologically. A preview shows the actual text to send. Responses must cite selected observation IDs; unsupported IDs or malformed responses receive one repair attempt before being rejected. Up to three total requests may occur for repairs or retryable failures. Timeouts are not automatically replayed. Citation validation establishes that referenced moments exist, not that every interpretation is correct. Busy days can still have missing or excerpted evidence; review clickable references in History. An API key, network access, supported model, and available quota are required. No history from before installation is available.

The app uses a native dark/light UI with four bottom destinations: Home, Search, Timeline, and Settings. Home provides activity summaries and recent screenshots; Timeline offers local date/keyword filtering; Search provides Gemini recall and cited answers. Dedicated Gemini setup, capture setup, and a screenshot viewer share the same visual system. See [the design notes](DESIGN.md). Source files are in `app/src/main/java/dev/miniscreenpipe/` and are all Kotlin.

## Tests

`./gradlew testDebugUnitTest lintDebug` checks context bounds and day coverage, question relevance, citation validation, query tokenization, merge boundaries, and invalid input. `./gradlew connectedDebugAndroidTest` on an emulator/device also checks SQLite filtering, escaped queries, date ranges, retention, deletion, privacy defaults, Keystore key round-trip, and actual screenshot/OCR capture with pause, exclusion, and password-field filtering. The standard History/CaptureFlow instrumentation tests clear app history; run them only on an emulator or test installation. StorageV2Test uses isolated databases and covers migration, coalescing, shared image deletion, prefix search, and image-cap eviction that retains OCR. PhysicalDeviceSmokeTest preserves existing history and is explicitly selected with `-e useSavedKey true` when testing with an already saved key. Run the reconnect helper after phone instrumentation exits.

Manual device verification: enable the service, start capture, switch to a readable app, wait an interval, find its screenshot/text, pause and confirm no new observations, verify a password field and excluded app are skipped, lock the phone, test notification Pause, export JSON, and make a Gemini request with a valid key.

## Reference

[Screenpipe](https://github.com/screenpipe/screenpipe) was explored with **jCodeMunch MCP** to inspect its paired capture loop, event debounce, privacy filters, bounded conversation context, and evidence-focused meeting prompts. Those patterns informed the independent Android implementation. No Screenpipe code was copied.

Android screenshot API: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
OCR: https://developers.google.com/ml-kit/vision/text-recognition/v2/android
Gemini REST: https://ai.google.dev/api/generate-content

Default Gemini model: [Gemini 3.5 Flash-Lite](https://ai.google.dev/gemini-api/docs/models/gemini-3.5-flash-lite). The model can be changed in Settings.

### After running phone instrumentation tests

Android force-stops the target process when an instrumentation test finishes. This can leave its accessibility binding marked as crashed even when the test passed. Recover the enabled service **after the test command exits**, outside the test runner:

```sh
python3 scripts/reconnect-service.py --serial YOUR_ADB_SERIAL
```

The helper preserves other enabled accessibility services and reconnects Aevra while preserving its saved Start/Pause choice. Alternatively, switch Aevra's accessibility permission off and on. Reopen the settings page if Android continues showing a cached malfunction warning.


## v0.6: Aevra retrieval and Gemini review

Your Aevra branding, icons, Gemini model selector and answer UI are preserved. Search and Settings now persist the same selected model; earlier `gemini_model` selections are migrated. Questions retrieve local BM25-ranked evidence before any Gemini call, with date filters, duplicate suppression and relevant OCR excerpts. Evidence is limited to 24 topic observations or 48 overview samples, 18,000 characters and 24 KB UTF-8. An unmatched topic does not send unrelated history. Read [RETRIEVAL.md](RETRIEVAL.md) for the pipeline and practical limits, and [CAPTURE_ANALYSIS.md](CAPTURE_ANALYSIS.md) for the comparison against your Screenpipe notes.

The 160-observation/40,000-character limits described for older releases above have been replaced by these smaller retrieval budgets. Gemini model availability/quota still depends on the API account.


### v0.7.0 capture recovery

Chrome recording was verified on the connected Android 15 phone using the public Example Domain page. Recording no longer silently stops after a capture-setting change, service restart, or accessibility feedback interruption. Hidden password controls no longer suppress an otherwise ordinary screen; visible password fields still block capture. Status messages identify excluded apps, password guards, browser guards, locked screens, and protected windows.

`ChromeCaptureTest` is a separately selected, non-destructive phone test: it opens a public Chrome page, checks screenshot/OCR storage, captures a separate test app, checks setting/reconnection recovery, and verifies visible password protection. It removes only observations it created and restores the settings it temporarily changed. Like all Android instrumentation, its teardown can disconnect accessibility; run the reconnect helper afterwards.


### Branding and installation identity

The launcher, app UI, accessibility service and notifications use Aevra. Exports are named `aevra-*.json` / `aevra-screenshots.zip`; Settings and diagnostics derive their version from the installed package. The Gradle project is named Aevra. The Android application ID/Kotlin namespace remain `dev.miniscreenpipe` so existing installs, accessibility component bindings, local history and the Keystore key continue to work. The legacy `mini-screenpipe-v2` export format identifies the data schema and stays compatible with older exports.

Capture diagnostics save the last status and pause reason without saving page text or the API key. `CurrentPageCaptureTest` explicitly resumes Chrome's existing task without supplying a URL, checks a screenshot file and OCR text from the current page, retains that requested capture, and leaves recording enabled. After instrumentation, `scripts/reconnect-service.py --serial SERIAL --keep-screen` restores the enabled service without opening Aevra over the current screen.
