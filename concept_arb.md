# ARB Support Concept

## Goal

Add `.arb` support to the plugin in a way that is clear, deterministic, and consistent with the existing XLIFF flow, while accounting for the fact that ARB translation is always a `base/source file + target file` operation rather than a self-contained single-file translation.

## Scope

### In scope
- Translate ARB target files using a resolved base/source ARB file.
- Support standard Flutter setups automatically.
- Support `l10n.yaml` discovery in subdirectories / modules.
- Provide prompt fallback when auto-resolution is not possible.
- Support ARB save reminders for:
  - target/non-base files: translate this file
  - base files: translate to all inferred target files
- Support multi-file ARB selection with one resolved base group.
- Keep existing application-level settings for tokens, reminders, and XLIFF.
- Add ARB project-level overrides for base and language inference.

### Out of scope for v1
- Processing one selection that resolves to multiple different base groups.
- Rich override editing workflows beyond clearing saved overrides.
- Auto-rewriting ARB `@@locale` values.
- Registering a custom ARB file type.

## Design Principle

The plugin must no longer think in terms of "translate one selected file". It must think in terms of "resolve one or more translation jobs and then execute them".

### Translation job model
- XLIFF job: one file
- ARB job: one `base/source` file plus one `target` file

Internally, ARB batch operations still resolve into per-target jobs, but UX may present them as a single batch.

## Why ARB Needs Different Handling

The current XLIFF flow in `src/main/kotlin/io/doloc/intellij/action/TranslateWithDolocAction.kt` is single-file:
- select one XLIFF
- scan it
- send that file
- overwrite that same file with the response

ARB is different:
- doloc ARB translation expects multipart `source + target`
- the translated output overwrites only the target file
- the source file is an input, not an output

That means the action, request builder, reminder logic, and settings must all become pair-aware for ARB.

## File Kind Rules

### Supported file kinds
- `.xlf`
- `.xliff`
- `.arb`

### Mixed selection behavior
If a selection contains:
- ARB + XLIFF
- ARB + any unsupported file
- XLIFF + any unsupported file

then the action must abort and show a warning/info message.

This is intentionally strict for v1. It avoids partially applying a command to only some files.

## ARB Resolution Model

### Key terms
- **base file**: the source/template ARB file
- **target file**: the ARB file that will be updated
- **scope directory**: the directory context that owns one ARB configuration / override set

## Resolution Order

Resolution must be deterministic and based on nearest scope.

### Scope discovery
For a given ARB file, walk upward from the file's parent directory toward the project root.

The first directory that contains either:
- a saved ARB override entry, or
- `l10n.yaml`

defines the active scope.

If both exist in the same directory:
- saved override wins over `l10n.yaml`

### Why this order
This avoids both bad extremes:
- a global `l10n.yaml` overriding a user's explicit local override
- a far-away saved override overriding a nearer module-local Flutter config

The rule is therefore:
- nearest scope wins
- within the same scope, saved override wins over `l10n.yaml`

## Base File Resolution

### Exact resolution order
For a target ARB file, resolve the base file in this order:

1. nearest-scope saved base override
2. nearest-scope `l10n.yaml` `template-arb-file`
3. Flutter/name-directory heuristics
4. prompt fallback

### Heuristics
Heuristics should be conservative and only auto-select when the result is unique.

#### Preferred standard Flutter patterns
- `lib/l10n/app_en.arb` as base
- `l10n.yaml` with:
  - `arb-dir`
  - `template-arb-file`

#### Name-based heuristics
- same directory, same file family, locale suffix changes:
  - `app_en.arb` -> `app_de.arb`
  - `intl_en.arb` -> `intl_fr.arb`

#### Directory-based heuristics
Support directory-encoded locale layouts, for example:
- `./a/en/app.arb`
- `./a/de/app.arb`

The heuristic should consider:
- same filename
- sibling locale directories
- same scope directory
- common source locale preference for Flutter setups, especially `en`

If more than one plausible base remains:
- do not guess
- prompt

## Language Resolution

### Important rule
Saved `srcLang` and `trgLang` values are fallback hints. They do **not** override explicit `@@locale` values already present in ARB files.

This is deliberate. If a file explicitly declares `@@locale`, plugin-side saved hints must not silently contradict the file's own metadata.

### Source language resolution
1. base file `@@locale`
2. saved scope `srcLang`
3. base filename inference
4. prompt fallback

### Target language resolution
1. target file `@@locale`
2. saved target override for that exact target file
3. target filename inference
4. prompt fallback

### Consequence
If the user wants to change the real language of a file that already contains `@@locale`, the correct fix is to update the ARB file metadata, not rely on the plugin override.

## Prompt Fallback

When auto-resolution cannot determine base file and/or languages, show a prompt.

### Prompt contents
For a single unresolved target file:
- base file picker
- source language field
- target language field
- `Remember base and source language for this directory scope`
- `Remember target language for this file`

For unresolved multi-target selection in one base group:
- one shared base file picker
- one shared source language field
- per-target language rows only for targets that still need target-language input

### Storage behavior
- remembered base + source language are stored on scope-directory level
- remembered target language is stored per target file path within that scope

