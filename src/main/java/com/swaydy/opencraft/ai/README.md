# ai/ 包文件说明

本包负责 **大模型客户端与 AI 配置**：与 OpenAI 兼容 Chat Completions 接口通信、AI 助手配置的存储与传输、配置编辑器的服务端处理，以及对话/召唤等核心服务的编排。

> **重构状态**：LLM 客户端已从旧版（java.net.http 手写 SSE 解析）重写为基于官方 `com.openai:openai-java:4.52.0` 的 `LlmClient`（dsh-llm 风格：提供者无关词汇 + chunk 流协议 + 稳定错误码），**所有调用方（`AgentRuntime`/`AiCompanionService`/`OpenCraftGameTests`）已直接适配新 API**；旧兼容桥 `LlmClientCompat` 已删除。旧 `LlmClientToolCallsTest.java` 已被 `LlmClientTest.java` 取代。

## 文件一览

| 文件 | 作用 | 关键内容 |
|---|---|---|
| **AiBlockConfig.java** | **AI 助手配置模型**：每个 AI 徽标方块一份，随方块存档（NBT）持久化，无外部配置文件 | 字段：`baseUrl`/`apiKey`/`model`/`name`/`temperature`/`maxHistoryMessages`/`timeoutSeconds`/`language`/`agent`（Agent 预设）/`maxDistance`/`speed`。默认值解析优先级：**JVM 参数 > 环境变量 > jar 内烘焙默认值（`.env` XOR 混淆）> 代码内置回退**；`isUsable()` 只要求 baseUrl 非空；`saveAdditional`/`loadAdditional` 负责存档读写；`toData()`/`applyData()` 与 `AiConfigData` 互转（**apiKey 恒不外发**）；旧存档废弃标签（`AIEnabled`/`SystemPrompt`/`AllowActions`/跟随距离）已兼容处理 |
| **AiConfigData.java** | **配置的网络传输载体**：Gson 序列化为 JSON 在客户端/服务器间传递 | `record` 与 `AiBlockConfig` 可编辑字段一一对应。安全约定：服务端下发时 `apiKey` 恒为空串、仅以 `apiKeySet` 告知"已设置/未设置"；客户端换密钥须 `apiKeyChanged=true`（留空=清除），否则保存时保留原密钥。`toJson`/`fromJson`；与方块配置的互转走 `AiBlockConfig.toData()`/`applyData()` |
| **AiConfigHandler.java** | **配置编辑器的服务端处理**（右键 AI 徽标方块打开） | `openFor`（下发配置开编辑器）、`save`（写回方块，仅 op）、`summonWithBlock`/`dismissWithBlock`（配置界面"召唤/送走"合并按钮）、`chatWithBlock`/`interruptWithBlock`/`sendChatHistory`（配置界面第 4 页内置聊天窗口）、`syncBoundBlockPoweredState`（绑定方块亮起/熄灭）、`findNearestConfigBlock`（按区块遍历找最近的未绑定方块）、`canEdit`（op 校验） |
| **AiCompanionService.java** | **AI 助手核心服务**：召唤/送走/对话/历史/上下文 | 静态工具类。`summonFor`（转发 `AssistantFacade.summon`）、`ask`/`askGui`（委托 `AgentRuntime.runAsync`，异步 worker 线程池 + 回调回服务端线程）、按绑定方块键控的独立对话历史（`HISTORY: Map<GlobalPos, List<LlmClient.Message>>`，只存 user/assistant 最终文本；`historyJson` 输出 `[{role, content}]` 给 GUI 聊天窗口）、**世界内流式浮层**（`nextStreamSession`/`streamOverlay`/`finishOverlay` → S2C `AssistantStreamPayloads`）、GUI 事件推送、`buildGameContext`/`environmentCapsule`（玩家与助手共用的环境上下文）、`resolveItem`/`resolveBlock`（注册表解析）、`findSafeSpawnPos`、`speakAsAssistant`/`teleportAssistantToPlayer` 等 |
| **LlmClient.java** | **新版 LLM 客户端（基于官方 `com.openai:openai-java:4.52.0`，参考 deepseek-harness dsh-llm 设计）** | **提供者无关词汇**（`Block`：`TextBlock`/`ReasoningBlock`/`ToolCallBlock`/`ToolResultBlock`；`Message`（USER/ASSISTANT，system 走 `Request.system`）；`Request`；`ToolSchema`）、**流式 chunk 协议**（`BlockStart`/`TextDelta`/`ReasoningDelta`/`ToolCallDelta`/`BlockEnd`/`Usage`/终端 `Finish`，工具参数保持**原始分片字符串**）、**稳定错误码**（`LlmFailure` + `Codes`：AUTH/RATE_LIMIT/QUOTA/CONTEXT_WINDOW_EXCEEDED/SERVER/TIMEOUT/TRANSPORT/EMPTY_RESPONSE/MALFORMED_RESPONSE/STALLED…，纯函数 `httpErrorCode`/`failureCode`/`isContextWindowExceeded`/`isQuotaExceeded`）、**重试分离**（单次尝试，`maxRetries(0)`，重试归外部 `LlmRetryPolicy`）。`chat(Request)` 非流式返回 `ChatResult` / `stream(Request, ChunkSink)` 流式；懒加载共享基客户端 + 每次 `withOptions` 覆盖 baseUrl/apiKey/timeout（`warmUp()` 预热）；`checkApiKey` 校验 key（空 key 用占位 `"opencraft"`，非法字符 → INVALID_CREDENTIAL）；`reasoning_content` 经 `_additionalProperties()` 双向透传；SSE **idle 看门狗**（连接后不吐数据 → `STALLED` 不可重试）；**"端点忽略 stream"退化路径**（0 chunk → 再发一次非流式 `chat()` 合成 chunk 序列）。纯 Java、无 Minecraft 依赖，可对本地 mock 单测（`src/test/java/.../LlmClientTest.java`） |

