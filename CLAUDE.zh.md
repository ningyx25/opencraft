# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在本仓库中处理代码时提供指引。

## 项目概述

OpenCraft —— 一个面向 Minecraft 1.21.11 的 Fabric 模组。基于 FabricMC 的 example-mod 模板起步，并重命名为独立身份：mod id `opencraft`，基础包名 `com.swaydy.opencraft`，显示名 "OpenCraft"。

该模组添加了一个**AI 游戏助手**，陪伴玩家同行：一个绑定的伴生实体，会跟随玩家，并可通过 `/opencraft ask <消息>` 与其聊天，回复由任意兼容 OpenAI 的 Chat Completions API 生成（异步、在服务端线程之外执行）。此外还包含原有的 `ai_logo_block` 装饰/功能方块。

## 构建与运行命令

使用 Fabric Loom Gradle 插件 —— 具体是 `net.fabricmc.fabric-loom-remap` 变体，v1.17-SNAPSHOT —— 通过 Gradle wrapper（Gradle 9.5.1）调用。Java 21 为编译目标；需要 JDK 21+ 工具链（CI 使用 JDK 25 构建）。

| 任务 | 命令 |
|---|---|
| 完整构建（编译 + 打 jar + remap） | `./gradlew build` |
| 带模组启动 Minecraft 客户端 | `./gradlew runClient` |
| 带模组启动专用服务器 | `./gradlew runServer` |
| 运行无头 Fabric 游戏测试 | `./gradlew runGametestServer` |
| 运行测试 | `./gradlew test` |
| 仅编译 | `./gradlew compileJava compileClientJava` |
| 清理 | `./gradlew clean` |

- 未配置任何代码检查工具（Checkstyle/Spotless 等）。
- 游戏测试位于 `src/main/java/com/swaydy/opencraft/test/`（在 `fabric-gametest` 入口点下注册），通过 `gametestServer` Loom 运行配置执行（`-Dfabric-api.gametest=true`；过滤器默认为 `opencraft:*`，可用 `./gradlew runGametestServer -PgametestFilter=opencraft:<名字>` 覆盖）。主测试（`assistantLifecycleAndChat`）为模拟玩家召唤助手，触发真实的 LLM HTTP 请求（配置 `ai.baseUrl` 必须指向可达的 OpenAI 兼容端点，例如本地 mock），校验历史记录，然后送走助手。**注意**：gametest 服务器在测试期间会"冲刺"tick（远快于 20tps），而流式回复在独立线程上按墙钟时间到达——因此"等回复/等动作"的断言用 `thenWaitUntil(() -> { if (未就绪) throw new GameTestAssertException(Component.literal(...), (int) helper.getTick()); })` 轮询，而不是固定 `thenIdle(N)`（1.21.11 的 `thenWaitUntil(Runnable)` 靠抛 `GameTestAssertException` 保持轮询；抛其它异常会直接崩服）。
- `./gradlew test` 运行 `src/test/java/` 中的纯 Java JUnit 测试（目前为 `LlmClientToolCallsTest`，针对本地 mock 测试原生 function-calling 的 SSE 工具调用分片合并；不需要 Minecraft 运行时）。JUnit 5 依赖在 `build.gradle` 中声明（包含 `junit-platform-launcher`，Gradle 9.5.1 需要）。
- 构建产物 jar 输出到 `build/libs/`。CI（`.github/workflows/build.yml`）在 push/PR 时运行 `./gradlew build` 并上传 `build/libs/`。

## 架构

### 分离源码集 —— common 与 client

Loom 的 `splitEnvironmentSourceSets()`（在 `build.gradle` 中）将代码分离为两个源码集，使客户端专属类不会被打入专用服务器 jar：

- **Common（通用）** —— `src/main/java/`，包 `com.swaydy.opencraft`。在客户端和专用服务器上均运行。入口点：`com.swaydy.opencraft.OpenCraftMod` 实现 `ModInitializer`（`onInitialize`）。
- **Client（客户端）** —— `src/client/java/`，包 `com.swaydy.opencraft.client`。仅客户端。入口点：`com.swaydy.opencraft.client.OpenCraftModClient` 实现 `ClientModInitializer`（`onInitializeClient`）。渲染、按键绑定、客户端网络通信和客户端 mixin 归于此处。

两个入口点均在 `src/main/resources/fabric.mod.json` 的 `entrypoints.main` 和 `entrypoints.client` 下注册。

### Mixins

