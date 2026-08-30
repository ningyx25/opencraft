package com.swaydy.opencraft.test;

import com.swaydy.opencraft.ai.AiBlockConfig;
import com.swaydy.opencraft.ai.AiCompanionService;
import com.swaydy.opencraft.ai.AiConfigData;
import com.swaydy.opencraft.ai.AiConfigHandler;
import com.swaydy.opencraft.block.AiLogoBlock;
import com.swaydy.opencraft.block.AiLogoBlockEntity;
import com.swaydy.opencraft.block.ModBlocks;
import com.swaydy.opencraft.inventory.AssistantInventoryMenu;
import com.swaydy.opencraft.plugins.ToolContext;
import com.swaydy.opencraft.plugins.ToolDefinition;
import com.swaydy.opencraft.plugins.ToolResult;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * OpenCraft 的 Fabric Gametest（通过 -Dfabric-api.gametest=true 运行）。
 *
 * 配置说明：AI 助手的配置全部保存在游戏内的 AI 徽标方块实体里，
 * 不再依赖任何外部配置文件；测试会先放置并配置一个方块。
 *
 * 多助手规则：每个 AI 徽标方块最多绑定一个助手，一个玩家可同时拥有多个助手
 * （各绑定不同的方块）。
 *
 * 测试：
 * 1. assistantLifecycleAndChat —— 助手完整生命周期（召唤/聊天/动作/跨维度/送走）；
 * 2. aiLogoBlockConfigEditor —— AI 徽标方块作为配置载体（读写配置、权限、密钥安全）；
 * 3. configScreenSummonDismissToggle —— 配置界面合并按钮（召唤/送走切换、他人不可送走）；
 * 4. assistantVanishesWithBlock —— 助手与绑定方块共存；
 * 5. multipleAssistantsCoexist —— 多助手共存（一方块一助手，独立配置，路由/送走/拆方块）；
 * 6. summonRequiresConfigBlock —— 没有未绑定的方块时拒绝召唤；
 * 7. unboundAssistantDiscarded —— 无绑定助手被安全网清除；
 * 8. aiLogoBlockMiningAndRecipe —— 配方注册 + 徒手挖掘掉落；
 * 9. askTargetsSpecificAssistant —— 多助手时指定和哪个助手对话（ask <名字> <消息>）；
 * 10. assistantRightClickInteract —— 右键助手互动（绑定/主人右键开背包界面/非主人拒绝/聊天/送走）；
 * 11. assistantInventoryMenuLayoutAndTransfer —— 右键打开的双面板背包菜单
 *     （原版 E 背包布局：装备槽/2×2 合成/主背包/快捷栏 / 原版合成 / Shift 双向转移 / 助手消失后失效）。
 * 12. healAuraLoopHealsOwner —— 循环事件模块：heal_aura 治疗光环端到端
 *     （召唤绑定自动启动 / 受伤后每 ~40 tick 回 1 点血 / 满血后 persistent 闲置不消亡 / 送走即停止）。
 * 13. assistantOpensAndUsesChest —— 容器交互端到端（player_container_open 真实右键打开箱子 /
 *     player_container_list 查看内容 / player_container_take 取出物品 / player_container_put 放入物品 /
 *     player_container_close 关闭）。
 */
public class OpenCraftGameTests {
	/**
	 * 通过真实指令派发器执行 /opencraft 指令，返回执行器的返回值
	 * （0 = 拒绝/歧义，1 = 成功）。解析或执行抛异常时直接转为测试失败。
	 */
	private static int runCommand(GameTestHelper helper, ServerPlayer player, String command) {
		try {
			return helper.getLevel().getServer().getCommands().getDispatcher()
					.execute(new com.mojang.brigadier.StringReader(command),
							player.createCommandSourceStack());
		} catch (Exception e) {
			throw new AssertionError("指令执行失败: " + command + " -> " + e, e);
		}
	}

	/** 把 mock 玩家的 LLM 配置写进指定方块（指向本地 mock 服务器）。 */
	private static void configureMockBlock(GameTestHelper helper, BlockPos relPos,
	                                       ServerPlayer player) {
		helper.setBlock(relPos, ModBlocks.AI_LOGO_BLOCK.defaultBlockState());
		AiLogoBlockEntity blockEntity = helper.getBlockEntity(relPos, AiLogoBlockEntity.class);
		if (blockEntity == null) {
			throw new AssertionError("配置方块实体未创建");
		}
		AiBlockConfig cfg = blockEntity.getConfig();
		cfg.baseUrl = "http://127.0.0.1:18923/v1";
		cfg.apiKey = "test-key-123";
		cfg.model = "mock-model";
		cfg.timeoutSeconds = 15;
		blockEntity.markConfigChanged();
	}

	/**
	 * 清掉测试遗留的“孤儿”玩家形态 bot（主人已不在线的，通常是之前失败/中断的运行留下的），
	 * 防止跨测试占用方块。gtest 是 15 个测试在同一世界里并行跑的，因此**绝对不能**误伤
	 * 其它并行测试正在使用的 bot——只清理主人已不在线（离线）的。
	 */
	private static void dismissAllPlayerBots() {
		for (com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot
				: com.swaydy.opencraft.assistant.player.PlayerAssistantService.allActive()) {
			java.util.UUID ownerId = bot.getOwnerUuid();
			if (ownerId == null || bot.level().getServer().getPlayerList().getPlayer(ownerId) == null) {
				com.swaydy.opencraft.assistant.AssistantFacade.dismiss(bot);
			}
		}
	}

	/** 按名取 general_agent 的工具定义（拿不到直接失败）。 */
	private static ToolDefinition agentTool(String name) {
		ToolDefinition def = com.swaydy.opencraft.agent.AgentRegistry.agent("general_agent")
				.toolMap().get(name);
		if (def == null) {
			throw new AssertionError("general_agent 应提供工具 " + name);
		}
		return def;
	}

	/** 构造 {x,y,z} 参数（容器打开用）。 */
	private static JsonObject xyzArgs(int x, int y, int z) {
		JsonObject args = new JsonObject();
		args.addProperty("x", x);
		args.addProperty("y", y);
		args.addProperty("z", z);
		return args;
	}

