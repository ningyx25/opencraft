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
 * 计划摘要随工具成功结果回显给模型、进行中步骤由每轮尾部 {@code [Current State]} 观测携带
 * （不进 system——保 KV 前缀缓存）——助手在多步任务中始终记得"做到哪一步、还剩哪些"，
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

	/** 计划正文（紧凑 JSON steps 数组,结构化数据段——按需给展示/调试用；发给模型的是
	 *  {@link #summary()} 与 {@link #currentStep()}（工具结果回显 + 尾部观测））。 */
	public String format() {
		JsonArray arr = new JsonArray();
		for (Step s : steps) {
			JsonObject o = new JsonObject();
			o.addProperty("content", s.content());
			o.addProperty("status", s.status());
			arr.add(o);
		}
		JsonObject root = new JsonObject();
		root.add("steps", arr);
		return root.toString();
	}

	/** 是否存在未完成步骤（pending / in_progress）——终止守卫判断"任务是否真的做完"。 */
	public boolean hasUnfinished() {
		for (Step s : steps) {
			if (!s.status().equals("completed")) {
				return true;
			}
		}
		return false;
	}

	/** 第一个进行中步骤的内容（无 in_progress 步骤返回 null）——尾部状态观测的 plan_now 字段用。 */
	public String currentStep() {
		for (Step s : steps) {
			if (s.status().equals("in_progress")) {
				return s.content();
			}
		}
		return null;
	}

	/** 简短摘要（工具结果回显 + 日志）：N step(s)（完成 M，进行中 K，待办 L）。 */
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
		String plural = steps.size() == 1 ? "step" : "steps";
		return steps.size() + " " + plural + " (" + done + " done, " + active + " in progress, "
				+ (steps.size() - done - active) + " pending)";
	}
}
