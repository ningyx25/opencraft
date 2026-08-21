# agent 包文件说明

AI 助手的插件系统与 Agentic Loop（参考 deepseek-harness 的成熟 Agent 模式）。

| 文件 | 作用 |
|---|---|
| `AgentDefinition.java` | Agent 预设（record）：插件命名组合 + 人设提示词 + 最大工具轮数；负责汇总各插件的工具（重名先注册者生效）、system 提示词片段、游戏上下文片段，并生成 OpenAI tools JSON Schema。 |
| `AgentRegistry.java` | 插件与 Agent 预设的静态注册表：`init()` 在 mod 初始化时幂等注册全部内置插件与预设（chat_agent / general_agent）；提供按 id 查询、`resolveAgent` 从方块配置解析当前预设（未知回退默认）。 |
| `AgentRuntime.java` | Agentic loop 执行器（核心）：驱动「观察 → 决策 → 行动 → 再观察」循环——LLM 原生 function calling → 服务端线程执行工具 → 结果喂回 → 循环到最终文本回复。内置 LLM 重试、重复调用守卫、停滞守卫、结果裁剪、每轮工具上限、历史压缩、`ask_player` 向玩家提问（暂停/恢复）、`task_plan` 计划注入 system、玩家中断（interrupt）等机制。 |
| `AssistantPlugin.java` | 插件接口（"万物皆插件"）：一个能力单元可贡献工具（`tools()`）、system 提示词片段、游戏上下文片段、实体 AI Goal；助手能力 = 其预设装配的插件之和。 |
| `LlmRetryPolicy.java` | LLM 请求重试策略：按稳定错误码路由可重试性（限流/5xx/超时/传输错误/空响应可重试；STALLED 看门狗超时等不可重试），指数退避 + ±10% 抖动（500ms→10s，最多 2 次）。纯 Java 可单测。 |
| `RepeatToolGuard.java` | 重复工具调用守卫：跟踪同一次任务内「连续、完全相同」（工具名 + 参数 JSON 深度排序后一致）的调用，第 3 次温和提醒、第 5/8 次详细提醒，打断模型重复死循环；换工具/换参数即重置。纯 Java 可单测。 |
| `StallGuard.java` | 停滞守卫：连续 ≥3 轮只调用纯观察工具（player_look/player_inventory 等）而世界/背包无变化时注入一次提醒，让模型「给结论结束或执行真实动作」，打断纯观察空转。纯 Java 可单测。 |
| `TaskPlan.java` | 任务计划（`task_plan` 核心工具的数据模型）：模型以 `{steps:[{content,status}]}` 整单替换维护结构化步骤清单（pending/in_progress/completed），负责参数校验解析、注入 system 的格式化输出与摘要。纯 Java 可单测。 |
| `ToolContext.java` | 一次工具调用的执行上下文（record）：server / assistant（形态无关的 AiAssistant）/ owner（提问玩家）/ level；提供 `assistantEntity()`/`assistantPlayer()` 便捷强转。 |
| `ToolDefinition.java` | 单项工具定义（record）：工具名 + 给模型的说明 + 参数 JSON Schema + 执行器（服务端线程运行，返回 `ToolResult`）。 |
| `ToolResult.java` | 工具执行结果（record）：ok（成功/失败）+ 给模型看的结果文本（失败时模型据此自我纠正）；提供 `ok()`/`error()` 工厂方法。 |
| `ToolResultPruner.java` | 工具结果裁剪器：结果统一以 `[工具名 成功/失败]` 标记开头；超过 1200 字符保头 900 / 尾 200、中间省略，防上下文无限膨胀。纯 Java 可单测。 |

相关单测：`src/test/java/com/swaydy/opencraft/agent/AgentLoopGuardsTest.java`（守卫与重试策略）。
