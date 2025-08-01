Below is a complete **developer concept for the first-release IntelliJ/JetBrains-platform plug-in that integrates doloc**.
It captures every decision we just agreed on and is organised so a developer (or a small team) can start coding immediately.

---

## 1  Project scope & target

| Item                        | Decision                                                                                                                                                                                                                                                                                              |
| --------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Supported IDEs**          | All products based on **IntelliJ platform 2023.2 (LTS)** and **2024.1 (latest stable)**. Avoid APIs introduced after 2023.2 so the same binary runs on both.                                                                                                                                          |
| **Languages / formats**     | **XLIFF 1.2 & XLIFF 2.0** only. Other formats (JSON, Android XML) will be added in later versions.                                                                                                                                                                                                    |
| **Key user workflows (v1)** | 1️⃣ *Manual*: context-menu **“Translate with doloc”** on one or multiple selected `.xlf/.xliff` files.<br>2️⃣ *Reminder*: on saving a supported file the plug-in performs a quick scan; if **untranslated units** are detected it shows a toast with a **Run** button. No automatic translation runs. |
| **Application policy**      | Translated content **silently overwrites** the original file. If the file is *not* under VCS, show a pre-flight warning dialog; otherwise rely on Git/SVN undo.                                                                                                                                       |
| **Progress UX**             | Each translation run executes as an **IntelliJ “Background Task”** with a cancellable progress bar and per-file status. Errors raise a red balloon & entry in *Event Log*.                                                                                                                            |
| **Monetisation**            | The plug-in is **free on JetBrains Marketplace**. Usage is billed by doloc via API tokens. An anonymous free tier is supported through server-minted tokens (see §5).                                                                                                                                 |

---

## 2  Architecture overview

```
             ┌────────────────────────────────────────┐
             │   IntelliJ Platform 2023.2+/2024.1     │
             ├────────────────────────────────────────┤
             │  UI layer                              │
             │  • AnAction: TranslateWithDoloc        │
             │  • SaveFileListener → ToastReminder    │
             │  • SettingsConfigurable (doloc)        │
             ├────────────────────────────────────────┤
             │  Service layer                         │
             │  • DolocSettingsService (token, opts)  │
             │  • XliffScanner (lightweight DOM)      │
             │  • DolocRequestBuilder                 │
             │  • DolocBackgroundTask                 │
             ├────────────────────────────────────────┤
             │  Persistence                           │
             │  • IntelliJ Password Safe (token)      │
             │  • PropertiesComponent (anon-UUID)     │
             └────────────────────────────────────────┘
```

Implementation language: **Kotlin** + Gradle IntelliJ Plugin (“gradle-intellij-plugin”).

---

## 3  File-type handling

### 3.1 Registration

Declare file types (`xlf`, `xliff`) and reuse the platform XML PSI for lightweight parsing.

### 3.2 Untranslated-unit scan for the toast

Parse only `<trans-unit>`/`<unit>` headers and quickly stop when encountering:

* empty `<target>`
* `<target>` identical to `<source>`
* state attributes ∈ doloc default `untranslated` lists (XLIFF 1.2 default: `new,needs-translation,needs-l10n,needs-adaptation,no-state_target-equals-source,no-state_empty-target`; XLIFF 2.0 default analogous).
  The scan is **synchronous** and must finish in < 50 ms for typical files.

### 3.3 Context-menu scope

`AnAction` appears when:

* user selects ≥ 1 file and *all* are XLIFF, **or**
* selection is inside the Project tool-window on a single XLIFF.

The action collects the files and launches one Background Task that streams them sequentially.

---

## 4  Settings UI

Accessible via **Settings → Tools → doloc**.

| Control                            | Type                                               | Notes                                                                                       |
| ---------------------------------- | -------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| **API token**                      | password field + “Remove” button                   | Hidden chars; stored via `PasswordSafe`.                                                    |
| **Free-tier status**               | label                                              | Shows remaining quota if header `X-Doloc-Remaining` is returned.                            |
| **Untranslated filter**            | multiselect check-list                             | Pre-filled with XLIFF default state names; OR combinations only, mirroring doloc DNF rules  |
| **New state**                      | radio buttons (`translated`, `final`, `unchanged`) | Values sent via `newState=` query parameter.                                                |
| **Show reminder toast after save** | checkbox (on by default)                           |                                                                                             |

No free-form text fields: every option maps to safe enum values.

Configuration is *IDE-level* (affects every project).

---

