package com.swaydy.opencraft.client;

import com.swaydy.opencraft.ai.AiConfigData;
import com.swaydy.opencraft.client.gui.AiConfigScreen;
import com.swaydy.opencraft.client.render.AssistantStreamOverlay;
import com.swaydy.opencraft.net.AiConfigPayloads;
import com.swaydy.opencraft.net.AssistantPayloads;
import com.swaydy.opencraft.net.AssistantStreamPayloads;
import com.swaydy.opencraft.client.gui.AssistantInventoryScreen;
import com.swaydy.opencraft.inventory.ModMenuTypes;
import net.minecraft.client.gui.screens.MenuScreens;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;

public class OpenCraftModClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 注册双面板助手背包界面（与 MenuType 绑定，原版框架会自动在 S2C 打开包时调用）
		MenuScreens.register(ModMenuTypes.ASSISTANT_INVENTORY, AssistantInventoryScreen::new);

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

		// 接收"右键 AI 助手"的后续数据：打开背包界面后服务端把助手实体 ID 发来
		// （同一连接内紧随打开包按序到达，屏幕已建好）——左侧用原版
		// renderEntityInInventory 渲染这个实体的模型
		ClientPlayNetworking.registerGlobalReceiver(
				AssistantPayloads.AssistantInteractPayload.TYPE,
				(payload, context) -> context.client().execute(() -> {
					if (context.client().screen instanceof AssistantInventoryScreen screen) {
						screen.setAssistantEntityId(payload.entityId());
					}
				}));

		// 接收聊天窗口事件：转发给配置界面聊天窗口
		ClientPlayNetworking.registerGlobalReceiver(
				AiConfigPayloads.AiConfigChatEventPayload.TYPE,
				(payload, context) -> context.client().execute(() -> {
					if (context.client().screen instanceof AiConfigScreen screen) {
						screen.handleChatEvent(payload.kind(), payload.text(),
								payload.pos(), payload.dimension());
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
