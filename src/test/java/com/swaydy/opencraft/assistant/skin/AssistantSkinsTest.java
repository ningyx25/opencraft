package com.swaydy.opencraft.assistant.skin;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AssistantSkins} 纯 Java 单测：注册表完整性、id 归一化与未知 id 回退。
 */
class AssistantSkinsTest {

	@Test
	void defaultSkinIsPresentAndFirst() {
		List<AssistantSkins.SkinDef> all = AssistantSkins.all();
		assertTrue(!all.isEmpty(), "注册表不应为空");
		assertEquals(AssistantSkins.DEFAULT_ID, all.get(0).id(), "default 应排第一个（配置界面默认选中）");
	}

	@Test
	void deepseekFishSkinIsRegistered() {
		AssistantSkins.SkinDef def = AssistantSkins.byId("deepseek_fish");
		assertEquals("deepseek_fish", def.id(), "内置皮肤 蓝色大肥鱼(DeepSeek) 应已注册");
		// 贴图由客户端 Mixin 按此模型类型构造 PlayerSkin：DeepSeek 皮肤是 Alex 型细臂
		assertEquals("slim", def.model(), "deepseek_fish 应为 slim（Alex）模型");
	}

	@Test
	void normalizeFallsBackToDefault() {
		assertEquals(AssistantSkins.DEFAULT_ID, AssistantSkins.normalize(null));
		assertEquals(AssistantSkins.DEFAULT_ID, AssistantSkins.normalize(""));
		assertEquals(AssistantSkins.DEFAULT_ID, AssistantSkins.normalize("   "));
		assertEquals(AssistantSkins.DEFAULT_ID, AssistantSkins.normalize("no_such_skin"), "未知 id 应回退 default");
		assertEquals("deepseek_fish", AssistantSkins.normalize(" deepseek_fish "), "前后空白应裁剪后识别");
	}

	@Test
	void skinIdsAreUnique() {
		Set<String> seen = new HashSet<>();
		for (AssistantSkins.SkinDef def : AssistantSkins.all()) {
			assertTrue(seen.add(def.id()), "皮肤 id 不应重复: " + def.id());
		}
		assertEquals(AssistantSkins.all().size(), seen.size());
	}
}
