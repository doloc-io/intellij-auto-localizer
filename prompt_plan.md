Below is a **three-layer work plan** for *AutoLocalizer* followed by a **ready-to-run prompt series** you can feed to a code-generation LLM (e.g. another GPT) to implement the plug-in step-by-step.

---

## 1  Blueprint — high-level phases

| Phase                          | Goal                                                    | Key deliverables                                                      |
| ------------------------------ | ------------------------------------------------------- | --------------------------------------------------------------------- |
| **P1 — Scaffold**              | Stand-up a runnable IntelliJ-Platform plug-in skeleton. | Gradle project, `plugin.xml`, CI template.                            |
| **P2 — Core services**         | Provide shared plumbing.                                | Settings service (Password Safe), token manager, HTTP util.           |
| **P3 — Anonymous auth**        | Support free-tier anonymous tokens.                     | `/tokens/anonymous` handshake, quota headers parsing.                 |
| **P4 — XLIFF basics**          | Recognise XLIFF files & detect untranslated units.      | File-type registration, lightweight scanner.                          |
| **P5 — Single-file translate** | Translate one file via menu.                            | `TranslateWithDolocAction`, overwrite logic, VCS safety check.        |
| **P6 — Multi-file + progress** | Batch translation with UI feedback.                     | BackgroundTask, cancellable progress, error balloons.                 |
| **P7 — Save reminder toast**   | Nudge user on save.                                     | `VirtualFileListener`, toast with **Run** button.                     |
| **P8 — Settings UX**           | Give users control.                                     | Swing configurable with enum controls for `untranslated`, `newState`. |
| **P9 — Quota & upgrade path**  | Handle 429 and guide upgrade.                           | Modal dialog → browser with upgrade link `{token}`.                   |
| **P10 — Release**              | Ship & maintain.                                        | Marketplace artifacts, changelog, test matrix, docs.                  |

---

## 2  Iterative chunks → atomic steps

