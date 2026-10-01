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
