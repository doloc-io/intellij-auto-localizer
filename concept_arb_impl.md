# ARB Support Implementation Plan

## Objective

Implement ARB support with deterministic base resolution, project-level ARB overrides, ARB save reminders, and strict mixed-selection validation, while keeping the existing XLIFF flow intact.

## Existing Files to Extend

### Core action / orchestration
- `src/main/kotlin/io/doloc/intellij/action/TranslateWithDolocAction.kt`

### Request building
- `src/main/kotlin/io/doloc/intellij/api/DolocRequestBuilder.kt`
- `src/main/kotlin/io/doloc/intellij/api/DolocQueryBuilder.kt`

### Settings
- `src/main/kotlin/io/doloc/intellij/settings/DolocSettingsState.kt`
- `src/main/kotlin/io/doloc/intellij/settings/DolocConfigurable.kt`

### Reminder listener
- `src/main/kotlin/io/doloc/intellij/listener/DolocFileListener.kt`

### Plugin metadata
- `src/main/resources/META-INF/plugin.xml`
- `README.md`

### Tests
- `src/test/kotlin/io/doloc/intellij/action/TranslateWithDolocActionTest.kt`
- add new ARB-focused tests under `src/test/kotlin/io/doloc/intellij/arb/...`

## New Types to Add

## ARB parsing / inference
- `src/main/kotlin/io/doloc/intellij/arb/ArbFileAnalyzer.kt`
- `src/main/kotlin/io/doloc/intellij/arb/ArbLocaleHelper.kt`
- `src/main/kotlin/io/doloc/intellij/arb/ArbHeuristics.kt`
- `src/main/kotlin/io/doloc/intellij/arb/FlutterL10nConfigFinder.kt`

## Resolution / planning
- `src/main/kotlin/io/doloc/intellij/arb/ArbPairResolver.kt`
- `src/main/kotlin/io/doloc/intellij/arb/ArbSelectionPlanner.kt`
- `src/main/kotlin/io/doloc/intellij/arb/ArbTranslationJob.kt`

## Project-level overrides
- `src/main/kotlin/io/doloc/intellij/arb/ArbProjectOverridesService.kt`
- `src/main/kotlin/io/doloc/intellij/settings/ArbProjectOverridesConfigurable.kt`

## Reminder support
- `src/main/kotlin/io/doloc/intellij/arb/ArbReminderInspector.kt`

## Optional shared models
- `src/main/kotlin/io/doloc/intellij/translation/SelectionValidationResult.kt`
- `src/main/kotlin/io/doloc/intellij/translation/TranslationKind.kt`

## State Model

Use a project-level persistent state service, not the existing app-level `DolocSettingsState`.

Recommended state shape:

```kotlin
data class ArbProjectOverridesState(
    var scopes: MutableList<ArbScopeOverride> = mutableListOf()
)

data class ArbScopeOverride(
    var scopeDir: String = "",
    var baseFile: String = "",
    var sourceLang: String = "",
    var targetOverrides: MutableList<ArbTargetOverride> = mutableListOf()
)

data class ArbTargetOverride(
    var targetFile: String = "",
    var targetLang: String = ""
)
```

## Storage choice
Implement as a project-level service:

- `@Service(Service.Level.PROJECT)`
- `@State(...)`
- storage in `StoragePathMacros.WORKSPACE_FILE`

Reason:
- these are local project overrides
- they should not leak across projects
- they are best treated as workspace convenience, not shared global config

## Resolution Specification

This section is the implementation contract.

## 1. File-kind validation

In `TranslateWithDolocAction.update()`:
- enable for any selection containing at least one supported file type (`arb`, `xlf`, `xliff`)
- do not reject mixed selections here
- actual validation happens in `actionPerformed()`

In `actionPerformed()`:
- inspect the full selection
- if mixed ARB/XLIFF/unsupported appears, abort with warning/info

This matches the requested UX: show warning/info instead of silently hiding the action.

## 2. ARB base resolution

Add `ArbPairResolver.resolveScope(file, project)`.

Algorithm:
1. walk upward from `file.parent`
2. at each directory:
   - check for saved scope override
   - check for `l10n.yaml`
3. if either exists in that directory:
   - this directory is the active scope
   - if both exist there, saved override wins over `l10n.yaml`
   - stop walking
4. if no scope source found:
   - use heuristic scope

