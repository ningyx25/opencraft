package com.swaydy.opencraft.agent.hooks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.swaydy.opencraft.ai.LlmClient;
import com.swaydy.opencraft.agent.LoopSession;

import java.util.List;

/**
 * Agentic loop 的生命周期钩子（SPI）——对齐 deepseek-harness 的核心设计：
 * <b>loop 本身只做「调模型 → 跑工具 → 把结果喂回 → 重复」，其余一切（守卫、核心工具、
 * 计划、向玩家提问等横切策略）都是监听 loop 事件 taxonomy 的监听器</b>，而非写死在驱动里。
 *
 * <p>每个钩子是无状态的策略类 + 一份 per-task 状态；{@link LoopHooks#createDefaults()}
 * 在每次提问（{@link LoopSession}）时新建一整套钩子，跨任务不串状态（等价于 dsh 里
 * per-agent scoped 上下文 + {@code WeakMap<Agent, Chain>}）。所有方法默认 no-op，
 * 钩子只覆写自己关心的生命周期点。
 *
 * <p>生命周期点（参考 dsh 事件名）：
 * <ul>
 *   <li>{@link #tools} —— 贡献核心工具 schema（dsh：工具由插件贡献；todo/ask-user 即工具）。</li>
 *   <li>{@link #beforeBatch} —— 一批工具调用分派前的整批认领（ask_player 优先确认短路）。</li>
 *   <li>{@link #handleTool} —— 认领核心工具调用（task_plan / ask_player），否则放行插件注册表
 *       （dsh：工具经 registry 分派；核心工具需访问 session 故由此认领）。</li>
 *   <li>{@link #afterTool} —— 单个工具执行后观察/增补提醒（dsh：{@code tools/post-execute}，
 *       重复调用守卫在此）。</li>
 *   <li>{@link #afterBatch} —— 整批工具执行完后观察/增补提醒（停滞守卫在此）。</li>
 *   <li>{@link #onFinalText} —— 模型给出纯文本（无工具调用）时可否决收尾（dsh：
 *       {@code agent/turn-stopping}，终止守卫在此）。</li>
 * </ul>
 */
public interface LoopHook {
	/** 贡献核心工具的 OpenAI tools-schema（每个请求随插件工具一起附加）。默认不贡献。 */
	default List<JsonObject> tools() {
		return List.of();
	}

	/**
	 * 一批工具调用分派前的整批认领钩子。默认不认领。
	 * 仅 ask_player 用于「确认先于动作」的短路：发现有效提问则只处理它并暂停。
	 */
	default BatchClaim beforeBatch(LoopSession session, List<LlmClient.ToolCallBlock> calls) {
		return BatchClaim.none();
	}

	/**
	 * 认领一次核心工具调用。返回 {@link ToolHandle#notHandled()} 放行给插件工具注册表；
	 * 返回 handled 则用其结果，且 {@code askQuestion} 非空时循环暂停等待玩家回答。
	 */
	default ToolHandle handleTool(LoopSession session, LlmClient.ToolCallBlock call) {
		return ToolHandle.notHandled();
	}

	/**
	 * 单个工具执行后（核心或插件工具）：观察型钩子可向 {@code out} 追加提醒消息。
	 * 不得抛异常、不否决结果（参考 dsh repeat-tool-reminder 的 observe-and-enrich 语义）。
	 */
	default void afterTool(LoopSession session, ToolExec exec, List<LlmClient.Message> out) {
	}

	/**
	 * 一整批工具执行完后：可向 {@code out} 追加提醒消息（停滞守卫）。
	 *
	 * @param executedNames 本批实际执行的工具名（按调用顺序）
	 */
	default void afterBatch(LoopSession session, List<String> executedNames,
	                        List<LlmClient.Message> out) {
	}

	/**
	 * 模型输出纯文本（无工具调用、且非总结轮）时决定是否暂缓收尾。
	 * 默认 {@link HoldDecision#finish()} 放行；终止守卫返回 hold 注入提醒续轮。
	 */
	default HoldDecision onFinalText(LoopSession session, String text) {
		return HoldDecision.finish();
	}

	/**
	 * 解析工具参数 JSON 字符串为对象：空白串视为空对象；非法 JSON 或非对象返回 null
	 * （语义与 AgentRuntime 的 parseArgs 一致：null = 参数格式本身错了，回显让模型自纠）。
	 */
	static JsonObject parseArgsObject(String argumentsJson) {
		if (argumentsJson == null || argumentsJson.isBlank()) {
			return new JsonObject();
		}
		try {
			JsonElement el = JsonParser.parseString(argumentsJson);
			return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * 组装一个 {@code {"type":"function","function":{name,description,parameters}}} 工具条目。
	 * 供贡献核心工具 schema 的钩子复用。
	 */
	static JsonObject functionTool(String name, String description, JsonObject parameters) {
		JsonObject fn = new JsonObject();
		fn.addProperty("name", name);
		fn.addProperty("description", description);
		fn.add("parameters", parameters);
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "function");
		schema.add("function", fn);
		return schema;
	}
}
