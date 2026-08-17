package com.swaydy.opencraft.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 与 OpenAI 兼容的 Chat Completions 接口通信的纯 Java 客户端。
 *
 * 本类不依赖任何 Minecraft 类，便于独立测试：
 * 只需要一个实现 POST {baseUrl}/chat/completions 的服务器即可。
 *
 * 请求体：
 * <pre>
 * { "model": "...", "messages": [{"role":"system"|"user"|"assistant"|"tool","content":"..."}],
 *   "temperature": 0.8, "stream": true?,
 *   "tools": [ { "type":"function", "function": {"name","description","parameters"} } ]? }
 * </pre>
 * 非流式 {@link #chat} 读取 choices[0].message.content 与 message.tool_calls；
 * 流式 {@link #stream} 发送 "stream": true 并按 SSE 逐段读取 choices[0].delta.content，
 * 同时把分片的 delta.tool_calls 按 index 合并成完整的 tool_calls 列表交付
 * （端点忽略 stream 时自动退化为一次性完整回复）。
 */
public final class LlmClient {
	private static final Gson GSON = new Gson();
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	private LlmClient() {
	}

	/** 模型发起的一个工具调用（SSE 分片合并后的完整形态）。 */
	public record ToolCall(String id, String name, String arguments) {
	}

	/** 一条对话消息。 */
	public record Message(String role, String content, String toolCallId, List<ToolCall> toolCalls) {
		public Message {
			toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
		}

		public static Message system(String content) {
			return new Message("system", content, null, List.of());
		}

		public static Message user(String content) {
			return new Message("user", content, null, List.of());
		}

		public static Message assistant(String content) {
			return new Message("assistant", content, null, List.of());
		}

		/** assistant 消息附带它发起的工具调用（OpenAI 要求 tool_calls 原文回传）。 */
		public static Message assistant(String content, List<ToolCall> toolCalls) {
			return new Message("assistant", content, null, toolCalls);
		}

		/** 工具执行结果消息（role "tool"，通过 tool_call_id 关联到模型的一次调用）。 */
		public static Message tool(String toolCallId, String content) {
			return new Message("tool", content, toolCallId, List.of());
		}

		public boolean hasToolCalls() {
			return !toolCalls.isEmpty();
		}
	}

	/**
	 * 一次请求所需的全部参数。tools 为 OpenAI function calling 的工具 schema
	 * 列表（可为 null = 请求体不带 tools 字段，纯聊天路径）。
	 */
	public record Request(String baseUrl, String apiKey, String model, double temperature,
	                      List<Message> messages, int timeoutSeconds, List<JsonObject> tools) {

		/** 不带 tools 的请求（纯聊天）。 */
		public Request(String baseUrl, String apiKey, String model, double temperature,
		               List<Message> messages, int timeoutSeconds) {
			this(baseUrl, apiKey, model, temperature, messages, timeoutSeconds, null);
		}
	}

	/** 请求结果：成功时 ok=true 且 content 为回复文本；失败时 ok=false 且 error 为原因。 */
	public record Response(String content, boolean ok, String error, List<ToolCall> toolCalls) {
		public static Response success(String content, List<ToolCall> toolCalls) {
			return new Response(content, true, null, toolCalls == null ? List.of() : toolCalls);
		}

		public static Response success(String content) {
			return new Response(content, true, null, List.of());
		}

		public static Response failure(String error) {
			return new Response(null, false, error, List.of());
		}
	}

	/**
	 * 流式对话回调。所有回调都在发起 {@link #stream} 的线程（工作线程）上
	 * 按调用顺序串行执行。
	 */
	public interface StreamListener {
		/** 收到一段增量文本（可能为空串）。 */
		void onDelta(String delta);

		/** 流正常结束（收到 [DONE] 或响应体读完），且不会再收到其他回调。 */
		void onDone();

		/** 失败（HTTP 错误/IO 异常/无法解析），error 为原因。 */
		void onError(String error);

		/**
		 * 流中检测到完整的工具调用（SSE 分片已按 index 合并）。在 {@link #onDone()}
		 * 之前恰好回调一次（仅在确实有 tool_calls 时）；调用方以「回调过 onToolCalls
		 * 与否」判断本轮是工具调用还是最终文本回复。
		 */
		default void onToolCalls(List<ToolCall> toolCalls) {
		}
	}

	/**
	 * 发送一次对话请求（非流式，一次性拿到完整回复）。
	 * 任何异常都会以失败响应返回，不会抛出。
	 */
	public static Response chat(Request request) {
		if (request.messages() == null || request.messages().isEmpty()) {
			return Response.failure("消息列表为空");
		}
		try {
			HttpResponse<String> response = HTTP.send(buildRequest(request, false),
					HttpResponse.BodyHandlers.ofString());

			String responseBody = response.body() == null ? "" : response.body();
			if (response.statusCode() >= 200 && response.statusCode() < 300) {
				return parseContent(responseBody);
			}
			String error = extractError(responseBody);
			return Response.failure("HTTP " + response.statusCode() + (error.isEmpty() ? "" : ": " + error));
		} catch (Exception e) {
			return Response.failure(e.getClass().getSimpleName() + ": " + e.getMessage());
		}
	}

	/**
	 * 发送一次流式对话请求（SSE，{@code "stream": true}）。
	 *
	 * 增量文本通过 {@link StreamListener#onDelta} 逐段回调（每段可能只有几个字符，
	 * 调用方应自行节流合并后再上屏）；工具调用经 {@link StreamListener#onToolCalls}
	 * 在 onDone 之前恰好交付一次；流结束或出错后不会再收到更多回调。
	 *
	 * 兼容性：部分 OpenAI 兼容端点（或本地 mock）会忽略 {@code stream} 参数、
	 * 直接返回完整 JSON 响应。此时自动退化为一次性收到
	 * {@code onToolCalls?} + {@code onDelta(完整内容)} + {@code onDone()}，
	 * 调用方无需区分两种模式。
	 */
	public static void stream(Request request, StreamListener listener) {
		if (request.messages() == null || request.messages().isEmpty()) {
			listener.onError("消息列表为空");
			return;
		}
		try {
			HttpResponse<InputStream> response = HTTP.send(buildRequest(request, true),
					HttpResponse.BodyHandlers.ofInputStream());
			int status = response.statusCode();
			if (status < 200 || status >= 300) {
				String error = extractError(readAll(response.body(), 4096));
				listener.onError("HTTP " + status + (error.isEmpty() ? "" : ": " + error));
				return;
			}
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
				// 第一条非空行决定响应模式：SSE（data: 前缀）还是普通 JSON
				String first = firstNonBlankLine(reader);
				if (first == null) {
					listener.onDone(); // 空响应体视为正常结束
					return;
				}
				if (first.startsWith("data:")) {
					readSse(reader, first, listener);
				} else {
					// 端点忽略 stream：把整个响应体合并成一段 JSON 解析
					StringBuilder sb = new StringBuilder(first);
					String line;
					while ((line = reader.readLine()) != null) {
						sb.append('\n').append(line);
					}
					Response parsed = parseContent(sb.toString());
					if (parsed.ok()) {
						if (!parsed.toolCalls().isEmpty()) {
							listener.onToolCalls(parsed.toolCalls());
						}
						if (parsed.content() != null && !parsed.content().isEmpty()) {
							listener.onDelta(parsed.content());
						}
						listener.onDone();
					} else {
						listener.onError(parsed.error());
					}
				}
			}
		} catch (Exception e) {
			listener.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
		}
	}

	/** 读取第一行非空内容；整个流都为空时返回 null。 */
	private static String firstNonBlankLine(BufferedReader reader) throws IOException {
		String line;
		while ((line = reader.readLine()) != null) {
			if (!line.isBlank()) {
				return line;
			}
		}
		return null;
	}

	/** 逐行解析 SSE 流，把增量内容/工具调用回调出去；以 [DONE] 或流结束收尾（回调 onDone）。 */
	private static void readSse(BufferedReader reader, String firstLine, StreamListener listener)
			throws IOException {
		ToolCallAccumulator accumulator = new ToolCallAccumulator();
		emitSseData(firstLine.substring("data:".length()).trim(), listener, accumulator);
		String line;
		while ((line = reader.readLine()) != null) {
			if (line.isBlank()) {
				continue;
			}
			if (line.startsWith("data:")) {
				String data = line.substring("data:".length()).trim();
				if (data.equals("[DONE]")) {
					accumulator.emit(listener);
					listener.onDone();
					return;
				}
				emitSseData(data, listener, accumulator);
			}
			// 其他行（注释/心跳等）忽略
		}
		accumulator.emit(listener);
		listener.onDone();
	}

	/** 解析一条 SSE data 的 JSON，把增量内容/tool_calls 累积并回调出去。 */
	private static void emitSseData(String data, StreamListener listener, ToolCallAccumulator accumulator) {
		if (data.isEmpty() || data.equals("[DONE]")) {
			return;
		}
		try {
			JsonObject root = JsonParser.parseString(data).getAsJsonObject();
			JsonArray choices = root.has("choices") ? root.getAsJsonArray("choices") : null;
			if (choices == null || choices.isEmpty()) {
				return;
			}
			JsonObject first = choices.get(0).getAsJsonObject();
			// 记录 finish_reason：某些端点用它标识“工具调用结束”
			if (first.has("finish_reason") && !first.get("finish_reason").isJsonNull()) {
				accumulator.finishReason = first.get("finish_reason").getAsString();
			}
			String content = null;
			if (first.has("delta") && first.get("delta").isJsonObject()) {
				JsonObject delta = first.getAsJsonObject("delta");
				if (delta.has("tool_calls") && delta.get("tool_calls").isJsonArray()) {
					accumulator.mergeToolCalls(delta.getAsJsonArray("tool_calls"));
				}
				if (delta.has("content") && !delta.get("content").isJsonNull()) {
					content = delta.get("content").getAsString();
				}
			} else if (first.has("message") && first.get("message").isJsonObject()) {
				// 部分实现把完整 message 放进 SSE data
				JsonObject message = first.getAsJsonObject("message");
				if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
					accumulator.mergeToolCalls(message.getAsJsonArray("tool_calls"));
				}
				if (message.has("content") && !message.get("content").isJsonNull()) {
					content = message.get("content").getAsString();
				}
			}
			if (content != null && !content.isEmpty()) {
				listener.onDelta(content);
			}
			// finish_reason == "tool_calls"：工具调用已完整，提前交付（后面仍会 onDone）
			if ("tool_calls".equals(accumulator.finishReason)) {
				accumulator.emit(listener);
			}
		} catch (JsonSyntaxException | IllegalStateException e) {
			// 无法解析的 data 行（心跳/注释等）忽略
		}
	}

	/**
	 * SSE 工具调用分片合并器：按 index 把分片的 id/name/arguments 拼成完整 ToolCall。
	 * 工作线程内串行读写，无需加锁。
	 */
	private static final class ToolCallAccumulator {
		private final Map<Integer, ToolCall> byIndex = new TreeMap<>();
		private boolean emitted = false;
		private String finishReason = null;

		void mergeToolCalls(JsonArray calls) {
			for (JsonElement el : calls) {
				if (!el.isJsonObject()) {
					continue;
				}
				JsonObject obj = el.getAsJsonObject();
				int index = obj.has("index") ? obj.get("index").getAsInt() : 0;
				JsonObject fn = (obj.has("function") && obj.get("function").isJsonObject())
						? obj.getAsJsonObject("function") : null;
				String id = (obj.has("id") && !obj.get("id").isJsonNull())
						? obj.get("id").getAsString() : null;
				String name = (fn != null && fn.has("name") && !fn.get("name").isJsonNull())
						? fn.get("name").getAsString() : null;
				String args = (fn != null && fn.has("arguments") && !fn.get("arguments").isJsonNull())
						? fn.get("arguments").getAsString() : null;

				ToolCall existing = byIndex.get(index);
				if (existing == null) {
					byIndex.put(index, new ToolCall(id == null ? "" : id,
							name == null ? "" : name, args == null ? "" : args));
				} else {
					// arguments 分片是字符串增量，必须拼接；id/name 只在首个分片出现
					byIndex.put(index, new ToolCall(
							id != null ? id : existing.id(),
							name != null ? name : existing.name(),
							args != null ? existing.arguments() + args : existing.arguments()));
				}
			}
		}

		/** 恰好交付一次完整列表（onDone 之前）。 */
		void emit(StreamListener listener) {
			if (emitted || byIndex.isEmpty()) {
				return;
			}
			emitted = true;
			listener.onToolCalls(List.copyOf(byIndex.values()));
		}
	}

	/** 组装一次请求（stream=true 时请求体带 "stream": true）。 */
	private static HttpRequest buildRequest(Request request, boolean stream) {
		JsonObject body = new JsonObject();
		body.addProperty("model", request.model());
		JsonArray messages = new JsonArray();
		for (Message m : request.messages()) {
			JsonObject msg = new JsonObject();
			msg.addProperty("role", m.role());
			switch (m.role()) {
				case "tool" -> {
					msg.addProperty("content", m.content());
					if (m.toolCallId() != null) {
						msg.addProperty("tool_call_id", m.toolCallId());
					}
				}
				case "assistant" -> {
					if (m.hasToolCalls()) {
						JsonArray calls = new JsonArray();
						for (ToolCall c : m.toolCalls()) {
							JsonObject call = new JsonObject();
							call.addProperty("id", c.id());
							call.addProperty("type", "function");
							JsonObject fn = new JsonObject();
							fn.addProperty("name", c.name());
							fn.addProperty("arguments", c.arguments());
							call.add("function", fn);
							calls.add(call);
						}
						msg.add("tool_calls", calls);
						if (m.content() != null && !m.content().isEmpty()) {
							msg.addProperty("content", m.content());
						}
					} else {
						msg.addProperty("content", m.content());
					}
				}
				default -> msg.addProperty("content", m.content());
			}
			messages.add(msg);
		}
		body.add("messages", messages);
		body.addProperty("temperature", request.temperature());
		if (request.tools() != null && !request.tools().isEmpty()) {
			JsonArray tools = new JsonArray();
			for (JsonObject tool : request.tools()) {
				tools.add(tool);
			}
			body.add("tools", tools);
		}
		if (stream) {
			body.addProperty("stream", true);
		}

		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create(normalizeEndpoint(request.baseUrl())))
				.timeout(Duration.ofSeconds(Math.max(5, request.timeoutSeconds())))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));

		String key = request.apiKey() == null ? "" : request.apiKey().trim();
		if (!key.isEmpty()) {
			builder.header("Authorization", "Bearer " + key);
		}
		return builder.build();
	}

	/** 读取输入流（截断到 maxBytes，UTF-8）。 */
	private static String readAll(InputStream in, int maxBytes) throws IOException {
		if (in == null) {
			return "";
		}
		try (InputStream stream = in) {
			byte[] buf = new byte[Math.min(Math.max(1, maxBytes), 8192)];
			int total = 0;
			int n;
			while (total < buf.length && (n = stream.read(buf, total, buf.length - total)) > 0) {
				total += n;
			}
			return new String(buf, 0, total, StandardCharsets.UTF_8);
		}
	}

	/** 解析完整的（非流式）成功响应：content + tool_calls。 */
	private static Response parseContent(String responseBody) {
		try {
			JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
			JsonArray choices = root.has("choices") ? root.getAsJsonArray("choices") : null;
			if (choices != null && !choices.isEmpty()) {
				JsonObject first = choices.get(0).getAsJsonObject();
				JsonObject message = first.has("message") ? first.getAsJsonObject("message") : null;
				String content = null;
				if (message != null && message.has("content") && !message.get("content").isJsonNull()) {
					content = message.get("content").getAsString();
				}
				List<ToolCall> toolCalls = new ArrayList<>();
				if (message != null && message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
					for (JsonElement el : message.getAsJsonArray("tool_calls")) {
						if (!el.isJsonObject()) {
							continue;
						}
						JsonObject call = el.getAsJsonObject();
						JsonObject fn = (call.has("function") && call.get("function").isJsonObject())
								? call.getAsJsonObject("function") : null;
						toolCalls.add(new ToolCall(
								call.has("id") && !call.get("id").isJsonNull() ? call.get("id").getAsString() : "",
								fn != null && fn.has("name") ? fn.get("name").getAsString() : "",
								fn != null && fn.has("arguments") ? fn.get("arguments").getAsString() : "{}"));
					}
				}
				if (!toolCalls.isEmpty()) {
					return Response.success(content, toolCalls);
				}
				if (content != null) {
					return Response.success(content, List.of());
				}
			}
			return Response.failure("响应中没有找到 choices[0].message.content: " + truncate(responseBody));
		} catch (JsonSyntaxException | IllegalStateException | UnsupportedOperationException e) {
			return Response.failure("无法解析响应 JSON: " + truncate(responseBody));
		}
	}

	/** 从失败响应里提取 error.message（如果有）。 */
	private static String extractError(String responseBody) {
		try {
			JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
			if (root.has("error")) {
				JsonElement error = root.get("error");
				if (error.isJsonObject() && error.getAsJsonObject().has("message")) {
					return error.getAsJsonObject().get("message").getAsString();
				}
				return error.toString();
			}
		} catch (JsonSyntaxException | IllegalStateException ignored) {
			// 非 JSON 响应，原样截断返回
		}
		return truncate(responseBody);
	}

	private static String truncate(String text) {
		if (text == null) {
			return "";
		}
		return text.length() > 200 ? text.substring(0, 200) + "…" : text;
	}

	/** 规范化 base URL：去掉末尾斜杠，拼接 /chat/completions。 */
	static String normalizeEndpoint(String baseUrl) {
		String url = baseUrl == null ? "" : baseUrl.trim();
		while (url.endsWith("/")) {
			url = url.substring(0, url.length() - 1);
		}
		if (url.endsWith("/chat/completions")) {
			return url;
		}
		return url + "/chat/completions";
	}

	/** 辅助方法：把消息列表裁剪到最近 n 条（保留首条 system 提示）。 */
	public static List<Message> trimHistory(List<Message> messages, int maxMessages) {
		if (messages == null || messages.isEmpty()) {
			return new ArrayList<>();
		}
		List<Message> result = new ArrayList<>();
		Message first = messages.get(0);
		boolean keepFirst = "system".equals(first.role());
		int budget = Math.max(2, maxMessages);
		if (keepFirst) {
			result.add(first);
			budget = Math.max(1, budget - 1);
		}
		int from = Math.max(keepFirst ? 1 : 0, messages.size() - budget);
		for (int i = from; i < messages.size(); i++) {
			result.add(messages.get(i));
		}
		return result;
	}
}