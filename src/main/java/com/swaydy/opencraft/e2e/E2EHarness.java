package com.swaydy.opencraft.e2e;

import com.mojang.authlib.GameProfile;
import com.swaydy.opencraft.OpenCraftMod;
import com.swaydy.opencraft.ai.AiBlockConfig;
import com.swaydy.opencraft.ai.AiCompanionService;
import com.swaydy.opencraft.assistant.AssistantFacade;
import com.swaydy.opencraft.assistant.player.AiAssistantPlayer;
import com.swaydy.opencraft.block.AiLogoBlockEntity;
import com.swaydy.opencraft.block.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 自然世界端到端测试编排器：在固定种子的新生成真实存档里，用真实 LLM + general_agent
 * 驱动玩家形态助手从真实新玩家状态开始游玩，并按真实背包结果验收。
 *
 * <p>没有人工测试区：不铺平台、不清空地形、不种树、不埋矿、不预放容器。
 * 合成主人通过原版 PlayerList.placeNewPlayer 自然进入世界，助手出生在主人旁边，
 * 只在自然地面上临时放置一个 AI 配置方块（任务结束恢复原状态）。</p>
 */
public final class E2EHarness {
	/** 结果文件（相对服务器工作目录 run/）。 */
	private static final String RESULTS_FILE = "logs/e2e-results.txt";
	/** 当前套件的详细日志文件（含工具序列/验证细节等；每次套件独立文件）。 */
	private static final String E2E_LOG_FILE = "logs/e2e-%s.log";
	/** 当前套件详细日志文件路径（套件开始时设置）。 */
	private static volatile String suiteLogPath = "";
	/** 当前任务的详细记录（工具事件/周期状态），任务结束写入套件日志文件。 */
	private static volatile StringBuilder currentTaskLog = new StringBuilder();
	/** 当前任务的 AI 配置方块原自然状态（服务端线程写入/清理）。 */
	private static final java.util.Map<GlobalPos, BlockState> ORIGINAL_CONFIG_STATES = new java.util.HashMap<>();

	private E2EHarness() {
	}

	// ------------------------------------------------------------------
	// 入口：命令 / autorun
	// ------------------------------------------------------------------

