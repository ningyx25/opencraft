package com.swaydy.opencraft;

import com.swaydy.opencraft.ai.AiCompanionService;
import com.swaydy.opencraft.ai.AiConfigHandler;
import com.swaydy.opencraft.block.ModBlockEntities;
import com.swaydy.opencraft.block.ModBlocks;
import com.swaydy.opencraft.command.ModCommands;
import com.swaydy.opencraft.entity.ModEntities;
import com.swaydy.opencraft.net.AiConfigPayloads;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
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

		// 方块 / BlockItem 注册 + 加入创造标签页
		ModBlocks.register();

		// AI 徽标方块的方块实体（配置载体）注册
		ModBlockEntities.register();

		// AI 助手实体 + 刷怪蛋 + 属性注册
		ModEntities.register();

		// /opencraft 指令
		ModCommands.register();

		// AI 服务（对话历史、线程池、服务器生命周期）
		AiCompanionService.init();

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
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
