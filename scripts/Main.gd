extends Control
## Main.gd
##
## The main (and, for now, only) gameplay screen. Handles:
##   - Displaying the hero's stats and gold (from GameState)
##   - Running the auto-combat loop: the hero and the current enemy attack
##     each other on their own independent timers — no player input needed
##   - Spawning a new Enemy (see scripts/Enemy.gd) whenever one dies, and
##     advancing the stage counter
##   - Handling the hero "dying" (HP hits 0): they respawn instantly at
##     full health, on the same stage. No extra punishment — this is an
##     idle game, so failure should just cost a little time, not progress.
##
## NOT in this step yet: tap-to-damage, the upgrade screen, save/load,
## offline earnings, prestige. Those come later, one at a time.

@onready var stage_label: Label = $VBoxContainer/StageLabel
@onready var hero_name_label: Label = $VBoxContainer/HeroNameLabel
@onready var attack_label: Label = $VBoxContainer/AttackLabel
@onready var attack_speed_label: Label = $VBoxContainer/AttackSpeedLabel
@onready var gold_label: Label = $VBoxContainer/GoldLabel
@onready var hero_health_label: Label = $VBoxContainer/HeroHealthLabel
@onready var hero_health_bar: ProgressBar = $VBoxContainer/HeroHealthBar
@onready var enemy_health_label: Label = $VBoxContainer/EnemyHealthLabel
@onready var enemy_health_bar: ProgressBar = $VBoxContainer/EnemyHealthBar

## How often the enemy attacks the hero, in seconds. The hero has an
## "Attack Speed" stat that controls how often THEY attack; enemies don't
## have their own attack-speed stat in Phase 1, so this is just a flat
## interval for every enemy.
const ENEMY_ATTACK_INTERVAL: float = 1.0

var current_enemy: Enemy
var hero_attack_timer: float = 0.0
var enemy_attack_timer: float = 0.0

func _ready() -> void:
	# Fresh start every time this screen loads: full health, from GameState's
	## own max_health. (There's no save/load yet, so this always runs once.)
	GameState.hero_current_health = GameState.max_health
	_update_static_labels()
	_spawn_enemy()

## _process() is a built-in Godot function that runs once per rendered
## frame; `delta` is how many seconds passed since the last frame. We use
## it to advance both combat timers. This is normal for a real-time combat
## loop — the CLAUDE.md rule against recalculating stats in _process() is
## about not redoing the whole stat-block math every frame, not about
## simple timers like this one.
func _process(delta: float) -> void:
	if current_enemy == null:
		return

	hero_attack_timer += delta
	var hero_attack_interval: float = 1.0 / max(GameState.attack_speed, 0.01)
	while hero_attack_timer >= hero_attack_interval:
		hero_attack_timer -= hero_attack_interval
		_hero_attacks()
		if current_enemy == null:
			return  # enemy just died; _spawn_enemy() already ran

	enemy_attack_timer += delta
	while enemy_attack_timer >= ENEMY_ATTACK_INTERVAL:
		enemy_attack_timer -= ENEMY_ATTACK_INTERVAL
		_enemy_attacks()

	_update_combat_labels()

func _hero_attacks() -> void:
	current_enemy.take_damage(GameState.attack)
	if current_enemy.is_dead():
		GameState.gold += current_enemy.gold_reward
		GameState.current_stage += 1
		_spawn_enemy()

func _enemy_attacks() -> void:
	GameState.hero_current_health = max(0.0, GameState.hero_current_health - current_enemy.attack)
	if GameState.hero_current_health <= 0.0:
		GameState.hero_current_health = GameState.max_health  # instant respawn, same stage

func _spawn_enemy() -> void:
	current_enemy = Enemy.new(GameState.current_stage)
	enemy_attack_timer = 0.0
	_update_combat_labels()

func _update_static_labels() -> void:
	hero_name_label.text = GameState.hero_name
	attack_label.text = "Attack: %d" % GameState.attack
	attack_speed_label.text = "Attack Speed: %.1f / sec" % GameState.attack_speed

func _update_combat_labels() -> void:
	stage_label.text = "Stage: %d" % GameState.current_stage
	gold_label.text = "Gold: %d" % GameState.gold

	hero_health_label.text = "Hero HP: %d / %d" % [int(ceil(GameState.hero_current_health)), GameState.max_health]
	hero_health_bar.max_value = GameState.max_health
	hero_health_bar.value = GameState.hero_current_health

	if current_enemy:
		enemy_health_label.text = "Enemy HP: %d / %d" % [int(ceil(current_enemy.current_health)), int(current_enemy.max_health)]
		enemy_health_bar.max_value = current_enemy.max_health
		enemy_health_bar.value = current_enemy.current_health
