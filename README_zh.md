# OpenCraft

为 **Minecraft 1.21.11（Fabric）** 开发的 AI 游戏助手模组。助手是一个**真正的 `ServerPlayer` bot** —— 像多人联机玩家一样进服，出现在玩家列表和实体追踪里，由大模型通过原生 function calling 驱动，能挖掘、放置、合成、递物品。支持任意 OpenAI 兼容的 Chat Completions 接口（流式 SSE）。

> **语言：** [English](README.md) · 中文（本文件）

## 演示

[demo.mp4](docs/media/demo.mp4)

---

## 功能概览

### AI 助手 —— 真实的 `ServerPlayer` bot

- 通过 `/opencraft summon` 或右键 **AI 徽标方块**召唤；以 `PlayerList.placeNewPlayer` 正式进服，拥有完整 43 槽玩家背包、游戏模式、经验值。
- **默认自动跟随** —— 平时跟着主人走，太远 / 跨维度会瞬移回到主人身边；下达任务时退出跟随专注执行，任务结束后自动恢复跟随。
- 生存模式 + 无敌 + 自动补食物 —— 拥有真实玩家的全部能力，不会轻易死亡。
- 绑定状态、背包随存档持久化；重新召唤后自动读回。
- **多助手共存** —— 每个 AI 徽标方块绑定一个助手；一个玩家可以同时拥有多个助手。

### 大模型对话

- `/opencraft ask <消息>` 与最近的助手对话；`/opencraft ask <名字> <消息>` 精确指定某位助手（Tab 补全）。
- **流式回复** —— 生成时逐字实时显示在 action bar，生成完毕后广播到聊天栏。
- 每个助手拥有独立对话记忆；记忆过长时自动压缩为摘要（不直接截断丢失）。
- 动态游戏上下文（维度、坐标、时间、生命、饥饿、手持物品、天气、群系、状态效果、附近怪物、脚下/视线方块、主人在哪）随首轮提问注入，后续轮次靠 `[Current State]` 摘要与工具差分保持新鲜。
- LLM 请求跑在独立 daemon 线程池上，**绝不阻塞**服务端主线程。

### Agent 预设与工具调用

**Agent 预设**决定 LLM 的行为方式 —— 每个预设组合若干插件，插件向 LLM 暴露 OpenAI 风格的 tool schema，模型通过原生 function calling 决定调用哪个，服务端执行后把结果喂回，循环直到模型给出最终文本回复（agentic loop）。

配置界面「Agent 预设」页内置两个：

| 预设 | 插件 | 最大工具轮次 |
|---|---|---|
| `chat_agent`（纯聊天） | 助手控制 | 3 |
| `general_agent`（全能，默认） | 助手控制 + 玩家动作 | 250 |

玩家动作工具（`general_agent` 专属，全部走真实玩家代码路径）：

| 工具 | 作用 |
|---|---|
| `player_goto` / `player_stop` / `player_jump` | 走到坐标 / 停移动 / 跳一下 |
| `player_find` | 按关键词找方块/实体/掉落物，返回精确坐标 + 方位 + 距离 |
| `player_mine` | 走到方块旁用 `ServerPlayerGameMode.destroyBlock` 破坏，掉落物自动拾取 |
| `player_place` | 用主手物品以 `useItemOn` 贴着指定面放置方块 |
| `player_craft` | 按真实玩家规则合成（2×2 随时，3×3 需要附近有工作台） |
| `player_inventory` | 列出完整背包 + 装备（槽号 + 数量 + 耐久） |
| `player_hand_to_player` | 从背包取物品递给主人（背包满则掉在主人脚边） |
| `player_container_open` / `_close` | 像真实玩家右键一样打开/关闭容器（箱子/桶/潜影盒/熔炉 …） |
| `player_container_list` | 查看已打开容器的内容 + 自己的背包（只读） |
| `player_container_take` / `_put` | shift 点击整栈取/放容器与背包之间 |
| `teleport_to_player` | 瞬间传送到主人身边（跨维度，所有预设可用） |

**Agentic loop 健壮性**：网络瞬时错误指数退避重试；重复工具调用死循环检测并打断；超长工具结果头尾截断；历史过长 LLM 压成摘要；破坏性操作前向玩家确认（`/opencraft answer`，90 秒超时后按合理假设继续）；多步任务自动维护计划并实时更新。

### AI 徽标方块

