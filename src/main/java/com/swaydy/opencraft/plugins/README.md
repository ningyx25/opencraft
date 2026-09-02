# plugins 包文件说明

本包是「插件系统」的 SPI 接口、工具类型与内置插件实现：每个插件实现 `AssistantPlugin` 接口，向 LLM 暴露 OpenAI 格式的 function-calling 工具（`ToolDefinition`），由 `AgentRegistry` 按预设装配。

**布局约定**（同 `agent/presets/`、`loop/presets/` 的思路）：插件系统的 SPI 接口（`AssistantPlugin`）与**内置插件实现**集中在 `plugins/presets/` 子包，`plugins/` 根包只留与插件配套的 Tool* 工具类型（它们同时被插件实现与 `AgentRegistry`/`AgentRuntime` 使用）。新增内置插件 = 在 `plugins/presets/` 建一个实现 `AssistantPlugin` 的类 + 在 `AgentRegistry.init()` 注册（并按需加进 Agent 预设的 `plugins()`）。

## plugins/ 根包（工具类型）

| 文件 | 类型 | 作用 |
|---|---|---|
| `ToolContext.java` | 工具类型 | 一次工具调用的执行上下文（record）：server / assistant（形态无关的 AiAssistant）/ owner（提问玩家）/ level；提供 `assistantPlayer()` 便捷强转。原在 `agent/` 包，已移入本包。 |
| `ToolDefinition.java` | 工具类型 | 单项工具定义（record）：工具名 + 给模型的说明 + 参数 JSON Schema + 执行器（服务端线程运行，返回 `ToolResult`）。原在 `agent/` 包，已移入本包。 |
| `ToolResult.java` | 工具类型 | 工具执行结果（record）：ok（成功/失败）+ 给模型看的结果文本（失败时模型据此自我纠正）；提供 `ok()`/`error()`/`deferred()` 工厂方法。原在 `agent/` 包，已移入本包。 |
| `ToolArgs.java` | 工具类 | 工具参数读取小工具：`intOf`/`strOf`/`boolOf`/`has` 安全读取 JSON 参数，避免各插件重复 try/catch（public，供 `presets/` 子包使用） |
| `ToolSchema.java` | 工具类 | 构建 OpenAI function-calling 的 parameters JSON Schema 小工具：`prop`（单项 type+description）、`object`（构造 `{type, properties, required}`）（public，供 `presets/` 子包使用） |

## plugins/presets/（插件 SPI + 内置实现）

| 文件 | 插件 ID | 类型 | 作用 / 提供工具 |
|---|---|---|---|
| `AssistantPlugin.java` | — | 插件接口（SPI） | 「万物皆插件」的能力单元接口：可贡献工具（`tools()`）、system 提示词片段、游戏上下文片段；助手能力 = 其预设装配的插件之和。原在 `agent/` 包，已移入本包。 |
| `AssistantControlPlugin.java` | `assistant_control` | 通用（所有预设都装） | 基础控制插件：`teleport_to_player` —— 让助手瞬间传送到主人身边（支持跨维度）。跟随自动，无 `set_mode`。 |
| `PlayerMovementPlugin.java` | `player_movement` | 玩家动作·移动族 | `player_goto`（走过去，异步 [Event] 续轮）/ `player_stop`（停下并取消挖掘）/ `player_teleport`（同维度瞬移到坐标，走路难到达的兜底，受最大距离缰绳约束）/ `player_jump`（跳跃）。 |
| `PlayerPerceptionPlugin.java` | `player_perception` | 玩家动作·感知族 | `player_find`：按关键词/ID 找方块/实体/掉落物，返回精确坐标+方位+距离（只读）。 |
| `PlayerWorldPlugin.java` | `player_world` | 玩家动作·世界交互族 | `player_mine`（真实 `ServerPlayerGameMode` 破坏进度挖掘，掉落自动拾取，异步 [Event]）/ `player_place`（`useItemOn` 贴面放置，`sneak=true` 对箱子/熔炉放置而非打开）。 |
| `PlayerCraftingPlugin.java` | `player_crafting` | 玩家动作·合成族 | `player_craft`：用助手背包材料按玩家配方书流程合成（随身 2×2 随时可合，3×3 需旁边真有工作台）。 |
| `PlayerInventoryPlugin.java` | `player_inventory` | 玩家动作·背包物品族 | `player_inventory`（查看自己/主人完整背包：逐槽号+数量+耐久+装备，只读）/ `player_item_move`（背包/装备槽位交换，`-1` 丢弃）/ `player_hotbar_select`（选主手快捷栏）/ `player_hand_to_player`（递物品给主人）。 |
| `PlayerContainerPlugin.java` | `player_container` | 玩家动作·容器交互族 | `player_container_open`（`useItemOn` sneak=false 真实右键打开容器，远距异步 [Event]）/ `player_container_list`（容器+助手背包两侧内容，只读）/ `player_container_take`/`player_container_put`（shift-click 整栈取/放）/ `player_container_close`（关闭）。 |
| `PlayerActionMechanics.java` | —（非插件，包内实现） | 玩家动作 capability provider | 玩家 bot 动作的真实实现：上面 6 个能力族插件的 executor 引用本类的<b>无状态静态方法</b>（直接驱动 `AiAssistantPlayer` 的移动/挖掘控制器、玩家背包、容器菜单）。插件 = 模型可见的工具 surface（schema/描述/提示词片段）+ 能力分组；Mechanics = 玩家操作实现，二者分离，工具族可单独组合进不同预设。 |

> **能力族拆分（参考 deepseek-harness）**：dsh 把能力分成 shell / fs / web 等 capability family（Service Definition / Provider / Consumer）。这里把原来的单体 `PlayerActionsPlugin`（1500+ 行、17 个工具）按内聚的能力族拆成 6 个可组合插件（移动 / 感知 / 世界交互 / 合成 / 背包物品 / 容器交互），共享 `PlayerActionMechanics` 的玩家 bot 实现。坐标/环境/近旁方块/附近实体等观察信息为所有预设共需的核心上下文，由 `agent/Prompts` 的 **Assistant State** JSON 段每轮直接注入 system，不占工具调用；背包在上下文里只是摘要，模型需要精确完整视图时调 `player_inventory`。

> 说明：历史上的实体形态插件（`MovementPlugin`/`PerceptionPlugin`/`MiningPlugin` 等）与实体形态助手已随旧存档兼容一并删除——所有动作都在真 ServerPlayer bot 上以真实 `ServerPlayerGameMode`/玩家背包实现（见 `PlayerActionMechanics`）。
