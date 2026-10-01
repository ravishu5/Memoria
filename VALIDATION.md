# Validation

Verified on 1 October 2026:

- `assembleDebug`: passed; installable APK generated.
- `testDebugUnitTest`: 4 tests passed (context ordering, truncation, request bound, invalid input).
- `connectedDebugAndroidTest`: 3 tests passed on the Pixel_10a Android 17 emulator. These cover actual screenshot capture and OCR, pause, excluded apps, password-screen filtering, SQLite search/date filtering/retention/deletion, and Keystore encryption.
- `lintDebug`: passed with non-blocking target-SDK/dependency-update warnings.
- Installed the final APK and launched the History screen; verified its controls and empty state through the Android UI hierarchy.

Remaining unverified: the Android 11–13 display-capture fallback. Physical-phone capture and a live Gemini response were subsequently verified as described below. Device-specific accessibility/background restrictions may differ from the emulator. The Gemini model is configurable; the default is `gemini-3.5-flash-lite`.

## Physical phone verification

Installed the APK on the connected A142 phone running Android 15 (API 35). A dedicated non-destructive device smoke test passed in approximately 47 seconds:

- Captured a test activity's screenshot and recognized its visible phrase with OCR.
- Verified pause, package exclusion, and password-field filtering prevent additional captures.
- Successfully called Gemini from the phone using the user's supplied key and received an answer based only on the synthetic test observation.
- Saved the key encrypted with Android Keystore and deleted temporary plaintext input.
- Restored the default capture interval and exclusions, removed test observations, and left capture paused.

The physical device test requires an explicitly staged app-private key input, or explicit selection with `-e useSavedKey true` to use the already encrypted key; otherwise it skips itself. Do not put credentials in source files or test arguments. The standard history tests clear app history and should be used only on test installations; the physical smoke test preserves pre-existing history.

## Accessibility service recovery

After the physical instrumentation run, Android's process exit report showed `FORCE STOP` with `finished inst`; Accessibility Manager retained the enabled service in its crashed set with a dead connection. There was no recorded Java exception for Mini Screenpipe. Reconnecting only its enabled accessibility component restored the bound service and cleared the crashed-service set. Added `scripts/reconnect-service.py` and documented the required post-instrumentation recovery. The service starts paused on reconnection.

Verified the recovered service remained bound with an empty crashed-service set across four checks over 30 seconds. The refreshed permission details page showed the normal service description in place of the malfunction warning. Recording remained paused and the encrypted Gemini key was preserved.

## Version 0.2 robustness verification

- Kotlin build (`assembleDebug`, `assembleDebugAndroidTest`) and lint passed. Lint reports only target-SDK, newer dependency, and translation warnings.
- All 11 JVM tests passed: bounded chronological context, broad time coverage, relevant excerpts, citation validation and injection resistance, merge rules, safe Unicode search input, normalization, and invalid input.
- All 5 selected Android tests passed on Pixel_10a Android 17: non-destructive v1 database migration, unchanged-moment coalescing, shared WebP assets and deletion, prefix search, screenshot-cap eviction retaining OCR, existing retention/privacy/Keystore checks, and actual capture/OCR with pause, exclusion and password filtering.
- Device testing caught an Android FTS4 build without enhanced `AND` syntax. Queries now use adjacent quoted prefix terms for conjunction, verified through actual Android searches and migrated-history tests.

The Android 11–13 screenshot fallback remains unverified. OCR quality labels are heuristics; citation validation confirms references exist but does not prove interpretation accuracy.

### Version 0.2 on the connected A142 phone

Installed version 0.2.0 with `adb install -r`, preserving app data and the encrypted saved API key. The selected non-destructive physical smoke test passed in 42.5 seconds: real screenshot/OCR capture, pause, app exclusion, password-screen filtering, and a live Gemini structured response with a valid test-observation citation. Only synthetic screen text was sent to Gemini. Test observations and the helper APK were removed. The service was reconnected after instrumentation and capture left paused.

## Version 0.3 settings and history search

