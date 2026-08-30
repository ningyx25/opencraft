package com.swaydy.opencraft.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.swaydy.opencraft.agent.AgentDefinition;
import com.swaydy.opencraft.agent.AgentRegistry;
import com.swaydy.opencraft.ai.AiConfigData;
import com.swaydy.opencraft.loop.LoopDefinition;
import com.swaydy.opencraft.loop.LoopRegistry;
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
import net.minecraft.client.gui.components.PlayerSkinWidget;
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
	private String skin;

	private double maxDistance;
	private double speed;

	// 循环事件状态（第 3 页“行动行为”）：来自服务器随配置下发的快照
	/** 一条循环事件运行状态（id / 阶段 / 已执行次数）。 */
	private record LoopStatusEntry(String id, String phase, long iteration) {
	}
	/** 本方块循环事件实例状态（空 = 无实例在运行）。 */
	private List<LoopStatusEntry> loopStatus = List.of();
	/** 本方块已启用的循环事件 id（服务器下发，空 = 全部启用）。 */
	private List<String> enabledLoops = List.of();

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
	                      String loopStatusJson, BlockPos blockPos, ResourceKey<Level> dimension) {
		super(Component.translatable("screen.opencraft.config.title"));
		this.data = data;
		this.canEdit = canEdit;
		this.blockPos = blockPos;
		this.dimension = dimension;
		this.blockBound = blockBound;
		this.blockBoundByMe = blockBoundByMe;
		loadData(data);
		parseLoopStatus(loopStatusJson);
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
		this.skin = com.swaydy.opencraft.assistant.skin.AssistantSkins.normalize(data.skin());

		this.maxDistance = data.maxDistance();
		this.speed = data.speed();
		this.enabledLoops = data.enabledLoops() == null ? List.of() : data.enabledLoops();
	}

	/**
	 * 解析服务器下发的循环事件状态 JSON（[{id, phase, iteration}]）。
	 * 解析失败或空串时保持空列表（界面显示“已停止”）。
	 */
	private void parseLoopStatus(String json) {
		List<LoopStatusEntry> parsed = new ArrayList<>();
		if (json != null && !json.isBlank()) {
			try {
				JsonArray array = JsonParser.parseString(json).getAsJsonArray();
				for (JsonElement element : array) {
					JsonObject obj = element.getAsJsonObject();
					String id = obj.has("id") ? obj.get("id").getAsString() : "";
					String phase = obj.has("phase") ? obj.get("phase").getAsString() : "";
					long iteration = obj.has("iteration") ? obj.get("iteration").getAsLong() : 0;
					if (!id.isEmpty()) {
						parsed.add(new LoopStatusEntry(id, phase, iteration));
					}
				}
			} catch (Exception e) {
				// 解析失败：保留空状态显示
			}
		}
		this.loopStatus = parsed;
	}

	/** 指定 id 的循环事件当前状态文本（运行中带阶段/次数，否则“已停止”）。 */
	private Component loopStatusComponent(String id) {
		for (LoopStatusEntry e : this.loopStatus) {
			if (e.id().equals(id)) {
				return Component.translatable("screen.opencraft.config.loop.running",
						phaseComponent(e.phase()), e.iteration()).withColor(0xFF55FF55);
			}
		}
		return Component.translatable("screen.opencraft.config.loop.stopped").withColor(0xFFAAAAAA);
	}

	/** 循环阶段枚举名 → 翻译文本（未知阶段回退原文）。 */
	private static Component phaseComponent(String phase) {
		return switch (phase) {
			case "WAITING" -> Component.translatable("screen.opencraft.config.loop.phase.waiting");
			case "EXECUTING" -> Component.translatable("screen.opencraft.config.loop.phase.executing");
			case "MONITORING" -> Component.translatable("screen.opencraft.config.loop.phase.monitoring");
			default -> Component.literal(phase);
		};
	}

	/** Agent 预设的显示组件：优先从注册表取翻译键，未注册回退到旧映射/原文。 */
	private static Component agentDisplayComponent(String agentId) {
		if (agentId != null) {
			for (AgentDefinition def : AgentRegistry.agents()) {
				if (agentId.equals(def.id())) {
					return Component.translatable(def.displayName());
				}
			}
		}
		return switch (agentId == null ? "" : agentId) {
			case "chat_agent" -> Component.translatable("agent.opencraft.chat");
			case "general_agent" -> Component.translatable("agent.opencraft.general");
			default -> Component.literal(agentId == null ? "" : agentId);
		};
	}

	/** 皮肤的显示组件：内置皮肤走翻译键（skin.opencraft.<id>），未知 id 回退原文。 */
	private static Component skinDisplayComponent(String skinId) {
		for (com.swaydy.opencraft.assistant.skin.AssistantSkins.SkinDef def
				: com.swaydy.opencraft.assistant.skin.AssistantSkins.all()) {
			if (def.id().equals(skinId)) {
				return Component.translatable(def.displayNameKey());
			}
		}
		return Component.literal(skinId == null ? "" : skinId);
	}

	/** 服务器返回新数据时刷新界面 */
	public void updateData(AiConfigData data, boolean canEdit, boolean blockBound, boolean blockBoundByMe,
	                       String loopStatusJson, BlockPos blockPos, ResourceKey<Level> dimension) {
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
		parseLoopStatus(loopStatusJson);
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
				this.agent,
				new ArrayList<>(this.enabledLoops),
				this.skin
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
			// 选项动态取自 AgentRegistry.agents()（新预设注册后自动出现）；注册表为空时回退硬编码列表
			List<String> agentIds = new ArrayList<>();
			for (AgentDefinition def : AgentRegistry.agents()) {
				agentIds.add(def.id());
			}
			if (agentIds.isEmpty()) {
				agentIds.add("chat_agent");
				agentIds.add("general_agent");
			}
			if (!agentIds.contains(AiConfigScreen.this.agent)) {
				agentIds.add(0, AiConfigScreen.this.agent);
			}
			CycleButton<String> agentPicker = CycleButton.<String>builder(
							AiConfigScreen::agentDisplayComponent, AiConfigScreen.this.agent)
					.withValues(agentIds)
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
	// Tab 3: 行动行为（跟随/待命模式已移除，仅剩行动范围与速度 + 循环事件开关）
	// =========================================================================
	/**
	 * 行动行为页：两个滑块 + 循环事件卡片列表。不用 GridLayoutTab——网格会把超出
	 * 标签区的内容整体<b>垂直居中</b>，列表一多顶部滑块就被顶进标签栏；改走
	 * {@link ChatWindowTab} 的自绘布局：在 {@link #doLayout} 里精确摆位,
	 * 卡片列表高度 = 标签区剩余高度,超出部分由 {@link ScrollableColumn} 内部滚动。
	 */
	private class CompanionBehaviorTab implements Tab {
		private final NumericSliderButton maxSlider;
		private final NumericSliderButton speedSlider;
		private final ScrollableColumn loopList;

		public CompanionBehaviorTab() {
			Font font = AiConfigScreen.this.font;

			// 跟随/待命模式已整体移除：不再有跟随/停止/瞬移距离滑块
			// 走丢最大距离 (10.0 ~ 128.0 格)
			this.maxSlider = new NumericSliderButton(
					0, 0, CONTROL_WIDTH, ROW_HEIGHT,
					Component.translatable("screen.opencraft.config.max_distance"),
					AiConfigScreen.this.maxDistance, 10.0, 128.0, 1.0, 1, " 格",
					val -> AiConfigScreen.this.maxDistance = val
			);
			this.maxSlider.setTooltip(Tooltip.create(
					Component.translatable("screen.opencraft.config.max_distance.tooltip")));
			this.maxSlider.active = AiConfigScreen.this.canEdit;

			// 移动速度倍率 (0.5x ~ 2.5x)
			this.speedSlider = new NumericSliderButton(
					0, 0, CONTROL_WIDTH, ROW_HEIGHT,
					Component.translatable("screen.opencraft.config.speed"),
					AiConfigScreen.this.speed, 0.5, 2.5, 0.05, 2, "x",
					val -> AiConfigScreen.this.speed = val
			);
			this.speedSlider.setTooltip(Tooltip.create(
					Component.translatable("screen.opencraft.config.speed.tooltip")));
			this.speedSlider.active = AiConfigScreen.this.canEdit;

			// 循环事件列表：区域标题与卡片都在容器内随列表滚动；卡片直接显示描述,
			// 右侧对齐运行状态；整卡点击切换启用
			this.loopList = new ScrollableColumn(CONTROL_WIDTH);
			java.util.List<LoopDefinition> loopDefs = LoopRegistry.all();
			if (loopDefs.isEmpty()) {
				this.loopList.addChild(new StringWidget(
						Component.translatable("screen.opencraft.config.loop.none"), font));
			} else {
				StringWidget loopHeader = new StringWidget(
						Component.translatable("screen.opencraft.config.loops")
								.withStyle(net.minecraft.ChatFormatting.BOLD),
						font);
				loopHeader.setTooltip(Tooltip.create(
						Component.translatable("screen.opencraft.config.loops.tooltip")));
				this.loopList.addChild(loopHeader);
				for (LoopDefinition def : loopDefs) {
					String id = def.id();
					boolean selected = AiConfigScreen.this.enabledLoops.contains(id);
					LoopCardWidget card = this.loopList.addChild(new LoopCardWidget(
							def, selected, AiConfigScreen.this.loopStatusComponent(id), font,
							checked -> {
								// 修改 enabledLoops 副本（避免写回不可变列表）
								List<String> updated = new ArrayList<>(
										AiConfigScreen.this.enabledLoops);
								if (checked) {
									if (!updated.contains(id)) {
										updated.add(id);
									}
								} else {
									updated.remove(id);
								}
								AiConfigScreen.this.enabledLoops = updated;
							}));
					card.active = AiConfigScreen.this.canEdit;
				}
			}
		}

		@Override
		public Component getTabTitle() {
			return Component.translatable("screen.opencraft.config.tab.companion");
		}

		@Override
		public Component getTabExtraNarration() {
			return Component.empty();
		}

		@Override
		public void visitChildren(Consumer<AbstractWidget> consumer) {
			consumer.accept(this.maxSlider);
			consumer.accept(this.speedSlider);
			consumer.accept(this.loopList);
		}

		@Override
		public void doLayout(ScreenRectangle rectangle) {
			int x = rectangle.left() + (rectangle.width() - CONTROL_WIDTH) / 2;
			int y = rectangle.top() + 4;
			this.maxSlider.setPosition(x, y);
			y += ROW_HEIGHT + 4;
			this.speedSlider.setPosition(x, y);
			y += ROW_HEIGHT + 8;
			// 卡片列表吃掉标签区剩余的全部高度（列表内部滚动）,底边与页脚留 8px——
			// 列表声明的高度永远是"实际可视高度",网格/页脚都不会被挤出去
			this.loopList.setPosition(x, y);
			this.loopList.setWidth(CONTROL_WIDTH);
			this.loopList.setHeight(Math.max(60, rectangle.bottom() - 8 - y));
		}
	}

	// =========================================================================
	// Tab 4: 聊天窗口
	// =========================================================================

	/**
	 * 聊天页：皮肤预览 + 选择器（顶部一行）+ 滚动对话记录 + 输入框 + 发送按钮
	 * （自定义 Tab，填满标签区域）。皮肤行放本页顶部作为“助手名片”——预览每帧跟随
	 * 选择器即时切换（宽/细模型自动），可拖拽旋转；从“对话与动作”页移入，该页保持
	 * 纯表单行对齐。
	 */
	private class ChatWindowTab implements Tab {
		private final ChatLogWidget log;
		private final ChatInputBox input;
		private final Button sendButton;
		private final Button interruptButton;
		/** 助手皮肤实时预览（PlayerSkinWidget 每帧调 supplier）。 */
		private final PlayerSkinWidget skinPreview;
		/** 助手皮肤选择器（按钮文本自带“助手皮肤：”前缀，独立标签省略）。 */
		private final CycleButton<String> skinPicker;
		/** 顶部皮肤行的预览尺寸。 */
		private static final int SKIN_PREVIEW_WIDTH = 48;
		private static final int SKIN_PREVIEW_HEIGHT = 72;

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

			// 皮肤预览 + 选择器：选项动态取自 AssistantSkins，但 default（原版按 UUID 随机
			// 皮肤）只是"未选择时的原版回退"，不作为可选项出现在选择器里；
			// id 由服务端随召唤/保存同步给客户端，贴图随模组分发、客户端 Mixin 渲染时替换
			List<String> skinIds = new ArrayList<>();
			for (com.swaydy.opencraft.assistant.skin.AssistantSkins.SkinDef def
					: com.swaydy.opencraft.assistant.skin.AssistantSkins.all()) {
				if (!com.swaydy.opencraft.assistant.skin.AssistantSkins.DEFAULT_ID.equals(def.id())) {
					skinIds.add(def.id());
				}
			}
			if (skinIds.isEmpty()) {
				// 没有任何内置皮肤可选的极端情况：退回允许选当前值（含 default），不让选择器空转
				skinIds.add(AiConfigScreen.this.skin);
			} else if (!skinIds.contains(AiConfigScreen.this.skin)) {
				// 当前值是 default（新配置初始值）或已下架的皮肤：落到第一个可选项
				AiConfigScreen.this.skin = skinIds.get(0);
			}
			this.skinPicker = CycleButton.<String>builder(
							AiConfigScreen::skinDisplayComponent, AiConfigScreen.this.skin)
					.withValues(skinIds)
					.withTooltip(val -> Tooltip.create(Component.translatable("screen.opencraft.config.skin.tooltip")))
					.create(0, 0, 100, ROW_HEIGHT,
							Component.translatable("screen.opencraft.config.skin"),
							(btn, val) -> AiConfigScreen.this.skin = val);
			this.skinPicker.active = AiConfigScreen.this.canEdit;
			this.skinPreview = new PlayerSkinWidget(SKIN_PREVIEW_WIDTH, SKIN_PREVIEW_HEIGHT,
					AiConfigScreen.this.minecraft.getEntityModels(),
					() -> com.swaydy.opencraft.client.skin.AssistantSkinState.previewSkin(
							AiConfigScreen.this.skin));
			this.skinPreview.setTooltip(Tooltip.create(
					Component.translatable("screen.opencraft.config.skin.preview.tooltip")));
			// 只读模式同样禁止拖拽预览（active 不影响渲染，仅禁交互），与选择器状态一致
			this.skinPreview.active = AiConfigScreen.this.canEdit;

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
			consumer.accept(this.skinPreview);
			consumer.accept(this.skinPicker);
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

			// 顶部皮肤行：预览靠左，选择器占满剩余宽度并垂直居中于预览高度
			int stripTop = rectangle.top() + 8;
			this.skinPreview.setPosition(left, stripTop);
			int pickerX = left + SKIN_PREVIEW_WIDTH + 8;
			this.skinPicker.setPosition(pickerX, stripTop + (SKIN_PREVIEW_HEIGHT - ROW_HEIGHT) / 2);
			this.skinPicker.setWidth(Math.max(0, right - pickerX));
			this.skinPicker.setHeight(ROW_HEIGHT);

			this.log.setPosition(left, stripTop + SKIN_PREVIEW_HEIGHT + 8);
			this.log.setWidth(Math.max(0, right - left));
			this.log.setHeight(Math.max(0, inputY - 6 - (stripTop + SKIN_PREVIEW_HEIGHT + 8)));
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
	// 循环事件卡片组件（第 3 页“行动行为”）
	// =========================================================================

	/**
	 * 单个循环事件的卡片：深色半透明底 + 1px 边框（与聊天页日志框同款画法）。
	 * 上行左侧自绘复选框 + 名称、右侧状态文本（右对齐带颜色）；下方灰色小字描述
	 * 直接可见（按卡片内容宽度换行,参与卡片高度——列表超高由 {@link ScrollableColumn}
	 * 滚动,不需要为省高度隐藏描述）。点击卡片任意处即可切换勾选（复选框精灵用原版
	 * Checkbox 同款 sprite 自绘——1.21.11 的 AbstractWidget 没有 children 机制，
	 * 且 Checkbox 无 setSelected，无法直接内嵌一个可点击的 Checkbox 控件）。
	 * 宽度/位置由 {@link ScrollableColumn} 分配（setWidth 时重算描述换行与高度）。
	 */
	private class LoopCardWidget extends AbstractWidget {
		private static final int PADDING = 6;
		private static final int BOX_LABEL_GAP = 4;
		private static final int TITLE_DESC_GAP = 4;
		private static final int COLOR_DESC = 0xFFA0A0A0;
		private static final int COLOR_BORDER = 0xFF3A3A3A;
		private static final int COLOR_BORDER_HOVER = 0xFF6A6A6A;
		private static final net.minecraft.resources.Identifier BOX_SPRITE =
				net.minecraft.resources.Identifier.withDefaultNamespace("widget/checkbox");
		private static final net.minecraft.resources.Identifier BOX_SELECTED_SPRITE =
				net.minecraft.resources.Identifier.withDefaultNamespace("widget/checkbox_selected");
		private static final net.minecraft.resources.Identifier BOX_HOVER_SPRITE =
				net.minecraft.resources.Identifier.withDefaultNamespace("widget/checkbox_highlighted");
		private static final net.minecraft.resources.Identifier BOX_SELECTED_HOVER_SPRITE =
				net.minecraft.resources.Identifier.withDefaultNamespace("widget/checkbox_selected_highlighted");

		private final Font font;
		private final Component name;
		private final Component status;
		private final String description;
		private final Consumer<Boolean> onToggle;
		private List<FormattedCharSequence> descLines = List.of();
		private boolean selected;

		LoopCardWidget(LoopDefinition def, boolean selected, Component status,
		               Font font, Consumer<Boolean> onToggle) {
			super(0, 0, 0, PADDING * 2 + Checkbox.getBoxSize(font),
					Component.literal(def.displayName() == null ? def.id() : def.displayName()));
			this.font = font;
			this.name = this.getMessage();
			this.status = status;
			this.description = def.description();
			this.onToggle = onToggle;
			this.selected = selected;
		}

		@Override
		public void setWidth(int width) {
			super.setWidth(width);
			// 描述按卡片内容宽度重新换行,并据此重算卡片高度（真实宽度在滚动容器
			// 布局时才确定,初始高度只含标题行）
			if (this.description == null || width <= 0) {
				this.descLines = List.of();
			} else {
				this.descLines = this.font.split(Component.literal(this.description),
						Math.max(1, width - PADDING * 2));
			}
			int height = PADDING * 2 + Checkbox.getBoxSize(this.font);
			if (!this.descLines.isEmpty()) {
				height += TITLE_DESC_GAP + this.descLines.size() * this.font.lineHeight;
			}
			this.setHeight(height);
		}

		@Override
		protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			int right = this.getX() + this.getWidth();
			int bottom = this.getY() + this.getHeight();
			// 背景 + 边框（悬停时边框提亮）
			graphics.fill(this.getX(), this.getY(), right, bottom, 0x66000000);
			int border = this.isHovered() ? COLOR_BORDER_HOVER : COLOR_BORDER;
			graphics.hLine(this.getX(), right, this.getY(), border);
			graphics.hLine(this.getX(), right, bottom - 1, border);
			graphics.vLine(this.getX(), this.getY(), bottom, border);
			graphics.vLine(right - 1, this.getY(), bottom, border);

			int boxSize = Checkbox.getBoxSize(this.font);
			int boxX = this.getX() + PADDING;
			int boxY = this.getY() + PADDING;
			// 复选框精灵（原版 Checkbox 同款 sprite）
			net.minecraft.resources.Identifier sprite = this.selected
					? (this.isHovered() ? BOX_SELECTED_HOVER_SPRITE : BOX_SELECTED_SPRITE)
					: (this.isHovered() ? BOX_HOVER_SPRITE : BOX_SPRITE);
			graphics.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
					sprite, boxX, boxY, boxSize, boxSize);
			// 名称（复选框右侧，垂直居中于标题行）
			graphics.drawString(this.font, this.name,
					boxX + boxSize + BOX_LABEL_GAP,
					boxY + (boxSize - this.font.lineHeight) / 2, 0xFFFFFFFF);
			// 状态文本（右对齐，垂直居中于标题行）
			graphics.drawString(this.font, this.status,
					right - PADDING - this.font.width(this.status),
					boxY + (boxSize - this.font.lineHeight) / 2, 0xFFFFFFFF);
			// 描述（灰色小字，换行显示）
			int y = boxY + boxSize + TITLE_DESC_GAP;
			for (FormattedCharSequence line : this.descLines) {
				graphics.drawString(this.font, line, this.getX() + PADDING, y, COLOR_DESC);
				y += this.font.lineHeight;
			}
		}

		@Override
		public void onClick(net.minecraft.client.input.MouseButtonEvent event, boolean bl) {
			this.selected = !this.selected;
			this.onToggle.accept(this.selected);
		}

		@Override
		protected void updateWidgetNarration(
				net.minecraft.client.gui.narration.NarrationElementOutput narrationElementOutput) {
			this.defaultButtonNarrationText(narrationElementOutput);
		}
	}

	/**
	 * 可滚动的竖排控件容器：把一组子控件装进一个视口——内容超高时滚轮滚动,
	 * 视口外 scissor 裁剪并画细滚动条。
	 *
	 * <p><b>高度由外部（Tab 的 doLayout）设定为实际可视高度</b>——容器绝不向布局
	 * 申报内容的完整高度（否则网格垂直居中会把上方控件顶出标签区），内容超出
	 * 视口的部分在容器内部滚动；{@link #viewportBottom()} 另有"截到页脚上方"的
	 * 兜底,双保险保证不压页脚按钮。
	 *
	 * <p>子控件不注册进 Screen——每帧由本容器按滚动量平移子控件位置后统一渲染
	 * （悬停提示仍由 AbstractWidget.render 自带机制生效），并把鼠标事件按平移后的
	 * 坐标转发给子控件；键盘焦点/旁白不经过子控件（当前只放可点击的卡片）。
	 * 宽度/位置由外部摆放：setX/setY/setWidth 时同步重排子控件。
	 */
	private class ScrollableColumn extends AbstractWidget {
		private static final int PADDING = 6;
		private static final int ENTRY_GAP = 4;
		private static final int SCROLL_STEP = 16;
		private static final int MIN_VIEWPORT = 24;
		private static final int SCROLLBAR_WIDTH = 2;
		private static final int COLOR_THUMB = 0xFF6A6A6A;

		private final List<AbstractWidget> children = new ArrayList<>();
		/** 子控件未滚动时的 y 坐标（与 children 一一对应）。 */
		private final List<Integer> baseYs = new ArrayList<>();
		/** 内容总高度（含上下内边距，与视口高度无关）。 */
		private int contentHeight;
		private int scrollOffset;

		ScrollableColumn(int width) {
			super(0, 0, width, MIN_VIEWPORT, Component.empty());
		}

		/** 追加一个子控件（宽度/位置由本容器统一分配），返回原控件便于链式设置属性。 */
		<T extends AbstractWidget> T addChild(T child) {
			this.children.add(child);
			this.baseYs.add(0);
			this.layoutChildren();
			return child;
		}

		private void layoutChildren() {
			this.baseYs.clear();
			int width = Math.max(1, this.getWidth() - PADDING * 2 - SCROLLBAR_WIDTH);
			int y = this.getY() + PADDING;
			for (AbstractWidget child : this.children) {
				child.setWidth(width);
				child.setX(this.getX() + PADDING);
				child.setY(y);
				this.baseYs.add(y);
				y += child.getHeight() + ENTRY_GAP;
			}
			this.contentHeight = this.children.isEmpty()
					? 0 : (y - ENTRY_GAP + PADDING) - this.getY();
			this.scrollOffset = Math.min(this.scrollOffset, maxScroll(viewportHeight()));
		}

		@Override
		public void setX(int x) {
			super.setX(x);
			this.layoutChildren();
		}

		@Override
		public void setY(int y) {
			super.setY(y);
			this.layoutChildren();
		}

		@Override
		public void setWidth(int width) {
			super.setWidth(width);
			this.layoutChildren();
		}

		/** 实际可视视口的底边：自身内容底与「页脚按钮上方」取小（布局 arrange 后 footer 高度可信）。 */
		private int viewportBottom() {
			int footerTop = AiConfigScreen.this.height - AiConfigScreen.this.layout.getFooterHeight();
			return Math.max(this.getY() + MIN_VIEWPORT,
					Math.min(this.getY() + this.getHeight(), footerTop - 2));
		}

		private int viewportHeight() {
			return Math.max(1, viewportBottom() - this.getY());
		}

		private int maxScroll(int viewHeight) {
			return Math.max(0, this.contentHeight - viewHeight);
		}

		@Override
		public boolean isMouseOver(double mouseX, double mouseY) {
			return this.visible && mouseX >= this.getX() && mouseX < this.getX() + this.getWidth()
					&& mouseY >= this.getY() && mouseY < viewportBottom();
		}

		@Override
		protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			int viewBottom = viewportBottom();
			int viewHeight = viewBottom - this.getY();
			this.scrollOffset = Math.min(this.scrollOffset, maxScroll(viewHeight));
			int shift = -this.scrollOffset;

			graphics.enableScissor(this.getX(), this.getY(), this.getX() + this.getWidth(), viewBottom);
			for (int i = 0; i < this.children.size(); i++) {
				AbstractWidget child = this.children.get(i);
				child.setY(this.baseYs.get(i) + shift);
				if (child.getY() < viewBottom && child.getY() + child.getHeight() > this.getY()) {
					child.render(graphics, mouseX, mouseY, partialTick);
				}
			}
			graphics.disableScissor();

			// 内容超出视口时在右侧画一条细滚动条
			int max = maxScroll(viewHeight);
			if (max > 0) {
				int trackHeight = viewHeight - 4;
				int thumbHeight = Math.max(12, (int) ((long) trackHeight * viewHeight
						/ Math.max(1, this.contentHeight)));
				int thumbY = this.getY() + 2
						+ (int) ((long) (trackHeight - thumbHeight) * this.scrollOffset / max);
				graphics.fill(this.getX() + this.getWidth() - 4, thumbY,
						this.getX() + this.getWidth() - 2, thumbY + thumbHeight, COLOR_THUMB);
			}
		}

		@Override
		public boolean mouseScrolled(double mouseX, double mouseY,
		                             double horizontalAmount, double verticalAmount) {
			if (this.visible && this.isMouseOver(mouseX, mouseY)) {
				int max = maxScroll(viewportHeight());
				if (max > 0) {
					int target = this.scrollOffset + (verticalAmount > 0 ? -SCROLL_STEP : SCROLL_STEP);
					this.scrollOffset = Math.max(0, Math.min(target, max));
				}
				return true;
			}
			return false;
		}

		@Override
		public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean bl) {
			if (this.visible && this.isMouseOver(event.x(), event.y())) {
				// 子控件位置在上一帧渲染时已按滚动量平移,按当前坐标转发即可命中
				for (AbstractWidget child : this.children) {
					if (child.visible && child.mouseClicked(event, bl)) {
						return true;
					}
				}
				return true; // 点到容器空白处也由本容器消费,避免穿透到下层
			}
			return false;
		}

		@Override
		protected void updateWidgetNarration(
				net.minecraft.client.gui.narration.NarrationElementOutput narrationElementOutput) {
			this.defaultButtonNarrationText(narrationElementOutput);
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
