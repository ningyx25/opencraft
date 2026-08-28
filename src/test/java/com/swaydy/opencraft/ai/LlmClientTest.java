package com.swaydy.opencraft.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新 {@link LlmClient}（基于 openai-java，dsh-llm 风格词汇）的纯 Java 单测：
 * 非流式 chat、SSE 流式 chunk 协议（text/reasoning/tool-call delta、block-end 组装、
 * finish reason、usage、EMPTY_RESPONSE、MALFORMED_RESPONSE）、HTTP 错误映射、
 * 看门狗 STALLED、非流式退化路径、API key 校验与错误码纯函数。
 * 走本地 {@link HttpServer} mock，无 Minecraft 运行时。
 */
class LlmClientTest {
	private HttpServer server;
	private int port;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.start();
		port = server.getAddress().getPort();
	}

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	/** 给 /v1/chat/completions 挂一个固定响应体的处理器（200，SSE content-type）。 */
	private void stub(String body) {
		stub(200, body, "text/event-stream");
	}

	private void stub(int status, String body, String contentType) {
		server.createContext("/v1/chat/completions", exchange -> {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", contentType);
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
	}

	/** 构造一个访问本地 mock 的 Request。 */
	private LlmClient.Request request(int timeoutSeconds) {
		return new LlmClient.Request("http://127.0.0.1:" + port + "/v1", "test-key", "mock-model",
				null, List.of(LlmClient.Message.user("hi")), timeoutSeconds);
	}

	/** 同步跑完一次流，收集全部 chunk。 */
	private List<LlmClient.Chunk> collect(LlmClient.Request req) {
		List<LlmClient.Chunk> chunks = new ArrayList<>();
		LlmClient.stream(req, chunks::add);
		return chunks;
	}

	private LlmClient.Finish lastFinish(List<LlmClient.Chunk> chunks) {
		LlmClient.Chunk last = chunks.get(chunks.size() - 1);
		assertTrue(last instanceof LlmClient.Finish, "最后一个 chunk 应为 Finish: " + last);
		return (LlmClient.Finish) last;
	}

	// ------------------------------------------------------------------
	// 非流式 chat
	// ------------------------------------------------------------------

	@Test
	void chatParsesContentAndToolCalls() {
		String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"好的\","
				+ "\"tool_calls\":[{\"id\":\"call_xyz\",\"type\":\"function\","
				+ "\"function\":{\"name\":\"craft\",\"arguments\":\"{\\\"item\\\":\\\"minecraft:stick\\\"}\"}}]},"
				+ "\"finish_reason\":\"tool_calls\"}]}";
		stub(json);

		LlmClient.ChatResult result = LlmClient.chat(request(15));
		assertTrue(result.ok(), "应成功: " + result);
		assertEquals(LlmClient.FinishKind.TOOL_CALLS, result.reason().kind());
		assertEquals("好的", result.text());
		List<LlmClient.ToolCallBlock> calls = new ArrayList<>();
		for (LlmClient.Block b : result.content()) {
			if (b instanceof LlmClient.ToolCallBlock t) {
				calls.add(t);
			}
		}
		assertEquals(1, calls.size());
		assertEquals("call_xyz", calls.get(0).id());
		assertEquals("craft", calls.get(0).name());
		assertEquals("{\"item\":\"minecraft:stick\"}", calls.get(0).arguments());
	}

	@Test
	void chatMapsRateLimitToCode() {
		String json = "{\"error\":{\"message\":\"Rate limit reached\","
				+ "\"type\":\"rate_limit_error\",\"code\":\"rate_limit_exceeded\"}}";
		stub(429, json, "application/json");

		LlmClient.ChatResult result = LlmClient.chat(request(15));
		assertFalse(result.ok());
		assertEquals(LlmClient.Codes.RATE_LIMIT, result.failure().code());
		assertEquals(429, result.failure().status());
	}

	@Test
	void chatEmptyMessagesFailsFast() {
		LlmClient.Request empty = new LlmClient.Request("http://127.0.0.1:" + port + "/v1", "k",
				"m", null, List.of(), 15);
		LlmClient.ChatResult result = LlmClient.chat(empty);
		assertFalse(result.ok());
		assertEquals(LlmClient.Codes.EMPTY_RESPONSE, result.failure().code());
	}

	// ------------------------------------------------------------------
	// SSE 流式 chunk 协议
	// ------------------------------------------------------------------

	@Test
	void sseTextDeltasAssembleBlock() {
		String sse = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"你好\"}}]}\n\n"
				+ "data: {\"choices\":[{\"delta\":{\"content\":\"，世界\"}}]}\n\n"
				+ "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
				+ "data: [DONE]\n\n";
		stub(sse);

		List<LlmClient.Chunk> chunks = collect(request(15));
		assertEquals(5, chunks.size());
		assertTrue(chunks.get(0) instanceof LlmClient.BlockStart bs
				&& bs.blockType() == LlmClient.BlockType.TEXT);
		assertEquals("你好", ((LlmClient.TextDelta) chunks.get(1)).text());
		assertEquals("，世界", ((LlmClient.TextDelta) chunks.get(2)).text());
		assertTrue(chunks.get(3) instanceof LlmClient.BlockEnd be
				&& be.block().equals(new LlmClient.TextBlock("你好，世界")));
		LlmClient.Finish f = lastFinish(chunks);
		assertTrue(f.ok());
		assertEquals(LlmClient.FinishKind.STOP, f.reason().kind());
	}

	@Test
	void sseToolCallFragmentsMergeByIndex() {
		String sse = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":null,"
				+ "\"tool_calls\":[{\"index\":0,\"id\":\"call_abc\",\"type\":\"function\","
				+ "\"function\":{\"name\":\"goto\",\"arguments\":\"\"}}]}}]}\n\n"
				+ "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
				+ "\"function\":{\"arguments\":\"{\\\"x\\\":10,\\\"y\\\":64\"}}]}}]}\n\n"
				+ "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
				+ "\"function\":{\"arguments\":\",\\\"z\\\":-8}\"}}]}}]}\n\n"
				+ "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
				+ "data: [DONE]\n\n";
		stub(sse);

		List<LlmClient.Chunk> chunks = collect(request(15));
		assertTrue(chunks.get(0) instanceof LlmClient.BlockStart bs
				&& bs.blockType() == LlmClient.BlockType.TOOL_CALL);
		// 原始分片：argumentsDelta 为增量字符串，id/name 只在首分片
		LlmClient.ToolCallDelta first = (LlmClient.ToolCallDelta) chunks.get(1);
		assertEquals("call_abc", first.id());
		assertEquals("goto", first.name());
		assertEquals("", first.argumentsDelta());
		LlmClient.ToolCallDelta second = (LlmClient.ToolCallDelta) chunks.get(2);
		assertEquals("{\"x\":10,\"y\":64", second.argumentsDelta());
		// 块结束携带合并后的完整 ToolCallBlock
		LlmClient.BlockEnd end = (LlmClient.BlockEnd) chunks.get(4);
		assertEquals(new LlmClient.ToolCallBlock("call_abc", "goto", "{\"x\":10,\"y\":64,\"z\":-8}"),
				end.block());
		LlmClient.Finish f = lastFinish(chunks);
		assertTrue(f.ok());
		assertEquals(LlmClient.FinishKind.TOOL_CALLS, f.reason().kind());
	}

	@Test
	void sseReasoningInterleavesWithText() {
		String sse = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\","
				+ "\"reasoning_content\":\"第一步先\",\"content\":null}}]}\n\n"
				+ "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"确认位置\","
				+ "\"content\":\"我看看\"}}]}\n\n"
				+ "data: {\"choices\":[{\"delta\":{\"content\":\"周围\"}}]}\n\n"
				+ "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
				+ "data: [DONE]\n\n";
		stub(sse);

		List<LlmClient.Chunk> chunks = collect(request(15));
		LlmClient.BlockStart r = (LlmClient.BlockStart) chunks.get(0);
		assertEquals(LlmClient.BlockType.REASONING, r.blockType());
		assertEquals("第一步先", ((LlmClient.ReasoningDelta) chunks.get(1)).text());
		assertEquals("确认位置", ((LlmClient.ReasoningDelta) chunks.get(2)).text());
		LlmClient.BlockStart t = (LlmClient.BlockStart) chunks.get(3);
		assertEquals(LlmClient.BlockType.TEXT, t.blockType());
		assertEquals("我看看", ((LlmClient.TextDelta) chunks.get(4)).text());
		// 块结束按打开顺序：先 reasoning 后 text
		LlmClient.BlockEnd re = (LlmClient.BlockEnd) chunks.get(6);
		assertEquals(new LlmClient.ReasoningBlock("第一步先确认位置"), re.block());
		LlmClient.BlockEnd te = (LlmClient.BlockEnd) chunks.get(7);
		assertEquals(new LlmClient.TextBlock("我看看周围"), te.block());
		LlmClient.Finish f = lastFinish(chunks);
		assertTrue(f.ok());
	}

	@Test
	void sseMapsLengthToMaxTokens() {
		String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"太长\"}}]}\n\n"
				+ "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n"
				+ "data: [DONE]\n\n";
		stub(sse);
		assertEquals(LlmClient.FinishKind.MAX_TOKENS, lastFinish(collect(request(15))).reason().kind());
	}

	@Test
	void sseMapsUnknownFinishReasonToErrorCode() {
		String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"被过滤\"}}]}\n\n"
				+ "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"content_filter\"}]}\n\n"
				+ "data: [DONE]\n\n";
		stub(sse);
		LlmClient.Finish f = lastFinish(collect(request(15)));
		assertEquals(LlmClient.FinishKind.ERROR, f.reason().kind());
		assertEquals("CONTENT_FILTER", f.reason().code());
		assertNotNull(f.failure());
	}

	@Test
	void sseReportsUsageWhenProvided() {
		String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
				+ "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],"
				+ "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,"
				+ "\"prompt_tokens_details\":{\"cached_tokens\":4},"
				+ "\"completion_tokens_details\":{\"reasoning_tokens\":2}}}\n\n"
				+ "data: [DONE]\n\n";
		stub(sse);
		List<LlmClient.Chunk> chunks = collect(request(15));
		LlmClient.Chunk usageChunk = chunks.get(chunks.size() - 2);
		assertTrue(usageChunk instanceof LlmClient.Usage);
		LlmClient.Usage u = (LlmClient.Usage) usageChunk;
		// 互斥计数：input = prompt(10) - cacheRead(4)
		assertEquals(6, u.inputTokens());
		assertEquals(5, u.outputTokens());
		assertEquals(4, u.cacheReadTokens());
		assertEquals(2, u.reasoningTokens());
	}

	@Test
	void sseMalformedPayloadFailsWithMalformedResponse() {
		String sse = "data: {not json\n\n"
				+ "data: [DONE]\n\n";
		stub(sse);
		LlmClient.Finish f = lastFinish(collect(request(15)));
		assertEquals(LlmClient.FinishKind.ERROR, f.reason().kind());
		assertEquals(LlmClient.Codes.MALFORMED_RESPONSE, f.failure().code());
	}

	@Test
	void sseHttp500FailsWithServerCode() {
		String json = "{\"error\":{\"message\":\"boom\"}}";
		stub(500, json, "application/json");
		LlmClient.Finish f = lastFinish(collect(request(15)));
		assertEquals(LlmClient.FinishKind.ERROR, f.reason().kind());
		assertEquals(LlmClient.Codes.SERVER, f.failure().code());
		assertEquals(500, f.failure().status());
	}

	@Test
	void sseEmptyCompletionIsEmptyResponse() {
		// 端点忽略 stream、返回完整 JSON，且内容是空完成 → EMPTY_RESPONSE
		String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\"},"
				+ "\"finish_reason\":\"stop\"}]}";
		stub(json);
		LlmClient.Finish f = lastFinish(collect(request(15)));
		assertEquals(LlmClient.FinishKind.ERROR, f.reason().kind());
		assertEquals(LlmClient.Codes.EMPTY_RESPONSE, f.failure().code());
	}

	// ------------------------------------------------------------------
	// 非流式退化路径（端点忽略 stream）
	// ------------------------------------------------------------------

	@Test
	void sseIgnoredEndpointFallsBackToChat() {
		// 端点忽略 stream、直接返回完整 JSON（含文本 + 工具调用）
		String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"好的\","
				+ "\"tool_calls\":[{\"id\":\"call_xyz\",\"type\":\"function\","
				+ "\"function\":{\"name\":\"craft\",\"arguments\":\"{\\\"item\\\":\\\"minecraft:stick\\\"}\"}}]},"
				+ "\"finish_reason\":\"tool_calls\"}]}";
		stub(json);
		List<LlmClient.Chunk> chunks = collect(request(15));
		// 合成：text 块 + tool-call 块 + Finish(TOOL_CALLS)
		LlmClient.BlockStart t = (LlmClient.BlockStart) chunks.get(0);
		assertEquals(LlmClient.BlockType.TEXT, t.blockType());
		assertEquals("好的", ((LlmClient.TextDelta) chunks.get(1)).text());
		LlmClient.BlockStart tc = (LlmClient.BlockStart) chunks.get(3);
		assertEquals(LlmClient.BlockType.TOOL_CALL, tc.blockType());
		LlmClient.BlockEnd end = (LlmClient.BlockEnd) chunks.get(5);
		assertEquals(new LlmClient.ToolCallBlock("call_xyz", "craft", "{\"item\":\"minecraft:stick\"}"),
				end.block());
		LlmClient.Finish f = lastFinish(chunks);
		assertTrue(f.ok());
		assertEquals(LlmClient.FinishKind.TOOL_CALLS, f.reason().kind());
	}

	// ------------------------------------------------------------------
	// 看门狗（连接后不吐数据 → STALLED）
	// ------------------------------------------------------------------

	@Test
	void stalledStreamFailsWithStalledCode() throws Exception {
		// 发 200 头 + 一个未写完的 SSE data 行，然后静默：客户端收完头阻塞在读上 → 看门狗超时 → close → STALLED。
		// 两个关键点（JDK 25 实测踩坑）：
		// 1. 必须写出真实字节再 flush——新版 JDK 的 HttpServer 不再在 sendResponseHeaders 时把响应头
		//    刷到网络，纯静默会让客户端一直等不到头，而看门狗（建流后才起表）根本没机会启动，
		//    最终被 SDK 读超时以 TIMEOUT 收场（CI 上本测试 flaky 的根因）；
		// 2. 只写半个事件（无换行收尾）——符合 SSE 规范的解析器不会把它交付成 chunk，
		//    不会重置看门狗的 idle 计时，"服务端中断"场景成立。
		server.createContext("/v1/chat/completions", exchange -> {
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
			exchange.sendResponseHeaders(200, 0);
			OutputStream body = exchange.getResponseBody();
			body.write("data:".getBytes(StandardCharsets.UTF_8));
			body.flush();
			try {
				Thread.sleep(30_000);
			} catch (InterruptedException ignored) {
			}
		});

		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<LlmClient.Finish> finish = new AtomicReference<>();
		LlmClient.stream(request(5), chunk -> {
			if (chunk instanceof LlmClient.Finish f) {
				finish.set(f);
				done.countDown();
			}
		});
		assertTrue(done.await(20, TimeUnit.SECONDS), "看门狗应在超时前触发");
		assertNotNull(finish.get());
		assertEquals(LlmClient.FinishKind.ERROR, finish.get().reason().kind());
		assertEquals(LlmClient.Codes.STALLED, finish.get().failure().code());
		assertTrue(finish.get().failure().message().contains("request-stalled"));
	}

	// ------------------------------------------------------------------
	// 纯函数：API key、错误码、上下文/配额措辞、baseUrl
	// ------------------------------------------------------------------

	@Test
	void checkApiKeyTrimsAndValidates() {
		assertTrue(LlmClient.checkApiKey("  sk-123  ").ok());
		assertEquals("sk-123", LlmClient.checkApiKey("  sk-123  ").value());
		assertEquals("empty", LlmClient.checkApiKey("").reason());
		assertEquals("empty", LlmClient.checkApiKey("   ").reason());
		assertEquals("empty", LlmClient.checkApiKey(null).reason());
		assertEquals("illegalCharacters", LlmClient.checkApiKey("sk\nkey").reason());
		assertEquals("illegalCharacters", LlmClient.checkApiKey("密钥").reason());
	}

	@Test
	void httpErrorCodeMapsStatuses() {
		assertEquals(LlmClient.Codes.AUTH, LlmClient.httpErrorCode(401, "unauthorized"));
		assertEquals(LlmClient.Codes.AUTH, LlmClient.httpErrorCode(403, "forbidden"));
		assertEquals(LlmClient.Codes.RATE_LIMIT, LlmClient.httpErrorCode(429, "too many requests"));
		assertEquals(LlmClient.Codes.SERVER, LlmClient.httpErrorCode(500, "boom"));
		assertEquals(LlmClient.Codes.SERVER, LlmClient.httpErrorCode(503, "unavailable"));
		assertEquals(LlmClient.Codes.INVALID_REQUEST, LlmClient.httpErrorCode(400, "bad params"));
		assertEquals(LlmClient.Codes.INVALID_REQUEST, LlmClient.httpErrorCode(413, "too large"));
		assertEquals("HTTP_418", LlmClient.httpErrorCode(418, "teapot"));
	}

	@Test
	void contextWindowAndQuotaWording() {
		assertTrue(LlmClient.isContextWindowExceeded("This model's maximum context length is 8192 tokens"));
		assertTrue(LlmClient.isContextWindowExceeded("the request is too large for the model context window"));
		assertFalse(LlmClient.isContextWindowExceeded("bad request"));
		assertTrue(LlmClient.isQuotaExceeded("You exceeded your current quota, please check your plan"));
		assertTrue(LlmClient.isQuotaExceeded("insufficient_quota"));
		assertFalse(LlmClient.isQuotaExceeded("rate limit exceeded"));
	}

	@Test
	void normalizeBaseUrlVariants() {
		assertEquals("https://api.openai.com/v1", LlmClient.normalizeBaseUrl("https://api.openai.com/v1/"));
		assertEquals("https://api.openai.com/v1", LlmClient.normalizeBaseUrl("https://api.openai.com/v1/chat/completions"));
		assertEquals("http://127.0.0.1:8080", LlmClient.normalizeBaseUrl("http://127.0.0.1:8080"));
		assertEquals("", LlmClient.normalizeBaseUrl(null));
	}

	/** 请求侧：带工具 schema 的 Request 能正常序列化（tools 真正上线）并返回文本回复。 */
	@Test
	void requestWithToolsSerializes() throws Exception {
		String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"完成\"},"
				+ "\"finish_reason\":\"stop\"}]}";
		java.util.concurrent.atomic.AtomicReference<String> bodyRef = new java.util.concurrent.atomic.AtomicReference<>();
		server.createContext("/v1/chat/completions", exchange -> {
			bodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
		com.google.gson.JsonObject parameters = new com.google.gson.JsonObject();
		parameters.addProperty("type", "object");
		parameters.addProperty("required", "[\"target\"]");
		LlmClient.Request req = new LlmClient.Request(
				"http://127.0.0.1:" + port + "/v1", "test-key", "mock-model", "system提示",
				List.of(LlmClient.Message.user("帮我")),
				List.of(new LlmClient.ToolSchema("my_tool", "does something", parameters)),
				0.7, 64, List.of("END"), 15);
		LlmClient.ChatResult result = LlmClient.chat(req);
		assertTrue(result.ok(), "应成功: " + result);
		assertEquals("完成", result.text());
		// 请求体应包含 tools schema、system 消息、温度与 max_tokens（可选字段按语义上线）
		String body = bodyRef.get();
		assertNotNull(body, "应捕获请求体");
		assertTrue(body.contains("\"tools\""), "请求体应含 tools: " + body);
		assertTrue(body.contains("\"my_tool\""), "请求体应含工具名: " + body);
		assertTrue(body.contains("\"type\":\"object\""), "请求体应含 parameters: " + body);
		assertTrue(body.contains("\"system提示\""), "请求体应含 system: " + body);
		assertTrue(body.contains("\"max_completion_tokens\":64"), "请求体应含 max_tokens: " + body);
		assertTrue(body.contains("\"temperature\":0.7"), "请求体应含 temperature: " + body);
		assertTrue(body.contains("\"END\""), "请求体应含 stop: " + body);
	}
}