- Added dedicated Gemini setup: entered-key visibility toggle, model ID, encrypted-key preservation, synthetic connection check, and browser link to `https://aistudio.google.com/app/apikey`.
- Added grayscale/short-edge screenshot resolution (480p, 720p, 1080p, Original), auto-delete choices including Never, app selection with exclusions taking precedence, appearance choices, biometric/device-credential app lock, foreground capture, screenshot ZIP export with manifest, and sanitized diagnostics.
- The top History question bar defaults to all retained history, independently of the day/keyword filter, and supports an explicit selected-day scope. Existing evidence preview, bounded context and citation validation remain in place.
- Build, lint and all 11 unit tests passed. Ten distinct emulator tests passed across feature/regression runs: image conversion with preserved original pixels and no upscaling; Never/30-day retention and ZIP image mappings; selected apps cannot bypass exclusions; natural-language UI retrieves earlier-day evidence; exact AI Studio key-page intent; locked startup hides history; plus existing migration, storage, capture, privacy and Keystore tests. Layouts were reviewed from synthetic emulator screenshots in dark mode.
- Android accepted and ran the persisted retention job via JobScheduler on the emulator. Background scheduling remains approximate and may be delayed by Android.
- The selected non-destructive physical test passed on the A142 Android 15 phone in 42.1 seconds. It verified an active foreground capture service, stored short-edge resolution at most 480 pixels, OCR, pause/exclusions/password filtering, and a live structured Gemini response with a valid synthetic observation citation. Temporary settings and observations were restored/removed.

Successful biometric/device-credential authentication was not automated; the locked startup gate was verified. OCR remains Latin-script only. Grayscale savings vary with image content. Storage settings apply to new captures. Exports stream records and images to avoid loading a full history into memory.

The streamed-export feature tests passed after the final export change. Installed the final APK on the phone, removed the test helper, and confirmed version 0.3.0, a bound Mini Screenpipe accessibility service absent from the crashed set, paused recording, and the retained encrypted API key. The temporary emulator was shut down.

## Version 0.4 visual redesign

Adapted the supplied Memoria showcase into the native Kotlin app: navy surfaces, cool gray text, violet gradients and accents, consistent bordered cards, native line icons, bottom Home/Search/Timeline/Settings navigation, compact capture status, real usage summaries, thumbnail memory cards, timeline rails, cited answer cards, grouped settings, capture setup, and a dedicated screenshot viewer. Dark is the default when no appearance choice has been saved; existing explicit appearance preferences are preserved. The launcher icon uses the same violet palette.

Build, lint and all 11 unit tests passed. Both affected emulator UI tests passed, including natural-language search across retained days, evidence preview, exact AI Studio key-page navigation, screenshot-viewer navigation, Android Back returning to the timeline, and the locked-startup privacy gate. Synthetic answer and screenshot fixtures were used only for layout review; they are not live Gemini responses. Final rendered screenshots are saved in `preview/`, and design analysis is in `DESIGN.md`.

This version changes presentation and navigation; existing capture/storage/Gemini implementations remain in use. Their prior device verification is recorded above. No personal history or key was sent during visual testing.

Installed final version 0.4.0 on the A142 phone. Confirmed Mini Screenpipe is bound and absent from the crashed-service set, capture is paused, and the encrypted API key is retained. App data was preserved through the update. The temporary emulator was shut down after final preview capture.

## v0.6.0 — local retrieval and Gemini model review (2026-10-01)

Preserved the user's Aevra name/icon assets, Flash-Lite model choices and answer UI. Fixed Search writing the wrong model preference, migrated existing selections, snapshotted each request's model/key and prevented cancelled model tests from overwriting newer request state. Explicit single-moment questions retain the selected ID, including three-digit IDs.

Replaced the newest-300 keyword cutoff and large mixed evidence prompt with local FTS4-statistics BM25 ranking across scoped matches, a bounded 128-candidate heap, 24 focused excerpts or 48 time-distributed overview observations, date parsing/intersection, duplicate suppression within half-hour windows, overlapping chunk selection and actual 18,000-character/24,000-byte UTF-8 evidence limits. Empty topic retrieval does not send unrelated history. Structured citation validation remains mandatory; unsupported/missing evidence is explicit.

Validation:
- Final app/test build and Android lint passed. All 33 JVM tests passed, including BM25 rarity/length normalization/saturation, date/DST intersection, safe FTS syntax, old evidence retrieval, citation injection, empty retrieval, chunk-boundary cropping, repeated visits, explicit record selection, capture cadence and Unicode byte-budget/time coverage.
- Eleven selected emulator tests passed: isolated retrieval/model migration (2), OCR recovery/lifetime (3), actual capture/password/exclusion/URL/redaction (1), UI/history search/AI Studio/privacy (2), image/retention/export/app selection (3). Four final targeted emulator tests then passed, adding a real Search model-picker assertion and rechecking retrieval.
- Five isolated phone tests passed for retrieval/model preference migration and OCR timeout/error ownership. The first physical capture smoke run failed before recording because Android retained duplicate short/full accessibility component aliases. Diagnosed the binding state and process exit reasons; no app Java crash was reported. Normalizing both aliases in the recovery script restored the bound service, and the phone smoke test setup now handles both aliases too.

See RETRIEVAL.md for scope, corpus-statistics details, sources and limitations; CAPTURE_ANALYSIS.md documents the Android adaptation of the supplied Screenpipe notes.

