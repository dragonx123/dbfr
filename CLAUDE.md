# CLAUDE.md — Idle RPG Project

## About this project

An idle/incremental RPG for Android, built in **Godot 4.x** using **GDScript**.

## About me (important — read this)

I am a **complete beginner with no coding experience.** I am relying on you to write
essentially all of the code. Please work accordingly:

- **Explain what you're doing in plain language** before and after writing code.
- **Don't assume I know terminology.** If you use a term like "signal," "node,"
  "autoload," or "singleton," briefly explain it the first time.
- **Give me exact click-by-click instructions** for anything I have to do inside the
  Godot editor. I don't know where things are in the UI. "Add a Node2D" means nothing
  to me without telling me where to click.
- **One step at a time.** Don't hand me six files at once. Build one thing, tell me how
  to test it, and wait for me to confirm it works before moving on.
- **Tell me when something is a big deal.** If a decision is hard to reverse later,
  flag it and explain the tradeoff before proceeding.
- **If I ask for something that's a bad idea, say so.** I'd rather be told early.

## Design documents

Full design lives in `/docs`. Read these before implementing any system:

- `docs/01-game-design-doc.md` — core loop, economy, stages, prestige, MVP scope
- `docs/02-skill-tree.md` — 300-node data-driven skill tree
- `docs/03-class-system.md` — 44 classes, evolutions, class-exclusive nodes

**These docs are the source of truth.** If something I ask for contradicts them, point
out the conflict rather than silently picking one.

## Current phase

**Phase 1 — MVP only.** See GDD Section 9.

Build ONLY these things right now:
- One hero, three stats (Attack, Health, Attack Speed)
- Auto-combat vs. scaling enemies
- Tap-to-damage
- Gold + one upgrade screen
- Stage progression
- Local save/load
- Offline earnings
- One prestige mechanic

**Do not build** the skill tree, classes, equipment, or monetization yet. If I ask for
them before the MVP loop works, remind me they're Phase 2+ and ask if I'm sure.

## Architecture rules

- **Data-driven wherever possible.** Skill nodes, classes, stages, and items live in
  JSON in `/data`, interpreted by generic scripts. Never hardcode content that will
  scale to hundreds of entries.
- **Placeholder art only.** Colored rectangles and Godot's built-in fonts. Do not let
  art block mechanics.
- **Save format:** local JSON via Godot's `FileAccess`. Store allocated node IDs and
  state values, not computed stats — recompute those on load.
- **Stat recalculation is cached.** Recalculate the full stat block only when something
  changes (upgrade purchased, node allocated), never per combat frame.
- **Comment generously.** Assume I'll read this code in three months having forgotten
  everything.

## Project structure

```
/scenes     — .tscn scene files
/scripts    — .gd script files
/data       — JSON content files
/assets     — art, audio
/docs       — design documents
```

## Balancing baseline (from GDD §4, expect to tune)

- XP to next level: `50 * level^1.5`
- Enemy HP by stage: `base_HP * 1.12^stage`
- Gold drop: `base_gold * 1.10^stage`
- Offline rate: 60% of active rate, 8-hour cap at baseline
- Prestige currency: proportional to `best_stage^0.6`

## Things to avoid

- Multiplicative stat stacking without caps (causes exponential blowout)
- Recalculating stats inside `_process()` or `_physics_process()`
- Hardcoded content that belongs in JSON
- Building Phase 2+ systems before the Phase 1 loop is fun
