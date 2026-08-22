# OpenCraft

一个为 Minecraft 1.21.11（Fabric）开发的 AI 游戏助手模组。助手是一个**真正的 ServerPlayer bot**——像多人联机玩家一样进服、出现在玩家列表与实体追踪中，由大模型通过原生 function calling 驱动，能够挖掘、放置、合成、递物品，支持任意 OpenAI 兼容的 Chat Completions 接口（含流式 SSE）。

## 演示

https://github.com/user-attachments/assets/c4a91eff-5ded-4788-9cba-8ecffc6c3d61

---

## 功能概览

### AI 助手（真实玩家 Bot）

- 通过 `/opencraft summon` 或右键 AI 徽标方块召唤，助手以正式 `PlayerList.placeNewPlayer` 方式进服，拥有 43 槽玩家背包、游戏模式、经验值。
- **默认跟随**：平时自动跟着主人走（太远/跨维度会直接瞬移回主人身边）；当你下达任务指令时退出跟随、专注执行，指令完成后自动回到跟随。
- 生存模式 + 无敌 + 食物补满——拥有玩家的全部能力，不会轻易死亡。
- 绑定状态与背包随存档持久化，重新召唤后自动读回。
- **多助手共存**：每个 AI 徽标方块绑定一个助手，一位玩家可同时拥有多个助手。

### 大模型对话

- `/opencraft ask <消息>` 与最近的助手对话；`/opencraft ask <名字> <消息>` 精确指定某位助手（Tab 补全）。
- **流式回复**：大模型生成时逐字实时显示在 action bar，生成完毕后广播到聊天栏。
- 每个助手拥有独立对话记忆，记忆过长时自动压缩为摘要而非直接丢弃。
- 助手上下文包含玩家实时游戏状态（维度、坐标、时间、生命、饥饿、手持物品等）。
- AI 请求在独立线程池异步执行，不阻塞服务端主线程。

### Agent 预设与工具调用

助手通过 **Agent 预设**决定大模型的行为——每个预设组合若干插件，插件向大模型暴露 OpenAI 格式的工具（tools schema），由 LLM 原生 function calling 决定调用，服务端执行后把结果喂回，循环直到模型给出最终回复（agentic loop）。

内置两个预设（在配置界面「对话与动作」页切换）：

| 预设 | 插件 | 最大工具轮次 |
|---|---|---|
| `chat_agent`（纯聊天） | 助手控制 | 3 |
| `general_agent`（全能，默认） | 助手控制 + 玩家动作 | 250 |

玩家动作工具（`general_agent` 专属，全部以真实玩家方式执行）：

| 工具 | 作用 |
|---|---|
| `player_goto` / `player_stop` / `player_jump` | 走到指定坐标 / 停止移动 / 跳一下 |
| `player_look` | 观察周围：坐标、朝向、附近方块/实体/掉落物、背包摘要 |
| `player_find` | 按关键词找方块/实体/掉落物，返回精确坐标 + 方位 + 距离 |
| `player_mine` | 走到方块旁用 `ServerPlayerGameMode.destroyBlock` 破坏，掉落物自动拾取 |
| `player_place` | 用主手物品以 `useItemOn` 贴着指定面放置方块 |
| `player_craft` | 按玩家规则合成（2×2 随时可合，3×3 需附近有工作台） |
| `player_inventory` | 查看自己或主人的背包与装备 |
| `player_hand_to_player` | 从背包取物品递给主人（背包满则掉在主人脚边） |
| `teleport_to_player` | 瞬间传送到主人身边（支持跨维度，所有预设可用） |

**Agentic loop 健壮性**：自动退避重试网络错误；检测重复工具调用死循环并打断；工具结果超长自动裁剪；对话历史过长时 LLM 压缩为摘要；破坏性操作前向玩家提问确认（`/opencraft answer` 回复，90 秒超时后按合理假设继续）；多步任务自动列计划并实时更新进度。

### AI 徽标方块

合成配方：**4 铁锭 + 4 红石 + 1 玻璃**（可徒手挖掘，必定掉落自身）。

右键打开游戏内配置编辑器（4 页 Tab）：

| 页签 | 配置项 |
|---|---|
| 接口与密钥 | 接口地址、API Key、模型、语言 |
| 对话与动作 | 助手名字、Agent 预设、温度、请求超时、记忆条数 |
| 行动行为 | 行动最大距离、移动速度 |
| 聊天 | 内置聊天窗口（流式增量显示，与命令行共享记忆） |

- 点"保存配置"立即生效；仅 op 可保存，非 op 只读。
- API Key 不会发送到客户端，界面只显示"已设置（已隐藏）/未设置"。
- 底部按钮兼具召唤与送走功能：无助手时点击召唤，已绑定时点击送走。
- 有助手绑定时方块亮起（亮度 15），助手消失后自动熄灭。

