---
name: mine-and-collect
description: How to gather a requested number of blocks or ores (stone→cobblestone, coal, iron…) — target selection, tool-tier check, quota counting. Use when the player asks to mine N of something ("挖三个圆石", "挖点铁矿"); pair with dig-down-staircase when matches are underground.
requires_tools: player_find, player_mine
---

# Mine and Collect (Quota Mining)

1. `player_find <target>` and read the annotations:
   - "(N blocks below you)" or the underground note → the vein is underground: descend with
     the dig-down-staircase skill first, then re-run `player_find` — coordinates refresh as
     you move.
   - surface-level matches → mine directly.
2. Tool tier check BEFORE the first swing (stone needs wooden pickaxe or better, iron ore
   needs stone or better) — wrong tier breaks the block but drops NOTHING.
3. Mine one block per `player_mine`; each [Event] reports the outcome plus
   "picked up: X×N". Count the PICKED-UP numbers toward the quota, not blocks broken —
   drops lie in the shaft until you walk over them.
4. Quota met → stop; if drops are scattered below, descend/pass over them before reporting.
5. Report with inventory numbers from the Assistant State — inventory is the truth.
