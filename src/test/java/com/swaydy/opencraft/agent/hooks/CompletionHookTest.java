package com.swaydy.opencraft.agent.hooks;

import com.google.gson.JsonObject;
import com.swaydy.opencraft.agent.LoopSession;
import com.swaydy.opencraft.agent.TaskPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 终止守卫钩子（agent/turn-stopping 否决语义）的纯逻辑单测：计划未完成则暂缓收尾，
 * 无计划/计划完成则放行。异步动作在途依赖 Minecraft 运行时，这里用 null 助手路径覆盖。
 */
class CompletionHookTest {

	private static LoopSession session(TaskPlan plan) {
		LoopSession s = new LoopSession(null, null, null,
				null, new ArrayList<>(), null, null, null, null, false, 0);
		s.plan = plan;
		return s;
	}

	private static TaskPlan plan(String status) {
		JsonObject step = new JsonObject();
		step.addProperty("content", "gather wood");
		step.addProperty("status", status);
		JsonObject steps = new JsonObject();
		steps.add("steps", new com.google.gson.JsonArray());
		steps.getAsJsonArray("steps").add(step);
		return TaskPlan.fromJson(steps);
	}

	@Test
	void unfinishedPlanHoldsFinalText() {
		CompletionHook hook = new CompletionHook();
		HoldDecision decision = hook.onFinalText(session(plan("in_progress")), "I am heading there.");
		assertTrue(decision.hold());
		assertTrue(decision.reminder().contains("not finished"));
	}

	@Test
	void finishedPlanAllowsFinalText() {
		CompletionHook hook = new CompletionHook();
		HoldDecision decision = hook.onFinalText(session(plan("completed")), "All done.");
		assertFalse(decision.hold());
	}

	@Test
	void noPlanAllowsFinalText() {
		CompletionHook hook = new CompletionHook();
		HoldDecision decision = hook.onFinalText(session(null), "Nothing to do.");
		assertFalse(decision.hold());
	}
}