合成配方：**4 铁锭 + 4 红石 + 1 玻璃**（可徒手挖掘，必定掉落自身）。

右键打开游戏内配置编辑器（4 页 Tab）：

| 页签 | 内容 |
|---|---|
| 接口与密钥 | `baseUrl`、API Key、模型 |
| Agent 预设 | 助手名字、Agent 预设、温度、请求超时、记忆条数 |
| 循环事件 | 每个循环的启用开关 + 实时运行状态 |
| 聊天 | 内置聊天窗口（流式，与 `/opencraft ask` 共享记忆） |

- 「保存配置」立即生效。op 可保存，非 op 只读。
- API Key 永远不离开服务端 —— 界面只显示「已设置（已隐藏）/ 未设置」。
- 底部按钮同时承担「召唤 / 送走」：未绑定时点击召唤，已绑定时点击送走。
- 绑定助手时方块亮起（亮度 15）；助手消失后自动熄灭。

**默认配置烘焙**：在项目根目录 `.env` 写入 `OPEN_CRAFT_BASE_URL` / `OPEN_CRAFT_MODEL` / `OPEN_CRAFT_API_KEY` 后执行 `./gradlew build`，生成的 jar 自带这些默认值（XOR 混淆存储，jar 内无明文）。运行时优先级：JVM 参数 > 环境变量 > jar 内烘焙值 > 代码内置回退。

### 循环事件（触发条件 → 执行事件 → 监测条件）

召唤助手后，内置**循环事件模块**立即在绑定的方块上启动。6 个守护型循环开箱即用 —— 全部 `persistent`，每轮结束回 WAITING 而不是销毁：

| 循环 id | 效果 |
|---|---|
| `heal_aura` | 主人生命不满时每 2 秒 +1 HP |
| `feed_aura` | 主人饥饿不满时每 2 秒 +1 饥饿（同时恢复饱和度） |
| `breath_aura` | 水下每 0.5 秒 +60 氧气 |
| `extinguish_fire` | 着火时每 0.5 秒灭火 |
| `pickup_aura` | 助手 5 格内已过拾取保护期的掉落物拉向助手（3D 全方向） |
| `mob_repel` | 主人 6 格内敌对生物每 1 秒击退（不造成伤害） |

框架是通用的 —— 一个循环 = `LoopCondition` + `LoopEvent` + `LoopMonitor` + `LoopDefinition`。`/opencraft loop status` 列出已注册定义和活动实例。引擎设计见 [docs/agent-architecture.md](docs/agent-architecture.md)。

---

## 快速上手

1. 放置 AI 徽标方块（合成：4 铁锭 + 4 红石 + 1 玻璃）。
2. 右键方块，在第一页填入 `baseUrl` / 模型 / API Key，点「保存配置」。
3. 点击底部按钮（**召唤**）—— 助手以玩家 bot 形态进服并默认跟随你。
4. 用 `/opencraft ask <消息>` 对话，或在配置界面的聊天页输入。
5. 用 `/opencraft dismiss` 送走助手；破坏方块会一并清掉助手和它的记忆。

---

## 指令

| 指令 | 说明 |
|---|---|
| `/opencraft ask <消息>` | 与最近的助手对话 |
| `/opencraft ask <名字> <消息>` | 与指定助手对话（Tab 补全；同名助手用 `(x,y,z)` 消歧） |
| `/opencraft answer <回答>` | 回答助手提出的确认问题 |
| `/opencraft interrupt`（别名 `stop`） | 立即中止最近助手正在执行的任务 |
| `/opencraft summon` | 召唤助手（绑定最近的未绑定方块） |
| `/opencraft dismiss [all]` | 送走最近 / 全部助手 |
| `/opencraft status` | 列出全部助手及配置状态 |
| `/opencraft reset [all]` | 清空最近 / 全部助手的对话记忆 |
| `/opencraft loop status` | 列出全部循环事件定义 + 活动实例 |
| `/opencraft debug [on\|off\|status]` | 查看或切换调试模式（需 op） |
| `/opencraft help` | 显示游戏内帮助 |

---

## 构建与运行

要求：**JDK 21+**（CI 使用 JDK 25，`--release 21` 锁定字节码版本）。

