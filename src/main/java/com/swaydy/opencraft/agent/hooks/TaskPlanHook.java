package com.swaydy.opencraft.agent.hooks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.swaydy.opencraft.agent.LoopSession;
import com.swaydy.opencraft.agent.TaskPlan;
import com.swaydy.opencraft.ai.LlmClient;
import com.swaydy.opencraft.plugins.ToolResult;

import java.util.List;

/**
 * {@code task_plan} 核心工具钩子（参考 deepseek-harness 的 {@code dsh-tool-todo} 插件）。
 *
 * <p>贡献 {@code task_plan} 工具 schema 并认领其调用：模型以「整单替换」维护结构化步骤清单，
 * 成功时把解析后的 {@link TaskPlan} 与格式化文本写回 session（计划摘要随本工具的成功结果
 * 回显给模型、并进入每轮尾部状态观测；{@code CompletionHook} 读 {@code plan} 判断是否还有
 * 未完成步骤。计划不再注入 system——保持 system 跨轮恒定以命中 KV 前缀缓存）。
 *
 * <p>与旧内联行为一致：成功 = 「做了实事」（重置停滞计数）且<b>不</b>计入重复调用链；
 * 失败（参数错误）计入重复调用链——防止模型用错误参数无限重试。
 */
public final class TaskPlanHook implements LoopHook {
	public static final String TOOL_NAME = "task_plan";

	@Override
	public List<JsonObject> tools() {
		return List.of(LoopHook.functionTool(TOOL_NAME,
				"Record the plan and progress of your current multi-step task. Replace the whole list: each call sends the complete list. "
						+ "Mark each step completed as you finish it; as long as the task isn't over, keep at least one step in_progress. "
						+ "Don't use it for simple one-step tasks.",
				parameters()));
	}

	@Override
	public ToolHandle handleTool(LoopSession session, LlmClient.ToolCallBlock call) {
		if (!TOOL_NAME.equals(call.name() == null ? "" : call.name().trim())) {
			return ToolHandle.notHandled();
		}
		TaskPlan plan = TaskPlan.fromJson(LoopHook.parseArgsObject(call.arguments()));
		if (plan == null) {
			return ToolHandle.handled(ToolResult.error(
					"task_plan parameters are invalid: provide a steps array, each item {content, status}, "
							+ "status ∈ [pending|in_progress|completed], content non-empty and unique, at least one step."));
		}
		session.plan = plan;
		session.planUpdatedThisRound = true;
		return ToolHandle.handled(ToolResult.ok(
				"Task plan updated: " + plan.summary()
						+ (plan.currentStep() != null ? "; current step: " + plan.currentStep() : "")
						+ ". Follow it and update the status as you go."));
	}

	/** { type: object, properties: { steps: [{content, status}] }, required: [steps] } */
	private static JsonObject parameters() {
		JsonObject step = new JsonObject();
		step.addProperty("type", "object");
		JsonObject stepProps = new JsonObject();
		JsonObject content = new JsonObject();
		content.addProperty("type", "string");
		content.addProperty("description", "What this step should accomplish");
		stepProps.add("content", content);
		JsonObject status = new JsonObject();
		status.addProperty("type", "string");
		JsonArray statusEnum = new JsonArray();
		statusEnum.add("pending");
		statusEnum.add("in_progress");
		statusEnum.add("completed");
		status.add("enum", statusEnum);
		status.addProperty("description", "pending / in_progress / completed");
		stepProps.add("status", status);
		step.add("properties", stepProps);
		JsonArray stepRequired = new JsonArray();
		stepRequired.add("content");
		stepRequired.add("status");
		step.add("required", stepRequired);

		JsonObject steps = new JsonObject();
		steps.addProperty("type", "array");
		steps.add("items", step);
		steps.addProperty("description", "The complete list of steps; each call sends the full list to replace it");

		JsonObject properties = new JsonObject();
		properties.add("steps", steps);
		JsonObject params = new JsonObject();
		params.addProperty("type", "object");
		params.add("properties", properties);
		JsonArray required = new JsonArray();
		required.add("steps");
		params.add("required", required);
		return params;
	}
}