两个独立的 mixin 配置，各自 `compatibilityLevel: JAVA_21` 且 `injectors.defaultRequire: 1`：

- **通用 mixin** —— `src/main/resources/opencraft.mixins.json`，包 `com.swaydy.opencraft.mixin`，列于 `mixins` 数组中（目前为 `OpenCraftMixin`，一个对 `MinecraftServer.loadLevel` 的空操作 `@Inject`）。在此添加新的服务端/通用 mixin 类。
- **客户端 mixin** —— `src/client/resources/opencraft.client.mixins.json`，包 `com.swaydy.opencraft.client.mixin`，列于 `client` 数组中（目前为 `OpenCraftClientMixin`，一个对 `MinecraftClient.run` 的空操作 `@Inject`），并在 `fabric.mod.json` 中限定 `environment: "client"`。在此添加新的客户端 mixin 类。

添加 mixin 类时，必须同时将其类名注册到对应 JSON 的数组中 —— 未注册的 mixin 不会生效。由于 `defaultRequire: 1`，目标方法缺失的 mixin 会在加载时直接报错，因此依赖某个 mixin 前请对照当前 mappings 核实目标方法名。

### 命名空间标识符

所有 `Identifier` 都通过 `src/main/java/com/swaydy/opencraft/OpenCraftMod.java` 中的辅助方法路由：

```java
public static final String MOD_ID = "opencraft";
public static Identifier id(String path) {
    return Identifier.fromNamespaceAndPath(MOD_ID, path);
}
```

`MOD_ID` 是命名空间的唯一真相来源（SLF4J logger 也以它命名）。不要手工构造使用其它命名空间的 `Identifier`。

### Mappings

使用 `loom.officialMojangMappings()`（官方 Mojang mappings，非 Yarn）—— 在 `build.gradle` 中设置。请参考现有源文件获取正在使用的确切类名/导入名（例如入口点导入了 `net.minecraft.resources.Identifier`）。

## AI 助手模块（通用代码）

