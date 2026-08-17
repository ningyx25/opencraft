# AI 助手插件系统 + Agent 预设 设计文档

日期：2026-08-17
状态：已实现（2026-08-18）。落地情况见下方「实现备注」；接口与行为与本文一致，少数细节做了简化。

> 实现备注（2026-08-18）：
> - 全部按本文落地：`agent/` 框架核心（AssistantPlugin / ToolDefinition / ToolContext / ToolResult /
>   AgentDefinition / AgentRegistry / AgentRuntime）+ `plugins/` 7 个插件 + `presets/` chat_agent /
>   general_agent；`AiAssistantEntity` 增加 9 格背包与任务系统（AssistantTask + MoveToBlockTask /
>   MineBlockTask / AttackTask + TaskHostGoal）；`AiCompanionService.ask*` 委托 AgentRuntime。
> - LlmClient 原生 function calling（tools + SSE tool_calls 分片合并 + 非流式退化）已有 JUnit 单测
>   `src/test/.../LlmClientToolCallsTest`（5 个用例，`./gradlew test` 通过）。
> - 简化点：Agent 预设下拉在客户端直接列出内置两个预设（chat_agent / general_agent），
>   AiConfigData 未额外携带可用预设列表（避免加重保存往返）；互动界面只读显示当前预设名。
> - 权限：工具仅约束“只为主人服务”，不再需要旧 allowActions 开关；挖掘/合成等世界操作不要求 op。

## 1. 目标与背景

现状：AI 助手的能力是硬编码的——LLM 在回复文本里写 `[ACTION: ...]` 标记，服务端用正则解析后执行 8 个固定动作（give/time/heal/feed/xp/mode/tp/weather）。没有结构化参数、没有执行结果反馈、无法多步任务。

目标：参考 deepseek-harness「万物皆插件」的思想重构：

- **插件（Plugin）**：一组内聚的能力单元，可贡献工具（tool）、系统提示词片段、游戏上下文片段、实体 Goal。
- **Agent 预设（AgentDefinition）**：插件的命名组合 + 人设提示词 + 循环参数。
- 助手的能力 = 其 Agent 预设装配的插件之和；LLM 通过 OpenAI 兼容的**原生 function calling** 调用工具，服务端执行后把结果喂回模型，循环直到模型给出最终文本回复（agentic loop）。
- 首批预设：`chat_agent`（纯聊天）、`general_agent`（像普通玩家一样移动/挖掘/合成/战斗）。

## 2. 架构总览

```
com.swaydy.opencraft.agent/            ← 新包：框架核心（common 源码集）
  AssistantPlugin.java                 插件接口
  ToolDefinition.java                  工具定义（name/description/parameters JSON Schema/executor）
  ToolContext.java                     工具执行上下文（server/assistant/owner/level）
  ToolResult.java                      执行结果（ok + 文本，序列化进 tool 消息）
  AgentDefinition.java                 Agent 预设（id/显示名/插件列表/人设提示词/maxToolRounds）
  AgentRegistry.java                   插件注册表 + Agent 预设注册表
  AgentRuntime.java                    agentic loop 执行器
  plugins/
    AssistantControlPlugin.java        基础插件：mode/tp/come + 跟随 Goal
    MovementPlugin.java                移动：goto / stop
    PerceptionPlugin.java              感知：look_around / inspect_block
    MiningPlugin.java                  挖掘：mine
    InventoryPlugin.java               物品：list_inventory / equip / hand_to_player
    CraftingPlugin.java                合成：craft
    CombatPlugin.java                  战斗：attack
  presets/
    ChatAgent.java                     chat_agent = [AssistantControlPlugin]
    GeneralAgent.java                  general_agent = 全部 7 个插件
```

数据流：

```
玩家提问 → AiCompanionService.ask*
  → AgentRuntime.runAsync(player, assistant)          [工作线程发起 HTTP]
      组装消息：system(人设 + 插件提示词 + 游戏上下文) + 历史 + user
      循环（≤ maxToolRounds）：
        LlmClient.stream(request with tools)
          ├─ 文本 delta → 打字机 reveal（action bar / GUI "delta" 事件，沿用现有机制）
          ├─ tool_calls 累积（delta.tool_calls 分片合并）
          └─ 流结束：
              有 tool_calls → 调度回服务端线程逐个执行（世界操作必须主线程）
                              → 追加 assistant(tool_calls) + tool(结果) 消息 → 下一轮
              无 tool_calls → 最终回复：写历史 + 广播/GUI "reply" 事件，结束
      超过轮数/出错 → 错误提示（GUI "error" 事件 / 聊天提示）
```

