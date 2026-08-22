package com.swaydy.opencraft;

import com.swaydy.opencraft.ai.AiCompanionService;
import com.swaydy.opencraft.ai.AiConfigHandler;
import com.swaydy.opencraft.assistant.AiAssistant;
import com.swaydy.opencraft.assistant.AssistantFacade;
import com.swaydy.opencraft.block.ModBlockEntities;
import com.swaydy.opencraft.block.ModBlocks;
import com.swaydy.opencraft.command.ModCommands;
import com.swaydy.opencraft.entity.ModEntities;
import com.swaydy.opencraft.net.AiConfigPayloads;
import com.swaydy.opencraft.net.AssistantPayloads;
import com.swaydy.opencraft.net.AssistantStreamPayloads;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenCraftMod implements ModInitializer {
	public static final String MOD_ID = "opencraft";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		LOGGER.info("OpenCraft 启动！AI 游戏助手已就绪。");

		// 预热 openai-java 客户端（懒初始化共享基客户端，纯本地构建不发任何网络请求）：
		// 避免玩家第一次提问卡顿、也避免 gametest“冲刺”tick 下首个聊天请求的“等回复”断言
		// 因冷启动超时。
		com.swaydy.opencraft.ai.LlmClient.warmUp();

		// 调试模式：-Dopencraft.debug=true / OPEN_CRAFT_DEBUG=true 默认开启；
		// 游戏内可用 /opencraft debug on|off 动态切换（详见 DebugLog）
		if (com.swaydy.opencraft.logging.DebugLog.isEnabled()) {
			com.swaydy.opencraft.logging.DebugLog.log("debug",
					"OpenCraft 启动，调试模式已由启动参数开启（日志文件: {}）",
					com.swaydy.opencraft.logging.DebugLog.logFilePath());
			LOGGER.info("[OpenCraft] 调试模式已开启，日志写入 {}",
					com.swaydy.opencraft.logging.DebugLog.logFilePath());
		}

		// 助手双面板背包菜单类型注册
		com.swaydy.opencraft.inventory.ModMenuTypes.register();

		// 方块 / BlockItem 注册 + 加入创造标签页
		ModBlocks.register();

		// AI 徽标方块的方块实体（配置载体）注册
		ModBlockEntities.register();

		// AI 助手实体 + 刷怪蛋 + 属性注册
		ModEntities.register();

		// AI 插件系统 + Agent 预设注册表（插件、预设、agentic loop）
		com.swaydy.opencraft.agent.AgentRegistry.init();

		// /opencraft 指令
		ModCommands.register();

		// AI 服务（对话历史、线程池、服务器生命周期）
		AiCompanionService.init();

		// 玩家形态助手（假玩家/客户端形态）注册表与生命周期
		com.swaydy.opencraft.assistant.player.PlayerAssistantService.init();

		// AI 配置编辑器网络包：注册类型 + 保存/召唤/送走接收器
		PayloadTypeRegistry.playC2S().register(
				AiConfigPayloads.AiConfigSavePayload.TYPE,
				AiConfigPayloads.AiConfigSavePayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(
				AiConfigPayloads.AiConfigSummonPayload.TYPE,
				AiConfigPayloads.AiConfigSummonPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(
				AiConfigPayloads.AiConfigDismissPayload.TYPE,
				AiConfigPayloads.AiConfigDismissPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(
				AiConfigPayloads.AiConfigDataPayload.TYPE,
				AiConfigPayloads.AiConfigDataPayload.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(
				AiConfigPayloads.AiConfigSavePayload.TYPE,
				(payload, context) -> {
					ServerPlayer player = context.player();
					context.server().execute(() -> AiConfigHandler.save(
							player, payload.pos(), payload.dimension(), payload.json()));
				});
		ServerPlayNetworking.registerGlobalReceiver(
				AiConfigPayloads.AiConfigSummonPayload.TYPE,
				(payload, context) -> {
					ServerPlayer player = context.player();
					context.server().execute(() -> AiConfigHandler.summonWithBlock(
							player, payload.pos(), payload.dimension()));
				});
		ServerPlayNetworking.registerGlobalReceiver(
				AiConfigPayloads.AiConfigDismissPayload.TYPE,
				(payload, context) -> {
					ServerPlayer player = context.player();
					context.server().execute(() -> AiConfigHandler.dismissWithBlock(
							player, payload.pos(), payload.dimension()));
				});
		// 配置界面聊天窗口：注册类型 + 发消息/取历史接收器 + 事件下发
		PayloadTypeRegistry.playC2S().register(
				AiConfigPayloads.AiConfigChatPayload.TYPE,
				AiConfigPayloads.AiConfigChatPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(
				AiConfigPayloads.AiConfigChatHistoryPayload.TYPE,
				AiConfigPayloads.AiConfigChatHistoryPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(
				AiConfigPayloads.AiConfigInterruptPayload.TYPE,
				AiConfigPayloads.AiConfigInterruptPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(
				AiConfigPayloads.AiConfigChatEventPayload.TYPE,
				AiConfigPayloads.AiConfigChatEventPayload.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(
				AiConfigPayloads.AiConfigChatPayload.TYPE,
				(payload, context) -> {
					ServerPlayer player = context.player();
					context.server().execute(() -> AiConfigHandler.chatWithBlock(
							player, payload.pos(), payload.dimension(), payload.message()));
				});
		ServerPlayNetworking.registerGlobalReceiver(
				AiConfigPayloads.AiConfigChatHistoryPayload.TYPE,
				(payload, context) -> {
					ServerPlayer player = context.player();
					context.server().execute(() -> AiConfigHandler.sendChatHistory(
							player, payload.pos(), payload.dimension()));
				});
		ServerPlayNetworking.registerGlobalReceiver(
				AiConfigPayloads.AiConfigInterruptPayload.TYPE,
				(payload, context) -> {
					ServerPlayer player = context.player();
					context.server().execute(() -> AiConfigHandler.interruptWithBlock(
							player, payload.pos(), payload.dimension()));
				});

		// 流式回复世界内浮层：S2C（sessionId 路由，客户端只认最新会话，见 AssistantStreamPayloads）
		PayloadTypeRegistry.playS2C().register(
				AssistantStreamPayloads.AssistantStreamPayload.TYPE,
				AssistantStreamPayloads.AssistantStreamPayload.STREAM_CODEC);

		// 右键 AI 助手互动网络包：注册类型 + 聊天/送走接收器（跟随/待命模式已整体移除）
		PayloadTypeRegistry.playC2S().register(
				AssistantPayloads.AssistantChatPayload.TYPE,
				AssistantPayloads.AssistantChatPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(
				AssistantPayloads.AssistantDismissPayload.TYPE,
				AssistantPayloads.AssistantDismissPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(
				AssistantPayloads.AssistantInteractPayload.TYPE,
				AssistantPayloads.AssistantInteractPayload.STREAM_CODEC);
		ServerPlayNetworking.registerGlobalReceiver(
				AssistantPayloads.AssistantChatPayload.TYPE,
				(payload, context) -> context.server().execute(() -> {
					ServerPlayer player = context.player();
					AiAssistant assistant =
							AssistantFacade.resolveOwned(player, payload.entityId());
					if (assistant == null) {
						player.sendSystemMessage(Component.translatable("command.opencraft.interact.gone"));
						return;
					}
					String message = payload.message().trim();
					if (message.isEmpty()) {
						player.sendSystemMessage(Component.translatable("command.opencraft.ask.blank"));
						return;
					}
					GlobalPos block = assistant.getConfigBlock();
					if (block == null) {
						player.sendSystemMessage(Component.translatable("command.opencraft.interact.gone"));
						return;
					}
					// GUI 模式：回复以流式增量/完整回复事件回传互动界面（私人会话，不广播世界聊天）
					AiCompanionService.askGui(player, assistant, message,
							block.pos(), block.dimension());
				}));
		ServerPlayNetworking.registerGlobalReceiver(
				AssistantPayloads.AssistantDismissPayload.TYPE,
				(payload, context) -> context.server().execute(() -> {
					ServerPlayer player = context.player();
					AiAssistant assistant =
							AssistantFacade.resolveOwned(player, payload.entityId());
					if (assistant == null) {
						player.sendSystemMessage(Component.translatable("command.opencraft.interact.gone"));
						return;
					}
					if (AssistantFacade.dismiss(assistant)) {
						player.sendSystemMessage(Component.translatable("command.opencraft.dismiss.success"));
					}
				}));
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
