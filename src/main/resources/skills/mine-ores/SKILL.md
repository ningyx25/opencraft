---
name: mine-ores
description: How to find and mine ores (coal, iron, gold, diamond) safely with the right tool tier. Use whenever the player asks for ores or ingots ("挖点铁矿", "找些煤", "挖钻石"), or when a crafting plan needs ore-derived materials (cobblestone, raw_iron, diamond).
requires_tools: player_find, player_mine, player_hotbar_select, player_teleport
---

# Mine Ores

Tier gates (mining without the right pickaxe drops NOTHING): wooden pickaxe → stone/coal;
stone pickaxe → iron; iron pickaxe → gold/diamond/redstone. If `player_mine` fails with
"too hard for my current main-hand tool", craft the next tier first (see craft-toolchain).

1. Equip the best pickaxe you have: `player_item_move` it into a hotbar slot if needed, then
   `player_hotbar_select` that slot.
2. `player_find <ore>` (e.g. `coal`, `iron`, `diamond`) — exposed ore is common on cave walls,
   cliffs and mountainsides; trust the returned coordinates, don't guess.
3. `player_mine` the ore directly — it walks you adjacent and breaks it; drops auto-pickup.
4. Deep or hard-to-reach ore: don't waste rounds walking or digging stairs — `player_teleport`
   straight to the ore's coordinates. It's synchronous and auto-adjusts the landing to a safe
   air pocket near the target (same max-distance leash as `player_goto`). Buried in solid rock
   with no air pocket? Mine the few blocks above it to open the way down.
5. Veins cluster: after the first ore, probe the blocks around that spot to reveal the rest
   before moving to the next find.
6. Hazards: if lava or water shows up near the target in the Assistant State, `player_stop`
   and route around it.
7. Count drops from the [Event] "picked up" messages toward the requested amount; report
   totals when done, hand ores over with `player_hand_to_player` only if asked.