## 5  Authentication & quota workflow

### 5.1 First run (no token stored)

1. **Call `POST /api/tokens/anonymous`**.
   *Response*: `{ "token": "...", "quota": 1000 }`.
2. Save token to Password Safe & PropertiesComponent (for reuse outside Settings UI).
3. Continue translation call.

### 5.2 Quota exceeded

* If any doloc request returns **HTTP 429** or header `X-Doloc-Quota: exhausted`, immediately show a modal dialog:

  > “Your free translation quota is used up. Create a free doloc account to continue.”
  > Buttons: **Create account** / Cancel
* **Create account** opens the default browser at
  `https://doloc.io/account?upgradeToken=<token>&utm_source=intellij&utm_medium=plugin&utm_campaign=auto_localizer&utm_content=quota_dialog`
  (backend upgrades the exact same token; no copy-paste needed).

### 5.3 Opt-in to personal/prod token

Users can at any time paste a personally generated token (via Settings); the plug-in overwrites the anonymous one and stops hitting the anonymous endpoint.

---

## 6  Translation request lifecycle

```kotlin
val qs = buildQueryString(
    untranslated = settings.untranslated, // if not default
    newState = settings.newState          // if not default
)

val request = HttpRequest.newBuilder()
    .uri(URI("https://api.doloc.io$qs"))
    .header("Authorization", "Bearer ${settings.apiToken}")
    .header("Accept", "application/octet-stream")
    .POST(BodyPublishers.ofFile(xliffPath))
    .build()
```

* **Per-file** upload → response → overwrite same path.
* Catch IO/HTTP exceptions; surface message in Event Log & red balloon.
* If response body is empty but status 200, treat as no changes.

---

## 7  Version-control safety

Before writing the translated bytes:

```kotlin
if (!ChangeListManager.getInstance(project).isUnderVcs(xliffPath)) {
    val ok = Messages.showYesNoDialog(
        project,
        "File “${xliffPath.fileName}” is not under version control. "
      + "Overwrite anyway?",
        "doloc Translation",
        "Overwrite",
        "Cancel",
        Messages.getWarningIcon()
    )
    if (ok != Messages.YES) return@runTask
}
```

---

## 8  Testing strategy

| Layer               | Tests                                                                                                       |
| ------------------- | ----------------------------------------------------------------------------------------------------------- |
| **Parser**          | JUnit parameterised tests with minimal XLIFF snippets verifying untranslated detection & mutilation safety. |
| **Request builder** | MockWebServer verifying proper query strings & headers for all settings permutations.                       |
| **End-to-end**      | Use doloc staging endpoint or fixture server returning canned translated files. Run on CI (GitHub Actions). |

---

## 9  Build & release checklist

1. **Gradle**: `org.jetbrains.intellij` plugin, `platformVersion = "232"`; `intellij.type = IC`.
2. **Plugin ID**: `io.doloc.intellij`.
3. `plugin.xml` permissions:

    * `<depends>` platform modules only.
    * `<applicationService>` DolocSettingsService.
    * No statistics permission needed (we do *not* use PermanentInstallationID).
4. Continuous delivery pipeline:

    * Tag pushes → Gradle `publishPlugin` to Marketplace, channel `default`.
    * `since-build="232.0"`, `until-build=""`.
5. Verify plugin on 2023.2, 2024.1 and Android Studio Hedgehog (based on 2023.2).

---

## 10  Release plan (indicative)

| Week | Milestone                                                                         |
| ---- | --------------------------------------------------------------------------------- |
| 1    | Repository scaffolding, Gradle setup, file-type recognition, context-menu action. |
| 2    | Anonymous token handshake, basic API call & overwrite path.                       |
| 3    | BackgroundTask UI + progress, error handling, VCS warning.                        |
| 4    | Save-listener toast with untranslated scan.                                       |
| 5    | Settings panel (token, options).                                                  |
| 6    | Integration & manual QA on 3 IDE versions.                                        |
| 7    | Marketplace listing assets, docs & first-release submission.                      |

---

### References

* Default XLIFF 1.2 option values (`untranslated`, `newState`)&#x20;
* General options mechanism, DNF explanation&#x20;
* Quick “How it works” overview for doloc API flow&#x20;
* Token creation and storage guidance (end-user docs)&#x20;

---

**With this document a Kotlin-savvy JetBrains plug-in developer can start coding right away—and you have a clear path for future VS Code support or deeper IntelliJ inspections once v1 is in users’ hands.**
