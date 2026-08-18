package com.swaydy.opencraft.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 任务计划（参考 deepseek-harness 的 {@code dsh-tool-todo} 插件）。
 *
 * <p>模型通过 {@code task_plan} 工具维护一份结构化步骤清单（整单替换，每次调用都发完整列表），
 * AgentRuntime 每轮把当前计划注入 system 上下文——助手在多步任务中始终记得"做到哪一步、还剩哪些"，
 * 不会做晕头做重复。纯 Java、无 Minecraft 依赖，便于 JUnit 单测。
 *
 * <p>步骤状态：{@code pending}（待办）/ {@code in_progress}（进行中）/ {@code completed}（已完成）。
 * 约束：content 非空白且不重复；至少一条；状态必须是合法枚举。
 */
public final class TaskPlan {
	private static final List<String> STATUSES = List.of("pending", "in_progress", "completed");

	private final List<Step> steps;

	/** 一步：做什么 + 状态。 */
	public record Step(String content, String status) {
	}

	private TaskPlan(List<Step> steps) {
		this.steps = List.copyOf(steps);
	}

	/**
	 * 从工具参数 JSON 解析任务计划；参数非法（缺 steps / 空数组 / 内容空白 / 重复 /
	 * 非法状态）返回 null，调用方按"参数错误"回显给模型自纠。
	 */
	public static TaskPlan fromJson(JsonObject args) {
		if (args == null) {
			return null;
		}
		JsonElement el = args.get("steps");
		if (el == null || !el.isJsonArray()) {
			return null;
		}
		JsonArray arr = el.getAsJsonArray();
		if (arr.isEmpty()) {
			return null;
		}
		List<Step> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (JsonElement item : arr) {
			if (!item.isJsonObject()) {
				return null;
			}
			JsonObject o = item.getAsJsonObject();
			String content = o.has("content") && o.get("content").isJsonPrimitive()
					? o.get("content").getAsString().trim() : "";
			String status = o.has("status") && o.get("status").isJsonPrimitive()
					? o.get("status").getAsString().trim() : "";
			if (content.isEmpty() || !STATUSES.contains(status)) {
				return null;
			}
			if (!seen.add(content)) {
				return null;
			}
			out.add(new Step(content, status));
		}
		return new TaskPlan(out);
	}

	/** 给模型看的计划正文（注入 system 上下文用）：编号 + 状态标记 + 内容。 */
	public String format() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < steps.size(); i++) {
			Step s = steps.get(i);
			String mark = switch (s.status()) {
				case "in_progress" -> "⏳";
				case "completed" -> "✅";
				default -> "⬜";
			};
			sb.append(i + 1).append(". ").append(mark).append(' ').append(s.content()).append('\n');
		}
		String out = sb.toString();
		return out.endsWith("\n") ? out.substring(0, out.length() - 1) : out;
	}

	/** 简短摘要（工具结果回显 + 日志）：N 步（完成 M，进行中 K，待办 L）。 */
	public String summary() {
		int done = 0;
		int active = 0;
		for (Step s : steps) {
			if (s.status().equals("completed")) {
				done++;
			} else if (s.status().equals("in_progress")) {
				active++;
			}
		}
		return steps.size() + " 步（完成 " + done + "，进行中 " + active + "，待办 "
				+ (steps.size() - done - active) + "）";
	}
}