- `entity/AiAssistantEntity.java` —— `PathfinderMob` 子类。主人存储在 `SynchedEntityData` 中，类型为 `Optional<EntityReference<LivingEntity>>`（1.21.11 移除了 UUID 序列化器/`getPersistentData`；`EntityReference` 是新模式）。`registerGoals()` 在 `Mob.<init>` 中、实例字段初始化**之前**运行 —— 切勿在此引用字段；应内联创建 goal。重写了 `addAdditionalSaveData`/`readAdditionalSaveData`（使用 `ValueOutput`/`ValueInput`，而非 `CompoundTag`）以持久化主人（`Following` 标志已随跟随模式一同移除）。在构造函数中调用 `setPersistenceRequired()`。
- `entity/ModEntities.java` —— 通过 `EntityType.Builder.of(...).build(ResourceKey)` 注册 `EntityType`，`FabricDefaultAttributeRegistry.register`，以及多助手辅助方法 `findAssistantsFor(ServerPlayer)`（列出某玩家的所有助手）、`findNearestAssistantFor(ServerPlayer)`（按绑定方块距离取最近 —— ask/dismiss/reset 定向用）、`findAssistantsBySelector(ServerPlayer, String)`（按裸名 / `名字 (x,y,z)` / `名字(x,y,z)` 匹配 —— `ask <名字> <消息>` 用它指定某个助手）、`findAssistantsBoundTo`/`findAssistantBoundTo`/`isConfigBlockBound`（方块 → 助手）。无刷怪蛋（已移除：助手必须绑定 AI 徽标方块，刷怪蛋只会创建无意义的未绑定助手，会被自动清除）。
- `ai/LlmClient.java` —— 纯 Java 的 OpenAI 兼容 Chat Completions 客户端（`java.net.http.HttpClient` + Gson），无 Minecraft 依赖；可针对 mock 服务器做单元测试。两个入口：`chat(Request)`（非流式，`choices[0].message.content` + `message.tool_calls`）和 `stream(Request, StreamListener)`（SSE，发送 `"stream": true`，读取 `choices[0].delta.content` + 按 index 将 `delta.tool_calls[]` 合并为完整的 `ToolCall`；`StreamListener.onDelta/onDone/onError/onToolCalls` 在调用方工作线程上按序触发）。**兼容性**：`stream()` 先读第一条非空行判断模式——以 `data:` 开头按 SSE 逐行解析（`[DONE]` 结束）；否则视为端点忽略了 `stream` 参数，把整个响应体合并成普通 JSON 一次性回调（`onToolCalls?` + `onDelta(完整内容)` + `onDone`）。`Message`/`Request` 已扩展支持原生 function calling：`Message.tool(toolCallId, content)`、`Message.assistant(content, toolCalls)`、`Request.tools`（OpenAI tools schema，可为 null = 不发 tools 字段）。纯 Java SSE 工具调用分片合并有 JUnit 单测（`src/test/java/com/swaydy/opencraft/ai/LlmClientToolCallsTest`，`./gradlew test` 运行）。
- **插件系统 + Agent 预设（`agent/` 包，common 源码集）**——彻底替换旧 `[ACTION: ...]` 标记系统（`ai/AiAction`/`ai/AiActionParser` 已删除）。参考 deepseek-harness「万物皆插件」：`AssistantPlugin`（贡献 tools / systemPromptFragment / gameContextFragment / registerGoals）、`ToolDefinition`（name + description + JSON Schema + executor）、`ToolContext`（server/assistant/owner/level）、`ToolResult`、`AgentDefinition`（插件组合 + persona + maxToolRounds）、`AgentRegistry`（静态注册表，`OpenCraftMod.onInitialize` 调 `init()`）、`AgentRuntime`（agentic loop：LLM 原生 function calling → 服务端执行工具 → 喂回结果 → 循环到最终文本回复）。插件在 `plugins/`：`AssistantControlPlugin`（teleport_to_player，双形态通用）、`PlayerActionsPlugin`（玩家 bot 的"真玩家动作"：player_goto/player_stop/player_look/player_mine/player_place/player_craft/player_inventory/player_hand_to_player，全部用真实 `ServerPlayerGameMode` 执行）。旧存档遗留的实体形态插件（Movement/Perception/Mining/Inventory/Crafting/Combat）已删除——它们从未被任何预设装配，实体形态助手（仅旧存档兼容）现在是纯聊天伴侣。预设 `presets/`：`chat_agent`（纯聊天）、`general_agent`（默认；玩家式行动：移动/挖掘/放置/合成/递物/观察 + 传送到主人身边，maxToolRounds=8）。**预设只决定 LLM 行为，绝不决定身体形态**——不存在也不再存在 `player_agent` 预设。**跟随/待命模式已整体移除**：助手召唤后停留在原地，只有显式移动指令（player_goto 等）会驱动它移动；`set_mode` 工具、潜行右键切换、互动界面跟随按钮、`AiAssistant.isFollowing/setFollowing`、`FollowAssistantOwnerGoal`、配置里的跟随/停止/瞬移距离均已删除。
  - **Agentic loop 的成熟设计（参考 deepseek-harness）**——`AgentRuntime` 的循环已按 dsh 的成熟 Agent 模式强化（类注释详述；均有 JUnit 单测 `src/test/java/com/swaydy/opencraft/agent/AgentLoopGuardsTest.java`，`./gradlew test` 运行）：
    - **LLM 请求重试**（`agent/LlmRetryPolicy.java`，参考 dsh-llm-retry）：限流（HTTP 429）/5xx/超时/连接/IO 等瞬时失败，在**未吐出任何字符前**按指数退避 + 抖动（500ms→10s、±10%，最多 2 次）重试同一轮请求，重试期间"正忙"锁保持持有；空响应（无内容也无工具调用）视为可重试的退化完成（EMPTY_RESPONSE）。已吐出部分文本后失败不重试（避免重复上屏）。
    - **重复工具调用守卫**（`agent/RepeatToolGuard.java`，参考 dsh-repeat-tool-reminder）：跟踪同一次提问内「连续、完全相同（工具名 + 参数 JSON 深度按键排序后一致）」的调用，第 3 次注入温和提醒、第 5/8 次注入点名工具/次数/参数的详细提醒（作为 user 消息追在 tool 结果后），打断模型"笨笨地重复同一动作"的死循环；换工具/换参数即重置链条；参数 JSON 无法解析时按原文兜底。
    - **工具结果标记 + 裁剪**（`agent/ToolResultPruner.java`，参考 dsh-compaction-tool-result-pruner）：tool 结果统一以 `[工具名 成功/失败]` 开头（模型先读标记再读内容）；超过 1200 字符的结果保头 900 / 尾 200、中间省略，防上下文无限膨胀。参数 JSON 无法解析时把原文回显给模型让其自纠。
    - **每轮工具调用上限**（`MAX_TOOLS_PER_ROUND = 6`，参考 dsh-agent-loop 的 maxParallelToolCalls）：单轮超过 6 个调用时多余的直接返回"已达上限，先观察结果再继续"。
    - **历史压缩**（参考 dsh-compaction-basic）：历史长度超过 `maxHistoryMessages×2` 时，先在工作线程用一次非工具 LLM 调用（复用当前模型/persona，temperature 压低到≤0.5）把最旧区段压缩成 `<compacted-summary>` 记忆摘要并保留最近 `maxHistoryMessages` 条原文，再开始本轮；摘要未变短（< 原区段一半）或请求失败时自动退回直接裁剪；旧摘要会被后续压缩自然并入新摘要。压缩请求在 worker 线程执行、历史的落地替换在服务端线程（避免并发写）。
    - **向玩家提问（暂停/恢复）**（参考 dsh-tool-ask-user）：模型在指令含糊或行动有破坏性/不可逆影响时调用核心工具 `ask_player`——循环暂停（"正忙"锁保持持有）、把问题呈现给玩家（命令模式 `AiCompanionService.speakAsAssistant` 广播；GUI 模式发 "reply" 事件到窗口）；玩家用 `/opencraft answer <回答>` 回答后（`AgentRuntime.answer`，按绑定方块键控 + 仅主人有权限）把回答以 user 消息写回并恢复下一轮；超时 `ASK_TIMEOUT_MS=90s` 未答则自动按合理假设继续并在回复中说明。`PENDING_ASKS`（`Map<GlobalPos, PendingAsk>`）原子移除保证 answer 与超时只有一个能恢复；同批其他工具调用在提问时不执行（先问不做）。核心工具 schema 由 `AgentRuntime.coreToolSchemas()` 在每轮请求附加（不在 `toolMap()` 里，故不影响 `toolMap().size()` 相关测试）。
    - **任务计划跟踪**（参考 dsh-tool-todo + system-prompt 注入）：核心工具 `task_plan` 让模型以 `{steps:[{content,status}]}` 整单替换维护结构化计划（`agent/TaskPlan.java`，纯 Java 可单测；status ∈ pending/in_progress/completed，content 非空不重复）；`AgentRuntime` 每轮重建 `messages[0]`（`buildSystemWithPlan`，仍是单条 system 开头）把「【当前任务计划】…」注入上下文——多步任务不丢进度。`task_plan` 不参与重复调用守卫。
    - **状态可见性**：发起工具调用时提示"正在行动（第 N/M 步：工具名…）"（`command.opencraft.agent.executing` 现带 3 个占位符）。
  - **游戏内确认问答**：`/opencraft answer <回答>`（`command/ModCommands.java`）回答「最近的」助手（即提问的那个）的待回答提问；无待回答提问/非原提问者时报 `command.opencraft.answer.none`。lang 键：`command.opencraft.ask.question`（提问呈现）、`command.opencraft.answer.{blank,none,ok}`。chat_agent `maxToolRounds` 已 1→3（容纳一次澄清提问 + 恢复收尾）。
  - **mock LLM 注意**：`bin/mock_llm_server.py` 的 SSE 流式已在逐块输出后**去掉 `time.sleep`**——gametest 服务器"冲刺"tick，慢速流式（每块 5ms）会让并行测试的回复在 tick 预算内到不了位，导致"等回复"断言 flaky 超时。
