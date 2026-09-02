# hooks 子包说明（agent/hooks/）

Agentic loop 的**生命周期钩子（LoopHook）**——对齐 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)
的核心设计：**loop 驱动本身只做「调模型 → 跑工具 → 把结果喂回 → 重复」这一通用机制，
其余一切横切策略（守卫、核心工具、计划、向玩家提问）都是监听 loop 事件 taxonomy 的可插拔监听器**，
而不是写死在驱动里的 `if` 分支。

## 设计对照（dsh → opencraft）

| dsh 概念 | opencraft 对应 |
|---|---|
| loop 只负责 call-model / run-tools / repeat，其余交给监听器 | `AgentRuntime` 只保留调度/流式/暂停恢复等**机制**；策略全部下沉到本包 |
| `tools/post-execute` 事件 + `repeat-tool-reminder` 插件 | `LoopHook.afterTool` + `RepeatCallHook`（封装 `RepeatToolGuard`） |
| `agent/turn-stopping` 可否决本轮结束 | `LoopHook.onFinalText` + `CompletionHook`（封装 `TaskCompletionGuard`） |
| `todo_write` 工具 + system-prompt 计划段 | `task_plan` 工具由 `TaskPlanHook` 贡献/认领，计划文本仍由 `Prompts` 每轮注入 system |
| `ask_user` 等待人类输入的工具 | `ask_player` 工具由 `AskPlayerHook` 贡献/认领；暂停/恢复机制仍在 `AgentRuntime` |
| bundle 里 mount 的插件组合 | `LoopHooks.createDefaults()`——新增能力 = 写一个 `LoopHook` 并在此登记 |

每次提问（`LoopSession`）都由 `LoopHooks.createDefaults()` 新建一整套钩子，各钩子持有自己的
**per-task 状态**（重复链、停滞计数、计划……），跨任务不串（等价于 dsh 的 per-agent scoped 上下文）。

## 生命周期点（事件 taxonomy）

| `LoopHook` 方法 | 时机 | 内建实现 | 对应 dsh 事件 |
|---|---|---|---|
| `tools()` | 组装每次请求的 tools schema | `TaskPlanHook`、`AskPlayerHook` 贡献核心工具 | 工具由插件贡献 |
| `beforeBatch(calls)` | 一批工具调用分派**前**的整批认领 | `AskPlayerHook`：有效 `ask_player` 短路（**确认先于动作**，跳过同批其余工具） | — |
| `handleTool(call)` | 逐 call：核心工具认领，否则放行插件注册表 | `TaskPlanHook`（task_plan）、`AskPlayerHook`（兜底） | 工具经 registry 分派 |
| `afterTool(exec, out)` | 单个工具执行后（只观察/增补，不否决） | `RepeatCallHook`：撞阈值追加 `[Reminder]` user 消息 | `tools/post-execute` |
| `afterBatch(names, out)` | 整批工具执行完 | `StallHook`：连续纯观察无进展追加停滞提醒 | `tools/post-execute` |
| `onFinalText(text)` | 模型给纯文本（非总结轮）时可否决收尾 | `CompletionHook`：计划未完成/动作在途则暂缓 | `agent/turn-stopping` |

所有方法默认 no-op，钩子只覆写自己关心的点。

## 文件

| 文件 | 作用 |
|---|---|
| `LoopHook.java` | 钩子 SPI 接口（默认 no-op 生命周期方法 + `functionTool`/`parseArgsObject` 静态工具）。 |
| `LoopHooks.java` | 默认组合工厂：`createDefaults()` 每次任务新建 `[TaskPlanHook, AskPlayerHook, RepeatCallHook, StallHook, CompletionHook]`。新增内置能力在此登记。 |
| `TaskPlanHook.java` | 贡献并认领 `task_plan`：解析 `TaskPlan` 写回 `session.plan/planText`（`Prompts` 每轮注入 system；`CompletionHook` 读 `plan`）。成功=「做了实事」重置停滞、不计重复链；失败计入重复链防错误参数死循环。 |
| `AskPlayerHook.java` | 贡献并认领 `ask_player`：`beforeBatch` 扫描整批，有效提问→`ToolHandle.ask`（暂停等回答、跳过同批其余工具），缺参→错误结果其余照常。 |
| `RepeatCallHook.java` | 封装 `RepeatToolGuard`：`afterTool` 对计入重复链的调用观察，撞阈值追加提醒（冗余 goto / 成功 task_plan 经 `ToolExec.countForRepeat` 排除）。 |
| `StallHook.java` | 封装 `StallGuard`：`afterBatch` 判定「连续多轮只调只读观察工具且无状态变化」，追加停滞提醒。 |
| `CompletionHook.java` | 封装 `TaskCompletionGuard`：`onFinalText` 在计划未完成或异步动作在途时返回 `HoldDecision.hold`，驱动把文本当中途进度广播、注入提醒续轮（最多 `MAX_HOLDS` 次）。 |
| `ToolHandle.java` | `handleTool` 返回值：`notHandled()` 放行 / `handled(result)` / `ask(result, question)`（认领并暂停等回答）。 |
| `BatchClaim.java` | `beforeBatch` 返回值：是否整批认领（ask_player 短路）。 |
| `ToolExec.java` | `afterTool` 入参：一次工具执行的事实（call/name/result/`countForRepeat`）。 |
| `HoldDecision.java` | `onFinalText` 返回值：`finish()` 收尾 / `hold(reminder)` 暂缓。 |

> 纯策略类（`RepeatToolGuard` / `StallGuard` / `TaskCompletionGuard` / `TaskPlan`）仍在父包
> `agent/`，保持「纯 Java、无 Minecraft 依赖、可直接单测」；本包的钩子是它们在 loop 事件上的**接线**。
> 单测：`src/test/java/com/swaydy/opencraft/agent/hooks/LoopHooksTest.java`（装配/接线）、
> `src/test/java/com/swaydy/opencraft/agent/hooks/CompletionHookTest.java`（终止守卫否决语义）。
