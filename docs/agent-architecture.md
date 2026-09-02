# Agent 架构（对齐 DeepSeek Harness）

本文记录 opencraft 的 AI Agent 设计，以及它与
[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 包结构的对应关系。
目标是让 loop 驱动保持薄，把横切策略、能力与上下文做成可插拔、可组合、可单测的部件。

## 核心原则

- **薄驱动**：`AgentRuntime` 只负责「调模型 → 跑工具 → 把结果喂回 → 重复」的机制（线程调度、
  流式、暂停/恢复、历史压缩编排、中断）。
- **万物皆插件**：Agent 预设 = 插件的组合；插件贡献工具、提示词片段与上下文片段。
- **策略是监听器**：守卫、核心工具、计划、向玩家提问都通过 loop 生命周期钩子挂载，不写死在驱动里。
- **观察与拼装分离**：世界/背包状态观察（context）与 system 提示词小节拼装（system-prompt）分开。

## 包映射

| DeepSeek Harness 概念 | opencraft 实现 |
|---|---|
| `agent-loop`（唯一驱动） | `agent/AgentRuntime` |
| 每个 agent 的 scoped 上下文 | `agent/LoopSession`（每次提问新建，持有 hooks 与消息） |
| 事件 taxonomy（pre-step / post-execute / turn-stopping） | `agent/hooks/LoopHook` 生命周期方法 |
| `guard/repeat-tool-reminder` | `hooks/RepeatCallHook`（封装 `RepeatToolGuard`） |
| `agent/turn-stopping` 可否决收尾 | `hooks/CompletionHook`（封装 `TaskCompletionGuard`） |
| `tools/` 受守卫的执行管线 | `agent/ToolExecutor`（经 `Host` 回调接回 loop） |
| `skill/` 能力目录 | `agent/skills`（`SkillLibrary` + `Skill`） |
| `context/` 动态请求上下文 | `agent/GameContext`（`playerState` / `assistantState`） |
| `system-prompt/` 小节拼装 | `agent/Prompts` |
| `compaction/` 历史压缩 | `agent/HistoryCompactor` |
| `todo_write` 工具 | `hooks/TaskPlanHook` + `agent/TaskPlan` |
| `ask_user` 工具 | `hooks/AskPlayerHook` + `AgentRuntime` 暂停/恢复 |
| `llm/` 抽象与适配 | `ai/LlmClient`（OpenAI Chat Completions + SSE） |
| 插件体系 | `plugins/presets/AssistantPlugin` |
| capability family（shell/fs/…） | 玩家动作拆成 `PlayerMovementPlugin` / `PlayerPerceptionPlugin` / `PlayerWorldPlugin` / `PlayerCraftingPlugin` / `PlayerInventoryPlugin` / `PlayerContainerPlugin`，共享 `PlayerActionMechanics` |

## Loop 生命周期

每轮请求会依次经过：

1. `AgentRuntime` 在服务端线程重建 system（`Prompts.systemWithPlan` → `GameContext` 动态状态）。
2. LLM 流式返回工具调用。
3. `ToolExecutor.executeBatch` 分派整批工具：
   - `LoopHook.beforeBatch`：`AskPlayerHook` 的确认优先短路；
   - `LoopHook.handleTool`：认领 `task_plan` / `ask_player`；
   - 插件工具注册表执行；
   - `LoopHook.afterTool` / `afterBatch`：重复调用守卫与停滞守卫；
   - deferred 动作经 `Host.registerPendingAction` 暂停，等待 `[Event]` 续轮。
4. 无工具调用时，`LoopHook.onFinalText` 决定是否收尾（`CompletionHook` 阻止半途收尾）。

## 扩展方式

- 新增守卫：实现 `LoopHook`（只覆写关心的生命周期点），在 `agent/hooks/LoopHooks` 登记。
- 新增核心工具：实现 `LoopHook.tools()` 与 `handleTool`，在 `LoopHooks` 登记。
- 新增玩家动作能力：在 `plugins/presets/` 新建 `AssistantPlugin`，executor 指向
  `PlayerActionMechanics` 的静态方法；预设的 `plugins()` 决定是否装配。
- 新增 Agent 预设：继承 `agent/presets/BaseAgent` 并在 `AgentRegistry` 注册。
- 新增技能：按 `agent/skills` 现有格式写 SKILL.md 并在 `skills/index.json` 登记。

## 不变式

- 一个助手同一时间只跑一个 loop（按绑定方块的 `GlobalPos` 忙锁）。
- 工具与守卫的提醒都进同一份 `LlmClient.Message` 列表，顺序保证模型可见。
- 每个 `LoopSession` 重新创建整套 hooks，跨任务不串状态。
- 历史压缩产生的 `<compacted-summary>` 会越过后续裁剪保留，不会让压缩白做。
- 修改本区域后需通过：
  - JUnit：`agent/*`（守卫、管线、压缩、钩子装配）纯逻辑测试；
  - Fabric gametest：`./gradlew runGametestServer`（需要本地 mock LLM，见 `bin/mock_llm_server.py`）。
