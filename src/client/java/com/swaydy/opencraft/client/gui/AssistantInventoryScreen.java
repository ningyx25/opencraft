package com.swaydy.opencraft.client.gui;

import com.swaydy.opencraft.inventory.AssistantInventoryMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;

/**
 * 右键 AI 助手打开的双面板背包界面：左半是助手"按 E"的背包，右半是玩家自己的——
 * 两半都直接使用原版按 E 的背包（{@code InventoryScreen}）的素材与代码。
 *
 * <p>全部直接复用原版（不是仿制）：
 * <ul>
 * <li>背景：原版常量 {@link AbstractContainerScreen#INVENTORY_LOCATION}
 *     （{@code textures/gui/container/inventory.png}，176×166 整张），左右各按原版
 *     {@code InventoryScreen.renderBg} 同款 blit 画一次——含人物预览区、护甲槽、
 *     2×2 合成格的全部底图；</li>
 * <li>人物模型：直接调用原版静态方法
 *     {@link InventoryScreen#renderEntityInInventoryFollowsMouse}——
 *     左边渲染 AI 助手实体（模型随鼠标转动，和原版 E 界面看自己一样），
 *     右边渲染玩家自己（与 {@code this.minecraft.player} 的原版用法一致）；</li>
 * <li>标签：原版 E 界面同款位置（titleLabelX=97）——左标题为助手名，
 *     右标题为原版 {@code container.crafting}；</li>
 * <li>交互：继承 {@link AbstractContainerScreen}（拖拽/悬浮提示/Shift 转移全部原版）。</li>
 * </ul>
 *
 * <p>助手实体引用：服务端打开菜单后随 {@code AssistantInteractPayload}（复用原通道）
 * 把实体 ID 发到客户端（同一连接内按序到达，屏幕先建、ID 后到），渲染时按 ID 从世界中
 * 取实体；另有兜底——准星所指实体（右键前正对着的就是助手），网络包迟到/丢失也能渲染。
 * 两级都取不到（延迟/已移除）才不画模型，仅留底图。
 */
public class AssistantInventoryScreen extends AbstractContainerScreen<AssistantInventoryMenu> {

	/** 原版背包界面高度（inventory.png 的 166px）。 */
	private static final int PANEL_H = 166;

	/** 助手实体 ID（服务端随打开包之后下发，-1 = 未收到）。 */
	private int assistantEntityId = -1;
	/** 鼠标位置（原版 InventoryScreen 同款，喂给模型转头）。 */
	private float xMouse;
	private float yMouse;

	public AssistantInventoryScreen(AssistantInventoryMenu menu,
	                                Inventory playerInventory,
	                                Component title) {
		super(menu, playerInventory, title);
		this.imageWidth = AssistantInventoryMenu.PANEL_W * 2 + AssistantInventoryMenu.PANEL_GAP;
		this.imageHeight = PANEL_H;
		// 原版 E 界面的标题位置（"合成"标题在合成格上方）
		this.titleLabelX = 97;
		this.titleLabelY = 6;
		this.inventoryLabelX = 97 + AssistantInventoryMenu.RIGHT_PANEL_X;
		this.inventoryLabelY = 6;
	}

	/** 服务端下发的助手实体 ID（用于左侧模型渲染）。 */
	public void setAssistantEntityId(int entityId) {
		this.assistantEntityId = entityId;
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		this.xMouse = mouseX;
		this.yMouse = mouseY;
		super.render(graphics, mouseX, mouseY, partialTick);
		this.renderTooltip(graphics, mouseX, mouseY);
	}

	@Override
	protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
		int x = this.leftPos;
		int y = this.topPos;
		int rightX = x + AssistantInventoryMenu.RIGHT_PANEL_X;
		// 左右各画一次原版 inventory.png 整张（原版 InventoryScreen.renderBg 同款调用）
		graphics.blit(RenderPipelines.GUI_TEXTURED, INVENTORY_LOCATION,
				x, y, 0.0F, 0.0F, AssistantInventoryMenu.PANEL_W, PANEL_H, 256, 256);
		graphics.blit(RenderPipelines.GUI_TEXTURED, INVENTORY_LOCATION,
				rightX, y, 0.0F, 0.0F, AssistantInventoryMenu.PANEL_W, PANEL_H, 256, 256);
		// 人物模型：原版 InventoryScreen 的静态渲染方法直接调用（随鼠标转头）
		LivingEntity assistant = resolveAssistant();
		if (assistant != null) {
			InventoryScreen.renderEntityInInventoryFollowsMouse(graphics,
					x + 26, y + 8, x + 75, y + 78, 30, 0.0625F,
					this.xMouse, this.yMouse, assistant);
		}
		InventoryScreen.renderEntityInInventoryFollowsMouse(graphics,
				rightX + 26, y + 8, rightX + 75, y + 78, 30, 0.0625F,
				this.xMouse, this.yMouse, this.minecraft.player);
	}

	/**
	 * 解析左侧要渲染的助手实体（两级，尽量不丢）：
	 * 1. 服务端随打开包下发的实体 ID（准确）；
	 * 2. 兜底：准星所指实体——右键助手前玩家正对着的就是助手（界面打开期间视线冻结，
	 *    不变）；不依赖网络包时序，即使 ID 包迟到/丢失也能立刻渲染出模型，
	 *    助手身上的装备/手持物随模型一起显示（与原版 E 界面看自己一致）。
	 */
	private LivingEntity resolveAssistant() {
		if (this.assistantEntityId >= 0 && this.minecraft.level != null
				&& this.minecraft.level.getEntity(this.assistantEntityId) instanceof LivingEntity living
				&& !living.isRemoved()) {
			return living;
		}
		return this.minecraft.crosshairPickEntity instanceof LivingEntity living
				&& living != this.minecraft.player
				&& !living.isRemoved()
				? living : null;
	}

	@Override
	protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
		// 左标题：助手名；右标题：原版 E 界面的 "合成"（container.crafting）
		graphics.drawString(this.font, this.title,
				this.titleLabelX, this.titleLabelY, 0xFF404040, false);
		graphics.drawString(this.font, Component.translatable("container.crafting"),
				this.inventoryLabelX, this.inventoryLabelY, 0xFF404040, false);
	}

	/**
	 * 1.21.11 中 {@code AbstractContainerScreen.renderBackground} 负责两件事：
	 * 背景效果（游戏内模糊/半透明）+ 调用 {@code renderBg} 绘制容器纹理。
	 * 本类覆写它是为了绕开模糊着色器的 "Can only blur once per frame" 崩溃，
	 * 但必须补回 {@code renderBg} 调用——否则背景纹理永远不会绘制（整个界面透明）。
	 */
	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		this.renderTransparentBackground(graphics);
		this.renderBg(graphics, partialTick, mouseX, mouseY);
	}
}
