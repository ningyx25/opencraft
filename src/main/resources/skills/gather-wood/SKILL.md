---
name: gather-wood
description: How to chop trees and collect wood/logs efficiently. Use whenever the player asks for wood, logs or planks ("砍树", "去弄点木头"), or when a crafting plan needs logs/planks/sticks as materials.
requires_tools: player_find, player_mine
---

# Gather Wood

Wood is the base of every early-game chain (planks, sticks, crafting table, tools).

1. `player_find log` — pick the nearest tree; read distances and vertical offsets in the result.
2. Mine the trunk from the BOTTOM up, one log per `player_mine` — the tool walks you adjacent
   automatically; do not `player_goto` there first.
3. Reachable set first: standing on the ground you can reach roughly the lowest 2–3 trunk
   blocks. For the rest, prefer moving to the NEXT tree (cheaper than climbing); only if the
   player wants the whole tree, pillar up: `player_jump` then `player_place` a block (dirt/
   cobble) beneath yourself, repeat.
4. Drops auto-pickup when you pass over them — wait for the [Event] "picked up: …_log×N"
   before counting toward the requested amount, then move on.
5. Report totals when done; hand logs over with `player_hand_to_player` only if asked.
