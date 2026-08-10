class_name Enemy
extends RefCounted
## Enemy.gd
##
## A lightweight, DATA-ONLY description of one enemy encounter — not a scene
## or a node, just numbers. Combat doesn't need the enemy to exist visually
## in the scene tree, so we skip that overhead: we just create a fresh
## Enemy object each time one spawns, using the stage-scaling formulas from
## docs/01-game-design-doc.md §4.
##
## `class_name Enemy` registers this as a global type, so anywhere in the
## project can just write `Enemy.new(stage)` — no need to load/preload this
## file first.

## --- Baseline values the formulas scale from ---
## The GDD baseline only pins down the *scaling curves* (e.g. "HP grows by
## 1.12x per stage"), not the starting numbers — those are placeholder
## guesses to start playtesting with, and will need real tuning once the
## loop is playable.
const BASE_HEALTH: float = 20.0
const BASE_GOLD: float = 5.0
const BASE_ATTACK: float = 1.0

var stage: int
var max_health: float
var current_health: float
var gold_reward: int
var attack: float

func _init(p_stage: int) -> void:
	stage = p_stage

	# Enemy HP by stage: base_HP * 1.12^stage
	max_health = BASE_HEALTH * pow(1.12, stage)
	current_health = max_health

	# Gold drop: base_gold * 1.10^stage
	gold_reward = int(round(BASE_GOLD * pow(1.10, stage)))

	# Enemy attack (back at the hero): the GDD baseline doesn't define this
	# curve yet, so it borrows the same 1.12^stage growth as HP as a
	# starting point. Watch playtest feel and flag if fights feel too
	# deadly (hero dies constantly) or too safe (Health stat never matters).
	attack = BASE_ATTACK * pow(1.12, stage)

func take_damage(amount: float) -> void:
	current_health = max(0.0, current_health - amount)

func is_dead() -> bool:
	return current_health <= 0.0