### `l10n.yaml` handling
`FlutterL10nConfigFinder`:
- search nearest ancestor `l10n.yaml`
- parse minimal keys:
  - `arb-dir`
  - `template-arb-file`
- resolve `arb-dir` relative to the directory containing that `l10n.yaml`

### Heuristic fallback
`ArbHeuristics` should:
- prefer Flutter defaults like `app_en.arb`
- support same-directory locale suffix families
- support sibling locale directories with same filename
- only auto-pick when the candidate is unique

## 3. Language resolution

Implement in `ArbLocaleHelper`.

### Source language
1. base file `@@locale`
2. saved scope `sourceLang`
3. filename inference
4. prompt

### Target language
1. target file `@@locale`
2. saved target override for exact file path
3. filename inference
4. prompt

### Conflict policy
If `@@locale` and filename inference disagree:
- prefer `@@locale`
- do not block v1
- log/debug if needed, but keep UX simple

## 4. Prompt fallback

Add ARB-specific prompt UI.

Minimum v1 requirements:
- select base file
- input source language
- input target language
- remember scope checkbox
- remember target-language checkbox

For multi-target one-base selection:
- one shared base/source section
- per-target language fields only where unresolved

Persist remembered values only when the user opts in.

## 5. Selection planning

Add `ArbSelectionPlanner.plan(selectedFiles, project)`.

### Case A: one target selected
- resolve base/langs
- create one job

### Case B: one base selected only
- resolve base scope
- infer target files recursively within that scope
- filter to only targets needing translation
- show confirmation listing inferred target filenames
- create one job per target

### Case C: base + explicit targets selected
- run inference for all selected files
- if a selected file resolves as the base, skip it as a target
- do not auto-add unselected sibling targets
- create jobs for explicit selected targets only

### Case D: many targets selected
- resolve base for each
- group by base
- if more than one base group remains, abort with info for v1
- otherwise continue

## 6. ARB scanning / stale detection

Implement `ArbFileAnalyzer`.

Use `kotlinx.serialization.json.Json` to parse top-level JSON.

### Rules
- keys starting with `@` are metadata
- only top-level string-valued non-`@` keys are translatable
- `@@locale` is metadata
- `@foo` metadata belongs to key `foo`

### Compare base vs target
Need-translation conditions come from app-level ARB settings:
- `missing`
- `empty`
- `equal`

This analyzer is used by:
- reminders
- base-only fan-out filtering
- optional pre-flight validation

## 7. Request building

Extend `DolocRequestBuilder`.

Keep existing XLIFF request method unchanged.

Add new method, for example:
```kotlin
fun createArbTranslationRequest(
    sourceFile: VirtualFile,
    targetFile: VirtualFile,
    untranslated: Set<String>,
    sourceLang: String?,
    targetLang: String?
): HttpRequest
```

### Multipart behavior
Build multipart `form-data` with:
- part `source`
- part `target`

### Query params
Extend `DolocQueryBuilder` with ARB-specific builder:
```kotlin
fun buildArbTranslateQueryString(
    untranslated: Set<String>,
    sourceLang: String?,
    targetLang: String?
): String
```

Only include `sourceLang` / `targetLang` when explicitly resolved and needed.

## 8. Action orchestration

Refactor `TranslateWithDolocAction` to separate:
- selection validation
- XLIFF flow
- ARB flow

Recommended structure:
- keep class name
- add small dispatch layer
- move ARB logic into resolver/planner/helper classes
- avoid a single large `if/else` body

### Batch execution for ARB
When multiple ARB jobs are planned:
- run inside one background task
- progress text should include current target filename
- continue processing remaining targets if one target fails
- report partial failures in final notification

This resolves the pair-vs-batch UX tension cleanly.

## 9. Save reminders

Refactor `DolocFileListener` so it no longer hardcodes only XLIFF behavior.

### For ARB saved target file
- detect `.arb`
- resolve scope and base
- if saved file is not the base and has untranslated content relative to base:
  - notify `Translate this file`

### For ARB saved base file
- detect that the saved file is the scope base
- recursively infer target files in scope
- filter to those needing translation
- notify `Translate to all target files`
- hint includes filenames

### Project resolution
Replace `first open project` behavior with actual owning-project resolution for the file.

Use file-to-project resolution rather than:
- first open project
- arbitrary project fallback

