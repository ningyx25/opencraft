package com.swaydy.opencraft.agent;

import com.swaydy.opencraft.entity.AiAssistantEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 一次工具调用的执行上下文：工具在服务端线程上执行，可访问此时所需的全部环境。
 *
 * @param server    当前 Minecraft 服务器
 * @param assistant 执行该工具的 AI 助手实体
 * @param owner     提问/发出指令的玩家（工具为 owner 服务）
 * @param level     助手当前所在的维度（工具只应在该维度内行动）
 */
public record ToolContext(MinecraftServer server, AiAssistantEntity assistant,
                          ServerPlayer owner, ServerLevel level) {
}