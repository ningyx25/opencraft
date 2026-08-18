# OpenCraft

一个为 Minecraft 1.21.11 (Fabric) 开发的模组，目标是给游戏增添 **AI 游戏助手**，陪玩家一起冒险、建造、生存。

助手是一个**真正的 ServerPlayer（bot）**——像多人联机客户端一样进服、绑定到「AI 徽标方块」的真玩家，**召唤后停留在原地**（不再有跟随/待命模式），只受显式指令驱动（player_goto 移动、teleport_to_player 传送到身边），并可通过聊天对话或**原生 function calling 工具调用**真正动手改变游戏世界——挖掘、放置、合成、递物品，全都能做。任意兼容 OpenAI 的 Chat Completions 接口即可接入（支持流式 SSE）。

## 功能

### AI 游戏助手（真正的玩家 Bot）
- **AI 助手就是一个真正的 ServerPlayer（bot）**——像多人联机客户端一样加入服务器（`PlayerList.placeNewPlayer` 正式进服，出现在玩家列表 / Tab / 实体追踪中，对其他人就是一个客户端玩家）。**这与 Agent 预设无关**：预设只决定大模型的工具与行为，助手的“玩家形态”是它自身的形态。
- 通过 `/opencraft summon` 或右键 AI 徽标方块点“用本方块召唤助手”召唤，绑定它召唤时使用的 AI 徽标方块。**不自动跟随**：召唤后停留在原地，只有显式移动指令（`player_goto` 等）会驱动它；`teleport_to_player` 可让它瞬移到你身边。右键助手打开**互动界面**（和这个助手聊天、送走它）；未绑定助手右键=绑定主人。
- **拥有普通玩家的全部内容（可以不用，但不能没有）**：真正的 43 槽玩家背包（36 主背包 + 装备 + 副手 + 身体 + 坐骑鞍）、游戏模式、经验；玩家式动作用真实的 `ServerPlayerGameMode` 执行（`player_mine` 破坏 / `player_place` 放置 / `player_craft` 合成 / `player_hand_to_player` 递物），掉落物自动拾进背包。
- 生命形态：生存模式 + 无敌 + 食物补满——拥有玩家的一切能力，但作为陪玩助手不会轻易死。
- 存档持久化：绑定状态、背包/装备随玩家存档保存（送走/服务器停止自动落盘，重新召唤读回）。
- **多助手共存**：每个 AI 徽标方块最多绑定一个助手，一个玩家可以同时拥有多个助手（各绑定不同的方块）。聊天时以 `[名字 (x,y,z)]` 区分是哪个助手在说话。
- 旧存档兼容：早期实体形态（PathfinderMob）的助手仍可被查找/送走；重新召唤同一方块时自动迁移为玩家 bot。

### 大模型对话
- `/opencraft ask <消息>` 让“最近的”AI 助手回答你（按绑定方块距离），回复以 `[名字 (x,y,z)]` 前缀广播到游戏聊天。
- **多助手时指定和谁对话**：`/opencraft ask <名字> <消息>` 精确指定某位助手（Tab 可补全助手名；同名助手用 `名字(坐标)` 区分；名字不存在时回退到最近的助手）。
- **回复是流式的（SSE）**：大模型逐字生成时，回复会实时显示在提问者屏幕下方的快捷栏上方（action bar），生成完毕后再把完整回复广播到聊天——不再需要干等整段回复。
- 每个助手（按绑定方块）拥有独立的对话记忆；`/opencraft reset [all]` 可清空最近/全部助手的记忆。
- 助手会获得玩家的实时游戏状态（维度、坐标、时间、生命、饥饿、手持物品等）作为上下文，回答更贴合当前游戏。
- AI 请求（含流式读取）在独立线程池中异步执行，不阻塞服务端主线程；接口不支持流式时自动退化为一次性返回完整回复。

