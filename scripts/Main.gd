extends Control
## Main.gd
##
## Attached to the root node of scenes/Main.tscn — this is the very first
## screen the game shows. Right now all it does is read the hero's stats
## from the GameState autoload (see scripts/autoload/GameState.gd) and put
## them into the on-screen labels, just so we can SEE that the data exists
## and is readable from a scene. No combat, upgrades, or saving yet — those
## come in later steps.

## @onready means "wait until this node has actually entered the scene tree,
## then grab this reference." The $ is shorthand for "find a child node at
## this path relative to me."
@onready var hero_name_label: Label = $VBoxContainer/HeroNameLabel
@onready var attack_label: Label = $VBoxContainer/AttackLabel
@onready var health_label: Label = $VBoxContainer/HealthLabel
@onready var attack_speed_label: Label = $VBoxContainer/AttackSpeedLabel
@onready var gold_label: Label = $VBoxContainer/GoldLabel

## _ready() is a built-in Godot function: it runs once, automatically, the
## moment this node and all its children have finished loading.
func _ready() -> void:
	hero_name_label.text = GameState.hero_name
	attack_label.text = "Attack: %d" % GameState.attack
	health_label.text = "Health: %d" % GameState.max_health
	attack_speed_label.text = "Attack Speed: %.1f / sec" % GameState.attack_speed
	gold_label.text = "Gold: %d" % GameState.gold
