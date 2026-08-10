extends Node
## GameState.gd  —  Autoload (a.k.a. "singleton")
##
## An "autoload" is a script Godot loads ONCE, automatically, before any
## scene starts, and keeps alive for the entire time the game is running.
## It's registered in Project Settings (we did this via project.godot, under
## the [autoload] section) under the name "GameState". Because of that, any
## script anywhere in the game can just write `GameState.attack` and get the
## live value — no need to find a specific node or pass data between scenes.
##
## This is our central hub for the hero's data: stats, gold, current stage,
## upgrade levels, etc. Later systems (save/load, prestige) will read and
## write through here too, so there's one source of truth.
##
## Phase 1 (MVP) scope only — see CLAUDE.md / docs/01-game-design-doc.md.
## One hero, three stats. Do not add skill-tree/class/equipment fields here
## yet; those are Phase 2+.

## --- Hero identity ---
var hero_name: String = "Hero"

## --- Base stats (before any upgrades) ---
## These never change — they're the starting point. Upgrades add a FLAT
## bonus per level on top of these (see recalculate_stats() below).
## Additive on purpose: CLAUDE.md flags multiplicative stat stacking
## without caps as something to avoid, so Phase 1 upgrades add instead of
## multiply. Placeholder tuning values — expect to adjust after playtesting.
const BASE_ATTACK: int = 5
const BASE_MAX_HEALTH: int = 50
const BASE_ATTACK_SPEED: float = 1.0

## How much each upgrade level adds to its stat. Placeholder tuning values.
const ATTACK_PER_LEVEL: int = 2
const HEALTH_PER_LEVEL: int = 10
const ATTACK_SPEED_PER_LEVEL: float = 0.05

## How much gold the NEXT level of an upgrade costs: base * growth^level.
## Placeholder tuning values, same "expect to tune" caveat as everywhere
## else in docs/01-game-design-doc.md §4.
const ATTACK_UPGRADE_BASE_COST: int = 10
const HEALTH_UPGRADE_BASE_COST: int = 10
const ATTACK_SPEED_UPGRADE_BASE_COST: int = 20
const UPGRADE_COST_GROWTH: float = 1.15

## --- Upgrade levels (how many times each stat has been bought) ---
var attack_upgrade_level: int = 0
var health_upgrade_level: int = 0
var attack_speed_upgrade_level: int = 0

## --- Effective stats (base + upgrades) ---
## These are the CACHED, computed values everything else in the game reads.
## Per the "stat recalculation is cached" architecture rule, they're only
## recomputed inside recalculate_stats() below — which runs when an
## upgrade is bought, never every frame.
var attack: int = BASE_ATTACK
var max_health: int = BASE_MAX_HEALTH
var attack_speed: float = BASE_ATTACK_SPEED

## --- Economy / progression ---
var gold: int = 0
var current_stage: int = 1

## --- Combat state ---
## The hero's CURRENT health during combat — separate from max_health above,
## which is the ceiling. This goes down when the enemy lands a hit, and gets
## reset to max_health when the hero "respawns" after dying (see Main.gd).
## Float, not int, because damage amounts may end up fractional.
var hero_current_health: float = float(max_health)


## Recomputes attack / max_health / attack_speed from their base values
## plus current upgrade levels. Call this any time an upgrade level changes
## — this IS the "recalculate on change, not every frame" step.
func recalculate_stats() -> void:
	attack = BASE_ATTACK + attack_upgrade_level * ATTACK_PER_LEVEL
	max_health = BASE_MAX_HEALTH + health_upgrade_level * HEALTH_PER_LEVEL
	attack_speed = BASE_ATTACK_SPEED + attack_speed_upgrade_level * ATTACK_SPEED_PER_LEVEL


## --- Upgrade costs: how much gold the NEXT level of each upgrade costs ---
func get_attack_upgrade_cost() -> int:
	return int(round(ATTACK_UPGRADE_BASE_COST * pow(UPGRADE_COST_GROWTH, attack_upgrade_level)))

func get_health_upgrade_cost() -> int:
	return int(round(HEALTH_UPGRADE_BASE_COST * pow(UPGRADE_COST_GROWTH, health_upgrade_level)))

func get_attack_speed_upgrade_cost() -> int:
	return int(round(ATTACK_SPEED_UPGRADE_BASE_COST * pow(UPGRADE_COST_GROWTH, attack_speed_upgrade_level)))


## --- Upgrade purchases ---
## Each returns true if the purchase went through (enough gold), false if
## not enough gold. The UI uses the return value to decide whether to show
## any "not enough gold" feedback later.
func buy_attack_upgrade() -> bool:
	var cost := get_attack_upgrade_cost()
	if gold < cost:
		return false
	gold -= cost
	attack_upgrade_level += 1
	recalculate_stats()
	return true

func buy_health_upgrade() -> bool:
	var cost := get_health_upgrade_cost()
	if gold < cost:
		return false
	gold -= cost
	health_upgrade_level += 1
	var old_max: int = max_health
	recalculate_stats()
	# Heal by however much the ceiling just went up, so buying a Health
	# upgrade never feels wasted just because you were already near-death.
	hero_current_health = min(max_health, hero_current_health + (max_health - old_max))
	return true

func buy_attack_speed_upgrade() -> bool:
	var cost := get_attack_speed_upgrade_cost()
	if gold < cost:
		return false
	gold -= cost
	attack_speed_upgrade_level += 1
	recalculate_stats()
	return true
