package com.swaydy.opencraft.loop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LoopEngine} 的纯 Java 单测：状态机推进、间隔门控、迭代上限、
 * persistent 语义与全部守卫。用假条件/事件/监测函数（只碰 {@code ctx.state}）,anchor 用 String,
 * 不依赖任何 Minecraft 运行时。
 */
class LoopEngineTest {

	private static final String A = "anchor-A";
	private static final String B = "anchor-B";

	@BeforeEach
	void clearEngine() {
		LoopEngine.clear();
	}

	@AfterEach
	void clearEngineAfter() {
		LoopEngine.clear();
	}

	// ------------------------------------------------------------------
	// 辅助：用 ctx.state 计数的假组成部分
	// ------------------------------------------------------------------

	/** 事件：把 state 里的 "n" 计数 +1。 */
	private static void bump(LoopContext ctx) {
		ctx.state().merge("n", 1, (a, b) -> ((Number) a).intValue() + ((Number) b).intValue());
	}

	/** 读 state 里的 "n" 计数。 */
	private static int count(LoopContext ctx) {
		return ((Number) ctx.state().getOrDefault("n", 0)).intValue();
	}

	private static LoopDefinition def(LoopCondition trigger, LoopEvent event, LoopMonitor monitor,
	                                  int interval, int maxIterations, boolean persistent) {
		return LoopDefinition.builder("test")
				.trigger(trigger).event(event).monitor(monitor)
				.intervalTicks(interval).maxIterations(maxIterations).persistent(persistent)
				.build();
	}

	// ------------------------------------------------------------------
	// 状态机推进（interval = 1：触发在 0,事件在 1,监测在 2,再触发在 3 …）
	// ------------------------------------------------------------------

	@Test
	void advancesThroughFullCycle() {
		LoopDefinition d = def(ctx -> true, LoopEngineTest::bump, ctx -> LoopVerdict.CONTINUE, 1, 0, false);
		LoopEngine.start(d, A);

		LoopEngine.tick(0); // WAITING: 触发通过 → EXECUTING
		assertEquals(LoopPhase.EXECUTING, phase(A), "tick0 应进入执行阶段");
		assertEquals(0, iterations(A), "事件还没执行");

		LoopEngine.tick(1); // EXECUTING: 执行事件 → MONITORING
		assertEquals(LoopPhase.MONITORING, phase(A));
		assertEquals(1, iterations(A), "事件已执行一次");

		LoopEngine.tick(2); // MONITORING: CONTINUE → 回到 WAITING
		assertEquals(LoopPhase.WAITING, phase(A));
		assertTrue(LoopEngine.isRunning(A, "test"));

		LoopEngine.tick(3); // WAITING: 再次评估触发 → EXECUTING
		assertEquals(LoopPhase.EXECUTING, phase(A));
		LoopEngine.tick(4); // 事件第二次执行
		assertEquals(2, iterations(A));
		assertTrue(LoopEngine.isRunning(A, "test"), "CONTINUE 循环应持续运行");
	}

	@Test
	void triggerFalseSkipsEventButKeepsWatching() {
		LoopDefinition d = def(ctx -> false, LoopEngineTest::bump, ctx -> LoopVerdict.CONTINUE, 1, 0, false);
		LoopEngine.start(d, A);
		for (long t = 0; t <= 20; t++) {
			LoopEngine.tick(t);
		}
		assertTrue(LoopEngine.isRunning(A, "test"), "触发不满足时实例应保持存活监视");
		assertEquals(0, iterations(A), "事件从未执行");
	}

	// ------------------------------------------------------------------
	// 监测函数裁决
	// ------------------------------------------------------------------

	@Test
	void monitorStopRemovesOneShotInstance() {
		LoopDefinition d = def(ctx -> true, LoopEngineTest::bump, ctx -> LoopVerdict.STOP, 1, 0, false);
		LoopEngine.start(d, A);

		LoopEngine.tick(0);
		LoopEngine.tick(1); // 事件执行一次
		assertEquals(1, iterations(A), "事件执行了一次");
		LoopEngine.tick(2); // 监测 STOP → 一次性实例被移除
		assertFalse(LoopEngine.isRunning(A, "test"), "一次性循环 STOP 后实例应被移除");
		assertEquals(0, LoopEngine.activeCount());
	}

	@Test
	void monitorStopPersistentKeepsWatching() {
		LoopDefinition d = def(ctx -> true, LoopEngineTest::bump, ctx -> LoopVerdict.STOP, 1, 0, true);
		LoopEngine.start(d, A);

		LoopEngine.tick(0);
		LoopEngine.tick(1); // 事件 1
		LoopEngine.tick(2); // 监测 STOP → persistent: 回到等待
		assertTrue(LoopEngine.isRunning(A, "test"), "persistent 循环 STOP 只结束本轮");
		assertEquals(1, iterations(A));

		LoopEngine.tick(3); // 触发再次通过
		LoopEngine.tick(4); // 事件 2（主人再次受伤 → 再次治疗）
		assertEquals(2, iterations(A), "persistent 循环应可再次触发执行");
		assertTrue(LoopEngine.isRunning(A, "test"));
	}

