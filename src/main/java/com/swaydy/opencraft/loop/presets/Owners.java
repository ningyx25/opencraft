package com.swaydy.opencraft.loop.presets;

import com.swaydy.opencraft.assistant.AiAssistant;
import com.swaydy.opencraft.assistant.player.AiAssistantPlayer;
import com.swaydy.opencraft.assistant.player.PlayerAssistantService;
import com.swaydy.opencraft.loop.LoopContext;
import com.swaydy.opencraft.loop.LoopModule;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 内置循环事件预设共享的「锚点解析」工具：从循环实例锚点（绑定方块的 {@link GlobalPos}）
 * 解析出主人玩家或助手本体，供预设的条件/事件/监测函数在服务端 tick 线程上运行时现取
 * （见 {@code HealAuraLoop} 的闭包捕获约定）。
 *
 * <p>任何缺失环节（无服务端/维度未加载/无绑定助手/主人离线）都返回 {@code null}，
 * 由调用方判空后跳过本轮——引擎对 {@code null} 无感，触发条件按不通过处理，
 * persistent 实例继续闲置监视。
 */
final class Owners {

	private Owners() {
	}

	/** 绑定方块的主人玩家；无服务端/方块/绑定助手/主人离线 → {@code null}。 */
	static ServerPlayer ownerOf(LoopContext ctx) {
		GlobalPos block = blockOf(ctx);
		if (block == null) {
			return null;
		}
		MinecraftServer server = LoopModule.server();
		if (server == null) {
			return null;
		}
		ServerLevel level = server.getLevel(block.dimension());
		if (level == null) {
			return null;
		}
		AiAssistant assistant = PlayerAssistantService.findBoundTo(block);
		if (assistant == null) {
			return null;
		}
		UUID ownerUuid = assistant.getOwnerUuid();
		return ownerUuid == null ? null : server.getPlayerList().getPlayer(ownerUuid);
	}

	/** 绑定方块的玩家形态助手本体；无服务端/方块/助手/已死亡 → {@code null}。 */
	static AiAssistantPlayer assistantOf(LoopContext ctx) {
		GlobalPos block = blockOf(ctx);
		if (block == null || LoopModule.server() == null) {
			return null;
		}
		AiAssistantPlayer assistant = PlayerAssistantService.findBoundTo(block);
		return assistant != null && assistant.isAlive() ? assistant : null;
	}

	/** 锚点不是绑定方块（如单测里的任意对象）→ {@code null}。 */
	private static GlobalPos blockOf(LoopContext ctx) {
		Object anchor = ctx.anchor();
		return anchor instanceof GlobalPos block ? block : null;
	}
}
