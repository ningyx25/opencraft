---
name: craft-toolchain
description: The vanilla early-game tool chain — log → planks → sticks → crafting table → wooden pickaxe → stone tools. Use whenever the player asks to craft tools or a workbench ("做把镐子", "放个工作台"), when player_craft fails with "requires a crafting table" or "not enough materials", or before mining that needs a better tool tier.
requires_tools: player_craft, player_place, player_mine
---

# Tool Chain (Early-Game Bootstrap)

Vanilla recipes: 1 log → 4 planks; 2 planks → 4 sticks; 4 planks → crafting table;
wooden pickaxe = 3 planks + 2 sticks; stone pickaxe = 3 cobblestone + 2 sticks.
Cobblestone requires a wooden pickaxe first — never skip tiers.

1. Read the Assistant State inventory first — the tool or materials may already be there.
2. Call `player_craft` directly; its errors are precise — react to them:
   - "requires a crafting table" → craft a crafting table (4 planks), `player_hotbar_select`
     its slot, `player_place` it on open ground nearby, then re-craft. Place it ONCE and
     reuse it — 3×3 works while you stand near it.
   - "not enough materials" → gather exactly what is missing (planks from logs, sticks from
     planks, cobblestone by mining stone with a pickaxe).
3. Tier gates: wooden pickaxe mines stone/coal; stone pickaxe mines iron; iron pickaxe mines
   gold/diamond/redstone. If `player_mine` says "too hard for my current main-hand tool",
   craft the next tier before retrying — the block drops nothing without the right tier.
4. Equip before mining: `player_hotbar_select` the tool's slot (slot numbers in the
   Assistant State inventory).
5. If the player wants the product, finish with `player_hand_to_player`.