This is required because ARB overrides are project-level.

## 10. Settings changes

## App-level settings page
Modify `DolocSettingsState` and `DolocConfigurable` to add ARB untranslated defaults:
- `missing` enabled by default
- `empty` optional
- `equal` optional

Add explanatory text:
- existing settings on this page are app-level
- ARB base/language overrides are project-level
- project-level overrides are configured separately

## Project-level settings page
Add a new `projectConfigurable` in `plugin.xml`.

Suggested display name:
- `Auto Localizer ARB Overrides`

Configurable behavior:
- constructor receives `Project`
- reads/writes `ArbProjectOverridesService`
- shows saved scope rows and target rows
- supports clear actions only in v1

### Display behavior
Each scope section should show:
- scope directory
- saved base file
- saved source language
- `Clear` action

Each target row should show:
- actual target file path
- target language override
- `Clear` action

Stale file paths should be visibly marked as missing/stale and ignored at runtime.

## 11. Plugin metadata

Update `plugin.xml`:
- action description should no longer say XLIFF-only
- plugin description should mention ARB support
- add project configurable registration
- update change notes

Do **not** register a custom ARB file type.

## 12. Tests

## Unit tests

### `ArbLocaleHelperTest`
Cover:
- `@@locale`
- filename inference
- conflict preference
- missing locale

### `FlutterL10nConfigFinderTest`
Cover:
- nearest ancestor `l10n.yaml`
- relative `arb-dir`
- `template-arb-file`
- submodule layout

### `ArbPairResolverTest`
Cover:
- same-scope saved override beats same-scope `l10n.yaml`
- nearer `l10n.yaml` beats farther saved override
- heuristic fallback
- directory-based locale layouts
- unresolved ambiguity

### `ArbFileAnalyzerTest`
Cover:
- metadata skipping
- missing/empty/equal logic
- blank targets
- nested metadata keys
- non-string value handling

### `ArbSelectionPlannerTest`
Cover:
- base-only selection fan-out
- base + targets selection skipping base as target
- many targets with one base
- many targets with different bases -> abort
- mixed ARB/XLIFF/unsupported -> abort

## Integration / platform tests

### `TranslateWithDolocActionTest`
Extend or split to cover:
- single ARB target translation
- base-only ARB selection with confirmation flow
- explicit ARB multi-target selection
- mixed selection info path
- partial batch failure reporting

### Reminder tests
Add listener-oriented coverage for:
- ARB target save reminder
- ARB base save reminder
- project ownership resolution
- reminder suppression when no translation work exists

## 13. Validation Checklist

Before implementation is considered done:

- selection validator is deterministic
- ARB and XLIFF paths stay isolated
- app-level settings remain intact
- ARB overrides are project-level
- nearest-scope precedence is implemented exactly
- explicit `@@locale` beats saved language hint
- base-only selection fans out only after confirmation
- base+explicit-target selection does not auto-add siblings
- multi-base ARB selection aborts in v1
- listener uses correct project, not first open project
- no custom ARB file type is introduced
- plugin text is updated from XLIFF-only wording

## Recommended Execution Order

1. add project-level ARB override state/service
2. add `l10n.yaml` finder + locale helper
3. add ARB file analyzer
4. add pair resolver + selection planner
5. add ARB request builder path
6. refactor action dispatch to support ARB
7. add ARB save reminder flow
8. add project configurable
9. add app-level ARB untranslated settings
10. update plugin metadata and docs
11. add/finish tests
12. run `gradle test`
13. run `gradle check` if environment allows

## Self-check / Sparring Notes

### Decision: saved override vs `l10n.yaml`
Final answer:
- nearest scope wins
- same-scope saved override beats same-scope `l10n.yaml`

This is more correct than either `always saved first` or `always l10n.yaml first`.

### Decision: target-language overrides
Final answer:
- fallback-only
- do not override explicit `@@locale`

If this were not true, plugin behavior could silently diverge from ARB file metadata and Flutter expectations.

### Decision: mixed selection
Final answer:
- abort with warning/info
- strict by request, even though it is less permissive than some IDE actions

### Decision: base-only manual action
Final answer:
- supported
- confirmation required
- fan-out limited to inferred targets inside the resolved scope

### Decision: selected base + explicit targets
Final answer:
- skip selected base as target
- do not auto-expand to all siblings
- process only explicit targets
