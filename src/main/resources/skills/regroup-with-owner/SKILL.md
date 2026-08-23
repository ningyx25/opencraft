---
name: regroup-with-owner
description: When to walk back vs teleport to the owner, and how the max-distance leash works. Use when the player says come back / follow / 回来, when a target fails with "too far from the owner", or when you finish a task far from the owner.
requires_tools: player_goto, teleport_to_player
---

# Regroup With the Owner

- `player_goto` toward the owner (position in Player State) — normal when close; the
  arrival [Event] tells you when you're there.
- `teleport_to_player` — instant, cross-dimension. Use when: the player wants you back NOW
  ("回来", "快回来"); you are stuck (repeated timeouts) or fell into a cave; the task is
  done and the walk back is long.

## The distance leash

Mining targets must be within max distance of the OWNER — when `player_mine` fails with
"more than N blocks from the owner", do not retry the same block: `ask_player` the owner to
move closer, or pick a nearer target. Goto targets are limited by distance from your OWN
current position — approach far goals in steps.

After regrouping, report what you accomplished (counts/items) — don't make the player ask.
