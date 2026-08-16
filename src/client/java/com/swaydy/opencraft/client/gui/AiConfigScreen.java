package com.swaydy.opencraft.client.gui;

import com.swaydy.opencraft.ai.AiConfigData;
import com.swaydy.opencraft.net.AiConfigPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
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

import java.util.Locale;
import java.util.function.Consumer;

/**
 * AI 配置编辑器（现代版）：右键 AI 徽标方块后打开。
 *
 * 采用 Minecraft 1.21 原生 TabNavigationBar 标签栏导航与 HeaderAndFooterLayout 布局系统，
 * 分为“接口与密钥”、“对话与动作”、“伴侣行为” 3 个清晰的分页。
 *
 * “AI 功能”开关与“用本方块召唤助手”已合并为底部同一个按钮：未绑定助手时点击 =
 * 召唤（绑定本方块）；已绑定自己的助手时点击 = 送走（不召唤）。绑定状态由服务器随
 * 配置数据一起下发（AiConfigDataPayload.bound/boundByMe），每次保存/召唤/送走后刷新。
 *
 * 安全特性：API Key 从不在网络中明文传输，客户端输入时采用掩码格式化，仅 OP 管理员可保存生效。
 * 配置只保存在被右键的 AI 徽标方块实体里（不依赖任何外部配置文件），
 * 因此保存/召唤请求都会携带目标方块的坐标 + 维度。
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

	private boolean allowActions;
	private double temperature;
	private int timeoutSeconds;
	private int maxHistoryMessages;
	private String systemPrompt;
	private String name;

	private double followDistance;
	private double stopDistance;
	private double teleportDistance;
	private double maxDistance;
	private double speed;

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

		this.allowActions = data.allowActions();
		this.temperature = data.temperature();
		this.timeoutSeconds = data.timeoutSeconds();
		this.maxHistoryMessages = data.maxHistoryMessages();
		this.systemPrompt = data.systemPrompt();
		this.name = data.name() == null ? "" : data.name();

		this.followDistance = data.followDistance();
		this.stopDistance = data.stopDistance();
		this.teleportDistance = data.teleportDistance();
		this.maxDistance = data.maxDistance();
		this.speed = data.speed();
	}

	/** 服务器返回新数据时刷新界面 */
	public void updateData(AiConfigData data, boolean canEdit, boolean blockBound, boolean blockBoundByMe,
	                       BlockPos blockPos, ResourceKey<Level> dimension) {
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
		// 1. 创建 3 个 Tab 分页
		EndpointKeyTab endpointTab = new EndpointKeyTab();
		ChatActionsTab chatTab = new ChatActionsTab();
		CompanionBehaviorTab companionTab = new CompanionBehaviorTab();

		// 2. 创建顶部 TabNavigationBar
		this.tabNavigationBar = TabNavigationBar.builder(this.tabManager, this.width)
				.addTabs(new Tab[]{endpointTab, chatTab, companionTab})
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
				this.systemPrompt,
				this.temperature,
				this.maxHistoryMessages,
				this.timeoutSeconds,
				this.allowActions,
				this.language,
				this.followDistance,
				this.stopDistance,
				this.teleportDistance,
				this.maxDistance,
				this.speed,
				this.name
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

			// 允许动作开关
			CycleButton<Boolean> actionSwitch = CycleButton.onOffBuilder(AiConfigScreen.this.allowActions)
					.withTooltip(val -> Tooltip.create(Component.translatable("screen.opencraft.config.allow_actions.tooltip")))
					.create(0, 0, CONTROL_WIDTH, ROW_HEIGHT,
							Component.translatable("screen.opencraft.config.allow_actions"),
							(btn, val) -> AiConfigScreen.this.allowActions = val);
			actionSwitch.active = AiConfigScreen.this.canEdit;
			rows.addChild(actionSwitch);

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

			// 系统提示词多行编辑框
			MultiLineEditBox promptBox = MultiLineEditBox.builder()
					.setShowBackground(true)
					.setShowDecorations(true)
					.build(font, CONTROL_WIDTH, 60, Component.translatable("screen.opencraft.config.system_prompt"));
			promptBox.setCharacterLimit(3000);
			promptBox.setValue(AiConfigScreen.this.systemPrompt);
			promptBox.setValueListener(s -> AiConfigScreen.this.systemPrompt = s);
			promptBox.setTooltip(Tooltip.create(Component.translatable("screen.opencraft.config.system_prompt.tooltip")));
			rows.addChild(CommonLayouts.labeledElement(font, promptBox, Component.translatable("screen.opencraft.config.system_prompt")));
		}
	}

	// =========================================================================
	// Tab 3: 伴侣行为
	// =========================================================================
	private class CompanionBehaviorTab extends GridLayoutTab {
		public CompanionBehaviorTab() {
			super(Component.translatable("screen.opencraft.config.tab.companion"));
			GridLayout.RowHelper rows = this.layout.createRowHelper(1);
			rows.defaultCellSetting().paddingVertical(2).alignHorizontallyCenter();

			// 跟随触发距离 (1.0 ~ 30.0 格)
			NumericSliderButton followSlider = new NumericSliderButton(
					0, 0, CONTROL_WIDTH, ROW_HEIGHT,
					Component.translatable("screen.opencraft.config.follow_distance"),
					AiConfigScreen.this.followDistance, 1.0, 30.0, 0.5, 1, " 格",
					val -> AiConfigScreen.this.followDistance = val
			);
			followSlider.setTooltip(Tooltip.create(Component.translatable("screen.opencraft.config.follow_distance.tooltip")));
			followSlider.active = AiConfigScreen.this.canEdit;
			rows.addChild(followSlider);

			// 停止靠近距离 (0.5 ~ 10.0 格)
			NumericSliderButton stopSlider = new NumericSliderButton(
					0, 0, CONTROL_WIDTH, ROW_HEIGHT,
					Component.translatable("screen.opencraft.config.stop_distance"),
					AiConfigScreen.this.stopDistance, 0.5, 10.0, 0.5, 1, " 格",
					val -> AiConfigScreen.this.stopDistance = val
			);
			stopSlider.setTooltip(Tooltip.create(Component.translatable("screen.opencraft.config.stop_distance.tooltip")));
			stopSlider.active = AiConfigScreen.this.canEdit;
			rows.addChild(stopSlider);

			// 瞬移距离 (5.0 ~ 64.0 格)
			NumericSliderButton tpSlider = new NumericSliderButton(
					0, 0, CONTROL_WIDTH, ROW_HEIGHT,
					Component.translatable("screen.opencraft.config.teleport_distance"),
					AiConfigScreen.this.teleportDistance, 5.0, 64.0, 1.0, 1, " 格",
					val -> AiConfigScreen.this.teleportDistance = val
			);
			tpSlider.setTooltip(Tooltip.create(Component.translatable("screen.opencraft.config.teleport_distance.tooltip")));
			tpSlider.active = AiConfigScreen.this.canEdit;
			rows.addChild(tpSlider);

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