**默认配置烘焙**：将 `OPEN_CRAFT_BASE_URL`、`OPEN_CRAFT_MODEL`、`OPEN_CRAFT_API_KEY` 写入项目根目录 `.env` 后执行 `./gradlew build`，生成的 jar 自带这些默认值（XOR 混淆存储，jar 内无明文）。运行时优先级：JVM 参数 > 环境变量 > jar 内烘焙值 > 代码内置回退。

---

## 快速上手

1. 放置 AI 徽标方块（合成：4 铁锭 + 4 红石 + 1 玻璃）。
2. 右键方块，在配置编辑器中填入接口地址、模型和 API Key，点"保存配置"。
3. 点底部"用本方块召唤助手"，助手以玩家 bot 形态进服并默认跟随你。
4. 用 `/opencraft ask <消息>` 开始对话，或直接在配置界面的聊天页输入。
5. 用 `/opencraft dismiss` 送走助手；破坏方块时绑定的助手与记忆一并移除。

---

## 指令

| 指令 | 说明 |
|---|---|
| `/opencraft ask <消息>` | 与最近的助手对话 |
| `/opencraft ask <名字> <消息>` | 与指定助手对话（Tab 补全名字） |
| `/opencraft answer <回答>` | 回答助手提出的确认问题 |
| `/opencraft interrupt`（别名 `stop`） | 中断最近助手正在执行的任务 |
| `/opencraft summon` | 召唤助手（绑定最近的未绑定方块） |
| `/opencraft dismiss [all]` | 送走最近 / 全部助手 |
| `/opencraft status` | 列出全部助手及配置状态 |
| `/opencraft reset [all]` | 清空最近 / 全部助手的对话记忆 |
| `/opencraft debug [on\|off]` | 查看或切换调试模式（需 op） |
| `/opencraft help` | 显示帮助 |

---

## 调试模式

开启后，业务日志（对话收发、LLM 请求/回复、工具调用与结果、任务状态、召唤/送走等，**不含 API Key**）写入 `<游戏目录>/logs/opencraft-debug.log`，格式为 `[时间] [分类] 内容`。

- 每次开启会清空旧日志，只保留本次会话内容。
- 启动时默认开启：JVM 参数 `-Dopencraft.debug=true` 或环境变量 `OPEN_CRAFT_DEBUG=true`。
- 单次会话日志超过 5 MB 自动从头重写；默认关闭。

---

## 构建与运行

要求：JDK 21+（CI 使用 JDK 25，`--release 21` 锁定字节码版本）。

```bash
./gradlew build              # 编译 + 打包（含纯 Java 单元测试）
./gradlew runClient          # 启动客户端
./gradlew runServer          # 启动专用服务器
./gradlew runGametestServer  # 运行 Fabric 游戏测试（无头服务器）
./gradlew test               # 仅运行 JUnit 单元测试
```

- 纯 Java 单元测试（`src/test/java/`，JUnit 5）：验证 LLM 客户端的 SSE 工具调用分片合并，无需 Minecraft 运行时。
- Fabric 游戏测试（`src/main/java/com/swaydy/opencraft/test/`）：覆盖助手生命周期、配置编辑器、多助手共存、背包/拾取/挖掘等，需配置可达的 mock LLM 端点。

---

## 项目结构

```
src/
├── main/java/com/swaydy/opencraft/
│   ├── OpenCraftMod.java          # 模组入口，注册方块/命令/网络包
│   ├── agent/                     # Agent 框架：AgentRuntime、预设注册、插件接口
│   ├── assistant/                 # 助手抽象：AiAssistant 统一接口、AssistantFacade、
│   │                              #   player/（AiAssistantPlayer 真 ServerPlayer bot）
│   ├── plugins/                   # 内置插件：助手控制、移动、感知、挖掘、物品、合成
│   ├── presets/                   # Agent 预设：chat_agent、general_agent
│   ├── ai/                        # LLM 客户端（SSE 流式 + 工具调用）、配置模型
│   ├── block/                     # AI 徽标方块与方块实体
│   ├── command/                   # /opencraft 指令
│   ├── inventory/                 # 右键助手的双面板背包菜单（AssistantInventoryMenu）
│   ├── net/                       # 自定义网络包
│   └── test/                      # Fabric 游戏测试
├── client/java/com/swaydy/opencraft/client/
│   ├── OpenCraftModClient.java    # 客户端入口
│   ├── gui/                       # 配置界面（4 页 Tab）、右键助手的双面板背包界面
│   └── render/                    # 助手实体渲染器、世界内流式浮层
└── main/resources/                # fabric.mod.json、语言文件、贴图、配方、战利品表
```

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

## License

CC0-1.0（保留自 FabricMC example-mod 模板的许可声明）。