	/** 注册 autorun 钩子（{@code -Dopencraft.e2e.autorun=<id>}，服务器启动后自动跑并退出）。 */
	public static void registerAutoRunHook() {
		registerShotClientGlue();
		com.swaydy.opencraft.agent.AgentRuntime.addToolListener((toolName, result) -> {
			StringBuilder sb = currentTaskLog;
			if (sb != null) {
				synchronized (sb) {
					sb.append("  [工具] ").append(toolName)
							.append(result == null ? " → ?" : (result.ok() ? " → 成功" : " → 失败"))
							.append(result == null || result.message() == null ? ""
									: "  " + result.message())
							.append('\n');
				}
			}
		});
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			String autorun = System.getProperty("opencraft.e2e.autorun");
			if (autorun == null || autorun.isBlank()) {
				return;
			}
			com.swaydy.opencraft.e2e.E2ERegistry.init();
			server.execute(() -> {
				ServerLevel level = server.overworld();
				if (Boolean.getBoolean("opencraft.e2e.probe")) {
					if (level != null) {
						probeWorld(level);
					}
					shutdown(server).run();
					return;
				}
				if (level == null) {
					log("[E2E] 自动运行失败：主世界不可用");
					shutdown(server).run();
					return;
				}
				if ("all".equalsIgnoreCase(autorun.trim())) {
					log("[E2E] 拒绝 autorun=all：自然 e2e 每个任务必须使用独立新生成世界；请运行 bash bin/run_e2e_all.sh");
					shutdown(server).run();
					return;
				}
				com.swaydy.opencraft.e2e.E2ETask task = com.swaydy.opencraft.e2e.E2ERegistry.byId(autorun.trim());
				if (task == null) {
					log("[E2E] 未知任务: " + autorun + "（可用: " + taskIds() + "）");
					shutdown(server).run();
					return;
				}
				long holdMs = 0;
				String holdProp = System.getProperty("opencraft.e2e.holdMs");
				if (holdProp != null && !holdProp.isBlank()) {
					try {
						holdMs = Long.parseLong(holdProp.trim());
					} catch (NumberFormatException ignored) {
					}
				}
				final long hold = holdMs;
				Runnable onDone = () -> new Thread(() -> {
					if (hold > 0) {
						log("[E2E] 任务结束，保持服务器运行 " + (hold / 1000) + "s 供截图客户端连入…");
						try {
							Thread.sleep(hold);
						} catch (InterruptedException e) {
							return;
						}
					}
					shotTarget = null;
					shutdown(server).run();
				}, "E2E-hold").start();
				runTasks(level, List.of(task), onDone);
			});
		});
	}

	/** 当前 e2e 任务的助手（截图客户端要粘到它的眼睛上）；任务结束/hold 时清除。 */
	private static volatile AiAssistantPlayer shotTarget;

	/** 真客户端 glue：任务进行中把连接进来的真客户端 TP 到助手眼睛位置并同步朝向。 */
	private static void registerShotClientGlue() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
			AiAssistantPlayer target = shotTarget;
			if (target == null || target.isRemoved()) {
				return;
			}
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (p == target || p.isRemoved()) {
					continue;
				}
				String name = p.getName().getString();
				if (name == null || name.startsWith("E2E_")) {
					continue;
				}
				p.teleportTo(target.level(),
						target.getX(), target.getY() + 1.62, target.getZ(),
						java.util.Set.of(), target.getYRot(), target.getXRot(), false);
			}
		});
	}

	/** 运行一个自然世界任务（标准语义：一次只能一个任务，保证一个任务一个新世界）。 */
	public static void runTasks(ServerLevel level, List<com.swaydy.opencraft.e2e.E2ETask> tasks,
	                            Runnable onAllDone) {
		if (tasks == null || tasks.isEmpty()) {
			log("[E2E] 自然 e2e 没有任务可运行");
			if (onAllDone != null) {
				onAllDone.run();
			}
			return;
		}
		if (tasks.size() != 1) {
			log("[E2E] 自然 e2e 一次只能运行一个任务；全量请使用 bin/run_e2e_all.sh（每任务一个新世界）");
			if (onAllDone != null) {
				onAllDone.run();
			}
			return;
		}
		String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
		suiteLogPath = String.format(E2E_LOG_FILE, stamp);
		appendLogFile("======== E2E 自然世界详细日志 " + stamp + " ========");
		writeSuiteHeader();
		log("[E2E] 开始自然世界端到端任务: " + tasks.get(0).id());
		runNext(level, tasks, 0, new ArrayList<>(), onAllDone);
	}

	/** 跑单个任务（命令入口）。 */
	public static void runTask(ServerLevel level, String taskId, Runnable onAllDone) {
		com.swaydy.opencraft.e2e.E2ETask task = com.swaydy.opencraft.e2e.E2ERegistry.byId(taskId);
		if (task == null) {
			log("[E2E] 未知任务: " + taskId + "（可用: " + taskIds() + "）");
			return;
		}
		runTasks(level, List.of(task), onAllDone);
	}

	// ------------------------------------------------------------------
	// 任务链（服务端线程 ↔ 守护等待线程）
	// ------------------------------------------------------------------

	private static void runNext(ServerLevel level, List<com.swaydy.opencraft.e2e.E2ETask> tasks, int index,
	                            List<com.swaydy.opencraft.e2e.E2EResult> results, Runnable onAllDone) {
		if (index >= tasks.size()) {
			finishSuite(results, onAllDone);
			return;
		}
		com.swaydy.opencraft.e2e.E2ETask task = tasks.get(index);
		currentTaskLog = new StringBuilder();
		log("[E2E] 任务 「" + task.id() + "」开始（超时 " + (task.timeoutMillis() / 1000) + "s）");
		taskLog("==== 任务 " + task.id() + " ====");
		taskLog("描述: " + task.description());
		taskLog("超时: " + (task.timeoutMillis() / 1000) + "s");
		taskLog("指令: " + task.taskPrompt());
		com.swaydy.opencraft.e2e.E2EContext ctx;
		try {
			ctx = setupTask(level, task);
		} catch (Exception e) {
			com.swaydy.opencraft.e2e.E2EResult fail =
					new com.swaydy.opencraft.e2e.E2EResult(task.id(), false, 0, "任务启动失败: " + e);
			results.add(fail);
			taskLog("启动失败: " + e);
			flushTaskLog();
			report(fail);
			runNext(level, tasks, index + 1, results, onAllDone);
			return;
		}
		long start = System.currentTimeMillis();
		taskLog("下发指令: " + task.taskPrompt());
		log("[E2E] 已向助手下发指令（绑方块 " + ctx.configBlock().pos().toShortString() + "）");
		AiCompanionService.ask(ctx.owner(), ctx.assistant(), task.taskPrompt());
		startWatcher(level, tasks, index, results, ctx, task, start, onAllDone);
	}

	private static void startWatcher(ServerLevel level, List<com.swaydy.opencraft.e2e.E2ETask> tasks, int index,
	                                 List<com.swaydy.opencraft.e2e.E2EResult> results,
	                                 com.swaydy.opencraft.e2e.E2EContext ctx,
	                                 com.swaydy.opencraft.e2e.E2ETask task, long start, Runnable onAllDone) {
		Thread watcher = new Thread(() -> {
			long deadline = start + task.timeoutMillis();
			boolean stillBusy = true;
			int lastStatusSec = 0;
			int histBefore = AiCompanionService.historySize(ctx.configBlock());
			while (System.currentTimeMillis() < deadline) {
				if (!com.swaydy.opencraft.agent.AgentRuntime.isBusy(ctx.configBlock())) {
					stillBusy = false;
					break;
				}
				int elapsed = (int) ((System.currentTimeMillis() - start) / 1000);
				if (elapsed - lastStatusSec >= 10) {
					lastStatusSec = elapsed;
					int hist = AiCompanionService.historySize(ctx.configBlock());
					AiAssistantPlayer a = ctx.assistant();
					taskLog("[t+" + elapsed + "s] 位置=("
							+ (int) a.getX() + "," + (int) a.getY() + "," + (int) a.getZ()
							+ ") 历史=" + (hist - histBefore) + " 条  背包非空=" + ctx.nonEmptySlotCount() + " 格");
				}
				try {
					Thread.sleep(200);
				} catch (InterruptedException e) {
					return;
				}
			}
			final boolean timedOut = stillBusy;
			if (!timedOut) {
				try {
					Thread.sleep(1500);
				} catch (InterruptedException e) {
					return;
				}
			}
			ctx.server().executeIfPossible(() ->
					finishTask(level, tasks, index, results, ctx, task, start, timedOut, onAllDone));
		}, "E2E-watch-" + task.id());
		watcher.setDaemon(true);
		watcher.start();
	}

	private static void finishTask(ServerLevel level, List<com.swaydy.opencraft.e2e.E2ETask> tasks, int index,
	                               List<com.swaydy.opencraft.e2e.E2EResult> results,
	                               com.swaydy.opencraft.e2e.E2EContext ctx,
	                               com.swaydy.opencraft.e2e.E2ETask task, long start,
	                               boolean timedOut, Runnable onAllDone) {
		long duration = System.currentTimeMillis() - start;
		String message;
		boolean passed;
		int hist = AiCompanionService.historySize(ctx.configBlock());
		AiAssistantPlayer a = ctx.assistant();
		taskLog("用时: " + (duration / 1000) + "s  历史: " + hist + " 条");
		taskLog("助手位置: (" + (int) a.getX() + "," + (int) a.getY() + "," + (int) a.getZ() + ")");
		StringBuilder inv = new StringBuilder();
		for (net.minecraft.world.item.ItemStack stack : a.getInventory().getNonEquipmentItems()) {
			if (stack.isEmpty()) {
				continue;
			}
			if (inv.length() > 0) {
				inv.append(", ");
			}
			inv.append(java.util.Objects.toString(
					net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()), "?"));
			inv.append("x").append(stack.getCount());
		}
		taskLog("背包: " + (inv.length() > 0 ? inv : "空"));
		if (timedOut) {
			com.swaydy.opencraft.agent.AgentRuntime.interrupt(ctx.configBlock());
			message = "超时（" + (duration / 1000) + "s 未完成），已中断；历史 " + hist + " 条";
			passed = false;
			taskLog("结果: 超时（已中断）");
		} else {
			String lastReply = lastHistoryText(ctx.configBlock());
			// 循环提前结束但最后一条非空消息就是任务指令本身 = 助手从未产出文本
			// （典型成因：LLM 连续重试后请求失败，整轮被杀）。直接把任务指令当成
			// 「助手最后回复」会把 LLM/网络故障误读成模型行为，这里显式标注。
			if (lastReply != null && lastReply.trim().equals(task.taskPrompt().trim())) {
				taskLog("助手最后回复: （无——循环未产出任何助手文本，疑似 LLM 请求失败，"
						+ "LLM 逐轮日志见 logs/e2e-debug-" + task.id() + ".log）");
				try {
					passed = task.verify(ctx);
				} catch (Exception e) {
					passed = false;
				}
				message = (passed ? "验证通过" : "验证失败")
						+ " | 助手未产出回复（LLM 循环中断，详见 logs/e2e-debug-"
						+ task.id() + ".log）";
			} else {
				taskLog("助手最后回复: " + lastReply);
				try {
					passed = task.verify(ctx);
				} catch (Exception e) {
					passed = false;
					lastReply = "验证异常: " + e;
				}
				message = (passed ? "验证通过" : "验证失败")
						+ " | 助手最后回复: " + truncate(lastReply, 120);
				taskLog("验证: " + (passed ? "PASS" : "FAIL") + "  |  " + lastReply);
			}
		}
		try {
			task.teardown(ctx);
		} catch (Exception e) {
			log("[E2E] 任务 " + task.id() + " teardown 异常: " + e);
		}
		dismissAssistant(ctx);
		com.swaydy.opencraft.e2e.E2EResult result =
				new com.swaydy.opencraft.e2e.E2EResult(task.id(), passed, duration, message);
		results.add(result);
		flushTaskLog();
		preserveDebugLog(task.id());
		report(result);
		runNext(level, tasks, index + 1, results, onAllDone);
	}

	private static void finishSuite(List<com.swaydy.opencraft.e2e.E2EResult> results, Runnable onAllDone) {
		int passed = (int) results.stream().filter(com.swaydy.opencraft.e2e.E2EResult::passed).count();
		for (com.swaydy.opencraft.e2e.E2EResult r : results) {
			log("[E2E]   - " + r.summaryLine());
		}
		String summary = "[E2E] 任务结果: " + passed + "/" + results.size() + " 通过";
		log(summary);
		appendFile(summary);
		if (suiteLogPath != null && !suiteLogPath.isEmpty()) {
			log("[E2E] 详细日志: " + Path.of(suiteLogPath).toAbsolutePath());
		}
		if (onAllDone != null) {
			onAllDone.run();
		}
	}

	// ------------------------------------------------------------------
	// 自然世界场景准备
	// ------------------------------------------------------------------

	private static com.swaydy.opencraft.e2e.E2EContext setupTask(ServerLevel level,
	                                                             com.swaydy.opencraft.e2e.E2ETask task) {
		BlockPos worldSpawn = level.getRespawnData().pos();
		ProbeResult probe = probeNaturalWorld(level, worldSpawn);
		taskLog("固定种子: " + level.getSeed());
		taskLog("世界出生点: " + worldSpawn.toShortString());
		taskLog("地形勘察: " + probe.summary());
		if (!probe.passed()) {
			throw new IllegalStateException("自然出生点不满足 e2e 勘察条件: " + probe.summary());
		}

		ServerPlayer owner = createOwner(level, task.id());
		owner.getInventory().clearContent();
		// 重新掷点直到「实际落点」满足勘察条件：
		// 世界种子固定，但 vanilla 新玩家落点（adjustSpawnLocation）用实体随机源
		// 螺旋搜索，同一存档每次落点不同；玩家式工具的搜索半径只有 20 格，
		// 若落到（勘察通过的世界出生点之外的）无树山顶，任务不可能完成——
		// 那是环境缺陷不是模型能力，必须在这里挡住。
		BlockPos actualSpawn = null;
		ProbeResult spawnProbe = null;
		int spawnAttempts = 0;
		for (int attempt = 1; attempt <= 40; attempt++) {
			spawnAttempts = attempt;
			BlockPos candidate = owner.adjustSpawnLocation(level, worldSpawn);
			ProbeResult r = probeNaturalWorld(level, candidate);
			if (attempt <= 3 || r.passed()) {
				taskLog("出生点掷点 #" + attempt + ": " + candidate.toShortString() + " → " + r.summary());
			}
			if (r.passed()) {
				actualSpawn = candidate;
				spawnProbe = r;
				break;
			}
		}
		if (actualSpawn == null) {
			throw new IllegalStateException("40 次出生点掷点都不满足勘察条件（落点附近无树/无暴露石头）；"
					+ "末次: " + (spawnProbe == null ? "?" : spawnProbe.summary()));
		}
		owner.teleportTo(actualSpawn.getX() + 0.5, actualSpawn.getY(), actualSpawn.getZ() + 0.5);
		taskLog("合成主人自然落点: " + owner.getName().getString() + " @ "
				+ actualSpawn.toShortString() + "（掷点 " + spawnAttempts + " 次）");

		BlockPos configPos = findConfigPos(level, actualSpawn);
		if (configPos == null) {
			throw new IllegalStateException("出生点 8 格内没有安全的自然地面可放配置方块");
		}
		GlobalPos configBlock = placeConfigBlock(level, configPos);
		taskLog("配置方块: " + configPos.toShortString()
				+ "（baseUrl=" + AiBlockConfig.defaultBaseUrl()
				+ ", model=" + AiBlockConfig.defaultModel()
				+ ", agent=general_agent）");

		AiAssistantPlayer assistant = AssistantFacade.summon(owner, configBlock);
		if (assistant == null) {
			throw new IllegalStateException("召唤助手失败（方块/维度不可用？）");
		}
		assistant.getInventory().clearContent();
		assistant.getAbilities().invulnerable = true;
		assistant.setInvulnerable(true);
		assistant.setHealth(assistant.getMaxHealth());
		taskLog("助手: 系统名=" + assistant.getName().getString()
				+ " 显示名=" + assistant.getConfig().effectiveName()
				+ " 出生点=(" + (int) assistant.getX() + "," + (int) assistant.getY() + "," + (int) assistant.getZ() + ")"
				+ "（真实空背包，已设无敌）");
		shotTarget = assistant;
		com.swaydy.opencraft.e2e.E2EContext ctx = new com.swaydy.opencraft.e2e.E2EContext(
				level.getServer(), level, owner, assistant, configBlock,
				actualSpawn, worldSpawn, ORIGINAL_CONFIG_STATES.get(configBlock));
		// 任务场景准备（SPI 钩子，服务端线程）：发放任务承诺的初始物品（木镐/工作台等）。
		// 必须在助手进服且清空背包之后、下发指令之前调用，否则任务 prompt 声称的
		// 初始物品与助手真实背包不一致，会把 harness 缺陷误判成模型能力问题。
		try {
			task.setup(ctx);
		} catch (RuntimeException e) {
			throw new IllegalStateException("任务 setup 失败: " + e, e);
		}
		taskLog("场景准备 setup 后助手背包: " + inventorySummary(assistant));
		return ctx;
	}

	/** 助手主背包物品摘要（e2e 日志用），如 "minecraft:wooden_pickaxe x1"。 */
	private static String inventorySummary(net.minecraft.world.entity.player.Player player) {
		StringBuilder sb = new StringBuilder();
		for (net.minecraft.world.item.ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (stack.isEmpty()) {
				continue;
			}
			if (sb.length() > 0) {
				sb.append(", ");
			}
			sb.append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()))
					.append(" x").append(stack.getCount());
		}
		return sb.length() > 0 ? sb.toString() : "空";
	}

	/**
	 * 合成一个真实进入 PlayerList 的主人玩家，并用原版出生点调整逻辑确定自然落点。
	 * 不铺平台；对 mod 而言它就是一个刚进服的真实新玩家。
	 */
	private static ServerPlayer createOwner(ServerLevel level, String taskId) {
		MinecraftServer server = level.getServer();
		String name = "E2E_" + taskId;
		if (name.length() > 16) {
			name = name.substring(0, 16);
		}
		GameProfile profile = new GameProfile(
				UUID.nameUUIDFromBytes(("opencraft:e2e:owner:" + taskId).getBytes(StandardCharsets.UTF_8)), name);
		ServerPlayer owner = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
		server.getPlayerList().placeNewPlayer(
				new com.swaydy.opencraft.assistant.player.FakeConnection(), owner,
				CommonListenerCookie.createInitial(profile, false));
		owner.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
		owner.getAbilities().invulnerable = true;
		owner.setInvulnerable(true);
		owner.getFoodData().setFoodLevel(20);
		owner.getFoodData().setSaturation(20f);
		return owner;
	}

	/** 只读勘察自然出生点：树、可采石头和安全条件，不修改任何方块。 */
	private static ProbeResult probeNaturalWorld(ServerLevel level, BlockPos spawn) {
		int oakLogs = 0;
		int exposedStone = 0;
		int hazards = 0;
		int water = 0;
		for (int dx = -20; dx <= 20; dx++) {
			for (int dy = -20; dy <= 20; dy++) {
				for (int dz = -20; dz <= 20; dz++) {
					BlockPos pos = spawn.offset(dx, dy, dz);
					BlockState state = level.getBlockState(pos);
					if (state.is(Blocks.OAK_LOG)) {
						oakLogs++;
					}
					if (state.is(Blocks.STONE) && hasAirNeighbor(level, pos)) {
						exposedStone++;
					}
					if (state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.CACTUS)
							|| state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.POWDER_SNOW)) {
						hazards++;
					}
					if (Math.abs(dx) <= 4 && Math.abs(dz) <= 4 && state.getFluidState().is(net.minecraft.world.level.material.Fluids.WATER)) {
						water++;
					}
				}
			}
		}
		boolean groundSafe = !level.getBlockState(spawn).isSolid()
				&& !level.getBlockState(spawn.above()).isSolid()
				&& level.getBlockState(spawn.below()).isSolid()
				&& level.getFluidState(spawn).isEmpty();
		boolean passed = oakLogs >= 3 && exposedStone >= 8 && hazards == 0
				&& water <= 16 && groundSafe;
		return new ProbeResult(passed, oakLogs, exposedStone, hazards, water, groundSafe);
	}

	private static boolean hasAirNeighbor(ServerLevel level, BlockPos pos) {
		for (Direction dir : Direction.values()) {
			if (level.getBlockState(pos.relative(dir)).isAir()) {
				return true;
			}
		}
		return false;
	}

	/** 在自然出生点 8 格内找配置方块位置：原位置为空气、可站、不替换自然方块。 */
	private static BlockPos findConfigPos(ServerLevel level, BlockPos spawn) {
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int dx = -8; dx <= 8; dx++) {
			for (int dz = -8; dz <= 8; dz++) {
				for (int dy = -4; dy <= 4; dy++) {
					BlockPos pos = spawn.offset(dx, dy, dz);
					BlockState state = level.getBlockState(pos);
					BlockState above = level.getBlockState(pos.above());
					BlockState below = level.getBlockState(pos.below());
					if (!state.isAir() || !above.isAir() || !below.isSolid()
							|| !level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.below()).isEmpty()) {
						continue;
					}
					if (below.is(Blocks.LAVA) || below.is(Blocks.MAGMA_BLOCK) || below.is(Blocks.CACTUS)) {
						continue;
					}
					double distance = spawn.distSqr(pos);
					if (distance < bestDistance) {
						bestDistance = distance;
						best = pos;
					}
				}
			}
		}
		return best;
	}

	/** 放置 AI 徽标方块并写入真实默认 LLM 配置；返回其 GlobalPos。 */
	private static GlobalPos placeConfigBlock(ServerLevel level, BlockPos pos) {
		BlockState originalState = level.getBlockState(pos);
		level.setBlock(pos, ModBlocks.AI_LOGO_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
		if (level.getBlockEntity(pos) instanceof AiLogoBlockEntity be) {
			AiBlockConfig cfg = be.getConfig();
			cfg.baseUrl = AiBlockConfig.defaultBaseUrl();
			cfg.apiKey = AiBlockConfig.defaultApiKey();
			cfg.model = AiBlockConfig.defaultModel();
			cfg.agent = "general_agent";
			// e2e 把单轮 LLM 超时放宽到 180s：真实上游（尤其首次请求带完整
			// 系统提示 + 20 个工具）可能 1-3 分钟才吐第一个 token，默认 60s 的
			// SSE 看门狗会把"慢"误判成失败直接结束整轮。
			cfg.timeoutSeconds = 180;
			be.markConfigChanged();
			ORIGINAL_CONFIG_STATES.put(GlobalPos.of(level.dimension(), pos), originalState);
			return GlobalPos.of(level.dimension(), pos);
		}
		throw new IllegalStateException("AI 徽标方块实体未创建于 " + pos.toShortString());
	}

	/**
	 * 任务收尾：恢复配置方块原自然状态，并清空对话记忆。
	 * 不移除假玩家，避免真实存档 PlayerList.remove 触发 vanilla 光照引擎崩溃。
	 */
	private static void dismissAssistant(com.swaydy.opencraft.e2e.E2EContext ctx) {
		if (ctx.configOriginalState() != null) {
			ctx.level().setBlock(ctx.configBlock().pos(), ctx.configOriginalState(), Block.UPDATE_ALL);
		}
		ORIGINAL_CONFIG_STATES.remove(ctx.configBlock());
		AiCompanionService.resetHistory(ctx.configBlock());
		shotTarget = null;
	}

	// ------------------------------------------------------------------
	// 报告
	// ------------------------------------------------------------------

	private static void report(com.swaydy.opencraft.e2e.E2EResult result) {
		log("[E2E] " + result.summaryLine());
		appendFile("[E2E] " + result.summaryLine());
	}

	private static void writeSuiteHeader() {
		appendFile("======== E2E 自然世界套件 "
				+ new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date())
				+ " ========");
	}

	private static void appendFile(String line) {
		try {
			Path path = Path.of(RESULTS_FILE);
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}
			Files.writeString(path, line + System.lineSeparator(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (Exception e) {
			OpenCraftMod.LOGGER.debug("[OpenCraft] 写 e2e 结果文件失败: {}", e.toString());
		}
	}

	private static void log(String line) {
		OpenCraftMod.LOGGER.info("[OpenCraft] {}", line);
		com.swaydy.opencraft.logging.DebugLog.log("e2e", "{}", line.replace("[E2E] ", ""));
		appendLogFile(line);
	}

	private static void taskLog(String line) {
		StringBuilder sb = currentTaskLog;
		if (sb != null) {
			synchronized (sb) {
				sb.append(line).append('\n');
			}
		}
	}

	private static void flushTaskLog() {
		StringBuilder sb = currentTaskLog;
		if (sb != null) {
			synchronized (sb) {
				appendLogFile(sb.toString().stripTrailing());
			}
		}
	}

	/**
	 * 任务结束时把本服务器的 opencraft-debug.log（LLM 逐轮请求/回复/重试/失败）
	 * 另存为按任务命名的文件——debug 日志每个 JVM 覆盖式重写，
	 * 而全量套件每个任务都是独立 JVM，不另存就只剩最后一个任务的证据。
	 */
	private static void preserveDebugLog(String taskId) {
		try {
			Path src = Path.of("logs/opencraft-debug.log");
			if (!Files.exists(src)) {
				return;
			}
			Path dst = Path.of("logs/e2e-debug-" + taskId + ".log");
			Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			appendLogFile("[E2E] LLM 调试日志: " + dst.toAbsolutePath());
		} catch (Exception e) {
			OpenCraftMod.LOGGER.debug("[OpenCraft] 保存 e2e debug 日志失败: {}", e.toString());
		}
	}

	private static void appendLogFile(String text) {
		if (suiteLogPath == null || suiteLogPath.isEmpty()) {
			return;
		}
		try {
			Path path = Path.of(suiteLogPath);
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}
			Files.writeString(path, text + System.lineSeparator(), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (Exception e) {
			OpenCraftMod.LOGGER.debug("[OpenCraft] 写 e2e 详细日志失败: {}", e.toString());
		}
	}

	private static String lastHistoryText(GlobalPos key) {
		try {
			List<com.swaydy.opencraft.ai.LlmClient.Message> history = AiCompanionService.getHistory(key);
			for (int i = history.size() - 1; i >= 0; i--) {
				com.swaydy.opencraft.ai.LlmClient.Message m = history.get(i);
				String text = messageText(m);
				if (!text.isBlank()) {
					return text;
				}
			}
		} catch (Exception ignored) {
		}
		return "（无历史消息）";
	}

	private static String messageText(com.swaydy.opencraft.ai.LlmClient.Message m) {
		if (m == null || m.content() == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (com.swaydy.opencraft.ai.LlmClient.Block b : m.content()) {
			if (b instanceof com.swaydy.opencraft.ai.LlmClient.TextBlock t && t.text() != null) {
				sb.append(t.text());
			}
		}
		return sb.toString();
	}

	private static String truncate(String s, int max) {
		if (s == null) {
			return "（空）";
		}
		String flat = s.replace('\n', ' ').replace('\r', ' ').trim();
		return flat.length() <= max ? flat : flat.substring(0, max) + "…";
	}

	private static String taskIds() {
		StringBuilder sb = new StringBuilder();
		for (com.swaydy.opencraft.e2e.E2ETask t : com.swaydy.opencraft.e2e.E2ERegistry.all()) {
			if (sb.length() > 0) {
				sb.append(", ");
			}
			sb.append(t.id());
		}
		return sb.toString();
	}

	/** 只读输出自然出生点勘察 JSON（opencraft.e2e.probe=true 时，不召唤助手）。 */
	private static void probeWorld(ServerLevel level) {
		BlockPos spawn = level.getRespawnData().pos();
		ProbeResult result = probeNaturalWorld(level, spawn);
		String json = """
				{
				  "seed": %d,
				  "spawn": {"x": %d, "y": %d, "z": %d},
				  "passed": %s,
				  "oakLogs": %d,
				  "exposedStone": %d,
				  "hazards": %d,
				  "nearbyWater": %d,
				  "groundSafe": %s
				}
				""".formatted(level.getSeed(), spawn.getX(), spawn.getY(), spawn.getZ(),
				result.passed(), result.oakLogs(), result.exposedStone(), result.hazards(),
				result.nearbyWater(), result.groundSafe());
		try {
			Path path = Path.of("logs/e2e-world-probe.json");
			if (path.getParent() != null) {
				Files.createDirectories(path.getParent());
			}
			Files.writeString(path, json, StandardCharsets.UTF_8);
			log("[E2E] 世界勘察完成: " + result.summary() + " → " + path.toAbsolutePath());
		} catch (Exception e) {
			log("[E2E] 写世界勘察 JSON 失败: " + e);
		}
	}

	private static Runnable shutdown(MinecraftServer server) {
		return () -> server.execute(() -> {
			try {
				server.getPlayerList().saveAll();
				server.saveAllChunks(true, true, false);
				server.halt(false);
			} catch (Exception e) {
				OpenCraftMod.LOGGER.warn("[OpenCraft] E2E 停服异常: {}", e.toString());
			}
		});
	}

	private record ProbeResult(boolean passed, int oakLogs, int exposedStone,
	                           int hazards, int nearbyWater, boolean groundSafe) {
		private String summary() {
			return "pass=" + passed + ", oak_log=" + oakLogs + "/3, exposed_stone=" + exposedStone
					+ "/8, hazards=" + hazards + "/0, nearby_water=" + nearbyWater
					+ "/16, ground_safe=" + groundSafe;
		}
	}
}