线程模型（沿用现有约定）：

- HTTP/SSE 读取在工作线程（`AiCompanionService` 现有 EXECUTOR）；
- 工具执行、历史写入、聊天广播一律 `server.executeIfPossible` 回服务端线程；
- 工具执行完成后由服务端线程把「继续下一轮请求」的任务交回工作线程池；
- 长任务（寻路、挖掘）不在工具调用里阻塞——工具只**下达指令**（设置 Goal / 任务状态），立即返回；模型通过后续 `look_around` 观察结果。

## 3. 核心接口

```java
public interface AssistantPlugin {
    /** 插件唯一 id，如 "movement"。 */
    String id();

    /** 本插件贡献的工具；无工具的插件（纯 Goal/上下文）返回空列表。 */
    default List<ToolDefinition> tools() { return List.of(); }

    /** 追加到 system 提示词的能力说明片段（告诉模型有哪些工具、怎么用）；可为 null。 */
    default String systemPromptFragment() { return null; }

    /** 追加到游戏上下文的状态片段（如助手当前坐标/正在执行的任务）；可为 null。 */
    default String gameContextFragment(ToolContext ctx) { return null; }

    /** 给助手实体注册的 AI Goal（如跟随、挖掘任务 Goal）；默认无。 */
    default void registerGoals(AiAssistantEntity assistant) { }
}

public record ToolDefinition(
        String name,                    // 工具名，如 "mine"
        String description,             // 给模型看的说明
        JsonObject parameters,          // JSON Schema（Gson 构建）
        ToolExecutor executor) {
    public interface ToolExecutor {
        /** 在服务端线程执行；返回给模型看的结果文本。 */
        ToolResult execute(ToolContext ctx, JsonObject args);
    }
}

public record ToolResult(boolean ok, String message) {
    public static ToolResult ok(String message);
    public static ToolResult error(String message);
}

/** 工具执行上下文：服务端线程上可用的全部环境。 */
public record ToolContext(
        MinecraftServer server,
        AiAssistantEntity assistant,
        ServerPlayer owner,             // 提问的玩家（工具为 owner 服务）
        ServerLevel level) { }

public record AgentDefinition(
        String id,                      // "chat_agent" / "general_agent"
        String displayName,             // 配置界面显示名（翻译键）
        List<AssistantPlugin> plugins,
        String personaPrompt,           // 人设提示词（替代/补充 systemPrompt）
        int maxToolRounds) {            // agentic loop 最大轮数
    /** 汇总全部工具（按插件顺序，重名时先注册者生效并告警）。 */
    public Map<String, ToolDefinition> toolMap();
    /** 汇总 system 提示词片段。 */
    public String combinedPromptFragments();
}
```

`AgentRegistry`（静态注册表，`OpenCraftMod.onInitialize` 时初始化）：

- `registerPlugin(AssistantPlugin)` / `plugin(String id)`
- `registerAgent(AgentDefinition)` / `agent(String id)` / `agents()`（有序，供配置界面下拉）
- `resolveAgent(AiBlockConfig)`：config.agent 为空或未知 → 回退 `general_agent`（默认预设）并记日志。

## 4. 插件与工具清单

### 4.1 AssistantControlPlugin（基础插件，所有预设都装）

| 工具 | 参数 | 行为 |
|---|---|---|
| `set_mode` | `mode: follow\|stay` | 切换助手跟随/待命（原 `[ACTION: mode]`） |
| `teleport_to_player` | 无 | 助手瞬移到主人身边，跨维度（原 `[ACTION: tp]`） |

`registerGoals`：注册 `FollowAssistantOwnerGoal`（从实体 `registerGoals()` 迁出）。

### 4.2 MovementPlugin

