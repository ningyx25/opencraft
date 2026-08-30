---
name: smelt-in-furnace
description: How to smelt ores and cook items in a furnace (raw_iron → iron_ingot, sand → glass, log → charcoal, raw food → cooked food). Use whenever the player asks to smelt or cook ("烧铁锭", "炼铁", "烧点玻璃", "烤点吃的"), or when a crafting plan needs a smelted product.
requires_tools: player_container_open, player_container_list, player_container_put, player_container_take, player_container_close
---

# Smelt in a Furnace

Smelting converts raw_iron → iron_ingot, sand → glass, oak_log → charcoal, raw meat → cooked.
You need a furnace: `player_find furnace` first; if none exists, craft one (8 cobblestone,
3×3 recipe — needs a crafting table, see craft-toolchain) and place it nearby.

1. Place the furnace: `player_hotbar_select` its slot, `player_place` it on open ground
   (use `sneak=true` when placing against functional blocks, otherwise you open them).
2. Check quantities first: count the product you already hold in the Assistant State and
   smelt only the missing amount (e.g. iron_pickaxe needs 3 iron_ingot — with 1 already
   smelted, put 2 more raw_iron).
3. `player_container_open` the furnace — opening may be asynchronous (walking first); wait
   for the [Event] outcome.
4. `player_container_put` fuel FIRST (coal is best — 1 coal smelts 8 items; planks/logs work
   too), then `player_container_put` the input (e.g. raw_iron). The tool shift-clicks each
   stack into its proper slot automatically.
5. Smelting takes real time, one item per operation. Don't poll `player_container_list`
   back-to-back — interleave other steps and check again between rounds until the product
   appears, then `player_container_take` it and `player_container_close`.
6. The taken product lands in a free hotbar slot and becomes your selected mainhand —
   after closing, re-select your working tool (e.g. the pickaxe) before the next action.
7. Big batches: repeat put-input → wait → take; top up fuel when the list shows it low.
8. If the player wants the product, finish with `player_hand_to_player`.
