package com.swaydy.opencraft.assistant.skin;

import java.util.List;

/**
 * 玩家形态助手的**内置皮肤**注册表。
 *
 * 皮肤与显示名解耦：显示名只影响聊天/界面文本，皮肤决定 bot 在世界里的模型外观。
 * 默认皮肤（default）= 原版行为（客户端按 GameProfile UUID 从 18 个官方默认皮肤里
 * 哈希选一个，不可自定义）；其余皮肤是随模组分发的内置贴图
 * （约定路径 assets/opencraft/textures/{@link #TEXTURE_DIR}<id>.png），
 * 由客户端在渲染时替换（见 AssistantSkinState + PlayerInfoMixin）：
 * 1.21.9+ 皮肤解析在客户端，且对非本地玩家强制要求 Mojang 签名贴图，假玩家无法
 * 从服务端"骗"到自定义皮肤——因此走客户端 Mixin 替换，皮肤 id 由服务端经
 * AssistantSkinPayload 同步到客户端（见 AssistantSkinSync）。
 *
 * 本类保持纯 Java（不依赖 Minecraft 类），便于单测与 common 侧配置校验。
 */
public final class AssistantSkins {
	/** 一条内置皮肤定义：id 与模型类型（"wide" = 经典 4px 臂 / "slim" = Alex 3px 臂）。 */
	public record SkinDef(String id, String model) {
		/** 界面显示名翻译键（lang：skin.opencraft.<id>）。 */
		public String displayNameKey() {
			return "skin.opencraft." + this.id;
		}
	}

	/** 默认皮肤 id：不做任何替换，走原版皮肤解析。 */
	public static final String DEFAULT_ID = "default";
	/** 内置皮肤贴图在模组资源里的目录约定（assets/opencraft/textures/<这里>/<id>.png）。 */
	public static final String TEXTURE_DIR = "skins/";

	private static final List<SkinDef> ALL = List.of(
			new SkinDef(DEFAULT_ID, "wide"),
			new SkinDef("deepseek_fish", "slim")
	);

	private AssistantSkins() {
	}

	/** 全部内置皮肤（含 default——它是"未选择时的原版回退"，配置界面选择器不把它列为可选项）。 */
	public static List<SkinDef> all() {
		return ALL;
	}

	/** 按 id 查皮肤定义；null/空串/未知 id 一律回退 default。 */
	public static SkinDef byId(String id) {
		String key = id == null ? "" : id.trim();
		for (SkinDef def : ALL) {
			if (def.id().equals(key)) {
				return def;
			}
		}
		return ALL.get(0);
	}

	/** 归一化皮肤 id：null/空/未知 → default（保存配置与同步前调用）。 */
	public static String normalize(String id) {
		return byId(id).id();
	}
}
