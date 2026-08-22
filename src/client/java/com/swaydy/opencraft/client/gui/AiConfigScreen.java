package com.swaydy.opencraft.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.swaydy.opencraft.ai.AiConfigData;
import com.swaydy.opencraft.net.AiConfigPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.layouts.CommonLayouts;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * AI 配置编辑器（现代版）：右键 AI 徽标方块后打开。
 *
 * 采用 Minecraft 1.21 原生 TabNavigationBar 标签栏导航与 HeaderAndFooterLayout 布局系统，
 * 分为“接口与密钥”、“对话与动作”、“伴侣行为”、“聊天” 4 个清晰的分页。
 *
 * “AI 功能”开关与“用本方块召唤助手”已合并为底部同一个按钮：未绑定助手时点击 =
 * 召唤（绑定本方块）；已绑定自己的助手时点击 = 送走（不召唤）。绑定状态由服务器随
 * 配置数据一起下发（AiConfigDataPayload.bound/boundByMe），每次保存/召唤/送走后刷新。
 *
 * “聊天”页是内置的对话窗口：不用 /opencraft ask 也能和本方块的助手聊天——发送消息
 * 后回复以流式增量（AiConfigChatEventPayload "delta"）实时显示在窗口里，结束以
 * "reply" 回传完整回复；没有助手绑定时发送第一条消息会自动召唤一个绑定本方块。
 * 对话历史与命令行 /opencraft ask 完全共享（同一份记忆，按方块键控）。
 *
 * 安全特性：API Key 从不在网络中明文传输，客户端输入时采用掩码格式化，仅 OP 管理员可保存生效。
 * 配置只保存在被右键的 AI 徽标方块实体里（不依赖任何外部配置文件），
 * 因此保存/召唤/聊天请求都会携带目标方块的坐标 + 维度。
 */
public class AiConfigScreen extends Screen {
	private static final int CONTROL_WIDTH = 310;
	private static final int ROW_HEIGHT = 20;

	private static final EditBox.TextFormatter MASKED = (text, cursor) ->
			FormattedCharSequence.forward("•".repeat(text.length()), Style.EMPTY);

	// 当前配置数据状态
	private AiConfigData data;
	private final boolean canEdit;
	/** 目标 AI 徽标方块（配置载体）位置。 */
	private BlockPos blockPos;
	private ResourceKey<Level> dimension;

	/** 目标方块当前是否已绑定助手 / 绑定的是否是本玩家的助手（决定合并按钮的召唤/送走状态）。 */
	private boolean blockBound;
	private boolean blockBoundByMe;

	private String baseUrl;
	private String model;
	private String language;
	private boolean changeKey;
	private String newApiKey = "";

	private double temperature;
	private int timeoutSeconds;
	private int maxHistoryMessages;
	private String name;
	private String agent;

	private double maxDistance;
	private double speed;

	// 聊天窗口状态（第 4 页“聊天”）
	/** 一条聊天记录（role: user/assistant/system）。 */
	private record ChatEntry(String role, String text) {
	}
	/** 窗口里显示的对话（与服务器按方块键控的记忆一致；rebuild 后仍保留）。 */
	private final List<ChatEntry> chatEntries = new ArrayList<>();
	/** 正在接收流式增量回复的助手气泡下标；-1 表示没有正在流式的气泡。 */
	private int chatStreamingIndex = -1;
	/** “正在思考…”占位条目的下标；-1 表示没有。 */
	private int chatThinkingIndex = -1;
	/** 已发送消息、正在等待助手回复（期间禁用输入框）。 */
	private boolean chatWaiting;
	/** 输入框草稿（rebuild 后保留）。 */
	private String chatDraft = "";
	/** 本次屏幕会话是否已请求过历史（避免 rebuild 重复请求）。 */
	private boolean chatHistoryRequested;
	/** 聊天页控件引用（tick 里更新可用状态）。 */
	private ChatLogWidget chatLogWidget;
	private ChatInputBox chatInputBox;
	private Button chatSendButton;
	private Button chatInterruptButton;

	// 布局与导航
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private final TabManager tabManager = new TabManager(this::addRenderableWidget, this::removeWidget);
	private TabNavigationBar tabNavigationBar;
	private int selectedTab = 0;

	public AiConfigScreen(AiConfigData data, boolean canEdit, boolean blockBound, boolean blockBoundByMe,
	                      BlockPos blockPos, ResourceKey<Level> dimension) {
		super(Component.translatable("screen.opencraft.config.title"));
		this.data = data;
		this.canEdit = canEdit;
		this.blockPos = blockPos;
		this.dimension = dimension;
		this.blockBound = blockBound;
		this.blockBoundByMe = blockBoundByMe;
		loadData(data);
	}

	private void loadData(AiConfigData data) {
		this.baseUrl = data.baseUrl();
		this.model = data.model();
		this.language = data.language();
		this.changeKey = !data.apiKeySet();
		this.newApiKey = "";

		this.temperature = data.temperature();
		this.timeoutSeconds = data.timeoutSeconds();
		this.maxHistoryMessages = data.maxHistoryMessages();
		this.name = data.name() == null ? "" : data.name();
		this.agent = data.agent() == null || data.agent().isBlank()
				? "general_agent" : data.agent();

		this.maxDistance = data.maxDistance();
		this.speed = data.speed();
	}

	/** Agent 预设的显示名（翻译键 → 友好文本）。 */
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

