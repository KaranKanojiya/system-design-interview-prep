# Agent Orientation

> Read this first. It tells any AI assistant working in this repo where things live, which files to consult, and what conventions to follow.

## What this repo is

A **system-design interview prep** monorepo. 20 use cases + 3 foundations. Every use case ships:

1. **Real Java implementation** in `NN-slug/src/main/java/` (~30–65 classes each, Java 21, Gradle wrapper)
2. **Long-form design docs** in `NN-slug/docs/{hld,lld,patterns,tradeoffs,tech,caching,cloud}/`
3. **Revision walkthrough** in `NN-slug/revision/INTERVIEW_WALKTHROUGH.md`
4. **Interactive HTML study canvases** in `canvas/NN-slug/{hld,lld,deepdive}.html`

The primary study surface is **`canvas/index.html`** — a hub grid with all foundations and use cases.

## Where to look

| You want to… | Look here |
|---|---|
| Understand user preferences, code style, conventions | `.memory/PREFERENCES.md` |
| Understand current project status / what's built | `.memory/SESSION_STATE.md` |
| Know who the user is / their goals | `.memory/USER_CONTEXT.md` |
| Build or edit any canvas under `canvas/**` | `.cursor/rules/canvas.mdc` (auto-attached for canvas files) |
| Understand the exemplar canvas structure | `canvas/10-ecommerce/{hld,lld,deepdive}.html` |
| Find real Java classes to reference in an LLD canvas | `NN-slug/src/main/java/**/*.java` — use ONLY names that exist |
| Cross-project pattern reference | `docs/DESIGN_PATTERNS_REFERENCE.md` + `docs/DESIGN_PATTERNS_UML.md` |
| Universal estimation cheatsheet | `docs/ESTIMATION_CHEATSHEET.md` |

## Layout conventions (critical for canvases)

- **New canvases MUST use the 3-column × 6/7-row spacious grid** (240 px nodes, 70 px gutters, 26 px row-gaps). Full spec in `.cursor/rules/canvas.mdc` Rule A.
- **Legacy 4-col grid** (x=20/205/395/620, w≤150) only for foundations + already-shipped canvases (01–09, 11–13, 20). Do not retrofit unless a specific bug is reported.
- **Subtitle hard-cap: 28 chars at font-size 11.** Guardrail: `rg -oN 'font-size="1[01]">[^<]{29,}' canvas/**/*.html` must return empty.
- **Arrow labels** use `class="edge-label"` (paint-order halo) AND MUST land in a **gutter × row-gap intersection**. If a label can't fit there, delete it — the halo is a fallback, not a licence for arbitrary placement.
- **CSS palette is a whitelist** (see `canvas/assets/canvas.css`): `--text --muted --faint --border --panel --panel-2 --bg --eden --survivor --old --meta --stack --gc --warn --fig`. An undefined var → invisible SVG stroke → the node disappears.
- **Cloud service icons** must reference a file that exists under `canvas/assets/logos/services/` — never invent icon names.

## Behavioural conventions (from `.memory/PREFERENCES.md`)

- Numbered sequences on ALL flow diagrams — (1), (2), (3)… for interview walkthrough.
- Heavy comments showing wiring / call chains — AppConfig → Service → Strategy → Repository.
- Show the ugly if-else code first, then the clean pattern solution.
- Demo edge cases (celebrity problem, thundering herd, flash-sale, split-brain) explicitly in main app output.
- Fix shared CSS first, per-canvas HTML second — one edit in `canvas/assets/canvas.css` benefits all 63 canvases.
- Only commit when the user explicitly asks. Not proactively.

## Commands you'll actually run

```bash
# Run any project
./gradlew :NN-slug:run

# Open the canvas hub
open canvas/index.html    # or Cursor Live Preview

# Full guardrail sweep on all canvases
rg -oN 'font-size="1[01]">[^<]{29,}' canvas/**/*.html            # subtitle overflow — must be empty
rg -o 'var\(--[a-z0-9-]+\)' canvas/**/*.html | sort -u             # CSS var whitelist check
rg -o 'data-tab="[^"]+"' canvas/**/*.html | sort -u                # tab wiring sanity
rg -o 'data-panel="[^"]+"' canvas/**/*.html | sort -u              # must equal data-tab set

# Java build
./gradlew build
```

## What NOT to do

- Do NOT invent Java class names on LLD class maps — grep `NN-slug/src/main/java/` first.
- Do NOT reference cloud service icons that don't exist under `canvas/assets/logos/services/`.
- Do NOT use CSS vars outside the whitelist — the node vanishes silently.
- Do NOT use the legacy 4-col grid for new canvases.
- Do NOT touch `canvas/index.html` from inside a per-canvas build — wire tiles in a dedicated step.
- Do NOT commit without an explicit user request.