```bash
./gradlew build                 # 编译 + 打包（含纯 Java 单元测试）
./gradlew runClient             # 启动 Minecraft 客户端
./gradlew runServer             # 启动专用服务器
./gradlew runGametestServer     # 跑 Fabric 游戏测试（无头服务器）
./gradlew test                  # 只跑 JUnit（无 Minecraft 运行时）
./gradlew runE2E -Pe2eTask=<id>  # 跑单个 e2e 自然生存任务
```

- **JUnit**（`./gradlew test`，无 Minecraft）—— 覆盖 SSE / 工具调用 chunk 协议、错误映射、看门狗、重试策略、历史压缩、重复调用守卫、停滞守卫、循环引擎、循环预设、技能、Agent 预设、任务计划。
- **Fabric 游戏测试**（`./gradlew runGametestServer`）—— 覆盖助手生命周期、配置 UI、多助手共存、背包、挖掘、循环事件。需可达的 mock LLM 端点（`bin/mock_llm_server.py`）。
- **E2E**（`./gradlew runE2E`）—— 在固定种子的真实新存档里，用 `general_agent` 驱动玩家形态助手从出生点开始完成生存任务，按主人/助手背包物品计数验证真实结果。单任务或 `bash bin/run_e2e_all.sh` 全量；结果在 `run/logs/e2e-results.txt`。

---

## 调试模式

开启后，业务日志（对话收发、LLM 请求/回复、工具调用与结果、任务状态、召唤/送走等，**绝不写 API Key**）写入 `<游戏目录>/logs/opencraft-debug.log`，格式 `[时间] [分类] 内容`。

- 每次开启会清空旧日志，只保留本次会话。
- 启动默认开启：JVM 参数 `-Dopencraft.debug=true` 或环境变量 `OPEN_CRAFT_DEBUG=true`。
- 单次会话超 5 MB 自动从头重写。
- 游戏中用 `/opencraft debug on|off` 切换（op 限定）。

---

## 项目结构

```
src/
├── main/java/com/swaydy/opencraft/
│   ├── OpenCraftMod.java          # 模组入口，注册方块/命令/网络包
│   ├── agent/                     # AgentRuntime（薄驱动）、ToolExecutor、
│   │                              #   LlmRetryPolicy / RepeatToolGuard / StallGuard、
│   │                              #   GameContext（动态）、HistoryCompactor、
│   │                              #   TaskPlan / TaskCompletionGuard、presets/、skills/、hooks/
│   ├── ai/                        # LlmClient（SSE + 工具调用）、AiCompanionService、
│   │                              #   AiBlockConfig / AiConfigHandler（配置 UI 接线）
│   ├── assistant/                 # AiAssistant 接口、AssistantFacade、player/（bot）
│   ├── plugins/                   # 内置插件：助手控制 + 玩家动作、presets/
│   ├── block/                     # AI 徽标方块 + 方块实体（配置存储）
│   ├── command/                   # /opencraft 指令树
│   ├── inventory/                 # 右键助手的双面板背包菜单
│   ├── loop/                      # 循环引擎 + 注册表 + Minecraft 接线、presets/
│   ├── e2e/                       # 端到端自然出生任务 harness + trace + replay
│   ├── logging/                   # SLF4J → 调试文件 logger
│   ├── net/                       # 自定义网络包
│   └── test/                      # Fabric 游戏测试
├── client/java/com/swaydy/opencraft/client/
│   ├── OpenCraftModClient.java    # 客户端入口
│   ├── gui/                       # 配置界面（4 页 Tab）、双面板背包屏幕
│   ├── render/                    # 助手实体渲染器、世界内流式浮层
│   ├── skin/                      # 皮肤选择器
│   └── ShotAutoCapture.java       # 开发期 e2e 截图助手
└── main/resources/                # fabric.mod.json、语言文件、贴图、配方、战利品
```

---

## 进一步阅读

- [docs/agent-architecture.md](docs/agent-architecture.md) —— Agent 设计与 DeepSeek Harness 的包映射。
- [CLAUDE.md](CLAUDE.md) —— 在本仓库工作的开发者指引（构建命令、架构概要、约定）。

---

## 版本依赖

| 组件 | 版本 |
|---|---|
| Minecraft | 1.21.11 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.141.4+1.21.11 |
| Fabric Loom | 1.17-SNAPSHOT |
| Java | 21 |
| Gradle | 9.5.1 |

---

## 协议

CC0-1.0（继承自 FabricMC example-mod 模板的许可声明）。
