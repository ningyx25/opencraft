# command 包文件说明

| 文件 | 作用 |
|---|---|
| `ModCommands.java` | 注册 `/opencraft` 指令树（Brigadier + Fabric `CommandRegistrationCallback`）。子命令：`ask <消息>`（问最近的助手，支持开头带助手名字精确路由，Tab 补全名字、同名用坐标消歧）、`answer <回答>`（回答助手的 `ask_player` 提问，恢复暂停的循环）、`interrupt` / `stop`（中断最近助手当前任务并释放忙锁）、`summon`（召唤助手并绑定最近的未绑定 AI 徽标方块）、`dismiss [all]`（送走最近/全部助手）、`status`（列出全部助手及形态/模型/API Key/记忆条数）、`reset [all]`（清空最近/全部助手的对话记忆）、`debug on/off/status`（调试日志开关，on/off 需 OP 权限）、`help`。所有业务逻辑委托给 `AssistantFacade` / `AiCompanionService` / `AgentRuntime` / `DebugLog`，本类只做参数解析与反馈消息。 |
