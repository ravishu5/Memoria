# Comparison with the supplied Screenpipe notes

The attachment mixes desktop Screenpipe details with this Android app's existing implementation. The applicable gaps were implemented in v0.5, and are being revalidated with v0.6 retrieval.

| Item | Android implementation |
| --- | --- |
| Native screenshot capture and buffer ownership | Already present. API 34+ captures the active window; API 30–33 uses the display. A failed/protected window is not retried via display capture. |
| Event state machine | Window/focus and click events; 250 ms scroll settling; 750 ms text settling; 1 s generic content settling; a universal 750 ms screenshot-attempt gap. Continuous content bursts are capped at 1.5 s to avoid capture starvation. No keystrokes or clipboard contents are recorded. |
| Visual change | Adaptive mode checks every 3 s, comparing 96×96 RGB samples to the last committed frame. A ≥5% changed-pixel fraction (channel difference >24) triggers OCR. Changed accessible text also bypasses this filter. |
| Idle fallback | At most 15 s in adaptive mode, or the shorter configured interval. Disabling adaptive mode uses the configured idle interval plus events. Timing is best effort: in-flight OCR, Android scheduling and protected windows may delay samples. Visual probes that fail to meet the threshold are discarded before full fingerprinting/OCR. |
| Filter polling | Eligibility checked every 1 s and on accessibility events, invalidating pending work on app/privacy transitions. Checked again before storage. Password/locked/excluded screens remain skipped. |
| Browser filters | Visible private-tab labels and exposed address-bar IDs; user-defined blocked domains match exact hosts/subdomains, not arbitrary URL substrings. Hidden address bars and private modes cannot always be detected. Excluding the browser package is stronger. |
| SHA-256 | Already present, but is an **exact** fingerprint, not a perceptual hash. Retained for safe OCR reuse. Accessible-text changes invalidate reuse too. |
| OCR timeout/error fallback | Errors already supported accessible text. Timeouts now preserve accessible text with explicit `ocr-timeout` provenance; original pixels live until the asynchronous task finishes, and storage uses an independent copy. A pending timed-out task prevents new neural OCR tasks from accumulating; accessible fallback continues. Without accessible text, the sample is skipped until recovery. |
| Optional PII removal | New opt-in sensitive-text redaction for common emails, phone/card-like number sequences and recognizable API-key formats. Applied before new text storage and outgoing Gemini evidence. It is best effort; screenshot pixels and older stored text are unchanged. Dates and citation IDs are preserved. |
| WebP, grayscale, resolution, coalescing, FTS, retention/cap | Already present. OCR sees original-resolution pixels before storage conversion. Image eviction preserves OCR text. Retention may delete both according to the selected policy. |
| Retrieval/context | Replaced the newest-keyword cutoff and large mixed context with local BM25 and bounded excerpts in v0.6; see RETRIEVAL.md. |
| Desktop video/H.265/native OS pipelines | Desktop-specific. Android retains efficient user-controlled screenshot history; continuous video/audio is outside this mini app's scope. |

Reference: jCodeMunch inspection of Screenpipe's indexed `packages/sdk/recorder-core/src/platform/recorder.rs::paired_capture_loop_for_monitor`, plus [Android accessibility screenshot APIs](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#takeScreenshotOfWindow(int,%20java.util.concurrent.Executor,%20android.accessibilityservice.AccessibilityService.TakeScreenshotCallback)).
