# agent 包文件说明

AI 助手的 Agentic Loop 执行与守卫机制（参考 deepseek-harness 的成熟 Agent 模式）。

**架构（对齐 dsh 的核心原则）**：`AgentRuntime` 是**薄驱动**——只保留「调模型 → 跑工具 → 喂回结果 → 重复」
的通用机制（线程调度、流式、暂停/恢复、延迟动作、历史压缩编排）；所有**横切策略**（守卫、核心工具 task_plan/
ask_player、终止守卫）都抽到 `hooks/` 子包，做成监听 loop 生命周期的可插拔 `LoopHook`（事件 taxonomy：
`beforeBatch` / `handleTool` / `afterTool` / `afterBatch` / `onFinalText` / `tools()`），每次提问随
`LoopSession` 新建一套。新增一个守卫或核心工具 = 写一个 `LoopHook` 并在 `hooks/LoopHooks` 登记，无需改驱动。
插件接口与工具类型在 `plugins/` 包，Agent 预设 SPI 在 `presets/` 子包，钩子细节见 `hooks/README.md`。

| 文件 | 作用 |
|---|---|
| `AgentDefinition.java` | Agent 预设（record）：插件命名组合 + 人设提示词 + 最大工具轮数；负责汇总各插件的工具（重名先注册者生效）、system 提示词片段、游戏上下文片段，并生成 OpenAI tools JSON Schema。 |
| `AgentRegistry.java` | 插件与 Agent 预设的静态注册表：`init()` 在 mod 初始化时幂等注册全部内置插件与预设；提供按 id 查询、`resolveAgent` 从方块配置解析当前预设（未知回退默认 `general_agent`）。内置预设 SPI 基类与内置预设类在 `presets/` 子包（`BaseAgent` + `ChatAgent` / `GeneralAgent`，见该子包 README）。 |
| `presets/BaseAgent.java` | Agent 预设的 SPI 基类（同 `plugins/presets/AssistantPlugin` 的思路）：预设类继承它、覆写访问器声明属性，`definition()` 组装 `AgentDefinition`。 |
| `AgentRuntime.java` | Agentic loop **薄驱动**（核心机制）：驱动「观察 → 决策 → 行动 → 再观察」循环——LLM 原生 function calling → 委托 `ToolExecutor` 跑工具（钩子认领核心工具、插件注册表分派）→ 结果喂回 → 循环到最终文本（收尾由 `onFinalText` 钩子可否决）。驱动只保留机制：线程调度、流式打字机、LLM 重试调度（策略在 `LlmRetryPolicy`）、历史压缩编排（策略在 `HistoryCompactor`）、`ask_player`/延迟动作的暂停-恢复状态机、玩家中断（interrupt）。工具分派、结果裁剪与每轮上限在 `ToolExecutor`；横切策略全部在 `hooks/`。 |
| `Prompts.java` | system 提示词组装（对应 dsh `system-prompt/`：有序小节拼装，本类不读世界）：静态文本（基础人设 `BASE_PERSONA`、历史压缩指令 `COMPACT_INSTRUCTION`）＋ 名字/人设组装 `persona`（`# Identity`）＋ `system`/`systemWithPlan` 按 `# Identity` → 预设 persona → `# Capabilities`（插件片段）→ `# Skills`（技能库）→ `# Game Context`（调用 `GameContext` 产出 `## Player State`/`## Assistant State`）→ `# Current Task Plan` 的顺序拼整段。插件提示词片段仍在各插件（自带 `##`），预设 persona 仍在 `presets/` 预设类内（自带 `#`）。 |
| `GameContext.java` | 模型可见的**动态游戏上下文**（对应 dsh `context/`）：观察世界/背包 → 每轮注入 system 的 json 数据段（`playerState` 主人段：位置/环境/时间/生命饥饿/经验/模式/身体/效果/装备/注视/背包摘要；`assistantState` 助手段：坐标/朝向/移动 + 环境 + 近旁方块（带相对坐标）+ 大范围方块计数 + 附近实体 + 按槽位背包装备清单，完整吸收原 player_look；背包是摘要，精确完整视图由插件工具 `player_inventory` 按需提供）。每轮在服务端线程重建、始终最新；状态 JSON 同步落快照 `logs/opencraft/player.json` 与 `assistant.json`（`logging/StateSnapshots`）。从原 `Prompts` 拆出——观察（context）与拼装（system-prompt）分离。 |
| `ToolExecutor.java` | 工具执行管线（对齐 dsh 的 `tools/` 包：工具注册表 + 受守卫的执行管线）：loop 唯一的工具分派点——`beforeBatch` 整批认领（ask 短路）→ 逐 call `handleTool` 认领核心工具 / 否则查插件注册表（未知工具/参数错误/执行异常/冗余 goto/延迟动作互斥在此处理）→ `afterTool`/`afterBatch` 钩子 → deferred 工具经 `Host.registerPendingAction` 暂停等 [Event]。单批最多 6 个调用，结果统一标记+裁剪。ask 暂停/延迟动作注册/移动控制器查询/观察者回调等 loop 协调副作用经 `Host` 回调接回 `AgentRuntime`，让管线本身对具体工具名无感。 |
| `LoopSession.java` | 单次提问（一次 loop 任务）的共享状态（原 `AgentRuntime` 内部类 `LoopContext` 提升为顶层类）：消息列表、忙锁键、system、计划（plan/planText）、终止守卫计数、暂停标记（ask/action），以及本次任务装配的 `hooks` 列表；并提供 `asyncActionInFlight()` 判定（终止守卫与延迟动作共用）。 |
| `LlmRetryPolicy.java` | LLM 请求重试策略：按稳定错误码路由可重试性（限流/5xx/超时/传输错误/空响应可重试；STALLED 看门狗超时等不可重试），指数退避 + ±10% 抖动（500ms→10s，最多 2 次）。纯 Java 可单测。 |
| `HistoryCompactor.java` | 历史压缩（对应 dsh `compaction/` 包）：历史超过 `maxHistoryMessages×2` 时把最旧区段用一次非工具 LLM 调用压成 `<compacted-summary>` 记忆摘要（保留更多记忆，后续压缩自然并入旧摘要）；压缩失败/未变短退回只保留最近 n 条；裁剪时被裁区段里的记忆摘要会保留在结果头部，不会被下一次提问裁掉。本类承载策略与纯逻辑（`needsCompaction`/`summarize`/`apply`/`trimToRecent`），线程编排仍在 `AgentRuntime`。纯列表逻辑可直接单测。 |
| `RepeatToolGuard.java` | 重复工具调用守卫（纯策略）：跟踪同一次任务内「连续、完全相同」（工具名 + 参数 JSON 深度排序后一致）的调用，第 3 次温和提醒、第 5/8 次详细提醒，打断模型重复死循环；换工具/换参数即重置。由 `hooks/RepeatCallHook` 在 `afterTool` 接线。纯 Java 可单测。 |
| `StallGuard.java` | 停滞守卫（纯策略）：连续 ≥3 轮只调用纯观察工具（player_find/player_inventory/player_container_list 等）而世界/背包无变化时注入一次提醒，让模型「给结论结束或执行真实动作」，打断纯观察空转。由 `hooks/StallHook` 在 `afterBatch` 接线。纯 Java 可单测。 |
| `TaskPlan.java` | 任务计划数据模型（`task_plan` 核心工具）：模型以 `{steps:[{content,status}]}` 整单替换维护结构化步骤清单（pending/in_progress/completed），负责参数校验解析、注入 system 的格式化输出与摘要。由 `hooks/TaskPlanHook` 贡献工具/认领调用并写回 `LoopSession.plan`。纯 Java 可单测。 |
| `ToolResultPruner.java` | 工具结果裁剪器：结果统一以 `[工具名 成功/失败]` 标记开头；超过 1200 字符保头 900 / 尾 200、中间省略，防上下文无限膨胀。纯 Java 可单测。 |

> 插件 SPI 类型（`AssistantPlugin` / `ToolContext` / `ToolDefinition` / `ToolResult`）在 `plugins/` 包（见该包 README）；
> loop 的横切策略钩子在 `hooks/` 子包（见 `hooks/README.md`）；Agent 预设见 `presets/` 子包 README。
> 本包只保留 loop 薄驱动（`AgentRuntime`）、任务状态（`LoopSession`）与纯策略类。

相关单测：`src/test/java/com/swaydy/opencraft/agent/AgentLoopGuardsTest.java`（守卫与重试策略）、`src/test/java/com/swaydy/opencraft/agent/HistoryCompactorTest.java`（历史压缩纯逻辑）、`src/test/java/com/swaydy/opencraft/agent/ToolExecutorTest.java`（工具执行管线契约）、`src/test/java/com/swaydy/opencraft/agent/hooks/LoopHooksTest.java`（钩子装配/接线）、`src/test/java/com/swaydy/opencraft/agent/hooks/CompletionHookTest.java`（终止守卫否决语义）。
