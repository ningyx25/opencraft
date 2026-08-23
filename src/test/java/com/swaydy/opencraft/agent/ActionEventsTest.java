package com.swaydy.opencraft.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ActionEvents} 的纯 Java 单测：到达事件（Δy 提示/卡住传送标记）、
 * 挖掘事件文案、背包差分文本。
 */
class ActionEventsTest {

	@Test
	void arrivalTextPlainWhenSameLevel() {
		String text = ActionEvents.arrivalText(-153, 67, -53, 0, false);
		assertEquals("Arrived at (-153,67,-53)", text, "无垂直差时就是纯到达文本");
	}

	@Test
	void arrivalTextNotesBigVerticalGap() {
		// 复现日志场景:目标在脚下 9 格（走到了石头正上方的地表）
		String text = ActionEvents.arrivalText(-153, 67, -53, -9, false);
		assertTrue(text.contains("9 blocks below you"), "应提示目标在下方: " + text);
		assertTrue(text.contains("dig down"), "应建议向下挖: " + text);
	}

	@Test
	void arrivalTextNotesTargetAbove() {
		String text = ActionEvents.arrivalText(-100, 64, -10, 5, false);
		assertTrue(text.contains("5 blocks above you"), "应提示目标在上方: " + text);
		assertFalse(text.contains("dig down"), "上方不该建议向下挖: " + text);
	}

	@Test
	void arrivalTextReportsTeleport() {
		String text = ActionEvents.arrivalText(1, 2, 3, 0, true);
		assertTrue(text.contains("teleported"), "卡住传送应如实报告: " + text);
	}

	@Test
	void smallVerticalGapNeedsNoNote() {
		String text = ActionEvents.arrivalText(1, 2, 3, 1, false);
		assertFalse(text.contains("below") && text.contains("above"), "±1 格不必提示");
	}

	@Test
	void miningTextsAreInformative() {
		assertTrue(ActionEvents.miningCompleteText(1, 2, 3).contains("(1,2,3)"));
		assertTrue(ActionEvents.miningAbortedRangeText(1, 2, 3).contains("interaction range"));
		assertTrue(ActionEvents.miningBlockGoneText(1, 2, 3).contains("gone"));
		assertTrue(ActionEvents.stoppedText().contains("stopped"));
	}

	@Test
	void noPickupYetNoteGuidesInsteadOfMisleading() {
		// 旧文案 "drops may not have been picked up" 会误导模型去地面找掉落物;
		// 新口径:尚未入包(掉在矿柱里),下一轮看 Assistant State 背包确认
		String note = ActionEvents.noPickupYetNote();
		assertTrue(note.contains("not in my inventory yet"), "应明确是'尚未'而非'可能没捡到': " + note);
		assertTrue(note.contains("Assistant State"), "应引导下一轮查背包: " + note);
		assertTrue(note.contains("shaft"), "应说明掉落物在矿柱里: " + note);
	}

	@Test
	void inventoryDiffShowsGainedAndLost() {
		Map<String, Integer> before = Map.of("cobblestone", 1, "stick", 2);
		Map<String, Integer> after = Map.of("cobblestone", 4, "spruce planks", 3);
		String diff = ActionEvents.inventoryDiffText(before, after);
		assertNotNull(diff);
		assertTrue(diff.contains("cobblestone×3"), "捡到的圆石应按增量报告: " + diff);
		assertTrue(diff.contains("spruce planks×3"), "新物品应报告: " + diff);
		assertTrue(diff.contains("stick×2"), "消耗的木棍应报告: " + diff);
	}

	@Test
	void inventoryDiffNoChangeIsNull() {
		Map<String, Integer> same = Map.of("cobblestone", 3);
		assertNull(ActionEvents.inventoryDiffText(same, same), "无变化返回 null（省略该句）");
		assertNull(ActionEvents.inventoryDiffText(null, same), "快照缺失返回 null");
	}

	@Test
	void inventoryDiffCountIncreases() {
		String diff = ActionEvents.inventoryDiffText(Map.of("cobblestone", 1), Map.of("cobblestone", 3));
		assertEquals(" picked up: cobblestone×2", diff, "同物品数量增加按增量报告");
	}
}
