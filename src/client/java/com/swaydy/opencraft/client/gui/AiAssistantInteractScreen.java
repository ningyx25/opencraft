package com.swaydy.opencraft.client.gui;

import com.swaydy.opencraft.net.AssistantPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * 右键 AI 助手打开的“互动界面”：和【这个】助手聊天、切换跟随/待命、送走它（仅主人）。
 *
 * 聊天走 GUI 模式（{@code AiCompanionService.askGui}）：服务器的流式增量（"delta"）与
 * 完整回复（"reply"）通过 S2C {@code AiConfigChatEventPayload}（按绑定方块坐标路由）直接
 * 显示在本界面的对话区——私人会话，不广播到世界聊天。
 *
 * 界面要点与配置界面一致：透明背景（避免模糊崩溃）、底部按钮栏（HeaderAndFooterLayout）。
 */
public class AiAssistantInteractScreen extends Screen {
	private final int entityId;
	private final String displayName;
	/** 助手绑定方块的坐标：聊天回复的 S2C 事件按它路由回本界面。 */
	private final BlockPos blockPos;
	private final ResourceKey<Level> dimension;
	private boolean following;
	private boolean isOwner;
	private String model;

	private EditBox chatBox;
	private Button sendButton;
	private Button followButton;

	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

	// 对话区：已完成的对话行 + 正在流式回复的累积文本 + “正在思考”占位
	private final List<String> conversation = new ArrayList<>();
	private final StringBuilder streaming = new StringBuilder();
	private boolean thinking = false;

	private static final int MAX_LOG_ENTRIES = 60;

	public AiAssistantInteractScreen(int entityId, String displayName, boolean following,
	                                 boolean isOwner, String model,
	                                 BlockPos blockPos, ResourceKey<Level> dimension) {
		super(Component.literal(displayName));
		this.entityId = entityId;
		this.displayName = displayName;
		this.following = following;
		this.isOwner = isOwner;
		this.model = model;
		this.blockPos = blockPos;
		this.dimension = dimension;
	}

	public int getEntityId() {
		return this.entityId;
	}

	/** 该界面是否对应某个绑定方块（用于接收按方块路由的聊天事件）。 */
	public boolean matchesBlock(BlockPos pos, ResourceKey<Level> dim) {
		return this.blockPos != null && this.blockPos.equals(pos)
				&& this.dimension != null && this.dimension.equals(dim);
	}

	/** 服务器重新下发状态时刷新（当前用于打开/重建时的兜底）。 */
	public void updateData(boolean following, boolean isOwner, String model) {
		this.following = following;
		this.isOwner = isOwner;
		this.model = model;
		this.rebuildWidgets();
	}

	/**
	 * 处理服务器下发的聊天事件（GUI 模式 ask 的 S2C 回传；调用方已按方块坐标过滤）：
	 * - "thinking"：助手开始思考（显示“正在思考…”占位）；
	 * - "delta"   ：流式增量（服务器发的是截至当前的完整快照，节流合并，因此整体替换）；
	 * - "reply"   ：流式结束的完整回复（收尾当前气泡）；
	 * - "error"   ：出错，显示错误文本。
	 */
	public void handleChatEvent(String kind, Component text) {
		switch (kind) {
			case "thinking" -> {
				this.thinking = true;
				this.streaming.setLength(0);
			}
			case "delta" -> {
				this.thinking = false;
				this.streaming.setLength(0);
				// 服务器发的是截至当前的完整快照（打字机 reveal 合并），整体替换；末尾 ▍ 表示生成中
				this.streaming.append(text.getString()).append("▍");
			}
			case "reply" -> {
				this.thinking = false;
				this.streaming.setLength(0);
				String reply = text.getString();
				if (!reply.isEmpty()) {
					this.conversation.add(reply);
					trimConversation();
				}
			}
			case "error" -> {
				this.thinking = false;
				this.streaming.setLength(0);
				this.conversation.add(text.getString());
				trimConversation();
			}
			default -> { /* 忽略未知事件 */ }
		}
	}

	@Override
	protected void init() {
		// 聊天输入 + 发送按钮（位置在 repositionElements 里按窗口尺寸摆放）
		this.chatBox = new EditBox(this.font, 0, 0, 240, 20,
				Component.translatable("screen.opencraft.interact.chat"));
		this.chatBox.setMaxLength(500);
		this.chatBox.setHint(Component.translatable("screen.opencraft.interact.chat_hint"));
		this.sendButton = Button.builder(
						Component.translatable("screen.opencraft.interact.send"), b -> sendChat())
				.width(60)
				.build();
		this.addRenderableWidget(this.chatBox);
		this.addRenderableWidget(this.sendButton);

		// 底部按钮栏
		this.followButton = Button.builder(followLabel(), b -> toggleFollow())
				.width(150)
				.build();
		LinearLayout footer = LinearLayout.horizontal().spacing(8);
		footer.addChild(this.followButton);
		if (this.isOwner) {
			footer.addChild(Button.builder(
							Component.translatable("screen.opencraft.interact.dismiss"), b -> dismiss())
					.width(110)
					.build());
		}
		footer.addChild(Button.builder(
						Component.translatable("screen.opencraft.config.done"), b -> onClose())
				.width(70)
				.build());
		this.layout.addToFooter(footer);
		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
	}

