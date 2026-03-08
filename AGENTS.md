# AGENTS Guide

Operational guide for coding agents working in this repository.

## 1) Project Scope
- IntelliJ Platform plugin written in Kotlin.
- Build system: Gradle Kotlin DSL (`build.gradle.kts`).
- Root package: `io.doloc.intellij`.
- Plugin descriptor: `src/main/resources/META-INF/plugin.xml`.
- JVM target: 17.
- IntelliJ target: `IC 2023.2` (`sinceBuild = 232`).

## 2) Cursor / Copilot Rules
- No `.cursor/rules/` directory found.
- No `.cursorrules` file found.
- No `.github/copilot-instructions.md` file found.
- If these files appear later, treat them as repository-specific instructions and follow them.

## 3) Build Tooling Caveat
- `gradle/wrapper/gradle-wrapper.properties` exists and points to Gradle 8.13.
- `gradle-wrapper.jar` is not committed, so `./gradlew` fails in a clean clone.
- Prefer `./gradlew` if wrapper is restored.
- Otherwise use system `gradle` commands.
- To regenerate wrapper artifacts locally (if `gradle` exists):
  - `gradle wrapper --gradle-version 8.13`

## 4) Build / Test / Verify Commands
Use `./gradlew` when available; otherwise replace with `gradle`.

### Core
- Build everything: `gradle build`
- Run all tests: `gradle test`
- Run full checks: `gradle check`
- Run IDE sandbox: `gradle runIde`
- Build plugin ZIP: `gradle buildPlugin`
- Verify plugin: `gradle verifyPlugin`

### Single-Test Execution (important)
- Single class:
  - `gradle test --tests "io.doloc.intellij.xliff.TargetLanguageHelperTest"`
- Single method (stable example without backticks):
  - `gradle test --tests "io.doloc.intellij.action.TranslateWithDolocActionTest.testTranslateAction"`
- Package or subset wildcard:
  - `gradle test --tests "io.doloc.intellij.xliff.*"`

### CI Parity
- CI workflow runs: `gradle test --no-daemon`
- Release workflow runs: `gradle buildPlugin --no-daemon`

## 5) Lint / Formatting Status
- No dedicated lint tool is configured (no ktlint, detekt, spotless, or checkstyle config).
- No repository `.editorconfig` is present.
- Apply standard Kotlin formatting and keep diffs minimal.
- At minimum before handing off changes, run:
  - `gradle test`
  - `gradle check` (when environment allows)

## 6) Architecture Map
- Translation action entrypoint: `TranslateWithDolocAction`.
- API request construction: `DolocRequestBuilder`, `DolocQueryBuilder`.
- HTTP client singleton: `HttpClientProvider`.
- Settings/state and credentials: `DolocSettingsService`, `DolocSettingsState`.
- Anonymous token lifecycle: `AnonymousTokenManager`.
- XLIFF scanning: `LightweightXliffScanner`.
- Target-language helpers: `TargetLanguageHelper`.
- Save reminder listener: `DolocFileListener`.

## 7) Code Style Expectations

### Imports
- Prefer explicit imports.
- Keep import order stable using IDE optimize-imports behavior.
- Avoid introducing wildcard imports unless already established in that file.

### Formatting
- 4-space indentation, no tabs.
- Keep lines readable; split long argument lists across lines.
- Keep Kotlin brace/spacing style consistent with current files.
- Avoid unrelated formatting-only edits.

### Types and Nullability
- Prefer immutable `val`; use `var` only for real mutable state.
- Encode optional values with nullable types explicitly.
- Use guard clauses early (`?: return`, `if (...) return`).
- Use focused data classes for structured payloads.

### Naming
- Types (`class`, `object`, `enum`): `PascalCase`.
- Functions/properties/locals: `camelCase`.
- Constants: `UPPER_SNAKE_CASE` for true constants.
- Boolean names should read as predicates (`isX`, `hasX`, `showX`).
- Tests use both patterns currently:
  - backtick descriptive names (JUnit),
  - `test...` methods in `BasePlatformTestCase` classes.

### Error Handling
- Do not silently ignore exceptions at integration boundaries.
- If user feedback is needed, catch, log, and notify.
- Use IntelliJ logging (`logger<T>()` or `Logger.getInstance(...)`).
- Include actionable context in failures (HTTP status, brief reason).
- Re-throw `ProcessCanceledException`.

### Threading and IntelliJ APIs
- Keep blocking or heavy work off the UI thread.
- Use `Task.Backgroundable` / pooled threads for background operations.
- Perform VFS writes in `runWriteAction`.
- Perform UI updates on EDT (`invokeLater` / `invokeAndWait`).
- Save open documents before translation writes.

### Networking and Secrets
- Reuse `HttpClientProvider.client`.
- Build API requests through `DolocRequestBuilder`.
- Keep tokens in `PasswordSafe` via `DolocSettingsService`.
- Never log token values or other secrets.

### XLIFF-Specific Rules
- Preserve behavior for XLIFF 1.2 and XLIFF 2.0.
- Keep scanner logic lightweight (SAX-style parsing with early exits).
- Respect configurable untranslated-state semantics.
- For parser changes, add tests for both XLIFF versions.

### Tests
- Test sources: `src/test/kotlin`.
- Test fixtures: `src/test/resources/xliff`.
- Use MockWebServer for HTTP behavior.
- Use `BasePlatformTestCase` for IntelliJ platform integration paths.
- When changing action flow, scanner behavior, or request construction, update/add tests.

## 8) Change Guidance for Agents
- Keep patches focused; avoid opportunistic refactors.
- If plugin wiring changes, verify `plugin.xml` registrations.
- If settings schema/behavior changes, update both state and UI configurable.
- If API contract/query params change, update builders and tests together.
- For user-facing errors, keep notification text clear and log details once.
- Update "description" and "change-notes" in `plugin.xml` for user-visible changes.

## 9) Pre-PR Checklist
- Code compiles (best effort in the current environment).
- Relevant tests are added/updated.
- `gradle test` run, or blocked reason is stated explicitly.
- No credentials/secrets are committed.
- User-visible behavior changes include plugin metadata/docs updates when appropriate.