| 工具 | 参数 | 行为 |
|---|---|---|
| `goto` | `x,y,z`（int，绝对坐标） | 下达移动指令：`MoveToBlockTask`（包装 `MoveToGoal` + 完成/失败判定），立即返回「正在前往」；到达/超时由模型下轮 `look_around` 确认 |
| `stop` | 无 | 取消当前移动/挖掘任务 |

### 4.3 PerceptionPlugin

| 工具 | 参数 | 行为 |
|---|---|---|
| `look_around` | `radius`（默认 8，上限 16） | 返回助手坐标、朝向、任务状态（移动中/挖掘中/空闲）、周围方块摘要（按种类计数：石头×N、铁矿×M…）、附近实体（玩家/怪物/物品掉落物，含距离）、脚下与头顶安全性 |
| `inspect_block` | `x,y,z` | 返回指定方块 id、是否可挖掘、硬度、是否需要工具 |

感知是 agentic loop 的「眼睛」——挖掘/移动类工具只下达指令，模型靠 `look_around` 观察结果并决定下一步。

### 4.4 MiningPlugin

| 工具 | 参数 | 行为 |
|---|---|---|
| `mine` | `x,y,z` | 下达挖掘指令：`MineBlockTask` Goal（寻路到目标旁 → 持续 swing + `ServerPlayerGameMode` 同款破坏逻辑的 `level.destroyBlock` 由助手执行，掉落物归主人：掉落到主人脚边或进主人背包）；距离过远（>32）或方块不可破坏（基岩/空气）直接返回错误；否则返回「正在挖掘」 |

安全约束：只允许挖掘主人所在维度、与主人距离 ≤ `maxDistance`（配置，默认 64）内的方块；不破坏容器/带方块实体的功能方块第一版直接拒绝（避免吞数据），返回明确错误文本。

### 4.5 InventoryPlugin

| 工具 | 参数 | 行为 |
|---|---|---|
| `list_inventory` | `whose: self\|player`（默认 self） | 返回助手背包或主人背包的物品清单（id×数量） |
| `equip` | `slot: mainhand`, `item: <物品id>` | 从助手背包把物品换到主手（挖掘前拿镐） |
| `hand_to_player` | `item`, `amount` | 从助手背包取出物品递给主人（背包满则掉落到主人脚边）——替代旧 `give` 的「玩家能力」版本：只能给助手自己有的东西 |

助手背包：`AiAssistantEntity` 新增 9 格 `SimpleContainer inventory`（随 NBT 持久化），挖掘掉落物默认进助手背包。

### 4.6 CraftingPlugin

| 工具 | 参数 | 行为 |
|---|---|---|
| `craft` | `item: <物品id>`, `amount`（默认 1） | 服务端查 `RecipeManager` 匹配配方：材料取自助手背包，合成产物进助手背包；无配方/材料不足返回错误文本（列出缺什么） |

实现走 `level.recipeAccess()` + 合成配方遍历（`CraftingRecipe`/熔炼等第一版只支持 2×2/3×3 有序无序合成与石头切割等无容器配方，用 `RecipeHolder` 的 `assemble` 逻辑在内存容器上模拟）。

### 4.7 CombatPlugin

| 工具 | 参数 | 行为 |
|---|---|---|
| `attack` | `target: <实体描述>`（最近匹配：按名字/类型，如 "zombie"） | 找主人附近 16 格内匹配实体，下达攻击指令（`MeleeAttackGoal` 风格的 `AttackTask` Goal）；找不到目标返回错误 |

## 5. Agent 预设

```java
// presets/ChatAgent.java
new AgentDefinition("chat_agent", "agent.opencraft.chat",
        List.of(new AssistantControlPlugin()),
        CHAT_PERSONA,            // 纯聊天人设：只陪伴/答疑，不操作世界
        1)                       // 无工具循环（set_mode/tp 最多 1 轮）

// presets/GeneralAgent.java
new AgentDefinition("general_agent", "agent.opencraft.general",
        List.of(new AssistantControlPlugin(), new MovementPlugin(),
                new PerceptionPlugin(), new MiningPlugin(),
                new InventoryPlugin(), new CraftingPlugin(), new CombatPlugin()),
        GENERAL_PERSONA,         // 「像玩家一样行动」人设：观察→计划→行动→再观察
        8)                       // 多步任务预算
```

