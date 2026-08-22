package com.swaydy.opencraft.ai;

import static com.openai.core.ObjectMappers.jsonMapper;

import com.google.gson.JsonObject;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.Timeout;
import com.openai.core.http.Headers;
import com.openai.core.http.StreamResponse;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionSystemMessageParam;
import com.openai.models.chat.completions.ChatCompletionTool;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import com.openai.models.completions.CompletionUsage;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import com.swaydy.opencraft.logging.LlmDebugLogger;

/**
 * 基于官方 {@code com.openai:openai-java}（4.52.0）的 OpenAI 兼容 Chat Completions 客户端。
 *
 * <p>设计参考 deepseek-harness 的 dsh-llm 包（packages/llm/llm，见其 README）：
 * <ul>
 *   <li><b>提供者无关词汇</b>：消息内容是一组类型化块（{@link TextBlock}/{@link ReasoningBlock}/
 *       {@link ToolCallBlock}/{@link ToolResultBlock}），与 provider wire 格式解耦；</li>
 *   <li><b>流式 chunk 协议</b>：{@link #stream(Request, ChunkSink)} 按序交付
 *       {@code block-start / text-delta / reasoning-delta / tool-call-delta / block-end / usage / finish}，
 *       工具参数保持<b>原始分片字符串</b>（调用方负责组装，参考 dsh BlockAssembler 的分工）；
 *       每个调用以唯一的终端 {@link Finish} chunk 收尾（成功或失败都不抛出）；</li>
 *   <li><b>稳定错误码</b>：失败统一为 {@link LlmFailure}（{@code message/code/status/
 *       providerRetryAfterMs/requestId}），code 为可路由的稳定字符串（{@link Codes}），
 *       调用方按 code 而非文本分类（参考 dsh {@code httpErrorCode}）；</li>
 *   <li><b>重试分离</b>：本客户端每次调用只尝试一次，重试完全由外部策略（mod 的
 *       {@code LlmRetryPolicy}）负责，客户端自身 {@code maxRetries(0)}。</li>
 * </ul>
 *
 * <p>SDK 适配要点：
 * <ul>
 *   <li>懒加载共享基客户端 + 每次请求 {@code withOptions} 覆盖 baseUrl/apiKey/timeout
 *       （复用底层 OkHttp 调度线程池）；</li>
 *   <li>空 API Key 允许——SDK 鉴权闸门要求非空 key，空配置时发占位 key {@code "opencraft"}
 *       （本地无鉴权的 OpenAI 兼容服务忽略 Authorization）；含非法字符的 key 直接以
 *       {@code INVALID_CREDENTIAL} 失败（参考 dsh {@code normalizeApiKey}）；</li>
 *   <li>{@code reasoning_content}（思维链）经 SDK 各模型 {@code _additionalProperties()} 双向透传：
 *       流式从 delta、非流式从 message 捕捉，请求侧用
 *       {@code ChatCompletionAssistantMessageParam.putAdditionalProperty("reasoning_content", ...)}
 *       原样回传（DeepSeek 等推理模型带工具调用的 assistant 消息回传必填）；</li>
 *   <li>工具 schema 经 {@code ObjectMappers.jsonMapper().readValue(json, ChatCompletionFunctionTool.class)}
 *       反序列化（任意 JSON Schema 走 FunctionParameters 的 additionalProperties 保留）；</li>
 *   <li><b>SSE 读取看门狗</b>：SDK 的 OkHttp readTimeout 只保证“两次读之间间隔”，服务端拿连接后
 *       不吐数据仍可能无限阻塞——{@code stream()} 保留守护看门狗线程，按 <b>idle</b> 计时
 *       （每收到一个 chunk 重置），超过 {@code max(5, timeoutSeconds)+2s} 无数据即 {@code close()}
 *       底层流打断阻塞读，统一报 {@code STALLED}（参考 dsh idleWatchdog 的 idle 语义；dsh 把 idle
 *       超时归入 TIMEOUT，本 mod 保留既有决策把“连接后不吐数据”判为不可重试的 STALLED）；</li>
 *   <li><b>“端点忽略 stream”退化路径</b>：SDK 的 SSE 解析器要求 {@code data:} 行，端点直接返回完整
 *       JSON 时解析出 0 个 chunk，适配层对该请求<b>再发一次非流式</b> {@link #chat(Request)}
 *       （幂等端点返回同一回复），合成 blocks 与终端 finish；仅该退化场景多发一次请求。</li>
 * </ul>
 *
 * <p>纯 Java、无 Minecraft 依赖，可对本地 mock 服务器单测。所有回调在发起线程上按序串行执行。
 */
public final class LlmClient {
	/** 空 API Key 时的占位 key（SDK 鉴权闸门要求非空；本地无鉴权端点忽略 Authorization）。 */
	private static final String PLACEHOLDER_KEY = "opencraft";

	/** dsh api-key.ts：可打印 ASCII（含空格以外）的合法 API key。 */
	private static final Pattern LEGAL_API_KEY = Pattern.compile("^[\\x21-\\x7E]+$");

	private static final long CONNECT_TIMEOUT_SECONDS = 10L;
	/** 流式场景中 SDK read/request 超时相对看门狗的裕量（秒）：保证看门狗先触发 STALLED。 */
	private static final long STREAM_TIMEOUT_MARGIN_SECONDS = 5L;
	/** 看门狗 idle 超时的额外裕量（毫秒）。 */
	private static final long WATCHDOG_GRACE_MS = 2000L;

	// ------------------------------------------------------------------
	// 共享客户端（懒加载）与线程池
	// ------------------------------------------------------------------

