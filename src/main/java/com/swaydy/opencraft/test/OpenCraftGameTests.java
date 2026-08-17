package com.swaydy.opencraft.test;

import com.swaydy.opencraft.ai.AiBlockConfig;
import com.swaydy.opencraft.ai.AiCompanionService;
import com.swaydy.opencraft.ai.AiConfigData;
import com.swaydy.opencraft.ai.AiConfigHandler;
import com.swaydy.opencraft.block.AiLogoBlock;
import com.swaydy.opencraft.block.AiLogoBlockEntity;
import com.swaydy.opencraft.block.ModBlocks;
import com.swaydy.opencraft.entity.AiAssistantEntity;
import com.swaydy.opencraft.entity.ModEntities;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
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
 * 10. assistantRightClickInteract —— 右键助手互动（绑定/普通右键开界面/潜行切换跟随/非主人拒绝/聊天/送走）。
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

	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 400)
	public void assistantLifecycleAndChat(GameTestHelper helper) {
		// 清掉之前测试遗留在世界里的助手（测试世界在 run/world 中持久化）
		helper.killAllEntitiesOfClass(AiAssistantEntity.class);

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
					AiAssistantEntity assistant = AiCompanionService.summonFor(player);
					if (assistant == null) {
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
					// 7) 跨维度跟随：把玩家传送到地狱，助手应在 ~40 tick 内跟过来
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
					// 8) 助手应该已经跟到地狱维度
					AiAssistantEntity assistant = ModEntities.findNearestAssistantFor(player);
					if (assistant == null) {
						throw new AssertionError("跨维度后助手实体不存在");
					}
					if (assistant.level() != player.level()) {
						throw new AssertionError("助手没有跟随到地狱维度（助手在 "
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
					AiAssistantEntity assistant = AiCompanionService.summonFor(player);
					if (assistant == null || assistant.getConfigBlock() == null
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
							"in-game-edited-model", sent.systemPrompt(),
							sent.temperature(), sent.maxHistoryMessages(), sent.timeoutSeconds(),
							sent.language(),
							sent.followDistance(), sent.stopDistance(),
							sent.teleportDistance(), sent.maxDistance(), sent.speed(),
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
							"model-keep-key", sent.systemPrompt(),
							sent.temperature(), sent.maxHistoryMessages(), sent.timeoutSeconds(),
							sent.language(),
							sent.followDistance(), sent.stopDistance(),
							sent.teleportDistance(), sent.maxDistance(), sent.speed(),
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
					if (!ModEntities.findAssistantsFor(player).isEmpty()) {
						throw new AssertionError("无助手绑定时送走不应影响任何助手");
					}
					// 2) 召唤（合并按钮的“召唤”半）→ 助手绑定该方块（下一 tick 才进入查找表）
					AiConfigHandler.summonWithBlock(player, absPos, dimension);
				})
				.thenIdle(5)
				.thenExecute(() -> {
					if (ModEntities.findAssistantBoundTo(level, bindPos) == null) {
						throw new AssertionError("用方块召唤后助手应绑定到该方块");
					}
					if (!helper.getLevel().getBlockState(absPos).getValue(AiLogoBlock.POWERED)) {
						throw new AssertionError("召唤后绑定方块应亮起");
					}
					// 3) 别人尝试送走 → 被拒绝，助手仍在（合并按钮对他人显示为禁用态）
					ServerPlayer other = helper.makeMockServerPlayerInLevel();
					other.teleportTo(playerPos.x, playerPos.y, playerPos.z);
					AiConfigHandler.dismissWithBlock(other, absPos, dimension);
					if (ModEntities.findAssistantBoundTo(level, bindPos) == null) {
						throw new AssertionError("别人的助手不应被非主人送走");
					}
					// 4) 主人送走（合并按钮的“不召唤”半）→ 助手消失、方块熄灭
					AiConfigHandler.dismissWithBlock(player, absPos, dimension);
					if (!ModEntities.findAssistantsFor(player).isEmpty()) {
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
					if (ModEntities.findAssistantBoundTo(level, bindPos) == null) {
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
					AiAssistantEntity assistant = AiCompanionService.summonFor(player);
					if (assistant == null) {
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
					AiAssistantEntity a1 = AiCompanionService.summonFor(player, absA);
					AiAssistantEntity b1 = AiCompanionService.summonFor(player, absB);
					if (a1 == null || b1 == null) {
						throw new AssertionError("多助手召唤失败");
					}
					if (a1 == b1) {
						throw new AssertionError("不同方块应产生不同助手实例");
					}
					if (!absA.equals(a1.getConfigBlock()) || !absB.equals(b1.getConfigBlock())) {
						throw new AssertionError("助手未绑定各自方块");
					}
					// 2) 幂等：同一 tick 内再次用 A 召唤返回同一实例（RECENT_SUMMONS 缓存）
					if (AiCompanionService.summonFor(player, absA) != a1) {
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
					AiAssistantEntity b2 = AiCompanionService.summonFor(player, absB);
					if (b2 == null) {
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
					if (AiCompanionService.summonFor(player) != null) {
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
					AiAssistantEntity a = AiCompanionService.summonFor(player, absA);
					AiAssistantEntity b = AiCompanionService.summonFor(player, absB);
					if (a == null || b == null || a == b) {
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
	 * 验证“右键 AI 助手与之交互”的服务器端行为：
	 * 1. 未绑定助手：右键 → 绑定主人；
	 * 2. 主人普通右键 → 不改变跟随状态（“打开互动界面”的 S2C 在 mock 连接上是空操作）；
	 * 3. 主人潜行右键 → 快速切换跟随/待命；
	 * 4. 非主人右键 → 被拒绝（“只听主人的话”），状态不变；
	 * 5. resolveOwnedAssistant：正确实体 ID → 助手；他人 / 错误 ID → null（服务端不信任客户端）；
	 * 6. 互动界面“聊天”路径：resolve + ask(player, assistant, msg) → 消息写入该助手历史；
	 * 7. 互动界面“送走”：dismissAssistantEntity → 助手消失，重复调用幂等返回 false。
	 */
	@GameTest(structure = "fabric-gametest-api-v1:empty", maxTicks = 300)
	public void assistantRightClickInteract(GameTestHelper helper) {
		helper.killAllEntitiesOfClass(AiAssistantEntity.class);
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
					AiAssistantEntity assistant = AiCompanionService.summonFor(player, bindPos);
					if (assistant == null) {
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
					boolean initiallyFollowing = assistant.isFollowing();
					// 2) 主人普通右键（不潜行）→ 打开互动界面，不改变跟随状态
					net.minecraft.world.InteractionResult normal =
							assistant.interact(player, net.minecraft.world.InteractionHand.MAIN_HAND);
					if (!normal.consumesAction()) {
						throw new AssertionError("主人普通右键应消费交互");
					}
					if (assistant.isFollowing() != initiallyFollowing) {
						throw new AssertionError("普通右键不应改变跟随状态");
					}
					// 3) 主人潜行右键 → 切换跟随/待命
					player.setShiftKeyDown(true);
					try {
						net.minecraft.world.InteractionResult sneak =
								assistant.interact(player, net.minecraft.world.InteractionHand.MAIN_HAND);
						if (!sneak.consumesAction()) {
							throw new AssertionError("潜行右键应消费交互");
						}
						if (assistant.isFollowing() == initiallyFollowing) {
							throw new AssertionError("潜行右键应切换跟随状态");
						}
					} finally {
						player.setShiftKeyDown(false);
					}
					// 4) 非主人右键 → 被拒绝，跟随状态与主人都不变
					ServerPlayer other = helper.makeMockServerPlayerInLevel();
					other.teleportTo(playerPos.x, playerPos.y, playerPos.z);
					boolean followBefore = assistant.isFollowing();
					net.minecraft.world.InteractionResult stranger =
							assistant.interact(other, net.minecraft.world.InteractionHand.MAIN_HAND);
					if (!stranger.consumesAction()) {
						throw new AssertionError("非主人右键应消费交互（拒绝但消费）");
					}
					if (assistant.isFollowing() != followBefore
							|| !player.getUUID().equals(assistant.getOwnerUuid())) {
						throw new AssertionError("非主人右键不应改变任何状态");
					}
					// 5) resolveOwnedAssistant：正确 ID → 助手；他人 → null；错误 ID → null
					if (AiCompanionService.resolveOwnedAssistant(player, assistant.getId()) != assistant) {
						throw new AssertionError("主人按实体 ID 应解析到自己的助手");
					}
					if (AiCompanionService.resolveOwnedAssistant(other, assistant.getId()) != null) {
						throw new AssertionError("他人按实体 ID 不应解析到我的助手");
					}
					if (AiCompanionService.resolveOwnedAssistant(player, 999999) != null) {
						throw new AssertionError("不存在的实体 ID 应返回 null");
					}
					// 6) 互动界面“聊天”路径（服务器接收器做的正是 resolve + askGui：
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
					// 1) 未绑定助手：chatWithBlock 自动召唤并绑定本方块，user 消息立即入史
					AiConfigHandler.chatWithBlock(player, absPos, dimension, "你好，介绍一下你自己");
					if (ModEntities.findAssistantBoundTo(level, bindPos) == null) {
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
					AiAssistantEntity bound = ModEntities.findAssistantBoundTo(level, bindPos);
					int size = AiCompanionService.historySize(bindPos);
					AiConfigHandler.chatWithBlock(player, absPos, dimension, "第二条消息");
					if (ModEntities.findAssistantBoundTo(level, bindPos) != bound) {
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
					if (ModEntities.findAssistantBoundTo(level, bindPos) == null) {
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
					AiCompanionService.dismissAllFor(player);
					AiCompanionService.resetAllHistory(player);
					helper.succeed();
				});
	}
}
