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
## etc. Later systems (combat, upgrades, save/load, prestige) will all read
## and write through here, so there's one source of truth.
##
## Phase 1 (MVP) scope only — see CLAUDE.md / docs/01-game-design-doc.md.
## One hero, three stats. Do not add skill-tree/class/equipment fields here
## yet; those are Phase 2+.

## --- Hero identity ---
var hero_name: String = "Hero"

## --- Base stats (Phase 1: just these three) ---
## These are the RAW base values, before any upgrade bonuses are applied.
## Once we add the upgrade screen, upgrades will modify these bases, and
## any "effective" stat used in combat will be recalculated FROM these —
## per the architecture rule "stat recalculation is cached": we recompute
## only when something changes (an upgrade is bought), never every frame.
var attack: int = 5
var max_health: int = 50
var attack_speed: float = 1.0  # attacks per second

## --- Economy / progression ---
var gold: int = 0
var current_stage: int = 1

## --- Combat state ---
## The hero's CURRENT health during combat — separate from max_health above,
## which is the ceiling. This goes down when the enemy lands a hit, and gets
## reset to max_health when the hero "respawns" after dying (see Main.gd).
## Float, not int, because damage amounts may end up fractional once stat
## scaling/upgrades are added.
var hero_current_health: float = float(max_health)