	/** 服务器返回新数据时刷新界面 */
	public void updateData(AiConfigData data, boolean canEdit, boolean blockBound, boolean blockBoundByMe,
	                       BlockPos blockPos, ResourceKey<Level> dimension) {
		// 配置方块变了（右键了另一个方块）：聊天窗口要清空并重新拉取新方块的历史
		if (!blockPos.equals(this.blockPos) || !dimension.equals(this.dimension)) {
			this.chatEntries.clear();
			this.chatStreamingIndex = -1;
			this.chatThinkingIndex = -1;
			this.chatWaiting = false;
			this.chatHistoryRequested = false;
		}
		this.blockPos = blockPos;
		this.dimension = dimension;
		this.blockBound = blockBound;
		this.blockBoundByMe = blockBoundByMe;
		if (this.tabNavigationBar != null && this.tabManager.getCurrentTab() != null) {
			int idx = this.tabNavigationBar.getTabs().indexOf(this.tabManager.getCurrentTab());
			if (idx >= 0) {
				this.selectedTab = idx;
			}
		}
		this.data = data;
		loadData(data);
		if (this.minecraft != null) {
			SystemToast.addOrUpdate(
					this.minecraft.getToastManager(),
					SystemToast.SystemToastId.NARRATOR_TOGGLE,
					Component.translatable("screen.opencraft.config.toast.saved.title"),
					Component.translatable("screen.opencraft.config.toast.saved.desc")
			);
		}
		this.rebuildWidgets();
	}

	@Override
	protected void init() {
		// 1. 创建 4 个 Tab 分页
		EndpointKeyTab endpointTab = new EndpointKeyTab();
		ChatActionsTab chatTab = new ChatActionsTab();
		CompanionBehaviorTab companionTab = new CompanionBehaviorTab();
		ChatWindowTab chatWindowTab = new ChatWindowTab();

		// 2. 创建顶部 TabNavigationBar
		this.tabNavigationBar = TabNavigationBar.builder(this.tabManager, this.width)
				.addTabs(new Tab[]{endpointTab, chatTab, companionTab, chatWindowTab})
				.build();
		this.addRenderableWidget(this.tabNavigationBar);

		// 3. 构建底部按钮栏
		LinearLayout footerLayout = LinearLayout.horizontal().spacing(8);

		// 合并按钮：“AI 功能”开关与“用本方块召唤助手”已合并为一个按钮——
		// 未绑定助手 → 点击“用本方块召唤助手”；已绑定自己的助手 → 点击“送走本方块助手”（不召唤）；
		// 已被他人助手绑定 → 按钮禁用并提示。服务器每次返回数据都会刷新这里的绑定状态。
		Component toggleLabel;
		Component toggleTooltip;
		boolean toggleActive;
		if (!this.blockBound) {
			toggleLabel = Component.translatable("screen.opencraft.config.summon_block");
			toggleTooltip = Component.translatable("screen.opencraft.config.summon_block.tooltip");
			toggleActive = true;
		} else if (this.blockBoundByMe) {
			toggleLabel = Component.translatable("screen.opencraft.config.dismiss_block");
			toggleTooltip = Component.translatable("screen.opencraft.config.dismiss_block.tooltip");
			toggleActive = true;
		} else {
			toggleLabel = Component.translatable("screen.opencraft.config.block_bound_other");
			toggleTooltip = Component.translatable("screen.opencraft.config.block_bound_other.tooltip");
			toggleActive = false;
		}
		Button summonButton = Button.builder(toggleLabel, b -> {
					if (this.blockBoundByMe) {
						dismissWithBlock();
					} else {
						summonWithBlock();
					}
				})
				.width(150)
				.tooltip(Tooltip.create(toggleTooltip))
				.build();
		summonButton.active = toggleActive;

		Button saveButton = Button.builder(Component.translatable("screen.opencraft.config.save"), b -> save())
				.width(150)
				.tooltip(Tooltip.create(Component.translatable(this.canEdit
						? "screen.opencraft.config.save.tooltip"
						: "screen.opencraft.config.no_permission_hint")))
				.build();
		saveButton.active = this.canEdit;

		Button doneButton = Button.builder(Component.translatable("screen.opencraft.config.done"), b -> onClose())
				.width(150)
				.build();

		footerLayout.addChild(summonButton);
		footerLayout.addChild(saveButton);
		footerLayout.addChild(doneButton);

		this.layout.addToFooter(footerLayout);
		this.layout.visitWidgets(this::addRenderableWidget);

		// 4. 选择当前 Tab 并调整位置
		this.tabNavigationBar.selectTab(this.selectedTab, false);
		this.repositionElements();

		// 5. 首次打开时向服务器要一次本方块助手的对话历史（填充聊天窗口）
		this.requestChatHistory();
	}

	@Override
	public void tick() {
		super.tick();
		// 等待回复或他人助手占用时禁用聊天输入
		if (this.chatInputBox != null) {
			boolean canChat = canChat();
			this.chatInputBox.setEditable(canChat);
			if (!canChat && this.chatInputBox.isFocused()) {
				this.chatInputBox.setFocused(false);
			}
		}
		if (this.chatSendButton != null) {
			this.chatSendButton.active = canChat();
		}
		if (this.chatInterruptButton != null) {
			// 「中断」在“思考/工具轮次/流式回复”任一阶段可用（卡住时可中止并重新提问）
			this.chatInterruptButton.active = this.chatWaiting || this.chatStreamingIndex >= 0;
		}
		if (this.chatLogWidget != null) {
			this.chatLogWidget.refreshScroll();
		}
	}

