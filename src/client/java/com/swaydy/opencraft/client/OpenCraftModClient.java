package com.swaydy.opencraft.client;

import com.swaydy.opencraft.ai.AiConfigData;
import com.swaydy.opencraft.client.gui.AiAssistantInteractScreen;
import com.swaydy.opencraft.client.gui.AiConfigScreen;
import com.swaydy.opencraft.client.render.AiAssistantRenderer;
import com.swaydy.opencraft.client.render.AssistantStreamOverlay;
import com.swaydy.opencraft.entity.ModEntities;
import com.swaydy.opencraft.net.AiConfigPayloads;
import com.swaydy.opencraft.net.AssistantPayloads;
import com.swaydy.opencraft.net.AssistantStreamPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderers;

public class OpenCraftModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 注册 AI 助手的渲染器（EntityRenderers.register 通过 Fabric 的
		// transitive access widener 开放给模组使用）
		EntityRenderers.register(ModEntities.AI_ASSISTANT, AiAssistantRenderer::new);

		// 接收 AI 配置数据：打开（或刷新）游戏内配置编辑器
		ClientPlayNetworking.registerGlobalReceiver(
				AiConfigPayloads.AiConfigDataPayload.TYPE,
				(payload, context) -> context.client().execute(() -> {
					AiConfigData data = AiConfigData.fromJson(payload.json());
					Minecraft minecraft = context.client();
					if (minecraft.screen instanceof AiConfigScreen screen) {
						screen.updateData(data, payload.canEdit(), payload.bound(), payload.boundByMe(),
								payload.pos(), payload.dimension());
					} else {
						minecraft.setScreen(new AiConfigScreen(
								data, payload.canEdit(), payload.bound(), payload.boundByMe(),
								payload.pos(), payload.dimension()));
					}
				}));

		// 接收“右键 AI 助手互动”数据：打开（或刷新）互动界面
		ClientPlayNetworking.registerGlobalReceiver(
				AssistantPayloads.AssistantInteractPayload.TYPE,
				(payload, context) -> context.client().execute(() -> {
					Minecraft minecraft = context.client();
					if (minecraft.screen instanceof AiAssistantInteractScreen screen
							&& screen.getEntityId() == payload.entityId()) {
						screen.updateData(payload.isOwner(), payload.model(),
								payload.agent());
					} else {
						minecraft.setScreen(new AiAssistantInteractScreen(
								payload.entityId(), payload.displayName(),
								payload.isOwner(), payload.model(), payload.agent(),
								payload.blockPos(), payload.dimension()));
					}
				}));

		// 接收聊天窗口事件：转发给配置界面聊天窗口，或（按绑定方块坐标匹配）右键互动界面
		ClientPlayNetworking.registerGlobalReceiver(
				AiConfigPayloads.AiConfigChatEventPayload.TYPE,
				(payload, context) -> context.client().execute(() -> {
					if (context.client().screen instanceof AiConfigScreen screen) {
						screen.handleChatEvent(payload.kind(), payload.text(),
								payload.pos(), payload.dimension());
					} else if (context.client().screen instanceof AiAssistantInteractScreen interact
							&& interact.matchesBlock(payload.pos(), payload.dimension())) {
						interact.handleChatEvent(payload.kind(), payload.text());
					}
				}));

		// 世界内流式浮层：接收全部入口的流式回复快照（sessionId 路由，见 AssistantStreamOverlay）
		ClientPlayNetworking.registerGlobalReceiver(
				AssistantStreamPayloads.AssistantStreamPayload.TYPE,
				(payload, context) -> context.client().execute(() -> AssistantStreamOverlay.update(
						payload.sessionId(), payload.name(), payload.text(), payload.done())));

		// 渲染世界内流式浮层（每次 HUD 渲染时）
		HudRenderCallback.EVENT.register(AssistantStreamOverlay::render);
	}
}
