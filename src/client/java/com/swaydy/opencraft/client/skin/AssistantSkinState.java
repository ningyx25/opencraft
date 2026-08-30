package com.swaydy.opencraft.client.skin;

import com.mojang.authlib.GameProfile;
import com.swaydy.opencraft.OpenCraftMod;
import com.swaydy.opencraft.assistant.skin.AssistantSkins;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

/**
 * 客户端侧的助手皮肤状态：bot UUID → 内置皮肤 id 映射 + 渲染期皮肤解析。
 *
 * 数据来源是服务端的 AssistantSkinPayload（召唤/配置保存时全服广播、登录时补发），
 * 贴图本体随模组在客户端分发（assets/opencraft/textures/skins/<id>.png）。
 * 1.21.9+ 客户端对非本地玩家强制 Mojang 签名皮肤，服务端无法给假玩家"塞"自定义
 * 贴图，因此由 {@link com.swaydy.opencraft.client.mixin.PlayerInfoMixin} 在
 * 创建皮肤 lookup 时改道到这里：返回的 Supplier **每次 get 都重新查表**——
 * 皮肤同步晚于首次渲染到达（或中途换肤）也能即时生效，无需重建 PlayerInfo。
 */
public final class AssistantSkinState {
	/** 活动助手的皮肤映射（default 不入表 = 走原版解析；断线时整体清空）。 */
	private static final Map<UUID, String> SKINS = new ConcurrentHashMap<>();

	private AssistantSkinState() {
	}

	/** 接收服务端同步（OpenCraftModClient 注册的接收器调用）。 */
	public static void apply(UUID botUuid, String skinId) {
		String normalized = AssistantSkins.normalize(skinId);
		if (AssistantSkins.DEFAULT_ID.equals(normalized)) {
			SKINS.remove(botUuid);
		} else {
			SKINS.put(botUuid, normalized);
		}
	}

	/** 断开连接时清空映射，避免跨服务器残留误伤同 UUID 的真实玩家。 */
	public static void clear() {
		SKINS.clear();
	}

	/**
	 * PlayerInfoMixin 调用：该 profile 是否应使用内置皮肤。
	 * 返回动态 Supplier（每次 get 重新查表），null = 非助手，走原版解析。
	 */
	public static @Nullable Supplier<PlayerSkin> overrideLookup(GameProfile profile) {
		if (profile == null || !SKINS.containsKey(profile.id())) {
			return null;
		}
		return () -> skinFor(profile.id());
	}

	/** 解析某个 bot 的皮肤（查不到/已切回 default 时回退原版按 UUID 的默认皮肤）。 */
	public static PlayerSkin skinFor(UUID botUuid) {
		return skinOfDef(AssistantSkins.byId(SKINS.get(botUuid)), botUuid);
	}

	/**
	 * 配置界面实时预览：按任意皮肤 id 构造 {@link PlayerSkin}（default = 固定 UUID 的
	 * 原版默认皮肤，仅示意）。配合 {@code PlayerSkinWidget} 使用——它每帧调用 supplier，
	 * 选择器切换后预览即时跟随（含宽/细模型自动切换）。
	 */
	public static PlayerSkin previewSkin(String skinId) {
		return skinOfDef(AssistantSkins.byId(skinId), PREVIEW_UUID);
	}

	/** 预览用固定 UUID（default 皮肤按它取一个原版默认皮肤，稳定不变）。 */
	private static final UUID PREVIEW_UUID = UUID.nameUUIDFromBytes(
			"opencraft:skin-preview".getBytes(java.nio.charset.StandardCharsets.UTF_8));

	private static PlayerSkin skinOfDef(AssistantSkins.SkinDef def, UUID uuid) {
		if (AssistantSkins.DEFAULT_ID.equals(def.id())) {
			return DefaultPlayerSkin.get(uuid);
		}
		// 贴图路径约定：opencraft:skins/<id> → assets/opencraft/textures/skins/<id>.png
		//（ResourceTexture 构造器自动补 textures/ 前缀与 .png 后缀）
		PlayerModelType model = "slim".equals(def.model())
				? PlayerModelType.SLIM : PlayerModelType.WIDE;
		return new PlayerSkin(
				new ClientAsset.ResourceTexture(
						OpenCraftMod.id(AssistantSkins.TEXTURE_DIR + def.id())),
				null, null, model, true);
	}
}
