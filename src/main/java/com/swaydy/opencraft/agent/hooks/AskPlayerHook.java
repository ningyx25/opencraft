package com.swaydy.opencraft.agent.hooks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.swaydy.opencraft.agent.LoopSession;
import com.swaydy.opencraft.ai.LlmClient;
import com.swaydy.opencraft.plugins.ToolResult;

import java.util.List;

/**
 * {@code ask_player} 核心工具钩子（参考 deepseek-harness 的 {@code dsh-tool-ask-user}：
 * 一个等待人类输入的长时间工具，loop 在其结果就绪前暂停）。
 *
 * <p>贡献 {@code ask_player} 工具 schema；模型在指令含糊或行动具破坏性/不可逆影响时调用。
 * 有效提问 → 返回 {@link ToolHandle#ask}：结果入列后循环暂停、向玩家呈现问题，
 * 玩家 {@code /opencraft answer} 回答或超时后恢复（暂停/恢复机制由 {@code AgentRuntime} 承载）。
 *
 * <p><b>确认优先</b>：模型常在破坏性动作<em>之前</em>请求确认，故 {@link #beforeBatch} 在逐 call
 * 分派前扫描整批——发现有效 ask_player 就只处理它并暂停、跳过同批其余工具（提问缺参则回错误结果、
 * 其余工具照常执行）。
 */
public final class AskPlayerHook implements LoopHook {
	public static final String TOOL_NAME = "ask_player";

	@Override
	public List<JsonObject> tools() {
		return List.of(LoopHook.functionTool(TOOL_NAME,
				"Ask the player a short question to confirm when you cannot decide what to do, or when an action may be "
						+ "destructive or irreversible (e.g. mining a functional block, unclear target). The conversation pauses after "
						+ "the call and resumes once the player replies via /opencraft answer; don't use it unless you really need confirmation.",
				parameters()));
	}

	/** 整批扫描：有效提问短路（只处理 ask 并暂停）；缺参提问回错误、其余工具照常。 */
	@Override
	public BatchClaim beforeBatch(LoopSession session, List<LlmClient.ToolCallBlock> calls) {
		for (LlmClient.ToolCallBlock call : calls) {
			if (!TOOL_NAME.equals(call.name() == null ? "" : call.name().trim())) {
				continue;
			}
			return BatchClaim.of(call, handle(call));
		}
		return BatchClaim.none();
	}

	/** 防御性逐 call 认领（正常已由 {@link #beforeBatch} 认领）。 */
	@Override
	public ToolHandle handleTool(LoopSession session, LlmClient.ToolCallBlock call) {
		if (!TOOL_NAME.equals(call.name() == null ? "" : call.name().trim())) {
			return ToolHandle.notHandled();
		}
		return handle(call);
	}

	/** 解析 ask_player 调用：有效提问 → 暂停等待；缺 question → 错误结果（不暂停）。 */
	private static ToolHandle handle(LlmClient.ToolCallBlock call) {
		String question = questionOf(call);
		if (question == null) {
			return ToolHandle.handled(ToolResult.error(
					"Please provide the question parameter (a short confirmation question for the player)."));
		}
		return ToolHandle.ask(
				ToolResult.ok("Question asked to the player; waiting for their reply…"), question);
	}

	/** 从参数取 question 文本；缺失/非字符串/空白返回 null。 */
	private static String questionOf(LlmClient.ToolCallBlock call) {
		JsonObject args = LoopHook.parseArgsObject(call.arguments());
		if (args == null || !args.has("question") || !args.get("question").isJsonPrimitive()) {
			return null;
		}
		String q = args.get("question").getAsString();
		return (q == null || q.isBlank()) ? null : q.trim();
	}

	/** { type: object, properties: { question: string, options?: string[] }, required: [question] } */
	private static JsonObject parameters() {
		JsonObject question = new JsonObject();
		question.addProperty("type", "string");
		question.addProperty("description", "A short one-line question to ask the player for confirmation.");
		JsonObject optionsItem = new JsonObject();
		optionsItem.addProperty("type", "string");
		JsonObject options = new JsonObject();
		options.addProperty("type", "array");
		options.add("items", optionsItem);
		options.addProperty("description", "Optional: a few candidate answers for the player");

		JsonObject properties = new JsonObject();
		properties.add("question", question);
		properties.add("options", options);
		JsonObject params = new JsonObject();
		params.addProperty("type", "object");
		params.add("properties", properties);
		JsonArray required = new JsonArray();
		required.add("question");
		params.add("required", required);
		return params;
	}
}
