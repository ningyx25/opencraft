# net 包 — 自定义网络包（CustomPacketPayload）

本包定义 mod 的全部自定义网络包。所有包 ID 经 `OpenCraftMod.id(...)` 命名空间化；编解码用 `StreamCodec.composite`；服务端注册在 `OpenCraftMod.onInitialize`，客户端 S2C 接收器注册在 `OpenCraftModClient`。

| 文件 | 作用 | 包含的包 |
|---|---|---|
| `AssistantPayloads.java` | 「右键 AI 助手打开背包界面」的网络包。服务端打开 `AssistantInventoryMenu` 后把助手**实体 ID** 紧随打开包发给客户端，背包界面左侧用原版 `renderEntityInInventory` 渲染助手模型 | `assistant_interact`（S2C：背包界面要渲染的助手实体 ID） |
| `AiConfigPayloads.java` | AI 配置编辑器（AI 徽标方块 → 配置界面）的网络包。配置保存在方块实体中，每个包都携带目标方块的坐标+维度；服务端只接受指向 AI 徽标方块实体的请求 | `ai_config_data`（S2C：配置 JSON + canEdit/bound/boundByMe）；`ai_config_save`（C2S：保存配置）；`ai_config_summon`（C2S：召唤并绑定助手）；`ai_config_dismiss`（C2S：送走助手）；`ai_config_chat`（C2S：聊天窗口发消息）；`ai_config_chat_history`（C2S：拉取对话历史）；`ai_config_interrupt`（C2S：中断当前任务）；`ai_config_chat_event`（S2C：聊天事件，kind = history/thinking/delta/reply/error） |
| `AssistantStreamPayloads.java` | 世界内共享流式浮层的 S2C 包，推送「截至当前的已生成文本快照」，与窗口事件互补——无论从哪个入口发起对话都能看到回复逐字出现。`sessionId` 按玩家递增分配，客户端只认最大会话号，避免并发流互相覆盖串扰；`done=true` 时浮层去光标淡出 | `assistant_stream`（S2C：sessionId + 助手名 + 文本快照 + done） |
