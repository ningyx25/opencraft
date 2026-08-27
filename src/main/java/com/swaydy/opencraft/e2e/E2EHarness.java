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
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 端到端测试编排器：在<b>无头真实存档</b>（独立服务器）里，用真实 LLM + general_agent
 * 驱动玩家形态 AI 助手完成内置任务，并按世界方块状态 / 助手背包物品计数验证真实结果。
 *
 * <p><b>为什么不是 gametest</b>：gametest 的空结构世界与脚本化断言面向单元验证；
 * e2e 在 {@code ./gradlew runServer} 起的真实世界里跑完整 agentic loop
 * （真实 LLM → 工具调用 → 真玩家式移动/挖掘/放置/合成 → 世界状态变化），
 * 是"助手像真实玩家一样进服干活"的验收。</p>
 *
 * <p><b>无头驱动</b>：所有 /opencraft 命令原本要求玩家源（{@code getPlayerOrException}），
 * 本模块绕开命令层，直接用公共 API：合成一个<b>主人玩家</b>并像助手一样用
 * {@code PlayerList.placeNewPlayer} + 黑洞连接正式进服（对 mod 就是一个真客户端，
 * 跟随/治疗/网络广播全部照常），{@code AssistantFacade.summon} 召唤、
 * {@code AiCompanionService.ask} 下发任务、{@code AgentRuntime.isBusy} 轮询完成状态。
 * 每任务一个独立主人（用完送走），避免 PlayerList UUID 冲突。</p>
 *
 * <p><b>运行入口</b>：
 * <ul>
 *   <li>控制台命令 {@code /opencraft e2e run <id|all>}（无玩家在线也能跑）；</li>
 *   <li>自动运行 {@code -Dopencraft.e2e.autorun=<id|all>}（配合 gradle 任务
 *       {@code ./gradlew runE2E}，先删 {@code run/world} 拿全新存档，跑完自动退出）。</li>
 * </ul>
 * 结果写入 {@code run/logs/e2e-results.txt}（追加，每次套件带分隔头）并打日志。</p>
 */
public final class E2EHarness {
	/** 每个任务独立测试区：x 方向间隔（格），避免跨任务干扰（平台/树/助手/主人各自独立）。 */
	private static final int AREA_STRIDE = 50;
	/** 测试区基因原点（每任务 + index*AREA_STRIDE）。y=120 高于所有地形，真实世界的树木/石头
	 * 不会干扰任务场景，玩家式 find 也扫不到（find 半径 ≤ 20 格）。 */
	private static final BlockPos AREA_BASE = new BlockPos(300, 120, 300);

	/** 第 index 个任务的测试区原点。 */
	private static BlockPos areaOrigin(int index) {
		return new BlockPos(AREA_BASE.getX() + index * AREA_STRIDE, AREA_BASE.getY(), AREA_BASE.getZ());
	}
	/** 平台半边长（平台边长 2*PLATFORM_HALF+1）。 */
	private static final int PLATFORM_HALF = 8;

	/** 平台半边长（供任务验证区域扫描使用）。 */
	public static int platformRadius() {
		return PLATFORM_HALF;
	}
	/** 平台上方清空高度。 */
	private static final int CLEAR_HEIGHT = 24;
	/** 结果文件（相对服务器工作目录 run/）。 */
	private static final String RESULTS_FILE = "logs/e2e-results.txt";
	/** 当前套件的详细日志文件（含工具序列/验证细节等；每次套件独立文件）。 */
	private static final String E2E_LOG_FILE = "logs/e2e-%s.log";
	/** 当前套件详细日志文件路径（套件开始时设置）。 */
	private static volatile String suiteLogPath = "";
	/** 当前任务的详细记录（工具事件/周期状态），任务结束写入套件日志文件。 */
	private static volatile StringBuilder currentTaskLog = new StringBuilder();

	private E2EHarness() {
	}