- **助手形态抽象（`assistant/AiAssistant.java` 接口）**——统一抽象：`AiAssistantPlayer`（真 ServerPlayer 假玩家，`formId()=="player"`，**当前唯一形态**）与 `AiAssistantEntity`（PathfinderMob 底座，`formId()=="entity"`，仅旧存档遗留兼容）。Agentic loop、对话、历史、命令、界面只依赖接口的身体无关方法；身体专属能力由插件提供。`assistant/AssistantFacade.java` 跨形态统一路由：一个方块至多一个助手（跨形态判定占用），查找/召唤/送走/对话都按统一规则；**召唤一律走玩家形态**（`PlayerAssistantService.summonFor` → `PlayerList.placeNewPlayer` 正式进服），与方块配置的 agent 预设无关；旧存档遗留的实体助手在重新召唤时先被送走迁移。`agent/ToolContext.java` 携带 `AiAssistant`，提供 `assistantEntity()`/`assistantPlayer()` 便捷强转（实体插件只在实体形态运行，玩家插件只在玩家形态运行）。
- **玩家形态助手（`assistant/player/`，common 源码集）**——把 AI 助手做成"像多人联机客户端一样进服"的真玩家：`AiAssistantPlayer extends ServerPlayer`，拥有普通玩家的全部内容（可以不用但不能没有）：真正的 43 槽玩家背包（1.21.11：36 主背包 + `EntityEquipment` 7 装备槽含身体/坐骑鞍）、游戏模式、玩家式动作（`ServerPlayerGameMode.destroyBlock/useItemOn` 真实破坏/放置、掉落物自动拾取——玩家形态本身就是 Player）。核心机制：`FakeConnection extends Connection`（黑洞连接：`Connection(PacketFlow.SERVERBOUND)` 构造后 channel 为 null，`send()/disconnect()` 天然无害，`setupInboundProtocol/setupOutboundProtocol` 必须重写为 no-op，否则 placeNewPlayer 会 NPE）；`PlayerAssistantService.summonFor` 用**确定性 UUID（按绑定方块）** 建 GameProfile → `PlayerList.placeNewPlayer(new FakeConnection(), player, CommonListenerCookie.createInitial(profile, false))` 正式进服（加入 PlayerList、实体追踪、Tab、广播，对其他人就是一个客户端玩家；**进服的系统玩家名固定为 `PlayerAssistantService.SYSTEM_NAME`（现为 "IAISwayDy"）**，与方块配置显示名解耦——想整体改 bot 进服名（加入消息/Tab/list）改这个常量即可，聊天显示名仍读配置；**多个 bot 同名是故意允许的**——原版本来就允许多个同名玩家（服务端只校验 UUID），mod 内部路由全靠配置显示名、存档靠确定性 UUID，重名只有 Tab/加入消息的观感重复、无功能问题，别当 bug "修复"成进服名唯一）→ 载入旧存档（`loadPlayerData` + `TagValueInput.create`）→ 生存模式 + `abilities.invulnerable`（不摔伤/不淹死/不饿死，`causeFallDamage` 覆写为 false）。**移动**：`ServerPlayer` 服务端不应用输入/重力（服务器信任客户端移动包），`PlayerMovementController` 每 tick 用 `move(MoverType.PLAYER, delta)` 带碰撞直接驱动位置 + 自施加重力/跳跃/卡住传送回退。**两个关键细节**：(1) 着地判定用脚底 2mm 薄切片与方块碰撞实时计算（`hasGroundBelow`），**不依赖 `Entity.onGround`**——bot 纯水平 move() 不刷新该标志，靠它门控重力会导致走出平台边缘后浮空；切片必须极薄，否则 bot 会悬停在离地几厘米处（切片碰地即判着地、重力被清零）；(2) 移动时用 `Mth.atan2` + 每 tick 限幅 15° 同步平滑转向 yRot/yHeadRot/yBodyRot（朝向移动方向，不侧滑）。**不再有跟随**：`PlayerAssistantService.keepSafeState` 每 tick 只维持无敌/食物满；手动指令（`movement.moveTo(..., manual=true)`，如 player_goto/player_mine）是唯一移动来源。`onSlowTick` 每 40 tick 只做安全网校验；配置方块被拆 → 送走并清空记忆（与实体版安全网一致）。`AiAssistantPlayer.tick()` 由 `ServerLevel` 实体循环驱动（`ServerPlayer.tick` 不调 super，所以重力/移动全部自管）；持久化：`addAdditionalSaveData/readAdditionalSaveData` 存 `OpenCraftOwner`/`OpenCraftDim`/`OpenCraftX/Y/Z`，背包/装备由 ServerPlayer 原样持久化到 `players/<uuid>.dat`（送走/服务器停止时 `PlayerList.remove/saveAll` 自动落盘，重进/重召唤时 `loadPlayerData` 读回）。右键交互 `interact(Player, hand)` 与实体版同规则（绑主/开互动界面/非主人拒绝；潜行右键与普通右键相同，均打开互动界面）。`bin/mock_llm_server.py` 是本地 OpenAI 兼容 mock LLM（供无头 gametest 的聊天测试）。
- `entity/AiAssistantEntity.java` —— legacy entity-form assistant; no follow (follow/stay modes removed): it stays where summoned and is driven only by explicit tasks. `readAdditionalSaveData` re-asserts `setPersistenceRequired()` because the NBT read resets the flag after construction. **任务系统与背包（玩家式）**：36 格 `SimpleContainer inventory`（`INVENTORY_SIZE = 36`，与生存玩家主背包一致；NBT `list("Inventory", ItemStack.OPTIONAL_CODEC)` 持久化，旧 9 格存档自动兼容）+ `AssistantTask currentTask`（服务端字段，不持久化）；任务以高优先级 Goal 挂到 goalSelector（`TaskHostGoal` 常驻优先级 0 代理驱动 currentTask，任务活跃时压制散步）。**装备栏用 LivingEntity 原生 `equipment`**（头盔/胸甲/护腿/靴子/主手/副手，随存档自动持久化；vanilla 每 tick 的 `detectEquipmentUpdates` 让装备属性/护甲值生效）。`tick()` 每 5 tick 扫描脚下 ItemEntity **自动拾取**（`pickupNearbyItems`：跳过掉落保护期物品，`take`+`onItemPickup` 播放拾取动画，背包满留原地）；`dropEquipment` 死亡时掉落全部背包+装备（玩家式）；`autoSelectMiningTool(state)` 挖掘前自动把背包里最快的工具换到主手；`giveToInventory/countOf` 供插件使用。`registerGoals()` 只注册基础 Goal（Float/LookAt/RandomLook/Stroll），插件 Goal 在首次 tick 由 `ensureAgentGoals()` 按当前 Agent 预设懒注册（registerGoals 在字段初始化前调用，无法读配置；当前无插件注册 Goal）。
- `ai/AiCompanionService.java` —— history and the same-tick summon cache are keyed by the assistant's bound block (`Map<GlobalPos, ...>`; one block = one assistant = one independent memory, which survives dismiss/re-summon of the same block), async LLM calls on a daemon executor, callbacks marshalled to the server thread via `server.executeIfPossible`. `summonFor` uses a `RECENT_SUMMONS` cache because `addFreshEntity` only makes entities visible to lookups on the NEXT tick (PersistentEntitySectionManager loadingInbox), and scans upward for a safe spawn spot. `ask()`/`askGui()` 委托 `AgentRuntime.runAsync`（agentic loop，见上）；本类保留：召唤/送走/reset、历史存取（`getHistory`/`appendHistory`/`historyJson`/`historySize`）、显示/广播辅助（`showStreamingText`/`sendGuiEvent`/`finishStreamReply`/`finishGuiReply`/`speakAsAssistant`/`teleportAssistantToPlayer`/`buildGameContext`/`resolveItem`/`notifyInventoryGain`）、以及打招呼用的无工具 `streamPlain`（打字机 reveal 逻辑内联）。**消息结构约束**：`AgentRuntime.buildSystem` 把 人设+插件提示词+游戏上下文 合并成**单条 system 且在开头**（vLLM/Qwen 约束）；历史只存 user/assistant 最终文本（tool 往返不写入长期历史）。
- **右键 AI 助手互动**（`net/AssistantPayloads.java` + `client/gui/AiAssistantInteractScreen.java`）：右键自己的助手（普通或潜行）→ 服务器发 S2C `AssistantInteractPayload`（实体 ID + 显示名 + isOwner + 模型 + Agent + 绑定方块坐标）在客户端打开互动界面——和**这个**助手聊天、送走它（仅主人按钮）；未绑定助手右键=绑定主人。聊天走 C2S `AssistantChatPayload`（目标=实体 ID），服务端用 `AiCompanionService.resolveOwnedAssistant(player, entityId)` 重新校验后走 **GUI 模式** `askGui(...)`：回复以 `thinking/delta/reply/error` S2C 事件（`AiConfigChatEventPayload`，按绑定方块坐标路由）**流式显示在互动界面里**（私人会话，不广播世界聊天），客户端 `AiAssistantInteractScreen.handleChatEvent` 渲染对话区；送走走 `AssistantDismissPayload`，`dismissAssistantEntity` 送走指定助手（幂等）。
- `command/ModCommands.java` — `/opencraft` tree (`ask <msg>` targets the nearest assistant; `ask <名字> <msg>` targets a specific assistant by name with Tab-completion (same-name assistants are disambiguated with `名字(坐标)`, unknown names fall back to the nearest assistant); `summon` binds the nearest unbound block; `dismiss [all]`; `status` lists every assistant; `reset [all]`; `help`; no reload — config lives in blocks). Note: 1.21.11 replaced `hasPermission(int)` with `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)`.
- **AI 配置载体 = AI 徽标方块（无外部配置文件）** — 配置全部保存在 `block/AiLogoBlockEntity.java`（BlockEntity，`AiBlockConfig` 15 字段，NBT 随方块存档持久化；注册用 `FabricBlockEntityTypeBuilder`）。`block/AiLogoBlock.java` 实现 `EntityBlock`；普通右键 → `ai/AiConfigHandler.openFor(player, pos, dim)` 把该方块的配置以 `ai/AiConfigData`（Gson JSON）通过 `AiConfigPayloads.AiConfigDataPayload`（S2C，带 BlockPos+维度+`bound`/`boundByMe` 绑定状态）发给客户端，打开 `client/gui/AiConfigScreen`（1.21 TabNavigationBar 四页 UI）；"保存/召唤/送走/聊天"通过 `AiConfigSavePayload`/`AiConfigSummonPayload`/`AiConfigDismissPayload`/`AiConfigChatPayload`（C2S，带目标方块坐标）发回服务器，`AiConfigHandler.save/summonWithBlock/dismissWithBlock/chatWithBlock` 写回方块实体 / 绑定助手 / 送走助手 / 发起对话。**配置界面第 4 页是内置聊天窗口**（不用 `/opencraft ask` 也能对话）：聊天页打开时发 `AiConfigChatHistoryPayload` 拉取本方块助手的对话历史，服务器以 S2C `AiConfigChatEventPayload`（kind: history/thinking/delta/reply/error，Component 文本，带方块坐标路由）回传——`chatWithBlock` 优先聊本方块已绑定的自己的助手，未绑定则自动用本方块召唤一个，他人占用则拒绝（`command.opencraft.summon.block_occupied`）；`AiCompanionService.askGui(...)` 与命令行 `ask` 共享同一份按方块键控的历史与 agentic loop，但 GUI 模式的流式增量/最终回复只回传窗口（不广播世界聊天，私人会话）。**"AI 功能"开关与"用本方块召唤助手"已合并为配置界面底部同一个按钮**：未绑定助手 → 点击召唤（绑定本方块）；已绑定自己的助手 → 点击送走（不召唤）；已被他人绑定 → 禁用。因此 `AiBlockConfig` 不再有独立的 `aiEnabled` 字段（旧存档的 `AIEnabled` NBT 标签已废弃），`isUsable()` 只要求 baseUrl 非空。**Agent 预设**：`AiBlockConfig.agent`（NBT `Agent`，默认 `general_agent`）只决定助手的 LLM 行为；配置界面第 2 页的「允许动作」开关已替换为「Agent 预设」下拉（chat_agent / general_agent，保存时写回 `agent`；**预设不决定身体形态**——助手一律是玩家形态的假玩家 bot，旧存档残留的 `player_agent` 值在保存时被归一为 general_agent）；`AiConfigData` 增 `agent` 字段（`allowActions` 已移除）。默认配置：`baseUrl`/`model`/`apiKey` 的默认值解析优先级为 **JVM 参数（`-Dopencraft.baseUrl/model/apiKey`）> 运行时环境变量（`OPEN_CRAFT_BASE_URL/MODEL/API_KEY`）> 编译期烘焙进 jar 的值 > 代码内置回退**（`AiBlockConfig.defaultBaseUrl()/defaultModel()/defaultApiKey()`）。烘焙机制：`build.gradle` 的 `generateDefaultsResource` 任务在编译期读取项目根目录 `.env`，把三个值以 XOR 混淆字节（key "opencraft"）写入 `build/generated/resources/opencraft/defaults.dat` 打进 jar；`AiBlockConfig.loadBakedDefaults()` 运行时从 classpath 读取并解码——因此 **jar 放到任何环境（含游戏启动器/独立服务器）都自带 `.env` 的默认值，且 jar 内无明文**（仅防肉眼，不防反编译）。`.env` 同时被注入到 run 任务（`runClient/runServer/runGametestServer`）的进程环境。内置回退：`baseUrl="https://api.openai.com/v1"`、`model="gpt-4o"`、apiKey 为 XOR 混淆字节，`name="小智"`。
  - **助手绑定配置方块**：`AiAssistantEntity` 持久化 `GlobalPos configBlock`（NBT `storeNullable("ConfigBlock", GlobalPos.CODEC)`），`getConfig()` 实时读绑定方块；没有绑定则用静态默认值。`AiCompanionService.summonFor(player, explicitGlobalPos)` 显式指定方块，否则 `AiConfigHandler.findNearestConfigBlock(level, center, radius, unboundOnly)` 找最近的**未绑定**方块（按区块遍历）。只有 op（`PlayerList.isOp(new NameAndId(profile))`）可保存。
  - **多助手共存规则（一方块一助手）**：每个 AI 徽标方块最多绑定一个助手——`summonFor` 目标方块已被他人助手绑定时返回 null（拒绝），重复召唤同一方块则幂等返回原实例；`summonFor` 自动模式只找未绑定方块。`AiAssistantEntity.tick()` 每 40 tick 校验——`configBlock` 为空或绑定方块已消失时 `discard()`（约 2 秒内清除刷怪蛋/旧存档遗留的无绑定助手）；`AiLogoBlockEntity.preRemoveSideEffects` 在方块被破坏时立刻 `discard()` 所有绑定助手并清除该方块的对话记忆。`/opencraft ask <消息>`（不带名字）与 `dismiss/reset` 按"绑定方块距玩家最近"的助手路由；`/opencraft ask <名字> <消息>` 按名字精确指定和哪个助手对话（`ModEntities.findAssistantsBySelector`，Tab 可补全，同名用坐标消歧，未知名回退最近助手）；`dismiss [all]`/`reset [all]` 可批量。助手显示名为 `[配置名字 (x,y,z)]`（`AiAssistantEntity.getDisplayName()` 服务端实时读方块配置的 `name`，`entity.opencraft.ai_assistant.named` = "%s (%s)"），聊天时能区分是哪个助手；`ModCommands.status` 也按此格式列出。
  - **密钥安全**：API Key 的任何部分都不发送给客户端（`AiConfigData.apiKey` 恒为空串，仅 `apiKeySet` 布尔告知"已设置/未设置"）；客户端要换密钥须把 `apiKeyChanged` 置 true 并填新值（留空=清除），否则服务端保留原密钥。界面 API Key 输入框用掩码格式化（`FormattedCharSequence.forward("•"...)`）。
  - UI 要点：`renderTransparentBackground`（勿用 `renderBackground`，1.21.11 会 "Can only blur once per frame" 崩溃）；控件回调里不要直接增删控件，用 `rebuildRequested` 标志延迟到 `tick()` 重建（翻页用 `Screen.rebuildWidgets()`）。潜行右键保留发光开关。网络包注册在 `OpenCraftMod.onInitialize`；客户端在 `OpenCraftModClient` 注册 S2C 接收器。`ServerPlayNetworking.send` 对 mock 连接会失败，发送已 try/catch 包裹。
