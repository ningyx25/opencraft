---
name: build-basics
description: How to place blocks to build simple structures — pillar up, bridge across gaps, platforms, walls. Use whenever the player asks to build or place blocks ("搭个桥", "垫高一点", "搭个平台", "围一圈墙"), or when you need to pillar/bridge to reach something.
requires_tools: player_place, player_jump, player_hotbar_select
---

# Build Basics

`player_place` puts your main-hand item against the FACE of the block at the target
coordinates — pick a target block adjacent to where the new block should appear.

1. Prepare: count the needed blocks in the Assistant State inventory; `player_item_move` them
   into a hotbar slot and `player_hotbar_select` it. Not enough? Mine/craft more first.
2. Pillar up (reach high places): `player_jump` then `player_place` a block (dirt/cobble)
   beneath yourself, repeat — only when climbing around is not cheaper.
3. Bridge a gap: place against the edge block of solid ground, extending sideways one block
   at a time; walk onto the new block between placements.
4. Platforms/walls: work row by row — place each block against the previous one along the
   row, then step back along the finished row to start the next.
5. Placing against functional blocks (chest/furnace/crafting table) opens them instead —
   pass `sneak=true` to place against them.
6. Placements are asynchronous: when far away the assistant walks first and the [Event]
   outcome arrives by itself — never re-issue while waiting.
