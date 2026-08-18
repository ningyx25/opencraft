package com.swaydy.opencraft.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.swaydy.opencraft.net.AiConfigPayloads;
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
 * 右键 AI 助手打开的“互动界面”：和【这个】助手聊天、送走它（仅主人）。
 *
 * 打开时向服务器请求该助手（按绑定方块）的对话历史，历史以 "history" 事件回传并显示在
 * 对话区（与 /opencraft ask、配置界面聊天窗口共享同一份记忆）。
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
	private boolean isOwner;
	private String model;
	private String agent;

	private EditBox chatBox;
	private Button sendButton;

	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

	// 对话区：已完成的对话行 + 正在流式回复的累积文本 + “正在思考”占位
	private final List<String> conversation = new ArrayList<>();
	private final StringBuilder streaming = new StringBuilder();
	private boolean thinking = false;
	/** 是否已请求过历史（只请求一次；重建界面不重复拉取，避免打断进行中的对话）。 */
	private boolean historyRequested = false;

	private static final int MAX_LOG_ENTRIES = 60;

	public AiAssistantInteractScreen(int entityId, String displayName,
	                                 boolean isOwner, String model, String agent,
	                                 BlockPos blockPos, ResourceKey<Level> dimension) {
		super(Component.literal(displayName));
		this.entityId = entityId;
		this.displayName = displayName;
		this.isOwner = isOwner;
		this.model = model;
		this.agent = agent;
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
	public void updateData(boolean isOwner, String model, String agent) {
		this.isOwner = isOwner;
		this.model = model;
		this.agent = agent;
		this.rebuildWidgets();
	}

	/**
	 * 处理服务器下发的聊天事件（GUI 模式 ask 的 S2C 回传；调用方已按方块坐标过滤）：
	 * - "history"：对话历史快照（[{role, content}, ...]，整体替换对话区，打开时拉取）；
	 * - "thinking"：助手开始思考（显示“正在思考…”占位）；
	 * - "delta"   ：流式增量（服务器发的是截至当前的完整快照，节流合并，因此整体替换）；
	 * - "reply"   ：流式结束的完整回复（收尾当前气泡）；
	 * - "error"   ：出错，显示错误文本。
	 */
	public void handleChatEvent(String kind, Component text) {
		switch (kind) {
			case "history" -> {
				// 历史快照：清空并整体替换对话区（含进行中的流式状态）
				this.thinking = false;
				this.streaming.setLength(0);
				this.conversation.clear();
				parseHistory(text.getString());
				trimConversation();
			}
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

		// 底部按钮栏（跟随/待命模式已整体移除，只保留送走与关闭）
		LinearLayout footer = LinearLayout.horizontal().spacing(8);
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

		// 打开时向服务器拉取该助手的历史对话（只请求一次；重建界面不重复拉取）
		if (!this.historyRequested && this.blockPos != null && this.dimension != null) {
			this.historyRequested = true;
			ClientPlayNetworking.send(new AiConfigPayloads.AiConfigChatHistoryPayload(
					this.blockPos, this.dimension));
		}
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
		// 输入框下方一行小字：模型 + Agent 预设 + 操作提示
		String modelText = this.model == null || this.model.isBlank()
				? Component.translatable("screen.opencraft.interact.no_model").getString()
				: Component.translatable("screen.opencraft.interact.model", this.model).getString();
		String agentText = Component.translatable("screen.opencraft.interact.agent",
				displayAgent(this.agent)).getString();
		String hintText = Component.translatable("screen.opencraft.interact.hint").getString();
		graphics.drawString(this.font, Component.literal(modelText), 12,
				this.height - this.layout.getFooterHeight() - 12, 0xFF9A9A9A);
		graphics.drawString(this.font, Component.literal(agentText), 12 + this.font.width(modelText) + 14,
				this.height - this.layout.getFooterHeight() - 12, 0xFF9A9A9A);
		graphics.drawString(this.font, Component.literal(hintText),
				12 + this.font.width(modelText) + 14 + this.font.width(agentText) + 14,
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

	/** 解析服务器下发的历史 JSON（[{role, content}, ...]）并填充对话区；解析失败则保持空。 */
	private void parseHistory(String json) {
		try {
			JsonArray array = JsonParser.parseString(json).getAsJsonArray();
			for (JsonElement element : array) {
				JsonObject obj = element.getAsJsonObject();
				String role = obj.has("role") ? obj.get("role").getAsString() : "user";
				String content = obj.has("content") ? obj.get("content").getAsString() : "";
				if (content == null || content.isBlank()) {
					continue;
				}
				// 用户消息加“你：”前缀，助手回复直接显示（标题栏即助手名字）
				if ("user".equals(role)) {
					this.conversation.add(youPrefix() + content);
				} else {
					this.conversation.add(content);
				}
			}
		} catch (Exception e) {
			// 历史 JSON 解析失败：保留空对话区，不打扰用户
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
		this.conversation.add(youPrefix() + message);
		this.trimConversation();
		this.streaming.setLength(0);
		this.thinking = true;
	}

	private void dismiss() {
		ClientPlayNetworking.send(new AssistantPayloads.AssistantDismissPayload(this.entityId));
		this.onClose();
	}

	/** 用户消息前缀（历史与本地回显共用）。 */
	private static String youPrefix() {
		return Component.translatable("screen.opencraft.interact.you").getString();
	}

	/** Agent 预设 id → 友好显示名。 */
	private static String displayAgent(String agentId) {
		if (agentId == null || agentId.isBlank()) {
			return Component.translatable("agent.opencraft.general").getString();
		}
		return switch (agentId) {
			case "chat_agent" -> Component.translatable("agent.opencraft.chat").getString();
			case "general_agent" -> Component.translatable("agent.opencraft.general").getString();
			default -> agentId;
		};
	}
}