## 单元测试

| 文件（`src/test/java/com/swaydy/opencraft/ai/`） | 作用 |
|---|---|
| **LlmClientTest.java** | `LlmClient` 的纯 Java 单测（本地 `HttpServer` mock，无 Minecraft）：非流式 chat（含 tools 真正上线）、SSE 流式 chunk 协议（text/reasoning/tool-call delta、block-end 组装、finish reason、usage）、`EMPTY_RESPONSE`/`MALFORMED_RESPONSE`、HTTP 错误映射、看门狗 `STALLED`、非流式退化路径、`checkApiKey` 与错误码纯函数 |

> 旧 `LlmClientToolCallsTest.java`（及过渡期 `LlmClientCompatTest.java`）已随新 API 适配删除/取代。

## 调用关系

```
AiConfigScreen (客户端) ── C2S 包 ──> AiConfigHandler ──> AiLogoBlockEntity（存 AiBlockConfig）
                                              │
                                              ├──> AssistantFacade.summon/dismiss（召唤/送走助手）
                                              └──> AiCompanionService.askGui（聊天窗口）
AiCompanionService ──> AgentRuntime.runAsync ──> LlmClient ──> OpenAI 兼容 API
```

## 设计要点

- **配置不落地外部文件**：全部存在游戏内方块实体里（每个方块一份，随存档持久化）。
- **API Key 安全**：任何部分都不发给客户端，传输时恒为空串，仅用 `apiKeySet` 布尔标记。
- **异步不阻塞主线程**：所有 HTTP 请求在守护线程池执行，回调经 `server.execute` 回到服务端线程。
- **一方块一助手一记忆**：对话历史按绑定方块（`GlobalPos`）键控，送走再召唤同一方块记忆仍在。
- **LLM 客户端为传输层纯适配**：提供者无关词汇 + 流式 chunk 协议 + 稳定错误码；重试按 `LlmFailure.code` 路由（`LlmRetryPolicy.retryableCode`），工具执行/历史由调用方负责（参考 deepseek-harness 的 dsh-llm 设计）。
