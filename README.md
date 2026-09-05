# OpenCraft

An AI game assistant mod for **Minecraft 1.21.11 (Fabric)**. The assistant is a **real `ServerPlayer` bot** — it joins the server like a multiplayer client, shows up in the player list and entity tracker, and is driven by an LLM through native function calling to mine, place, craft, and hand items to the owner. Works with any OpenAI-compatible Chat Completions endpoint (streaming SSE included).

> **Languages:** English (this file) · [中文 (zh-CN)](README_zh.md)

## Demo

[demo.mp4](docs/media/demo.mp4)

---

## Highlights

### AI assistant — a real `ServerPlayer` bot

- Summon with `/opencraft summon` or by right-clicking an **AI Logo Block**; the bot joins via `PlayerList.placeNewPlayer` with a full 43-slot player inventory, game mode, and XP.
- **Auto-follow by default** — keeps up with you, teleport-rejoins across distances/dimensions. Exits follow the moment a task starts and resumes when it finishes.
- Survival mode + invulnerable + auto-fed — can take hits without dying, owns the same capabilities as a real player.
- Bind state and inventory persist with the world; reload after dismiss rehydrates everything.
- **Many assistants, one each** — every AI Logo Block binds to a single assistant, and one player can own several at once.

### LLM chat

- `/opencraft ask <message>` to talk to the nearest assistant; `/opencraft ask <name> <message>` to target a specific one (Tab-completable).
- **Streaming replies** — token-by-token output in the action bar while the model is generating, then broadcast to chat on completion.
- Each assistant has its own conversation memory; long histories are compacted into summaries, not truncated.
- The dynamic game context (dimension, coords, time, health, hunger, held item, weather, biome, status effects, nearby mobs, what's underfoot, where the owner is) is appended to the first user message and refreshed on later turns.
- LLM requests run on a daemon pool, never on the server thread.

### Agent presets & tool calling

An **agent preset** decides how the LLM behaves — each preset composes a set of plugins, plugins expose OpenAI-style tool schemas, the LLM picks which to call via native function calling, the server executes them, the result goes back, and the loop continues until the model gives a final answer.

Two presets ship in the config UI's "Agent Preset" tab:

| Preset | Plugins | Max tool rounds |
|---|---|---|
| `chat_agent` (pure chat) | assistant control | 3 |
| `general_agent` (full, default) | assistant control + player actions | 250 |

Player-action tools (exclusive to `general_agent`, all run through real player code paths):

| Tool | Effect |
|---|---|
| `player_goto` / `player_stop` / `player_jump` | Walk to coords / stop moving / jump once |
| `player_find` | Find blocks/entities/drops by keyword — returns exact coords, bearing, distance |
| `player_mine` | Walk next to a block and break it via `ServerPlayerGameMode.destroyBlock`; drops auto-pickup |
| `player_place` | Place the held item against a face via `useItemOn` |
| `player_craft` | Craft like a real player (2×2 anywhere, 3×3 needs a nearby table) |
| `player_inventory` | List full inventory + equipment (slot id, count, durability) |
| `player_hand_to_player` | Hand an item from inventory to the owner (drops at their feet if full) |
| `player_container_open` / `_close` | Right-click open / close a container (chest, barrel, shulker, furnace, …) |
| `player_container_list` | List the open container + own inventory (read-only) |
| `player_container_take` / `_put` | Shift-click whole stacks between container and inventory |
| `teleport_to_player` | Instantly teleport to the owner (cross-dim; all presets) |

**Agentic-loop robustness**: exponential-backoff retry on transient network errors; repeat-call detection breaks dead loops; oversized tool results are head/tail-truncated; long histories are LLM-summarized; destructive actions confirm with the player (`/opencraft answer`, 90s timeout, then continue on a reasonable assumption); multi-step tasks get a live-updating plan.

### AI Logo Block

Crafting recipe: **4 iron ingots + 4 redstone + 1 glass** (mineable by hand, always drops itself).

Right-click opens an in-game config editor (4 tabs):

| Tab | Contents |
|---|---|
| Endpoint & API key | `baseUrl`, API key, model |
| Agent preset | Assistant name, preset, temperature, request timeout, history size |
| Loop events | Per-loop enable toggles + live status |
| Chat | Built-in chat window (streaming, shares memory with `/opencraft ask`) |

- "Save config" applies immediately. Ops can save; non-ops are read-only.
- The API key never leaves the server — the UI only shows "set (hidden) / not set".
- The bottom button doubles as summon / dismiss: summons when unbound, dismisses when bound.
- Bound blocks emit light level 15; the block darkens when the assistant leaves.

**Baked-in defaults**: put `OPEN_CRAFT_BASE_URL`, `OPEN_CRAFT_MODEL`, and `OPEN_CRAFT_API_KEY` in a project-root `.env` and run `./gradlew build` — the produced jar ships with those defaults (XOR-obfuscated, no plaintext in the jar). Runtime resolution: JVM flag > env var > baked-in > code fallback.

### Loop events (trigger → act → monitor)

The built-in **loop event module** runs the moment an assistant is summoned. Six guard-style loops ship out of the box — all are `persistent`, so they return to "waiting" after each round instead of dying:

| Loop id | Effect |
|---|---|
| `heal_aura` | Heal owner 1 HP every 2 s while injured |
| `feed_aura` | Feed owner 1 hunger point every 2 s while hungry (also restores saturation) |
| `breath_aura` | Refill +60 air every 0.5 s while underwater |
| `extinguish_fire` | Extinguish owner every 0.5 s while on fire |
| `pickup_aura` | Pull unprotected drops within 5 m of the assistant into its inventory (3D, not just horizontal) |
| `mob_repel` | Knockback hostiles within 6 m of the owner every 1 s (no damage) |

The framework is generic — a loop is `LoopCondition` + `LoopEvent` + `LoopMonitor` plus a `LoopDefinition`. `/opencraft loop status` lists every registered definition and active instance. See [docs/agent-architecture.md](docs/agent-architecture.md) for the engine design.

---

## Quick start

1. Place an AI Logo Block (craft: 4 iron + 4 redstone + 1 glass).
2. Right-click the block, fill in `baseUrl` / model / API key on the first tab, click **Save config**.
3. Click the bottom button (**Summon**) — the assistant joins as a player bot and starts following you.
4. Run `/opencraft ask <message>` to chat, or use the Chat tab in the config UI.
5. Run `/opencraft dismiss` to send the bot away; breaking the block also discards it and its memory.

---

## Commands

| Command | Description |
|---|---|
| `/opencraft ask <message>` | Talk to the nearest assistant |
| `/opencraft ask <name> <message>` | Talk to a specific assistant (Tab-completable; coordinate-suffix disambiguates same-name bots) |
| `/opencraft answer <reply>` | Reply to the assistant's confirmation question |
| `/opencraft interrupt` (alias `stop`) | Abort the nearest assistant's running task immediately |
| `/opencraft summon` | Summon an assistant (binds the nearest unbound block) |
| `/opencraft dismiss [all]` | Dismiss the nearest / all assistants |
| `/opencraft status` | List every assistant and its config state |
| `/opencraft reset [all]` | Clear conversation memory for the nearest / all assistants |
| `/opencraft loop status` | List every registered loop definition and active instance |
| `/opencraft debug [on\|off\|status]` | View or toggle debug mode (ops only) |
| `/opencraft help` | Show in-game help |

---

## Building & running

Requires **JDK 21+** (CI uses JDK 25; `--release 21` pins bytecode level).

```bash
./gradlew build              # compile + package (+ pure-Java unit tests)
./gradlew runClient          # launch the Minecraft client with the mod
./gradlew runServer          # launch a dedicated server with the mod
./gradlew runGametestServer  # run Fabric gametests (headless server)
./gradlew test               # JUnit 5 only — no Minecraft runtime
./gradlew runE2E -Pe2eTask=<id>   # run a single end-to-end natural-spawn task
```

- **JUnit** (`./gradlew test`, no Minecraft) — covers the SSE/tool-call chunk protocol, error mapping, watchdogs, retry policy, history compression, repeat-call guard, stall guard, loop engine, presets, skills, agent definition.
- **Fabric gametests** (`./gradlew runGametestServer`) — covers the assistant lifecycle, config UI, multi-assistant coexistence, inventory, mining, loop events. Needs a reachable mock LLM endpoint (e.g. `bin/mock_llm_server.py`).
- **End-to-end** (`./gradlew runE2E`) — boots a real world with a fixed seed, drives a player-form assistant from spawn with `general_agent`, verifies the outcome against the owner's inventory. Run a single task or `bash bin/run_e2e_all.sh` for the whole suite. Results land in `run/logs/e2e-results.txt`.

---

## Debug mode

When enabled, business logs (chat send/receive, LLM requests and replies, tool calls and results, task state, summon/dismiss, … — **never the API key**) are written to `<game-dir>/logs/opencraft-debug.log` in the format `[timestamp] [category] content`.

- Each session starts fresh: the file is wiped when debug is turned on.
- Default on at boot: JVM flag `-Dopencraft.debug=true` or env var `OPEN_CRAFT_DEBUG=true`.
- Single-session cap of 5 MB; over the cap the file is overwritten from the top.
- Toggle in-game with `/opencraft debug on|off` (op-only).

---

## Project structure

```
src/
├── main/java/com/swaydy/opencraft/
│   ├── OpenCraftMod.java          # mod entrypoint, registers blocks/commands/packets
│   ├── agent/                     # AgentRuntime (thin driver), ToolExecutor,
│   │                              #   LlmRetryPolicy / RepeatToolGuard / StallGuard,
│   │                              #   GameContext (dynamic), HistoryCompactor,
│   │                              #   TaskPlan / TaskCompletionGuard, presets/, skills/, hooks/
│   ├── ai/                        # LlmClient (SSE + tool calls), AiCompanionService,
│   │                              #   AiBlockConfig / AiConfigHandler (config UI plumbing)
│   ├── assistant/                 # AiAssistant interface, AssistantFacade, player/ (bot)
│   ├── plugins/                   # Built-in plugins: assistant control + player actions,
│   │                              #   presets/
│   ├── block/                     # AI Logo Block + block entity (config storage)
│   ├── command/                   # /opencraft command tree
│   ├── inventory/                 # Double-panel inventory menu (right-click the bot)
│   ├── loop/                      # Loop engine + registry + Minecraft wiring, presets/
│   ├── e2e/                       # End-to-end natural-spawn harness + trace + replay
│   ├── logging/                   # SLF4J → debug file logger
│   ├── net/                       # Custom networking payloads
│   └── test/                      # Fabric gametests
├── client/java/com/swaydy/opencraft/client/
│   ├── OpenCraftModClient.java    # client entrypoint
│   ├── gui/                       # Config UI (4 tabs), double-panel inventory screen
│   ├── render/                    # Bot entity renderer, world-space streaming overlay
│   ├── skin/                      # Skin picker
│   └── ShotAutoCapture.java       # Dev-only e2e screenshot helper
└── main/resources/                # fabric.mod.json, lang files, textures, recipes, loot
```

---

## Further reading

- [docs/agent-architecture.md](docs/agent-architecture.md) — Agent design and its mapping to DeepSeek Harness.
- [CLAUDE.md](CLAUDE.md) — developer guidance for working in this repo (build commands, architecture summary, conventions).

---

## Versions

| Component | Version |
|---|---|
| Minecraft | 1.21.11 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.141.4+1.21.11 |
| Fabric Loom | 1.17-SNAPSHOT |
| Java | 21 |
| Gradle | 9.5.1 |

---

## License

CC0-1.0 (inherited from the FabricMC example-mod template).
