---
name: mine-ores
description: How to find and mine ores (coal, iron, gold, diamond) with the right tool tier. Use whenever the player asks for ores or ingots ("挖点铁矿", "找些煤", "挖钻石"), or when a crafting plan needs ore-derived materials (cobblestone, raw_iron, diamond).
requires_tools: player_find, player_mine, player_hotbar_select, player_teleport
---

# Mine Ores

Tier gates (mining without the right pickaxe drops NOTHING): wooden pickaxe → stone/coal;
stone pickaxe → iron; iron pickaxe → gold/diamond/redstone. If `player_mine` fails with
"too hard for my current main-hand tool", craft the next tier first (see craft-toolchain).
A wrong main-hand mines silently wasted: stone takes ~8s instead of ~1s and the [Event]
shows no "picked up" items — that means re-select the right tool before continuing.

1. Confirm the pickaxe is your selected mainhand in the Assistant State. After any container
   take/put or hand-over the selected slot can change (a taken ingot landed in a hotbar slot
   and became mainhand) — re-select the pickaxe with `player_hotbar_select` if not.
2. `player_find <ore>` (e.g. `coal`, `iron`, `diamond`) to locate ore; pass radius=20 to
   widen the search (default 12). Find searches world blocks/entities only — it cannot see
   your inventory, so check held materials in the Assistant State instead.
3. `player_mine` the ore directly — it walks you adjacent and breaks it; drops auto-pickup.
4. Hard-to-reach ore: don't take long walking detours — `player_teleport` straight to its
   coordinates (synchronous, same max-distance leash as `player_goto`); the landing
   auto-adjusts to a safe air pocket near the target. Buried just 1–2 blocks deep? Dig those
   few blocks down. If the find result says all matches lie far below you underground,
   teleport cannot reach them — dig a short staircase toward the listed coordinates instead.
5. Stay within reach: mine/goto/teleport all refuse targets beyond the max-distance leash
   from the owner (e.g. 64 blocks). Don't wander off chasing distant finds; if you end up
   stranded, `teleport_to_player` and continue from the owner's side.
6. Veins cluster: after the first ore, probe the blocks around that spot to reveal the rest
   before moving on.
7. Hazards: lava or water near the target → `player_stop` and route around it.
8. Count drops from the [Event] "picked up" messages toward the requested amount; report
   totals when done, hand ores over with `player_hand_to_player` only if asked.
