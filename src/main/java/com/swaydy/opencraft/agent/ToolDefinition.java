package com.swaydy.opencraft.agent;

import com.google.gson.JsonObject;

/**
 * LLM 可调用的一项工具。
 *
 * @param name       工具名（如 "mine"），模型用它在 function calling 中调用
 * @param description 给模型看的行为说明（时机/参数/返回值语义要写清楚）
 * @param parameters 参数 JSON Schema（Gson 构建，{"type":"object","properties":{...}}）
 * @param executor   在服务端线程执行，返回给模型看的结果文本
 */
public record ToolDefinition(String name, String description, JsonObject parameters,
                             ToolExecutor executor) {

	/** 工具执行器：在服务端线程运行，解析 args 后执行并返回结果。 */
	@FunctionalInterface
	public interface ToolExecutor {
		ToolResult execute(ToolContext ctx, JsonObject args);
	}
}