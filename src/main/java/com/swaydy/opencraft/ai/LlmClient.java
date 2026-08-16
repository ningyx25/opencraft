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

/**
 * 与 OpenAI 兼容的 Chat Completions 接口通信的纯 Java 客户端。
 *
 * 本类不依赖任何 Minecraft 类，便于独立测试：
 * 只需要一个实现 POST {baseUrl}/chat/completions 的服务器即可。
 *
 * 请求体：
 * <pre>
 * { "model": "...", "messages": [{"role":"system"|"user"|"assistant","content":"..."}], "temperature": 0.8 }
 * </pre>
 * 非流式 {@link #chat} 读取 choices[0].message.content；
 * 流式 {@link #stream} 发送 "stream": true 并按 SSE 逐段读取 choices[0].delta.content
 * （端点忽略 stream 时自动退化为一次性完整回复）。
 */
public final class LlmClient {
	private static final Gson GSON = new Gson();
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	private LlmClient() {
	}

	/** 一条对话消息。 */
	public record Message(String role, String content) {
		public static Message system(String content) {
			return new Message("system", content);
		}

		public static Message user(String content) {
			return new Message("user", content);
		}

		public static Message assistant(String content) {
			return new Message("assistant", content);
		}
	}

	/** 一次请求所需的全部参数。 */
	public record Request(String baseUrl, String apiKey, String model, double temperature,
	                      List<Message> messages, int timeoutSeconds) {
	}

	/** 请求结果：成功时 ok=true 且 content 为回复文本；失败时 ok=false 且 error 为原因。 */
	public record Response(String content, boolean ok, String error) {
		public static Response success(String content) {
			return new Response(content, true, null);
		}

		public static Response failure(String error) {
			return new Response(null, false, error);
		}
	}

	/**
	 * 流式对话回调。所有回调都在发起 {@link #stream} 的线程（工作线程）上
	 * 按调用顺序串行执行：先收到若干次 {@link #onDelta}，最后恰好收到一次
	 * {@link #onDone} 或 {@link #onError}（二选一，不会再收到其他回调）。
	 */
	public interface StreamListener {
		/** 收到一段增量文本（可能为空串）。 */
		void onDelta(String delta);

		/** 流正常结束（收到 [DONE] 或响应体读完）。 */
		void onDone();

		/** 失败（HTTP 错误/IO 异常/无法解析），error 为原因。 */
		void onError(String error);
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
	 * 调用方应自行节流合并后再上屏）；流结束或出错后不会再收到更多回调。
	 *
	 * 兼容性：部分 OpenAI 兼容端点（或本地 mock）会忽略 {@code stream} 参数、
	 * 直接返回完整 JSON 响应。此时自动退化为一次性收到
	 * {@code onDelta(完整内容)} + {@code onDone()}，调用方无需区分两种模式。
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
						listener.onDelta(parsed.content());
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

	/** 逐行解析 SSE 流，把增量内容回调出去；以 [DONE] 或流结束收尾（回调 onDone）。 */
	private static void readSse(BufferedReader reader, String firstLine, StreamListener listener)
			throws IOException {
		emitSseData(firstLine.substring("data:".length()).trim(), listener);
		String line;
		while ((line = reader.readLine()) != null) {
			if (line.isBlank()) {
				continue;
			}
			if (line.startsWith("data:")) {
				String data = line.substring("data:".length()).trim();
				if (data.equals("[DONE]")) {
					listener.onDone();
					return;
				}
				emitSseData(data, listener);
			}
			// 其他行（注释/心跳等）忽略
		}
		listener.onDone();
	}

	/** 解析一条 SSE data 的 JSON，把增量内容（若有）回调出去。 */
	private static void emitSseData(String data, StreamListener listener) {
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
			String content = null;
			if (first.has("delta") && first.get("delta").isJsonObject()) {
				JsonObject delta = first.getAsJsonObject("delta");
				if (delta.has("content") && !delta.get("content").isJsonNull()) {
					content = delta.get("content").getAsString();
				}
			} else if (first.has("message") && first.get("message").isJsonObject()) {
				// 部分实现把完整 message 放进 SSE data
				JsonObject message = first.getAsJsonObject("message");
				if (message.has("content") && !message.get("content").isJsonNull()) {
					content = message.get("content").getAsString();
				}
			}
			if (content != null && !content.isEmpty()) {
				listener.onDelta(content);
			}
		} catch (JsonSyntaxException | IllegalStateException e) {
			// 无法解析的 data 行（心跳/注释等）忽略
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
			msg.addProperty("content", m.content());
			messages.add(msg);
		}
		body.add("messages", messages);
		body.addProperty("temperature", request.temperature());
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

	/** 从成功响应里解析 choices[0].message.content。 */
	private static Response parseContent(String responseBody) {
		try {
			JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
			JsonArray choices = root.has("choices") ? root.getAsJsonArray("choices") : null;
			if (choices != null && !choices.isEmpty()) {
				JsonObject first = choices.get(0).getAsJsonObject();
				JsonObject message = first.has("message") ? first.getAsJsonObject("message") : null;
				if (message != null && message.has("content")) {
					JsonElement content = message.get("content");
					if (!content.isJsonNull()) {
						return Response.success(content.getAsString());
					}
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
