# 01 — Game Design Doc (Idle RPG)

> **Status: starting stub.** This file only contains what was already agreed in
> `CLAUDE.md` (Phase 1 scope + balancing baseline). It does not yet have the full
> narrative/core-loop writeup, stage list, or economy tuning notes that a complete
> GDD would have — those should be filled in (by you, or by asking Claude to draft
> them) as the game takes shape. Treat the missing sections as "not decided yet,"
> not as an oversight.

## 1. Elevator pitch

An idle/incremental RPG for Android. The hero fights automatically through a
sequence of increasingly difficult stages; the player earns gold to buy upgrades,
taps the enemy for bonus damage, and periodically prestiges to reset progress for a
permanent multiplier.

*(To be expanded: setting, tone, target session length, etc.)*

## 2. Core loop (Phase 1 / MVP)

1. Hero auto-attacks the current enemy.
2. Player can tap the enemy for extra damage.
3. Enemy dies → drops gold → next enemy in the stage spawns.
4. Enough kills/progress → stage advances (enemies get harder, per the scaling
   formulas below).
5. Player spends gold on one upgrade screen to raise stats.
6. When progress stalls, the player prestiges: resets stage/gold, keeps a
   permanent multiplier bought with prestige currency.
7. While the app is closed, gold accrues at a reduced "offline" rate, capped.

## 3. Hero stats (Phase 1)

Exactly three stats for the single MVP hero:

- **Attack** — damage per hit
- **Health** — max HP
- **Attack Speed** — attacks per second

No skill tree, no classes, no equipment yet — those are Phase 2+ (see
`CLAUDE.md`).

## 4. Balancing baseline (expect to tune)

- XP to next level: `50 * level^1.5`
- Enemy HP by stage: `base_HP * 1.12^stage`
- Gold drop: `base_gold * 1.10^stage`
- Offline rate: 60% of active rate, 8-hour cap at baseline
- Prestige currency: proportional to `best_stage^0.6`

*(Exact `base_HP`, `base_gold`, per-level attack/health growth, and stage count are
not decided yet — pick reasonable placeholder numbers when implementing and tune
from actual play.)*

## 5. Economy (Phase 1)

- Single currency: **Gold**, earned from enemy kills (active) and offline accrual.
- Spent on one upgrade screen (upgrades TBD in scope, but must map to the three
  hero stats above).
- **Prestige currency**: separate, earned on prestige, spent on permanent
  multipliers. Exact prestige shop contents: TBD.

## 6. Stages

Enemies scale per-stage using the HP/gold formulas above. Stage layout (how many
enemies per stage, boss stages, etc.) is not decided yet.

## 7. Save / offline earnings

- Local JSON save via Godot's `FileAccess`.
- Save stores raw state (level, stat purchases, gold, stage, prestige count) —
  never computed/derived stats. Derived stats are recalculated on load.
- On load, if a save exists, compute elapsed real time since last save, apply the
  offline gold formula (60% rate, 8h cap), and award the gold.

## 8. Prestige

One prestige mechanic for Phase 1: player can prestige once they've reached some
stage threshold (TBD), which resets stage/gold/upgrades but grants prestige
currency (`best_stage^0.6`) to spend on a permanent multiplier.

## 9. MVP scope (Phase 1) — from CLAUDE.md

Build ONLY:
- One hero, three stats (Attack, Health, Attack Speed)
- Auto-combat vs. scaling enemies
- Tap-to-damage
- Gold + one upgrade screen
- Stage progression
- Local save/load
- Offline earnings
- One prestige mechanic

Explicitly **not** Phase 1: skill tree (`02-skill-tree.md`), class system
(`03-class-system.md`), equipment, monetization.
