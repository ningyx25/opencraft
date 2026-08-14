package com.swaydy.opencraft.client;

import com.swaydy.opencraft.ai.AiConfigData;
import com.swaydy.opencraft.client.gui.AiConfigScreen;
import com.swaydy.opencraft.client.render.AiAssistantRenderer;
import com.swaydy.opencraft.entity.ModEntities;
import com.swaydy.opencraft.net.AiConfigPayloads;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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
						screen.updateData(data, payload.canEdit(), payload.pos(), payload.dimension());
					} else {
						minecraft.setScreen(new AiConfigScreen(
								data, payload.canEdit(), payload.pos(), payload.dimension()));
					}
				}));
	}
}
