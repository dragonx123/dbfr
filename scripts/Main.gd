extends Control
## Main.gd
##
## The main (and, for now, only) gameplay screen. Handles:
##   - Displaying hero stats, gold, and combat state (from GameState)
##   - Running the auto-combat loop: hero and enemy attack each other on
##     independent timers, no input required
##   - Tap-to-damage: tapping/clicking the "Tap to Attack!" button deals one
##     extra hit's worth of damage, on top of auto-combat
##   - The Upgrade panel: toggled with a button, lets the player spend gold
##     to raise Attack / Health / Attack Speed (cost & growth math lives in
##     GameState.gd)
##   - Hero "dying" (HP hits 0): respawns instantly at full health, same
##     stage — idle games shouldn't punish failure harshly
##
## NOT in this step yet: save/load, offline earnings, prestige.

## --- Game (combat) panel nodes ---
@onready var game_panel: VBoxContainer = $GamePanel
@onready var stage_label: Label = $GamePanel/StageLabel
@onready var hero_name_label: Label = $GamePanel/HeroNameLabel
@onready var attack_label: Label = $GamePanel/AttackLabel
@onready var attack_speed_label: Label = $GamePanel/AttackSpeedLabel
@onready var gold_label: Label = $GamePanel/GoldLabel
@onready var hero_health_label: Label = $GamePanel/HeroHealthLabel
@onready var hero_health_bar: ProgressBar = $GamePanel/HeroHealthBar
@onready var enemy_health_label: Label = $GamePanel/EnemyHealthLabel
@onready var enemy_health_bar: ProgressBar = $GamePanel/EnemyHealthBar
@onready var tap_button: Button = $GamePanel/TapButton
@onready var upgrades_button: Button = $GamePanel/UpgradesButton

## --- Upgrade panel nodes ---
@onready var upgrade_panel: VBoxContainer = $UpgradePanel
@onready var upgrade_gold_label: Label = $UpgradePanel/GoldLabel
@onready var attack_upgrade_label: Label = $UpgradePanel/AttackRow/AttackUpgradeLabel
@onready var attack_buy_button: Button = $UpgradePanel/AttackRow/AttackBuyButton
@onready var health_upgrade_label: Label = $UpgradePanel/HealthRow/HealthUpgradeLabel
@onready var health_buy_button: Button = $UpgradePanel/HealthRow/HealthBuyButton
@onready var attack_speed_upgrade_label: Label = $UpgradePanel/AttackSpeedRow/AttackSpeedUpgradeLabel
@onready var attack_speed_buy_button: Button = $UpgradePanel/AttackSpeedRow/AttackSpeedBuyButton
@onready var back_button: Button = $UpgradePanel/BackButton

## How often the enemy attacks the hero, in seconds. Enemies don't have
## their own attack-speed stat in Phase 1, so this is a flat interval.
const ENEMY_ATTACK_INTERVAL: float = 1.0

var current_enemy: Enemy
var hero_attack_timer: float = 0.0
var enemy_attack_timer: float = 0.0

func _ready() -> void:
	GameState.hero_current_health = GameState.max_health

	# A "signal" is Godot's way of announcing "something happened" so other
	# code can react to it. Every Button node emits a "pressed" signal
	# whenever it's clicked or tapped. `.connect()` hooks one of our own
	# functions up to run automatically whenever that signal fires.
	tap_button.pressed.connect(_on_tap_button_pressed)
	upgrades_button.pressed.connect(_show_upgrade_panel)
	back_button.pressed.connect(_show_game_panel)
	attack_buy_button.pressed.connect(_on_buy_attack_pressed)
	health_buy_button.pressed.connect(_on_buy_health_pressed)
	attack_speed_buy_button.pressed.connect(_on_buy_attack_speed_pressed)

	_update_static_labels()
	_spawn_enemy()

## _process() runs once per rendered frame; `delta` is the time in seconds
## since the last frame. Used here to advance both combat timers. This is
## normal for a real-time combat loop — the CLAUDE.md rule against
## recalculating stats in _process() is about not redoing the whole
## stat-block math every frame, not about simple timers like these.
func _process(delta: float) -> void:
	if current_enemy == null:
		return

	hero_attack_timer += delta
	var hero_attack_interval: float = 1.0 / max(GameState.attack_speed, 0.01)
	while hero_attack_timer >= hero_attack_interval:
		hero_attack_timer -= hero_attack_interval
		_deal_damage_to_enemy(GameState.attack)
		if current_enemy == null:
			return  # enemy just died; _spawn_enemy() already ran

	enemy_attack_timer += delta
	while enemy_attack_timer >= ENEMY_ATTACK_INTERVAL:
		enemy_attack_timer -= ENEMY_ATTACK_INTERVAL
		_enemy_attacks()

	_update_combat_labels()
	if upgrade_panel.visible:
		_update_upgrade_labels()  # keep gold/costs live while panel is open

## --- Tap-to-damage ---
func _on_tap_button_pressed() -> void:
	if current_enemy == null:
		return
	_deal_damage_to_enemy(GameState.attack)

## Shared by both auto-attacks and taps, so enemy-death handling (gold,
## stage advance, next spawn) only lives in one place.
func _deal_damage_to_enemy(amount: float) -> void:
	current_enemy.take_damage(amount)
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

## --- Upgrade panel show/hide ---
func _show_upgrade_panel() -> void:
	game_panel.visible = false
	upgrade_panel.visible = true
	_update_upgrade_labels()

func _show_game_panel() -> void:
	upgrade_panel.visible = false
	game_panel.visible = true

func _on_buy_attack_pressed() -> void:
	GameState.buy_attack_upgrade()
	_update_upgrade_labels()
	_update_static_labels()

func _on_buy_health_pressed() -> void:
	GameState.buy_health_upgrade()
	_update_upgrade_labels()

func _on_buy_attack_speed_pressed() -> void:
	GameState.buy_attack_speed_upgrade()
	_update_upgrade_labels()
	_update_static_labels()

## --- Label refreshers ---
func _update_static_labels() -> void:
	hero_name_label.text = GameState.hero_name
	attack_label.text = "Attack: %d" % GameState.attack
	attack_speed_label.text = "Attack Speed: %.2f / sec" % GameState.attack_speed

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

func _update_upgrade_labels() -> void:
	upgrade_gold_label.text = "Gold: %d" % GameState.gold

	attack_upgrade_label.text = "Attack: %d (Lv %d)" % [GameState.attack, GameState.attack_upgrade_level]
	var attack_cost := GameState.get_attack_upgrade_cost()
	attack_buy_button.text = "Buy (%d g)" % attack_cost
	attack_buy_button.disabled = GameState.gold < attack_cost

	health_upgrade_label.text = "Health: %d (Lv %d)" % [GameState.max_health, GameState.health_upgrade_level]
	var health_cost := GameState.get_health_upgrade_cost()
	health_buy_button.text = "Buy (%d g)" % health_cost
	health_buy_button.disabled = GameState.gold < health_cost

	attack_speed_upgrade_label.text = "Attack Speed: %.2f / sec (Lv %d)" % [GameState.attack_speed, GameState.attack_speed_upgrade_level]
	var speed_cost := GameState.get_attack_speed_upgrade_cost()
	attack_speed_buy_button.text = "Buy (%d g)" % speed_cost
	attack_speed_buy_button.disabled = GameState.gold < speed_cost