	/** 构造 {item} 参数（容器取/放用）。 */
	private static JsonObject itemArgs(String item) {
		JsonObject args = new JsonObject();
		args.addProperty("item", item);
		return args;
	}

	
	/**
	 * 验证 AI 徽标方块作为“配置载体”（配置只保存在方块里，无外部文件）：
	 * 1. 放置方块并右键（useBlock）触发 openFor（不崩溃）；
	 * 2. 非管理员：发送的数据 apiKey 为空、apiKeySet 正确、保存被拒绝；
	 * 3. 授予 op 后：保存修改 → 方块实体配置即时生效（改模型/关动作/换密钥）；
	 * 4. 不更换密钥保存时旧密钥保留；API Key 从不通过数据外发。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
	public void aiLogoBlockConfigEditor(GameTestHelper helper) {
		dismissAllPlayerBots();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		// 把玩家放到结构内并放置 AI 徽标方块（结构区块保持加载）
		net.minecraft.world.phys.Vec3 playerPos = helper.absoluteVec(
				new net.minecraft.world.phys.Vec3(4.5, 2, 4.5));
		player.teleportTo(playerPos.x, playerPos.y, playerPos.z);
		BlockPos blockPos = new BlockPos(4, 3, 4);
		helper.setBlock(blockPos, ModBlocks.AI_LOGO_BLOCK.defaultBlockState());
		AiLogoBlockEntity blockEntity = helper.getBlockEntity(blockPos, AiLogoBlockEntity.class);
		if (blockEntity == null) {
			throw new AssertionError("配置方块实体未创建");
		}
		// 预置一份 mock 配置
		blockEntity.getConfig().baseUrl = "http://127.0.0.1:18923/v1";
		blockEntity.getConfig().apiKey = "test-key-123";
		blockEntity.getConfig().model = "mock-model";
		blockEntity.markConfigChanged();

		net.minecraft.server.players.NameAndId nameAndId =
				new net.minecraft.server.players.NameAndId(player.getGameProfile());
		net.minecraft.server.players.PlayerList playerList = player.level().getServer().getPlayerList();
		ResourceKey<net.minecraft.world.level.Level> dimension = player.level().dimension();
		BlockPos absPos = helper.absolutePos(blockPos);

		helper.startSequence()
				.thenExecute(() -> {
					// 1) 右键方块触发 openFor（发送配置数据，不应召唤助手）
					helper.useBlock(blockPos, player);
					if (!com.swaydy.opencraft.assistant.AssistantFacade.findAssistantsFor(player).isEmpty()) {
						throw new AssertionError("右键配置方块不应召唤助手");
					}
					// 1.5) 无人绑定时方块应为未激活（不亮）
					net.minecraft.world.level.block.state.BlockState state =
							helper.getLevel().getBlockState(absPos);
					if (state.getValue(AiLogoBlock.POWERED)
							|| state.getLightEmission() != 0) {
						throw new AssertionError("无助手绑定时方块应处于未激活（不亮）状态");
					}
					// 2) 非管理员：API Key 不应发送给客户端
					if (playerList.isOp(nameAndId)) {
						throw new AssertionError("mock 玩家初始不应是 op");
					}
					AiConfigData sent = blockEntity.getConfig().toData();
					if (!sent.apiKey().isEmpty()) {
						throw new AssertionError("API Key 不应发送给客户端");
					}
					if (!sent.apiKeySet()) {
						throw new AssertionError("apiKeySet 应反映已配置密钥");
					}
					if (!sent.baseUrl().equals("http://127.0.0.1:18923/v1")) {
						throw new AssertionError("数据应与方块配置一致");
					}
					// 非管理员保存应被拒绝（方块配置不变）
					AiConfigHandler.save(player, absPos, dimension, sent.toJson());
					if (!blockEntity.getConfig().model.equals("mock-model")
							|| !"test-key-123".equals(blockEntity.getConfig().apiKey)) {
						throw new AssertionError("非管理员保存不应生效");
					}
					// 3) 授予 op
					playerList.op(nameAndId);
					if (!playerList.isOp(nameAndId)) {
						throw new AssertionError("op 授予失败");
					}
					// JSON 往返（客户端依赖 fromJson 重建数据）
					AiConfigData roundTrip = AiConfigData.fromJson(sent.toJson());
					if (!sent.equals(roundTrip)) {
						throw new AssertionError("配置 JSON 往返不一致");
					}
					// 4) 管理员保存：修改模型 + 关闭动作 + 更换密钥（apiKeyChanged=true）+ 改名字
					AiConfigData edited = new AiConfigData(
							sent.baseUrl(), "new-secret-key-456", true, true,
							"in-game-edited-model",
							sent.temperature(), sent.maxHistoryMessages(), sent.timeoutSeconds(),
							sent.language(),
							sent.maxDistance(), sent.speed(),
							"改名小智", "general_agent", sent.enabledLoops(), "deepseek_fish");
					AiConfigHandler.save(player, absPos, dimension, edited.toJson());
					if (!"in-game-edited-model".equals(blockEntity.getConfig().model)) {
						throw new AssertionError("保存后方块配置未即时生效");
					}
					if (!"general_agent".equals(blockEntity.getConfig().agent)) {
						throw new AssertionError("保存后 agent 预设应生效");
					}
					if (!"deepseek_fish".equals(blockEntity.getConfig().skin)) {
						throw new AssertionError("保存后皮肤选择应生效");
					}
					if (!"new-secret-key-456".equals(blockEntity.getConfig().apiKey)) {
						throw new AssertionError("更换密钥未生效");
					}
					if (!"改名小智".equals(blockEntity.getConfig().name)) {
						throw new AssertionError("保存后助手名字未生效");
					}
					// 保存后显式启用了循环事件（enabledLoops 持久化生效；默认未配置 = 全启用）
					if (!blockEntity.getConfig().isLoopEnabled("heal_aura")) {
						throw new AssertionError("保存后循环事件应保持启用（默认全启用）");
					}
					// 5) 未勾选更换时保存：密钥应保留（客户端只会传空串）
					AiConfigData keepKey = new AiConfigData(
							sent.baseUrl(), "", false, true,
							"model-keep-key",
							sent.temperature(), sent.maxHistoryMessages(), sent.timeoutSeconds(),
							sent.language(),
							sent.maxDistance(), sent.speed(),
							"keep-key-name", "general_agent", java.util.List.of(), "default");
					AiConfigHandler.save(player, absPos, dimension, keepKey.toJson());
					if (!"new-secret-key-456".equals(blockEntity.getConfig().apiKey)) {
						throw new AssertionError("未更换密钥时不应覆盖旧密钥");
					}
					if (!"model-keep-key".equals(blockEntity.getConfig().model)) {
						throw new AssertionError("keep-key 保存的模型未生效");
					}
					if (!"keep-key-name".equals(blockEntity.getConfig().name)) {
						throw new AssertionError("keep-key 保存的名字未生效");
					}
					// 显式保存空 enabledLoops = 全部循环事件关闭（与"未配置"区分）
					if (blockEntity.getConfig().isLoopEnabled("heal_aura")) {
						throw new AssertionError("保存空 enabledLoops 后循环事件应全部关闭");
					}
					// 6) 恢复
					AiBlockConfig original = blockEntity.getConfig();
					original.baseUrl = "http://127.0.0.1:18923/v1";
					original.apiKey = "test-key-123";
					original.model = "mock-model";
					original.agent = "general_agent";
					blockEntity.markConfigChanged();
				})
				.thenSucceed();
	}

	/**
	 * 验证“配置界面合并按钮”（“AI 功能”开关与“用本方块召唤助手”合并为同一个
	 * 召唤/不召唤按钮）的服务器端行为：
	 * 1. 未绑定助手时 dismissWithBlock 幂等（无助手可送走也不报错）；
	 * 2. summonWithBlock 召唤 → 助手绑定该方块、方块亮起；
	 * 3. 别人的助手绑定该方块时 dismissWithBlock 应被拒绝（助手仍在）；
	 * 4. 主人 dismissWithBlock 送走 → 助手消失、方块熄灭（“不召唤”状态）；
	 * 5. 可反复切换：再次 summonWithBlock 又能重新绑定。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
	public void configScreenSummonDismissToggle(GameTestHelper helper) {
		dismissAllPlayerBots();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		BlockPos platform = new BlockPos(4, 1, 4);
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				helper.setBlock(platform.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
			}
		}
		for (int dy = 0; dy <= 3; dy++) {
			helper.setBlock(platform.offset(0, dy, 0), Blocks.AIR.defaultBlockState());
		}
		net.minecraft.world.phys.Vec3 playerPos = helper.absoluteVec(
				new net.minecraft.world.phys.Vec3(4.5, 2, 4.5));
		player.teleportTo(playerPos.x, playerPos.y, playerPos.z);

		BlockPos blockPos = new BlockPos(4, 1, 1);
		configureMockBlock(helper, blockPos, player);
		ResourceKey<net.minecraft.world.level.Level> dimension = player.level().dimension();
		BlockPos absPos = helper.absolutePos(blockPos);
		ServerLevel level = (ServerLevel) helper.getLevel();
		GlobalPos bindPos = GlobalPos.of(dimension, absPos);

		helper.startSequence()
				.thenExecute(() -> {
					// 1) 未绑定：dismissWithBlock 幂等（合并按钮在“召唤”状态，点了也不会出错）
					AiConfigHandler.dismissWithBlock(player, absPos, dimension);
					if (!com.swaydy.opencraft.assistant.AssistantFacade.findAssistantsFor(player).isEmpty()) {
						throw new AssertionError("无助手绑定时送走不应影响任何助手");
					}
					// 2) 召唤（合并按钮的“召唤”半）→ 玩家 bot 绑定该方块（下一 tick 才进入查找表）
					AiConfigHandler.summonWithBlock(player, absPos, dimension);
				})
				.thenIdle(5)
				.thenExecute(() -> {
					if (com.swaydy.opencraft.assistant.AssistantFacade.findBoundTo(level, bindPos) == null) {
						throw new AssertionError("用方块召唤后应绑定一个玩家 bot 到该方块");
					}
					if (!helper.getLevel().getBlockState(absPos).getValue(AiLogoBlock.POWERED)) {
						throw new AssertionError("召唤后绑定方块应亮起");
					}
					// 3) 别人尝试送走 → 被拒绝，助手仍在（合并按钮对他人显示为禁用态）
					ServerPlayer other = helper.makeMockServerPlayerInLevel();
					other.teleportTo(playerPos.x, playerPos.y, playerPos.z);
					AiConfigHandler.dismissWithBlock(other, absPos, dimension);
					if (com.swaydy.opencraft.assistant.AssistantFacade.findBoundTo(level, bindPos) == null) {
						throw new AssertionError("别人的助手不应被非主人送走");
					}
					// 4) 主人送走（合并按钮的“不召唤”半）→ 助手消失、方块熄灭
					AiConfigHandler.dismissWithBlock(player, absPos, dimension);
					if (!com.swaydy.opencraft.assistant.AssistantFacade.findAssistantsFor(player).isEmpty()) {
						throw new AssertionError("送走后助手应消失");
					}
					if (helper.getLevel().getBlockState(absPos).getValue(AiLogoBlock.POWERED)) {
						throw new AssertionError("送走后绑定方块应熄灭");
					}
					// 5) 再次召唤 → 又能重新绑定（反复切换）
					AiConfigHandler.summonWithBlock(player, absPos, dimension);
				})
				.thenIdle(5)
				.thenExecute(() -> {
					if (com.swaydy.opencraft.assistant.AssistantFacade.findBoundTo(level, bindPos) == null) {
						throw new AssertionError("再次召唤后助手应重新绑定");
					}
					// 清理
					AiConfigHandler.dismissWithBlock(player, absPos, dimension);
					AiCompanionService.resetAllHistory(player);
					helper.succeed();
				});
	}

	/**
	 * 验证“助手与绑定方块共存”：
	 * 1. 召唤助手绑定方块 → 助手存在；
	 * 2. 破坏绑定方块 → 助手应随之消失（反之：助手送走时方块保留已在 lifecycle 测试验证）。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
	public void assistantVanishesWithBlock(GameTestHelper helper) {
		dismissAllPlayerBots();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		// 平台 + 玩家 + 配置方块
		BlockPos platform = new BlockPos(4, 1, 4);
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				helper.setBlock(platform.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
			}
		}
		net.minecraft.world.phys.Vec3 playerPos = helper.absoluteVec(
				new net.minecraft.world.phys.Vec3(4.5, 2, 4.5));
		player.teleportTo(playerPos.x, playerPos.y, playerPos.z);
		BlockPos configBlockPos = new BlockPos(4, 1, 1);
		configureMockBlock(helper, configBlockPos, player);

		helper.startSequence()
				.thenExecute(() -> {
					com.swaydy.opencraft.assistant.AiAssistant assistant =
							com.swaydy.opencraft.assistant.AssistantFacade.summonNearest(player);
					if (assistant == null) {
						throw new AssertionError("summonNearest 返回 null");
					}
					if (com.swaydy.opencraft.assistant.AssistantFacade.findAssistantsFor(player).isEmpty()) {
						throw new AssertionError("召唤后助手应存在");
					}
				})
				.thenIdle(5)
				.thenExecute(() -> {
					// 破坏绑定方块（不掉落，避免干扰）
					BlockPos abs = helper.absolutePos(configBlockPos);
					boolean broken = helper.getLevel().destroyBlock(abs, false, player, 3);
					if (!broken) {
						throw new AssertionError("方块未能破坏");
					}
				})
				.thenIdle(5)
				.thenExecute(() -> {
					// 方块没了 → 助手必须一起消失（共存规则）
					if (!com.swaydy.opencraft.assistant.AssistantFacade.findAssistantsFor(player).isEmpty()) {
						throw new AssertionError("绑定方块被破坏后助手应随之消失");
					}
					helper.succeed();
				});
	}

	/**
	 * 验证“多助手共存”（每个 AI 徽标方块绑定一个助手）：
	 * 1. 放置两个 AI 徽标方块 A（近）、B（远），用各自方块召唤 → 两个不同助手；
	 * 2. 再次用 A 召唤 → 幂等返回同一个实例（数量不变）；
	 * 3. 各助手使用自己方块的独立配置；两个绑定方块同时亮起；
	 * 4. “最近助手”路由 = A（A 方块离玩家更近）；
	 * 5. 送走最近助手 → 只剩 B；A 方块熄灭、B 方块仍亮；
	 * 6. 破坏 B 方块 → B 助手消失、A 助手也已不在（多助手按方块独立管理）。
	 */
	/**
	 * 验证“助手必须绑定 AI 徽标方块”：
	 * 附近（48 格内）没有任何未绑定的 AI 徽标方块时，召唤应被拒绝（返回 null 且不创建实体）。
	 *
	 * 注意：gametest 的多个测试在同一个持久世界里并行运行，其它测试会放置 AI 徽标
	 * 方块；因此把 mock 玩家先传送到远离所有测试结构的坐标，保证 48 格内没有方块。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 120)
	public void summonRequiresConfigBlock(GameTestHelper helper) {
		dismissAllPlayerBots();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		// 平台 + 玩家（不放任何 AI 徽标方块）
		BlockPos platform = new BlockPos(4, 1, 4);
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				helper.setBlock(platform.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
			}
		}
		// 传送到远离所有测试结构的坐标（附近 48 格内不可能有 AI 徽标方块）
		player.teleportTo(60000, 80, 60000);

		helper.startSequence()
				.thenExecute(() -> {
					if (com.swaydy.opencraft.assistant.AssistantFacade.summonNearest(player) != null) {
						throw new AssertionError("附近没有 AI 徽标方块时不应召唤助手");
					}
					if (!com.swaydy.opencraft.assistant.AssistantFacade.findAssistantsFor(player).isEmpty()) {
						throw new AssertionError("无方块时不应创建助手");
					}
					helper.succeed();
				});
	}

	/**
	 * 验证“无绑定助手一律消失”：
	 * 直接生成一个 configBlock == null 的助手（模拟刷怪蛋/旧存档遗留），
	 * 它应在约 40 tick 内被安全网自动清除。
	 */
	/**
	 * 验证 AI 徽标方块可获取性：
	 * 1. 有合成配方（opencraft:ai_logo_block 已注册到配方管理器）；
	 * 2. 徒手挖掘（空手）会掉落方块本身。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 120)
	public void aiLogoBlockMiningAndRecipe(GameTestHelper helper) {
		dismissAllPlayerBots();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		// 把玩家放到结构内，放置 AI 徽标方块
		net.minecraft.world.phys.Vec3 playerPos = helper.absoluteVec(
				new net.minecraft.world.phys.Vec3(4.5, 2, 4.5));
		player.teleportTo(playerPos.x, playerPos.y, playerPos.z);
		BlockPos blockPos = new BlockPos(4, 3, 4);
		helper.setBlock(blockPos, ModBlocks.AI_LOGO_BLOCK.defaultBlockState());

		helper.startSequence()
				.thenExecute(() -> {
					// 1) 配方已注册
					ResourceKey<Recipe<?>> recipeKey = ResourceKey.create(
							Registries.RECIPE, com.swaydy.opencraft.OpenCraftMod.id("ai_logo_block"));
					if (player.level().getServer().getRecipeManager().byKey(recipeKey).isEmpty()) {
						throw new AssertionError("缺少 AI 徽标方块合成配方");
					}
					// 2) 直接查战利品表：空手（TOOL=空物品）应产出方块本身
					BlockPos abs = helper.absolutePos(blockPos);
					net.minecraft.world.level.block.state.BlockState state =
							helper.getLevel().getBlockState(abs);
					java.util.List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(
							state, (ServerLevel) helper.getLevel(),
							abs, null, player, ItemStack.EMPTY);
					boolean foundInDrops = drops.stream().anyMatch(
							d -> d.is(ModBlocks.AI_LOGO_BLOCK.asItem()));
					if (!foundInDrops) {
						throw new AssertionError("战利品表未产出 AI 徽标方块（drop 数量 "
								+ drops.size() + "）");
					}
					// 3) 先把玩家传送开（避免自动拾取掉落物干扰验证），再徒手破坏方块
					if (!player.getMainHandItem().isEmpty()) {
						throw new AssertionError("测试前提：玩家应空手");
					}
					player.teleportTo(abs.getX() + 0.5, abs.getY() + 30, abs.getZ() + 0.5);
					boolean broken = helper.getLevel().destroyBlock(abs, true, player, 3);
					if (!broken) {
						throw new AssertionError("方块未能破坏");
					}
				})
				.thenIdle(3)
				.thenExecute(() -> {
					// 4) 掉落物应作为物品实体出现在方块附近（玩家已被传送走，不会被拾取）。
					//    注意：findEntities(Vec3, double) 的参数是结构相对坐标（内部会 absoluteVec）
					List<ItemEntity> items = helper.findEntities(EntityType.ITEM,
							new net.minecraft.world.phys.Vec3(4.5, 3, 4.5), 6.0);
					for (ItemEntity item : items) {
						if (item.getItem().is(ModBlocks.AI_LOGO_BLOCK.asItem())) {
							helper.succeed();
							return;
						}
					}
					throw new AssertionError("徒手挖掘后未掉落 AI 徽标方块");
				});
	}

	/**
	 * 验证“多助手同时存在时如何指定和哪个助手对话”：
	 * 1. AssistantFacade.findAssistantsBySelector 的选择器匹配（纯名字 / 显示名 / 紧凑 名字(坐标) / 未知 / 重名）；
	 * 2. /opencraft ask <消息>（不带名字）→ 路由到“最近”的助手（A 近 B 远），只有 A 的历史增长；
	 * 3. /opencraft ask 小红 <消息> → 精确指定 B，只有 B 的历史增长；
	 * 4. 用带坐标的显示名（引号括起）指定 → 同样命中 B；
	 * 5. 名字不存在 → 提示后回退到最近的助手（A 的历史增长）；
	 * 6. 两个助手同名 → 报“歧义”失败（指令返回 0），谁的历史都不增长。
	 *
	 * 历史按“助手绑定的方块”键控（一方块 = 一助手 = 一份记忆），ask() 会同步把
	 * user 消息写入目标助手的记忆，因此“路由到哪个助手”可以立即断言，无需等回复。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 8000)
	public void askTargetsSpecificAssistant(GameTestHelper helper) {
		dismissAllPlayerBots();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		// 平台 + 玩家
		BlockPos platform = new BlockPos(4, 1, 4);
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				helper.setBlock(platform.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
			}
		}
		for (int dy = 0; dy <= 3; dy++) {
			helper.setBlock(platform.offset(0, dy, 0), Blocks.AIR.defaultBlockState());
		}
		net.minecraft.world.phys.Vec3 playerPos = helper.absoluteVec(
				new net.minecraft.world.phys.Vec3(4.5, 2, 4.5));
		player.teleportTo(playerPos.x, playerPos.y, playerPos.z);

		// 两个 AI 徽标方块：A 近（默认名字 小智）、B 远（名字 小红）
		BlockPos blockA = new BlockPos(4, 1, 6);
		BlockPos blockB = new BlockPos(1, 1, 4);
		configureMockBlock(helper, blockA, player);
		helper.setBlock(blockB, ModBlocks.AI_LOGO_BLOCK.defaultBlockState());
		AiLogoBlockEntity blockBEntity = helper.getBlockEntity(blockB, AiLogoBlockEntity.class);
		if (blockBEntity == null) {
			throw new AssertionError("B 方块实体未创建");
		}
		blockBEntity.getConfig().baseUrl = "http://127.0.0.1:18923/v1";
		blockBEntity.getConfig().apiKey = "test-key-123";
		blockBEntity.getConfig().model = "mock-model-b";
		blockBEntity.getConfig().name = "小红";
		blockBEntity.markConfigChanged();

		ServerLevel level = (ServerLevel) helper.getLevel();
		GlobalPos absA = GlobalPos.of(level.dimension(), helper.absolutePos(blockA));
		GlobalPos absB = GlobalPos.of(level.dimension(), helper.absolutePos(blockB));
		BlockPos absBBlock = helper.absolutePos(blockB);
		String bXyz = absBBlock.getX() + "," + absBBlock.getY() + "," + absBBlock.getZ();

		// 各步的“历史条数快照”（gametest 服务器按真实时间跑，而流式回复在独立线程，
		// 因此用 thenWaitUntil 轮询等回复写入历史，而不是固定 thenIdle）
		int[] aSize = {0};
		int[] bSize = {0};

		helper.startSequence()
				.thenExecute(() -> {
					com.swaydy.opencraft.assistant.AiAssistant a = com.swaydy.opencraft.assistant.AssistantFacade.summon(player, absA);					com.swaydy.opencraft.assistant.AiAssistant b = com.swaydy.opencraft.assistant.AssistantFacade.summon(player, absB);					if (a == null || b == null || a == b) {
						throw new AssertionError("两个助手召唤失败");
					}
				})
				.thenIdle(5)
				.thenExecute(() -> {
					// 1) 选择器匹配
					if (com.swaydy.opencraft.assistant.AssistantFacade.findAssistantsBySelector(player, "小红").size() != 1) {
						throw new AssertionError("纯名字应匹配到 B");
					}
					if (com.swaydy.opencraft.assistant.AssistantFacade.findAssistantsBySelector(player, "小红 (" + bXyz + ")").size() != 1) {
						throw new AssertionError("显示名应匹配到 B");
					}
					if (com.swaydy.opencraft.assistant.AssistantFacade.findAssistantsBySelector(player, "小红(" + bXyz + ")").size() != 1) {
						throw new AssertionError("紧凑 名字(坐标) 应匹配到 B");
					}
					if (com.swaydy.opencraft.assistant.AssistantFacade.findAssistantsBySelector(player, "小智").size() != 1) {
						throw new AssertionError("小智 应匹配到 A");
					}
					if (!com.swaydy.opencraft.assistant.AssistantFacade.findAssistantsBySelector(player, "不存在的助手").isEmpty()) {
						throw new AssertionError("未知名字不应匹配");
					}
					// 2) 不带名字 → 问“最近”的助手（A）：只有 A 的历史立即增长（user 消息同步入史）
					aSize[0] = AiCompanionService.historySize(absA);
					bSize[0] = AiCompanionService.historySize(absB);
					int result = runCommand(helper, player, "opencraft ask 给最近的助手打个招呼");
					if (result != 1) {
						throw new AssertionError("ask 指令应执行成功，实际 " + result);
					}
					if (AiCompanionService.historySize(absA) != aSize[0] + 1
							|| AiCompanionService.historySize(absB) != bSize[0]) {
						throw new AssertionError("ask <消息> 应路由到最近的助手（A），实际 A="
								+ AiCompanionService.historySize(absA) + " B="
								+ AiCompanionService.historySize(absB));
					}
				})
				.thenWaitUntil(() -> {
					// 等 A 的流式回复写入历史
					if (AiCompanionService.historySize(absA) < aSize[0] + 2) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
							net.minecraft.network.chat.Component.literal("等待 A 的回复写入历史…"),
							(int) helper.getTick());
					}
				})
				.thenExecute(() -> {
					// 回复应只落在 A 的历史里
					if (AiCompanionService.historySize(absB) != bSize[0]) {
						throw new AssertionError("A 的回复不应进入 B 的历史，实际 B="
								+ AiCompanionService.historySize(absB));
					}
					// 3) 指定名字 小红 → 只有 B 的历史增长
					aSize[0] = AiCompanionService.historySize(absA);
					bSize[0] = AiCompanionService.historySize(absB);
					int result = runCommand(helper, player, "opencraft ask 小红 你好小红");
					if (result != 1) {
						throw new AssertionError("ask <名字> 指令应执行成功，实际 " + result);
					}
					if (AiCompanionService.historySize(absB) != bSize[0] + 1
							|| AiCompanionService.historySize(absA) != aSize[0]) {
						throw new AssertionError("ask 小红 <消息> 应只路由到 B，实际 A="
								+ AiCompanionService.historySize(absA) + " B="
								+ AiCompanionService.historySize(absB));
					}
				})
				.thenWaitUntil(() -> {
					// 等 B 的流式回复写入历史
					if (AiCompanionService.historySize(absB) < bSize[0] + 2) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
							net.minecraft.network.chat.Component.literal("等待 B 的回复写入历史…"),
							(int) helper.getTick());
					}
				})
				.thenExecute(() -> {
					// B 的回复不应进入 A 的历史
					if (AiCompanionService.historySize(absA) != aSize[0]) {
						throw new AssertionError("B 的回复不应进入 A 的历史，实际 A="
								+ AiCompanionService.historySize(absA));
					}
					// 4) 带坐标的显示名（引号括起）指定 → 同样命中 B
					aSize[0] = AiCompanionService.historySize(absA);
					bSize[0] = AiCompanionService.historySize(absB);
					int result = runCommand(helper, player,
							"opencraft ask \"小红 (" + bXyz + ")\" 用显示名找你");
					if (result != 1) {
						throw new AssertionError("ask <显示名> 指令应执行成功，实际 " + result);
					}
					if (AiCompanionService.historySize(absB) != bSize[0] + 1
							|| AiCompanionService.historySize(absA) != aSize[0]) {
						throw new AssertionError("带坐标的显示名应路由到 B，实际 A="
								+ AiCompanionService.historySize(absA) + " B="
								+ AiCompanionService.historySize(absB));
					}
				})
				.thenWaitUntil(() -> {
					if (AiCompanionService.historySize(absB) < bSize[0] + 2) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
							net.minecraft.network.chat.Component.literal("等待 B 的回复写入历史…"),
							(int) helper.getTick());
					}
				})
				.thenExecute(() -> {
					// 5) 名字不存在 → 提示并回退到最近的助手（A）
					aSize[0] = AiCompanionService.historySize(absA);
					bSize[0] = AiCompanionService.historySize(absB);
					int result = runCommand(helper, player, "opencraft ask 不存在的人 你还好吗");
					if (result != 1) {
						throw new AssertionError("未知名字应回退（指令仍返回 1），实际 " + result);
					}
					if (AiCompanionService.historySize(absA) != aSize[0] + 1
							|| AiCompanionService.historySize(absB) != bSize[0]) {
						throw new AssertionError("未知名字应回退到最近的助手（A），实际 A="
								+ AiCompanionService.historySize(absA) + " B="
								+ AiCompanionService.historySize(absB));
					}
				})
				.thenWaitUntil(() -> {
					if (AiCompanionService.historySize(absA) < aSize[0] + 2) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
							net.minecraft.network.chat.Component.literal("等待 A 的回复写入历史…"),
							(int) helper.getTick());
					}
				})
				.thenExecute(() -> {
					// 6) 两个助手同名 → 歧义失败，谁的历史都不增长
					AiLogoBlockEntity bEntity = helper.getBlockEntity(blockB, AiLogoBlockEntity.class);
					if (bEntity == null) {
						throw new AssertionError("B 方块实体不见了");
					}
					bEntity.getConfig().name = "小智"; // 与 A 同名
					bEntity.markConfigChanged();
					aSize[0] = AiCompanionService.historySize(absA);
					bSize[0] = AiCompanionService.historySize(absB);
					int result = runCommand(helper, player, "opencraft ask 小智 你好");
					if (result != 0) {
						throw new AssertionError("同名时应返回失败（歧义），实际 " + result);
					}
					if (AiCompanionService.historySize(absA) != aSize[0]
							|| AiCompanionService.historySize(absB) != bSize[0]) {
						throw new AssertionError("同名歧义时不应询问任何助手，实际 A="
								+ AiCompanionService.historySize(absA) + " B="
								+ AiCompanionService.historySize(absB));
					}
					// 清理：送走全部助手并清空历史
					com.swaydy.opencraft.assistant.AssistantFacade.dismissAllFor(player);
					AiCompanionService.resetAllHistory(player);
					helper.succeed();
				});
	}

	/**
	 * 验证右键打开的“双面板助手背包菜单”（{@link AssistantInventoryMenu}）——
	 * 两半都是原版按 E 的背包（护甲 + 副手 + 2×2 合成 + 主背包 + 快捷栏）：
	 * 1. 布局：92 个槽位（每侧 46），坐标与原版 InventoryMenu 一致（右面板 x+180）；
	 * 2. 装备槽直通实体：给助手戴头盔 → 菜单护甲槽读到同一件；
	 * 3. 原版合成：玩家侧 2×2 合成格放 4 块橡木板 → 结果槽出工作台（原版
	 *    slotChangedCraftingGrid 配方匹配直接复用）；
	 * 4. Shift + 点击双向转移：助手 → 玩家（优先快捷栏）、玩家 → 助手；
	 * 5. 助手被送走（实体移除）后菜单失效（服务端自动关闭界面）。
	 *
	 * <p>直接用与实体 {@code openInventoryScreen} 相同的方式构造菜单——
	 * “打开菜单”的网络发包在 mock 连接上是空操作，这里只验证菜单逻辑本身。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 120)
	public void assistantInventoryMenuLayoutAndTransfer(GameTestHelper helper) {
		dismissAllPlayerBots();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		// 平台 + 玩家 + 配置方块 + 召唤助手（右键打开的正是该助手的背包）
		BlockPos platform = new BlockPos(4, 1, 4);
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				helper.setBlock(platform.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
			}
		}
		net.minecraft.world.phys.Vec3 playerPos = helper.absoluteVec(
				new net.minecraft.world.phys.Vec3(4.5, 2, 4.5));
		player.teleportTo(playerPos.x, playerPos.y, playerPos.z);
		BlockPos configBlockPos = new BlockPos(4, 1, 1);
		configureMockBlock(helper, configBlockPos, player);
		ServerLevel level = (ServerLevel) helper.getLevel();
		GlobalPos bindPos = GlobalPos.of(level.dimension(), helper.absolutePos(configBlockPos));

		helper.startSequence()
				.thenExecute(() -> {
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer assistant =
							AiCompanionService.summonFor(player, bindPos);
					if (assistant == null) {
						throw new AssertionError("召唤助手失败");
					}
					AssistantInventoryMenu menu = new AssistantInventoryMenu(
							1, player.getInventory(), assistant.getInventory(),
							() -> !assistant.isRemoved());

					// 1) 布局：每侧 46 格（结果/合成4/护甲4/副手/主背包27/快捷栏9）
					if (menu.slots.size() != AssistantInventoryMenu.TOTAL_SLOTS) {
						throw new AssertionError("应有 " + AssistantInventoryMenu.TOTAL_SLOTS
								+ " 个槽位，实际 " + menu.slots.size());
					}
					assertSlot(menu, AssistantInventoryMenu.LEFT_RESULT, 154, 28, "助手结果槽");
					assertSlot(menu, AssistantInventoryMenu.LEFT_CRAFT_START, 98, 18, "助手合成格");
					assertSlot(menu, AssistantInventoryMenu.LEFT_ARMOR_START, 8, 8, "助手头盔槽");
					assertSlot(menu, AssistantInventoryMenu.LEFT_OFFHAND, 77, 62, "助手副手槽");
					assertSlot(menu, AssistantInventoryMenu.LEFT_INV_START, 8, 84, "助手主背包首格");
					assertSlot(menu, 37, 8, 142, "助手快捷栏首格");
					int rx = AssistantInventoryMenu.RIGHT_PANEL_X;
					assertSlot(menu, AssistantInventoryMenu.RIGHT_RESULT, 154 + rx, 28, "玩家结果槽");
					assertSlot(menu, AssistantInventoryMenu.RIGHT_CRAFT_START, 98 + rx, 18, "玩家合成格");
					assertSlot(menu, AssistantInventoryMenu.RIGHT_ARMOR_START, 8 + rx, 8, "玩家头盔槽");
					assertSlot(menu, AssistantInventoryMenu.RIGHT_OFFHAND, 77 + rx, 62, "玩家副手槽");
					assertSlot(menu, AssistantInventoryMenu.RIGHT_INV_START, 8 + rx, 84, "玩家主背包首格");
					assertSlot(menu, 83, 8 + rx, 142, "玩家快捷栏首格");

					// 2) 装备槽直通实体：给助手戴头盔 → 菜单护甲槽读到同一件
					assistant.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
							new ItemStack(Items.DIAMOND_HELMET));
					if (menu.slots.get(AssistantInventoryMenu.LEFT_ARMOR_START).getItem()
							.getItem() != Items.DIAMOND_HELMET) {
						throw new AssertionError("助手头盔槽应实时读到实体装备");
					}

					// 3) 原版合成：玩家侧 2×2 格放 4 块橡木板 → 结果槽出工作台
					for (int i = 0; i < 4; i++) {
						menu.slots.get(AssistantInventoryMenu.RIGHT_CRAFT_START + i)
								.set(new ItemStack(Items.OAK_PLANKS));
					}
					if (menu.slots.get(AssistantInventoryMenu.RIGHT_RESULT).getItem()
							.getItem() != Items.CRAFTING_TABLE) {
						throw new AssertionError("玩家侧合成格应产出工作台（原版配方匹配）");
					}

					// 4) Shift 转移：助手 → 玩家（reverse 优先快捷栏，落到玩家快捷栏最后一格）
					assistant.getInventory().setItem(9, new ItemStack(Items.STONE, 3));
					ItemStack moved = menu.quickMoveStack(player, AssistantInventoryMenu.LEFT_INV_START);
					if (moved.getItem() != Items.STONE || moved.getCount() != 3) {
						throw new AssertionError("Shift 转移应返回被移动的物品");
					}
					if (!assistant.getInventory().getItem(9).isEmpty()) {
						throw new AssertionError("转移后原助手格应清空");
					}
					if (player.getInventory().getItem(8).getItem() != Items.STONE
							|| player.getInventory().getItem(8).getCount() != 3) {
						throw new AssertionError("石头应转移到玩家快捷栏（优先快捷栏）");
					}
					if (menu.slots.get(91).getItem().getItem() != Items.STONE) {
						throw new AssertionError("玩家快捷栏末格（容器格 8）应映射到菜单末格");
					}

					// 玩家 → 助手（玩家主背包容器格 20 = 菜单 slot 56+11=67）
					player.getInventory().setItem(20, new ItemStack(Items.DIAMOND, 2));
					moved = menu.quickMoveStack(player, AssistantInventoryMenu.RIGHT_INV_START + 11);
					if (moved.getItem() != Items.DIAMOND) {
						throw new AssertionError("玩家 → 助手 Shift 转移应成功");
					}
					if (!player.getInventory().getItem(20).isEmpty()) {
						throw new AssertionError("转移后玩家原格应清空");
					}
					if (assistant.getInventory().getItem(9).getItem() != Items.DIAMOND
							|| assistant.getInventory().getItem(9).getCount() != 2) {
						throw new AssertionError("钻石应转移到助手背包");
					}

					// 5) 助手被送走 → 菜单失效
					if (!menu.stillValid(player)) {
						throw new AssertionError("助手还在时菜单应有效");
					}
					com.swaydy.opencraft.assistant.AssistantFacade.dismiss(assistant);
					if (menu.stillValid(player)) {
						throw new AssertionError("助手消失后菜单应失效");
					}
					helper.succeed();
				});
	}

	/** 断言菜单槽位的屏幕坐标（相对 leftPos/topPos，与原版纹理洞对齐）。 */
	private static void assertSlot(AssistantInventoryMenu menu, int index, int x, int y, String what) {
		net.minecraft.world.inventory.Slot slot = menu.slots.get(index);
		if (slot.x != x || slot.y != y) {
			throw new AssertionError(what + "应在 (" + x + "," + y + ")，实际 ("
					+ slot.x + "," + slot.y + ")");
		}
	}