	private static final ExecutorService SHARED_EXECUTOR = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "opencraft-llm-http");
		t.setDaemon(true);
		return t;
	});

	private static volatile OpenAIClient baseClient;

	private LlmClient() {
	}

	/** 预热：强制初始化共享基客户端（不发任何网络请求）。 */
	public static void warmUp() {
		baseClient();
	}

	private static OpenAIClient baseClient() {
		OpenAIClient client = baseClient;
		if (client == null) {
			synchronized (LlmClient.class) {
				client = baseClient;
				if (client == null) {
					client = OpenAIOkHttpClient.builder()
							.apiKey(PLACEHOLDER_KEY)
							// 重试归外部 LlmRetryPolicy 独家负责，SDK 不做自动重试
							.maxRetries(0)
							// 跨请求复用 OkHttp 调度线程池
							.dispatcherExecutorService(SHARED_EXECUTOR)
							.build();
					baseClient = client;
				}
			}
		}
		return client;
	}

	// ------------------------------------------------------------------
	// 提供者无关词汇（参考 dsh-llm types.ts / message.ts）
	// ------------------------------------------------------------------

	/** 内容块（dsh ContentBlock 的核心子集）。 */
	public sealed interface Block permits TextBlock, ReasoningBlock, ToolCallBlock, ToolResultBlock {
		/** 块的类型标签（"text"/"reasoning"/"tool-call"/"tool-result"）。 */
		String type();
	}

	/** 对用户可见的纯文本块。 */
	public record TextBlock(String text) implements Block {
		public TextBlock {
			text = text == null ? "" : text;
		}

		@Override
		public String type() {
			return "text";
		}
	}

	/** 推理/思维链块，与可见文本区分。 */
	public record ReasoningBlock(String text) implements Block {
		public ReasoningBlock {
			text = text == null ? "" : text;
		}

		@Override
		public String type() {
			return "reasoning";
		}
	}

	/** 模型发起的一次工具调用（完整形态）。 */
	public record ToolCallBlock(String id, String name, String arguments) implements Block {
		public ToolCallBlock {
			id = id == null ? "" : id;
			name = name == null ? "" : name;
			arguments = arguments == null ? "" : arguments;
		}

		@Override
		public String type() {
			return "tool-call";
		}
	}

	/** 工具执行结果（寄宿在 user 消息里，序列化时展开为 role:"tool" 的 wire 消息）。 */
	public record ToolResultBlock(String toolCallId, String content, boolean isError) implements Block {
		@Override
		public String type() {
			return "tool-result";
		}
	}

	/** 消息角色：system 提示词走 {@link Request#system()}，不占用消息角色。 */
	public enum Role {
		USER, ASSISTANT
	}

	/** 一条消息：角色 + 有序内容块（不可变）。 */
	public record Message(Role role, List<Block> content) {
		public Message {
			content = List.copyOf(content);
		}

		public static Message user(String text) {
			return new Message(Role.USER, List.of(new TextBlock(text)));
		}

		public static Message user(List<Block> content) {
			return new Message(Role.USER, content);
		}

		public static Message assistant(String text) {
			return new Message(Role.ASSISTANT, List.of(new TextBlock(text)));
		}

		public static Message assistant(List<Block> content) {
			return new Message(Role.ASSISTANT, content);
		}

		/** 一条仅含工具结果的 user 消息（序列化时展开为 role:"tool"）。 */
		public static Message toolResult(String toolCallId, String content, boolean isError) {
			return new Message(Role.USER, List.of(new ToolResultBlock(toolCallId, content, isError)));
		}

		/** 拼接全部文本块。 */
		public String text() {
			StringBuilder sb = new StringBuilder();
			for (Block b : content) {
				if (b instanceof TextBlock t) {
					sb.append(t.text());
				}
			}
			return sb.toString();
		}

		/** 拼接全部推理块。 */
		public String reasoning() {
			StringBuilder sb = new StringBuilder();
			for (Block b : content) {
				if (b instanceof ReasoningBlock r) {
					sb.append(r.text());
				}
			}
			return sb.toString();
		}

		/** 全部工具调用块。 */
		public List<ToolCallBlock> toolCalls() {
			List<ToolCallBlock> out = new ArrayList<>();
			for (Block b : content) {
				if (b instanceof ToolCallBlock t) {
					out.add(t);
				}
			}
			return out;
		}

		/** 全部工具结果块。 */
		public List<ToolResultBlock> toolResults() {
			List<ToolResultBlock> out = new ArrayList<>();
			for (Block b : content) {
				if (b instanceof ToolResultBlock t) {
					out.add(t);
				}
			}
			return out;
		}
	}

	/** 一个工具的 OpenAI function-calling schema。 */
	public record ToolSchema(String name, String description, JsonObject parameters) {
		public ToolSchema {
			parameters = parameters == null ? new JsonObject() : parameters;
		}

		/**
		 * 从 OpenAI tools 条目的 JSON 解析（{@code {"type":"function","function":
		 * {name,description,parameters}}}）。缺字段时 name/description 兜底为空串、
		 * parameters 兜底为 {@code {}}。
		 */
		public static ToolSchema fromJson(JsonObject tool) {
			JsonObject fn = (tool != null && tool.has("function") && tool.get("function").isJsonObject())
					? tool.getAsJsonObject("function") : new JsonObject();
			String name = (fn.has("name") && fn.get("name").isJsonPrimitive())
					? fn.get("name").getAsString() : "";
			String description = (fn.has("description") && fn.get("description").isJsonPrimitive())
					? fn.get("description").getAsString() : "";
			JsonObject parameters = (fn.has("parameters") && fn.get("parameters").isJsonObject())
					? fn.getAsJsonObject("parameters") : new JsonObject();
			return new ToolSchema(name, description, parameters);
		}
	}

	/**
	 * 一次请求的全部参数（dsh GenerateOptions 的 Java 版）。可选字段为 null/空时不上线（沿用
	 * provider 默认值）。{@code timeoutSeconds} 是超时预算：非流式=总超时；流式=idle 看门狗阈值。
	 */
	public record Request(String baseUrl, String apiKey, String model, String system,
	                      List<Message> messages, List<ToolSchema> tools,
	                      Double temperature, Integer maxTokens, List<String> stop,
	                      int timeoutSeconds) {
		public Request {
			messages = messages == null ? List.of() : List.copyOf(messages);
			tools = tools == null ? null : List.copyOf(tools);
			stop = stop == null ? null : List.copyOf(stop);
		}

		/** 纯聊天请求（无 tools/采样/stop）。 */
		public Request(String baseUrl, String apiKey, String model, String system,
		               List<Message> messages, int timeoutSeconds) {
			this(baseUrl, apiKey, model, system, messages, null, null, null, null, timeoutSeconds);
		}
	}

	// ------------------------------------------------------------------
	// 流式 chunk 协议（参考 dsh StreamChunk / translate.ts）
	// ------------------------------------------------------------------

	/** 块的类型标签（用于 {@link BlockStart}）。 */
	public enum BlockType {
		TEXT, REASONING, TOOL_CALL
	}

	/** 流式事件：以唯一的终端 {@link Finish} 收尾（成功/失败都不抛出）。 */
	public sealed interface Chunk permits BlockStart, TextDelta, ReasoningDelta,
			ToolCallDelta, BlockEnd, Usage, Finish {
	}

	/** 一个新内容块开始（index 由本客户端按序分配）。 */
	public record BlockStart(int index, BlockType blockType) implements Chunk {
	}

	/** 一段文本增量。 */
	public record TextDelta(int index, String text) implements Chunk {
	}

	/** 一段推理/思维链增量。 */
	public record ReasoningDelta(int index, String text) implements Chunk {
	}

	/**
	 * 一次工具调用的增量。{@code argumentsDelta} 是<b>原始分片字符串</b>（按 index 拼接成完整参数，
	 * 参考 dsh：工具参数保持 raw string，由调用方组装）；{@code id}/{@code name} 只在首个分片出现。
	 */
	public record ToolCallDelta(int index, String id, String name, String argumentsDelta) implements Chunk {
	}

	/** 一个内容块结束（携带组装好的完整块；在本客户端于流结束前按打开顺序补发）。 */
	public record BlockEnd(int index, Block block) implements Chunk {
	}

	/** 一次调用的 token 用量（互斥计数：cacheRead 已从 inputTokens 中扣除）。 */
	public record Usage(int inputTokens, int outputTokens,
	                    Integer cacheReadTokens, Integer reasoningTokens) implements Chunk {
	}

	/** 模型为何停止。 */
	public enum FinishKind {
		STOP, TOOL_CALLS, MAX_TOKENS, ERROR, ABORTED
	}

	/** 停止原因：{@code kind==ERROR} 时 {@code code} 为稳定错误码。 */
	public record FinishReason(FinishKind kind, String code) {
		public static FinishReason stop() {
			return new FinishReason(FinishKind.STOP, null);
		}

		public static FinishReason toolCalls() {
			return new FinishReason(FinishKind.TOOL_CALLS, null);
		}

		public static FinishReason maxTokens() {
			return new FinishReason(FinishKind.MAX_TOKENS, null);
		}

		public static FinishReason error(String code) {
			return new FinishReason(FinishKind.ERROR, code);
		}

		public static FinishReason aborted() {
			return new FinishReason(FinishKind.ABORTED, null);
		}
	}

	/** 终端 chunk：每个调用恰好一个；失败（ERROR/ABORTED）时携带 {@link LlmFailure}。 */
	public record Finish(FinishReason reason, LlmFailure failure) implements Chunk {
		public Finish {
			// 错误类 reason 必须带失败事实：缺省时按 reason 补一个（如 finish_reason 映射的
			// content_filter 等，此时没有 HTTP 层面的失败对象）
			if (failure == null && (reason.kind() == FinishKind.ERROR || reason.kind() == FinishKind.ABORTED)) {
				String code = reason.code() == null
						? (reason.kind() == FinishKind.ABORTED ? Codes.ABORTED : "UNKNOWN")
						: reason.code();
				failure = LlmFailure.of("model stopped: " + code, code);
			}
		}

		public boolean ok() {
			return reason == null || reason.kind() != FinishKind.ERROR && reason.kind() != FinishKind.ABORTED;
		}
	}

	// ------------------------------------------------------------------
	// 失败（参考 dsh LlmFailure / error.ts）
	// ------------------------------------------------------------------

	/**
	 * 一次失败的可序列化事实；code 为稳定的机器可路由字符串（参考 dsh {@code LlmFailure}）。
	 * 策略（是否重试）属于调用方，不在本记录内。
	 */
	public record LlmFailure(String message, String code, Integer status,
	                         Long providerRetryAfterMs, String requestId) {
		public LlmFailure {
			if (code == null || code.isBlank()) {
				throw new IllegalArgumentException("LlmFailure code is required");
			}
		}

		public static LlmFailure of(String message, String code) {
			return new LlmFailure(message, code, null, null, null);
		}
	}

	/** 稳定的提供者无关失败码（参考 dsh error.ts 的常量与 adapter 的 httpErrorCode）。 */
	public static final class Codes {
		/** 401/403：鉴权失败。 */
		public static final String AUTH = "AUTH";
		/** 429：限流。 */
		public static final String RATE_LIMIT = "RATE_LIMIT";
		/** 账户配额/余额耗尽（区别于请求限流）。 */
		public static final String QUOTA = "QUOTA";
		/** 请求超出模型上下文窗口。 */
		public static final String CONTEXT_WINDOW_EXCEEDED = "CONTEXT_WINDOW_EXCEEDED";
		/** 400/413 等参数类错误。 */
		public static final String INVALID_REQUEST = "INVALID_REQUEST";
		/** ≥500：服务端错误。 */
		public static final String SERVER = "SERVER";
		/** 超时（可重试的瞬时失败）。 */
		public static final String TIMEOUT = "TIMEOUT";
		/** 网络传输错误（连接拒绝/DNS/TLS 等）。 */
		public static final String TRANSPORT = "TRANSPORT";
		/** 调用方主动中止。 */
		public static final String ABORTED = "ABORTED";
		/** 模型返回了无任何内容的完整回复（或请求无消息）。 */
		public static final String EMPTY_RESPONSE = "EMPTY_RESPONSE";
		/** 响应无法解析（非瞬时）。 */
		public static final String MALFORMED_RESPONSE = "MALFORMED_RESPONSE";
		/** 流提前关闭。 */
		public static final String STREAM_CLOSED = "STREAM_CLOSED";
		/** 凭据已提供但无法使用（格式非法，区别于 MISSING_CREDENTIAL）。 */
		public static final String INVALID_CREDENTIAL = "INVALID_CREDENTIAL";
		/** 未提供凭据（本 mod 以占位 key 处理，通常不会出现）。 */
		public static final String MISSING_CREDENTIAL = "MISSING_CREDENTIAL";
		/** 内容类型不受支持。 */
		public static final String UNSUPPORTED_CONTENT = "UNSUPPORTED_CONTENT";
		/**
		 * mod 专有：SSE 读取看门狗——服务端拿到连接后长时间不吐数据。判为<b>不可重试</b>
		 * （重试只会再次白等，快速失败让玩家重问/中断）。dsh 把 idle 超时归入 TIMEOUT，
		 * 本 mod 保留既有决策单独成码。
		 */
		public static final String STALLED = "STALLED";

		private Codes() {
		}
	}

	// ------------------------------------------------------------------
	// 调用
	// ------------------------------------------------------------------

	/** 非流式调用的结果：{@code ok()} 时 content+reason；失败时 failure 非空。 */
	public record ChatResult(List<Block> content, FinishReason reason, LlmFailure failure) {
		public ChatResult {
			content = List.copyOf(content);
		}

		public static ChatResult success(List<Block> content, FinishReason reason) {
			return new ChatResult(content, reason, null);
		}

		public static ChatResult failure(LlmFailure failure) {
			return new ChatResult(List.of(), FinishReason.error(failure.code()), failure);
		}

		public boolean ok() {
			return failure == null;
		}

		/** 拼接全部文本块（供历史压缩等只需正文的调用方使用）。 */
		public String text() {
			StringBuilder sb = new StringBuilder();
			for (Block b : content) {
				if (b instanceof TextBlock t) {
					sb.append(t.text());
				}
			}
			return sb.toString();
		}
	}

	/** 流式事件的接收器。所有回调在发起 {@link #stream(Request, ChunkSink)} 的线程上按序串行执行。 */
	@FunctionalInterface
	public interface ChunkSink {
		void onChunk(Chunk chunk);
	}

	/**
	 * 发送一次非流式对话请求（单次尝试，一次性拿到完整回复）。
	 * 任何异常都以失败 {@link ChatResult} 返回，不会抛出；重试由外部策略负责。
	 */
	public static ChatResult chat(Request request) {
		if (request == null || hasNoContent(request)) {
			return ChatResult.failure(LlmFailure.of("消息列表为空", Codes.EMPTY_RESPONSE));
		}
		if (normalizeBaseUrl(request.baseUrl()).isEmpty()) {
			return ChatResult.failure(LlmFailure.of("baseUrl 为空", Codes.INVALID_REQUEST));
		}
		ApiKeyCheck key = checkApiKey(request.apiKey());
		if (!key.ok() && !"empty".equals(key.reason())) {
			return ChatResult.failure(LlmFailure.of("Invalid API key: " + key.reason(), Codes.INVALID_CREDENTIAL));
		}
		LlmDebugLogger.logRequest(request);
		try {
			OpenAIClient client = clientFor(key, request.baseUrl(), nonStreamTimeout(request.timeoutSeconds()));
			ChatCompletion completion = client.chat().completions().create(buildParams(request, false));
			ChatResult result = parseCompletion(completion);
			LlmDebugLogger.logResponse(result.content(), result.reason(), result.failure());
			return result;
		} catch (Exception e) {
			ChatResult result = ChatResult.failure(toFailure(e));
			LlmDebugLogger.logResponse(result.content(), result.reason(), result.failure());
			return result;
		}
	}

	/**
	 * 发送一次流式对话请求（SSE），以 chunk 协议交付。终端 {@link Finish} 恒为最后一个 chunk，
	 * 失败也在 Finish 内表达（不抛出）。
	 *
	 * <p>看门狗：守护线程按 idle 计时（每收到一个 chunk 重置），超过
	 * {@code max(5, timeoutSeconds)+2s} 无数据即关闭底层流，以 {@code STALLED} 失败收尾。
	 */
	public static void stream(Request request, ChunkSink sink) {
		if (sink == null) {
			throw new IllegalArgumentException("ChunkSink is required");
		}
		if (request == null || hasNoContent(request)) {
			sink.onChunk(new Finish(FinishReason.error(Codes.EMPTY_RESPONSE),
					LlmFailure.of("消息列表为空", Codes.EMPTY_RESPONSE)));
			return;
		}
		if (normalizeBaseUrl(request.baseUrl()).isEmpty()) {
			sink.onChunk(new Finish(FinishReason.error(Codes.INVALID_REQUEST),
					LlmFailure.of("baseUrl 为空", Codes.INVALID_REQUEST)));
			return;
		}
		ApiKeyCheck key = checkApiKey(request.apiKey());
		if (!key.ok() && !"empty".equals(key.reason())) {
			sink.onChunk(new Finish(FinishReason.error(Codes.INVALID_CREDENTIAL),
					LlmFailure.of("Invalid API key: " + key.reason(), Codes.INVALID_CREDENTIAL)));
			return;
		}
		LlmDebugLogger.logRequest(request);
		// 包装 sink：在 BlockEnd 收集完整块、Finish 时记录原始回复
		ChunkSink loggingSink = LlmDebugLogger.wrapSink(sink);
		AtomicBoolean finished = new AtomicBoolean(false);
		AtomicBoolean stalled = new AtomicBoolean(false);
		try {
			OpenAIClient client = clientFor(key, request.baseUrl(), streamTimeout(request.timeoutSeconds()));
			StreamResponse<ChatCompletionChunk> stream = client.chat().completions().createStreaming(
					buildParams(request, true));
			Watchdog watchdog = new Watchdog(request.timeoutSeconds(), stream, stalled, finished);
			watchdog.start();
			try (stream) {
				translate(request, stream, loggingSink, watchdog);
				// 正常完成：translate 已发终端 Finish；置位后即使收尾关闭抛异常也不重复发
				finished.set(true);
			} finally {
				watchdog.stop();
			}
		} catch (Exception e) {
			// 只发一个终端 Finish（防看门狗与正常结束的竞态）
			if (!finished.getAndSet(true)) {
				if (stalled.get()) {
					loggingSink.onChunk(new Finish(FinishReason.error(Codes.STALLED),
							LlmFailure.of("request-stalled: 服务端连接后长时间未返回数据", Codes.STALLED)));
				} else {
					loggingSink.onChunk(new Finish(FinishReason.error(failureCode(e)), toFailure(e)));
				}
			}
		}
	}

	private static boolean hasNoContent(Request request) {
		return (request.system() == null || request.system().isBlank())
				&& (request.messages() == null || request.messages().isEmpty());
	}

	// ------------------------------------------------------------------
	// 客户端管道
	// ------------------------------------------------------------------

	private static OpenAIClient clientFor(ApiKeyCheck key, String baseUrl, Timeout timeout) {
		return baseClient().withOptions(b -> {
			b.baseUrl(normalizeBaseUrl(baseUrl));
			b.apiKey("empty".equals(key.reason()) ? PLACEHOLDER_KEY : key.value());
			b.timeout(timeout);
		});
	}

	/** 非流式：总超时（connect 10s + request 总时限）。 */
	private static Timeout nonStreamTimeout(int timeoutSeconds) {
		return Timeout.builder()
				.connect(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
				.request(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
				.build();
	}

	/** 流式：仅作兜底（connect/read/request 都放大），真正的“连接后不吐数据”由看门狗负责。 */
	private static Timeout streamTimeout(int timeoutSeconds) {
		long total = Math.max(5, timeoutSeconds) + STREAM_TIMEOUT_MARGIN_SECONDS;
		return Timeout.builder()
				.connect(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
				.read(Duration.ofSeconds(total))
				.request(Duration.ofSeconds(total))
				.build();
	}

	/**
	 * 规范化 baseUrl：去尾部斜杠；若以 {@code /chat/completions} 结尾则去掉该后缀
	 * （SDK 自行拼接路径，baseUrl 必须是根形态）。
	 */
	public static String normalizeBaseUrl(String baseUrl) {
		String url = baseUrl == null ? "" : baseUrl.trim();
		while (url.endsWith("/")) {
			url = url.substring(0, url.length() - 1);
		}
		if (url.endsWith("/chat/completions")) {
			url = url.substring(0, url.length() - "/chat/completions".length());
		}
		return url;
	}

	// ------------------------------------------------------------------
	// 请求序列化（参考 dsh serialize.ts）
	// ------------------------------------------------------------------

	private static ChatCompletionCreateParams buildParams(Request request, boolean stream) {
		ChatCompletionCreateParams.Builder b = ChatCompletionCreateParams.builder()
				.model(request.model());
		if (request.system() != null && !request.system().isBlank()) {
			b.addSystemMessage(request.system());
		}
		for (Message m : request.messages()) {
			addWireMessage(b, m);
		}
		if (request.temperature() != null) {
			b.temperature(request.temperature());
		}
		if (request.maxTokens() != null) {
			b.maxCompletionTokens(request.maxTokens().longValue());
		}
		if (request.stop() != null && !request.stop().isEmpty()) {
			b.stop(ChatCompletionCreateParams.Stop.ofStrings(request.stop()));
		}
		if (request.tools() != null && !request.tools().isEmpty()) {
			List<ChatCompletionTool> wireTools = new ArrayList<>();
			for (ToolSchema tool : request.tools()) {
				JsonObject fn = new JsonObject();
				if (tool.name() != null) {
					fn.addProperty("name", tool.name());
				}
				if (tool.description() != null) {
					fn.addProperty("description", tool.description());
				}
				fn.add("parameters", tool.parameters() == null ? new JsonObject() : tool.parameters());
				JsonObject schema = new JsonObject();
				schema.addProperty("type", "function");
				schema.add("function", fn);
				// 任意 JSON Schema 经 FunctionParameters 的 additionalProperties 保留
				ChatCompletionFunctionTool parsed;
				try {
					parsed = jsonMapper().readValue(schema.toString(), ChatCompletionFunctionTool.class);
				} catch (com.fasterxml.jackson.core.JsonProcessingException e) {
					throw new IllegalArgumentException("无法解析工具 schema: " + tool.name(), e);
				}
				wireTools.add(ChatCompletionTool.ofFunction(parsed));
			}
			b.tools(wireTools);
		}
		if (stream) {
			b.streamOptions(ChatCompletionStreamOptions.builder().includeUsage(true).build());
		}
		return b.build();
	}

	private static void addWireMessage(ChatCompletionCreateParams.Builder b, Message m) {
		switch (m.role()) {
			case ASSISTANT -> {
				String text = m.text();
				List<ToolCallBlock> calls = m.toolCalls();
				String reasoning = m.reasoning();
				ChatCompletionAssistantMessageParam.Builder ab = ChatCompletionAssistantMessageParam.builder()
						// 文本为空的回合发 "" 而非 null（纯工具调用回合 / 纯推理回合）
						.content(text == null ? "" : text);
				if (reasoning != null && !reasoning.isEmpty()) {
					// 思维链回传：DeepSeek 等推理模型带工具调用的 assistant 消息回传必填
					ab.putAdditionalProperty("reasoning_content", JsonValue.from(reasoning));
				}
				if (!calls.isEmpty()) {
					List<ChatCompletionMessageToolCall> wireCalls = new ArrayList<>();
					for (ToolCallBlock c : calls) {
						wireCalls.add(ChatCompletionMessageToolCall.ofFunction(
								ChatCompletionMessageFunctionToolCall.builder()
										.id(c.id())
										.function(ChatCompletionMessageFunctionToolCall.Function.builder()
												.name(c.name())
												.arguments(c.arguments())
												.build())
										.build()));
					}
					ab.toolCalls(wireCalls);
				}
				b.addMessage(ChatCompletionMessageParam.ofAssistant(ab.build()));
			}
			case USER -> {
				String text = m.text();
				List<ToolResultBlock> results = m.toolResults();
				if (text.isEmpty() && results.isEmpty()) {
					return; // 空 user 消息不上线
				}
				// 有文本（或没有工具结果）时先发 user 消息，工具结果各自展开为 role:"tool"
				if (!text.isEmpty() || results.isEmpty()) {
					b.addMessage(ChatCompletionMessageParam.ofUser(
							ChatCompletionUserMessageParam.builder().content(text).build()));
				}
				for (ToolResultBlock r : results) {
					String content = r.content() == null || r.content().isEmpty()
							? "(no output)" : r.content();
					b.addMessage(ChatCompletionMessageParam.ofTool(
							ChatCompletionToolMessageParam.builder()
									.toolCallId(r.toolCallId())
									.content(content)
									.build()));
				}
			}
			default -> {
				// 未知角色：安全忽略（Role 是枚举，正常不会走到）
			}
		}
	}

	// ------------------------------------------------------------------
	// 非流式解析
	// ------------------------------------------------------------------

	private static ChatResult parseCompletion(ChatCompletion completion) {
		if (completion.choices() == null || completion.choices().isEmpty()) {
			return ChatResult.failure(LlmFailure.of("响应中没有 choices", Codes.EMPTY_RESPONSE));
		}
		ChatCompletion.Choice choice = completion.choices().get(0);
		ChatCompletionMessage message = choice.message();
		String content = message.content().orElse(null);
		String reasoning = reasoningOf(message._additionalProperties());
		List<Block> blocks = new ArrayList<>();
		if (content != null && !content.isEmpty()) {
			blocks.add(new TextBlock(content));
		}
		if (reasoning != null && !reasoning.isEmpty()) {
			blocks.add(new ReasoningBlock(reasoning));
		}
		for (ChatCompletionMessageToolCall tc : message.toolCalls().orElse(List.of())) {
			ChatCompletionMessageFunctionToolCall fn = tc.asFunction();
			blocks.add(new ToolCallBlock(fn.id(), fn.function().name(), fn.function().arguments()));
		}
		FinishReason reason = mapFinishReason(choice.finishReason());
		if (reason.kind() == FinishKind.STOP && blocks.isEmpty()) {
			return ChatResult.failure(LlmFailure.of("模型返回了无任何内容的完整回复", Codes.EMPTY_RESPONSE));
		}
		return ChatResult.success(blocks, reason);
	}

	private static String reasoningOf(Map<String, JsonValue> additional) {
		if (additional == null) {
			return null;
		}
		JsonValue v = additional.get("reasoning_content");
		if (v == null) {
			return null;
		}
		java.util.Optional<String> s = v.asString();
		return s.orElse(null);
	}

	// ------------------------------------------------------------------
	// 流式翻译（参考 dsh translate.ts）
	// ------------------------------------------------------------------

	/** 一个正在组装的块（流结束后转成 {@link BlockEnd}）。 */
	private static final class OpenBlock {
		final int index;
		final BlockType type;
		String text = "";
		String callId;
		String name;

		OpenBlock(int index, BlockType type) {
			this.index = index;
			this.type = type;
		}
	}

	private static OpenBlock open(List<OpenBlock> order, BlockType type) {
		OpenBlock block = new OpenBlock(order.size(), type);
		order.add(block);
		return block;
	}

	private static Block closeBlock(OpenBlock b) {
		return switch (b.type) {
			case TEXT -> new TextBlock(b.text);
			case REASONING -> new ReasoningBlock(b.text);
			case TOOL_CALL -> new ToolCallBlock(
					b.callId == null ? "" : b.callId,
					b.name == null ? "" : b.name,
					b.text);
		};
	}

	private static void translate(Request request, StreamResponse<ChatCompletionChunk> stream,
	                              ChunkSink sink, Watchdog watchdog) {
		List<OpenBlock> order = new ArrayList<>();
		OpenBlock[] text = {null};
		OpenBlock[] reasoning = {null};
		Map<Integer, OpenBlock> tools = new HashMap<>();
		FinishReason[] pendingReason = {null};
		Usage[] pendingUsage = {null};
		int count = 0;
		Iterator<ChatCompletionChunk> it = stream.stream().iterator();
		while (it.hasNext()) {
			ChatCompletionChunk chunk = it.next();
			count++;
			watchdog.pulse();
			translateChunk(chunk, order, text, reasoning, tools, pendingReason, pendingUsage, sink);
		}
		if (count == 0) {
			// 端点忽略 stream、直接返回完整 JSON：SDK 的 SSE 解析器产出 0 个 chunk。
			// 退化：对该请求再发一次非流式 chat()（幂等端点返回同一回复），合成 chunk 序列。
			emitSynthesized(chat(request), sink);
			return;
		}
		for (OpenBlock b : order) {
			sink.onChunk(new BlockEnd(b.index, closeBlock(b)));
		}
		if (pendingUsage[0] != null) {
			sink.onChunk(pendingUsage[0]);
		}
		FinishReason reason = pendingReason[0] == null ? FinishReason.stop() : pendingReason[0];
		if (reason.kind() == FinishKind.STOP && order.isEmpty()) {
			sink.onChunk(new Finish(FinishReason.error(Codes.EMPTY_RESPONSE),
					LlmFailure.of("模型返回了无任何内容的完整回复", Codes.EMPTY_RESPONSE)));
		} else {
			sink.onChunk(new Finish(reason, null));
		}
	}

	private static void translateChunk(ChatCompletionChunk chunk, List<OpenBlock> order,
	                                   OpenBlock[] text, OpenBlock[] reasoning,
	                                   Map<Integer, OpenBlock> tools,
	                                   FinishReason[] pendingReason, Usage[] pendingUsage,
	                                   ChunkSink sink) {
		for (ChatCompletionChunk.Choice choice : chunk.choices()) {
			ChatCompletionChunk.Choice.Delta delta = choice.delta();
			// 推理先行：思维链与文本交错，空字符串首块不开块
			String reasoningText = reasoningOf(delta._additionalProperties());
			if (reasoningText != null && !reasoningText.isEmpty()) {
				if (reasoning[0] == null) {
					reasoning[0] = open(order, BlockType.REASONING);
					sink.onChunk(new BlockStart(reasoning[0].index, BlockType.REASONING));
				}
				reasoning[0].text += reasoningText;
				sink.onChunk(new ReasoningDelta(reasoning[0].index, reasoningText));
			}
			Optional<String> content = delta.content();
			if (content.isPresent() && !content.get().isEmpty()) {
				if (text[0] == null) {
					text[0] = open(order, BlockType.TEXT);
					sink.onChunk(new BlockStart(text[0].index, BlockType.TEXT));
				}
				text[0].text += content.get();
				sink.onChunk(new TextDelta(text[0].index, content.get()));
			}
			// 工具调用按 index 分片：id/name 取首个分片，arguments 为增量字符串
			for (ChatCompletionChunk.Choice.Delta.ToolCall tc : delta.toolCalls().orElse(List.of())) {
				int index = (int) tc.index();
				OpenBlock block = tools.get(index);
				if (block == null) {
					block = open(order, BlockType.TOOL_CALL);
					tools.put(index, block);
					sink.onChunk(new BlockStart(block.index, BlockType.TOOL_CALL));
				}
				OpenBlock target = block;
				tc.id().ifPresent(id -> target.callId = id);
				tc.function().ifPresent(fn -> fn.name().ifPresent(n -> target.name = n));
				String fragment = tc.function().flatMap(fn -> fn.arguments()).orElse("");
				block.text += fragment;
				sink.onChunk(new ToolCallDelta(block.index, block.callId, block.name, fragment));
			}
			choice.finishReason().ifPresent(fr -> pendingReason[0] = mapFinishReason(fr));
		}
		chunk.usage().ifPresent(u -> pendingUsage[0] = mapUsage(u));
	}

	/** 把一次非流式结果合成 chunk 序列（退化路径用）。 */
	private static void emitSynthesized(ChatResult result, ChunkSink sink) {
		if (!result.ok()) {
			sink.onChunk(new Finish(FinishReason.error(result.failure().code()), result.failure()));
			return;
		}
		int index = 0;
		String text = result.text();
		if (!text.isEmpty()) {
			sink.onChunk(new BlockStart(index, BlockType.TEXT));
			sink.onChunk(new TextDelta(index, text));
			sink.onChunk(new BlockEnd(index, new TextBlock(text)));
			index++;
		}
		for (Block b : result.content()) {
			if (b instanceof ReasoningBlock r) {
				sink.onChunk(new BlockStart(index, BlockType.REASONING));
				sink.onChunk(new ReasoningDelta(index, r.text()));
				sink.onChunk(new BlockEnd(index, r));
				index++;
			}
		}
		for (Block b : result.content()) {
			if (b instanceof ToolCallBlock t) {
				sink.onChunk(new BlockStart(index, BlockType.TOOL_CALL));
				sink.onChunk(new ToolCallDelta(index, t.id(), t.name(), t.arguments()));
				sink.onChunk(new BlockEnd(index, t));
				index++;
			}
		}
		sink.onChunk(new Finish(result.reason(), null));
	}

	private static FinishReason mapFinishReason(ChatCompletion.Choice.FinishReason reason) {
		java.util.Optional<String> rawValue = reason._value().asKnown();
		return mapFinishReasonValue(rawValue.orElse(""));
	}

	private static FinishReason mapFinishReason(ChatCompletionChunk.Choice.FinishReason reason) {
		java.util.Optional<String> rawValue = reason._value().asKnown();
		return mapFinishReasonValue(rawValue.orElse(""));
	}

	/**
	 * 按<b>值</b>映射 finish reason。注意：Jackson 反序列化得到的是新实例，与 SDK 的
	 * 常量（STOP/TOOL_CALLS/…）不保证同一，必须比较原始字符串而非 {@code ==}。
	 */
	private static FinishReason mapFinishReasonValue(String raw) {
		return switch (raw) {
			case "stop" -> FinishReason.stop();
			case "tool_calls", "function_call" -> FinishReason.toolCalls();
			case "length" -> FinishReason.maxTokens();
			// content_filter / 未来新增：以原始原因大写作为错误码
			default -> FinishReason.error(raw.isEmpty() ? "UNKNOWN" : raw.toUpperCase(Locale.ROOT));
		};
	}

	/** dsh mapUsage：prompt_tokens 含 cache 命中，inputTokens 为互斥的未缓存输入。 */
	private static Usage mapUsage(CompletionUsage usage) {
		Long cacheRead = usage.promptTokensDetails().flatMap(d -> d.cachedTokens()).orElse(null);
		long input = usage.promptTokens() - (cacheRead == null ? 0L : cacheRead);
		long output = usage.completionTokens();
		Long reasoning = usage.completionTokensDetails().flatMap(d -> d.reasoningTokens()).orElse(null);
		return new Usage(safeInt(input), safeInt(output),
				cacheRead == null ? null : safeInt(cacheRead),
				reasoning == null ? null : safeInt(reasoning));
	}

	private static int safeInt(long value) {
		return (int) Math.min(Integer.MAX_VALUE, Math.max(Integer.MIN_VALUE, value));
	}

	// ------------------------------------------------------------------
	// SSE 读取看门狗（idle 计时；参考 dsh idleWatchdog，但归入 mod 的 STALLED）
	// ------------------------------------------------------------------

	private static final class Watchdog {
		private final long idleMs;
		private final StreamResponse<?> stream;
		private final AtomicBoolean stalled;
		private final AtomicBoolean finished;
		private final Object lock = new Object();
		private volatile long deadline;
		private final Thread thread;

		Watchdog(int timeoutSeconds, StreamResponse<?> stream,
		         AtomicBoolean stalled, AtomicBoolean finished) {
			this.idleMs = Math.max(5, timeoutSeconds) * 1000L + WATCHDOG_GRACE_MS;
			this.stream = stream;
			this.stalled = stalled;
			this.finished = finished;
			this.deadline = System.currentTimeMillis() + idleMs;
			this.thread = new Thread(this::run, "opencraft-llm-watchdog");
			this.thread.setDaemon(true);
		}

		void start() {
			thread.start();
		}

		/** 每收到一个 chunk 调用：重置 idle 计时。 */
		void pulse() {
			deadline = System.currentTimeMillis() + idleMs;
			synchronized (lock) {
				lock.notifyAll();
			}
		}

		void stop() {
			thread.interrupt();
		}

		private void run() {
			try {
				while (!finished.get()) {
					long remaining = deadline - System.currentTimeMillis();
					if (remaining <= 0) {
						// 服务端长时间不吐数据：关闭底层流打断阻塞读（阻塞读会抛异常，
						// 由 stream() 的 catch 统一转为 STALLED 失败）
						stalled.set(true);
						try {
							stream.close();
						} catch (Exception ignored) {
							// 关闭失败不影响（读可能已经自行结束）
						}
						return;
					}
					synchronized (lock) {
						lock.wait(remaining);
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	// ------------------------------------------------------------------
	// 错误映射（参考 dsh httpErrorCode + SDK 异常层级）
	// ------------------------------------------------------------------

	/** 把 SDK 异常映射成稳定失败码（供终端 Finish 使用）。 */
	static String failureCode(Throwable e) {
		if (e instanceof OpenAIServiceException se) {
			return httpErrorCode(se.statusCode(), se.getMessage());
		}
		if (e instanceof OpenAIIoException io) {
			return isTimeout(io) ? Codes.TIMEOUT : Codes.TRANSPORT;
		}
		if (e instanceof OpenAIInvalidDataException) {
			return Codes.MALFORMED_RESPONSE;
		}
		if (e instanceof IllegalArgumentException) {
			return Codes.INVALID_REQUEST;
		}
		return Codes.TRANSPORT;
	}

	static LlmFailure toFailure(Throwable e) {
		if (e instanceof OpenAIServiceException se) {
			int status = se.statusCode();
			String message = se.getMessage() == null ? "HTTP " + status : se.getMessage();
			String code = httpErrorCode(status, message);
			Headers headers = se.headers();
			return new LlmFailure(message, code, status,
					retryAfterMs(headers.values("retry-after")),
					firstNonBlank(headers.values("x-request-id"), headers.values("x-deepseek-request-id")));
		}
		if (e instanceof OpenAIIoException io) {
			String message = io.getMessage() == null ? io.getClass().getSimpleName() : io.getMessage();
			return new LlmFailure(message, isTimeout(io) ? Codes.TIMEOUT : Codes.TRANSPORT,
					null, null, null);
		}
		if (e instanceof OpenAIInvalidDataException bad) {
			String message = bad.getMessage() == null ? "Malformed response" : bad.getMessage();
			return new LlmFailure(message, Codes.MALFORMED_RESPONSE, null, null, null);
		}
		String message = e.getClass().getSimpleName() + ": "
				+ (e.getMessage() == null ? String.valueOf(e) : e.getMessage());
		return new LlmFailure(message, Codes.TRANSPORT, null, null, null);
	}

	private static boolean isTimeout(Throwable t) {
		for (Throwable c = t; c != null; c = c.getCause()) {
			if (c instanceof SocketTimeoutException) {
				return true;
			}
			String m = c.getMessage();
			if (m != null) {
				String lower = m.toLowerCase(Locale.ROOT);
				if (lower.contains("timeout") || lower.contains("timed out")) {
					return true;
				}
			}
		}
		return false;
	}

	private static Long retryAfterMs(List<String> values) {
		String v = values == null || values.isEmpty() ? null : values.get(0);
		if (v == null || v.isBlank()) {
			return null;
		}
		if (v.matches("\\d+")) {
			long seconds = Long.parseLong(v);
			return seconds > 0 ? seconds * 1000L : null;
		}
		try {
			long ms = DateTimeFormatter.RFC_1123_DATE_TIME.parse(v, Instant::from).toEpochMilli()
					- System.currentTimeMillis();
			return ms > 0 ? ms : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static String firstNonBlank(List<String> a, List<String> b) {
		for (String s : a) {
			if (s != null && !s.isBlank()) {
				return s;
			}
		}
		for (String s : b) {
			if (s != null && !s.isBlank()) {
				return s;
			}
		}
		return null;
	}

	/** 参考 dsh httpErrorCode：HTTP 状态 + 错误措辞 → 稳定失败码。 */
	static String httpErrorCode(int status, String detail) {
		if (status == 401 || status == 403) {
			return Codes.AUTH;
		}
		if (status == 413) {
			return Codes.INVALID_REQUEST;
		}
		if (isQuotaExceeded(detail)) {
			return Codes.QUOTA;
		}
		if (status == 429) {
			return Codes.RATE_LIMIT;
		}
		if (status == 400) {
			return isContextWindowExceeded(detail) ? Codes.CONTEXT_WINDOW_EXCEEDED : Codes.INVALID_REQUEST;
		}
		if (status >= 500) {
			return Codes.SERVER;
		}
		return "HTTP_" + status;
	}

	// ------------------------------------------------------------------
	// 上下文/配额措辞分类（移植 dsh error.ts 的 isContextWindowExceededError / isQuotaExceededError）
	// ------------------------------------------------------------------

	private static final Pattern STRUCTURED_CONTEXT_OVERFLOW = Pattern.compile(
			"(?:^|[^a-z0-9])context[\\s_-](?:length|window)[\\s_-]"
					+ "(?:exceed(?:ed|s)?|overflow(?:ed)?|limit[\\s_-]exceeded)(?:$|[^a-z0-9])",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern MAX_CONTEXT = Pattern.compile(
			"\\b(?:maximum|max)(?:\\s+(?:allowed|supported))?\\s+context\\s+(?:length|window)\\b",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern TOO_LARGE_FOR_CONTEXT = Pattern.compile(
			"\\b(?:request|prompt|input|messages?)\\s+(?:is\\s+|are\\s+)?too\\s+(?:large|long)"
					+ "\\s+for\\s+(?:(?:this|the)\\s+)?(?:model(?:'s)?\\s+)?context(?:\\s+window)?\\b",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern TOO_LONG_FOR_MODEL = Pattern.compile(
			"\\b(?:input|prompt|request)\\s+(?:is\\s+)?too\\s+(?:long|large)\\s+for\\s+(?:this|the)\\s+model\\b",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern EXCEEDS_MODEL_CONTEXT = Pattern.compile(
			"\\b(?:input|prompt|request|messages?)\\b.{0,40}"
					+ "\\b(?:exceed(?:s|ed)?|overflows?|is\\s+larger\\s+than)\\b.{0,40}"
					+ "\\b(?:the\\s+)?(?:model(?:'s)?\\s+)?context(?:\\s+(?:length|window))?\\b",
			Pattern.CASE_INSENSITIVE);

	/** 识别 OpenAI 兼容提供者的“请求超出模型上下文窗口”措辞。 */
	static boolean isContextWindowExceeded(String detail) {
		if (detail == null) {
			return false;
		}
		return STRUCTURED_CONTEXT_OVERFLOW.matcher(detail).find()
				|| MAX_CONTEXT.matcher(detail).find()
				|| TOO_LARGE_FOR_CONTEXT.matcher(detail).find()
				|| TOO_LONG_FOR_MODEL.matcher(detail).find()
				|| EXCEEDS_MODEL_CONTEXT.matcher(detail).find();
	}

	private static final Pattern QUOTA_EXCEEDED = Pattern.compile(
			"\\binsufficient[\\s_-]+(?:quota|balance|credits?)\\b"
					+ "|\\b(?:quota|usage[\\s_-]+limit)[\\s_-]+(?:exceeded|exhausted|reached)\\b"
					+ "|\\bexceed(?:ed|s)?[\\s_-]+(?:(?:your|the)[\\s_-]+)?(?:current[\\s_-]+)?quota\\b"
					+ "|\\b(?:balance|credits?)[\\s_-]+(?:exhausted|depleted)\\b"
					+ "|\\bout[\\s_-]+of[\\s_-]+(?:credits?|budget)\\b",
			Pattern.CASE_INSENSITIVE);

	/** 识别“账户配额/余额耗尽”措辞（区别于瞬时限流）。 */
	static boolean isQuotaExceeded(String detail) {
		return detail != null && QUOTA_EXCEEDED.matcher(detail).find();
	}

	// ------------------------------------------------------------------
	// API Key 校验（参考 dsh api-key.ts normalizeApiKey）
	// ------------------------------------------------------------------

	/** 一个已提供 API Key 的校验结果。 */
	public record ApiKeyCheck(boolean ok, String value, String reason) {
		static ApiKeyCheck ok(String value) {
			return new ApiKeyCheck(true, value, null);
		}

		static ApiKeyCheck rejected(String reason) {
			return new ApiKeyCheck(false, null, reason);
		}
	}

	/**
	 * 校验一个<b>已提供</b>的 API Key：先 trim；空 → {@code "empty"}；含不可打印字符 → 
	 * {@code "illegalCharacters"}；否则返回 trim 后的值。缺省（null/空白）由调用方决定
	 * （本 mod 以占位 key 处理本地无鉴权端点）。
	 */
	public static ApiKeyCheck checkApiKey(String raw) {
		if (raw == null) {
			return ApiKeyCheck.rejected("empty");
		}
		String value = raw.trim();
		if (value.isEmpty()) {
			return ApiKeyCheck.rejected("empty");
		}
		if (!LEGAL_API_KEY.matcher(value).matches()) {
			return ApiKeyCheck.rejected("illegalCharacters");
		}
		return ApiKeyCheck.ok(value);
	}
}