### Agent 预设与插件工具（原生 function calling）
助手通过 **Agent 预设**决定大模型的**行为方式**——每个预设组合若干**插件**，插件向大模型暴露 OpenAI 格式的工具（tools schema），由 LLM 原生 function calling 决定调用哪个工具，服务端执行后把结果喂回模型，循环直到模型给出最终文本回复（agentic loop）。**预设与身体形态解耦**：无论选哪个预设，助手都是玩家形态的真玩家 bot。

内置 **2 个预设**（在配置界面「对话与动作」页下拉切换）：

| 预设 | 装配的插件 | 可用工具 | 最大工具轮次 |
|---|---|---|---|
| `chat_agent`（纯聊天） | 助手控制 | `teleport_to_player`（瞬移到主人身边） | 1 |
| `general_agent`（全能，默认） | 助手控制 + 玩家动作 | 上表 2 个 + `player_goto/player_stop/player_look/player_mine/player_place/player_craft/player_inventory/player_hand_to_player` | 8 |

玩家动作插件（`PlayerActionsPlugin`，玩家 bot 的核心能力）——**全部用真实的玩家方式执行**：

| 工具 | 作用 |
|---|---|
| `player_goto` / `player_stop` | 以玩家身份走到指定坐标 / 停下（bot 式移动，带碰撞与重力） |
| `player_look` | 观察周围：坐标/朝向/附近方块/实体/掉落物、是否在移动、背包装备摘要 |
| `player_mine` | 走到方块旁用真实的 `ServerPlayerGameMode.destroyBlock` 破坏（掉落物自动拾进背包；不能挖基岩/容器/配置方块） |
| `player_place` | 用主手物品以 `useItemOn` 贴着指定面放置方块 |
| `player_craft` | 用自己背包按**与玩家完全一致**的规则合成（2×2 及更小配方随时可合，3×3 配方需附近有工作台），产物进自己背包 |
| `player_inventory` | 列出自己（或主人）的玩家背包与装备 |
| `player_hand_to_player` | 从自己背包取出物品递给主人（主人背包满则掉主人脚边） |

> 早期实体形态（PathfinderMob）的插件（`goto`/`mine`/`attack`/`craft` 等）仍保留在插件注册表中，仅服务于旧存档遗留的实体助手与回归测试；新召唤的助手一律是玩家形态，使用上表的玩家式工具。

### 指令
| 指令 | 说明 |
|---|---|
| `/opencraft ask <消息...>` | 和“最近的”AI 助手聊天（按绑定方块距离） |
| `/opencraft ask <名字> <消息...>` | 和指定名字的助手聊天（多助手时用；Tab 补全名字） |
| `/opencraft summon` | 召唤一个助手（自动绑定最近的未绑定方块） |
| `/opencraft dismiss [all]` | 送走最近 / 全部助手 |
| `/opencraft status` | 列出你的全部助手及各自配置状态 |
| `/opencraft reset [all]` | 清空最近 / 全部助手的对话记忆 |
| `/opencraft debug [on\|off]` | 查看/切换调试模式（op；on/off 见下节） |
| `/opencraft help` | 显示帮助 |

### 调试模式（开发测试用）
开启后，mod 的业务日志（对话收发、LLM 请求/回复、每次工具调用与结果、任务开始/完成/失败、物品拾取、召唤/送走/配置变更等，**不含 API Key**）会写入 **`<游戏目录>/logs/opencraft-debug.log`**，每行格式 `[时间] [分类] 内容`，方便开发测试排查问题：

- 日志文件为**覆盖式**：每次开启调试模式都会清空旧日志，只保留本次会话新写入的内容；
- 启动时默认开启：JVM 参数 `-Dopencraft.debug=true`，或环境变量 `OPEN_CRAFT_DEBUG=true`（首次写入前清空旧日志）；
- 游戏内动态切换：`/opencraft debug`（查看状态与文件路径）、`/opencraft debug on|off`（需要 op 权限）；
- 单次会话内日志超过 5 MB 自动从头重写；默认关闭（不产生日志文件）。

