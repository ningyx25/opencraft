package com.swaydy.opencraft.client.render;

import com.swaydy.opencraft.OpenCraftMod;
import com.swaydy.opencraft.entity.AiAssistantEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;

/**
 * AI 助手渲染器：玩家模型（HumanoidModel）+ 自制的机器人贴图。
 *
 * 继承 HumanoidMobRenderer 后自动获得玩家式渲染：
 * - 主手/副手物品握持（ItemInHandLayer，HumanoidMobRenderer 自带）；
 * - 头戴物品（CustomHeadLayer）；
 * 额外挂 HumanoidArmorLayer 让头盔/胸甲/护腿/靴子的装备栏也像玩家一样显示出来。
 */
public class AiAssistantRenderer extends
		HumanoidMobRenderer<AiAssistantEntity, HumanoidRenderState, HumanoidModel<HumanoidRenderState>> {
	private static final Identifier TEXTURE = OpenCraftMod.id("textures/entity/ai_assistant.png");

	public AiAssistantRenderer(EntityRendererProvider.Context context) {
		super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
		// 装备栏渲染：与玩家同款护甲层（ArmorModelSet.bake 把模型层位置烘成 HumanoidModel）
		this.addLayer(new HumanoidArmorLayer<>(
				this,
				ArmorModelSet.bake(ModelLayers.PLAYER_ARMOR, context.getModelSet(),
						HumanoidModel::new),
				context.getEquipmentRenderer()));
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