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

- `entity/AiAssistantEntity.java` —— `PathfinderMob` 子类。主人存储在 `SynchedEntityData` 中，类型为 `Optional<EntityReference<LivingEntity>>`（1.21.11 移除了 UUID 序列化器/`getPersistentData`；`EntityReference` 是新模式）。`registerGoals()` 在 `Mob.<init>` 中、实例字段初始化**之前**运行 —— 切勿在此引用字段；应内联创建 goal。重写了 `addAdditionalSaveData`/`readAdditionalSaveData`（使用 `ValueOutput`/`ValueInput`，而非 `CompoundTag`）以持久化主人 + `Following` 标志。在构造函数中调用 `setPersistenceRequired()`。
- `entity/FollowAssistantOwnerGoal.java` —— 跟随/待命 AI，带传送兜底（`LivingEntity.randomTeleport`），距离参数来自配置。
- `entity/ModEntities.java` —— 通过 `EntityType.Builder.of(...).build(ResourceKey)` 注册 `EntityType`，`FabricDefaultAttributeRegistry.register`，以及多助手辅助方法 `findAssistantsFor(ServerPlayer)`（列出某玩家的所有助手）、`findNearestAssistantFor(ServerPlayer)`（按绑定方块距离取最近 —— ask/dismiss/reset 定向用）、`findAssistantsBySelector(ServerPlayer, String)`（按裸名 / `名字 (x,y,z)` / `名字(x,y,z)` 匹配 —— `ask <名字> <消息>` 用它指定某个助手）、`findAssistantsBoundTo`/`findAssistantBoundTo`/`isConfigBlockBound`（方块 → 助手）。无刷怪蛋（已移除：助手必须绑定 AI 徽标方块，刷怪蛋只会创建无意义的未绑定助手，会被自动清除）。
- `ai/LlmClient.java` —— 纯 Java 的 OpenAI 兼容 Chat Completions 客户端（`java.net.http.HttpClient` + Gson），无 Minecraft 依赖；可针对 mock 服务器做单元测试。两个入口：`chat(Request)`（非流式，`choices[0].message.content` + `message.tool_calls`）和 `stream(Request, StreamListener)`（SSE，发送 `"stream": true`，读取 `choices[0].delta.content` + 按 index 将 `delta.tool_calls[]` 合并为完整的 `ToolCall`；`StreamListener.onDelta/onDone/onError/onToolCalls` 在调用方工作线程上按序触发）。**兼容性**：`stream()` 先读第一条非空行判断模式——以 `data:` 开头按 SSE 逐行解析（`[DONE]` 结束）；否则视为端点忽略了 `stream` 参数，把整个响应体合并成普通 JSON 一次性回调（`onToolCalls?` + `onDelta(完整内容)` + `onDone`）。`Message`/`Request` 已扩展支持原生 function calling：`Message.tool(toolCallId, content)`、`Message.assistant(content, toolCalls)`、`Request.tools`（OpenAI tools schema，可为 null = 不发 tools 字段）。纯 Java SSE 工具调用分片合并有 JUnit 单测（`src/test/java/com/swaydy/opencraft/ai/LlmClientToolCallsTest`，`./gradlew test` 运行）。
- **插件系统 + Agent 预设（`agent/` 包，common 源码集）**——彻底替换旧 `[ACTION: ...]` 标记系统（`ai/AiAction`/`ai/AiActionParser` 已删除）。参考 deepseek-harness「万物皆插件」：`AssistantPlugin`（贡献 tools / systemPromptFragment / gameContextFragment / registerGoals）、`ToolDefinition`（name + description + JSON Schema + executor）、`ToolContext`（server/assistant/owner/level）、`ToolResult`、`AgentDefinition`（插件组合 + persona + maxToolRounds）、`AgentRegistry`（静态注册表，`OpenCraftMod.onInitialize` 调 `init()`）、`AgentRuntime`（agentic loop：LLM 原生 function calling → 服务端执行工具 → 喂回结果 → 循环到最终文本回复）。插件在 `plugins/`：`AssistantControlPlugin`（set_mode/teleport + 跟随 Goal）、`MovementPlugin`（goto/stop + MoveToBlockTask）、`PerceptionPlugin`（look_around/inspect_block）、`MiningPlugin`（mine + MineBlockTask）、`InventoryPlugin`（list_inventory/equip/hand_to_player）、`CraftingPlugin`（craft，用背包材料走 RecipeManager）、`CombatPlugin`（attack + AttackTask）。预设 `presets/`：`chat_agent`（纯聊天）、`general_agent`（移动/感知/挖掘/物品/合成/战斗，maxToolRounds=8）。
- `entity/AiAssistantEntity.java` —— 还负责跨维度跟随：`tick()` 每 40 tick 按主人 UUID 查玩家列表（`PlayerList.getPlayer`），维度不同时传送到主人所在维度。`readAdditionalSaveData` 重新断言 `setPersistenceRequired()`，因为 NBT 读取会在构造后重置该标志。**任务系统与背包（玩家式）**：36 格 `SimpleContainer inventory`（`INVENTORY_SIZE = 36`，与生存玩家主背包一致；NBT `list("Inventory", ItemStack.OPTIONAL_CODEC)` 持久化，旧 9 格存档自动兼容）+ `AssistantTask currentTask`（服务端字段，不持久化）；任务以高优先级 Goal 挂到 goalSelector（`TaskHostGoal` 常驻优先级 0 代理驱动 currentTask，任务活跃时压制跟随/散步）。**装备栏用 LivingEntity 原生 `equipment`**（头盔/胸甲/护腿/靴子/主手/副手，随存档自动持久化；vanilla 每 tick 的 `detectEquipmentUpdates` 让装备属性/护甲值生效）。`tick()` 每 5 tick 扫描脚下 ItemEntity **自动拾取**（`pickupNearbyItems`：跳过掉落保护期物品，`take`+`onItemPickup` 播放拾取动画，背包满留原地）；`dropEquipment` 死亡时掉落全部背包+装备（玩家式）；`autoSelectMiningTool(state)` 挖掘前自动把背包里最快的工具换到主手；`giveToInventory/countOf` 供插件使用。`registerGoals()` 只注册基础 Goal（Float/LookAt/RandomLook/Stroll），插件 Goal（跟随）在首次 tick 由 `ensureAgentGoals()` 按当前 Agent 预设懒注册（registerGoals 在字段初始化前调用，无法读配置）。
- `ai/AiCompanionService.java` —— 历史记录和同 tick 召唤缓存以助手的绑定方块为键（`Map<GlobalPos, ...>`；一个方块 = 一个助手 = 一份独立记忆，送走/重新召唤同一方块时记忆保留），异步 LLM 调用跑在守护线程池上，回调通过 `server.executeIfPossible` 编排回服务端线程。`summonFor` 使用 `RECENT_SUMMONS` 缓存，因为 `addFreshEntity` 要到下一 tick 才让实体对查询可见（PersistentEntitySectionManager loadingInbox），并向上扫描寻找安全出生点。`ask()`/`askGui()` 委托 `AgentRuntime.runAsync`（agentic loop，见上）；本类保留：召唤/送走/reset、历史存取（`getHistory`/`appendHistory`/`historyJson`/`historySize`）、显示/广播辅助（`showStreamingText`/`sendGuiEvent`/`finishStreamReply`/`finishGuiReply`/`speakAsAssistant`/`teleportAssistantToPlayer`/`buildGameContext`/`resolveItem`/`notifyInventoryGain`）、以及打招呼用的无工具 `streamPlain`（打字机 reveal 逻辑内联）。**消息结构约束**：`AgentRuntime.buildSystem` 把 人设+插件提示词+游戏上下文 合并成**单条 system 且在开头**（vLLM/Qwen 约束）；历史只存 user/assistant 最终文本（tool 往返不写入长期历史）。
- **右键 AI 助手互动**（`net/AssistantPayloads.java` + `client/gui/AiAssistantInteractScreen.java`）：右键自己的助手（不潜行）→ 服务器发 S2C `AssistantInteractPayload`（实体 ID + 显示名 + 跟随状态 + isOwner + 模型 + 绑定方块坐标）在客户端打开互动界面——和**这个**助手聊天、切换跟随/待命、送走它（仅主人按钮）；**潜行右键**保留快速切换跟随/待命；未绑定助手右键=绑定主人。聊天走 C2S `AssistantChatPayload`（目标=实体 ID），服务端用 `AiCompanionService.resolveOwnedAssistant(player, entityId)` 重新校验后走 **GUI 模式** `askGui(...)`：回复以 `thinking/delta/reply/error` S2C 事件（`AiConfigChatEventPayload`，按绑定方块坐标路由）**流式显示在互动界面里**（私人会话，不广播世界聊天），客户端 `AiAssistantInteractScreen.handleChatEvent` 渲染对话区；跟随/送走分别走 `AssistantToggleFollowPayload`/`AssistantDismissPayload`，`dismissAssistantEntity` 送走指定助手（幂等）。
- `command/ModCommands.java` —— `/opencraft` 命令树（`ask <消息>` 定向最近的助手；`ask <名字> <消息>` 按名字指定特定助手，支持 Tab 补全（同名助手用 `名字(坐标)` 消歧，未知名回退到最近助手）；`summon` 绑定最近的未绑定方块；`dismiss [all]`；`status` 列出所有助手；`reset [all]`；`help`；无 reload —— 配置存在方块里）。注意：1.21.11 用 `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)` 替换了 `hasPermission(int)`。
- **AI 配置载体 = AI 徽标方块（无外部配置文件）** — 配置全部保存在 `block/AiLogoBlockEntity.java`（BlockEntity，`AiBlockConfig` 15 字段，NBT 随方块存档持久化；注册用 `FabricBlockEntityTypeBuilder`）。`block/AiLogoBlock.java` 实现 `EntityBlock`；普通右键 → `ai/AiConfigHandler.openFor(player, pos, dim)` 把该方块的配置以 `ai/AiConfigData`（Gson JSON）通过 `AiConfigPayloads.AiConfigDataPayload`（S2C，带 BlockPos+维度+`bound`/`boundByMe` 绑定状态）发给客户端，打开 `client/gui/AiConfigScreen`（1.21 TabNavigationBar 四页 UI）；"保存/召唤/送走/聊天"通过 `AiConfigSavePayload`/`AiConfigSummonPayload`/`AiConfigDismissPayload`/`AiConfigChatPayload`（C2S，带目标方块坐标）发回服务器，`AiConfigHandler.save/summonWithBlock/dismissWithBlock/chatWithBlock` 写回方块实体 / 绑定助手 / 送走助手 / 发起对话。**配置界面第 4 页是内置聊天窗口**（不用 `/opencraft ask` 也能对话）：聊天页打开时发 `AiConfigChatHistoryPayload` 拉取本方块助手的对话历史，服务器以 S2C `AiConfigChatEventPayload`（kind: history/thinking/delta/reply/error，Component 文本，带方块坐标路由）回传——`chatWithBlock` 优先聊本方块已绑定的自己的助手，未绑定则自动用本方块召唤一个，他人占用则拒绝（`command.opencraft.summon.block_occupied`）；`AiCompanionService.askGui(...)` 与命令行 `ask` 共享同一份按方块键控的历史与 agentic loop，但 GUI 模式的流式增量/最终回复只回传窗口（不广播世界聊天，私人会话）。**"AI 功能"开关与"用本方块召唤助手"已合并为配置界面底部同一个按钮**：未绑定助手 → 点击召唤（绑定本方块）；已绑定自己的助手 → 点击送走（不召唤）；已被他人绑定 → 禁用。因此 `AiBlockConfig` 不再有独立的 `aiEnabled` 字段（旧存档的 `AIEnabled` NBT 标签已废弃），`isUsable()` 只要求 baseUrl 非空。**Agent 预设**：`AiBlockConfig.agent`（NBT `Agent`，默认 `general_agent`）决定助手能力；配置界面第 2 页的「允许动作」开关已替换为「Agent 预设」下拉（chat_agent / general_agent，保存时写回 `agent`）；`AiConfigData` 增 `agent` 字段（`allowActions` 已移除）。默认配置：`baseUrl`/`model`/`apiKey` 的默认值解析优先级为 **JVM 参数（`-Dopencraft.baseUrl/model/apiKey`）> 运行时环境变量（`OPEN_CRAFT_BASE_URL/MODEL/API_KEY`）> 编译期烘焙进 jar 的值 > 代码内置回退**（`AiBlockConfig.defaultBaseUrl()/defaultModel()/defaultApiKey()`）。烘焙机制：`build.gradle` 的 `generateDefaultsResource` 任务在编译期读取项目根目录 `.env`，把三个值以 XOR 混淆字节（key "opencraft"）写入 `build/generated/resources/opencraft/defaults.dat` 打进 jar；`AiBlockConfig.loadBakedDefaults()` 运行时从 classpath 读取并解码——因此 **jar 放到任何环境（含游戏启动器/独立服务器）都自带 `.env` 的默认值，且 jar 内无明文**（仅防肉眼，不防反编译）。`.env` 同时被注入到 run 任务（`runClient/runServer/runGametestServer`）的进程环境。内置回退：`baseUrl="https://api.openai.com/v1"`、`model="gpt-4o"`、apiKey 为 XOR 混淆字节，`name="小智"`。
  - **助手绑定配置方块**：`AiAssistantEntity` 持久化 `GlobalPos configBlock`（NBT `storeNullable("ConfigBlock", GlobalPos.CODEC)`），`getConfig()` 实时读绑定方块；没有绑定则用静态默认值。`AiCompanionService.summonFor(player, explicitGlobalPos)` 显式指定方块，否则 `AiConfigHandler.findNearestConfigBlock(level, center, radius, unboundOnly)` 找最近的**未绑定**方块（按区块遍历）。跟随 AI（`FollowAssistantOwnerGoal`）的距离参数也来自方块配置。只有 op（`PlayerList.isOp(new NameAndId(profile))`）可保存。
  - **多助手共存规则（一方块一助手）**：每个 AI 徽标方块最多绑定一个助手——`summonFor` 目标方块已被他人助手绑定时返回 null（拒绝），重复召唤同一方块则幂等返回原实例；`summonFor` 自动模式只找未绑定方块。`AiAssistantEntity.tick()` 每 40 tick 校验——`configBlock` 为空或绑定方块已消失时 `discard()`（约 2 秒内清除刷怪蛋/旧存档遗留的无绑定助手）；`AiLogoBlockEntity.preRemoveSideEffects` 在方块被破坏时立刻 `discard()` 所有绑定助手并清除该方块的对话记忆。`/opencraft ask <消息>`（不带名字）与 `dismiss/reset` 按"绑定方块距玩家最近"的助手路由；`/opencraft ask <名字> <消息>` 按名字精确指定和哪个助手对话（`ModEntities.findAssistantsBySelector`，Tab 可补全，同名用坐标消歧，未知名回退最近助手）；`dismiss [all]`/`reset [all]` 可批量。助手显示名为 `[配置名字 (x,y,z)]`（`AiAssistantEntity.getDisplayName()` 服务端实时读方块配置的 `name`，`entity.opencraft.ai_assistant.named` = "%s (%s)"），聊天时能区分是哪个助手；`ModCommands.status` 也按此格式列出。
  - **密钥安全**：API Key 的任何部分都不发送给客户端（`AiConfigData.apiKey` 恒为空串，仅 `apiKeySet` 布尔告知"已设置/未设置"）；客户端要换密钥须把 `apiKeyChanged` 置 true 并填新值（留空=清除），否则服务端保留原密钥。界面 API Key 输入框用掩码格式化（`FormattedCharSequence.forward("•"...)`）。
  - UI 要点：`renderTransparentBackground`（勿用 `renderBackground`，1.21.11 会 "Can only blur once per frame" 崩溃）；控件回调里不要直接增删控件，用 `rebuildRequested` 标志延迟到 `tick()` 重建（翻页用 `Screen.rebuildWidgets()`）。潜行右键保留发光开关。网络包注册在 `OpenCraftMod.onInitialize`；客户端在 `OpenCraftModClient` 注册 S2C 接收器。`ServerPlayNetworking.send` 对 mock 连接会失败，发送已 try/catch 包裹。
- 客户端：`client/render/AiAssistantRenderer.java` 继承 `MobRenderer<..., HumanoidRenderState, HumanoidModel<...>>`（1.21.11 渲染状态系统；通过 `EntityRenderers.register` 注册，该方法由 Fabric 的传递性 access widener 暴露）。自定义 64×64 人形贴图位于 `assets/opencraft/textures/entity/ai_assistant.png`。

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
