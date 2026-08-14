package com.swaydy.opencraft.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * 响应读取 choices[0].message.content。
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
	 * 发送一次对话请求。
	 * 任何异常都会以失败响应返回，不会抛出。
	 */
	public static Response chat(Request request) {
		if (request.messages() == null || request.messages().isEmpty()) {
			return Response.failure("消息列表为空");
		}
		String endpoint = normalizeEndpoint(request.baseUrl());
		try {
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

			HttpRequest.Builder builder = HttpRequest.newBuilder()
					.uri(URI.create(endpoint))
					.timeout(Duration.ofSeconds(Math.max(5, request.timeoutSeconds())))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));

			String key = request.apiKey() == null ? "" : request.apiKey().trim();
			if (!key.isEmpty()) {
				builder.header("Authorization", "Bearer " + key);
			}

			HttpResponse<String> response = HTTP.send(builder.build(),
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
