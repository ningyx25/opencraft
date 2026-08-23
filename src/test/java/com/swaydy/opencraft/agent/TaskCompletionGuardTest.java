package com.swaydy.opencraft.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TaskCompletionGuard} 的纯 Java 单测：
 * 计划未完成/异步动作在途 → 暂缓收尾；全部完成 → 放行；达到 MAX_HOLDS 后放行。
 */
class TaskCompletionGuardTest {

	@Test
	void holdsWhenPlanUnfinished() {
		String reminder = TaskCompletionGuard.holdReminder(true, false, 0, "2 steps (0 done, 1 in progress, 1 pending)");
		assertNotNull(reminder, "计划未完成应暂缓收尾");
		assertTrue(reminder.contains("unfinished steps"), "提醒应说明计划未完成: " + reminder);
		assertTrue(reminder.contains("2 steps"), "提醒应包含计划摘要: " + reminder);
	}

	@Test
	void holdsWhenAsyncActionInFlight() {
		String reminder = TaskCompletionGuard.holdReminder(false, true, 0, null);
		assertNotNull(reminder, "异步动作在途应暂缓收尾");
		assertTrue(reminder.contains("async action"), "提醒应说明异步动作在途: " + reminder);
		assertTrue(reminder.contains("abort"), "提醒应说明现在收尾会中止动作: " + reminder);
	}

	@Test
	void holdsForBothReasons() {
		String reminder = TaskCompletionGuard.holdReminder(true, true, 1, null);
		assertNotNull(reminder);
		assertTrue(reminder.contains("unfinished steps") && reminder.contains("async action"),
				"两个原因都应出现: " + reminder);
	}

	@Test
	void passesWhenEverythingComplete() {
		assertNull(TaskCompletionGuard.holdReminder(false, false, 0, null),
				"计划完成且无动作在途应放行收尾");
	}

	@Test
	void passesAfterMaxHolds() {
		for (int holds = 0; holds < TaskCompletionGuard.MAX_HOLDS; holds++) {
			assertNotNull(TaskCompletionGuard.holdReminder(true, false, holds, null),
					"第 " + (holds + 1) + " 次暂缓应生效");
		}
		assertNull(TaskCompletionGuard.holdReminder(true, false, TaskCompletionGuard.MAX_HOLDS, null),
				"达到 MAX_HOLDS 后放行（不与模型无限对抗）");
	}

	@Test
	void nullPlanSummaryIsFine() {
		assertNotNull(TaskCompletionGuard.holdReminder(true, false, 0, null), "摘要为 null 不影响判定");
		assertNotNull(TaskCompletionGuard.holdReminder(true, false, 0, "  "), "空白摘要不影响判定");
	}
}