	/**
	 * 聚焦验证原版式挖掘状态机（START → 工具速度推进 → STOP）：
	 * 直接 startMining（不经走路），断言按预计 tick 数破坏方块。
	 * 木镐挖石头 ≈ 1.15s（23 tick），与真实玩家一致。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
	public void botMinesWithVanillaProgression(GameTestHelper helper) {
		dismissAllPlayerBots();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		BlockPos platform = new BlockPos(4, 1, 4);
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				helper.setBlock(platform.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
			}
		}
		net.minecraft.world.phys.Vec3 playerPos = helper.absoluteVec(
				new net.minecraft.world.phys.Vec3(4.5, 2, 4.5));
		player.teleportTo(playerPos.x, playerPos.y, playerPos.z);
		BlockPos configBlockPos = new BlockPos(4, 1, 1);
		configureMockBlock(helper, configBlockPos, player);
		ServerLevel level = (ServerLevel) helper.getLevel();
		GlobalPos bindPos = GlobalPos.of(level.dimension(), helper.absolutePos(configBlockPos));
		BlockPos stoneRel = new BlockPos(5, 1, 4);
		helper.setBlock(stoneRel, Blocks.STONE.defaultBlockState());

		helper.startSequence()
				.thenExecute(() -> {
					com.swaydy.opencraft.assistant.AiAssistant summoned =
							AiCompanionService.summonFor(player, bindPos);
					if (!(summoned instanceof com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot)) {
						throw new AssertionError("召唤玩家形态助手失败");
					}
					bot.teleportTo(playerPos.x, playerPos.y, playerPos.z);
					bot.getInventory().setItem(bot.getInventory().getSelectedSlot(),
							new ItemStack(Items.WOODEN_PICKAXE, 1));
				})
				.thenIdle(10) // 让 bot 落到平台并让 movement.tick 把真实着地状态写回 onGround（挖掘速度依赖它）
				.thenExecute(() -> {
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot == null) {
						throw new AssertionError("玩家形态助手不应消失");
					}
					BlockPos stone = helper.absolutePos(stoneRel);
					// 直接开始挖掘（不经过走路），断言按工具速度需要 ~23 tick（木镐 vs 石头）
					int ticks = bot.movement().startMining(bot, level, stone);
					if (ticks < 0) {
						throw new AssertionError("startMining 拒绝开始（范围/方块/工具问题），返回 " + ticks);
					}
					if (ticks < 10 || ticks > 60) {
						throw new AssertionError("木镐挖石头应约 23 tick（真实速度），实际 " + ticks
								+ "（若 ≈113：onGround 未生效的原版 ÷5 悬空减速）");
					}
				})
				.thenWaitUntil(() -> {
					BlockPos stone = helper.absolutePos(stoneRel);
					if (!helper.getLevel().getBlockState(stone).is(Blocks.AIR)) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal("等待原版进度挖掘完成…"),
								(int) helper.getTick());
					}
				})
				.thenExecute(() -> helper.succeed());
	}

	/**
	 * 端到端复现“右键玩家形态助手打开双面板背包”的服务器侧全链路：
	 * 1. 真实召唤玩家形态 bot，给它头盔/盾牌/镐子；
	 * 2. 直接调用 {@code openMenu}（**不经过实体里的 try/catch**，任何异常都会带栈浮出）；
	 * 3. 断言菜单真的挂到玩家身上（openMenu 若在 initMenu/同步阶段抛异常，
	 *    containerMenu 不会更新——客户端会打开一个“死”界面：左面板空、点击无效）；
	 * 4. 断言左侧装备/副手/背包槽实时读到 bot 的真实装备与物品；
	 * 5. 模拟“拿着头盔点击助手头盔槽”：bot 真的戴上头盔。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
	public void assistantBotInventoryOpensEndToEnd(GameTestHelper helper) {
		dismissAllPlayerBots();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		// 平台 + 玩家 + 配置方块
		BlockPos platform = new BlockPos(4, 1, 4);
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				helper.setBlock(platform.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
			}
		}
		net.minecraft.world.phys.Vec3 playerPos = helper.absoluteVec(
				new net.minecraft.world.phys.Vec3(4.5, 2, 4.5));
		player.teleportTo(playerPos.x, playerPos.y, playerPos.z);
		BlockPos configBlockPos = new BlockPos(4, 1, 1);
		configureMockBlock(helper, configBlockPos, player);
		ServerLevel level = (ServerLevel) helper.getLevel();
		GlobalPos bindPos = GlobalPos.of(level.dimension(), helper.absolutePos(configBlockPos));

		helper.startSequence()
				.thenExecute(() -> {
					// 1) 真实召唤玩家形态 bot（与玩家右键使用同一条路径）
					com.swaydy.opencraft.assistant.AiAssistant summoned =
							AiCompanionService.summonFor(player, bindPos);
					if (!(summoned instanceof com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot)) {
						throw new AssertionError("召唤玩家形态助手失败: " + summoned);
					}
					// 给 bot 装备：头盔 + 副手盾牌 + 背包里一把镐子
					bot.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,
							new ItemStack(Items.DIAMOND_HELMET));
					bot.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,
							new ItemStack(Items.SHIELD));
					bot.getInventory().add(new ItemStack(Items.DIAMOND_PICKAXE));

					// 2) 直接 openMenu——不走实体里的 try/catch，异常会原样抛出（带栈）
					player.openMenu(new net.minecraft.world.SimpleMenuProvider(
							(id, playerInv, p) -> new AssistantInventoryMenu(
									id, playerInv, bot.getInventory(), () -> !bot.isRemoved()),
							bot.getDisplayName()));

					// 3) 菜单必须真的挂上（若 initMenu/初始同步抛异常，这里不会更新）
					if (!(player.containerMenu instanceof AssistantInventoryMenu menu)) {
						throw new AssertionError("openMenu 后 containerMenu 应为 AssistantInventoryMenu，实际 "
								+ player.containerMenu.getClass().getSimpleName());
					}
					// 4) 左侧槽位实时读到 bot 的装备与物品
					if (menu.slots.get(AssistantInventoryMenu.LEFT_ARMOR_START).getItem()
							.getItem() != Items.DIAMOND_HELMET) {
						throw new AssertionError("bot 的头盔应出现在左侧头盔槽");
					}
					if (menu.slots.get(AssistantInventoryMenu.LEFT_OFFHAND).getItem()
							.getItem() != Items.SHIELD) {
						throw new AssertionError("bot 的盾牌应出现在左侧副手槽");
					}
					boolean pickaxeVisible = false;
					for (int i = AssistantInventoryMenu.LEFT_INV_START; i < 46; i++) {
						if (menu.slots.get(i).getItem().getItem() == Items.DIAMOND_PICKAXE) {
							pickaxeVisible = true;
							break;
						}
					}
					if (!pickaxeVisible) {
						throw new AssertionError("bot 背包里的镐子应出现在左侧背包/快捷栏");
					}
					// 右侧第一格应是玩家自己的背包
					if (menu.slots.get(AssistantInventoryMenu.RIGHT_INV_START).container != player.getInventory()) {
						throw new AssertionError("右面板应是玩家自己的背包");
					}

					// 5) 模拟“拿着胸甲点击助手胸甲槽”：bot 真的穿上
					menu.setCarried(new ItemStack(Items.DIAMOND_CHESTPLATE));
					menu.clicked(AssistantInventoryMenu.LEFT_ARMOR_START + 1, 0,
							net.minecraft.world.inventory.ClickType.PICKUP, player);
					if (bot.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)
							.getItem() != Items.DIAMOND_CHESTPLATE) {
						throw new AssertionError("点击后 bot 应穿上胸甲，实际 "
								+ bot.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST));
					}
					if (!menu.getCarried().isEmpty()) {
						throw new AssertionError("放上后光标物品应收走");
					}
					// 6) 再取下：空光标点击助手胸甲槽 → bot 脱下（装备槽绑的是 bot 的
					//    Inventory 原版索引，读写即真实装备）
					menu.clicked(AssistantInventoryMenu.LEFT_ARMOR_START + 1, 0,
							net.minecraft.world.inventory.ClickType.PICKUP, player);
					if (!bot.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).isEmpty()) {
						throw new AssertionError("取下后 bot 不应再穿胸甲");
					}
					if (menu.getCarried().getItem() != Items.DIAMOND_CHESTPLATE) {
						throw new AssertionError("取下后胸甲应在光标上");
					}

					// 7) 客户端式菜单（MenuType 工厂同款构造）对称性冒烟：所有槽位都是
					//    容器绑定——客户端同步（container_set_content → slot.set）只会写
					//    容器、绝不触碰实体装备 API（右键助手闪退的根因就是客户端槽位
					//    调了实体的 setItemSlot → onEquipItem 的 ServerLevel 强转）
					AssistantInventoryMenu clientMenu = new AssistantInventoryMenu(0, player.getInventory());
					for (net.minecraft.world.inventory.Slot slot : clientMenu.slots) {
						slot.set(new ItemStack(Items.STONE)); // 任何槽位被同步写入都不应抛异常
						slot.set(ItemStack.EMPTY);
					}
					// 玩家侧头盔槽写头盔 → 落到玩家 Inventory 的原版装备索引
					clientMenu.slots.get(AssistantInventoryMenu.RIGHT_ARMOR_START)
							.set(new ItemStack(Items.DIAMOND_HELMET));
					if (player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)
							.getItem() != Items.DIAMOND_HELMET) {
						throw new AssertionError("玩家侧头盔槽应写入玩家 Inventory 的装备索引");
					}
				})
				.thenIdle(3) // 让 bot 的 doTick（LivingEntity.tick）跑装备检测：头盔修饰器应被加上
				.thenExecute(() -> {
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot == null) {
						throw new AssertionError("玩家形态助手不应消失");
					}
					// 装备修饰器只会在 doTick → LivingEntity.tick 的装备检测里应用——
					// 护甲值 >0 是“bot 与真实玩家同款 tick 链真正在跑”的行为证据
					if (bot.getArmorValue() < 1) {
						throw new AssertionError("戴着头盔的 bot 护甲值应 >0（doTick 装备检测已应用修饰器），实际 "
								+ bot.getArmorValue());
					}
					// 制造真实变更：取下头盔，稍后护甲值应归零（修饰器被同一条链移除）
					bot.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, ItemStack.EMPTY);
				})
				.thenIdle(3)
				.thenExecute(() -> {
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot == null) {
						throw new AssertionError("玩家形态助手不应消失");
					}
					if (bot.getArmorValue() != 0) {
						throw new AssertionError("取下头盔后护甲值应归零（doTick 的装备检测已移除修饰器），实际 "
								+ bot.getArmorValue());
					}
					player.closeContainer();
					helper.succeed();
				});
	}

	/**
	 * 验证“配置界面聊天窗口”的服务器端流程（聊天页与 /opencraft ask 共享同一份记忆）：
	 * 1. 未绑定助手时 chatWithBlock → 自动召唤一个助手并绑定本方块，user 消息入史；
	 * 2. 流式回复写入该方块（即该助手）的历史（thenWaitUntil 轮询）；
	 * 3. 再次 chatWithBlock → 路由到同一个助手，历史继续增长；
	 * 4. 别人对已占用方块 chatWithBlock → 被拒绝（历史不变、原助手不受影响）；
	 * 5. historyJson 返回可解析的 JSON 历史快照；sendChatHistory 不崩溃（模拟连接发送被吞）。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 4000)
	public void configScreenChatWindow(GameTestHelper helper) {
		dismissAllPlayerBots();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		BlockPos platform = new BlockPos(4, 1, 4);
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				helper.setBlock(platform.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
			}
		}
		for (int dy = 0; dy <= 3; dy++) {
			helper.setBlock(platform.offset(0, dy, 0), Blocks.AIR.defaultBlockState());
		}
		net.minecraft.world.phys.Vec3 playerPos = helper.absoluteVec(
				new net.minecraft.world.phys.Vec3(4.5, 2, 4.5));
		player.teleportTo(playerPos.x, playerPos.y, playerPos.z);

		BlockPos blockPos = new BlockPos(4, 1, 1);
		configureMockBlock(helper, blockPos, player);
		ResourceKey<net.minecraft.world.level.Level> dimension = player.level().dimension();
		BlockPos absPos = helper.absolutePos(blockPos);
		ServerLevel level = (ServerLevel) helper.getLevel();
		GlobalPos bindPos = GlobalPos.of(dimension, absPos);

		helper.startSequence()
				.thenExecute(() -> {
					// 1) 未绑定助手：chatWithBlock 自动召唤一个玩家 bot 并绑定本方块，user 消息立即入史
					AiConfigHandler.chatWithBlock(player, absPos, dimension, "你好，介绍一下你自己");
					if (com.swaydy.opencraft.assistant.AssistantFacade.findBoundTo(level, bindPos) == null) {
						throw new AssertionError("聊天应自动召唤一个助手并绑定本方块");
					}
					if (AiCompanionService.historySize(bindPos) != 1) {
						throw new AssertionError("发送消息后历史应新增 1 条 user 消息，实际 "
								+ AiCompanionService.historySize(bindPos));
					}
				})
				.thenWaitUntil(() -> {
					// 2) 等流式回复写入历史（独立线程，按墙钟时间到达）
					if (AiCompanionService.historySize(bindPos) < 2) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal("等待聊天回复写入历史…"),
								(int) helper.getTick());
					}
				})
				.thenExecute(() -> {
					// 3) 再次聊天：仍是同一个助手，历史继续增长
					com.swaydy.opencraft.assistant.AiAssistant bound =
							com.swaydy.opencraft.assistant.AssistantFacade.findBoundTo(level, bindPos);
					int size = AiCompanionService.historySize(bindPos);
					AiConfigHandler.chatWithBlock(player, absPos, dimension, "第二条消息");
					if (com.swaydy.opencraft.assistant.AssistantFacade.findBoundTo(level, bindPos) != bound) {
						throw new AssertionError("重复聊天应路由到同一个助手");
					}
					if (AiCompanionService.historySize(bindPos) != size + 1) {
						throw new AssertionError("第二条消息后历史应 +1，实际 "
								+ AiCompanionService.historySize(bindPos));
					}
				})
				.thenWaitUntil(() -> {
					if (AiCompanionService.historySize(bindPos) < 4) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal("等待第二条回复写入历史…"),
								(int) helper.getTick());
					}
				})
				.thenExecute(() -> {
					// 4) 别人对已占用方块聊天 → 被拒绝：历史不变、原助手仍在
					ServerPlayer other = helper.makeMockServerPlayerInLevel();
					other.teleportTo(playerPos.x, playerPos.y, playerPos.z);
					int size = AiCompanionService.historySize(bindPos);
					AiConfigHandler.chatWithBlock(other, absPos, dimension, "我能和你聊聊吗");
					if (AiCompanionService.historySize(bindPos) != size) {
						throw new AssertionError("他人聊天被拒后历史不应增长，实际 "
								+ AiCompanionService.historySize(bindPos));
					}
					if (com.swaydy.opencraft.assistant.AssistantFacade.findBoundTo(level, bindPos) == null) {
						throw new AssertionError("他人聊天被拒不应影响原助手");
					}
					// 5) 历史 JSON 快照可解析
					String json = AiCompanionService.historyJson(bindPos);
					if (json == null || !json.startsWith("[") || !json.contains("\"role\"")) {
						throw new AssertionError("历史 JSON 快照格式不正确: " + json);
					}
					// 6) sendChatHistory 不崩溃（mock 连接发送失败被 try/catch 吞掉）
					AiConfigHandler.sendChatHistory(player, absPos, dimension);
					// 清理
					com.swaydy.opencraft.assistant.AssistantFacade.dismissAllFor(player);
					AiCompanionService.resetAllHistory(player);
					helper.succeed();
				});
	}

	/**
	 * 端到端验证内置技能真实注入到发给 LLM 的 HTTP 请求：
	 * （skills/index.json 登记的技能经 general_agent 绑定 + requires_tools 双重过滤后，
	 * 由 Prompts.system 渲染进每轮请求 system 消息——请求体里是 messages[0] 的 role=system）
	 * 1. 用 mock 方块聊天触发 agentic loop（mod → 真实 HTTP 请求 → mock 服务器）；
	 * 2. 等回复完成，从 mock 的 GET /v1/requests 拉回全部请求体；
	 * 3. 断言：至少一个请求的 system 含 "# Skills" 大节与 ## gather-wood / ## craft-toolchain；
	 * 4. 断言：没有任何请求的 system 含已删除技能名（dig-down-staircase 等）——
	 *    证明删除彻底、注入无残留。
	 * 注意：openai-java SDK 首次 createStreaming 有 ~400ms 冷启动开销，maxTicks 需给足。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 2000)
	public void skillsInjectedIntoSystemPrompt(GameTestHelper helper) {
		dismissAllPlayerBots();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		BlockPos platform = new BlockPos(4, 1, 4);
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				helper.setBlock(platform.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
			}
		}
		for (int dy = 0; dy <= 3; dy++) {
			helper.setBlock(platform.offset(0, dy, 0), Blocks.AIR.defaultBlockState());
		}
		net.minecraft.world.phys.Vec3 playerPos = helper.absoluteVec(
				new net.minecraft.world.phys.Vec3(4.5, 2, 4.5));
		player.teleportTo(playerPos.x, playerPos.y, playerPos.z);

		BlockPos blockPos = new BlockPos(4, 1, 1);
		configureMockBlock(helper, blockPos, player);
		ResourceKey<net.minecraft.world.level.Level> dimension = player.level().dimension();
		BlockPos absPos = helper.absolutePos(blockPos);
		GlobalPos bindPos = GlobalPos.of(dimension, absPos);

		helper.startSequence()
				.thenExecute(() -> {
					// 触发 agentic loop：聊天自动召唤助手并提问（砍树 → general_agent 的 gather-wood 场景）
					AiConfigHandler.chatWithBlock(player, absPos, dimension, "砍一棵树");
					if (AiCompanionService.historySize(bindPos) != 1) {
						throw new AssertionError("发送消息后历史应新增 1 条 user 消息，实际 "
								+ AiCompanionService.historySize(bindPos));
					}
				})
				.thenWaitUntil(() -> {
					// 等流式回复写入历史（独立线程，按墙钟时间到达）
					if (AiCompanionService.historySize(bindPos) < 2) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal("等待聊天回复写入历史…"),
								(int) helper.getTick());
					}
				})
				.thenExecute(() -> {
					// 从 mock 服务器拉回真实收到的请求体（端到端：mod → HTTP → mock）
					java.util.List<com.google.gson.JsonObject> requests =
							fetchMockRequests("http://127.0.0.1:18923/v1/requests");
					if (requests.isEmpty()) {
						throw new AssertionError("mock 未收到任何请求");
					}
					StringBuilder allSystems = new StringBuilder();
					for (com.google.gson.JsonObject req : requests) {
						// 请求体里 system 是 messages[0] 的 role=system 消息（非顶层字段）
						String system = "";
						com.google.gson.JsonArray msgs = req.has("messages")
								? req.getAsJsonArray("messages") : null;
						if (msgs != null && msgs.size() > 0
								&& msgs.get(0).getAsJsonObject().has("role")
								&& "system".equals(msgs.get(0).getAsJsonObject().get("role").getAsString())) {
							system = msgs.get(0).getAsJsonObject().has("content")
									? msgs.get(0).getAsJsonObject().get("content").getAsString() : "";
						}
						allSystems.append(system).append('\n');
						// 已删除的技能不应出现在任何请求里（删除彻底、无残留）
						for (String gone : new String[]{
								"dig-down-staircase", "mine-and-collect", "regroup-with-owner"}) {
							if (system.contains(gone)) {
								throw new AssertionError("已删除技能不应出现在请求 system 中: " + gone);
							}
						}
					}
					if (!allSystems.toString().contains("# Skills")) {
						throw new AssertionError("system 应含 # Skills 大节");
					}
					if (!allSystems.toString().contains("## gather-wood")) {
						throw new AssertionError("system 应注入 ## gather-wood 技能小节");
					}
					if (!allSystems.toString().contains("## craft-toolchain")) {
						throw new AssertionError("system 应注入 ## craft-toolchain 技能小节");
					}
					// 清理
					com.swaydy.opencraft.assistant.AssistantFacade.dismissAllFor(player);
					AiCompanionService.resetAllHistory(player);
					helper.succeed();
				});
	}

	/** 从 mock 服务器 GET /v1/requests 拉取最近收到的请求体列表（本地往返，毫秒级）。 */
	private static java.util.List<com.google.gson.JsonObject> fetchMockRequests(String url) {
		try {
			java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
					.connectTimeout(java.time.Duration.ofSeconds(5)).build();
			java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
					.uri(java.net.URI.create(url)).GET()
					.timeout(java.time.Duration.ofSeconds(10)).build();
			java.net.http.HttpResponse<String> resp = client.send(req,
					java.net.http.HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() != 200) {
				throw new AssertionError("拉取 mock 请求失败: HTTP " + resp.statusCode());
			}
			com.google.gson.JsonObject obj =
					com.google.gson.JsonParser.parseString(resp.body()).getAsJsonObject();
			java.util.List<com.google.gson.JsonObject> out = new java.util.ArrayList<>();
			for (com.google.gson.JsonElement e : obj.getAsJsonArray("requests")) {
				out.add(e.getAsJsonObject());
			}
			return out;
		} catch (Exception e) {
			throw new AssertionError("GET " + url + " 失败: " + e, e);
		}
	}