### AI 徽标方块 —— 游戏内唯一的 AI 配置载体
- **可获取**：有合成配方（4 铁锭 + 4 红石 + 1 玻璃），徒手/任意工具挖掘都会掉落自身（战利品表无工具条件）。
- **普通右键**：打开游戏内配置编辑器，编辑本方块保存的全部 AI 配置（分 4 页 Tab）：
  - 「接口与密钥」：接口地址、API Key、模型、语言；
  - 「对话与动作」：助手名字、**Agent 预设**（`chat_agent` / `general_agent` 下拉）、温度、请求超时、对话记忆条数；
  - 「行动行为」：行动最大距离（工具目标离主人的上限）、移动速度；
  - 「聊天」：内置聊天窗口——不用 `/opencraft ask` 也能直接和本方块的助手对话（回复以流式增量实时显示在窗口里，与命令行共享同一份对话记忆；本方块还没有助手时发送第一条消息会自动召唤一个绑定本方块）。
  - 点“保存配置”立即生效。**只有管理员（op）可以保存**；非管理员只读。
  - **默认配置**：接口地址、API Key、模型的默认值**在编译期从项目根目录 `.env` 烘焙进 jar**（以 XOR 混淆字节存储，jar 内无明文）：把 `OPEN_CRAFT_BASE_URL`、`OPEN_CRAFT_MODEL`、`OPEN_CRAFT_API_KEY` 写进 `.env` 再 `./gradlew build`，生成的 jar 放到任何环境（游戏启动器/服务器）都自带这些默认值。运行时优先级：JVM 参数（`-Dopencraft.*`）> 环境变量（`OPEN_CRAFT_*`）> jar 内烘焙值 > 代码内置回退（OpenAI 官方地址 `https://api.openai.com/v1` / `gpt-4o` / 混淆默认密钥）。助手名字默认「小智」——新放置的方块可直接用。
  - **安全**：API Key 的任何部分都不会发送到客户端/显示在界面——只显示“已设置（已隐藏）/未设置”；更换密钥需勾选“更换 API Key”，输入框以圆点掩码显示，留空表示清除。
  - **“召唤/不召唤助手”合并按钮**：底部按钮 = 原来的“AI 功能”开关与“用本方块召唤助手”合并而来——本方块还没有助手时点击**用本方块召唤助手**（把 AI 助手绑定到本方块，一个方块最多一个助手，助手运行时配置全部读取本方块保存的内容）；已绑定你自己的助手时按钮变为**送走本方块助手**（取消召唤）；已被他人助手绑定时按钮禁用。
- **激活状态自动管理**：有 AI 助手绑定本方块（被召唤）时方块亮起（亮度 15，切换为发光贴图）；助手被送走/消失后自动熄灭。潜行右键可查看当前状态说明。

## 配置方式（纯游戏内，无外部文件）

**AI 助手的配置只保存在游戏内的 AI 徽标方块实体里**（每个方块一份，随方块存档持久化），不再依赖任何外部配置文件：

1. 放置 AI 徽标方块（合成：4 铁锭 + 4 红石 + 1 玻璃）；
2. 右键方块 → 在配置编辑器里填好接口地址（任意 OpenAI 兼容的 Chat Completions 接口）、模型、API Key 等；
3. 点底部“用本方块召唤助手”→ 助手绑定该方块（召唤后停在原地，可用 `player_goto`/`teleport_to_player` 指挥它），`/opencraft ask` 聊天、行动参数等全部使用该方块的配置；再点同一按钮（此时显示“送走本方块助手”）即可取消召唤；
4. 修改配置 → 点“保存配置”立即生效；换方块=换配置（每个方块独立）。

**多助手共存**：每个 AI 徽标方块最多绑定一个助手；想同时拥有多个助手，就放置多个 AI 徽标方块并分别配置、分别召唤——它们会同时停留在各自的位置待命，`/opencraft ask <消息>` 由绑定方块离你最近的助手回答，`/opencraft ask <名字> <消息>` 可精确指定某一位，聊天前缀 `[名字 (x,y,z)]` 可区分是谁在说话。破坏方块时，绑定它的助手（及其记忆）一起消失。

## 构建与运行

