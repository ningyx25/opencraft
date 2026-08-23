---
name: dig-down-staircase
description: How to descend underground in Minecraft by digging a staircase instead of a 1x1 shaft, keeping every block within interaction range and drops collectable. Use whenever the target blocks (stone, ore, caves) are below the surface and you must lose altitude — mining under yourself, digging down to a target Y level, or when player_mine keeps failing with "not within interaction range" while you stand above a shaft.
requires_tools: player_mine, player_goto
---

# Staircase Digging (Descend While Mining)

Walking never changes your altitude. To reach blocks below the surface you must dig your
way down — and the *shape* of what you dig decides whether you can actually descend.

**Never dig a 1×1 shaft straight below while standing BESIDE it.** The block supporting
you is in the neighbouring column: the shaft gets deeper, you stay on the surface, the
next block falls out of interaction range, and the drops land where you cannot pick
them up. This failure mode looks like: `player_mine` succeeds a few times, then keeps
failing with "not within interaction range" while your Y stays the same.

## The staircase loop

Each cycle costs two `player_mine` calls, lowers you exactly one block, and keeps the
next block within reach:

1. Pick a direction toward the target (e.g. toward the target's X or Z).
2. Mine the block at your feet level, one block in that direction
   (`y = your feet Y`).
3. Mine the block directly below it (`y = your feet Y - 1`, same column).
4. Walk into the step with `player_goto` — you drop one block as you cross the hole.
5. Repeat from step 2 until you reach the target layer, then mine toward the target
   block itself.

The `[Event]` after each mine reports the outcome and picked-up drops; the Assistant
State shows your current position between steps — verify your Y actually dropped before
continuing.

## Shortcuts and recovery

- **Only 1–2 blocks down** (target just below reach): mine the block directly below
  your feet — the column you actually stand on — and you fall into the hole.
- **Already stuck above a shaft you dug**: mine the column you are standing on (your
  support block at feet Y − 1) to drop into the shaft, or pillar-jump with
  `player_place` if you need to go up instead.
- **Coming back up later**: keep the staircase as your exit path (it is walkable
  upward), or pillar-jump with `player_place` from the bottom.
