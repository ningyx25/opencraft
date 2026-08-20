# plugins 包文件说明

本包是「插件系统」的内置插件实现：每个插件实现 `AssistantPlugin` 接口，向 LLM 暴露 OpenAI 格式的 function-calling 工具（`ToolDefinition`），由 `AgentRegistry` 按预设装配。

| 文件 | 插件 ID | 形态 | 作用 / 提供工具 |
|---|---|---|---|
| `AssistantControlPlugin.java` | `assistant_control` | 通用（所有预设都装） | 基础控制插件：`teleport_to_player` —— 让助手瞬间传送到主人身边（支持跨维度）。跟随/待命模式已移除，故无 `set_mode` 工具 |
| `PlayerActionsPlugin.java` | `player_actions` | **玩家形态**（当前唯一新形态） | 玩家式动作插件，核心能力：`player_goto`（移动）/`player_stop`（停下）/`player_jump`（跳跃）/`player_look`（观察）/`player_find`（按关键词找东西返回精确坐标+方位+距离）/`player_mine`（用真实 `ServerPlayerGameMode.destroyBlock` 挖掘）/`player_place`（`useItemOn` 放置）/`player_craft`（用玩家背包按玩家规则合成）/`player_inventory`（背包/装备清单）/`player_hand_to_player`（递物给主人）。另通过 `gameContextFragment` 每轮注入「助手状态 + 环境摘要」 |
| `MovementPlugin.java` | `movement` | 实体形态（旧存档遗留） | 移动插件：`goto`（下达 `MoveToBlockTask` 异步移动）/`stop`（取消当前移动/挖掘/攻击任务）；`gameContextFragment` 注入当前任务描述 |
| `PerceptionPlugin.java` | `perception` | 实体形态（旧存档遗留） | 感知插件（agentic loop 的"眼睛"）：`look_around`（坐标/朝向/任务状态/周围方块计数/附近实体含距离/脚下与头顶安全）、`inspect_block`（方块 id/硬度/可挖掘性）；`gameContextFragment` 注入坐标+任务状态 |
| `MiningPlugin.java` | `mining` | 实体形态（旧存档遗留） | 挖掘插件：`mine` 异步下达 `MineBlockTask`，掉落物进助手背包；安全约束（仅主人维度、距离 ≤ maxDistance、不挖基岩/容器/配置方块） |
| `InventoryPlugin.java` | `inventory` | 实体形态（旧存档遗留） | 物品插件：`list_inventory`（背包/装备清单，可看主人）、`equip`（从背包装备到对应装备栏）、`hand_to_player`（递物给主人，背包满掉脚边）；`gameContextFragment` 每轮注入【我的背包】【我的装备】 |
| `CraftingPlugin.java` | `crafting` | 实体形态（旧存档遗留） | 合成插件：`craft` 用助手背包材料合成，规则与玩家一致（2×2 及更小配方随时可合，3×3 需附近有工作台），产物进背包 |
| `CombatPlugin.java` | `combat` | 实体形态（旧存档遗留） | 战斗插件：`attack` 按名字/类型攻击附近 16 格内目标（默认最近的怪物；只打敌对生物，不攻击玩家），异步下达 `AttackTask` |
| `ToolArgs.java` | — | 工具类（包级私有） | 工具参数读取小工具：`intOf`/`strOf`/`has` 安全读取 JSON 参数，避免各插件重复 try/catch |
| `ToolSchema.java` | — | 工具类（包级私有） | 构建 OpenAI function-calling 的 parameters JSON Schema 小工具：`prop`（单项 type+description）、`object`（构造 `{type, properties, required}`） |

> 说明：`PlayerActionsPlugin`（玩家形态）与其余实体形态插件（`MovementPlugin`/`PerceptionPlugin`/`MiningPlugin`/`InventoryPlugin`/`CraftingPlugin`/`CombatPlugin`）功能对应——玩家形态用真实 `ServerPlayerGameMode`/玩家背包执行，实体形态走实体任务系统。实体形态插件仅服务于旧存档遗留的实体助手与回归测试；新召唤的助手一律是玩家形态。
