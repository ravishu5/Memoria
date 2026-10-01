# Mini Screenpipe UI — Memoria reference adaptation

The supplied showcase uses hierarchy and density to make a complex recorder feel calm. Its key pattern is a nearly black canvas, subtly lighter cards, thin slate borders, violet interaction cues, and screenshots as the primary visual content. The redesign applies this consistently to the existing Android app.

## Visual system

- Background: `#0B0E14`; surface: `#121722`; elevated surface: `#1A2230`; border: `#2A3345`.
- Primary actions: violet `#6366F1`, with a restrained violet-to-periwinkle gradient. Active navigation and links use light lavender. Green is reserved for active capture status.
- Text: near-white primary, cool-gray secondary. Native sans-serif with medium/bold headings; compact captions and explicit section labels replace large repeated headings.
- Cards use 12–14 dp corners and one-pixel-density outlines. Shared padding, line icons, input fields, pills, and toggle colors unify the screens. Native ripple feedback remains available.
- Both dark and light palettes are implemented. Dark is the default when no appearance preference has been saved; explicit appearance choices are preserved.

## Screen translation

1. **Home**: compact capture status and start/pause control, a natural-language search field, today's actual capture counts and sampled app-time distribution, app icons, and two-column recent screenshot cards. Empty history shows a real setup state.
2. **Timeline**: Today/Yesterday/All/date controls, a local keyword field, day headings, time labels along a vertical rail, app icons, concise OCR excerpts, and screenshot thumbnails. Each moment opens the viewer.
3. **Search**: a prominent outlined question composer, clear history/day scope, useful suggested prompts, and user/assistant answer cards with working evidence references. Existing send-preview and cancellation behavior is retained.
4. **Settings**: compact grouped rows with line icons, secondary descriptions, chevrons, and violet switches. Gemini configuration uses the same form/action styling. App selection retains installed-app icons and searchable names.
5. **Memory viewer**: the captured image is the focal point, with source/time metadata, selectable recognized text, Copy, Ask about this moment, and confirmed deletion.
6. **Capture setup**: permission cards explain accessibility, visible capture controls, and local storage before starting capture.

## Product fidelity

The reference's navigation and layouts are adapted to the features actually implemented here. Four bottom destinations—Home, Search, Timeline, Settings—keep Gemini recall in one search/conversation flow. Dashboard counts and previews come from local history. Sampled time is labeled as an estimate. Privacy text distinguishes local capture from user-confirmed Gemini text sharing. Synthetic screenshots used for visual checks exist only in the disposable emulator; production retains its secure-window protection.
