package com.swaydy.opencraft.agent;

import com.swaydy.opencraft.ai.AiBlockConfig;
import com.swaydy.opencraft.ai.LlmClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话历史压缩（参考 deepseek-harness 的 {@code compaction/} 包：历史过长时把最旧区段压缩成
 * 记忆摘要，而不是直接丢弃）。
 *
 * <p>两种收束方式：
 * <ul>
 *   <li><b>压缩</b>：历史超过 {@code maxHistoryMessages×2} 时，在工作线程用一次非工具 LLM 调用
 *       （复用当前模型与 persona + {@link Prompts#COMPACT_INSTRUCTION}）把最旧区段压成
 *       {@code <compacted-summary>} 记忆摘要——比直接裁剪保留更多记忆；后续压缩会自然把旧摘要并入新摘要。</li>
 *   <li><b>裁剪（回退）</b>：压缩失败/摘要为空/没真正变短时，退回只保留最近 {@code maxHistoryMessages} 条。</li>
 * </ul>
 *
 * <p>本类只承载压缩的<b>策略与纯逻辑</b>（是否需要压缩、保留多少、LLM 摘要、落地应用、裁剪），
 * 不碰线程调度与 loop 续跑——那些由 {@link AgentRuntime} 编排（压缩在工作线程、落地在服务端线程）。
 * 纯列表逻辑（{@link #apply}/{@link #trimToRecent}）无 Minecraft 依赖，可直接单测。
 */
final class HistoryCompactor {
	private HistoryCompactor() {
	}

	/** 历史条数超过该阈值（maxHistoryMessages×2）才值得先压缩再开始本轮。 */
	static boolean needsCompaction(int historySize, int maxHistoryMessages) {
		return historySize > (long) maxHistoryMessages * 2L;
	}

	/** 实际保留的最近条数（至少 2，防止把上下文裁空）。 */
	static int keepCount(int maxHistoryMessages) {
		return Math.max(2, maxHistoryMessages);
	}

	/**
	 * 一批消息的模型可见字符数估算：文本 + 推理 + 工具调用名/参数 + 工具结果内容。
	 * 只用 {@code Message.text()} 会把纯工具回合与工具结果算成 0，让“摘要是否真的更短”判定失真。
	 */
	static long charsOf(List<LlmClient.Message> messages) {
		long total = 0;
		for (LlmClient.Message m : messages) {
			if (m == null) {
				continue;
			}
			total += len(m.text());
			total += len(m.reasoning());
			for (LlmClient.ToolCallBlock call : m.toolCalls()) {
				total += len(call.name());
				total += len(call.arguments());
			}
			for (LlmClient.ToolResultBlock result : m.toolResults()) {
				total += len(result.content());
			}
		}
		return total;
	}

	private static long len(String s) {
		return s == null ? 0 : s.length();
	}

	/**
	 * 在工作线程上把「最旧区段」压缩成摘要（一次非工具 LLM 调用）。
	 * 返回 null 表示压缩不可用（区段太小/请求失败/摘要为空/没有变短），调用方应退回裁剪。
	 */
	static String summarize(AiBlockConfig config, AgentDefinition agent,
	                        List<LlmClient.Message> region) {
		try {
			long regionChars = charsOf(region);
			if (regionChars <= 200) {
				return null; // 区段太小，不值得压缩
			}
			List<LlmClient.Message> messages = new ArrayList<>(region);
			messages.add(LlmClient.Message.user(Prompts.COMPACT_INSTRUCTION));
			LlmClient.Request request = new LlmClient.Request(
					config.baseUrl, config.apiKey, config.model,
					Prompts.persona(config, agent), messages, null,
					Math.min(0.5, config.temperature), null, null, config.timeoutSeconds);
			LlmClient.ChatResult resp = LlmClient.chat(request);
			if (!resp.ok()) {
				com.swaydy.opencraft.logging.DebugLog.log("history",
						"历史压缩请求失败: {}", resp.failure() == null ? "未知错误" : resp.failure().message());
				return null;
			}
			String summary = resp.text() == null ? "" : resp.text().trim();
			if (summary.isBlank() || summary.length() * 2 >= regionChars) {
				com.swaydy.opencraft.logging.DebugLog.log("history",
						"历史压缩结果未变短（{} → {} 字符）,放弃压缩", regionChars, summary.length());
				return null;
			}
			return summary;
		} catch (Exception e) {
			com.swaydy.opencraft.logging.DebugLog.log("history", "历史压缩异常: {}", e.toString());
			return null;
		}
	}

	/**
	 * 把压缩结果落地到历史（原地修改）：摘要有效且确实变短 → 最旧 {@code drop} 条替换为一条
	 * {@code <compacted-summary>}；否则退回裁剪最旧 {@code drop} 条。返回简短结果供日志。
	 *
	 * @param history     当前持久化历史（会被原地修改）
	 * @param keep        保留的最近条数（{@link #keepCount}）
	 * @param regionChars 被压缩区段的原始字符数（判断摘要是否真的变短用）
	 * @param summary     LLM 摘要，null = 压缩不可用（走裁剪）
	 */
	static Outcome apply(List<LlmClient.Message> history, int keep, long regionChars, String summary) {
		int drop = Math.min(Math.max(0, history.size() - keep), history.size());
		if (summary != null && drop > 0 && summary.length() * 2 < regionChars) {
			history.subList(0, drop).clear();
			history.add(0, LlmClient.Message.user(
					"<compacted-summary>\n" + summary + "\n</compacted-summary>"));
			return new Outcome(true, drop, summary.length());
		}
		if (drop > 0) {
			history.subList(0, drop).clear();
		}
		return new Outcome(false, drop, 0);
	}

	/**
	 * 把消息列表裁剪到最近 n 条（system 独立于消息列表，无需特殊保留首条）。
	 *
	 * <p>被裁掉的区段里若有记忆摘要（{@code <compacted-summary>}），会保留摘要并放到结果头部：
	 * 否则压缩出的摘要会在下一次提问开始、消息超过上限时立刻被当普通旧消息裁掉，压缩等于白做。
	 * 摘要随后续压缩自然并入新摘要，不会无限累积。
	 */
	static List<LlmClient.Message> trimToRecent(List<LlmClient.Message> messages, int maxMessages) {
		int keep = keepCount(maxMessages);
		if (messages.size() <= keep) {
			return messages;
		}
		List<LlmClient.Message> result =
				new ArrayList<>(messages.subList(messages.size() - keep, messages.size()));
		for (LlmClient.Message m : messages.subList(0, messages.size() - keep)) {
			if (isSummary(m)) {
				result.add(0, m);
			}
		}
		return result;
	}

	private static boolean isSummary(LlmClient.Message m) {
		String text = m == null ? null : m.text();
		return text != null && text.startsWith("<compacted-summary>");
	}

	/** 一次压缩落地结果（供调用方记录日志）。 */
	record Outcome(boolean compacted, int dropped, int summaryLength) {
	}
}
