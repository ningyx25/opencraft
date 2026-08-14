package com.swaydy.opencraft.client.render;

import com.swaydy.opencraft.OpenCraftMod;
import com.swaydy.opencraft.entity.AiAssistantEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;

/**
 * AI 助手渲染器：使用玩家模型（HumanoidModel）+ 自制的机器人贴图。
 * 在 1.21.11 的 RenderState 体系下，只需要提供 createRenderState 与贴图。
 */
public class AiAssistantRenderer extends MobRenderer<AiAssistantEntity, HumanoidRenderState, HumanoidModel<HumanoidRenderState>> {
	private static final Identifier TEXTURE = OpenCraftMod.id("textures/entity/ai_assistant.png");

	public AiAssistantRenderer(EntityRendererProvider.Context context) {
		super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
	}

	@Override
	public HumanoidRenderState createRenderState() {
		return new HumanoidRenderState();
	}

	@Override
	public Identifier getTextureLocation(HumanoidRenderState state) {
		return TEXTURE;
	}
}