	/**
	 * 验证“助手像普通生存玩家一样拥有背包与装备”：
	 * 1. 背包为 36 格（27 普通 + 9 快捷栏，与玩家一致）；
	 * 2. 自动拾取地上的物品进自己的背包（拾取后掉落物实体消失）；
	 * 3. 装备栏穿上护甲（胸甲 → CHEST）后，护甲值如实生效（≥5）；
	 * 4. 挖掘前 autoSelectMiningTool 自动把背包里最快的镐换到主手；
	 * 5. 挖掘掉落物进**助手自己的背包**（而不是主人背包），主人背包不增长。
	 */
	/**
	 * 验证“player_craft 按玩家规则合成”：
	 * 1. 3×3 配方（钻石块）在【没有工作台】时被拒绝，报“需要工作台”且不扣材料；
	 * 2. 助手旁边放置工作台后，9 个钻石（背包第 20 格）→ 合成钻石块成功、钻石扣光；
	 * 3. 18 个钻石 + amount=2 → 合成 2 个钻石块（按套数扣料）；
	 * 4. 2×2 及更小的配方（木棍 1×2）不需要工作台也能合成（材料在第 30 格）；
	 * 5. 材料不足时返回明确错误且不扣料。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
	public void craftUsesWholeBackpack(GameTestHelper helper) {
		dismissAllPlayerBots();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		BlockPos platform = new BlockPos(4, 1, 4);
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				helper.setBlock(platform.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
			}
		}
		for (int dy = 0; dy <= 3; dy++) {
			helper.setBlock(platform.offset(0, dy, 0), Blocks.AIR.defaultBlockState());
		}
		net.minecraft.world.phys.Vec3 playerPos = helper.absoluteVec(
				new net.minecraft.world.phys.Vec3(4.5, 2, 4.5));
		player.teleportTo(playerPos.x, playerPos.y, playerPos.z);
		BlockPos configBlockPos = new BlockPos(4, 1, 1);
		configureMockBlock(helper, configBlockPos, player);
		ServerLevel level = (ServerLevel) helper.getLevel();
		net.minecraft.server.MinecraftServer server = level.getServer();
		GlobalPos bindPos = GlobalPos.of(level.dimension(), helper.absolutePos(configBlockPos));

		helper.startSequence()
				.thenExecute(() -> {
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							(com.swaydy.opencraft.assistant.player.AiAssistantPlayer)
									AiCompanionService.summonFor(player, bindPos);
					if (bot == null) {
						throw new AssertionError("召唤玩家形态助手失败");
					}
					// 把 bot 放到平台中间（召唤落点可能落在周边地形上）
					net.minecraft.world.phys.Vec3 standPos = helper.absoluteVec(
							new net.minecraft.world.phys.Vec3(4.5, 2, 4.5));
					bot.teleportTo(standPos.x, standPos.y, standPos.z);
					ToolDefinition craftDef = com.swaydy.opencraft.agent.AgentRegistry
							.agent("general_agent").toolMap().get("player_craft");
					if (craftDef == null) {
						throw new AssertionError("general_agent 应提供 player_craft 工具");
					}
					// 1) 3×3 配方（钻石块）没有工作台 → 拒绝并提示需要工作台，不扣材料
					bot.getInventory().setItem(20, new ItemStack(Items.DIAMOND, 9));
					JsonObject args = new JsonObject();
					args.addProperty("item", "minecraft:diamond_block");
					args.addProperty("amount", 1);
					ToolResult res = craftDef.executor().execute(
							new ToolContext(server, bot, player, level), args);
					if (res.ok()) {
						throw new AssertionError("没有工作台时 3×3 配方（钻石块）不应合成成功");
					}
					if (!res.message().toLowerCase().contains("crafting table")) {
						throw new AssertionError("应提示需要工作台，实际: " + res.message());
					}
					if (bot.getInventory().countItem(Items.DIAMOND) != 9) {
						throw new AssertionError("没有工作台时不应扣材料，实际钻石 "
								+ bot.getInventory().countItem(Items.DIAMOND));
					}
					// 2) bot 旁边放一个工作台 → 9 个钻石（第 20 格）→ 合成钻石块成功
					helper.setBlock(new BlockPos(5, 1, 4), Blocks.CRAFTING_TABLE.defaultBlockState());
					ToolResult res2 = craftDef.executor().execute(
							new ToolContext(server, bot, player, level), args);
					if (!res2.ok()) {
						throw new AssertionError("有工作台时 player_craft 钻石块失败: " + res2.message());
					}
					if (bot.getInventory().countItem(Items.DIAMOND_BLOCK) < 1) {
						throw new AssertionError("背包应有 1 个钻石块");
					}
					if (bot.getInventory().countItem(Items.DIAMOND) != 0) {
						throw new AssertionError("9 个钻石应被扣光，实际 "
								+ bot.getInventory().countItem(Items.DIAMOND));
					}
					// 3) 18 个钻石 + amount=2 → 2 个钻石块（按套数扣料）
					bot.getInventory().setItem(5, new ItemStack(Items.DIAMOND, 18));
					JsonObject args3 = new JsonObject();
					args3.addProperty("item", "minecraft:diamond_block");
					args3.addProperty("amount", 2);
					ToolResult res3 = craftDef.executor().execute(
							new ToolContext(server, bot, player, level), args3);
					if (!res3.ok()) {
						throw new AssertionError("player_craft 2 套钻石块失败: " + res3.message());
					}
					if (bot.getInventory().countItem(Items.DIAMOND_BLOCK) < 3) {
						throw new AssertionError("背包应有 3 个钻石块（1+2），实际 "
								+ bot.getInventory().countItem(Items.DIAMOND_BLOCK));
					}
					if (bot.getInventory().countItem(Items.DIAMOND) != 0) {
						throw new AssertionError("18 个钻石应被扣光，实际 "
								+ bot.getInventory().countItem(Items.DIAMOND));
					}
					// 4) 2×2 及更小的配方（木棍 1×2）不依赖工作台也能合成（材料在第 30 格）
					//    先拆掉工作台，验证“不需要工作台”的规则独立成立
					helper.setBlock(new BlockPos(5, 1, 4), Blocks.AIR.defaultBlockState());
					bot.getInventory().setItem(30, new ItemStack(Items.OAK_PLANKS, 2));
					JsonObject args4 = new JsonObject();
					args4.addProperty("item", "minecraft:stick");
					ToolResult res4 = craftDef.executor().execute(
							new ToolContext(server, bot, player, level), args4);
					if (!res4.ok()) {
						throw new AssertionError("player_craft 木棍失败（1×2 配方不需要工作台）: " + res4.message());
					}
					if (bot.getInventory().countItem(Items.STICK) < 4) {
						throw new AssertionError("2 个木板应合成 4 根木棍，实际 "
								+ bot.getInventory().countItem(Items.STICK));
					}
					// 5) 材料不足：只有一个钻石 → 应返回错误且不扣料（钻石还在）
					int diamondBefore = bot.getInventory().countItem(Items.DIAMOND);
					bot.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 1));
					JsonObject args5 = new JsonObject();
					args5.addProperty("item", "minecraft:diamond_block");
					ToolResult res5 = craftDef.executor().execute(
							new ToolContext(server, bot, player, level), args5);
					if (res5.ok()) {
						throw new AssertionError("材料不足时 player_craft 应返回错误");
					}
					if (bot.getInventory().countItem(Items.DIAMOND) != diamondBefore + 1) {
						throw new AssertionError("合成失败不应扣材料");
					}
					// 清理
					com.swaydy.opencraft.assistant.AssistantFacade.dismiss(bot);
					AiCompanionService.resetAllHistory(player);
					helper.succeed();
				});
	}

	/**
	 * 验证“调试模式（覆盖式日志）”：
	 * 1. enable 后日志写入 <游戏目录>/logs/opencraft-debug.log，格式 [时间] [分类] 内容；
	 * 2. 再次 enable 时【清空旧日志】（覆盖式）：上次会话的内容不再存在，只保留本次会话；
	 * 3. disable 后 isEnabled 变回 false（写日志变为 no-op）。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 120)
	public void debugModeLogsToFile(GameTestHelper helper) {
		java.nio.file.Path logFile = com.swaydy.opencraft.logging.DebugLog.logFilePath();
		boolean wasEnabled = com.swaydy.opencraft.logging.DebugLog.isEnabled();
		// 1) 第一次开启：写入“旧会话”内容后关闭（文件里保留旧内容）
		com.swaydy.opencraft.logging.DebugLog.enable();
		if (!com.swaydy.opencraft.logging.DebugLog.isEnabled()) {
			throw new AssertionError("enable 后调试模式应处于开启状态");
		}
		com.swaydy.opencraft.logging.DebugLog.log("old", "旧会话内容");
		com.swaydy.opencraft.logging.DebugLog.disable();
		if (com.swaydy.opencraft.logging.DebugLog.isEnabled()) {
			throw new AssertionError("disable 后调试模式应处于关闭状态");
		}
		// 2) 重新开启：覆盖式——旧日志被清空，只保留本次会话的新内容
		com.swaydy.opencraft.logging.DebugLog.enable();
		com.swaydy.opencraft.logging.DebugLog.log("test", "调试日志测试 {}", 42);
		com.swaydy.opencraft.logging.DebugLog.log("chat", "模拟对话记录 abc");
		String content;
		try {
			content = java.nio.file.Files.readString(logFile);
		} catch (java.io.IOException e) {
			throw new AssertionError("调试日志文件不可读: " + e);
		}
		if (!content.contains("[test]") || !content.contains("[chat]")
				|| !content.contains("调试日志测试 42") || !content.contains("模拟对话记录 abc")) {
			throw new AssertionError("调试日志内容不完整: " + content);
		}
		if (content.contains("旧会话内容") || content.contains("调试模式已关闭")) {
			throw new AssertionError("覆盖式日志应清空旧会话内容，实际: " + content);
		}
		// 3) 关闭后写入应 no-op（文件不再增长）
		com.swaydy.opencraft.logging.DebugLog.disable();
		com.swaydy.opencraft.logging.DebugLog.log("test", "不应写入");
		try {
			String after = java.nio.file.Files.readString(logFile);
			if (after.contains("不应写入")) {
				throw new AssertionError("关闭后不应再写入调试日志");
			}
		} catch (java.io.IOException e) {
			throw new AssertionError("调试日志文件不可读: " + e);
		}
		if (!wasEnabled) {
			com.swaydy.opencraft.logging.DebugLog.disable();
		}
		helper.succeed();
	}

	/**
	 * 验证“AI 助手 = 真正的 ServerPlayer（bot）” —— 本次目标的核心交付：
	 * AI 助手像多人联机客户端一样进服，拥有普通玩家的全部内容（可以不用但不能没有）。
	 * 身体形态与 Agent 预设解耦：无论选哪个预设，召唤出的都是玩家形态：
	 * 1. summonFor 召唤出的就是 AiAssistantPlayer，它真实地进入了 PlayerList，
	 *    拥有真正的玩家背包（43 槽：36 主背包 + 7 装备槽）；
	 * 2. 右键交互（绑主/开背包界面/非主人拒绝）；
	 * 3. 玩家式动作：player_place 用真实 ServerPlayerGameMode.useItemOn 放置方块；
	 * 4. 玩家式挖掘：player_mine 走到方块旁用 destroyBlock 破坏，掉落物自动拾进背包；
	 * 5. 送走后从 PlayerList 移除、绑定方块熄灭。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 400)
	public void assistantPlayerFormLifecycle(GameTestHelper helper) {
		dismissAllPlayerBots();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		// 平台 + 玩家
		BlockPos platform = new BlockPos(4, 1, 4);
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				helper.setBlock(platform.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
			}
		}
		for (int dy = 0; dy <= 3; dy++) {
			helper.setBlock(platform.offset(0, dy, 0), Blocks.AIR.defaultBlockState());
		}
		// 放置测试的锚点方块（player_place 贴到它上面放）
		helper.setBlock(new BlockPos(5, 1, 4), Blocks.STONE.defaultBlockState());
		net.minecraft.world.phys.Vec3 playerPos = helper.absoluteVec(
				new net.minecraft.world.phys.Vec3(4.5, 2, 4.5));
		player.teleportTo(playerPos.x, playerPos.y, playerPos.z);

		// 配置方块（预设默认 general_agent：只影响 LLM 行为，身体形态恒为玩家 bot）
		BlockPos blockPos = new BlockPos(4, 1, 1);
		helper.setBlock(blockPos, ModBlocks.AI_LOGO_BLOCK.defaultBlockState());
		AiLogoBlockEntity blockEntity = helper.getBlockEntity(blockPos, AiLogoBlockEntity.class);
		if (blockEntity == null) {
			throw new AssertionError("配置方块实体未创建");
		}
		AiBlockConfig cfg = blockEntity.getConfig();
		cfg.baseUrl = "http://127.0.0.1:18923/v1";
		cfg.apiKey = "test-key-123";
		cfg.model = "mock-model";
		blockEntity.markConfigChanged();

		ServerLevel level = (ServerLevel) helper.getLevel();
		net.minecraft.server.MinecraftServer server = level.getServer();
		GlobalPos bindPos = GlobalPos.of(level.dimension(), helper.absolutePos(blockPos));
		BlockPos absBlock = helper.absolutePos(blockPos);
		net.minecraft.server.players.PlayerList playerList = server.getPlayerList();

		helper.startSequence()
				.thenExecute(() -> {
					// 1) 召唤 → 真实 ServerPlayer 进服（助手一律玩家形态，与预设无关）
					com.swaydy.opencraft.assistant.AiAssistant summoned =
							AiCompanionService.summonFor(player, bindPos);
					if (!(summoned instanceof com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot)) {
						throw new AssertionError("召唤出的助手应是玩家形态（真 ServerPlayer），实际 "
								+ (summoned == null ? "null" : summoned.getClass().getSimpleName()));
					}
					if (!playerList.getPlayers().contains(bot)) {
						throw new AssertionError("玩家形态助手应真实进入 PlayerList（像客户端一样进服）");
					}
					if (!bindPos.equals(bot.getConfigBlock())) {
						throw new AssertionError("玩家形态助手未绑定到配置方块");
					}
					// 真正的玩家背包：1.21.11 的 PlayerInventory = 36 主背包 + 7 装备槽
					//（头/胸/腿/脚/副手/身体/坐骑鞍），getContainerSize()=43
					if (bot.getInventory().getContainerSize() < 41) {
						throw new AssertionError("玩家形态助手应拥有真正的玩家背包（≥41 槽），实际 "
								+ bot.getInventory().getContainerSize());
					}
					if (bot.getInventory().getClass() != net.minecraft.world.entity.player.Inventory.class) {
						throw new AssertionError("玩家形态助手的背包应是真实 PlayerInventory");
					}
					if (!helper.getLevel().getBlockState(absBlock).getValue(AiLogoBlock.POWERED)) {
						throw new AssertionError("玩家形态助手召唤后绑定方块应亮起");
					}
					// 2) 右键交互：主人右键消费交互（打开互动界面）
					net.minecraft.world.InteractionResult r =
							bot.interact(player, net.minecraft.world.InteractionHand.MAIN_HAND);
					if (!r.consumesAction()) {
						throw new AssertionError("主人右键玩家形态助手应消费交互");
					}
				})
				.thenExecute(() -> {
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot == null) {
						throw new AssertionError("玩家形态助手不应消失");
					}
					// 3) 玩家式放置：给主手一块石头，player_place 放到平台上方
					bot.setPos(playerPos.x, playerPos.y, playerPos.z + 2); // 站在放置点旁
					bot.getInventory().setItem(bot.getInventory().getSelectedSlot(),
							new ItemStack(Items.STONE, 1));
					com.swaydy.opencraft.plugins.ToolDefinition placeTool =
							com.swaydy.opencraft.agent.AgentRegistry.agent("general_agent").toolMap()
									.get("player_place");
					if (placeTool == null) {
						throw new AssertionError("general_agent 预设应提供 player_place 工具");
					}
					JsonObject args = new JsonObject();
					// 贴到平台 (5,1,4) 的上表面（face=up）放置 → 目标 (5,2,4)
					BlockPos anchor = helper.absolutePos(new BlockPos(5, 1, 4));
					args.addProperty("x", anchor.getX());
					args.addProperty("y", anchor.getY());
					args.addProperty("z", anchor.getZ());
					args.addProperty("face", "up");
					ToolResult res = placeTool.executor().execute(
							new ToolContext(server, bot, player, level), args);
					if (!res.ok()) {
						throw new AssertionError("player_place 失败: " + res.message());
					}
				})
				.thenIdle(6)
				.thenExecute(() -> {
					BlockPos placed = helper.absolutePos(new BlockPos(5, 2, 4));
					if (!helper.getLevel().getBlockState(placed).is(Blocks.STONE)) {
						throw new AssertionError("player_place 应把石头放到 (5,2,4)，实际 "
								+ helper.getLevel().getBlockState(placed));
					}
					// 5) 玩家式挖掘：player_mine 挖掉刚放的石头，掉落物自动进背包。
					//    先给主手一把木镐（生存模式空手挖石头不掉圆石）
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot == null) {
						throw new AssertionError("玩家形态助手不应消失");
					}
					bot.getInventory().setItem(bot.getInventory().getSelectedSlot(),
							new ItemStack(Items.WOODEN_PICKAXE, 1));
					com.swaydy.opencraft.plugins.ToolDefinition mineTool =
							com.swaydy.opencraft.agent.AgentRegistry.agent("general_agent").toolMap()
									.get("player_mine");
					JsonObject args = new JsonObject();
					args.addProperty("x", placed.getX());
					args.addProperty("y", placed.getY());
					args.addProperty("z", placed.getZ());
					ToolResult res = mineTool.executor().execute(
							new ToolContext(server, bot, player, level), args);
					if (!res.ok()) {
						throw new AssertionError("player_mine 失败: " + res.message());
					}
				})
				.thenWaitUntil(() -> {
					// 等石头被破坏且圆石被玩家形态助手自动拾进背包
					BlockPos placed = helper.absolutePos(new BlockPos(5, 2, 4));
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot == null) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal("玩家形态助手不存在"),
								(int) helper.getTick());
					}
					boolean mined = helper.getLevel().getBlockState(placed).is(Blocks.AIR);
					if (!mined || bot.getInventory().countItem(Items.COBBLESTONE) < 1) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal(
										"等待 player_mine 破坏石头且圆石自动进背包…"),
								(int) helper.getTick());
					}
				})
				.thenExecute(() -> {
					// 6) 送走：从 PlayerList 移除、绑定方块熄灭
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot == null) {
						throw new AssertionError("送走前玩家形态助手应存在");
					}
					if (!com.swaydy.opencraft.assistant.AssistantFacade.dismiss(bot)) {
						throw new AssertionError("送走玩家形态助手应成功");
					}
					if (playerList.getPlayers().contains(bot)) {
						throw new AssertionError("送走后玩家形态助手应移出 PlayerList");
					}
					if (com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos) != null) {
						throw new AssertionError("送走后不应再有玩家形态助手绑定");
					}
					if (helper.getLevel().getBlockState(absBlock).getValue(AiLogoBlock.POWERED)) {
						throw new AssertionError("送走后绑定方块应熄灭");
					}
					AiCompanionService.resetAllHistory(player);
					helper.succeed();
				});
	}

	/**
	 * 验证 player_teleport 工具（玩家形态助手瞬移到指定坐标，同维度）：
	 * 1. 目标在 maxDistance 缰绳内 → 同步传送到位（位置精确等于目标落点）；
	 * 2. 目标超出缰绳（默认 64 格）→ 拒绝（"too far"），位置不变；
	 * 3. 先 player_goto 再 player_teleport → 传送取消在途移动，bot 停在传送点不回走。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 400)
	public void playerTeleportTool(GameTestHelper helper) {
		dismissAllPlayerBots();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		// 7×7 石砖平台（相对 y=0 实心，y≥1 空气）：bot 与传送目标都站在 y=1 空气层上
		BlockPos platform = new BlockPos(4, 1, 4);
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				helper.setBlock(platform.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
			}
		}
		BlockPos blockPos = new BlockPos(4, 1, 1); // 配置方块也放在平台上
		helper.setBlock(blockPos, ModBlocks.AI_LOGO_BLOCK.defaultBlockState());
		AiLogoBlockEntity blockEntity = helper.getBlockEntity(blockPos, AiLogoBlockEntity.class);
		if (blockEntity == null) {
			throw new AssertionError("配置方块实体未创建");
		}
		AiBlockConfig cfg = blockEntity.getConfig();
		cfg.baseUrl = "http://127.0.0.1:18923/v1";
		cfg.apiKey = "test-key-123";
		cfg.model = "mock-model";
		blockEntity.markConfigChanged();

		ServerLevel level = (ServerLevel) helper.getLevel();
		net.minecraft.server.MinecraftServer server = level.getServer();
		GlobalPos bindPos = GlobalPos.of(level.dimension(), helper.absolutePos(blockPos));
		net.minecraft.world.phys.Vec3 playerPos = helper.absoluteVec(
				new net.minecraft.world.phys.Vec3(2.5, 1, 2.5));
		player.teleportTo(playerPos.x, playerPos.y, playerPos.z);

		helper.startSequence()
				.thenExecute(() -> {
					com.swaydy.opencraft.assistant.AiAssistant summoned =
							AiCompanionService.summonFor(player, bindPos);
					if (!(summoned instanceof com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot)) {
						throw new AssertionError("召唤出的助手应是玩家形态，实际 "
								+ (summoned == null ? "null" : summoned.getClass().getSimpleName()));
					}
					// 模拟"任务执行中"（工具直接调用不经 AgentRuntime）：关闭跟随，
					// 防止 keepSafeState 的跟随逻辑把 bot 拉回玩家身边干扰位置断言
					bot.setFollowing(false);
				})
				.thenExecute(() -> {
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot == null) {
						throw new AssertionError("玩家形态助手不存在");
					}
					com.swaydy.opencraft.plugins.ToolDefinition tp =
							com.swaydy.opencraft.agent.AgentRegistry.agent("general_agent").toolMap()
									.get("player_teleport");
					if (tp == null) {
						throw new AssertionError("general_agent 预设应提供 player_teleport 工具");
					}
					ToolContext tctx = new ToolContext(server, bot, player, level);

					// 1) 传送到平台上的合法落点（相对 (6,1,6)：空气+空气+脚下石砖）
					bot.setPos(playerPos.x, playerPos.y, playerPos.z + 4); // (2.5,1,6.5)
					BlockPos tp1 = helper.absolutePos(new BlockPos(6, 1, 6));
					JsonObject args1 = new JsonObject();
					args1.addProperty("x", tp1.getX());
					args1.addProperty("y", tp1.getY());
					args1.addProperty("z", tp1.getZ());
					ToolResult r1 = tp.executor().execute(tctx, args1);
					if (!r1.ok()) {
						throw new AssertionError("player_teleport 应成功: " + r1.message());
					}
					if (!bot.blockPosition().equals(tp1)) {
						throw new AssertionError("传送后应精确落在 " + tp1 + "，实际 " + bot.blockPosition());
					}

					// 2) 目标超出 maxDistance（默认 64）→ 拒绝，位置不变
					BlockPos far = tp1.offset(0, 0, 100);
					JsonObject args2 = new JsonObject();
					args2.addProperty("x", far.getX());
					args2.addProperty("y", far.getY());
					args2.addProperty("z", far.getZ());
					ToolResult r2 = tp.executor().execute(tctx, args2);
					if (r2.ok() || !r2.message().contains("too far")) {
						throw new AssertionError("超距传送应被拒绝（too far），实际 " + r2.message());
					}
					if (!bot.blockPosition().equals(tp1)) {
						throw new AssertionError("被拒绝后位置不应改变，实际 " + bot.blockPosition());
					}

					// 3) 先 player_goto（启动在途移动）再 player_teleport →
					//    传送取消在途移动，bot 停在传送点不回走
					com.swaydy.opencraft.plugins.ToolDefinition gotoDef =
							com.swaydy.opencraft.agent.AgentRegistry.agent("general_agent").toolMap()
									.get("player_goto");
					if (gotoDef == null) {
						throw new AssertionError("general_agent 预设应提供 player_goto 工具");
					}
					BlockPos g1 = helper.absolutePos(new BlockPos(7, 1, 7));
					JsonObject argsG = new JsonObject();
					argsG.addProperty("x", g1.getX());
					argsG.addProperty("y", g1.getY());
					argsG.addProperty("z", g1.getZ());
					ToolResult rg = gotoDef.executor().execute(tctx, argsG);
					if (!rg.ok()) {
						throw new AssertionError("player_goto 应启动: " + rg.message());
					}
					if (!bot.movement().isMoving()) {
						throw new AssertionError("player_goto 后应有在途移动目标");
					}
					BlockPos tp2 = helper.absolutePos(new BlockPos(2, 1, 2));
					JsonObject args3 = new JsonObject();
					args3.addProperty("x", tp2.getX());
					args3.addProperty("y", tp2.getY());
					args3.addProperty("z", tp2.getZ());
					ToolResult r3 = tp.executor().execute(tctx, args3);
					if (!r3.ok()) {
						throw new AssertionError("goto 在途时 player_teleport 应成功: " + r3.message());
					}
					if (bot.movement().isMoving()) {
						throw new AssertionError("传送后应取消在途移动（不回走）");
					}
					if (!bot.blockPosition().equals(tp2)) {
						throw new AssertionError("传送后应停在 " + tp2 + "，实际 " + bot.blockPosition());
					}
				})
				.thenIdle(10)
				.thenExecute(() -> {
					// 过 10 tick 确认 bot 没有被旧移动目标拉回去（停住不动）
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot == null) {
						throw new AssertionError("玩家形态助手不存在");
					}
					BlockPos tp2 = helper.absolutePos(new BlockPos(2, 1, 2));
					if (!bot.blockPosition().equals(tp2)) {
						throw new AssertionError("传送后 bot 不应被旧目标拉走，实际 " + bot.blockPosition());
					}
					com.swaydy.opencraft.assistant.AssistantFacade.dismiss(bot);
					AiCompanionService.resetAllHistory(player);
					helper.succeed();
				});
	}

	/**
	 * 验证玩家形态助手的 bot 式移动物理（PlayerMovementController）：
	 * 1. 移动时会朝向目标方向（yRot 平滑转向，不再侧滑）；
	 * 2. 走出平台边缘后受重力下坠（不依赖可能陈旧的 onGround 标志，不会浮空）；
	 * 3. 落到下方接住平台后不再继续下坠（Y 稳定）。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 300)
	public void playerMovementPhysics(GameTestHelper helper) {
		dismissAllPlayerBots();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		// 上层平台（相对 y=0，方块顶面 = playerPos.y-1）：bot 站在上面，向东走出边缘
		BlockPos platform = new BlockPos(4, 1, 4);
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				helper.setBlock(platform.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
			}
		}
		// 下方接住平台（相对 y=-3 的方块，顶面在相对 y=-2 = playerPos.y-4）：bot 坠落后应落在这里
		for (int dx = -3; dx <= 8; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				helper.setBlock(platform.offset(dx, -4, dz), Blocks.STONE.defaultBlockState());
			}
		}
		for (int dy = 0; dy <= 3; dy++) {
			helper.setBlock(platform.offset(0, dy, 0), Blocks.AIR.defaultBlockState());
		}
		net.minecraft.world.phys.Vec3 playerPos = helper.absoluteVec(
				new net.minecraft.world.phys.Vec3(4.5, 2, 4.5));
		player.teleportTo(playerPos.x, playerPos.y, playerPos.z);

		BlockPos blockPos = new BlockPos(4, 1, 1);
		configureMockBlock(helper, blockPos, player);
		ServerLevel level = (ServerLevel) helper.getLevel();
		GlobalPos bindPos = GlobalPos.of(player.level().dimension(), helper.absolutePos(blockPos));
		// 平台边缘在相对 x=8（方块 x=7 占据 [7,8)）；目标放 x=10，使 bot 到达
		// （半径 1.0，即 x≥9）时已完全离开平台，随即下坠
		net.minecraft.world.phys.Vec3 targetPos = helper.absoluteVec(
				new net.minecraft.world.phys.Vec3(10.0, 2, 4.5));

		helper.startSequence()
				.thenExecute(() -> {
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							(com.swaydy.opencraft.assistant.player.AiAssistantPlayer)
									AiCompanionService.summonFor(player, bindPos);
					if (bot == null) {
						throw new AssertionError("召唤玩家形态助手失败");
					}
					bot.teleportTo(playerPos.x, playerPos.y, playerPos.z); // 站到平台正中
					// 锚定初始朝向（南，yaw=0），便于断言“转向目标方向”
					bot.setYRot(0.0F);
					bot.setYHeadRot(0.0F);
					bot.yBodyRot = 0.0F;
					// 目标在平台东侧边缘外：会先向东走、走出边缘后下坠
					bot.movement().moveTo(targetPos, 1.0, true);
				})
				.thenIdle(8)
				.thenExecute(() -> {
					// 1) 朝向：目标在正东（+X），yaw 应平滑转到 -90°（±30° 容差）
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot == null) {
						throw new AssertionError("助手不应消失");
					}
					float yaw = net.minecraft.util.Mth.wrapDegrees(bot.getYRot());
					float targetYaw = net.minecraft.util.Mth.wrapDegrees(-90.0F);
					float diff = Math.abs(yaw - targetYaw);
					if (diff > 30.0F && Math.abs(diff - 360.0F) > 30.0F) {
						throw new AssertionError("移动中应朝向目标方向（东，yaw≈-90），实际 yRot=" + yaw);
					}
				})
				.thenWaitUntil(() -> {
					// 2) 走出边缘后下坠：Y 应明显低于平台顶面（playerPos.y-1），不浮空
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot == null) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal("玩家形态助手不存在"),
								(int) helper.getTick());
					}
					if (bot.getY() > playerPos.y - 1.5) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal(
										"等待助手走出平台边缘并下坠（当前 y=" + bot.getY() + "）…"),
								(int) helper.getTick());
					}
				})
				.thenWaitUntil(() -> {
					// 3) 应落到下方接住平台（顶面在 playerPos.y-4）
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot == null) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal("玩家形态助手不存在"),
								(int) helper.getTick());
					}
					double catchTop = playerPos.y - 4.0;
					if (bot.getY() > catchTop + 0.5) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal(
										"等待助手落到下方接住平台（当前 y=" + bot.getY() + "）…"),
								(int) helper.getTick());
					}
				})
				.thenIdle(15)
				.thenExecute(() -> {
					// 落地后 Y 稳定：没有继续下坠（说明确实有地面支撑）
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot == null) {
						throw new AssertionError("助手不应消失");
					}
					double catchTop = playerPos.y - 4.0;
					if (bot.getY() < catchTop - 0.3 || bot.getY() > catchTop + 0.3) {
						throw new AssertionError("落地后 Y 应稳定在接住平台顶面附近，实际 y=" + bot.getY());
					}
					// 清理
					com.swaydy.opencraft.assistant.AssistantFacade.dismiss(bot);
					AiCompanionService.resetAllHistory(player);
					helper.succeed();
				});
	}

	/**
	 * 验证物品 ID 容错解析（用户反馈场景：bot 背包里的本模组物品 ai_logo_block 显示为
	 * 短名，模型照抄短名回填工具参数必须能解析、能递出）：
	 * 1. resolveItem 接受完整 ID（opencraft:ai_logo_block / minecraft:stone）、
	 *    裸名（ai_logo_block / stone）与描述名（block.opencraft.ai_logo_block / item.minecraft.stick）；
	 * 2. player_hand_to_player 用短名 "ai_logo_block" 把 bot 背包里的 AI 徽标方块递给主人。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
	public void assistantHandsOverModdedItem(GameTestHelper helper) {
		dismissAllPlayerBots();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		BlockPos platform = new BlockPos(4, 1, 4);
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				helper.setBlock(platform.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
			}
		}
		net.minecraft.world.phys.Vec3 playerPos = helper.absoluteVec(
				new net.minecraft.world.phys.Vec3(4.5, 2, 4.5));
		player.teleportTo(playerPos.x, playerPos.y, playerPos.z);

		BlockPos blockPos = new BlockPos(4, 1, 1);
		configureMockBlock(helper, blockPos, player);
		ServerLevel level = (ServerLevel) helper.getLevel();
		GlobalPos bindPos = GlobalPos.of(player.level().dimension(), helper.absolutePos(blockPos));
		net.minecraft.server.MinecraftServer server = level.getServer();

		helper.startSequence()
				.thenExecute(() -> {
					// 1) 容错解析（纯函数断言）
					if (com.swaydy.opencraft.ai.AiCompanionService.resolveItem("ai_logo_block") == null
							|| com.swaydy.opencraft.ai.AiCompanionService.resolveItem("opencraft:ai_logo_block") == null
							|| com.swaydy.opencraft.ai.AiCompanionService.resolveItem("block.opencraft.ai_logo_block") == null
							|| com.swaydy.opencraft.ai.AiCompanionService.resolveItem("stone") == null
							|| com.swaydy.opencraft.ai.AiCompanionService.resolveItem("minecraft:stone") == null
							|| com.swaydy.opencraft.ai.AiCompanionService.resolveItem("item.minecraft.stick") == null) {
						throw new AssertionError("resolveItem 应接受完整 ID / 裸名 / 短名 / 描述名");
					}
					if (com.swaydy.opencraft.ai.AiCompanionService.resolveItem("不存在的物品xyz") != null) {
						throw new AssertionError("resolveItem 对不存在的物品应返回 null");
					}
					// 2) 召唤 bot，背包放一个 AI 徽标方块（工具清单里它显示为短名 ai_logo_block）
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							(com.swaydy.opencraft.assistant.player.AiAssistantPlayer)
									AiCompanionService.summonFor(player, bindPos);
					if (bot == null) {
						throw new AssertionError("召唤玩家形态助手失败");
					}
					bot.getInventory().setItem(bot.getInventory().getSelectedSlot(),
							new ItemStack(ModBlocks.AI_LOGO_BLOCK.asItem(), 1));
					// 3) 用短名 player_hand_to_player 递给主人（复现用户反馈的失败场景）
					com.swaydy.opencraft.plugins.ToolDefinition handTool =
							com.swaydy.opencraft.agent.AgentRegistry.agent("general_agent").toolMap()
									.get("player_hand_to_player");
					if (handTool == null) {
						throw new AssertionError("general_agent 应提供 player_hand_to_player 工具");
					}
					JsonObject args = new JsonObject();
					args.addProperty("item", "ai_logo_block");
					args.addProperty("amount", 1);
					ToolResult res = handTool.executor().execute(
							new ToolContext(server, bot, player, level), args);
					if (!res.ok()) {
						throw new AssertionError("用短名 ai_logo_block 递物应成功: " + res.message());
					}
					if (player.getInventory().countItem(ModBlocks.AI_LOGO_BLOCK.asItem()) < 1) {
						throw new AssertionError("主人背包应收到 AI 徽标方块");
					}
					// 清理
					com.swaydy.opencraft.assistant.AssistantFacade.dismiss(bot);
					AiCompanionService.resetAllHistory(player);
					helper.succeed();
				});
	}

	/**
	 * 验证玩家形态助手与容器的真实玩家式交互（player_container_* 工具）：
	 * 1. 放置一个箱子并预填物品（两栈木板 + 一栈木棍），召唤 bot 到箱子旁；
	 * 2. player_container_open（真实右键路径 ServerPlayerGameMode.useItemOn）→
	 *    bot.containerMenu 变成 ChestMenu（27 槽）；
	 * 3. player_container_list 能看到箱子内容与自己的背包（只读）；
	 * 4. player_container_take 把箱子里的木板整栈 shift 点击取进背包；
	 * 5. player_container_put 把背包里的圆石放进箱子；
	 * 6. player_container_close 关闭容器（containerMenu 复位为 inventoryMenu）。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
	public void assistantOpensAndUsesChest(GameTestHelper helper) {
		dismissAllPlayerBots();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		BlockPos platform = new BlockPos(4, 1, 4);
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				helper.setBlock(platform.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
			}
		}
		net.minecraft.world.phys.Vec3 playerPos = helper.absoluteVec(
				new net.minecraft.world.phys.Vec3(4.5, 2, 4.5));
		player.teleportTo(playerPos.x, playerPos.y, playerPos.z);

		BlockPos configBlockPos = new BlockPos(4, 1, 1);
		configureMockBlock(helper, configBlockPos, player);
		ServerLevel level = (ServerLevel) helper.getLevel();
		GlobalPos bindPos = GlobalPos.of(level.dimension(), helper.absolutePos(configBlockPos));
		net.minecraft.server.MinecraftServer server = level.getServer();

		// 箱子放在玩家东侧（bot 站在它旁边即可触及）
		BlockPos chestRel = new BlockPos(6, 1, 4);
		helper.setBlock(chestRel, Blocks.CHEST.defaultBlockState());

		helper.startSequence()
				.thenExecute(() -> {
					// 预填箱子：oak_planks×10 + oak_planks×8（两栈）+ stick×8
					net.minecraft.world.level.block.entity.ChestBlockEntity chest =
							helper.getBlockEntity(chestRel,
									net.minecraft.world.level.block.entity.ChestBlockEntity.class);
					if (chest == null) {
						throw new AssertionError("箱子方块实体未创建");
					}
					chest.setItem(0, new ItemStack(Items.OAK_PLANKS, 10));
					chest.setItem(1, new ItemStack(Items.OAK_PLANKS, 8));
					chest.setItem(2, new ItemStack(Items.STICK, 8));
					chest.setChanged();
				})
				.thenExecute(() -> {
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							(com.swaydy.opencraft.assistant.player.AiAssistantPlayer)
									AiCompanionService.summonFor(player, bindPos);
					if (bot == null) {
						throw new AssertionError("召唤玩家形态助手失败");
					}
					// 放到箱子旁边（bot 自然落回平台），背包放 16 个圆石待放进箱子
					net.minecraft.world.phys.Vec3 botPos = helper.absoluteVec(
							new net.minecraft.world.phys.Vec3(5.5, 2, 4.5));
					bot.teleportTo(botPos.x, botPos.y, botPos.z);
					bot.getInventory().add(new ItemStack(Items.COBBLESTONE, 16));
				})
				.thenIdle(10) // 落回地面
				.thenExecute(() -> {
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot == null) {
						throw new AssertionError("助手不应消失");
					}
					BlockPos chestAbs = helper.absolutePos(chestRel);

					// 1) 打开箱子（真实右键路径）
					ToolResult open = agentTool("player_container_open").executor().execute(
							new ToolContext(server, bot, player, level),
							xyzArgs(chestAbs.getX(), chestAbs.getY(), chestAbs.getZ()));
					if (!open.ok()) {
						throw new AssertionError("打开箱子应成功: " + open.message());
					}
					if (!(bot.containerMenu instanceof net.minecraft.world.inventory.ChestMenu menu)) {
						throw new AssertionError("打开后 containerMenu 应为 ChestMenu，实际 "
								+ bot.containerMenu.getClass().getSimpleName());
					}
					if (menu.getContainer().getContainerSize() != 27) {
						throw new AssertionError("单箱子应有 27 槽，实际 "
								+ menu.getContainer().getContainerSize());
					}

					// 2) 查看内容（只读，能看到箱子与背包两侧）
					ToolResult list = agentTool("player_container_list").executor().execute(
							new ToolContext(server, bot, player, level), new JsonObject());
					if (!list.ok() || !list.message().contains("oak_planks")
							|| !list.message().contains("stick")) {
						throw new AssertionError("列表应显示箱子内容: " + list.message());
					}

					// 3) 取 oak_planks（整栈 shift 点击，18 全取走）
					ToolResult take = agentTool("player_container_take").executor().execute(
							new ToolContext(server, bot, player, level), itemArgs("minecraft:oak_planks"));
					if (!take.ok() || !take.message().contains("18")) {
						throw new AssertionError("应取走全部 18 个橡木木板: " + take.message());
					}
					if (bot.getInventory().countItem(Items.OAK_PLANKS) != 18) {
						throw new AssertionError("bot 背包应有 18 个橡木木板，实际 "
								+ bot.getInventory().countItem(Items.OAK_PLANKS));
					}
					int planksLeft = 0;
					for (int i = 0; i < menu.getContainer().getContainerSize(); i++) {
						ItemStack s = menu.getContainer().getItem(i);
						if (s.getItem() == Items.OAK_PLANKS) {
							planksLeft += s.getCount();
						}
					}
					if (planksLeft != 0) {
						throw new AssertionError("取走后箱子不应再有木板: " + planksLeft);
					}

					// 4) 把背包里的圆石放进箱子
					ToolResult put = agentTool("player_container_put").executor().execute(
							new ToolContext(server, bot, player, level), itemArgs("minecraft:cobblestone"));
					if (!put.ok() || !put.message().contains("16")) {
						throw new AssertionError("应把 16 个圆石放进箱子: " + put.message());
					}
					int cobbleInChest = 0;
					for (int i = 0; i < menu.getContainer().getContainerSize(); i++) {
						ItemStack s = menu.getContainer().getItem(i);
						if (s.getItem() == Items.COBBLESTONE) {
							cobbleInChest += s.getCount();
						}
					}
					if (cobbleInChest != 16 || bot.getInventory().countItem(Items.COBBLESTONE) != 0) {
						throw new AssertionError("圆石应在箱子里（16）且不在 bot 背包: 箱=" + cobbleInChest
								+ " bot=" + bot.getInventory().countItem(Items.COBBLESTONE));
					}

					// 5) 关闭容器
					ToolResult close = agentTool("player_container_close").executor().execute(
							new ToolContext(server, bot, player, level), new JsonObject());
					if (!close.ok()) {
						throw new AssertionError("关闭容器应成功: " + close.message());
					}
					if (bot.containerMenu != bot.inventoryMenu) {
						throw new AssertionError("关闭后 containerMenu 应复位为 inventoryMenu");
					}

					// 清理
					com.swaydy.opencraft.assistant.AssistantFacade.dismiss(bot);
					AiCompanionService.resetAllHistory(player);
					helper.succeed();
				});
	}

	/**
	 * 验证玩家形态助手的跳跃能力（PlayerMovementController）：
	 * 1. 显式 jump() 指令会让 bot 真正跳起（Y 明显升高）再落回地面（重力正常）；
	 * 2. 移动遇到 1 格高台阶时会**主动跳上**（远早于 60 tick 的“卡住传送”回退，
	 *    用 tick 预算区分主动爬台阶 vs 传送回退），爬上高层平台并到达目标点。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 300)
	public void playerMovementJumpAndClimb(GameTestHelper helper) {
		dismissAllPlayerBots();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		// 平地平台（方块在相对 y=0，顶面 = playerPos.y-1，bot 会落在其上）
		BlockPos platform = new BlockPos(4, 1, 4);
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				helper.setBlock(platform.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
			}
		}
		// 高层平台（方块在相对 y=1，顶面 = playerPos.y）：从西面（相对 x=5）向东延伸到 x=8，
		// 构成一个 1 格高的台阶——bot 必须跳上才能到达上面的目标点
		for (int dx = 5; dx <= 8; dx++) {
			for (int dz = 2; dz <= 6; dz++) {
				helper.setBlock(new BlockPos(dx, 1, dz), Blocks.STONE.defaultBlockState());
			}
		}
		net.minecraft.world.phys.Vec3 playerPos = helper.absoluteVec(
				new net.minecraft.world.phys.Vec3(4.5, 2, 4.5));
		player.teleportTo(playerPos.x, playerPos.y, playerPos.z);

		BlockPos blockPos = new BlockPos(4, 1, 1);
		configureMockBlock(helper, blockPos, player);
		ServerLevel level = (ServerLevel) helper.getLevel();
		GlobalPos bindPos = GlobalPos.of(player.level().dimension(), helper.absolutePos(blockPos));

		// 高层平台上的目标点（相对 x=7.5 y=2 z=4.5；站在高层顶面 = playerPos.y）
		net.minecraft.world.phys.Vec3 target = helper.absoluteVec(
				new net.minecraft.world.phys.Vec3(7.5, 2, 4.5));
		final double[] startY = {0.0};
		final long[] climbStartTick = {0};

		helper.startSequence()
				.thenExecute(() -> {
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							(com.swaydy.opencraft.assistant.player.AiAssistantPlayer)
									AiCompanionService.summonFor(player, bindPos);
					if (bot == null) {
						throw new AssertionError("召唤玩家形态助手失败");
					}
					bot.teleportTo(playerPos.x, playerPos.y, playerPos.z); // 到平台中央，等它落稳
				})
				.thenIdle(20)
				.thenExecute(() -> {
					// 1) 显式跳跃：应成功且先落稳在平台上（y ≈ playerPos.y-1）
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot == null) {
						throw new AssertionError("助手不应消失");
					}
					if (Math.abs(bot.getY() - (playerPos.y - 1.0)) > 0.6) {
						throw new AssertionError("bot 应先落在平台上（y≈" + (playerPos.y - 1) + "），实际 y=" + bot.getY());
					}
					startY[0] = bot.getY();
					if (!bot.movement().jump()) {
						throw new AssertionError("显式 jump() 应成功（着地时）");
					}
				})
				.thenWaitUntil(() -> {
					// 2) 跳起：Y 明显高于起跳点
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot == null) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal("助手不存在"),
								(int) helper.getTick());
					}
					if (bot.getY() - startY[0] < 0.35) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal(
										"等待 bot 跳起（当前 y=" + bot.getY() + "）…"),
								(int) helper.getTick());
					}
				})
				.thenWaitUntil(() -> {
					// 3) 落回地面：Y 回到起跳点附近（重力正常、没悬空）
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot == null) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal("助手不存在"),
								(int) helper.getTick());
					}
					if (bot.getY() > startY[0] + 0.35) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal(
										"等待 bot 落回地面（当前 y=" + bot.getY() + "）…"),
								(int) helper.getTick());
					}
				})
				.thenExecute(() -> {
					// 4) 走到 1 格台阶前：先放回地面，命令前往高层平台上的目标
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot == null) {
						throw new AssertionError("助手不应消失");
					}
					bot.teleportTo(playerPos.x, playerPos.y, playerPos.z);
					bot.setYRot(0.0F);
					bot.setYHeadRot(0.0F);
					bot.yBodyRot = 0.0F;
					climbStartTick[0] = helper.getTick();
					bot.movement().moveTo(target, 1.0, true);
				})
				.thenWaitUntil(() -> {
					// 5) 主动爬台阶：Y 升到高层平台顶面（playerPos.y）附近。
					//    必须在 60 tick“卡住传送”回退之前到达，否则说明没跳、是传送上去的
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot == null) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal("助手不存在"),
								(int) helper.getTick());
					}
					long elapsed = helper.getTick() - climbStartTick[0];
					if (elapsed > 55) {
						throw new AssertionError("超过 55 tick 才到高层（已经 "
								+ elapsed + " tick），疑似用了卡住传送回退而非主动跳上台阶");
					}
					if (bot.getY() < playerPos.y - 0.45) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal(
										"等待 bot 跳上 1 格台阶（当前 y=" + bot.getY() + "）…"),
								(int) helper.getTick());
					}
				})
				.thenWaitUntil(() -> {
					// 6) 到达高层平台上的目标点
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot == null) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal("助手不存在"),
								(int) helper.getTick());
					}
					double dHoriz = Math.hypot(bot.getX() - target.x, bot.getZ() - target.z);
					if (dHoriz > 1.2) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal(
										"等待 bot 到达高层目标点（水平距离 "
												+ Math.round(dHoriz * 10) / 10.0 + "）…"),
								(int) helper.getTick());
					}
				})
				.thenExecute(() -> {
					// 7) 收尾：站在高层平台上、清理
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot == null) {
						throw new AssertionError("助手不应消失");
					}
					if (Math.abs(bot.getY() - playerPos.y) > 0.6) {
						throw new AssertionError("到达后应站在高层平台顶面（y≈" + playerPos.y
								+ "），实际 y=" + bot.getY());
					}
					com.swaydy.opencraft.assistant.AssistantFacade.dismiss(bot);
					AiCompanionService.resetAllHistory(player);
					helper.succeed();
				});
	}

	/**
	 * 验证中断交互（AgentRuntime.interrupt）：
	 * 1. 提问后循环在跑时 interrupt() 能立即释放忙锁；
	 * 2. 中断（或第一次已结束时）随后立刻再提问，不再被“正忙”拒绝，能收到第二条的回复。
	 * 核心断言是「第二条提问一定被处理并得到回复」——这是忙锁已释放的确定性契约。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 4000)
	public void agentInterruptReleasesLoop(GameTestHelper helper) {
		dismissAllPlayerBots();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		BlockPos platform = new BlockPos(4, 1, 4);
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				helper.setBlock(platform.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
			}
		}
		net.minecraft.world.phys.Vec3 playerPos = helper.absoluteVec(
				new net.minecraft.world.phys.Vec3(4.5, 2, 4.5));
		player.teleportTo(playerPos.x, playerPos.y, playerPos.z);

		BlockPos blockPos = new BlockPos(4, 1, 1);
		configureMockBlock(helper, blockPos, player);
		ServerLevel level = (ServerLevel) helper.getLevel();
		GlobalPos bindPos = GlobalPos.of(player.level().dimension(), helper.absolutePos(blockPos));
		final boolean[] interrupted = {false};

		helper.startSequence()
				.thenExecute(() -> {
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							(com.swaydy.opencraft.assistant.player.AiAssistantPlayer)
									AiCompanionService.summonFor(player, bindPos);
					if (bot == null) {
						throw new AssertionError("召唤玩家形态助手失败");
					}
					// 第一条提问（命令模式，走真实 agentic loop）
					AiCompanionService.ask(player, "帮我看一下周围。");
				})
				.thenIdle(1)
				.thenExecute(() -> {
					// 极短等待后尝试中断：mock 回复通常还没回来，loop 大概率还在跑。
					// 即使已结束（interrupt=false），下面第二条提问仍须被接受——契约不受影响。
					interrupted[0] = com.swaydy.opencraft.agent.AgentRuntime.interrupt(bindPos);
				})
				.thenIdle(5)
				.thenExecute(() -> {
					// 第二条提问：必须被接受并得到回复（证明忙锁已释放、不再被“正忙”吞掉）
					AiCompanionService.ask(player, "你好。");
				})
				.thenWaitUntil(() -> {
					// 历史达到：2 条 user + ≥2 条 assistant（第一条的回复或“被中断”占位 + 第二条的回复）
					java.util.List<com.swaydy.opencraft.ai.LlmClient.Message> h =
							AiCompanionService.getHistory(bindPos);
					long users = h.stream()
							.filter(m -> m.role() == com.swaydy.opencraft.ai.LlmClient.Role.USER).count();
					long assistants = h.stream()
							.filter(m -> m.role() == com.swaydy.opencraft.ai.LlmClient.Role.ASSISTANT).count();
					if (users < 2 || assistants < 2) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal(
										"等待第二条提问得到回复（user=" + users + " assistant=" + assistants + "）…"),
								(int) helper.getTick());
					}
					if (interrupted[0]) {
						com.swaydy.opencraft.logging.DebugLog.log("test",
								"中断成功：首问被中止，第二条提问正常处理并回复");
					}
				})
				.thenExecute(() -> {
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot != null) {
						com.swaydy.opencraft.assistant.AssistantFacade.dismiss(bot);
					}
					AiCompanionService.resetAllHistory(player);
					helper.succeed();
				});
	}

	/**
	 * 验证“AI 助手跟随模式”（默认跟随；玩家下达指令后退出跟随；指令完成回到跟随）：
	 * 1. 召唤玩家形态 bot → 默认 isFollowing()==true（跟随模式）；
	 * 2. 玩家移开 → bot 向玩家方向移动（跟随真的驱动移动，不是停在原地）；
	 * 3. 下达指令（ask，真实 agentic loop）→ 立即退出跟随（isFollowing()==false）；
	 * 4. 等指令完成（历史 ≥2 条，loop 收尾）→ 回到跟随（isFollowing()==true）；
	 * 5. 再次移开玩家 → bot 重新跟上来（跟随恢复）。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 4000)
	public void playerAssistantFollowMode(GameTestHelper helper) {
		dismissAllPlayerBots();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		BlockPos platform = new BlockPos(4, 1, 4);
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				helper.setBlock(platform.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
			}
		}
		for (int dy = 0; dy <= 3; dy++) {
			helper.setBlock(platform.offset(0, dy, 0), Blocks.AIR.defaultBlockState());
		}
		net.minecraft.world.phys.Vec3 playerPos = helper.absoluteVec(
				new net.minecraft.world.phys.Vec3(4.5, 2, 4.5));
		player.teleportTo(playerPos.x, playerPos.y, playerPos.z);

		BlockPos blockPos = new BlockPos(4, 1, 1);
		configureMockBlock(helper, blockPos, player);
		ServerLevel level = (ServerLevel) helper.getLevel();
		GlobalPos bindPos = GlobalPos.of(player.level().dimension(), helper.absolutePos(blockPos));
		final double[] followStartX = {0.0};

		helper.startSequence()
				.thenExecute(() -> {
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							(com.swaydy.opencraft.assistant.player.AiAssistantPlayer)
									AiCompanionService.summonFor(player, bindPos);
					if (bot == null) {
						throw new AssertionError("召唤玩家形态助手失败");
					}
					// 1) 默认跟随模式
					if (!bot.isFollowing()) {
						throw new AssertionError("召唤后助手应默认处于跟随模式");
					}
					// 把 bot 放到平台中间固定起点，再把玩家沿 +X 移开 8 格
					net.minecraft.world.phys.Vec3 standPos = helper.absoluteVec(
							new net.minecraft.world.phys.Vec3(4.5, 2, 4.5));
					bot.teleportTo(standPos.x, standPos.y, standPos.z);
					followStartX[0] = standPos.x;
					player.teleportTo(playerPos.x + 8.0, playerPos.y, playerPos.z);
				})
				.thenWaitUntil(() -> {
					// 2) 跟随：bot 应朝玩家方向（+X）移动超过 3 格
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot == null) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal("玩家形态助手不存在"),
								(int) helper.getTick());
					}
					double moved = bot.getX() - followStartX[0];
					if (moved < 3.0) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal(
										"等待助手跟随走向玩家（当前 +X 位移 "
												+ Math.round(moved * 10) / 10.0 + "）…"),
								(int) helper.getTick());
					}
				})
				.thenExecute(() -> {
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot == null) {
						throw new AssertionError("玩家形态助手不存在");
					}
					// 3) 下达指令（真实 agentic loop，命令模式）→ 立即退出跟随
					AiCompanionService.ask(player, bot, "你好，介绍一下你自己。");
					if (bot.isFollowing()) {
						throw new AssertionError("玩家下达指令后助手应立即退出跟随模式");
					}
				})
				.thenWaitUntil(() -> {
					// 4) 等指令完成（user+assistant 历史 ≥2 条）→ 回到跟随
					if (AiCompanionService.historySize(bindPos) < 2) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal("等待指令完成（回复写入历史）…"),
								(int) helper.getTick());
					}
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot == null) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal("玩家形态助手不存在"),
								(int) helper.getTick());
					}
					if (!bot.isFollowing()) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal("指令完成后助手应回到跟随模式"),
								(int) helper.getTick());
					}
				})
				.thenExecute(() -> {
					// 5) 再次移开玩家 → bot 应重新跟上来（跟随恢复）
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot == null) {
						throw new AssertionError("玩家形态助手不存在");
					}
					player.teleportTo(playerPos.x + 14.0, playerPos.y, playerPos.z);
				})
				.thenWaitUntil(() -> {
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot == null) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal("玩家形态助手不存在"),
								(int) helper.getTick());
					}
					double moved = bot.getX() - followStartX[0];
					if (moved < 8.0) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal(
										"等待助手恢复跟随（当前 +X 位移 "
												+ Math.round(moved * 10) / 10.0 + "）…"),
								(int) helper.getTick());
					}
				})
				.thenExecute(() -> {
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer bot =
							com.swaydy.opencraft.assistant.player.PlayerAssistantService.findBoundTo(bindPos);
					if (bot != null) {
						com.swaydy.opencraft.assistant.AssistantFacade.dismiss(bot);
					}
					AiCompanionService.resetAllHistory(player);
					helper.succeed();
				});
	}

	/**
	 * 循环事件模块（loop 包）端到端验证：heal_aura（治疗光环）最小实现。
	 * 1. 召唤助手绑定方块 → heal_aura 循环实例自动启动（LoopEngine.isRunning 为真）;
	 * 2. 主人受伤（生命 10/20,食物 5 抑制原版自然回血）→ 每 ~40 tick 回 1 点血,
	 *    轮询等到生命回升;
	 * 3. 等到满血 → persistent 循环只结束本轮、实例仍在运行（闲置监视）;
	 * 4. 送走助手 → 循环实例停止。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 2000)
	public void healAuraLoopHealsOwner(GameTestHelper helper) {
		dismissAllPlayerBots();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		// 铺平台 + 放 AI 徽标方块（heal 不调 LLM,配置随意）
		BlockPos platform = new BlockPos(4, 1, 4);
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				helper.setBlock(platform.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
			}
		}
		for (int dy = 0; dy <= 3; dy++) {
			helper.setBlock(platform.offset(0, dy, 0), Blocks.AIR.defaultBlockState());
		}
		net.minecraft.world.phys.Vec3 playerPos = helper.absoluteVec(
				new net.minecraft.world.phys.Vec3(4.5, 2, 4.5));
		player.teleportTo(playerPos.x, playerPos.y, playerPos.z);
		BlockPos blockPos = new BlockPos(4, 1, 1);
		configureMockBlock(helper, blockPos, player);
		ResourceKey<net.minecraft.world.level.Level> dimension = player.level().dimension();
		BlockPos absPos = helper.absolutePos(blockPos);
		GlobalPos bindPos = GlobalPos.of(dimension, absPos);

		helper.startSequence()
				.thenExecute(() -> {
					// 1) 召唤（绑定最近的未绑定方块）→ 下一 tick 进入查找表
					if (com.swaydy.opencraft.assistant.AssistantFacade.summonNearest(player) == null) {
						throw new AssertionError("召唤助手失败");
					}
				})
				.thenIdle(5)
				.thenExecute(() -> {
					if (com.swaydy.opencraft.assistant.AssistantFacade.findBoundTo(helper.getLevel(), bindPos) == null) {
						throw new AssertionError("召唤后应绑定助手到方块");
					}
					// 召唤即自动启动循环事件
					if (!com.swaydy.opencraft.loop.LoopEngine.isRunning(bindPos, "heal_aura")) {
						throw new AssertionError("召唤后 heal_aura 循环实例应已启动");
					}
					// 2) 受伤到 10/20;食物 5:低于 18 无自然回血、高于 0 无饥饿掉血,
					//    确保只有治疗光环在回血
					player.setHealth(10.0F);
					player.getFoodData().setFoodLevel(5);
				})
				.thenWaitUntil(() -> {
					// 3) 每 ~40 tick 回 1 点血 → 轮询等到生命回升
					if (player.getHealth() <= 10.0F) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal(
										"等待治疗光环生效（当前生命 " + player.getHealth() + "）…"),
								(int) helper.getTick());
					}
				})
				.thenWaitUntil(() -> {
					// 4) 等到满血（监测函数 STOP 结束本轮）
					if (player.getHealth() < player.getMaxHealth()) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal(
										"等待治疗到满血（当前生命 " + player.getHealth() + "）…"),
								(int) helper.getTick());
					}
				})
				.thenExecute(() -> {
					// 5) 满血后 persistent 循环只闲置不消亡;且有迭代记录
					if (!com.swaydy.opencraft.loop.LoopEngine.isRunning(bindPos, "heal_aura")) {
						throw new AssertionError("满血后 persistent 循环应仍在运行（闲置监视）");
					}
					if (com.swaydy.opencraft.loop.LoopEngine.status().stream()
							.noneMatch(s -> bindPos.equals(s.anchor()) && s.iteration() > 0)) {
						throw new AssertionError("循环实例应有治疗迭代记录");
					}
					// 6) 送走助手 → 循环停止
					com.swaydy.opencraft.assistant.AiAssistant bound =
							com.swaydy.opencraft.assistant.AssistantFacade.findBoundTo(helper.getLevel(), bindPos);
					if (bound == null) {
						throw new AssertionError("助手不存在,无法送走");
					}
					com.swaydy.opencraft.assistant.AssistantFacade.dismiss(bound);
				})
				.thenIdle(5)
				.thenExecute(() -> {
					if (com.swaydy.opencraft.loop.LoopEngine.isRunning(bindPos, "heal_aura")) {
						throw new AssertionError("送走助手后 heal_aura 循环实例应停止");
					}
					AiCompanionService.resetAllHistory(player);
					helper.succeed();
				});
	}

	/**
	 * 验证配置界面「循环事件开关」的服务器端行为（配置保存即时生效）：
	 * 1. 召唤助手绑定方块 → heal_aura 循环实例自动启动；
	 * 2. 保存 enabledLoops=[]（全部关闭）→ 已绑定方块的循环实例立即停止；
	 * 3. 保存 enabledLoops=["heal_aura"]（重新开启）→ 循环实例重新启动；
	 * 4. 送走助手 → 全部循环停止。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 400)
	public void configLoopToggleStartsAndStops(GameTestHelper helper) {
		dismissAllPlayerBots();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		// 铺平台 + 放 AI 徽标方块（heal 不调 LLM，配置随意）
		BlockPos platform = new BlockPos(4, 1, 4);
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				helper.setBlock(platform.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
			}
		}
		for (int dy = 0; dy <= 3; dy++) {
			helper.setBlock(platform.offset(0, dy, 0), Blocks.AIR.defaultBlockState());
		}
		net.minecraft.world.phys.Vec3 playerPos = helper.absoluteVec(
				new net.minecraft.world.phys.Vec3(4.5, 2, 4.5));
		player.teleportTo(playerPos.x, playerPos.y, playerPos.z);
		BlockPos blockPos = new BlockPos(4, 1, 1);
		configureMockBlock(helper, blockPos, player);
		ResourceKey<net.minecraft.world.level.Level> dimension = player.level().dimension();
		BlockPos absPos = helper.absolutePos(blockPos);
		GlobalPos bindPos = GlobalPos.of(dimension, absPos);

		helper.startSequence()
				.thenExecute(() -> {
					// 保存配置需要 op
					net.minecraft.server.players.NameAndId nameAndId =
							new net.minecraft.server.players.NameAndId(player.getGameProfile());
					player.level().getServer().getPlayerList().op(nameAndId);
					// 1) 召唤（绑定最近的未绑定方块）→ 下一 tick 进入查找表
					if (com.swaydy.opencraft.assistant.AssistantFacade.summonNearest(player) == null) {
						throw new AssertionError("召唤助手失败");
					}
				})
				.thenIdle(5)
				.thenExecute(() -> {
					if (com.swaydy.opencraft.assistant.AssistantFacade.findBoundTo(helper.getLevel(), bindPos) == null) {
						throw new AssertionError("召唤后应绑定助手到方块");
					}
					// 召唤即自动启动循环事件
					if (!com.swaydy.opencraft.loop.LoopEngine.isRunning(bindPos, "heal_aura")) {
						throw new AssertionError("召唤后 heal_aura 循环实例应已启动");
					}
					// 2) 保存全部关闭（enabledLoops 为空 = 显式全关）→ 循环应立即停止
					AiConfigData disabled = new AiConfigData(
							blockEntity(helper, absPos).getConfig().toData().baseUrl(), "", false, false,
							"mock-model", 0.8, 20, 15, "zh-CN",
							64.0, 1.0, "小智", "general_agent", java.util.List.of(), "default");
					AiConfigHandler.save(player, absPos, dimension, disabled.toJson());
				})
				.thenExecute(() -> {
					if (com.swaydy.opencraft.loop.LoopEngine.isRunning(bindPos, "heal_aura")) {
						throw new AssertionError("关闭后 heal_aura 循环实例应已停止");
					}
					// 3) 重新开启（enabledLoops=["heal_aura"]）→ 循环重新启动
					AiConfigData enabled = new AiConfigData(
							blockEntity(helper, absPos).getConfig().toData().baseUrl(), "", false, false,
							"mock-model", 0.8, 20, 15, "zh-CN",
							64.0, 1.0, "小智", "general_agent", java.util.List.of("heal_aura"), "default");
					AiConfigHandler.save(player, absPos, dimension, enabled.toJson());
				})
				.thenExecute(() -> {
					if (!com.swaydy.opencraft.loop.LoopEngine.isRunning(bindPos, "heal_aura")) {
						throw new AssertionError("重新开启后 heal_aura 循环实例应运行");
					}
					// 4) 送走助手 → 循环停止
					com.swaydy.opencraft.assistant.AiAssistant bound =
							com.swaydy.opencraft.assistant.AssistantFacade.findBoundTo(helper.getLevel(), bindPos);
					if (bound == null) {
						throw new AssertionError("助手不存在,无法送走");
					}
					com.swaydy.opencraft.assistant.AssistantFacade.dismiss(bound);
				})
				.thenIdle(5)
				.thenExecute(() -> {
					if (com.swaydy.opencraft.loop.LoopEngine.isRunning(bindPos, "heal_aura")) {
						throw new AssertionError("送走助手后 heal_aura 循环实例应停止");
					}
					AiCompanionService.resetAllHistory(player);
					helper.succeed();
				});
	}

	/** 读取绝对坐标处的 AI 徽标方块实体（测试辅助）。 */
	private static AiLogoBlockEntity blockEntity(GameTestHelper helper, BlockPos absPos) {
		net.minecraft.world.level.block.entity.BlockEntity be = helper.getLevel().getBlockEntity(absPos);
		if (!(be instanceof AiLogoBlockEntity logo)) {
			throw new AssertionError("配置方块实体不存在");
		}
		return logo;
	}
}
