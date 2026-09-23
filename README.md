# Auto Localizer: XLIFF and ARB translation for JetBrains IDEs

Auto Localizer is a JetBrains plugin that translates XLIFF (`.xlf`) and ARB (`.arb`) files with one click using the [doloc](https://doloc.io/?utm_source=github&utm_medium=referral&utm_campaign=jetbrains_readme) translation service. 
Use it for Angular XLIFF or Flutter ARB files while keeping localization in your repository. It uses existing translations as context and supports both XLIFF 1.2 and 2.0.

**Try it with your own file:** install the plugin, right-click an XLIFF or ARB file, and select `Translate with Auto Localizer`. You can start without registering an account.

See the [JetBrains integration guide](https://doloc.io/getting-started/frameworks/intellij-idea/?utm_source=github&utm_medium=referral&utm_campaign=jetbrains_readme) for setup and format-specific details.

## What It Does

- Adds a `Translate with Auto Localizer` action for XLIFF/ARB files in the Project view
- Translates segments based on configurable translation states such as `initial` or `new` (or `missing`/`empty` for ARB)
- Can remind you about untranslated texts when XLIFF/ARB files changes
- Supports forever free with an anonymous token for up to 200 texts; see [current allowances and plans](https://doloc.io/pricing/?utm_source=github&utm_medium=referral&utm_campaign=jetbrains_readme)

## How To Use It

1. Install the plugin from the [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/27860-auto-localizer).
2. Right-click an XLIFF/ARB file and choose `Translate with Auto Localizer`.
3. Open `File | Settings | Tools | Auto Localizer` to configure translation behavior, save notifications, and an optional API key for larger quotas.
