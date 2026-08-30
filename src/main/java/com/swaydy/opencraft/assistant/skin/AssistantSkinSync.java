package com.swaydy.opencraft.assistant.skin;

import com.swaydy.opencraft.OpenCraftMod;
import com.swaydy.opencraft.assistant.player.AiAssistantPlayer;
import com.swaydy.opencraft.assistant.player.PlayerAssistantService;
import com.swaydy.opencraft.net.AssistantPayloads;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 助手皮肤的服务端同步：皮肤 id 保存在方块配置里（客户端不可见贴图，只同步 id 字符串），
 * 客户端按 id 用模组内置贴图替换 bot 的皮肤（见客户端 AssistantSkinState + PlayerInfoMixin）。
 *
 * 同步时机（保证任何客户端在任何时刻进服/目击 bot 都能拿到正确皮肤）：
 * <ul>
 * <li>召唤完成 / 旧存档重入 → 全服广播（{@link #syncToAll}）；</li>
 * <li>配置保存 → 该方块绑定的 bot 皮肤变了，立即重广播（{@link #syncForBlock}）；</li>
 * <li>玩家登录 → 补发全部活动 bot 的皮肤（PlayerAssistantService.init 里的 JOIN 钩子）；</li>
 * <li>送走 → 广播 default 清除映射，防止 UUID 将来复用时串味（{@link #clearAll}）。</li>
 * </ul>
 */
public final class AssistantSkinSync {
	private AssistantSkinSync() {
	}

	/** 把某个 bot 的当前皮肤同步给服务器上全部玩家（召唤完成 / 旧存档重入后调用）。 */
	public static void syncToAll(AiAssistantPlayer bot) {
		if (bot == null || bot.isRemoved()) {
			return;
		}
		MinecraftServer server = bot.level().getServer();
		if (server == null) {
			return;
		}
		String skinId = AssistantSkins.normalize(bot.getConfig().skin);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			send(player, bot.getUUID(), skinId);
		}
	}

	/** 方块绑定的助手皮肤同步（配置保存后调用；该方块无绑定时 no-op）。 */
	public static void syncForBlock(ServerLevel level, GlobalPos block) {
		if (level == null || block == null) {
			return;
		}
		AiAssistantPlayer bot = PlayerAssistantService.findBoundTo(block);
		if (bot != null) {
			syncToAll(bot);
		}
	}

	/** bot 送走时广播 default 清除映射（防 UUID 复用串味）。 */
	public static void clearAll(MinecraftServer server, UUID botUuid) {
		if (server == null || botUuid == null) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			send(player, botUuid, AssistantSkins.DEFAULT_ID);
		}
	}

	/** 玩家登录时补发单个 bot 的皮肤（JOIN 钩子逐个活动 bot 调用）。 */
	public static void syncTo(ServerPlayer player, AiAssistantPlayer bot) {
		if (player == null || bot == null || bot.isRemoved()) {
			return;
		}
		send(player, bot.getUUID(), AssistantSkins.normalize(bot.getConfig().skin));
	}

	private static void send(ServerPlayer player, UUID botUuid, String skinId) {
		try {
			ServerPlayNetworking.send(player,
					new AssistantPayloads.AssistantSkinPayload(botUuid, skinId));
		} catch (Exception e) {
			OpenCraftMod.LOGGER.debug("[OpenCraft] 发送助手皮肤同步失败（可能是模拟连接）: {}", e.toString());
		}
	}
}
