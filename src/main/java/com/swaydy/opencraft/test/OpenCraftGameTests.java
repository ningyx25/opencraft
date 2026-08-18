package com.swaydy.opencraft.test;

import com.swaydy.opencraft.ai.AiBlockConfig;
import com.swaydy.opencraft.ai.AiCompanionService;
import com.swaydy.opencraft.ai.AiConfigData;
import com.swaydy.opencraft.ai.AiConfigHandler;
import com.swaydy.opencraft.block.AiLogoBlock;
import com.swaydy.opencraft.block.AiLogoBlockEntity;
import com.swaydy.opencraft.block.ModBlocks;
import com.swaydy.opencraft.entity.AiAssistantEntity;
import com.swaydy.opencraft.entity.MineBlockTask;
import com.swaydy.opencraft.entity.ModEntities;
import com.swaydy.opencraft.plugins.InventoryPlugin;
import com.swaydy.opencraft.plugins.CraftingPlugin;
import com.swaydy.opencraft.agent.ToolContext;
import com.swaydy.opencraft.agent.ToolDefinition;
import com.swaydy.opencraft.agent.ToolResult;
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
 * 10. assistantRightClickInteract —— 右键助手互动（绑定/普通右键开界面/非主人拒绝/聊天/送走）。
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

	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 400)
	public void assistantLifecycleAndChat(GameTestHelper helper) {
		// 清掉之前测试遗留在世界里的助手（测试世界在 run/world 中持久化）
	helper.killAllEntitiesOfClass(AiAssistantEntity.class);
		dismissAllPlayerBots();

		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		// 测试世界是虚空：把玩家挪到测试结构内（结构区块在整个测试期间保持加载/运转），
		// 并在脚下垫一块平台，保证助手有安全出生点
		BlockPos platform = new BlockPos(4, 1, 4); // 结构相对坐标
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

		// 放置并配置一个 AI 徽标方块（配置载体，指向 mock LLM），助手会绑定到它
		BlockPos configBlockPos = new BlockPos(4, 1, 1);
		configureMockBlock(helper, configBlockPos, player);

		helper.startSequence()
				.thenExecute(() -> {
					// 1) 召唤并绑定（自动绑定最近的未绑定 AI 徽标方块；首次召唤异步打招呼）
					AiAssistantEntity assistant = (AiAssistantEntity) AiCompanionService.summonLegacyEntityFor(player);					if (assistant == null) {
						throw new AssertionError("summonFor 返回 null");
					}
					// 助手应绑定到刚配置的方块
					if (assistant.getConfigBlock() == null
							|| !assistant.getConfigBlock().pos().equals(helper.absolutePos(configBlockPos))) {
						throw new AssertionError("助手未绑定到 AI 徽标方块");
					}
					if (!assistant.getConfig().isUsable()) {
						throw new AssertionError("助手配置不可用（未读到方块配置）");
					}
					// 显示名 = 配置的名字（默认小智）+ 方块坐标（多助手时用于区分）
					BlockPos nameCheckPos = helper.absolutePos(configBlockPos);
					net.minecraft.network.chat.Component expectedName =
							net.minecraft.network.chat.Component.translatable(
									"entity.opencraft.ai_assistant.named", "小智",
									nameCheckPos.getX() + "," + nameCheckPos.getY() + "," + nameCheckPos.getZ());
					if (!expectedName.equals(assistant.getCustomName())) {
						throw new AssertionError("助手显示名应使用配置的名字（小智 + 坐标），实际 "
								+ assistant.getCustomName());
					}
					// 激活状态：助手被召唤 → 绑定方块应亮起
					BlockPos absConfig = helper.absolutePos(configBlockPos);
					if (!helper.getLevel().getBlockState(absConfig)
							.getValue(AiLogoBlock.POWERED)) {
						throw new AssertionError("助手召唤后方块应处于激活（亮起）状态");
					}
					if (helper.getLevel().getBlockState(absConfig).getLightEmission() != 15) {
						throw new AssertionError("激活状态下方块应发光（亮度 15）");
					}
				})
				.thenIdle(5)
				.thenExecute(() -> {
					// 2) 找回同一个实体（等一 tick 让实体进入世界的查找表）
					if (ModEntities.findAssistantsFor(player).isEmpty()) {
						throw new AssertionError("findAssistantsFor 找不到刚召唤的助手");
					}
					// 3) 第一条对话（异步请求 mock LLM，回复无动作）
					AiCompanionService.ask(player, "你好，小智！请介绍一下你自己。");
				})
				.thenWaitUntil(() -> {
					// 4) 等第一条回复写入该助手（按方块键控）的历史。
					//    流式回复在独立线程上读取、写回历史，因此轮询等待而不是固定 thenIdle
					//    （gametest 服务器会“冲刺”tick，固定 tick 数的墙钟时间可能不够）。
					AiAssistantEntity assistant = ModEntities.findNearestAssistantFor(player);
					if (assistant == null) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal("对话后助手实体不存在"),
								(int) helper.getTick());
					}
					if (AiCompanionService.historySize(assistant.getConfigBlock()) < 2) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal(
										"期望对话历史 >= 2 条（请确认方块配置的 ai.baseUrl 指向可用接口）"),
								(int) helper.getTick());
					}
				})
				.thenExecute(() -> {
					// 5) 第二条消息（回复仍走 agentic loop，但 mock 端点不回工具调用，最终给纯文本回复）
					AiCompanionService.ask(player, "谢谢，再介绍一下怎么挖矿吧。");
				})
				.thenWaitUntil(() -> {
					// 6) 等第二条回复写入该助手的历史（agentic loop 无工具调用时的收尾）
					AiAssistantEntity a2 = ModEntities.findNearestAssistantFor(player);
					if (a2 == null) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal("对话后助手实体不存在"),
								(int) helper.getTick());
					}
					if (AiCompanionService.historySize(a2.getConfigBlock()) < 4) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal(
										"期望历史至少 4 条（两次对话的 user+assistant），实际 "
												+ AiCompanionService.historySize(a2.getConfigBlock())),
								(int) helper.getTick());
					}
				})
				.thenExecute(() -> {
					// 7) 跨维度：把玩家传送到地狱——跟随模式已移除，助手应留在主世界不跟过来
					net.minecraft.server.MinecraftServer server = player.level().getServer();
					net.minecraft.server.level.ServerLevel nether =
							server.getLevel(net.minecraft.world.level.Level.NETHER);
					if (nether == null) {
						throw new AssertionError("地狱维度不存在");
					}
					for (int dx = -2; dx <= 2; dx++) {
						for (int dz = -2; dz <= 2; dz++) {
							nether.setBlock(new BlockPos(dx, 99, dz),
									Blocks.NETHERRACK.defaultBlockState(), 3);
						}
					}
					for (int dy = 100; dy <= 103; dy++) {
						nether.setBlock(new BlockPos(0, dy, 0), Blocks.AIR.defaultBlockState(), 3);
					}
					player.teleportTo(nether, 0.5, 100, 0.5,
							java.util.Set.of(), 0.0F, 0.0F, true);
				})
				.thenIdle(70)
				.thenExecute(() -> {
					// 8) 助手应留在主世界（跟随模式已移除，不跨维度传送）
					AiAssistantEntity assistant = ModEntities.findNearestAssistantFor(player);
					if (assistant == null) {
						throw new AssertionError("跨维度后助手实体不存在");
					}
					if (assistant.level() == player.level()) {
						throw new AssertionError("跟随模式已移除：助手不应跨维度跟随到地狱（助手在 "
								+ assistant.level().dimension().identifier()
								+ "，玩家在 " + player.level().dimension().identifier() + "）");
					}
					// 跨维度后仍能读取绑定方块（主世界）的配置
					if (!assistant.getConfig().isUsable()) {
						throw new AssertionError("跨维度后配置不可用");
					}
					// 9) 送走助手
					if (!AiCompanionService.dismissFor(player)) {
						throw new AssertionError("dismissFor 失败");
					}
					if (!ModEntities.findAssistantsFor(player).isEmpty()) {
						throw new AssertionError("dismiss 后助手仍然存在");
					}
					// 送走后绑定方块应熄灭（未激活）
					BlockPos absConfig = helper.absolutePos(configBlockPos);
					if (helper.getLevel().getBlockState(absConfig)
							.getValue(AiLogoBlock.POWERED)) {
						throw new AssertionError("助手送走后绑定方块应熄灭");
					}
					if (helper.getLevel().getBlockState(absConfig).getLightEmission() != 0) {
						throw new AssertionError("助手送走后绑定方块不应发光");
					}
					// 清理历史
					AiCompanionService.resetHistory(assistant.getConfigBlock());
				})
				.thenExecute(() -> {
					// 10) 共存性：传回主世界结构处（显式跨维度），重新召唤（绑定最近的未绑定方块）
					net.minecraft.server.level.ServerLevel overworld = player.level().getServer()
							.getLevel(net.minecraft.world.level.Level.OVERWORLD);
					net.minecraft.world.phys.Vec3 back = helper.absoluteVec(
							new net.minecraft.world.phys.Vec3(4.5, 2, 4.5));
					player.teleportTo(overworld, back.x, back.y, back.z,
							java.util.Set.of(), 0.0F, 0.0F, true);
					AiAssistantEntity assistant = (AiAssistantEntity) AiCompanionService.summonLegacyEntityFor(player);					if (assistant == null || assistant.getConfigBlock() == null
							|| !assistant.getConfigBlock().pos().equals(helper.absolutePos(configBlockPos))) {
						throw new AssertionError("重新召唤后助手未绑定到配置方块");
					}
				})
				.thenIdle(5)
				.thenExecute(() -> {
					// 破坏绑定的 AI 徽标方块 → 助手应随之消失（共存）
					if (ModEntities.findAssistantsFor(player).isEmpty()) {
						throw new AssertionError("破坏方块前助手应存在");
					}
					helper.getLevel().destroyBlock(helper.absolutePos(configBlockPos), false);
				})
				.thenIdle(10)
				.thenExecute(() -> {
					if (!ModEntities.findAssistantsFor(player).isEmpty()) {
						throw new AssertionError("绑定方块被破坏后，助手应随之消失");
					}
					AiCompanionService.resetAllHistory(player);
				})
				.thenSucceed();
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
	helper.killAllEntitiesOfClass(AiAssistantEntity.class);
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
					if (!ModEntities.findAssistantsFor(player).isEmpty()) {
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
							"改名小智", "general_agent");
					AiConfigHandler.save(player, absPos, dimension, edited.toJson());
					if (!"in-game-edited-model".equals(blockEntity.getConfig().model)) {
						throw new AssertionError("保存后方块配置未即时生效");
					}
					if (!"general_agent".equals(blockEntity.getConfig().agent)) {
						throw new AssertionError("保存后 agent 预设应生效");
					}
					if (!"new-secret-key-456".equals(blockEntity.getConfig().apiKey)) {
						throw new AssertionError("更换密钥未生效");
					}
					if (!"改名小智".equals(blockEntity.getConfig().name)) {
						throw new AssertionError("保存后助手名字未生效");
					}
					// 5) 未勾选更换时保存：密钥应保留（客户端只会传空串）
					AiConfigData keepKey = new AiConfigData(
							sent.baseUrl(), "", false, true,
							"model-keep-key",
							sent.temperature(), sent.maxHistoryMessages(), sent.timeoutSeconds(),
							sent.language(),
							sent.maxDistance(), sent.speed(),
							"keep-key-name", "general_agent");
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
	helper.killAllEntitiesOfClass(AiAssistantEntity.class);
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
	helper.killAllEntitiesOfClass(AiAssistantEntity.class);
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
					AiAssistantEntity assistant = (AiAssistantEntity) AiCompanionService.summonLegacyEntityFor(player);					if (assistant == null) {
						throw new AssertionError("summonFor 返回 null");
					}
					if (ModEntities.findAssistantsFor(player).isEmpty()) {
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
					if (!ModEntities.findAssistantsFor(player).isEmpty()) {
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
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 300)
	public void multipleAssistantsCoexist(GameTestHelper helper) {
	helper.killAllEntitiesOfClass(AiAssistantEntity.class);
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

		// 两个 AI 徽标方块：A 在玩家南侧（近），B 在西侧（远）
		BlockPos blockA = new BlockPos(4, 1, 6); // 距玩家 |0|+1+2 = 3
		BlockPos blockB = new BlockPos(1, 1, 4); // 距玩家 |3|+1+0 = 4
		configureMockBlock(helper, blockA, player); // 默认 mock 配置（model=mock-model）
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

		helper.startSequence()
				.thenExecute(() -> {
					// 1) 分别用两个方块召唤 → 两个不同的助手，各绑定自己的方块
					AiAssistantEntity a1 = (AiAssistantEntity) AiCompanionService.summonLegacyEntityFor(player, absA);					AiAssistantEntity b1 = (AiAssistantEntity) AiCompanionService.summonLegacyEntityFor(player, absB);					if (a1 == null || b1 == null) {
						throw new AssertionError("多助手召唤失败");
					}
					if (a1 == b1) {
						throw new AssertionError("不同方块应产生不同助手实例");
					}
					if (!absA.equals(a1.getConfigBlock()) || !absB.equals(b1.getConfigBlock())) {
						throw new AssertionError("助手未绑定各自方块");
					}
					// 2) 幂等：同一 tick 内再次用 A 召唤返回同一实例（RECENT_SUMMONS 缓存）
					if (AiCompanionService.summonLegacyEntityFor(player, absA) != a1) {
						throw new AssertionError("同一方块重复召唤应返回同一助手");
					}
					// 3) 各助手使用自己方块的独立配置（模型 + 名字）
					if (!"mock-model".equals(a1.getConfig().model)
							|| !"mock-model-b".equals(b1.getConfig().model)) {
						throw new AssertionError("各助手应使用自己方块的配置");
					}
					if (!"小智".equals(a1.getConfig().effectiveName())
							|| !"小红".equals(b1.getConfig().effectiveName())) {
						throw new AssertionError("各助手应使用自己方块配置的名字");
					}
					// 显示名应不同（名字不同 → 聊天时能区分）
					if (a1.getCustomName().equals(b1.getCustomName())) {
						throw new AssertionError("不同名字的助手显示名应不同");
					}
				})
				.thenIdle(5)
				.thenExecute(() -> {
					// 4) 世界查找表生效后：应同时存在两个助手，且两个方块各绑定一个
					List<AiAssistantEntity> owned = ModEntities.findAssistantsFor(player);
					if (owned.size() != 2) {
						throw new AssertionError("应同时存在两个助手，实际 " + owned.size());
					}
					if (ModEntities.findAssistantBoundTo(level, absA) == null
							|| ModEntities.findAssistantBoundTo(level, absB) == null) {
						throw new AssertionError("两个方块应各有一个助手绑定");
					}
					// 5) 两个绑定方块都应亮起
					if (!helper.getLevel().getBlockState(helper.absolutePos(blockA))
							.getValue(AiLogoBlock.POWERED)
							|| !helper.getLevel().getBlockState(helper.absolutePos(blockB))
							.getValue(AiLogoBlock.POWERED)) {
						throw new AssertionError("两个绑定方块都应激活亮起");
					}
					// 6) “最近助手”路由 = A 助手（A 方块离玩家更近）
					AiAssistantEntity expectedNearest = ModEntities.findAssistantBoundTo(level, absA);
					if (ModEntities.findNearestAssistantFor(player) != expectedNearest) {
						throw new AssertionError("应路由到绑定更近方块的助手");
					}
				})
				.thenExecute(() -> {
					// 7) 送走最近的助手（A）→ 只剩 B；A 方块熄灭、B 方块仍亮
					if (!AiCompanionService.dismissFor(player)) {
						throw new AssertionError("dismissFor 失败");
					}
					List<AiAssistantEntity> left = ModEntities.findAssistantsFor(player);
					if (left.size() != 1 || !absB.equals(left.get(0).getConfigBlock())) {
						throw new AssertionError("送走最近助手后应只剩 B 助手");
					}
					if (helper.getLevel().getBlockState(helper.absolutePos(blockA))
							.getValue(AiLogoBlock.POWERED)) {
						throw new AssertionError("A 方块应熄灭");
					}
					if (!helper.getLevel().getBlockState(helper.absolutePos(blockB))
							.getValue(AiLogoBlock.POWERED)) {
						throw new AssertionError("B 方块应保持亮起");
					}
				})
				.thenExecute(() -> {
					// 8) 重新召唤 B（幂等返回原实例），破坏 B 方块 → B 助手消失
					AiAssistantEntity b2 = (AiAssistantEntity) AiCompanionService.summonLegacyEntityFor(player, absB);					if (b2 == null) {
						throw new AssertionError("重新召唤 B 失败");
					}
					helper.getLevel().destroyBlock(helper.absolutePos(blockB), false);
				})
				.thenIdle(10)
				.thenExecute(() -> {
					if (!ModEntities.findAssistantsFor(player).isEmpty()) {
						throw new AssertionError("B 方块被破坏后其助手应消失");
					}
					AiCompanionService.resetAllHistory(player);
					helper.succeed();
				});
	}

	/**
	 * 验证“助手必须绑定 AI 徽标方块”：
	 * 附近（48 格内）没有任何未绑定的 AI 徽标方块时，召唤应被拒绝（返回 null 且不创建实体）。
	 *
	 * 注意：gametest 的多个测试在同一个持久世界里并行运行，其它测试会放置 AI 徽标
	 * 方块；因此把 mock 玩家先传送到远离所有测试结构的坐标，保证 48 格内没有方块。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 120)
	public void summonRequiresConfigBlock(GameTestHelper helper) {
	helper.killAllEntitiesOfClass(AiAssistantEntity.class);
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
					if (AiCompanionService.summonLegacyEntityFor(player) != null) {
						throw new AssertionError("附近没有 AI 徽标方块时不应召唤助手");
					}
					if (!ModEntities.findAssistantsFor(player).isEmpty()) {
						throw new AssertionError("无方块时不应创建助手实体");
					}
					helper.succeed();
				});
	}

	/**
	 * 验证“无绑定助手一律消失”：
	 * 直接生成一个 configBlock == null 的助手（模拟刷怪蛋/旧存档遗留），
	 * 它应在约 40 tick 内被安全网自动清除。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 120)
	public void unboundAssistantDiscarded(GameTestHelper helper) {
	helper.killAllEntitiesOfClass(AiAssistantEntity.class);
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

		// 直接生成一个无绑定助手（不调用 summonFor，绕开绑定逻辑）
		AiAssistantEntity unbound = new AiAssistantEntity(ModEntities.AI_ASSISTANT,
				(ServerLevel) helper.getLevel());
		unbound.setPos(helper.absoluteVec(new net.minecraft.world.phys.Vec3(4.5, 2, 4.5)));
		((ServerLevel) helper.getLevel()).addFreshEntity(unbound);

		helper.startSequence()
				.thenIdle(55) // 安全网每 40 tick 检查一次，留出余量
				.thenExecute(() -> {
					if (!unbound.isRemoved()) {
						throw new AssertionError("无绑定助手应在约 40 tick 内被安全网清除");
					}
					helper.succeed();
				});
	}

	/**
	 * 验证 AI 徽标方块可获取性：
	 * 1. 有合成配方（opencraft:ai_logo_block 已注册到配方管理器）；
	 * 2. 徒手挖掘（空手）会掉落方块本身。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 120)
	public void aiLogoBlockMiningAndRecipe(GameTestHelper helper) {
	helper.killAllEntitiesOfClass(AiAssistantEntity.class);
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
	 * 1. ModEntities.findAssistantsBySelector 的选择器匹配（纯名字 / 显示名 / 紧凑 名字(坐标) / 未知 / 重名）；
	 * 2. /opencraft ask <消息>（不带名字）→ 路由到“最近”的助手（A 近 B 远），只有 A 的历史增长；
	 * 3. /opencraft ask 小红 <消息> → 精确指定 B，只有 B 的历史增长；
	 * 4. 用带坐标的显示名（引号括起）指定 → 同样命中 B；
	 * 5. 名字不存在 → 提示后回退到最近的助手（A 的历史增长）；
	 * 6. 两个助手同名 → 报“歧义”失败（指令返回 0），谁的历史都不增长。
	 *
	 * 历史按“助手绑定的方块”键控（一方块 = 一助手 = 一份记忆），ask() 会同步把
	 * user 消息写入目标助手的记忆，因此“路由到哪个助手”可以立即断言，无需等回复。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 500)
	public void askTargetsSpecificAssistant(GameTestHelper helper) {
	helper.killAllEntitiesOfClass(AiAssistantEntity.class);
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
					AiAssistantEntity a = (AiAssistantEntity) AiCompanionService.summonLegacyEntityFor(player, absA);					AiAssistantEntity b = (AiAssistantEntity) AiCompanionService.summonLegacyEntityFor(player, absB);					if (a == null || b == null || a == b) {
						throw new AssertionError("两个助手召唤失败");
					}
				})
				.thenIdle(5)
				.thenExecute(() -> {
					// 1) 选择器匹配
					if (ModEntities.findAssistantsBySelector(player, "小红").size() != 1) {
						throw new AssertionError("纯名字应匹配到 B");
					}
					if (ModEntities.findAssistantsBySelector(player, "小红 (" + bXyz + ")").size() != 1) {
						throw new AssertionError("显示名应匹配到 B");
					}
					if (ModEntities.findAssistantsBySelector(player, "小红(" + bXyz + ")").size() != 1) {
						throw new AssertionError("紧凑 名字(坐标) 应匹配到 B");
					}
					if (ModEntities.findAssistantsBySelector(player, "小智").size() != 1) {
						throw new AssertionError("小智 应匹配到 A");
					}
					if (!ModEntities.findAssistantsBySelector(player, "不存在的助手").isEmpty()) {
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
					AiCompanionService.dismissAllFor(player);
					AiCompanionService.resetAllHistory(player);
					helper.succeed();
				});
	}

	/**
	 * 验证“右键 AI 助手与之交互”的服务器端行为（跟随/待命模式已移除）：
	 * 1. 未绑定助手：右键 → 绑定主人；
	 * 2. 主人右键（普通或潜行）→ 打开互动界面（“打开互动界面”的 S2C 在 mock 连接上是空操作）；
	 * 3. 非主人右键 → 被拒绝（“只听主人的话”），状态不变；
	 * 4. resolveOwnedAssistant：正确实体 ID → 助手；他人 / 错误 ID → null（服务端不信任客户端）；
	 * 5. 互动界面“聊天”路径：resolve + ask(player, assistant, msg) → 消息写入该助手历史；
	 * 6. 互动界面“送走”：dismissAssistantEntity → 助手消失，重复调用幂等返回 false。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 300)
	public void assistantRightClickInteract(GameTestHelper helper) {
	helper.killAllEntitiesOfClass(AiAssistantEntity.class);
		dismissAllPlayerBots();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		// 平台 + 玩家 + 配置方块（指向 mock LLM）
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
		GlobalPos bindPos = GlobalPos.of(level.dimension(), helper.absolutePos(configBlockPos));

		helper.startSequence()
				.thenExecute(() -> {
					AiAssistantEntity assistant = (AiAssistantEntity) AiCompanionService.summonLegacyEntityFor(player, bindPos);					if (assistant == null) {
						throw new AssertionError("召唤助手失败");
					}
					// 1) 未绑定助手：新造一个无主助手，右键应绑定主人
					AiAssistantEntity unbound = new AiAssistantEntity(ModEntities.AI_ASSISTANT, level);
					unbound.setPos(helper.absoluteVec(new net.minecraft.world.phys.Vec3(4.5, 2, 4.5)));
					level.addFreshEntity(unbound);
					net.minecraft.world.InteractionResult bindResult =
							unbound.interact(player, net.minecraft.world.InteractionHand.MAIN_HAND);
					if (!bindResult.consumesAction()) {
						throw new AssertionError("右键应消费交互");
					}
					if (!player.getUUID().equals(unbound.getOwnerUuid())) {
						throw new AssertionError("未绑定助手被右键后应绑定到该玩家");
					}
					// 无主助手没有绑定方块，安全网会清掉它：这里直接送走，避免干扰后续步骤
					AiCompanionService.dismissAssistantEntity(unbound);
				})
				.thenIdle(5)
				.thenExecute(() -> {
					AiAssistantEntity assistant = ModEntities.findAssistantBoundTo(level, bindPos);
					if (assistant == null) {
						throw new AssertionError("找不到已召唤的助手");
					}
					// 2) 主人右键（普通或潜行都一样）→ 打开互动界面
					net.minecraft.world.InteractionResult normal =
							assistant.interact(player, net.minecraft.world.InteractionHand.MAIN_HAND);
					if (!normal.consumesAction()) {
						throw new AssertionError("主人右键应消费交互");
					}
					player.setShiftKeyDown(true);
					try {
						net.minecraft.world.InteractionResult sneak =
								assistant.interact(player, net.minecraft.world.InteractionHand.MAIN_HAND);
						if (!sneak.consumesAction()) {
							throw new AssertionError("潜行右键也应消费交互（打开互动界面）");
						}
					} finally {
						player.setShiftKeyDown(false);
					}
					// 3) 非主人右键 → 被拒绝，主人与状态都不变
					ServerPlayer other = helper.makeMockServerPlayerInLevel();
					other.teleportTo(playerPos.x, playerPos.y, playerPos.z);
					net.minecraft.world.InteractionResult stranger =
							assistant.interact(other, net.minecraft.world.InteractionHand.MAIN_HAND);
					if (!stranger.consumesAction()) {
						throw new AssertionError("非主人右键应消费交互（拒绝但消费）");
					}
					if (!player.getUUID().equals(assistant.getOwnerUuid())) {
						throw new AssertionError("非主人右键不应改变任何状态");
					}
					// 4) resolveOwnedAssistant：正确 ID → 助手；他人 → null；错误 ID → null
					if (AiCompanionService.resolveOwnedAssistant(player, assistant.getId()) != assistant) {
						throw new AssertionError("主人按实体 ID 应解析到自己的助手");
					}
					if (AiCompanionService.resolveOwnedAssistant(other, assistant.getId()) != null) {
						throw new AssertionError("他人按实体 ID 不应解析到我的助手");
					}
					if (AiCompanionService.resolveOwnedAssistant(player, 999999) != null) {
						throw new AssertionError("不存在的实体 ID 应返回 null");
					}
					// 5) 互动界面“聊天”路径（服务器接收器做的正是 resolve + askGui：
					//    GUI 模式把回复回传互动界面，同时照常写入该助手的历史）
					int before = AiCompanionService.historySize(bindPos);
					AiAssistantEntity resolved =
							AiCompanionService.resolveOwnedAssistant(player, assistant.getId());
					AiCompanionService.askGui(player, resolved, "右键互动测试消息",
							bindPos.pos(), bindPos.dimension());
					if (AiCompanionService.historySize(bindPos) != before + 1) {
						throw new AssertionError("互动界面聊天应把消息写入该助手的历史");
					}
				})
				.thenWaitUntil(() -> {
					// 等流式回复写入历史（独立线程，按墙钟时间到达）
					if (AiCompanionService.historySize(bindPos) < 2) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal("等待回复写入历史…"),
								(int) helper.getTick());
					}
				})
				.thenExecute(() -> {
					AiAssistantEntity assistant = ModEntities.findAssistantBoundTo(level, bindPos);
					if (assistant == null) {
						throw new AssertionError("助手不应消失");
					}
					// 7) 互动界面“送走”：dismissAssistantEntity → 助手消失，重复调用幂等
					if (!AiCompanionService.dismissAssistantEntity(assistant)) {
						throw new AssertionError("送走指定助手应成功");
					}
					if (!ModEntities.findAssistantsFor(player).isEmpty()) {
						throw new AssertionError("送走后不应再有任何助手");
					}
					if (AiCompanionService.dismissAssistantEntity(assistant)) {
						throw new AssertionError("重复送走应幂等返回 false");
					}
					AiCompanionService.resetAllHistory(player);
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
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 400)
	public void configScreenChatWindow(GameTestHelper helper) {
	helper.killAllEntitiesOfClass(AiAssistantEntity.class);
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
	 * 验证“助手像普通生存玩家一样拥有背包与装备”：
	 * 1. 背包为 36 格（27 普通 + 9 快捷栏，与玩家一致）；
	 * 2. 自动拾取地上的物品进自己的背包（拾取后掉落物实体消失）；
	 * 3. equip 工具把护甲穿到对应装备栏（胸甲 → CHEST），护甲值如实生效（≥5）；
	 * 4. equip 工具把剑拿在主手（非护甲 → MAINHAND）；
	 * 5. 挖掘前 autoSelectMiningTool 自动把背包里最快的镐换到主手；
	 * 6. 挖掘掉落物进**助手自己的背包**（而不是主人背包），主人背包不增长；
	 * 7. hand_to_player 把背包物品递给主人（主人背包增加）。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 400)
	public void assistantPlayerLikeInventory(GameTestHelper helper) {
	helper.killAllEntitiesOfClass(AiAssistantEntity.class);
		dismissAllPlayerBots();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();

		// 平台 + 玩家 + 配置方块
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

		helper.startSequence()
				.thenExecute(() -> {
					// 1) 召唤助手 → 背包必须是 36 格（与玩家一致）
					AiAssistantEntity assistant = (AiAssistantEntity) AiCompanionService.summonLegacyEntityFor(player);					if (assistant == null) {
						throw new AssertionError("召唤助手失败");
					}
					if (assistant.getInventory().getContainerSize() != 36) {
						throw new AssertionError("助手背包应为 36 格，实际 "
								+ assistant.getInventory().getContainerSize());
					}
					// 把助手放到平台正中间（summonFor 的向上安全扫描可能落在周边地形上，
					// 测试需要确定性的站位）；随后把玩家挪到高空（让玩家不抢掉落物也不干扰助手位置）
					net.minecraft.world.phys.Vec3 standPos = helper.absoluteVec(
							new net.minecraft.world.phys.Vec3(4.5, 2, 4.5));
					assistant.teleportTo(standPos.x, standPos.y, standPos.z);
					assistant.getNavigation().stop();
					BlockPos highBase = helper.absolutePos(new BlockPos(4, 160, 4));
					for (int dx = -3; dx <= 3; dx++) {
						for (int dz = -3; dz <= 3; dz++) {
							level.setBlock(highBase.offset(dx, -1, dz),
									Blocks.STONE.defaultBlockState(), 3);
						}
					}
					player.teleportTo(highBase.getX() + 0.5, highBase.getY(),
							highBase.getZ() + 0.5);
				})
				.thenIdle(5)
				.thenExecute(() -> {
					AiAssistantEntity assistant = ModEntities.findNearestAssistantFor(player);
					if (assistant == null) {
						throw new AssertionError("助手不存在");
					}
					// 2) 自动拾取：在助手脚下生成一个钻石掉落物（无拾取保护期）
					ItemEntity item = new ItemEntity(level,
							assistant.getX(), assistant.getY() + 0.8, assistant.getZ(),
							new ItemStack(Items.DIAMOND, 1));
					item.setNoPickUpDelay();
					level.addFreshEntity(item);
				})
				.thenIdle(40)
				.thenExecute(() -> {
					AiAssistantEntity assistant = ModEntities.findNearestAssistantFor(player);
					if (assistant == null) {
						throw new AssertionError("助手不存在");
					}
					if (assistant.countOf(Items.DIAMOND.builtInRegistryHolder()) < 1) {
						throw new AssertionError("助手应自动拾取地上的钻石进背包，"
								+ "实际背包钻石数 " + assistant.countOf(Items.DIAMOND.builtInRegistryHolder()));
					}
					// 2.5) 背包内容必须实时注入 system 上下文（模型不调工具也能看到背包）
					String ctxFrag = new InventoryPlugin().gameContextFragment(
							new ToolContext(server, assistant, player, level));
					if (!ctxFrag.contains("diamond") || !ctxFrag.contains("【我的背包】")) {
						throw new AssertionError("游戏上下文应包含助手背包内容（模型可见），实际: " + ctxFrag);
					}
					// 3) equip 护甲：铁胸甲应穿到胸甲栏，护甲值生效
					assistant.giveToInventory(new ItemStack(Items.IRON_CHESTPLATE));
					ToolDefinition equipDef = new InventoryPlugin().tools().stream()
							.filter(t -> t.name().equals("equip")).findFirst().orElseThrow();
					JsonObject args = new JsonObject();
					args.addProperty("item", "minecraft:iron_chestplate");
					ToolResult res = equipDef.executor().execute(
							new ToolContext(server, assistant, player, level), args);
					if (!res.ok()) {
						throw new AssertionError("equip 铁胸甲失败: " + res.message());
					}
					if (!assistant.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE)) {
						throw new AssertionError("铁胸甲应穿到胸甲栏，实际 "
								+ assistant.getItemBySlot(EquipmentSlot.CHEST));
					}
					// 4) equip 剑 → 主手
					assistant.giveToInventory(new ItemStack(Items.IRON_SWORD));
					JsonObject swordArgs = new JsonObject();
					swordArgs.addProperty("item", "minecraft:iron_sword");
					ToolResult swordRes = equipDef.executor().execute(
							new ToolContext(server, assistant, player, level), swordArgs);
					if (!swordRes.ok()) {
						throw new AssertionError("equip 铁剑失败: " + swordRes.message());
					}
					if (!assistant.getMainHandItem().is(Items.IRON_SWORD)) {
						throw new AssertionError("铁剑应拿在主手，实际 "
								+ assistant.getMainHandItem());
					}
					// 4.5) 装备也必须实时出现在上下文里（模型知道助手穿着什么）
					String ctxFrag2 = new InventoryPlugin().gameContextFragment(
							new ToolContext(server, assistant, player, level));
					if (!ctxFrag2.contains("iron_chestplate") || !ctxFrag2.contains("胸甲")
							|| !ctxFrag2.contains("iron_sword")) {
						throw new AssertionError("游戏上下文应包含助手装备，实际: " + ctxFrag2);
					}
				})
				.thenIdle(5)
				.thenExecute(() -> {
					AiAssistantEntity assistant = ModEntities.findNearestAssistantFor(player);
					if (assistant == null) {
						throw new AssertionError("助手不存在");
					}
					// 护甲值应如实反映胸甲（vanilla 属性检测每 tick 自动生效）
					if (assistant.getArmorValue() < 5) {
						throw new AssertionError("穿铁胸甲后护甲值应 ≥5，实际 "
								+ assistant.getArmorValue());
					}
					// 5) 挖掘：给一把木镐（背包里），放置石头，下达挖掘任务
					assistant.giveToInventory(new ItemStack(Items.WOODEN_PICKAXE));
					BlockPos stonePos = new BlockPos(5, 1, 4); // 结构相对坐标
					helper.setBlock(stonePos, Blocks.STONE.defaultBlockState());
					BlockPos absStone = helper.absolutePos(stonePos);
					assistant.setCurrentTask(new MineBlockTask(assistant, level, absStone));
				})
				.thenIdle(10)
				.thenExecute(() -> {
					AiAssistantEntity assistant = ModEntities.findNearestAssistantFor(player);
					if (assistant == null) {
						throw new AssertionError("助手不存在");
					}
					// 挖掘开始时自动把木镐换到主手（autoSelectMiningTool）
					if (!assistant.getMainHandItem().is(Items.WOODEN_PICKAXE)) {
						throw new AssertionError("挖掘前应自动换木镐到主手，实际 "
								+ assistant.getMainHandItem());
					}
				})
				.thenWaitUntil(() -> {
					// 6) 轮询：石头被挖掉且圆石通过“挖掘掉落 → 掉落物 → 自动拾取”进助手背包
					AiAssistantEntity assistant = ModEntities.findNearestAssistantFor(player);
					if (assistant == null) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal("助手不存在"),
								(int) helper.getTick());
					}
					boolean mined = helper.getLevel().getBlockState(
							helper.absolutePos(new BlockPos(5, 1, 4))).is(Blocks.AIR);
					if (!mined || assistant.countOf(Items.COBBLESTONE.builtInRegistryHolder()) < 1) {
						throw new net.minecraft.gametest.framework.GameTestAssertException(
								net.minecraft.network.chat.Component.literal("等待挖掘完成且圆石进助手背包…"),
								(int) helper.getTick());
					}
				})
				.thenExecute(() -> {
					AiAssistantEntity assistant = ModEntities.findNearestAssistantFor(player);
					if (assistant == null) {
						throw new AssertionError("助手不存在");
					}
					// 挖掘掉落物应进助手背包而非主人背包
					if (player.getInventory().countItem(Items.COBBLESTONE) > 0) {
						throw new AssertionError("挖掘掉落物不应进入主人背包（应先进助手背包）");
					}
					// 7) hand_to_player 把圆石递给主人
					ToolDefinition handDef = new InventoryPlugin().tools().stream()
							.filter(t -> t.name().equals("hand_to_player")).findFirst().orElseThrow();
					JsonObject handArgs = new JsonObject();
					handArgs.addProperty("item", "minecraft:cobblestone");
					handArgs.addProperty("amount", 1);
					ToolResult handRes = handDef.executor().execute(
							new ToolContext(server, assistant, player, level), handArgs);
					if (!handRes.ok()) {
						throw new AssertionError("hand_to_player 失败: " + handRes.message());
					}
					if (player.getInventory().countItem(Items.COBBLESTONE) < 1) {
						throw new AssertionError("主人应收到助手递来的圆石");
					}
					// 清理
					AiCompanionService.dismissAllFor(player);
					AiCompanionService.resetAllHistory(player);
					helper.succeed();
				});
	}

	/**
	 * 验证“craft 按玩家规则合成”：
	 * 1. 3×3 配方（钻石块）在【没有工作台】时被拒绝，报“需要工作台”且不扣材料；
	 * 2. 助手旁边放置工作台后，9 个钻石（背包第 20 格）→ 合成钻石块成功、钻石扣光；
	 * 3. 18 个钻石 + amount=2 → 合成 2 个钻石块（按套数扣料）；
	 * 4. 2×2 及更小的配方（木棍 1×2）不需要工作台也能合成（材料在第 30 格）；
	 * 5. 材料不足时返回明确错误且不扣料。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 200)
	public void craftUsesWholeBackpack(GameTestHelper helper) {
	helper.killAllEntitiesOfClass(AiAssistantEntity.class);
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

		helper.startSequence()
				.thenExecute(() -> {
					AiAssistantEntity assistant = (AiAssistantEntity) AiCompanionService.summonLegacyEntityFor(player);					if (assistant == null) {
						throw new AssertionError("召唤助手失败");
					}
					// 把助手放到平台中间（summonFor 可能落在周边地形上）
					net.minecraft.world.phys.Vec3 standPos = helper.absoluteVec(
							new net.minecraft.world.phys.Vec3(4.5, 2, 4.5));
					assistant.teleportTo(standPos.x, standPos.y, standPos.z);
					ToolDefinition craftDef = new CraftingPlugin().tools().stream()
							.filter(t -> t.name().equals("craft")).findFirst().orElseThrow();
					// 1) 3×3 配方（钻石块）没有工作台 → 拒绝并提示需要工作台，不扣材料
					assistant.getInventory().setItem(20, new ItemStack(Items.DIAMOND, 9));
					JsonObject args = new JsonObject();
					args.addProperty("item", "minecraft:diamond_block");
					args.addProperty("amount", 1);
					ToolResult res = craftDef.executor().execute(
							new ToolContext(server, assistant, player, level), args);
					if (res.ok()) {
						throw new AssertionError("没有工作台时 3×3 配方（钻石块）不应合成成功");
					}
					if (!res.message().contains("工作台")) {
						throw new AssertionError("应提示需要工作台，实际: " + res.message());
					}
					if (assistant.countOf(Items.DIAMOND.builtInRegistryHolder()) != 9) {
						throw new AssertionError("没有工作台时不应扣材料，实际钻石 "
								+ assistant.countOf(Items.DIAMOND.builtInRegistryHolder()));
					}
					// 2) 助手旁边放一个工作台 → 9 个钻石（第 20 格）→ 合成钻石块成功
					helper.setBlock(new BlockPos(5, 1, 4), Blocks.CRAFTING_TABLE.defaultBlockState());
					ToolResult res2 = craftDef.executor().execute(
							new ToolContext(server, assistant, player, level), args);
					if (!res2.ok()) {
						throw new AssertionError("有工作台时 craft 钻石块失败: " + res2.message());
					}
					if (assistant.countOf(Items.DIAMOND_BLOCK.builtInRegistryHolder()) < 1) {
						throw new AssertionError("背包应有 1 个钻石块");
					}
					if (assistant.countOf(Items.DIAMOND.builtInRegistryHolder()) != 0) {
						throw new AssertionError("9 个钻石应被扣光，实际 "
								+ assistant.countOf(Items.DIAMOND.builtInRegistryHolder()));
					}
					// 3) 18 个钻石 + amount=2 → 2 个钻石块（按套数扣料）
					assistant.getInventory().setItem(5, new ItemStack(Items.DIAMOND, 18));
					JsonObject args3 = new JsonObject();
					args3.addProperty("item", "minecraft:diamond_block");
					args3.addProperty("amount", 2);
					ToolResult res3 = craftDef.executor().execute(
							new ToolContext(server, assistant, player, level), args3);
					if (!res3.ok()) {
						throw new AssertionError("craft 2 套钻石块失败: " + res3.message());
					}
					if (assistant.countOf(Items.DIAMOND_BLOCK.builtInRegistryHolder()) < 3) {
						throw new AssertionError("背包应有 3 个钻石块（1+2），实际 "
								+ assistant.countOf(Items.DIAMOND_BLOCK.builtInRegistryHolder()));
					}
					if (assistant.countOf(Items.DIAMOND.builtInRegistryHolder()) != 0) {
						throw new AssertionError("18 个钻石应被扣光，实际 "
								+ assistant.countOf(Items.DIAMOND.builtInRegistryHolder()));
					}
					// 4) 2×2 及更小的配方（木棍 1×2）不依赖工作台也能合成（材料在第 30 格）
					//    先拆掉工作台，验证“不需要工作台”的规则独立成立
					helper.setBlock(new BlockPos(5, 1, 4), Blocks.AIR.defaultBlockState());
					assistant.getInventory().setItem(30, new ItemStack(Items.OAK_PLANKS, 2));
					JsonObject args4 = new JsonObject();
					args4.addProperty("item", "minecraft:stick");
					ToolResult res4 = craftDef.executor().execute(
							new ToolContext(server, assistant, player, level), args4);
					if (!res4.ok()) {
						throw new AssertionError("craft 木棍失败（1×2 配方不需要工作台）: " + res4.message());
					}
					if (assistant.countOf(Items.STICK.builtInRegistryHolder()) < 4) {
						throw new AssertionError("2 个木板应合成 4 根木棍，实际 "
								+ assistant.countOf(Items.STICK.builtInRegistryHolder()));
					}
					// 5) 材料不足：只有一个钻石 → 应返回错误且不扣料（钻石还在）
					int diamondBefore = assistant.countOf(Items.DIAMOND.builtInRegistryHolder());
					assistant.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 1));
					JsonObject args5 = new JsonObject();
					args5.addProperty("item", "minecraft:diamond_block");
					ToolResult res5 = craftDef.executor().execute(
							new ToolContext(server, assistant, player, level), args5);
					if (res5.ok()) {
						throw new AssertionError("材料不足时 craft 应返回错误");
					}
					if (assistant.countOf(Items.DIAMOND.builtInRegistryHolder()) != diamondBefore + 1) {
						throw new AssertionError("合成失败不应扣材料");
					}
					// 清理
					AiCompanionService.dismissAllFor(player);
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
		java.nio.file.Path logFile = com.swaydy.opencraft.debug.DebugLog.logFilePath();
		boolean wasEnabled = com.swaydy.opencraft.debug.DebugLog.isEnabled();
		// 1) 第一次开启：写入“旧会话”内容后关闭（文件里保留旧内容）
		com.swaydy.opencraft.debug.DebugLog.enable();
		if (!com.swaydy.opencraft.debug.DebugLog.isEnabled()) {
			throw new AssertionError("enable 后调试模式应处于开启状态");
		}
		com.swaydy.opencraft.debug.DebugLog.log("old", "旧会话内容");
		com.swaydy.opencraft.debug.DebugLog.disable();
		if (com.swaydy.opencraft.debug.DebugLog.isEnabled()) {
			throw new AssertionError("disable 后调试模式应处于关闭状态");
		}
		// 2) 重新开启：覆盖式——旧日志被清空，只保留本次会话的新内容
		com.swaydy.opencraft.debug.DebugLog.enable();
		com.swaydy.opencraft.debug.DebugLog.log("test", "调试日志测试 {}", 42);
		com.swaydy.opencraft.debug.DebugLog.log("chat", "模拟对话记录 abc");
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
		com.swaydy.opencraft.debug.DebugLog.disable();
		com.swaydy.opencraft.debug.DebugLog.log("test", "不应写入");
		try {
			String after = java.nio.file.Files.readString(logFile);
			if (after.contains("不应写入")) {
				throw new AssertionError("关闭后不应再写入调试日志");
			}
		} catch (java.io.IOException e) {
			throw new AssertionError("调试日志文件不可读: " + e);
		}
		if (!wasEnabled) {
			com.swaydy.opencraft.debug.DebugLog.disable();
		}
		helper.succeed();
	}

	/**
	 * 验证“AI 助手 = 真正的 ServerPlayer（bot）” —— 本次目标的核心交付：
	 * AI 助手像多人联机客户端一样进服，拥有普通玩家的全部内容（可以不用但不能没有）。
	 * 身体形态与 Agent 预设解耦：无论选哪个预设，召唤出的都是玩家形态：
	 * 1. summonFor 召唤出的就是 AiAssistantPlayer，它真实地进入了 PlayerList，
	 *    拥有真正的玩家背包（43 槽：36 主背包 + 7 装备槽）；
	 * 2. 右键交互（绑主/开互动界面/非主人拒绝）与实体形态一致；
	 * 3. 玩家式动作：player_place 用真实 ServerPlayerGameMode.useItemOn 放置方块；
	 * 4. 玩家式挖掘：player_mine 走到方块旁用 destroyBlock 破坏，掉落物自动拾进背包；
	 * 5. 送走后从 PlayerList 移除、绑定方块熄灭。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 400)
	public void assistantPlayerFormLifecycle(GameTestHelper helper) {
	helper.killAllEntitiesOfClass(AiAssistantEntity.class);
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
					com.swaydy.opencraft.agent.ToolDefinition placeTool =
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
					com.swaydy.opencraft.agent.ToolDefinition mineTool =
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
	 * 验证玩家形态助手的 bot 式移动物理（PlayerMovementController）：
	 * 1. 移动时会朝向目标方向（yRot 平滑转向，不再侧滑）；
	 * 2. 走出平台边缘后受重力下坠（不依赖可能陈旧的 onGround 标志，不会浮空）；
	 * 3. 落到下方接住平台后不再继续下坠（Y 稳定）。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 300)
	public void playerMovementPhysics(GameTestHelper helper) {
		helper.killAllEntitiesOfClass(AiAssistantEntity.class);
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
		helper.killAllEntitiesOfClass(AiAssistantEntity.class);
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
					com.swaydy.opencraft.agent.ToolDefinition handTool =
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
}
