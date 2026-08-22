# presets 目录说明

Agent 预设（`AgentDefinition`）定义处。预设只决定助手的 LLM 行为（装配哪些插件、人设提示词、工具轮数上限），绝不决定身体形态——助手一律是玩家形态的假玩家 bot。

| 文件 | 预设 ID | 作用 | 装配插件 | maxToolRounds |
|---|---|---|---|---|
| `ChatAgent.java` | `chat_agent` | 纯聊天助手：只陪玩家聊天、答疑、给攻略建议，不主动操作世界（不移动/挖矿/合成）。适合只想安心聊天、不希望助手乱动世界的玩家。maxToolRounds=3，容纳偶尔的 `ask_player` 澄清提问 + 恢复后的收尾。 | `AssistantControlPlugin`（teleport_to_player） | 3 |
| `GeneralAgent.java` | `general_agent` | 像普通玩家一样行动的助手（**默认预设**）：**默认自动跟随主人**（玩家下达任务指令后退出跟随专注执行，完成后回到跟随），行动全部用真实玩家方式完成——移动/挖掘/放置/合成/递物/观察 + 传送到主人身边。人设强调「观察→计划→行动→再观察」、失败换做法不重复调用、含糊先问玩家、多步任务用 task_plan。 | `AssistantControlPlugin`（teleport_to_player）+ `PlayerActionsPlugin`（player_goto/player_stop/player_jump/player_look/player_find/player_mine/player_place/player_craft/player_inventory/player_hand_to_player） | 250 |

## 备注

- 每个文件提供静态工厂 `create()` 返回 `AgentDefinition`（id / lang 键 / 插件列表 / persona 提示词 / maxToolRounds）。
- 预设通过 AI 徽标方块配置界面的「Agent 预设」下拉选择，保存在 `AiBlockConfig.agent`（NBT `Agent`）。
- 核心工具（`ask_player`、`task_plan`）由 `AgentRuntime.coreToolSchemas()` 自动附加，与预设无关。
