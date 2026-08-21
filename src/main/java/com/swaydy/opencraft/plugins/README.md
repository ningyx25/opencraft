# plugins 包文件说明

本包是「插件系统」的内置插件实现：每个插件实现 `AssistantPlugin` 接口，向 LLM 暴露 OpenAI 格式的 function-calling 工具（`ToolDefinition`），由 `AgentRegistry` 按预设装配。

| 文件 | 插件 ID | 形态 | 作用 / 提供工具 |
|---|---|---|---|
| `AssistantPlugin.java` | — | 插件接口（SPI） | 「万物皆插件」的能力单元接口：可贡献工具（`tools()`）、system 提示词片段、游戏上下文片段、实体 AI Goal；助手能力 = 其预设装配的插件之和。原在 `agent/` 包，已移入本包。 |
| `AssistantControlPlugin.java` | `assistant_control` | 通用（所有预设都装） | 基础控制插件：`teleport_to_player` —— 让助手瞬间传送到主人身边（支持跨维度）。跟随/待命模式已移除，故无 `set_mode` 工具 |
| `PlayerActionsPlugin.java` | `player_actions` | **玩家形态**（当前唯一新形态） | 玩家式动作插件，核心能力：`player_goto`（移动）/`player_stop`（停下）/`player_jump`（跳跃）/`player_look`（观察）/`player_find`（按关键词找东西返回精确坐标+方位+距离）/`player_mine`（用真实 `ServerPlayerGameMode.destroyBlock` 挖掘）/`player_place`（`useItemOn` 放置）/`player_craft`（用玩家背包按玩家规则合成）/`player_inventory`（背包/装备清单）/`player_hand_to_player`（递物给主人）。另通过 `gameContextFragment` 每轮注入「助手状态 + 环境摘要」 |
| `ToolContext.java` | — | 工具类型 | 一次工具调用的执行上下文（record）：server / assistant（形态无关的 AiAssistant）/ owner（提问玩家）/ level；提供 `assistantEntity()`/`assistantPlayer()` 便捷强转。原在 `agent/` 包，已移入本包。 |
| `ToolDefinition.java` | — | 工具类型 | 单项工具定义（record）：工具名 + 给模型的说明 + 参数 JSON Schema + 执行器（服务端线程运行，返回 `ToolResult`）。原在 `agent/` 包，已移入本包。 |
| `ToolResult.java` | — | 工具类型 | 工具执行结果（record）：ok（成功/失败）+ 给模型看的结果文本（失败时模型据此自我纠正）；提供 `ok()`/`error()` 工厂方法。原在 `agent/` 包，已移入本包。 |
| `ToolArgs.java` | — | 工具类（包级私有） | 工具参数读取小工具：`intOf`/`strOf`/`has` 安全读取 JSON 参数，避免各插件重复 try/catch |
| `ToolSchema.java` | — | 工具类（包级私有） | 构建 OpenAI function-calling 的 parameters JSON Schema 小工具：`prop`（单项 type+description）、`object`（构造 `{type, properties, required}`） |

> 说明：旧存档遗留的实体形态插件（`MovementPlugin`/`PerceptionPlugin`/`MiningPlugin`/`InventoryPlugin`/`CraftingPlugin`/`CombatPlugin`）已删除——它们从未被任何 Agent 预设装配，运行时是死代码；实体形态助手（仅旧存档兼容）作为纯聊天伴侣存在。对应能力由 `PlayerActionsPlugin` 在玩家形态上以真实 `ServerPlayerGameMode`/玩家背包实现。