	@Override
	protected void repositionElements() {
		this.layout.arrangeElements();
		// 输入行固定摆在底部按钮栏上方，给上方对话区留出空间
		int inputY = this.height - this.layout.getFooterHeight() - 34;
		int boxWidth = Math.max(120, this.width - 24 - 60 - 6);
		this.chatBox.setPosition(12, inputY);
		this.chatBox.setWidth(boxWidth);
		this.sendButton.setPosition(12 + boxWidth + 6, inputY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		// 聊天框聚焦时回车直接发送（257 = Enter，335 = 小键盘 Enter）
		if (this.chatBox != null && this.chatBox.isFocused()
				&& (event.key() == 257 || event.key() == 335)) {
			this.sendChat();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// 透明背景：屏幕背后的游戏画面仍可见
		this.renderTransparentBackground(graphics);
		super.render(graphics, mouseX, mouseY, partialTick);
		renderConversation(graphics);
		// 输入框下方一行小字：模型 + 操作提示
		String modelText = this.model == null || this.model.isBlank()
				? Component.translatable("screen.opencraft.interact.no_model").getString()
				: Component.translatable("screen.opencraft.interact.model", this.model).getString();
		String hintText = Component.translatable("screen.opencraft.interact.hint").getString();
		graphics.drawString(this.font, Component.literal(modelText), 12,
				this.height - this.layout.getFooterHeight() - 12, 0xFF9A9A9A);
		graphics.drawString(this.font, Component.literal(hintText), 12 + this.font.width(modelText) + 16,
				this.height - this.layout.getFooterHeight() - 12, 0xFF6A6A6A);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ------------------------------------------------------------------
	// 对话区渲染
	// ------------------------------------------------------------------

	private void renderConversation(GuiGraphics graphics) {
		int maxWidth = this.width - 24;
		List<FormattedCharSequence> lines = new ArrayList<>();
		for (String entry : this.conversation) {
			lines.addAll(this.font.split(Component.literal(entry), maxWidth));
		}
		if (this.thinking) {
			lines.addAll(this.font.split(
					Component.translatable("screen.opencraft.interact.thinking"), maxWidth));
		} else if (this.streaming.length() > 0) {
			// 打字机 reveal 光标：流式回复末尾加 ▍
			lines.addAll(this.font.split(Component.literal(this.streaming + "▍"), maxWidth));
		}
		// 只显示最近的若干行（对话区 = 标题下方到输入框上方）
		int inputY = this.height - this.layout.getFooterHeight() - 34;
		int maxLines = Math.max(3, (inputY - 40) / 9);
		int from = Math.max(0, lines.size() - maxLines);
		int y = 32;
		for (int i = from; i < lines.size(); i++) {
			graphics.drawString(this.font, lines.get(i), 12, y, 0xFFFFFFFF);
			y += 9;
		}
	}

	private void trimConversation() {
		if (this.conversation.size() > MAX_LOG_ENTRIES) {
			this.conversation.subList(0, this.conversation.size() - MAX_LOG_ENTRIES).clear();
		}
	}

	// ------------------------------------------------------------------
	// 交互动作（全部发到服务器，由服务器校验并执行）
	// ------------------------------------------------------------------

	private void sendChat() {
		String message = this.chatBox.getValue().trim();
		if (message.isEmpty()) {
			return;
		}
		ClientPlayNetworking.send(new AssistantPayloads.AssistantChatPayload(this.entityId, message));
		this.chatBox.setValue("");
		// 本地立即回显用户消息 + “正在思考…”占位；服务端的 thinking/delta/reply 事件随后接管
		this.conversation.add(message);
		this.trimConversation();
		this.streaming.setLength(0);
		this.thinking = true;
	}

	private void toggleFollow() {
		this.following = !this.following;
		this.followButton.setMessage(followLabel());
		ClientPlayNetworking.send(new AssistantPayloads.AssistantToggleFollowPayload(this.entityId));
	}

	private void dismiss() {
		ClientPlayNetworking.send(new AssistantPayloads.AssistantDismissPayload(this.entityId));
		this.onClose();
	}

	private Component followLabel() {
		return Component.translatable(this.following
				? "screen.opencraft.interact.following"
				: "screen.opencraft.interact.staying");
	}
}
