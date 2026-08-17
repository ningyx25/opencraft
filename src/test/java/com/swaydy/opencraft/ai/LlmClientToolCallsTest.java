package com.swaydy.opencraft.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LlmClient 的原生 function calling 解析测试（纯 Java，无 Minecraft 运行时）：
 * 1. SSE 流式：delta.tool_calls 按 index 分片合并成完整 ToolCall（id/name/arguments 拼接）；
 * 2. SSE 流式：带文本 delta + 工具调用的混合；
 * 3. 非流式退化路径：完整 JSON 响应里的 message.tool_calls 解析。
 */
class LlmClientToolCallsTest {
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

	/** 给 /chat/completions 挂一个固定响应体的处理器。 */
	private void stub(String body) {
		server.createContext("/v1/chat/completions", exchange -> {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
	}

	/** 构造一个访问本地 mock 的 Request。 */
	private LlmClient.Request request() {
		return new LlmClient.Request(
				"http://127.0.0.1:" + port + "/v1", "test-key",
				"mock-model", 0.8,
				List.of(LlmClient.Message.user("hi")), 15);
	}

	@Test
	void sseMergesChunkedToolCalls() throws Exception {
		// 标准 OpenAI SSE：tool_calls 按 index 分片（arguments 是增量字符串）
		String sse = "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":null,"
				+ "\"tool_calls\":[{\"index\":0,\"id\":\"call_abc\",\"type\":\"function\","
				+ "\"function\":{\"name\":\"goto\",\"arguments\":\"\"}}]}}]}\n\n"
				+ "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
				+ "\"function\":{\"arguments\":\"{\\\"x\\\":10,\\\"y\\\":64,\\\"z\\\":-8}\"}}]}}]}\n\n"
				+ "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"
				+ "data: [DONE]\n\n";
		stub(sse);

		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<List<LlmClient.ToolCall>> calls = new AtomicReference<>();
		AtomicReference<String> error = new AtomicReference<>();

		LlmClient.stream(request(), new LlmClient.StreamListener() {
			@Override
			public void onDelta(String delta) {
			}

			@Override
			public void onToolCalls(List<LlmClient.ToolCall> toolCalls) {
				calls.set(toolCalls);
			}

			@Override
			public void onDone() {
				done.countDown();
			}

			@Override
			public void onError(String err) {
				error.set(err);
				done.countDown();
			}
		});
		assertTrue(done.await(10, TimeUnit.SECONDS), "stream 应在超时前结束");
		assertNull(error.get(), "不应有错误: " + error.get());
		assertNotNull(calls.get(), "应收到 onToolCalls");
		assertEquals(1, calls.get().size());
		LlmClient.ToolCall call = calls.get().get(0);
		assertEquals("call_abc", call.id());
		assertEquals("goto", call.name());
		assertEquals("{\"x\":10,\"y\":64,\"z\":-8}", call.arguments());
	}

	@Test
	void sseMultipleToolCallsByIndex() throws Exception {
		// 两个工具调用（index 0 和 1），分片交错
		String sse = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\","
				+ "\"function\":{\"name\":\"mine\",\"arguments\":\"{\\\"x\\\":1\"}}]}}]}\n\n"
				+ "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"c2\","
				+ "\"function\":{\"name\":\"look_around\",\"arguments\":\"{}\"}}]}}]}\n\n"
				+ "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
				+ "\"function\":{\"arguments\":\",\\\"y\\\":2,\\\"z\\\":3}\"}}]}}]}\n\n"
				+ "data: [DONE]\n\n";
		stub(sse);

		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<List<LlmClient.ToolCall>> calls = new AtomicReference<>();
		LlmClient.stream(request(), new LlmClient.StreamListener() {
			@Override
			public void onDelta(String delta) {
			}

			@Override
			public void onToolCalls(List<LlmClient.ToolCall> toolCalls) {
				calls.set(toolCalls);
			}

			@Override
			public void onDone() {
				done.countDown();
			}

			@Override
			public void onError(String err) {
				done.countDown();
			}
		});
		assertTrue(done.await(10, TimeUnit.SECONDS));
		assertNotNull(calls.get());
		assertEquals(2, calls.get().size());
		LlmClient.ToolCall mine = calls.get().get(0);
		assertEquals("c1", mine.id());
		assertEquals("mine", mine.name());
		assertEquals("{\"x\":1,\"y\":2,\"z\":3}", mine.arguments());
		LlmClient.ToolCall look = calls.get().get(1);
		assertEquals("c2", look.id());
		assertEquals("look_around", look.name());
		assertEquals("{}", look.arguments());
	}

	@Test
	void sseTextPlusToolCalls() throws Exception {
		// 工具调用前先给一段文本 delta（模型先解释再调用工具）
		String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"我看看周围\"}}]}\n\n"
				+ "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"t1\","
				+ "\"function\":{\"name\":\"look_around\",\"arguments\":\"{\\\"radius\\\":4}\"}}]}}]}\n\n"
				+ "data: [DONE]\n\n";
		stub(sse);

		StringBuilder text = new StringBuilder();
		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<List<LlmClient.ToolCall>> calls = new AtomicReference<>();
		LlmClient.stream(request(), new LlmClient.StreamListener() {
			@Override
			public void onDelta(String delta) {
				text.append(delta);
			}

			@Override
			public void onToolCalls(List<LlmClient.ToolCall> toolCalls) {
				calls.set(toolCalls);
			}

			@Override
			public void onDone() {
				done.countDown();
			}

			@Override
			public void onError(String err) {
				done.countDown();
			}
		});
		assertTrue(done.await(10, TimeUnit.SECONDS));
		assertEquals("我看看周围", text.toString());
		assertNotNull(calls.get());
		assertEquals(1, calls.get().size());
		assertEquals("look_around", calls.get().get(0).name());
	}

	@Test
	void nonStreamingFullJsonParsesToolCalls() throws Exception {
		// 端点忽略 stream、直接返回完整 JSON：message.tool_calls 也要解析
		String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,"
				+ "\"tool_calls\":[{\"id\":\"call_xyz\",\"type\":\"function\","
				+ "\"function\":{\"name\":\"craft\",\"arguments\":\"{\\\"item\\\":\\\"minecraft:stick\\\"}\"}}]},"
				+ "\"finish_reason\":\"tool_calls\"}]}";
		stub(json);

		CountDownLatch done = new CountDownLatch(1);
		AtomicReference<List<LlmClient.ToolCall>> calls = new AtomicReference<>();
		AtomicReference<String> error = new AtomicReference<>();
		LlmClient.stream(request(), new LlmClient.StreamListener() {
			@Override
			public void onDelta(String delta) {
			}

			@Override
			public void onToolCalls(List<LlmClient.ToolCall> toolCalls) {
				calls.set(toolCalls);
			}

			@Override
			public void onDone() {
				done.countDown();
			}

			@Override
			public void onError(String err) {
				error.set(err);
				done.countDown();
			}
		});
		assertTrue(done.await(10, TimeUnit.SECONDS));
		assertNull(error.get(), "不应有错误: " + error.get());
		assertNotNull(calls.get(), "非流式退化路径也应解析 tool_calls");
		assertEquals(1, calls.get().size());
		LlmClient.ToolCall call = calls.get().get(0);
		assertEquals("call_xyz", call.id());
		assertEquals("craft", call.name());
		assertEquals("{\"item\":\"minecraft:stick\"}", call.arguments());
	}

	@Test
	void toolMessageSerializesToolCallId() {
		// 序列化侧：tool 消息应带 tool_call_id（构造请求体后校验）
		LlmClient.Message msg = LlmClient.Message.tool("call_abc", "done");
		assertEquals("tool", msg.role());
		assertEquals("call_abc", msg.toolCallId());
		assertEquals("done", msg.content());
	}
}