- **调试模式（`debug/DebugLog.java`）**——开发测试用：把 mod 业务日志（对话、LLM 请求/回复、工具调用与结果、任务、拾取、召唤/送走/配置变更，**不含 API Key**）写入 `<游戏目录>/logs/opencraft-debug.log`，格式 `[时间] [分类] 内容`。日志为**覆盖式**：每次开启（启动参数或 `/opencraft debug on`）清空旧日志，只保留本次会话；单次会话内超 5MB 自动重写。开关：启动参数 `-Dopencraft.debug=true` / 环境变量 `OPEN_CRAFT_DEBUG=true` 默认开启；游戏内 `/opencraft debug on|off|status`（op）动态切换。埋点用 SLF4J 风格 `{}` 占位符（DebugLog 内部转成 `String.format` 的 `%s`）。有 gametest `debugModeLogsToFile` 覆盖。
- Client: `client/render/AiAssistantRenderer.java` extends `HumanoidMobRenderer<AiAssistantEntity, HumanoidRenderState, HumanoidModel<HumanoidRenderState>>` (1.21.11 render-state system; register via `EntityRenderers.register`, which Fabric's transitive access wideners expose) — 自带主/副手物品握持与头戴物品渲染，另挂 `HumanoidArmorLayer`（`ArmorModelSet.bake(ModelLayers.PLAYER_ARMOR, ...)`）让装备栏护甲像玩家一样显示。Custom 64×64 humanoid texture at `assets/opencraft/textures/entity/ai_assistant.png`.

## 关键版本（锁定在 gradle.properties）

- Minecraft: 1.21.11
- Fabric Loader: 0.19.3
- Fabric API: 0.141.4+1.21.11
- Loom: 1.17-SNAPSHOT
- Java: 21

## 新代码约定

- 通用代码 → `com.swaydy.opencraft`；客户端专属代码 → `com.swaydy.opencraft.client`。
- 通用 mixin → `com.swaydy.opencraft.mixin` + 在 `opencraft.mixins.json` 中注册；客户端 mixin → `com.swaydy.opencraft.client.mixin` + 在 `opencraft.client.mixins.json` 中注册。
- 所有命名空间 ID 一律通过 `OpenCraftMod.id("path")` 构造。
