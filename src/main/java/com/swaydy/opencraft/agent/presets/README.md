# presets 子包说明（agent/presets/）

Agent 预设（`AgentDefinition`）定义处。预设只决定助手的 LLM 行为（装配哪些插件、人设提示词、工具轮数上限），绝不决定身体形态——助手一律是玩家形态的假玩家 bot。内置预设集中放在本子包，与 `agent/` 框架本身分离（同 `loop/presets/` 的管理思路），方便扩展。

| 文件 | 预设 ID | 作用 | 装配插件 | maxToolRounds |
|---|---|---|---|---|
| `BaseAgent.java` | —（基类） | 预设的 SPI 基类（同 `plugins/presets/AssistantPlugin` 的思路）：预设类继承它、覆写访问器（id / displayName / plugins / personaPrompt / maxToolRounds / skills）声明自身属性，`definition()` 组装不可变的 `AgentDefinition` 供注册表注册。 | — | — |
| `ChatAgent.java` | `chat_agent` | 纯聊天助手：只陪玩家聊天、答疑、给攻略建议，不主动操作世界（不移动/挖矿/合成）。适合只想安心聊天、不希望助手乱动世界的玩家。maxToolRounds=3，容纳偶尔的 `ask_player` 澄清提问 + 恢复后的收尾。 | `AssistantControlPlugin`（teleport_to_player） | 3 |
| `GeneralAgent.java` | `general_agent` | 像普通玩家一样行动的助手（**默认预设**）：**默认自动跟随主人**（玩家下达任务指令后退出跟随专注执行，完成后回到跟随），行动全部用真实玩家方式完成——移动/挖掘/放置/合成/递物 + 传送到主人身边。观察（位置/环境/近旁方块/实体/背包）由 `Assistant State` 上下文每轮自动提供，定向找坐标用 `player_find`。人设强调「读上下文观察→计划→行动→读上下文确认」、失败换做法不重复调用、含糊先问玩家、多步任务用 task_plan。 | `AssistantControlPlugin`（teleport_to_player）+ `PlayerActionsPlugin`（player_goto/player_stop/player_jump/player_find/player_mine/player_place/player_craft/player_item_move/player_hotbar_select/player_hand_to_player） | 250 |

## 备注

- 每个预设类继承 `BaseAgent`、覆写访问器声明属性（id / lang 键 / 插件列表 / persona 提示词 / maxToolRounds / skills），由继承的 `definition()` 组装 `AgentDefinition`；新增内置预设 = 建一个继承 `BaseAgent` 的类 + 在 `AgentRegistry.init()` 注册。
- 预设通过 AI 徽标方块配置界面的「Agent 预设」下拉选择，保存在 `AiBlockConfig.agent`（NBT `Agent`）。
- 核心工具（`ask_player`、`task_plan`）由 `AgentRuntime.coreToolSchemas()` 自动附加，与预设无关。