人设提示词要点（GENERAL_PERSONA）：

- 你是能**亲自动手**的助手：先 `look_around` 观察，再调用工具行动，行动后再观察确认结果；
- 一次只做一步，不要假设工具一定成功（读工具返回的结果文本）；
- 挖掘/移动是异步指令，下达后需要再次 `look_around` 确认完成；
- 只为主人服务，不做破坏主人利益的事（不攻击玩家、不破坏主人的功能方块）。

## 6. LlmClient 扩展

`LlmClient` 保持纯 Java、无 Minecraft 依赖：

- `Message` 扩展：新增 `toolCallId`/`toolCalls` 字段，支持 role `tool`；序列化时按 OpenAI 格式输出（`assistant` 消息带 `tool_calls: [{id, type:"function", function:{name, arguments}}]`；`tool` 消息带 `tool_call_id` + `content`）。
- `Request` 新增 `List<JsonObject> tools`（OpenAI tools schema，可为 null = 不发 tools 字段，chat_agent 走此路径）。
- SSE 解析扩展：累积 `delta.tool_calls[]`（按 index 合并分片的 `function.name`/`function.arguments` 字符串）；`finish_reason == "tool_calls"` 或累积到 tool_calls 时通过新回调 `StreamListener.onToolCalls(List<ToolCall>)` 交付（`onDone` 仍表示流结束；调用方按「有无 tool_calls」决定是最终回复还是继续循环）。
- 非流式 JSON 退化路径同样解析 `message.tool_calls`。
- `ToolCall` record：`(String id, String name, String argumentsJson)`。

## 7. AgentRuntime（agentic loop）

```java
public final class AgentRuntime {
    /** 异步发起一次带工具循环的对话；显示/收尾回调复用现有打字机机制。 */
    public static void runAsync(ServerPlayer player, AiAssistantEntity assistant,
                                String question, GlobalPos historyKey,
                                BlockPos guiBlockPos, ResourceKey<Level> guiDimension);
}
```

职责与规则：

1. 组装首轮消息：`system`（`personaPrompt` + 插件 `systemPromptFragment` 汇总 + 游戏上下文 + 插件 `gameContextFragment`）——**单条 system 开头**（vLLM/Qwen 约束，沿用现有约定）；历史只存 user/assistant 最终文本（tool 往返不写入长期历史，避免污染与膨胀）。
2. 每轮：工作线程 `LlmClient.stream`；文本 delta 走现有打字机 reveal（命令模式 action bar / GUI "delta" 事件）。
3. 流结束且有 tool_calls：
   - GUI/聊天提示「正在执行：mine(…)」一行状态；
   - `server.executeIfPossible` 回主线程：按顺序执行每个工具（`ToolDefinition.executor`），异常捕获为 `ToolResult.error`；
   - 追加 `assistant(tool_calls)` + 每个 `tool` 结果消息到本轮会话消息列表；
   - 交回工作线程发起下一轮。
4. 流结束且无 tool_calls：最终回复——写历史（historyKey 非空）、命令模式广播/GUI "reply" 事件（沿用 `finishStreamReply`/`finishGuiReply` 语义）。
5. 轮数超过 `maxToolRounds`：把「已达最大行动步数」作为 tool 结果喂给模型做最后一轮总结（不再带 tools），然后收尾。
6. 任何一轮 HTTP 失败：`onError` → 现有错误提示路径。
7. 并发保护：同一助手同时只允许一个进行中的 loop（`Map<GlobalPos, Boolean> RUNNING`），重复提问时提示「助手正忙」。

## 8. 实体与任务系统

`AiAssistantEntity` 变更：