Each chunk (C#) is ≤ 1 dev-day; each step (S#.#) is \~30-90 mins.

### C1 — Scaffold project

* **S1.1** Init Gradle project with `org.jetbrains.intellij` plugin.
* **S1.2** Add `plugin.xml` skeleton (`id`, `name`, `since-build=232`).
* **S1.3** Configure run-IDE Gradle task; verify plugin loads.
* **S1.4** Commit `.editorconfig` & ktlint.
* **S1.5** Set up GitHub Actions workflow running `gradle test` & `runPluginVerifier`.

### C2 — Core services

* **S2.1** Create `DolocSettingsService` (application-level).
* **S2.2** Persist token via Password Safe; expose Kotlin Flow for changes.
* **S2.3** Implement `HttpClientProvider` using JDK 11 `HttpClient`.
* **S2.4** Centralise doloc base URL + query builder util.

### C3 — Anonymous token flow

* **S3.1** Add `AnonymousTokenManager` that lazily calls `/tokens/anonymous`.
* **S3.2** Unit-test manager with MockWebServer.
* **S3.3** Wire manager into `DolocSettingsService` (fallback when no token).
* **S3.4** Parse `X-Doloc-Remaining` & expose via service.

### C4 — XLIFF recognition & scan

* **S4.1** Register `XliffFileType` (inherits `LanguageFileType(XMLLanguage)`).
* **S4.2** Implement `LightweightXliffScanner` (SAX) detecting: empty target, identical src/target, default state list.
* **S4.3** Write parameterised tests for scanner.

### C5 — Single-file translate

* **S5.1** Add `TranslateWithDolocAction` visible for single XLIFF file.
* **S5.2** Send file via HTTP util; handle 200 response → overwrite file.
* **S5.3** Show warning dialog if file not under VCS (ChangeListManager).
* **S5.4** Surface success/error via balloon.

### C6 — Multi-file & BackgroundTask

* **S6.1** Extend action to allow multi-selection.
* **S6.2** Create `TranslateBatchTask` (Backgroundable) iterating files.
* **S6.3** Add progress fraction + cancel support.
* **S6.4** Aggregate failures → one balloon summary.

### C7 — Save-reminder toast

* **S7.1** Register `VirtualFileManagerListener`.
* **S7.2** On `contentsChanged`, run `LightweightXliffScanner`.
* **S7.3** If untranslated found, show `Notification` with *Run* action that triggers `TranslateWithDolocAction` for that file.
* **S7.4** Debounce to avoid duplicate toasts.

### C8 — Settings UI

* **S8.1** Build `DolocConfigurable` panel.
* **S8.2** Add password field (token) + “Remove”.
* **S8.3** Add enum controls for `untranslated` (checklist) & `newState` (radio).
* **S8.4** Validate & persist; update query builder tests.

### C9 — Quota exceeded dialog

* **S9.1** Intercept HTTP 429 / quota header.
* **S9.2** Show modal dialog; *Upgrade* opens browser `https://doloc.io/account?upgradeToken=…`.
* **S9.3** Log metric to Event Log.

### C10 — Release engineering

* **S10.1** Add `gradle-intellij-plugin` `publishPlugin` config.
* **S10.2** Write plugin description, changelog, icons.
* **S10.3** Update CI to build, verify, and publish on tag.
* **S10.4** Manual QA on 2023.2, 2024.1, Android Studio Hedgehog.
* **S10.5** First Marketplace submission.

---

## 3  Prompt series for a code-generation LLM

Each block is a self-contained prompt. Feed them sequentially; the generated code from one prompt becomes the base for the next.

> **Important:** replace `{{projectDir}}` with your repo root when you run these prompts.

---

### **Prompt 1 — create scaffold**

```text
You are writing Kotlin code for an IntelliJ-Platform plug-in named “AutoLocalizer”.

Task:
1. Initialise a Gradle project using the ‘org.jetbrains.intellij’ plugin.
2. Target IntelliJ `IC 232.*` (2023.2 LTS) and `IC 241.*`.
3. Add `plugin.xml` with:
   - id: io.doloc.intellij.auto-localizer
   - name: Auto Localizer
   - vendor: Doloc
   - since-build: 232
4. Provide Gradle tasks: `runIde`, `test`, `verifyPlugin`.

Output:
* `build.gradle.kts`
* `settings.gradle.kts`
* `src/main/resources/META-INF/plugin.xml`
```

---

### **Prompt 2 — settings service & HTTP util**

```text
Starting from the scaffold in {{projectDir}}, implement core services.

1. Create `DolocSettingsService` (ApplicationService) that:
   - Stores `apiToken` in IntelliJ Password Safe.
   - Publishes a `MutableStateFlow<String?>` called `tokenFlow`.

2. Add `HttpClientProvider` (singleton) returning a configured `java.net.http.HttpClient` with 30s timeout.

3. Unit test:
   - Write `DolocSettingsServiceTest` verifying token save & retrieve.
```

---

### **Prompt 3 — anonymous token manager**

```text
Extend AutoLocalizer.

1. Add `AnonymousTokenManager`:
   - On `suspend fun getOrCreateToken(): String`:
       * If `DolocSettingsService` has token ⇒ return it.
       * Else POST to `https://api.doloc.io/tokens/anonymous`.
       * Parse JSON `{token, quota}`; save token to settings.
2. Mock this endpoint in tests with okhttp `MockWebServer`.
3. Update `DolocSettingsService` so `apiToken` getter calls manager when null.
```

---

### **Prompt 4 — XLIFF file type & scanner**

```text
Add XLIFF support.

1. Register `XliffFileType` extending `LanguageFileType(XMLLanguage)` for extensions `xlf`, `xliff`.
2. Implement `LightweightXliffScanner.scan(File): Boolean` returning true if:
   - any `<target>` empty OR equals its `<source>`, OR
   - attribute `state` in set {new, needs-translation, needs-l10n, needs-adaptation}.
   (stream parse with SAX or StAX).
3. Tests: provide two sample files (+ one negative case) in `testResources` and assert detection.
```

---

### **Prompt 5 — single-file translation action**

```text
Add context menu action.

1. Create `TranslateWithDolocAction` (AnAction).
2. Visible if exactly one `XliffFileType` selected.
3. On `actionPerformed`:
   - Build query string from settings (`untranslated`, `newState`).
   - Send HTTP POST with file bytes and Bearer token.
   - On 200 overwrite file.
   - If file not under VCS, show warning dialog first.
4. Show green balloon on success, red on error.
5. Add integration test using MockWebServer that returns a modified XLIFF.
```

---

### **Prompt 6 — multi-file & BackgroundTask**

```text
Enhance translation.

1. Expand `TranslateWithDolocAction` to accept multiple selected XLIFF files.
2. Implement `TranslateBatchTask` (Backgroundable) that:
   - Processes files sequentially.
   - Updates progress fraction `i / total`.
   - Supports cancel.
3. Aggregate errors; show a single summary balloon.
```

---

### **Prompt 7 — save reminder toast**

```text
Add reminder.

1. Register a `BulkFileListener` (or `VirtualFileListener`) for `contentsChanged`.
2. If file is XLIFF and `LightweightXliffScanner` detects untranslated units:
   - Show Notification balloon “Untranslated strings found – Run AutoLocalizer?” with action button.
3. Clicking action triggers `TranslateWithDolocAction` for that file.
4. Debounce identical notifications within 10 s.
```

---

### **Prompt 8 — settings UI**

```text
Create UI panel.

1. Implement `DolocConfigurable`:
   - Password field for Token (hidden chars) + Remove button.
   - Check-list for `untranslated` states (pre-checked defaults).
   - Radio group for `newState`: translated / final / unchanged.
   - Checkbox “Show reminder toast”.
2. Bind components to `DolocSettingsService`.
3. Persist changes immediately on Apply.
```

---

### **Prompt 9 — quota exceeded handling**

```text
Handle free-tier limits.

1. Intercept responses in TranslateWithDolocAction util:
   - If status 402
2. show modal dialog:
   “Quota used up. Upgrade?” with buttons Upgrade / Cancel.
3. Upgrade button opens browser at
   `https://doloc.io/account`.
```

---

### **Prompt 10 — release pipeline**

```text
Finish project.

1. Add Gradle `publishPlugin` with Marketplace token env var.
2. Update GitHub Actions:
   - Build matrix: {IC 232, IC 241}.
   - Run Plugin Verifier.
   - On tag `v*` publish artifact.
3. Supply `README.md`, `CHANGELOG.md`, 128×128 and 40×40 icons.
4. Ensure tests green.
```

---

Each prompt *builds directly on the previous code base*—no orphaned pieces—and marches the project forward in safe, reviewable increments.
