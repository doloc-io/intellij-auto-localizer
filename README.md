# IntelliJ Auto Localizer

Auto Localizer is a JetBrains plugin that translates XLIFF (`.xlf`) and ARB (`.arb`) files with one click using the [doloc](https://doloc.io) translation service. 
It reuses existing translations to produce context-aware results and supports both XLIFF 1.2 and 2.0.

## What It Does

- Adds a `Translate with Auto Localizer` action for XLIFF/ARB files in the Project view
- Translates segments based on configurable translation states such as `initial` or `new` (or `missing`/`empty` for ARB)
- Can remind you about untranslated texts when XLIFF/ARB files changes
- Works for free without registration for files with up to 100 texts

## How To Use It

1. Install the plugin from the [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/27860-auto-localizer).
2. Right-click an XLIFF/ARB file and choose `Translate with Auto Localizer`.
3. Open `File | Settings | Tools | Auto Localizer` to configure translation behavior, save notifications, and an optional API key for larger quotas.