	// ------------------------------------------------------------------
	// 间隔与迭代上限
	// ------------------------------------------------------------------

	@Test
	void intervalGatesTriggerEvaluation() {
		LoopDefinition d = def(ctx -> true, LoopEngineTest::bump, ctx -> LoopVerdict.CONTINUE, 10, 0, false);
		LoopEngine.start(d, A);

		LoopEngine.tick(0); // 触发评估 → EXECUTING（下次评估 tick10）
		LoopEngine.tick(1); // 事件 1
		LoopEngine.tick(2); // 监测 → WAITING
		for (long t = 3; t <= 10; t++) {
			LoopEngine.tick(t); // tick10 重新评估触发
		}
		assertEquals(1, iterations(A), "tick10 之前不应再执行事件");
		LoopEngine.tick(11); // 事件 2
		assertEquals(2, iterations(A), "间隔过后再次执行");
	}

	@Test
	void maxIterationsStopsInstance() {
		LoopDefinition d = def(ctx -> true, LoopEngineTest::bump, ctx -> LoopVerdict.CONTINUE, 1, 3, false);
		LoopEngine.start(d, A);

		// 事件在第 1、4、7 tick 执行
		for (long t = 0; t <= 7; t++) {
			LoopEngine.tick(t);
		}
		assertEquals(3, iterations(A), "应执行满 3 次");
		LoopEngine.tick(8); // 第 3 次事件的监测轮:达到上限 → 停止
		assertFalse(LoopEngine.isRunning(A, "test"), "达到 maxIterations 后实例应被移除");
		assertEquals(0, LoopEngine.activeCount());
	}

	// ------------------------------------------------------------------
	// 守卫：异常不抛给调用方
	// ------------------------------------------------------------------

	@Test
	void eventExceptionStopsInstanceSilently() {
		LoopDefinition d = def(ctx -> true, ctx -> {
			throw new RuntimeException("boom");
		}, ctx -> LoopVerdict.CONTINUE, 1, 0, false);
		LoopEngine.start(d, A);

		LoopEngine.tick(0);
		LoopEngine.tick(1); // 事件抛异常 → 实例停止,异常不外泄
		assertFalse(LoopEngine.isRunning(A, "test"));
		assertEquals(0, LoopEngine.activeCount());
	}

	@Test
	void monitorExceptionStopsInstanceSilently() {
		LoopDefinition d = def(ctx -> true, LoopEngineTest::bump, ctx -> {
			throw new RuntimeException("boom");
		}, 1, 0, false);
		LoopEngine.start(d, A);

		LoopEngine.tick(0);
		LoopEngine.tick(1); // 事件 1
		LoopEngine.tick(2); // 监测抛异常 → 停止
		assertFalse(LoopEngine.isRunning(A, "test"));
	}

	@Test
	void triggerExceptionCountsThenStops() {
		LoopDefinition d = def(ctx -> {
			throw new RuntimeException("boom");
		}, LoopEngineTest::bump, ctx -> LoopVerdict.CONTINUE, 1, 0, false);
		LoopEngine.start(d, A);

		// 连续异常:第 1..4 次按不通过跳过,实例存活;第 5 次（>= MAX_CONSECUTIVE_ERRORS）停止
		for (long t = 0; t < LoopEngine.MAX_CONSECUTIVE_ERRORS - 1; t++) {
			LoopEngine.tick(t);
			assertTrue(LoopEngine.isRunning(A, "test"), "第 " + (t + 1) + " 次异常后仍存活");
			assertEquals(0, iterations(A), "事件从未执行");
		}
		LoopEngine.tick(LoopEngine.MAX_CONSECUTIVE_ERRORS - 1L);
		assertFalse(LoopEngine.isRunning(A, "test"), "连续异常达上限应停止实例");
		assertEquals(0, LoopEngine.activeCount());
	}

	@Test
	void triggerExceptionRecoversAfterSuccess() {
		final boolean[] fail = {true};
		LoopDefinition d = def(ctx -> {
			if (fail[0]) {
				throw new RuntimeException("boom");
			}
			return true;
		}, LoopEngineTest::bump, ctx -> LoopVerdict.CONTINUE, 1, 0, false);
		LoopEngine.start(d, A);

		LoopEngine.tick(0); // 异常 1
		fail[0] = false;
		LoopEngine.tick(1); // 异常 2（fail 已关?不——tick1 时 fail 已关,触发成功 → 进入 EXECUTING）
		assertEquals(LoopPhase.EXECUTING, phase(A), "触发恢复成功应继续推进");
		LoopEngine.tick(2); // 事件 1
		assertEquals(1, iterations(A));
	}