- 新增 `SimpleContainer inventory`（9 格，NBT 持久化）。
- 新增 `AssistantTask currentTask`（服务端字段，不持久化）：接口 `tick()`/`isDone()`/`isFailed()`/`describe()`；实现 `MoveToBlockTask`、`MineBlockTask`、`AttackTask`。`tick()` 里驱动 currentTask；新任务到来时取消旧任务。
- `registerGoals()` 改为：基础 Goal（Float/LookAt/RandomLook）+ 遍历当前 Agent 的插件调 `registerGoals(this)`（跟随 Goal 由此进入，不再硬编码）。Agent 变更（配置界面切换预设）时重建 goalSelector 相关 Goal。
- 任务 Goal 与 vanilla goalSelector 的关系：任务以高优先级 Goal 形式挂到 goalSelector（任务活跃时压制 RandomStroll 等），任务结束自动移除。

## 9. 配置与存档迁移

`AiBlockConfig`：

- 新增 `String agent = "general_agent"`（NBT 标签 `Agent`；`toData`/`applyData` 同步；`AiConfigData` 新增 `agent` 字段）。
- **删除** `allowActions` 字段与 NBT 读写（旧标签忽略即可，`loadAdditional` 不再读 `AllowActions`）。
- `DEFAULT_SYSTEM_PROMPT` 中「能力：游戏内动作」整段删除——动作说明改由各插件 `systemPromptFragment` 生成；`systemPrompt` 字段保留（玩家自定义人设，与 Agent 预设的 persona 合并：预设 persona 在前、自定义在后）。

`AiConfigScreen`（客户端）：

- 接口设置页新增「Agent 预设」下拉（从 `AiConfigData` 携带的可用预设列表渲染：id + 显示名 + 工具数）；
- 删除「允许动作」开关。

`AssistantPayloads`/互动界面：显示当前 Agent 预设名（只读）。

## 10. 删除清单（彻底替换旧 ACTION 系统）

- 删除 `ai/AiAction.java`、`ai/AiActionParser.java`；
- `AiCompanionService`：删除 `executeActions`/`giveItem`/`setTime`/`setWeather` 及其调用点；`askInternal` 改为委托 `AgentRuntime.runAsync`；`streamReply` 的打字机/reveal 机制抽成可被 AgentRuntime 复用的帮助方法（或整体迁入 AgentRuntime）；`teleportAssistantToPlayer` 保留（AssistantControlPlugin 与跨维度跟随共用）；
- 语言文件：删除 `command.opencraft.action.*` 相关键，新增 `agent.opencraft.*`、工具状态提示键。

## 11. 错误处理

- 工具执行抛异常 → `ToolResult.error("内部错误: …")` 喂回模型（模型可向玩家解释），同时记 WARN 日志；
- 工具参数解析失败（缺参/类型错）→ `ToolResult.error` 说明期望格式（模型可自我纠正重试）；
- 模型调用未注册的工具名 → `ToolResult.error("未知工具")`；
- 端点不支持 function calling（返回 4xx 提示 tools 不支持）→ 本轮降级为无 tools 的普通对话并提示玩家该端点不支持工具调用；
- loop 中助手被送走/方块被拆 → 每轮开始前校验 assistant.isAlive() 与 configBlock，失效则静默终止。

## 12. 测试

- **纯 Java 单测**（`src/test/java`，新增 JUnit 依赖或沿用 gametest——优先 gametest 以免引入新测试设施）：
  - `LlmClient` tool_calls SSE 分片合并（mock SSE 文本）；
  - `AgentRegistry` 注册/解析/未知 id 回退。
- **Gametest**（`test/OpenCraftGameTests.java` 扩展）：
  - 现有 `assistantLifecycleAndChat` 适配新协议（mock 端点返回无 tool_calls 的最终回复）；
  - 新增：mock 端点返回一轮 `tool_calls: [set_mode stay]` 再返回最终回复 → 验证助手切换待命、历史只含最终文本；
  - 新增：`goto` + `look_around` 两轮 → 验证助手坐标变化。
- mock 端点需支持回吐 `tool_calls`（现有 gametest 的本地 mock 扩展）。

## 13. 非目标（本次不做）

- 第三方/外部插件加载（反射、jar-in-jar、脚本）——插件是代码内注册的一等公民，API 稳定后再开放；
- 配置界面里逐个勾选插件（自定义 Agent 组装）——第一版只选预设；
- 跨维度挖掘/任务、红石操作、建造（放方块）、熔炉/酿造等容器交互；
- 旧管理员动作（give/time/heal/feed/xp/weather）的任何形式保留。