	// ------------------------------------------------------------------
	// 入口：命令 / autorun
	// ------------------------------------------------------------------

	/** 注册 autorun 钩子（{@code -Dopencraft.e2e.autorun=<id|all>}，服务器启动后自动跑并退出）。 */
	public static void registerAutoRunHook() {
		registerShotClientGlue();
		// 工具执行观察者：把每轮工具调用录进当前任务详细日志
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
				List<com.swaydy.opencraft.e2e.E2ETask> tasks;
				if ("all".equalsIgnoreCase(autorun.trim())) {
					tasks = com.swaydy.opencraft.e2e.E2ERegistry.all();
				} else {
					com.swaydy.opencraft.e2e.E2ETask task = com.swaydy.opencraft.e2e.E2ERegistry.byId(autorun.trim());
					if (task == null) {
						log("[E2E] 未知任务: " + autorun + "（可用: " + taskIds() + "）");
						shutdown(server).run();
						return;
					}
					tasks = List.of(task);
				}
				log("[E2E] 自动运行 " + tasks.size() + " 个任务: " + taskIds(tasks));
				// -Dopencraft.e2e.holdMs=<毫秒>：任务跑完后服务器保持运行这段时间
				// （真截图客户端启动慢，需要窗口期连上、被粘到助手眼睛并截图；默认 0 = 立即退出）
				long holdMs = 0;
				String holdProp = System.getProperty("opencraft.e2e.holdMs");
				if (holdProp != null && !holdProp.isBlank()) {
					try {
						holdMs = Long.parseLong(holdProp.trim());
					} catch (NumberFormatException ignored) {
					}
				}
				final long hold = holdMs;
				Runnable onAllDone = () -> new Thread(() -> {
					if (hold > 0) {
						log("[E2E] 套件结束，保持服务器运行 " + (hold / 1000) + "s 供截图客户端连入…");
						try {
							Thread.sleep(hold);
						} catch (InterruptedException e) {
							return;
						}
					}
					shotTarget = null; // hold 结束才解除 glue
					shutdown(server).run();
				}, "E2E-hold").start();
				runTasks(level, tasks, onAllDone);
			});
		});
	}

	/** 当前 e2e 任务的助手（截图客户端要粘到它的眼睛上）；hold 结束/下个任务时更新。 */
	private static volatile com.swaydy.opencraft.assistant.player.AiAssistantPlayer shotTarget;

	/**
	 * 注册"截图客户端 glue"：e2e 任务进行中（{@link #shotTarget} 非空）时，每 tick 把
	 * 连接进来的"非助手、非 E2E_ 合成主人"的玩家（即 Xvfb 真客户端）TP 到助手眼睛坐标、
	 * 朝向对齐助手——这样客户端截图画面就是助手的第一人称视角。
	 * 幂等（每次注册钩子只挂一次；靠 {@code shotTarget} 是否为空决定是否生效）。
	 */
	private static void registerShotClientGlue() {
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
			com.swaydy.opencraft.assistant.player.AiAssistantPlayer target = shotTarget;
			if (target == null || target.isRemoved()) {
				return;
			}
			for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (p == target || p.isRemoved()) {
					continue;
				}
				String name = p.getName().getString();
				if (name == null || name.startsWith("E2E_")) {
					continue; // 合成主人，不是截图客户端
				}
				p.teleportTo(target.level(),
						target.getX(), target.getY() + 1.62, target.getZ(),
						java.util.Set.of(), target.getYRot(), target.getXRot(), false);
			}
		});
	}

	/** 在指定维度依次运行一组任务（入口需在服务端线程；完成后回调 onAllDone）。 */
	public static void runTasks(ServerLevel level, List<com.swaydy.opencraft.e2e.E2ETask> tasks,
	                            Runnable onAllDone) {
		if (tasks == null || tasks.isEmpty()) {
			if (onAllDone != null) {
				onAllDone.run();
			}
			return;
		}
		// 新建本套件的详细日志文件（含任务头/工具序列/周期状态/验证细节）
		String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
		suiteLogPath = String.format(E2E_LOG_FILE, stamp);
		appendLogFile("======== E2E 详细日志 " + stamp + " ========");
		writeSuiteHeader();
		log("[E2E] 开始端到端套件，共 " + tasks.size() + " 个任务");
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
		log("[E2E] 任务 " + (index + 1) + "/" + tasks.size() + " 「" + task.id() + "」开始（超时 "
				+ (task.timeoutMillis() / 1000) + "s）");
		taskLog("==== 任务 " + task.id() + " ====");
		taskLog("描述: " + task.description());
		taskLog("超时: " + (task.timeoutMillis() / 1000) + "s");
		taskLog("指令: " + task.taskPrompt());
		taskLog("区域原点: " + areaOrigin(index).toShortString());
		com.swaydy.opencraft.e2e.E2EContext ctx;
		try {
			ctx = setupTask(level, task, index);
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

	/** 守护线程轮询 {@link com.swaydy.opencraft.agent.AgentRuntime#isBusy}，完成后切回服务端线程验证。 */
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
				// 每 10s 记录一次助手状态（位置/历史/背包）
				int elapsed = (int) ((System.currentTimeMillis() - start) / 1000);
				if (elapsed - lastStatusSec >= 10) {
					lastStatusSec = elapsed;
					int hist = AiCompanionService.historySize(ctx.configBlock());
					com.swaydy.opencraft.assistant.player.AiAssistantPlayer a = ctx.assistant();
					int slots = ctx.nonEmptySlotCount();
					taskLog("[t+" + elapsed + "s] 位置=("
							+ (int) a.getX() + "," + (int) a.getY() + "," + (int) a.getZ()
							+ ") 历史=" + (hist - histBefore) + " 条  背包非空=" + slots + " 格");
				}
				try {
					Thread.sleep(200);
				} catch (InterruptedException e) {
					return;
				}
			}
			final boolean timedOut = stillBusy;
			// 收尾宽限：loop 结束后给掉落物拾取/动作收尾一点时间再验证（防 flaky）
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

	/** 服务端线程：验证 + 清理 + 报告 + 续跑。详细日志写入当前任务 log。 */
	private static void finishTask(ServerLevel level, List<com.swaydy.opencraft.e2e.E2ETask> tasks, int index,
	                               List<com.swaydy.opencraft.e2e.E2EResult> results,
	                               com.swaydy.opencraft.e2e.E2EContext ctx,
	                               com.swaydy.opencraft.e2e.E2ETask task, long start,
	                               boolean timedOut, Runnable onAllDone) {
		long duration = System.currentTimeMillis() - start;
		String message;
		boolean passed;
		int hist = AiCompanionService.historySize(ctx.configBlock());
		com.swaydy.opencraft.assistant.player.AiAssistantPlayer a = ctx.assistant();
		taskLog("用时: " + (duration / 1000) + "s  历史: " + hist + " 条");
		taskLog("助手位置: (" + (int) a.getX() + "," + (int) a.getY() + "," + (int) a.getZ() + ")");
		// 背包快照
		StringBuilder inv = new StringBuilder();
		for (net.minecraft.world.item.ItemStack stack
				: a.getInventory().getNonEquipmentItems()) {
			if (stack.isEmpty()) continue;
			if (inv.length() > 0) inv.append(", ");
			inv.append(java.util.Objects.toString(
					net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()), "?"));
			inv.append("x").append(stack.getCount());
		}
		taskLog("背包: " + (inv.length() > 0 ? inv.toString() : "空"));
		if (timedOut) {
			com.swaydy.opencraft.agent.AgentRuntime.interrupt(ctx.configBlock());
			message = "超时（" + (duration / 1000) + "s 未完成），已中断；历史 " + hist + " 条";
			passed = false;
			taskLog("结果: 超时（已中断）");
		} else {
			String lastReply = lastHistoryText(ctx.configBlock());
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
		try {
			task.teardown(ctx);
		} catch (Exception e) {
			log("[E2E] 任务 " + task.id() + " teardown 异常: " + e);
		}
		dismissAssistant(ctx);
		com.swaydy.opencraft.e2e.E2EResult result =
				new com.swaydy.opencraft.e2e.E2EResult(task.id(), passed, duration, message);
		results.add(result);
		// 将当前任务详细日志刷入套件日志文件
		flushTaskLog();
		report(result);
		runNext(level, tasks, index + 1, results, onAllDone);
	}

	private static void finishSuite(List<com.swaydy.opencraft.e2e.E2EResult> results, Runnable onAllDone) {
		int passed = (int) results.stream().filter(com.swaydy.opencraft.e2e.E2EResult::passed).count();
		for (com.swaydy.opencraft.e2e.E2EResult r : results) {
			log("[E2E]   - " + r.summaryLine());
		}
		String summary = "[E2E] 套件结果: " + passed + "/" + results.size() + " 通过";
		log(summary);
		appendFile(summary);
		if (suiteLogPath != null && !suiteLogPath.isEmpty()) {
			log("[E2E] 详细日志: " + java.nio.file.Path.of(suiteLogPath).toAbsolutePath());
		}
		if (onAllDone != null) {
			onAllDone.run();
		}
	}

	// ------------------------------------------------------------------
	// 场景准备
	// ------------------------------------------------------------------

	private static com.swaydy.opencraft.e2e.E2EContext setupTask(ServerLevel level,
	                                                             com.swaydy.opencraft.e2e.E2ETask task,
	                                                             int index) {
		BlockPos areaOrigin = areaOrigin(index);
		prepareArea(level, areaOrigin); // 清空 + 铺平台
		taskLog("已铺 3 层石质平台 @ " + areaOrigin.toShortString());
		BlockPos blockPos = new BlockPos(
				areaOrigin.getX() - PLATFORM_HALF, areaOrigin.getY() + 1, areaOrigin.getZ() - PLATFORM_HALF);
		GlobalPos configBlock = placeConfigBlock(level, blockPos);
		taskLog("配置方块: " + blockPos.toShortString()
				+ "（baseUrl=" + AiBlockConfig.defaultBaseUrl()
				+ ", model=" + AiBlockConfig.defaultModel()
				+ ", agent=general_agent）");
		// 合成一个"真实存在"的主人玩家（加进 PlayerList，黑洞连接——对 mod 就是一个进服的客户端，
		// 跟随/治疗/网络广播全部照常工作）；每任务一个独立 UUID，留在世界不送走。
		ServerPlayer owner = createOwner(level, task.id(), areaOrigin);
		taskLog("主人: " + owner.getName().getString() + " (" + owner.getUUID() + ")");
		AiAssistantPlayer assistant = AssistantFacade.summon(owner, configBlock);
		if (assistant == null) {
			throw new IllegalStateException("召唤助手失败（方块/维度不可用？）");
		}
		// 评测期间助手不死亡：无敌旗标（挡所有普通伤害）+ 实体级 Invulnerable（挡虚空掉落/
		// 绕过无敌的伤害）；summonFor 已设 abilities.invulnerable，这里再兜底并回满血
		assistant.getAbilities().invulnerable = true;
		assistant.setInvulnerable(true);
		assistant.setHealth(assistant.getMaxHealth());
		taskLog("助手: 系统名=" + assistant.getName().getString()
				+ " 显示名=" + assistant.getConfig().effectiveName()
				+ " 出生点=(" + (int) assistant.getX() + "," + (int) assistant.getY() + "," + (int) assistant.getZ() + ")"
				+ "（已设无敌）");
		shotTarget = assistant; // 截图客户端粘到本任务助手的眼睛上
		com.swaydy.opencraft.e2e.E2EContext ctx = new com.swaydy.opencraft.e2e.E2EContext(
				level.getServer(), level, owner, assistant, configBlock, areaOrigin);
		task.setup(ctx); // 种树等场景
		return ctx;
	}

	/** 清空测试区 + 铺石质平台（每个任务独立区域，保证确定性，不依赖世界生成）。
	 * 平台<b>3 层厚</b>：挖掉表面一块只会掉进浅坑（1 格），不会挖穿平台掉到下方地形
	 * （平台 y=120 浮空，下方是地形——单层平台被挖穿 bot 会掉 40+ 格，掉落物留在平台上
	 * 超出拾取范围，验证永远失败）。 */
	private static void prepareArea(ServerLevel level, BlockPos areaOrigin) {
		int ox = areaOrigin.getX(), oy = areaOrigin.getY(), oz = areaOrigin.getZ();
		for (int dx = -PLATFORM_HALF; dx <= PLATFORM_HALF; dx++) {
			for (int dz = -PLATFORM_HALF; dz <= PLATFORM_HALF; dz++) {
				for (int dy = -2; dy <= 0; dy++) {
					level.setBlock(new BlockPos(ox + dx, oy + dy, oz + dz),
							Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
				}
				for (int dy = 1; dy <= CLEAR_HEIGHT; dy++) {
					level.setBlock(new BlockPos(ox + dx, oy + dy, oz + dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				}
			}
		}
		// 清掉区域里遗留的掉落物
		AABB box = new AABB(ox - PLATFORM_HALF - 2, oy - 3, oz - PLATFORM_HALF - 2,
				ox + PLATFORM_HALF + 2, oy + CLEAR_HEIGHT + 2, oz + PLATFORM_HALF + 2);
		for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, box)) {
			item.discard();
		}
	}

	/** 放置 AI 徽标方块并把 LLM 配置（.env 注入的真实默认值）写进去。 */
	private static GlobalPos placeConfigBlock(ServerLevel level, BlockPos pos) {
		level.setBlock(pos, ModBlocks.AI_LOGO_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
		if (level.getBlockEntity(pos) instanceof AiLogoBlockEntity be) {
			AiBlockConfig cfg = be.getConfig();
			cfg.baseUrl = AiBlockConfig.defaultBaseUrl();
			cfg.apiKey = AiBlockConfig.defaultApiKey();
			cfg.model = AiBlockConfig.defaultModel();
			cfg.agent = "general_agent";
			be.markConfigChanged();
		} else {
			throw new IllegalStateException("AI 徽标方块实体未创建于 " + pos);
		}
		return GlobalPos.of(level.dimension(), pos);
	}

	/**
	 * 合成一个"真实存在"的主人玩家：像助手一样用 {@code PlayerList.placeNewPlayer} +
	 * 黑洞连接正式进服（对 mod 就是一个真客户端，网络广播/跟随/治疗全部照常）。
	 * 每任务一个独立名字+UUID（任务结束送走，避免 PlayerList 冲突）；placeNewPlayer
	 * 会把它摆到世界出生点，所以随后重摆到测试区并保持无敌/食物满/不摔伤。
	 */
	private static ServerPlayer createOwner(ServerLevel level, String taskId, BlockPos areaOrigin) {
		MinecraftServer server = level.getServer();
		// 名字必须 ≤16 字符：player_info_update 的 ADD_PLAYER 广播按 16 上限编码，
		// 超长（如 E2E_place_workbench=19）会在服务端编码时抛 EncoderException，
		// 把连接中的真截图客户端整个踢下线（实测"String too big (was 19, max 16)"）。
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
		// placeNewPlayer 摆到出生点：重摆到测试区平台 + 无敌/食物满（站桩不掉血/不饿死/不摔伤）
		owner.teleportTo(areaOrigin.getX() + 0.5, areaOrigin.getY() + 1, areaOrigin.getZ() + 0.5);
		owner.getAbilities().invulnerable = true;
		owner.getFoodData().setFoodLevel(20);
		owner.getFoodData().setSaturation(20f);
		return owner;
	}

	/**
	 * 任务收尾：只清对话记忆，<b>不送走助手/主人</b>。
	 *
	 * <p>在真实存档里 {@code PlayerList.remove(假玩家)} 会在距离管理器里一次移除它
	 * 视野半径内的全部区块票券，触发 vanilla 光照引擎 {@code LeveledPriorityQueue}
	 * 的 {@code ArrayIndexOutOfBoundsException}（fastutil LongLinkedOpenHashSet 的
	 * fixPointers 拿到 -1 下标）——gametest 小世界不触发，真实世界必现。因此 e2e 不
	 * 主动移除假玩家，把它们留在各自平台上；服务器停服时用 saveAllChunks + halt
	 * 直接退出（JVM 退出，不做 PlayerList.removeAll），假玩家随进程消失。</p>
	 */
	private static void dismissAssistant(com.swaydy.opencraft.e2e.E2EContext ctx) {
		AiCompanionService.resetHistory(ctx.configBlock());
	}

	// ------------------------------------------------------------------
	// 报告
	// ------------------------------------------------------------------

	private static void report(com.swaydy.opencraft.e2e.E2EResult result) {
		log("[E2E] " + result.summaryLine());
		appendFile("[E2E] " + result.summaryLine());
	}

	private static void writeSuiteHeader() {
		appendFile("======== E2E 套件 " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()) + " ========");
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

	/** 追加到当前任务的详细日志（仅写详细日志文件，不打控制台）。 */
	private static void taskLog(String line) {
		StringBuilder sb = currentTaskLog;
		if (sb != null) {
			synchronized (sb) {
				sb.append(line).append('\n');
			}
		}
	}

	/** 把当前任务详细日志刷入套件日志文件。 */
	private static void flushTaskLog() {
		StringBuilder sb = currentTaskLog;
		if (sb != null) {
			synchronized (sb) {
				appendLogFile(sb.toString().stripTrailing());
			}
		}
	}

	/** 追加一行到套件详细日志文件（run/logs/e2e-<时间戳>.log）。 */
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

	/** 历史最后一条消息的纯文本（只取 TextBlock，剥离包装）。 */
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

	/** 把消息的块内容拼成纯文本（TextBlock 取 text，其余跳过）。 */
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

	private static String taskIds(List<com.swaydy.opencraft.e2e.E2ETask> tasks) {
		StringBuilder sb = new StringBuilder();
		for (com.swaydy.opencraft.e2e.E2ETask t : tasks) {
			if (sb.length() > 0) {
				sb.append(", ");
			}
			sb.append(t.id());
		}
		return sb.toString();
	}

	/**
	 * 关闭服务器：保存玩家/世界数据后退出 tick 循环。
	 *
	 * <p>不能直接用 {@code stopServer()}——它的 {@code PlayerList.remove} 会在真实存档
	 * 里触发光照引擎崩溃（见 {@link #dismissAssistant} 注释）。改用手动保存 +
	 * {@code halt(false)}。
	 *
	 * <p><b>注意：</b>必须在服务端线程上调用 {@code halt(false)}（作为任务调度），
	 * 不能在独立线程上调用——否则主 tick 线程的 {@code waitUntilNextTick} 在空任务队列
	 * 上调用 {@code remove()} 抛 {@code NoSuchElementException}，导致服务器崩溃退出
	 * 码非 0（嵌套构建模式下会中断整个 runE2EAll 流程）。
	 */
	private static Runnable shutdown(MinecraftServer server) {
		return () -> server.execute(() -> {
			try {
				server.getPlayerList().saveAll();
				server.saveAllChunks(true, true, false);
				server.halt(false); // running=false，tick 循环退出 → JVM 退出
			} catch (Exception e) {
				OpenCraftMod.LOGGER.warn("[OpenCraft] E2E 停服异常: {}", e.toString());
			}
		});
	}
}