	// ------------------------------------------------------------------
	// 实例级状态共享
	// ------------------------------------------------------------------

	@Test
	void statePersistsAcrossTicks() {
		// 事件置 done=true;触发条件读取 done——证明状态跨轮次共享
		LoopDefinition d = def(
				ctx -> !Boolean.TRUE.equals(ctx.state().get("done")),
				ctx -> ctx.state().put("done", true),
				ctx -> LoopVerdict.CONTINUE, 1, 0, false);
		LoopEngine.start(d, A);

		LoopEngine.tick(0); // 触发通过（done 未设）
		LoopEngine.tick(1); // 事件:done=true
		LoopEngine.tick(2); // 监测 → WAITING
		LoopEngine.tick(3); // 触发读取 done=true → 不通过,闲置
		assertEquals(1, iterations(A), "事件只执行一次");
		assertTrue(LoopEngine.isRunning(A, "test"), "实例仍存活（闲置监视）");
	}

	// ------------------------------------------------------------------
	// 生命周期:start 幂等 / stop / stopAll / clear / 多实例
	// ------------------------------------------------------------------

	@Test
	void startIsIdempotentAndStopWorks() {
		LoopDefinition d = def(ctx -> true, LoopEngineTest::bump, ctx -> LoopVerdict.CONTINUE, 1, 0, false);
		LoopEngine.start(d, A);
		LoopEngine.start(d, A);
		assertEquals(1, LoopEngine.activeCount(), "同 anchor+defId 重复 start 不重复创建");

		LoopEngine.stop(A, "test");
		assertEquals(0, LoopEngine.activeCount());
		assertFalse(LoopEngine.isRunning(A, "test"));

		LoopEngine.start(d, A);
		LoopEngine.stopAll(A);
		assertEquals(0, LoopEngine.activeCount(), "stopAll 应清掉该锚点全部实例");
	}

	@Test
	void clearRemovesAll() {
		LoopDefinition d = def(ctx -> true, LoopEngineTest::bump, ctx -> LoopVerdict.CONTINUE, 1, 0, false);
		LoopEngine.start(d, A);
		LoopEngine.start(d, B);
		assertEquals(2, LoopEngine.activeCount());
		LoopEngine.clear();
		assertEquals(0, LoopEngine.activeCount());
	}

	@Test
	void sameAnchorRunsMultipleDefinitions() {
		LoopDefinition d1 = def(ctx -> true, LoopEngineTest::bump, ctx -> LoopVerdict.CONTINUE, 1, 0, false);
		LoopDefinition d2 = LoopDefinition.builder("other")
				.trigger(ctx -> true).event(LoopEngineTest::bump)
				.monitor(ctx -> LoopVerdict.CONTINUE).build();
		LoopEngine.start(d1, A);
		LoopEngine.start(d2, A);
		assertEquals(2, LoopEngine.activeCount(), "同一锚点可同时跑多个不同定义");
		assertTrue(LoopEngine.isRunning(A, "test"));
		assertTrue(LoopEngine.isRunning(A, "other"));

		LoopEngine.stop(A, "test");
		assertTrue(LoopEngine.isRunning(A, "other"), "停止一个不影响另一个");
	}

	@Test
	void statusReportsInstances() {
		LoopDefinition d = def(ctx -> true, LoopEngineTest::bump, ctx -> LoopVerdict.CONTINUE, 1, 0, false);
		LoopEngine.start(d, A);
		LoopEngine.start(d, B);

		List<LoopStatus> status = LoopEngine.status();
		assertEquals(2, status.size());
		for (LoopStatus s : status) {
			assertEquals("test", s.defId());
			assertEquals(LoopPhase.WAITING, s.phase());
			assertEquals(0, s.iteration());
			assertTrue(s.anchor().equals(A) || s.anchor().equals(B), "锚点应出现在状态里: " + s.anchor());
		}

		LoopEngine.tick(0);
		LoopEngine.tick(1);
		LoopStatus after = LoopEngine.status().stream()
				.filter(s -> A.equals(s.anchor())).findFirst().orElseThrow();
		assertEquals(1, after.iteration());
	}

	// ------------------------------------------------------------------
	// 辅助断言
	// ------------------------------------------------------------------

	private static LoopPhase phase(Object anchor) {
		return LoopEngine.status().stream()
				.filter(s -> anchor.equals(s.anchor()))
				.findFirst()
				.map(LoopStatus::phase)
				.orElseThrow(() -> new AssertionError("找不到实例: " + anchor));
	}

	private static long iterations(Object anchor) {
		return LoopEngine.status().stream()
				.filter(s -> anchor.equals(s.anchor()))
				.findFirst()
				.map(LoopStatus::iteration)
				.orElse(-1L);
	}
}