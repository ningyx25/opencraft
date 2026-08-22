package com.swaydy.opencraft.logging;

import com.swaydy.opencraft.ai.LlmClient;
import com.swaydy.opencraft.ai.LlmClient.Block;
import com.swaydy.opencraft.ai.LlmClient.BlockEnd;
import com.swaydy.opencraft.ai.LlmClient.Chunk;
import com.swaydy.opencraft.ai.LlmClient.ChunkSink;
import com.swaydy.opencraft.ai.LlmClient.Finish;
import com.swaydy.opencraft.ai.LlmClient.FinishReason;
import com.swaydy.opencraft.ai.LlmClient.LlmFailure;
import com.swaydy.opencraft.ai.LlmClient.Message;
import com.swaydy.opencraft.ai.LlmClient.ReasoningBlock;
import com.swaydy.opencraft.ai.LlmClient.Request;
import com.swaydy.opencraft.ai.LlmClient.TextBlock;
import com.swaydy.opencraft.ai.LlmClient.ToolCallBlock;
import com.swaydy.opencraft.ai.LlmClient.ToolResultBlock;
import com.swaydy.opencraft.ai.LlmClient.ToolSchema;
import com.swaydy.opencraft.logging.DebugLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LLM 请求/回复的调试日志工具（debug 分类 "llm"）。
 *
 * <p>与 {@link LlmClient} 解耦：所有格式化、截断、DebugLog 调用集中在此类，
 * LlmClient 只在调用前后委托这里，不直接接触日志细节。
 *
 * <p>两个对外入口：
 * <ul>
 *   <li>{@link #logRequest(Request)} — 记录即将发出的请求；</li>
 *   <li>{@link #wrapSink(ChunkSink)} — 包装流式 sink，透明收集完整块并在 Finish 时记录回复；</li>
 *   <li>{@link #logResponse(List, FinishReason, LlmFailure)} — 非流式调用拿到结果后直接记录。</li>
 * </ul>
 */
public final class LlmDebugLogger {

	/** 单条 system / 文本块最大记录字符数。 */
	private static final int MAX_SYSTEM = 500;
	private static final int MAX_TEXT = 300;
	private static final int MAX_REPLY_TEXT = 500;
	private static final int MAX_REASONING = 300;
	private static final int MAX_ARGS = 200;
	private static final int MAX_TOOL_RESULT = 200;
	private static final int MAX_REPLY_ARGS = 300;

	private LlmDebugLogger() {
	}

	// ------------------------------------------------------------------
	// 请求日志
	// ------------------------------------------------------------------

	/**
	 * 记录一次请求：model、baseUrl、system（截断）、消息数摘要 + 最后一条 user 消息、工具名列表。
	 *
	 * <p>agentic loop 每轮都调一次，AgentRuntime 里已有"第N轮 消息数/工具数/问题"的完整摘要，
	 * 所以这里<b>不展开全量历史</b>——否则 10 轮对话会写入 1+2+…+10 条消息，指数膨胀。
	 * API Key 不出现在日志中。
	 */
	public static void logRequest(Request request) {
		StringBuilder sb = new StringBuilder();
		sb.append("→ ").append(request.model())
				.append(" @ ").append(LlmClient.normalizeBaseUrl(request.baseUrl())).append('\n');
		if (request.system() != null && !request.system().isBlank()) {
			sb.append("[system] ").append(truncate(request.system(), MAX_SYSTEM)).append('\n');
		}
		// 消息列表：只记条数 + 最后一条 user 消息（避免 agentic loop 每轮写全量上下文）
		List<Message> msgs = request.messages();
		if (!msgs.isEmpty()) {
			sb.append("[messages] 共 ").append(msgs.size()).append(" 条");
			for (int i = msgs.size() - 1; i >= 0; i--) {
				Message m = msgs.get(i);
				if (m.role() == LlmClient.Role.USER) {
					String text = m.text();
					if (!text.isEmpty()) {
						sb.append("，最后 user: ").append(truncate(text, MAX_TEXT));
					}
					break;
				}
			}
			sb.append('\n');
		}
		if (request.tools() != null && !request.tools().isEmpty()) {
			sb.append("[tools]");
			for (ToolSchema t : request.tools()) {
				sb.append(' ').append(t.name());
			}
			sb.append('\n');
		}
		DebugLog.log("llm", "请求:\n{}", sb.toString().stripTrailing());
	}

	// ------------------------------------------------------------------
	// 回复日志
	// ------------------------------------------------------------------

	/**
	 * 记录一次完整回复：finish reason、各类内容块（截断），失败时只记录 code + message。
	 */
	public static void logResponse(List<Block> content, FinishReason reason, LlmFailure failure) {
		if (failure != null) {
			DebugLog.log("llm", "← 失败 [{}] {}", failure.code(), failure.message());
			return;
		}
		StringBuilder sb = new StringBuilder();
		sb.append("← ").append(reason == null ? "stop" : reason.kind().name().toLowerCase(Locale.ROOT))
				.append('\n');
		for (Block b : content) {
			switch (b) {
				case TextBlock t -> {
					if (!t.text().isEmpty()) {
						sb.append("[text] ").append(truncate(t.text(), MAX_REPLY_TEXT)).append('\n');
					}
				}
				case ReasoningBlock r -> {
					if (!r.text().isEmpty()) {
						sb.append("[reasoning] ").append(truncate(r.text(), MAX_REASONING)).append('\n');
					}
				}
				case ToolCallBlock c ->
						sb.append("[tool_call] ").append(c.name()).append(' ')
								.append(truncate(c.arguments(), MAX_REPLY_ARGS)).append('\n');
				case ToolResultBlock r ->
						sb.append("[tool_result] ").append(r.toolCallId()).append(' ')
								.append(truncate(r.content(), MAX_TOOL_RESULT)).append('\n');
			}
		}
		DebugLog.log("llm", "回复:\n{}", sb.toString().stripTrailing());
	}

	// ------------------------------------------------------------------
	// 流式 sink 包装
	// ------------------------------------------------------------------

	/**
	 * 包装一个 {@link ChunkSink}：透明转发所有 chunk，同时在 {@link BlockEnd} 累积完整块，
	 * 并在 {@link Finish} 时调用 {@link #logResponse} 记录本次流式回复。
	 *
	 * <p>调用方只需把包装后的 sink 传给 {@code stream()}，无需在任何其他地方埋点。
	 */
	public static ChunkSink wrapSink(ChunkSink delegate) {
		return new LoggingChunkSink(delegate);
	}

	private static final class LoggingChunkSink implements ChunkSink {
		private final ChunkSink delegate;
		private final List<Block> blocks = new ArrayList<>();

		LoggingChunkSink(ChunkSink delegate) {
			this.delegate = delegate;
		}

		@Override
		public void onChunk(Chunk chunk) {
			if (chunk instanceof BlockEnd be) {
				blocks.add(be.block());
			} else if (chunk instanceof Finish f) {
				logResponse(blocks, f.reason(), f.failure());
			}
			delegate.onChunk(chunk);
		}
	}

	// ------------------------------------------------------------------
	// 工具方法
	// ------------------------------------------------------------------

	/** 超出 {@code max} 字符时截断并附加剩余字数提示。 */
	static String truncate(String s, int max) {
		if (s == null) return "(null)";
		if (s.length() <= max) return s;
		return s.substring(0, max) + "…[+" + (s.length() - max) + "]";
	}
}
