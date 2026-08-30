---
name: chest-storage
description: How to store items into and retrieve items from chests/barrels. Use whenever the player asks to deposit or withdraw items ("把东西放进箱子", "从箱子里拿点木头", "存一下"), or when your inventory is nearly full and low-priority loot should be deposited.
requires_tools: player_container_open, player_container_list, player_container_put, player_container_take, player_container_close
---

# Chest Storage

Containers (chest/barrel/shulker box) hold items; `player_mine` refuses to break them — never
try to get contents by mining, and don't break the player's containers at all.

1. `player_find chest` (or `barrel`) to locate the container, then `player_container_open` it
   — opening may be asynchronous (you walk there first); wait for the [Event] outcome.
2. `player_container_list` to see what's inside before moving anything.
3. `player_container_put <item> [amount]` to deposit, `player_container_take <item> [amount]`
   to withdraw — amount defaults to all matching stacks; pass a smaller number to move part
   of a stack. Taken items land in a free hotbar slot and may become your selected mainhand —
   re-select your working tool afterwards.
4. When your inventory is nearly full, proactively deposit low-priority loot (extra
   cobblestone, dirt); keep tools, fuel and current task materials on you.
5. When fetching materials for a task, take only what the plan needs — don't empty the chest.
6. `player_container_close` when done, then report what you stored or took.