This is required because one scope can have multiple target files with different languages.

## Project-Level ARB Overrides

## Storage level
Keep existing plugin settings application-level.

Add ARB overrides as **project-level** settings, stored separately from app-level settings.

Recommended storage behavior for v1:
- project/workspace-level persistence
- local to the project
- not mixed into the existing app-level `DolocSettingsState`

### Why
These overrides are project-structure-specific and should not leak between unrelated projects.

## Settings UI

### Application settings page
Keep the existing app-level page.

Add:
- ARB untranslated options (`missing`, `empty`, `equal`)
- a clear note that:
  - ARB base/language overrides are project-level
  - they are configured separately
  - project-level ARB overrides affect only the current project

### Project settings page
Add a new project-level settings page for ARB overrides.

Show saved overrides by scope directory.

For each scope row, show:
- scope directory
- saved base file
- saved source language
- clear action for the whole scope row

Under each scope, show actual target files with saved target-language overrides:
- target file path
- target language override
- clear action per target row

### v1 editing limits
For v1:
- no inline edit workflow is required
- only clear is required:
  - clear scope row
  - clear target row

## Selection Semantics

## Single file ARB selection

### Target file selected
- resolve base
- resolve source/target language
- translate that target file only

### Base file selected alone
- treat this as `translate to all inferred target files`
- infer targets within the resolved scope
- show confirmation listing the target filenames before executing

This behavior is intentionally supported for v1.

## Multi-file ARB selection

### Rule
Run base inference for all selected ARB files.

If a selected file resolves as the base file:
- skip it as a target

That means:
- if the base file is selected together with explicit targets, do **not** automatically add unselected sibling targets
- only explicitly selected non-base targets are translated

### Grouping
After resolution:
- group target files by resolved base file

### v1 limitation
If more than one base group remains:
- abort
- show info/warning
- list the different base groups / base files found
- ask the user to run one base group at a time

This is a deliberate v1 simplification.

## Save Reminder Behavior

The current save reminder in `src/main/kotlin/io/doloc/intellij/listener/DolocFileListener.kt` is XLIFF-only and must be generalized.

## ARB reminder types

### Non-base target file saved
If the saved ARB file resolves to a base different from itself:
- show reminder action: `Translate this file`
- hint should include the resolved base filename

### Base file saved
If the saved ARB file is resolved as the base for one or more target files:
- show reminder action: `Translate to all target files`
- hint should list inferred target filenames
- keep the hint compact if there are many targets

Recommended compact hint style:
- first few filenames, then `+N more`

## Reminder filtering
Only remind when there is actual ARB work to do according to ARB untranslated rules:
- `missing`
- `empty`
- `equal`

If no target needs translation:
- no reminder

## Scope for base-file fan-out
When a base file triggers fan-out:
- use the resolved scope directory
- search recursively within that scope
- never cross outside that scope

This supports target files in subdirectories.

## ARB Content Rules

Use ARB-specific scanning instead of XLIFF scanning.

### Translatable keys
At top level:
- keys not starting with `@` are translatable message keys

### Metadata keys
- `@@locale`
- `@messageKey`

These are metadata and must not be translated directly.

### Untranslated detection
For each source key:
- `missing`: key absent in target
- `empty`: target value is blank
- `equal`: target value exactly equals source value

Only string values should be treated as translatable for v1.

## Reminder and Action Project Ownership

The current listener chooses the first open project, which is not acceptable once ARB overrides become project-level.

For ARB reminders and any generalized reminder flow:
- resolve the owning project from the file
- do not use `first open project`

This is necessary for correctness when multiple projects are open.

## No Custom ARB File Type

Do not register a custom `.arb` file type in `plugin.xml` for v1.

Reason:
- ARB is JSON-shaped
- a custom ARB file type is not required for translation support
- avoiding a file type override reduces integration risk

Detection should rely on file extension and direct ARB parsing.

## User-Facing Behavior Summary

### Manual action
- XLIFF selection: existing single-file behavior
- ARB target selection: translate selected targets
- ARB base-only selection: translate all inferred targets after confirmation
- mixed supported/unsupported selection: abort with warning/info

### Save reminder
- target ARB save: translate this file
- base ARB save: translate all inferred targets

### Settings
- app-level:
  - token
  - reminder toggle
  - XLIFF options
  - ARB untranslated defaults
  - note about project-level ARB overrides
- project-level:
  - saved ARB scope overrides
  - saved target-language overrides

## Resolved Edge Cases

### Nested Flutter modules
Handled by nearest-ancestor scope search for `l10n.yaml`.

### Saved override vs `l10n.yaml`
Nearest scope wins.
Within the same scope, saved override wins.

### Selected base plus targets
Base is skipped as a target.
Only explicitly selected non-base targets are translated.

### Base selected alone
Fan out to all inferred targets in that scope.

### Multiple bases in one ARB selection
Abort in v1 with info.

### Target files in subdirectories
Supported by scope-recursive target discovery.

### Saved language override vs explicit `@@locale`
`@@locale` wins.
Saved override is fallback-only.

### Stale saved overrides
If saved base/target paths no longer exist:
- ignore them at runtime
- show them as stale in project settings until cleared