	@Override
	protected void repositionElements() {
		if (this.tabNavigationBar != null) {
			this.tabNavigationBar.setWidth(this.width);
			this.tabNavigationBar.arrangeElements();
			int headerBottom = this.tabNavigationBar.getRectangle().bottom();
			ScreenRectangle tabArea = new ScreenRectangle(
					0,
					headerBottom,
					this.width,
					this.height - this.layout.getFooterHeight() - headerBottom
			);
			this.tabManager.setTabArea(tabArea);
			this.layout.setHeaderHeight(headerBottom);
			this.layout.arrangeElements();
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (this.tabNavigationBar != null && this.tabNavigationBar.keyPressed(event)) {
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		// 使用透明背景，避免模糊着色器崩溃
		this.renderTransparentBackground(graphics);
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private void save() {
		if (!this.canEdit) {
			return;
		}
		AiConfigData configToSave = new AiConfigData(
				this.baseUrl,
				this.changeKey ? this.newApiKey : "",
				this.changeKey,
				this.data.apiKeySet(),
				this.model,
				this.temperature,
				this.maxHistoryMessages,
				this.timeoutSeconds,
				this.language,
				this.maxDistance,
				this.speed,
				this.name,
				this.agent
		);
		ClientPlayNetworking.send(new AiConfigPayloads.AiConfigSavePayload(
				configToSave.toJson(), this.blockPos, this.dimension));
	}

	/** 合并按钮的“召唤”半：用当前方块召唤（或重新绑定）AI 助手，助手将使用本方块的配置。 */
	private void summonWithBlock() {
		ClientPlayNetworking.send(new AiConfigPayloads.AiConfigSummonPayload(
				this.blockPos, this.dimension));
	}

	/** 合并按钮的“不召唤”半：送走绑定在本方块的 AI 助手（取消召唤）。 */
	private void dismissWithBlock() {
		ClientPlayNetworking.send(new AiConfigPayloads.AiConfigDismissPayload(
				this.blockPos, this.dimension));
	}

	// =========================================================================
	// 聊天窗口（第 4 页）
	// =========================================================================

	/** 当前是否能聊天：没有正在等待的回复，且本方块不是“被他人助手占用”状态。 */
	private boolean canChat() {
		return !this.chatWaiting && !(this.blockBound && !this.blockBoundByMe);
	}

	/**
	 * 处理服务器下发的聊天窗口事件（S2C AiConfigChatEventPayload）。
	 * 事件携带目标方块坐标，只有与当前配置方块一致才生效（防止过期/错位事件）。
	 */
	public void handleChatEvent(String kind, Component text, BlockPos pos, ResourceKey<Level> dimension) {
		if (!pos.equals(this.blockPos) || !dimension.equals(this.dimension)) {
			return;
		}
		switch (kind) {
			case "history" -> {
				// 服务器把完整历史发来：整体替换窗口内容
				this.chatEntries.clear();
				this.chatStreamingIndex = -1;
				this.chatThinkingIndex = -1;
				this.chatWaiting = false;
				parseHistoryJson(text.getString());
				scrollChatToBottom();
			}
			case "thinking" -> {
				if (this.chatThinkingIndex < 0) {
					this.chatEntries.add(new ChatEntry("system",
							Component.translatable("screen.opencraft.config.chat.thinking").getString()));
					this.chatThinkingIndex = this.chatEntries.size() - 1;
					this.chatWaiting = true;
					scrollChatToBottom();
				}
			}
			case "delta" -> {
				removeChatThinkingPlaceholder();
				this.chatWaiting = false;
				// 服务器每次“delta”发来的是截至当前的完整快照（节流/打字机 reveal 合并），
				// 因此用替换而非追加；末尾加 ▍ 光标提示“正在生成中”
				String snapshot = text.getString();
				if (snapshot.isEmpty()) {
					return;
				}
				String display = snapshot + "▍";
				if (this.chatStreamingIndex < 0 || this.chatStreamingIndex >= this.chatEntries.size()) {
					this.chatEntries.add(new ChatEntry("assistant", display));
					this.chatStreamingIndex = this.chatEntries.size() - 1;
				} else {
					this.chatEntries.set(this.chatStreamingIndex, new ChatEntry("assistant", display));
				}
				scrollChatToBottom();
			}
			case "reply" -> {
				removeChatThinkingPlaceholder();
				this.chatWaiting = false;
				String reply = text.getString();
				if (this.chatStreamingIndex >= 0 && this.chatStreamingIndex < this.chatEntries.size()) {
					this.chatEntries.set(this.chatStreamingIndex, new ChatEntry("assistant", reply));
				} else {
					this.chatEntries.add(new ChatEntry("assistant", reply));
				}
				this.chatStreamingIndex = -1;
				scrollChatToBottom();
			}
			case "error" -> {
				removeChatThinkingPlaceholder();
				this.chatStreamingIndex = -1;
				this.chatWaiting = false;
				this.chatEntries.add(new ChatEntry("system", text.getString()));
				scrollChatToBottom();
			}
			default -> { /* 未知事件：忽略 */ }
		}
	}

	/** 发送一条聊天消息（本方块助手）；没有助手绑定时服务器会自动召唤一个。 */
	private void sendChatMessage() {
		String message = this.chatDraft.trim();
		if (message.isEmpty() || !canChat()) {
			return;
		}
		this.chatDraft = "";
		if (this.chatInputBox != null) {
			this.chatInputBox.setValue("");
		}
		this.chatEntries.add(new ChatEntry("user", message));
		this.chatWaiting = true;
		scrollChatToBottom();
		ClientPlayNetworking.send(new AiConfigPayloads.AiConfigChatPayload(
				message, this.blockPos, this.dimension));
	}

	/** 请求本方块助手的对话历史（每个屏幕会话只请求一次）。 */
	private void requestChatHistory() {
		if (this.chatHistoryRequested) {
			return;
		}
		this.chatHistoryRequested = true;
		ClientPlayNetworking.send(new AiConfigPayloads.AiConfigChatHistoryPayload(
				this.blockPos, this.dimension));
	}

	/**
	 * 「中断」按钮：中止本方块助手正在进行的任务（卡住时可立即重新提问）。
	 * 本地先复位“等待”态（输入框立刻可用），服务器中断后回传 "reply"（已中断）上屏。
	 */
	private void sendInterrupt() {
		if (!this.chatWaiting && this.chatStreamingIndex < 0) {
			return;
		}
		this.chatWaiting = false;
		removeChatThinkingPlaceholder();
		this.chatStreamingIndex = -1;
		ClientPlayNetworking.send(new AiConfigPayloads.AiConfigInterruptPayload(
				this.blockPos, this.dimension));
	}

	/** 解析服务器下发的历史 JSON（[{role, content}, ...]）并填充窗口。 */
	private void parseHistoryJson(String json) {
		try {
			JsonArray array = JsonParser.parseString(json).getAsJsonArray();
			for (JsonElement element : array) {
				JsonObject obj = element.getAsJsonObject();
				String role = obj.has("role") ? obj.get("role").getAsString() : "user";
				String content = obj.has("content") ? obj.get("content").getAsString() : "";
				this.chatEntries.add(new ChatEntry(role, content));
			}
		} catch (Exception e) {
			// 历史 JSON 解析失败：保留空窗口，不打扰用户
		}
	}

	/** 若“正在思考…”占位还在，把它移除（首个增量/回复/错误到达时）。 */
	private void removeChatThinkingPlaceholder() {
		if (this.chatThinkingIndex >= 0 && this.chatThinkingIndex < this.chatEntries.size()) {
			ChatEntry entry = this.chatEntries.get(this.chatThinkingIndex);
			if ("system".equals(entry.role())) {
				this.chatEntries.remove(this.chatThinkingIndex);
				if (this.chatStreamingIndex > this.chatThinkingIndex) {
					this.chatStreamingIndex--;
				}
			}
		}
		this.chatThinkingIndex = -1;
	}

	/** 让聊天记录滚动到底部（新消息/新回复到达时）。 */
	private void scrollChatToBottom() {
		if (this.chatLogWidget != null) {
			this.chatLogWidget.scrollToBottom();
		}
	}

	// =========================================================================
	// Tab 1: 接口与密钥
	// =========================================================================
	private class EndpointKeyTab extends GridLayoutTab {
		public EndpointKeyTab() {
			super(Component.translatable("screen.opencraft.config.tab.endpoint"));
			GridLayout.RowHelper rows = this.layout.createRowHelper(1);
			rows.defaultCellSetting().paddingVertical(2).alignHorizontallyCenter();

			Font font = AiConfigScreen.this.font;

			// 接口地址 Base URL
			EditBox baseUrlBox = new EditBox(font, 0, 0, CONTROL_WIDTH, ROW_HEIGHT,
					Component.translatable("screen.opencraft.config.base_url"));
			baseUrlBox.setMaxLength(500);
			baseUrlBox.setValue(AiConfigScreen.this.baseUrl);
			baseUrlBox.setHint(Component.translatable("screen.opencraft.config.base_url_hint"));
			baseUrlBox.setTooltip(Tooltip.create(Component.translatable("screen.opencraft.config.base_url.tooltip")));
			baseUrlBox.setEditable(AiConfigScreen.this.canEdit);
			baseUrlBox.setResponder(s -> AiConfigScreen.this.baseUrl = s.trim());
			rows.addChild(CommonLayouts.labeledElement(font, baseUrlBox, Component.translatable("screen.opencraft.config.base_url")));

			// 模型名称 Model
			EditBox modelBox = new EditBox(font, 0, 0, CONTROL_WIDTH, ROW_HEIGHT,
					Component.translatable("screen.opencraft.config.model"));
			modelBox.setMaxLength(100);
			modelBox.setValue(AiConfigScreen.this.model);
			modelBox.setHint(Component.translatable("screen.opencraft.config.model_hint"));
			modelBox.setTooltip(Tooltip.create(Component.translatable("screen.opencraft.config.model.tooltip")));
			modelBox.setEditable(AiConfigScreen.this.canEdit);
			modelBox.setResponder(s -> AiConfigScreen.this.model = s.trim());
			rows.addChild(CommonLayouts.labeledElement(font, modelBox, Component.translatable("screen.opencraft.config.model")));

			// 语言代码 Language
			EditBox langBox = new EditBox(font, 0, 0, CONTROL_WIDTH, ROW_HEIGHT,
					Component.translatable("screen.opencraft.config.language"));
			langBox.setMaxLength(20);
			langBox.setValue(AiConfigScreen.this.language);
			langBox.setHint(Component.translatable("screen.opencraft.config.language_hint"));
			langBox.setTooltip(Tooltip.create(Component.translatable("screen.opencraft.config.language.tooltip")));
			langBox.setEditable(AiConfigScreen.this.canEdit);
			langBox.setResponder(s -> AiConfigScreen.this.language = s.trim());
			rows.addChild(CommonLayouts.labeledElement(font, langBox, Component.translatable("screen.opencraft.config.language")));

			// API Key 管理行
			boolean keySet = AiConfigScreen.this.data.apiKeySet();
			EditBox keyBox = new EditBox(font, 0, 0, CONTROL_WIDTH, ROW_HEIGHT,
					Component.translatable("screen.opencraft.config.api_key"));
			keyBox.setMaxLength(500);
			keyBox.addFormatter(MASKED);
			keyBox.setHint(Component.translatable("screen.opencraft.config.key_hint"));
			keyBox.setTooltip(Tooltip.create(Component.translatable("screen.opencraft.config.api_key.tooltip")));
			keyBox.setValue(AiConfigScreen.this.newApiKey);
			keyBox.setEditable(AiConfigScreen.this.canEdit && (!keySet || AiConfigScreen.this.changeKey));
			keyBox.setResponder(s -> AiConfigScreen.this.newApiKey = s.trim());

			if (keySet && AiConfigScreen.this.canEdit) {
				LinearLayout keyHeader = LinearLayout.horizontal().spacing(8);
				keyHeader.addChild(new StringWidget(Component.translatable("screen.opencraft.config.api_key")
						.append(" (").append(Component.translatable("screen.opencraft.config.key_set")).append(")"), font));
				Checkbox changeKeyCheck = Checkbox.builder(Component.translatable("screen.opencraft.config.change_key"), font)
						.selected(AiConfigScreen.this.changeKey)
						.onValueChange((c, checked) -> {
							AiConfigScreen.this.changeKey = checked;
							keyBox.setEditable(checked);
							if (!checked) {
								keyBox.setValue("");
								AiConfigScreen.this.newApiKey = "";
							}
						})
						.build();
				changeKeyCheck.setTooltip(Tooltip.create(Component.translatable("screen.opencraft.config.change_key.tooltip")));
				keyHeader.addChild(changeKeyCheck);

				LinearLayout keyGroup = LinearLayout.vertical().spacing(4);
				keyGroup.addChild(keyHeader);
				keyGroup.addChild(keyBox);
				rows.addChild(keyGroup);
			} else {
				String keyStatus = keySet
						? "screen.opencraft.config.key_set"
						: "screen.opencraft.config.key_not_set";
				rows.addChild(CommonLayouts.labeledElement(font, keyBox,
						Component.translatable("screen.opencraft.config.api_key")
								.append(" (").append(Component.translatable(keyStatus)).append(")")));
			}
		}
	}

	// =========================================================================
	// Tab 2: 对话与动作
	// =========================================================================
	private class ChatActionsTab extends GridLayoutTab {
		public ChatActionsTab() {
			super(Component.translatable("screen.opencraft.config.tab.chat"));
			GridLayout.RowHelper rows = this.layout.createRowHelper(1);
			rows.defaultCellSetting().paddingVertical(2).alignHorizontallyCenter();

			Font font = AiConfigScreen.this.font;

			// 助手名字（显示名 / 聊天前缀，也用于让模型用这个名字自称）
			EditBox nameBox = new EditBox(font, 0, 0, CONTROL_WIDTH, ROW_HEIGHT,
					Component.translatable("screen.opencraft.config.name"));
			nameBox.setMaxLength(50);
			nameBox.setValue(AiConfigScreen.this.name);
			nameBox.setHint(Component.translatable("screen.opencraft.config.name_hint"));
			nameBox.setTooltip(Tooltip.create(Component.translatable("screen.opencraft.config.name.tooltip")));
			nameBox.setEditable(AiConfigScreen.this.canEdit);
			nameBox.setResponder(s -> AiConfigScreen.this.name = s.trim());
			rows.addChild(CommonLayouts.labeledElement(font, nameBox,
					Component.translatable("screen.opencraft.config.name")));

			// Agent 预设下拉（只决定 LLM 行为 = 人设/工具/轮数；身体形态与预设无关——
			// 助手一律是真正的 ServerPlayer bot，像客户端一样进服）
			CycleButton<String> agentPicker = CycleButton.<String>builder(
							val -> Component.literal(displayAgent(val)), AiConfigScreen.this.agent)
					.withValues(List.of("chat_agent", "general_agent"))
					.withTooltip(val -> Tooltip.create(Component.translatable("screen.opencraft.config.agent.tooltip")))
					.create(0, 0, CONTROL_WIDTH, ROW_HEIGHT,
							Component.translatable("screen.opencraft.config.agent"),
							(btn, val) -> AiConfigScreen.this.agent = val);
			agentPicker.active = AiConfigScreen.this.canEdit;
			rows.addChild(CommonLayouts.labeledElement(font, agentPicker,
					Component.translatable("screen.opencraft.config.agent")));

			// 温度滑块 (0.0 ~ 2.0)
			NumericSliderButton tempSlider = new NumericSliderButton(
					0, 0, CONTROL_WIDTH, ROW_HEIGHT,
					Component.translatable("screen.opencraft.config.temperature"),
					AiConfigScreen.this.temperature, 0.0, 2.0, 0.05, 2, "",
					val -> AiConfigScreen.this.temperature = val
			);
			tempSlider.setTooltip(Tooltip.create(Component.translatable("screen.opencraft.config.temperature.tooltip")));
			tempSlider.active = AiConfigScreen.this.canEdit;
			rows.addChild(tempSlider);

			// 超时时间滑块 (5s ~ 120s)
			NumericSliderButton timeoutSlider = new NumericSliderButton(
					0, 0, CONTROL_WIDTH, ROW_HEIGHT,
					Component.translatable("screen.opencraft.config.timeout"),
					AiConfigScreen.this.timeoutSeconds, 5, 120, 1, 0, "s",
					val -> AiConfigScreen.this.timeoutSeconds = val.intValue()
			);
			timeoutSlider.setTooltip(Tooltip.create(Component.translatable("screen.opencraft.config.timeout.tooltip")));
			timeoutSlider.active = AiConfigScreen.this.canEdit;
			rows.addChild(timeoutSlider);

			// 历史记忆消息条数 (0 ~ 50 条)
			NumericSliderButton historySlider = new NumericSliderButton(
					0, 0, CONTROL_WIDTH, ROW_HEIGHT,
					Component.translatable("screen.opencraft.config.max_history"),
					AiConfigScreen.this.maxHistoryMessages, 0, 50, 1, 0, " 条",
					val -> AiConfigScreen.this.maxHistoryMessages = val.intValue()
			);
			historySlider.setTooltip(Tooltip.create(Component.translatable("screen.opencraft.config.max_history.tooltip")));
			historySlider.active = AiConfigScreen.this.canEdit;
			rows.addChild(historySlider);
		}
	}

	// =========================================================================
	// Tab 3: 行动行为（跟随/待命模式已移除，仅剩行动范围与速度）
	// =========================================================================
	private class CompanionBehaviorTab extends GridLayoutTab {
		public CompanionBehaviorTab() {
			super(Component.translatable("screen.opencraft.config.tab.companion"));
			GridLayout.RowHelper rows = this.layout.createRowHelper(1);
			rows.defaultCellSetting().paddingVertical(2).alignHorizontallyCenter();

			// 跟随/待命模式已整体移除：不再有跟随/停止/瞬移距离滑块
			// 走丢最大距离 (10.0 ~ 128.0 格)
			NumericSliderButton maxSlider = new NumericSliderButton(
					0, 0, CONTROL_WIDTH, ROW_HEIGHT,
					Component.translatable("screen.opencraft.config.max_distance"),
					AiConfigScreen.this.maxDistance, 10.0, 128.0, 1.0, 1, " 格",
					val -> AiConfigScreen.this.maxDistance = val
			);
			maxSlider.setTooltip(Tooltip.create(Component.translatable("screen.opencraft.config.max_distance.tooltip")));
			maxSlider.active = AiConfigScreen.this.canEdit;
			rows.addChild(maxSlider);

			// 移动速度倍率 (0.5x ~ 2.5x)
			NumericSliderButton speedSlider = new NumericSliderButton(
					0, 0, CONTROL_WIDTH, ROW_HEIGHT,
					Component.translatable("screen.opencraft.config.speed"),
					AiConfigScreen.this.speed, 0.5, 2.5, 0.05, 2, "x",
					val -> AiConfigScreen.this.speed = val
			);
			speedSlider.setTooltip(Tooltip.create(Component.translatable("screen.opencraft.config.speed.tooltip")));
			speedSlider.active = AiConfigScreen.this.canEdit;
			rows.addChild(speedSlider);
		}
	}

	// =========================================================================
	// Tab 4: 聊天窗口
	// =========================================================================

	/** 聊天页：滚动对话记录 + 输入框 + 发送按钮（自定义 Tab，填满标签区域）。 */
	private class ChatWindowTab implements Tab {
		private final ChatLogWidget log;
		private final ChatInputBox input;
		private final Button sendButton;
		private final Button interruptButton;

		public ChatWindowTab() {
			this.log = new ChatLogWidget(0, 0, 100, 100, AiConfigScreen.this);
			this.input = new ChatInputBox(AiConfigScreen.this.font, 0, 0, 100, 20,
					Component.translatable("screen.opencraft.config.chat.input"));
			this.input.setMaxLength(500);
			this.input.setValue(AiConfigScreen.this.chatDraft);
			this.input.setResponder(s -> AiConfigScreen.this.chatDraft = s);
			this.input.setHint(Component.translatable("screen.opencraft.config.chat.input_hint"));
			this.input.setCanLoseFocus(true);
			this.sendButton = Button.builder(
					Component.translatable("screen.opencraft.config.chat.send"),
					b -> AiConfigScreen.this.sendChatMessage())
					.size(60, 20)
					.build();
			// 「中断」：只有正在等待回复时才可用（卡住时可立即中止并重新提问）
			this.interruptButton = Button.builder(
					Component.translatable("gui.opencraft.interrupt"),
					b -> AiConfigScreen.this.sendInterrupt())
					.size(46, 20)
					.build();
			this.interruptButton.active = false;
			AiConfigScreen.this.chatLogWidget = this.log;
			AiConfigScreen.this.chatInputBox = this.input;
			AiConfigScreen.this.chatSendButton = this.sendButton;
			AiConfigScreen.this.chatInterruptButton = this.interruptButton;
		}

		@Override
		public Component getTabTitle() {
			return Component.translatable("screen.opencraft.config.tab.chat_window");
		}

		@Override
		public Component getTabExtraNarration() {
			return Component.empty();
		}

		@Override
		public void visitChildren(Consumer<AbstractWidget> consumer) {
			consumer.accept(this.log);
			consumer.accept(this.input);
			consumer.accept(this.sendButton);
			consumer.accept(this.interruptButton);
		}

		@Override
		public void doLayout(ScreenRectangle rectangle) {
			int left = rectangle.left() + 8;
			int right = rectangle.right() - 8;
			int bottom = rectangle.bottom() - 8;
			int inputHeight = 20;
			int inputY = bottom - inputHeight;
			int sendWidth = 60;
			int interruptWidth = 46;

			this.sendButton.setPosition(right - sendWidth, inputY);
			this.sendButton.setWidth(sendWidth);
			this.sendButton.setHeight(inputHeight);

			// 中断按钮紧挨发送按钮左侧
			this.interruptButton.setPosition(right - sendWidth - interruptWidth - 6, inputY);
			this.interruptButton.setWidth(interruptWidth);
			this.interruptButton.setHeight(inputHeight);

			this.input.setPosition(left, inputY);
			this.input.setWidth(Math.max(0, right - sendWidth - interruptWidth - 12 - left));
			this.input.setHeight(inputHeight);

			this.log.setPosition(left, rectangle.top() + 8);
			this.log.setWidth(Math.max(0, right - left));
			this.log.setHeight(Math.max(0, inputY - 6 - (rectangle.top() + 8)));
		}
	}

	/** 聊天记录滚动区域：多行自动换行渲染 + 滚轮滚动 + 自动贴底。 */
	private class ChatLogWidget extends AbstractWidget {
		private static final int PADDING = 6;
		private static final int ENTRY_GAP = 6;
		private static final int LINE_GAP = 2;
		private static final int SCROLL_STEP_MULTIPLIER = 2;
		private static final int COLOR_USER = 0xFFFFFFFF;
		private static final int COLOR_ASSISTANT = 0xFF7FE08A;
		private static final int COLOR_SYSTEM = 0xFFAAAAAA;
		private static final int COLOR_BODY = 0xFFE6E6E6;

		private final AiConfigScreen screen;
		/** 已向下滚动的像素数。 */
		private int scrollOffset;
		/** 贴底：内容增长时自动滚到底部；用户向上滚动后取消。 */
		private boolean stickToBottom = true;

		ChatLogWidget(int x, int y, int width, int height, AiConfigScreen screen) {
			super(x, y, width, height, Component.empty());
			this.screen = screen;
		}

		@Override
		protected void updateWidgetNarration(
				net.minecraft.client.gui.narration.NarrationElementOutput narrationElementOutput) {
			// 聊天记录内容多变且可能很长，不做逐条旁白
			this.defaultButtonNarrationText(narrationElementOutput);
		}

		@Override
		protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			// 背景
			graphics.fill(this.getX(), this.getY(),
					this.getX() + this.getWidth(), this.getY() + this.getHeight(), 0x66000000);
			// 边框
			graphics.hLine(this.getX(), this.getX() + this.getWidth(), this.getY(), 0xFF3A3A3A);
			graphics.hLine(this.getX(), this.getX() + this.getWidth(), this.getY() + this.getHeight() - 1, 0xFF3A3A3A);
			graphics.vLine(this.getX(), this.getY(), this.getY() + this.getHeight(), 0xFF3A3A3A);
			graphics.vLine(this.getX() + this.getWidth() - 1, this.getY(), this.getY() + this.getHeight(), 0xFF3A3A3A);

			int contentWidth = Math.max(1, this.getWidth() - PADDING * 2);
			if (this.screen.chatEntries.isEmpty()) {
				String hint = Component.translatable("screen.opencraft.config.chat.empty").getString();
				int hintWidth = this.screen.font.width(hint);
				graphics.drawString(this.screen.font, hint,
						this.getX() + (this.getWidth() - hintWidth) / 2,
						this.getY() + this.getHeight() / 2 - this.screen.font.lineHeight / 2,
						COLOR_SYSTEM);
				return;
			}

			// 贴底时随内容增长自动滚到底
			if (this.stickToBottom) {
				this.scrollOffset = maxScroll();
			}

			graphics.enableScissor(this.getX(), this.getY(),
					this.getX() + this.getWidth(), this.getY() + this.getHeight());
			int y = this.getY() + PADDING - this.scrollOffset;
			int top = this.getY();
			int bottom = this.getY() + this.getHeight();
			Font font = this.screen.font;
			for (ChatEntry entry : this.screen.chatEntries) {
				int entryHeight = entryHeight(entry, contentWidth);
				if (y + entryHeight < top) {
					y += entryHeight + ENTRY_GAP;
					continue; // 完全在可视区上方：跳过
				}
				Component prefix = rolePrefix(entry.role());
				int prefixWidth = font.width(prefix);
				int color = roleColor(entry.role());
				for (FormattedCharSequence line : font.split(
						Component.literal(entry.text()), contentWidth - prefixWidth - 2)) {
					if (y >= top && y + font.lineHeight <= bottom) {
						graphics.drawString(font, prefix, this.getX() + PADDING, y, color);
						graphics.drawString(font, line, this.getX() + PADDING + prefixWidth, y, COLOR_BODY);
					}
					y += font.lineHeight + LINE_GAP;
				}
				y += ENTRY_GAP;
			}
			graphics.disableScissor();
		}

		/** 某条记录渲染后的总高度（换行后的行数 × 行高 + 行距）。 */
		private int entryHeight(ChatEntry entry, int contentWidth) {
			Font font = this.screen.font;
			Component prefix = rolePrefix(entry.role());
			int bodyWidth = Math.max(1, contentWidth - font.width(prefix) - 2);
			int lines = Math.max(1, font.split(Component.literal(entry.text()), bodyWidth).size());
			return lines * (font.lineHeight + LINE_GAP);
		}

		/** 全部记录的总高度（含上下内边距）。 */
		private int contentHeight() {
			int contentWidth = Math.max(1, this.getWidth() - PADDING * 2);
			int total = PADDING;
			for (ChatEntry entry : this.screen.chatEntries) {
				total += entryHeight(entry, contentWidth) + ENTRY_GAP;
			}
			return total;
		}

		private int maxScroll() {
			return Math.max(0, contentHeight() - this.getHeight());
		}

		private Component rolePrefix(String role) {
			return switch (role) {
				case "user" -> Component.literal("你 > ");
				case "assistant" -> Component.literal(this.name() + " > ");
				default -> Component.empty();
			};
		}

		private int roleColor(String role) {
			return switch (role) {
				case "user" -> COLOR_USER;
				case "assistant" -> COLOR_ASSISTANT;
				default -> COLOR_SYSTEM;
			};
		}

		@Override
		public boolean mouseScrolled(double mouseX, double mouseY,
		                            double horizontalAmount, double verticalAmount) {
			if (this.isMouseOver(mouseX, mouseY) && this.visible) {
				int step = this.screen.font.lineHeight * SCROLL_STEP_MULTIPLIER;
				int target = this.scrollOffset + (verticalAmount > 0 ? -step : step);
				this.scrollOffset = Math.max(0, Math.min(target, maxScroll()));
				this.stickToBottom = this.scrollOffset >= maxScroll();
				return true;
			}
			return false;
		}

		@Override
		public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean bl) {
			if (this.isMouseOver(event.x(), event.y())) {
				// 点聊天记录区域：焦点还给输入框，方便接着打字
				if (AiConfigScreen.this.chatInputBox != null) {
					AiConfigScreen.this.chatInputBox.setFocused(true);
				}
				return true;
			}
			return false;
		}

		/** 滚到底部（新消息/新回复时调用）。 */
		public void scrollToBottom() {
			this.stickToBottom = true;
			this.scrollOffset = maxScroll();
		}

		/** 每 tick 调用：贴底时跟随内容增长（流式回复逐字出现）。 */
		public void refreshScroll() {
			if (this.stickToBottom) {
				this.scrollOffset = maxScroll();
			}
		}

		/** 聊天页的助手名字（用于气泡前缀）。 */
		private String name() {
			return this.screen.name == null || this.screen.name.isBlank()
					? "小智" : this.screen.name.trim();
		}
	}

	/** 聊天输入框：回车（Enter / 小键盘 Enter）直接发送。 */
	private class ChatInputBox extends EditBox {
		public ChatInputBox(Font font, int x, int y, int width, int height, Component label) {
			super(font, x, y, width, height, label);
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			int key = event.key();
			if (key == 257 || key == 335) { // GLFW Enter / KP_Enter
				AiConfigScreen.this.sendChatMessage();
				return true;
			}
			return super.keyPressed(event);
		}
	}

	// =========================================================================
	// 数值滑块组件 (基于 AbstractSliderButton)
	// =========================================================================
	private static class NumericSliderButton extends AbstractSliderButton {
		private final double min;
		private final double max;
		private final double step;
		private final int decimals;
		private final String suffix;
		private final Component prefix;
		private final Consumer<Double> onValueChange;

		public NumericSliderButton(int x, int y, int width, int height,
		                           Component prefix, double current,
		                           double min, double max, double step, int decimals,
		                           String suffix, Consumer<Double> onValueChange) {
			super(x, y, width, height, Component.empty(), toNormalized(current, min, max));
			this.prefix = prefix;
			this.min = min;
			this.max = max;
			this.step = step;
			this.decimals = decimals;
			this.suffix = suffix;
			this.onValueChange = onValueChange;
			this.updateMessage();
		}

		private static double toNormalized(double current, double min, double max) {
			if (max <= min) return 0.0;
			return Math.clamp((current - min) / (max - min), 0.0, 1.0);
		}

		public double getActualValue() {
			double raw = this.min + this.value * (this.max - this.min);
			if (this.step > 0) {
				raw = Math.round(raw / this.step) * this.step;
			}
			return Math.clamp(raw, this.min, this.max);
		}

		@Override
		protected void updateMessage() {
			double val = getActualValue();
			String valStr = this.decimals == 0
					? String.valueOf((long) Math.round(val))
					: String.format(Locale.ROOT, "%." + this.decimals + "f", val);
			this.setMessage(Component.empty().append(this.prefix).append(": ").append(Component.literal(valStr + this.suffix)));
		}

		@Override
		protected void applyValue() {
			if (this.onValueChange != null) {
				this.onValueChange.accept(getActualValue());
			}
		}
	}
}
