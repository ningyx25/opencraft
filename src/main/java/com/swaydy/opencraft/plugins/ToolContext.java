package com.swaydy.opencraft.plugins;

import com.swaydy.opencraft.assistant.AiAssistant;
import com.swaydy.opencraft.assistant.player.AiAssistantPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 一次工具调用的执行上下文：工具在服务端线程上执行，可访问此时所需的全部环境。
 *
 * @param server    当前 Minecraft 服务器
 * @param assistant 执行该工具的 AI 助手（见 {@link AiAssistant}）
 * @param owner     提问/发出指令的玩家（工具为 owner 服务）
 * @param level     助手当前所在的维度（工具只应在该维度内行动）
 */
public record ToolContext(MinecraftServer server, AiAssistant assistant,
                          ServerPlayer owner, ServerLevel level) {

	/** 助手本体（真 ServerPlayer bot）。 */
	public AiAssistantPlayer assistantPlayer() {
		return assistant instanceof AiAssistantPlayer player ? player : null;
	}
}