```bash
./gradlew build              # 编译 + 打包（含纯 Java 单元测试）
./gradlew runClient          # 启动客户端
./gradlew runServer          # 启动专用服务器
./gradlew runGametestServer  # 运行 Fabric 游戏测试（无头服务器）
./gradlew test               # 仅运行 JUnit 单元测试
```

要求：JDK 21+（CI 使用 JDK 25 构建，`--release 21` 锁定字节码版本）。

- 纯 Java 单元测试在 `src/test/java/`（JUnit 5），验证 LLM 客户端的 SSE 工具调用分片合并，不需要 Minecraft 运行时。
- Fabric 游戏测试在 `src/main/java/com/swaydy/opencraft/test/`，覆盖助手生命周期、配置编辑器、多助手共存、右键互动、聊天窗口、**玩家式背包/拾取/装备/挖掘掉落**等，需配置可达的 mock LLM 端点。

## 项目结构

- `src/main/java/com/swaydy/opencraft/` —— 通用代码（服务端/客户端共用）
  - `OpenCraftMod.java` —— 模组入口（`ModInitializer`），注册方块/实体/命令/网络包
  - `agent/` —— Agent 框架：`AgentRuntime`（agentic loop）、`AgentRegistry`（注册表）、`AgentDefinition`（预设定义）、`AssistantPlugin`（插件接口）、`ToolDefinition`/`ToolContext`/`ToolResult`
  - `assistant/` —— 助手形态抽象与跨形态路由：`AiAssistant`（实体/玩家统一接口）、`AssistantFacade`（一方块一助手的统一查找/召唤/送走/对话）、`player/`（玩家形态：`AiAssistantPlayer` 真 ServerPlayer 假玩家 + `FakeConnection` 黑洞连接 + `PlayerMovementController` 移动 + `PlayerAssistantService` 生命周期）
  - `plugins/` —— 内置插件：助手控制、移动、感知、挖掘、物品、合成、战斗、玩家动作
  - `presets/` —— Agent 预设：`chat_agent`（纯聊天）、`general_agent`（全能，玩家式行动）——只决定 LLM 行为，不决定身体形态
  - `ai/` —— 大模型客户端与配置：`LlmClient`（OpenAI 兼容 Chat Completions，SSE 流式 + 工具调用）、`AiCompanionService`（对话/召唤/历史服务）、`AiBlockConfig`（配置模型，存在方块实体里）、`AiConfigData`（网络传输）、`AiConfigHandler`（服务端处理）
  - `entity/` —— AI 助手实体（旧存档遗留形态）、任务系统（`AssistantTask`/`TaskHostGoal`/`MoveToBlockTask`/`MineBlockTask`/`AttackTask`）、实体注册
  - `block/` —— AI 徽标方块与方块实体（配置载体）
  - `command/` —— `/opencraft` 指令
  - `net/` —— 自定义网络包（`AiConfigPayloads`、`AssistantPayloads`）
  - `mixin/` —— 通用 mixin
  - `test/` —— Fabric 游戏测试
- `src/client/java/com/swaydy/opencraft/client/` —— 客户端代码
  - `OpenCraftModClient.java` —— 客户端入口（`ClientModInitializer`），注册渲染器与 S2C 接收器
  - `gui/` —— 配置界面 `AiConfigScreen`（4 页 Tab）、助手互动界面 `AiAssistantInteractScreen`
  - `render/` —— 助手实体渲染器
  - `mixin/` —— 客户端 mixin
- `src/main/resources/` —— `fabric.mod.json`、mixin 配置、语言文件（en_us/zh_cn）、贴图、方块模型、合成配方、战利品表
- `src/test/java/` —— 纯 Java JUnit 单元测试

## 关键版本

| 组件 | 版本 |
|---|---|
| Minecraft | 1.21.11 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.141.4+1.21.11 |
| Fabric Loom | 1.17-SNAPSHOT |
| Java | 21 |
| Gradle | 9.5.1 |

## License

CC0-1.0（保留自 FabricMC example-mod 模板的许可声明）。