The final physical smoke test passed in 42.847 seconds: actual phone screenshot/OCR, active foreground notification, configured grayscale/480p storage, pause, app exclusion, password-screen protection, production local retrieval bounded to synthetic fixture observations, and a live Gemini structured answer with a verified fixture citation. Existing history and encrypted key were preserved; test observations were removed and preferences restored. The test setup now canonicalizes short/full component aliases, executes valid settings arguments directly and verifies the written service value before waiting for binding. This resolves the initial setup failures without changing the app's capture logic.

Final cleanup: removed `dev.miniscreenpipe.test`, recovered the normal service using the alias-safe helper, confirmed Android's bound-service record labeled `Aevra capture` with an empty crashed-service set, version 0.6.0/code 6, paused recording, retained encrypted key/IV, and one canonical Aevra component entry. Android's bound-service dump identifies the service by label rather than including its package name, so the recovery helper now checks the actual bound label as well as its live process. The temporary emulator was shut down.

## v0.7.0 — Chrome capture and recorder recovery (2026-10-01)

The connected A142 phone (Android 15/API 35) had a healthy bound accessibility service but `recording=false`. Before changing the recorder, a separately selected non-destructive test successfully captured the public `https://example.com` page in actual Chrome, establishing that the screenshot/OCR API worked when explicitly started. Capture-setting edits, service connection/destruction, and accessibility feedback interruption were resetting recording.

Changes preserve the explicit Start/Pause choice across settings changes and service reconnection, save that choice synchronously before process shutdown, ignore feedback interruption, skip only visible password fields, and display specific skip reasons. Tree traversal limits still fail closed; protected screens and private/browser filters remain enforced. Android documents `onInterrupt` as an interruption of accessibility feedback: https://developer.android.com/guide/topics/ui/accessibility/views/service.

Final build: `assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug` succeeded. All 33 JVM tests passed. Lint reported no errors (22 existing-style warnings remain). `git diff --check` passed.

`ChromeCaptureTest` passed on the physical phone in 19.581 seconds. It verified a real Chrome screenshot with searchable Example Domain OCR, another app with a hidden password control, settings-change continuity, feedback interruption, active reconnection followed by another stored screenshot, visible-password exclusion, and preservation of explicit Pause through reconnection. The final recording choice was checked against the on-disk preference before instrumentation teardown. An earlier final-resume check exposed the asynchronous preference-write issue; synchronous persistence fixed it and the post-run check confirmed recording remained true.

Installed v0.7.0/code7, removed the test APK, and ran the reconnect helper after instrumentation teardown. Final independent ADB checks: accessibility bound=true, crashed=false, recording=true, encrypted key/IV retained. Existing user history was retained; only newly created test observations were removed. Aevra was left open with recording enabled; its own secure UI is excluded, and switching to an eligible app allows capture.

## v0.7.1 / v0.7.2 — exact Chrome page and Aevra branding (2026-10-01)

The follow-up phone inspection found `recording=false`, notification permission enabled, a healthy bound service, and the user's existing Chrome task. The exact reason for that earlier pause was not persisted, so it cannot be attributed retrospectively to a particular action. `CurrentPageCaptureTest` resumed Chrome's existing task without supplying a URL or replacing its tab. The page had approximately 300 accessibility nodes, depth 18, no visible password fields and no recognized private-tab markers; it captured successfully after recording was explicitly resumed. An initial diagnostic stopped because instrumentation exposed the launcher instead of Chrome; restoring Chrome's existing task corrected the test setup.

v0.7.1 adds persisted status and pause reasons (app, notification or notification-permission failure), including diagnostics export, and a `--keep-screen` reconnect-helper option. The exact-page test requires both a non-empty screenshot file and searchable OCR text. Requested real-page observations are retained, unlike synthetic fixtures.

The branding audit found Aevra correctly applied to the launcher, app screens, notification and accessibility service. Remaining old export filenames, documentation/project names and a stale `0.6.0 (6)` Settings version were corrected in v0.7.2. Installed-package metadata now supplies the Settings/diagnostics version. Self-exclusion derives the app's package from Context. The application ID and namespace remain `dev.miniscreenpipe`, and legacy key/export-schema identifiers remain compatible. The installed accessibility component matches the manifest; the name change did not cause the Chrome capture failure.

Final v0.7.2 build, both APKs, all 33 JVM tests and lint succeeded. APK badging confirmed package `dev.miniscreenpipe`, versionName `0.7.2`, versionCode `9`, application label `Aevra`. The exact-page phone test passed in 4.315 seconds. The helper APK was removed and accessibility reconnected with `--keep-screen`, retaining the Chrome task and recording choice. Existing history and encrypted Gemini key were preserved.
