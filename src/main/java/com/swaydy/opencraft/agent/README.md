# agent 包文件说明

AI 助手的 Agentic Loop 执行与守卫机制（参考 deepseek-harness 的成熟 Agent 模式）；插件接口与工具类型在 `plugins/` 包，Agent 预设 SPI 基类与内置预设类在 `presets/` 子包（`BaseAgent` + `ChatAgent` / `GeneralAgent`）。

| 文件 | 作用 |
|---|---|
| `AgentDefinition.java` | Agent 预设（record）：插件命名组合 + 人设提示词 + 最大工具轮数；负责汇总各插件的工具（重名先注册者生效）、system 提示词片段、游戏上下文片段，并生成 OpenAI tools JSON Schema。 |
| `AgentRegistry.java` | 插件与 Agent 预设的静态注册表：`init()` 在 mod 初始化时幂等注册全部内置插件与预设；提供按 id 查询、`resolveAgent` 从方块配置解析当前预设（未知回退默认 `general_agent`）。内置预设 SPI 基类与内置预设类在 `presets/` 子包（`BaseAgent` + `ChatAgent` / `GeneralAgent`，见该子包 README）。 |
| `presets/BaseAgent.java` | Agent 预设的 SPI 基类（同 `plugins/presets/AssistantPlugin` 的思路）：预设类继承它、覆写访问器声明属性，`definition()` 组装 `AgentDefinition`。 |
| `AgentRuntime.java` | Agentic loop 执行器（核心）：驱动「观察 → 决策 → 行动 → 再观察」循环——LLM 原生 function calling → 服务端线程执行工具 → 结果喂回 → 循环到最终文本回复。内置 LLM 重试、重复调用守卫、停滞守卫、结果裁剪、每轮工具上限、历史压缩、`ask_player` 向玩家提问（暂停/恢复）、`task_plan` 计划注入 system、玩家中断（interrupt）等机制。 |
| `Prompts.java` | 非插件提示词集中管理（结构化提示词：整体 Markdown、数据段 ```json）：静态文本（基础人设、历史压缩指令）＋ 配置相关（名字指令、人设组装 `persona`）＋ 动态上下文（`playerState`/`assistantState`——JSON 数据：位置/环境/近旁方块/大范围方块计数/附近实体/按槽位背包装备，完整吸收原 player_look/player_inventory）＋ system 整段组装（`system`/`systemWithPlan`：`# Identity` → 预设准则 → `# Capabilities`（插件 `##` 小节）→ `# Game Context`（`## Player State` + `## Assistant State`，JSON）→ `# Current Task Plan`（JSON）；助手状态为所有预设共需的核心段，不依赖插件）。插件的提示词片段仍在各插件（自带 `##` 标题）、预设 persona 仍在 `presets/` 子包的预设类内（自带 `#` 标题）。每次构建的状态 JSON 同步落快照到 `logs/opencraft/player.json` 与 `assistant.json`（`logging/StateSnapshots`）。 |
| `LlmRetryPolicy.java` | LLM 请求重试策略：按稳定错误码路由可重试性（限流/5xx/超时/传输错误/空响应可重试；STALLED 看门狗超时等不可重试），指数退避 + ±10% 抖动（500ms→10s，最多 2 次）。纯 Java 可单测。 |
| `RepeatToolGuard.java` | 重复工具调用守卫：跟踪同一次任务内「连续、完全相同」（工具名 + 参数 JSON 深度排序后一致）的调用，第 3 次温和提醒、第 5/8 次详细提醒，打断模型重复死循环；换工具/换参数即重置。纯 Java 可单测。 |
| `StallGuard.java` | 停滞守卫：连续 ≥3 轮只调用纯观察工具（player_find 等）而世界/背包无变化时注入一次提醒，让模型「给结论结束或执行真实动作」，打断纯观察空转（常规观察信息已由 `Assistant State` 上下文每轮自带，不占工具调用）。纯 Java 可单测。 |
| `TaskPlan.java` | 任务计划（`task_plan` 核心工具的数据模型）：模型以 `{steps:[{content,status}]}` 整单替换维护结构化步骤清单（pending/in_progress/completed），负责参数校验解析、注入 system 的 JSON 输出（`# Current Task Plan` 数据段）与摘要。纯 Java 可单测。 |
| `ToolResultPruner.java` | 工具结果裁剪器：结果统一以 `[工具名 成功/失败]` 标记开头；超过 1200 字符保头 900 / 尾 200、中间省略，防上下文无限膨胀。纯 Java 可单测。 |

> 插件 SPI 类型（`AssistantPlugin` / `ToolContext` / `ToolDefinition` / `ToolResult`）已移至 `plugins/` 包（见该包 README）——本包只保留 loop 执行与守卫逻辑；Agent 预设见 `presets/` 子包 README。

相关单测：`src/test/java/com/swaydy/opencraft/agent/AgentLoopGuardsTest.java`（守卫与重试策略）。
