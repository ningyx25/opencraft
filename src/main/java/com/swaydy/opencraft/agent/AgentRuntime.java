package com.swaydy.opencraft.agent;

import com.google.gson.JsonObject;
import com.swaydy.opencraft.OpenCraftMod;
import com.swaydy.opencraft.ai.AiBlockConfig;
import com.swaydy.opencraft.ai.AiCompanionService;
import com.swaydy.opencraft.ai.LlmClient;
import com.swaydy.opencraft.plugins.ToolResult;
import com.swaydy.opencraft.agent.hooks.HoldDecision;
import com.swaydy.opencraft.agent.hooks.LoopHook;
import com.swaydy.opencraft.assistant.AiAssistant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Agentic loop 执行器:驱动 AI 助手走"观察 → 决策 → 行动 → 再观察"的循环。
 *
 * <p><b>架构（对齐 deepseek-harness 的核心原则）</b>:loop 驱动本身只做「调模型 → 跑工具 →
 * 把结果喂回 → 重复」这一通用机制,所有横切策略（守卫 / 核心工具 / 计划 / 向玩家提问）都是
 * 挂载在 loop 生命周期上的可插拔监听器——{@link com.swaydy.opencraft.agent.hooks.LoopHook},
 * 默认组合见 {@link com.swaydy.opencraft.agent.hooks.LoopHooks}（每次提问随 {@link LoopSession}
 * 新建一套、各自持有 per-task 状态）。新增一个守卫或核心工具 = 写一个 {@code LoopHook} 并在
 * {@code LoopHooks} 登记,无需改动本驱动。生命周期点:{@code beforeBatch}（ask 确认短路）、
 * {@code handleTool}（核心工具认领 task_plan/ask_player）、{@code afterTool}（重复守卫）、
 * {@code afterBatch}（停滞守卫）、{@code onFinalText}（终止守卫否决收尾）、{@code tools()}（贡献
 * 核心工具 schema）。
 *
 * <p>驱动仍负责的机制与策略组件:
 * - <b>LLM 请求重试</b>（{@link LlmRetryPolicy},参考 dsh-llm-retry）:限流/5xx/超时/网络抖动
 *   等瞬时失败按指数退避 + 抖动重试,不把整轮对话变成报错；
 * - <b>重复工具调用守卫</b>（{@code RepeatCallHook} 封装 {@link RepeatToolGuard},
 *   参考 dsh-repeat-tool-reminder）:连续相同工具+相同参数达到阈值时注入提醒（先温和后详细）,
 *   打断模型的重复死循环；
 *   <b>goto 轮询豁免</b>（{@link MovementQueries}）:与在途手动移动同目标的重复 goto
 *   是"等待到达的再确认"而非死循环——不计数（afterTool 标记 countForRepeat=false）,
 *   改回一条信息型结果教模型等待；
 * - <b>延迟动作结果</b>（goto/mine/place,参照 ask_player 的暂停/恢复）:工具只启动动作
 *   并返回 deferred 结果,循环暂停;移动控制器完成/中止动作时经回调注入 [Event] user 消息
 *   （到达含 Δy 提示、挖掘含背包差分）自动续轮——一轮 = 一个完整动作,模型无需轮询烧请求;
 *   超时（45~50s）兜底恢复;
 * - <b>工具结果标记与裁剪</b>（{@link ToolResultPruner},参考 dsh-compaction-tool-result-pruner）:
 *   结果统一以 [工具名 成功/失败] 开头,超长结果保头尾裁中间,上下文增长有界；
 * - <b>每轮工具调用上限</b>（参考 dsh-agent-loop 的 maxParallelToolCalls）:防止一次连发太多调用；
 * - <b>历史压缩</b>（参考 dsh-compaction-basic）:历史过长时用一次非工具 LLM 调用把最旧区段
 *   压缩成记忆摘要（&lt;compacted-summary&gt;）,比直接裁剪保留更多记忆；压缩失败自动退回裁剪。
 * - <b>向玩家提问（暂停/恢复）</b>（{@code AskPlayerHook},参考 dsh-tool-ask-user）:模型在指令含糊
 *   或行动有破坏性/不可逆影响时调用核心工具 {@code ask_player},循环暂停并向玩家提问；玩家用
 *   /opencraft answer 回答后恢复循环；超时（{@link #ASK_TIMEOUT_MS}）未答则按合理假设继续并说明。
 *   有效提问在整批工具分派前短路（确认先于动作）；暂停/恢复机制仍在本驱动（pauseForAnswer/answer）。
 * - <b>任务计划跟踪</b>（{@code TaskPlanHook},参考 dsh-tool-todo）:模型用核心
 *   工具 {@code task_plan} 维护结构化步骤清单（整单替换）,计划摘要随工具结果回显、进行中步骤
 *   进入每轮尾部 [Current State] 观测,多步任务不丢失进度（计划不进 system——保前缀缓存）。
 * - <b>停滞守卫</b>（{@code StallHook} 封装 {@link StallGuard}）:连续多轮只调纯观察工具而世界/背包
 *   无变化时注入提醒,打断"卡在观察"空转。
 * - <b>终止守卫</b>（{@code CompletionHook} 封装 {@link TaskCompletionGuard},参考 dsh agent/turn-stopping）:
 *   模型输出纯文本时校验「任务真的完成了吗」——计划有未完成步骤或异步动作仍在途则暂缓收尾（最多 2 次）:
 *   文本作为中间消息广播给玩家,注入提醒续轮——防止"一边走向目标一边说正在赶路"就停了、任务被跟随召回半途而废。
 *
 * <p>线程模型（沿用现有约定）:
 * - HTTP/SSE 读取在工作线程（{@link #EXECUTOR}）,流式增量在那边按打字机节奏 reveal；
 * - 工具执行、历史写入、聊天广播一律 {@code server.executeIfPossible} 回服务端线程；
 * - 工具执行完成后由服务端线程把「继续下一轮请求」的任务交回工作线程池；
 * - 长任务（寻路、挖掘）不在工具调用里阻塞——工具只下达指令（设置任务/Goal）,立即返回；
 *   真实结果由延迟动作机制（[Event] 消息自动续轮）或每轮刷新的 Assistant State 上下文送达；
 * - 指令收尾时若动作仍在途,跟随恢复延迟到动作空闲（pendingFollowResume,见 keepSafeState）——
 *   立即恢复会让跟随逻辑把在途动作召回（"走一半掉头回家"）。
 *
 * <p>每轮流程:
 * 1. 组装消息:system（人设:基础 + 名字 + 预设 persona + 插件提示词 + 玩家状态 + 助手状态 + 插件状态,
 *    由 {@link Prompts} 统一组装）+ 历史 + user；
 * 2. 工作线程 LlmClient.stream（带 tools）；文本 delta 走打字机 reveal；
 *    瞬时失败/空响应 → 按退避策略重试（最多 2 次）；
 * 3. 流结束:
 *    - 有 tool_calls → 回服务端线程逐个执行（结果带 [成功/失败] 标记并裁剪,
 *      重复调用守卫触发时注入提醒；追加 assistant(tool_calls) + tool(结果) 消息）→ 下一轮；
 *    - 无 tool_calls → 最终回复:写历史（historyKey 非空）+ 命令模式广播 / GUI "reply" 事件。
 * 4. 轮数超过 maxToolRounds → 把「已达最大行动步数」作为 tool 结果喂给模型做最后一轮总结。
 */
public final class AgentRuntime {
	/** 工作线程池（守护线程,与 {@link AiCompanionService} 共用约定）。 */
	private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "opencraft-agent-loop");
		t.setDaemon(true);
		return t;
	});

	/** 流式回复每次"上屏"的最小间隔（毫秒）。 */
	private static final long FLUSH_INTERVAL_MS = 80L;
	/** 流式进行中每次最多新显示的字符数（打字机步长,80ms 一次 ≈ 100 字符/秒,接近阅读速度）。 */
	private static final int LIVE_REVEAL_CHARS = 8;
	/** 收尾阶段最多再刷新的次数:流结束后剩余文本按自适应步长推完（约 5 秒内）。 */
	private static final int MAX_REVEAL_FLUSHES = 60;
	/** ask_player 等待玩家回答的超时（毫秒）,超时后按合理假设自动继续。 */
	private static final long ASK_TIMEOUT_MS = 90_000L;
	/**
	 * 所有预设共享的基础人设、名字指令、玩家/助手状态段与 system 整段组装
	 * 已集中到 {@link Prompts}（非插件提示词统一管理）。
	 */

	/** 进行中的 loop 标记:按"助手绑定的方块"键控,保证同一助手同时只有一个 loop 在跑。 */
	private static final Map<GlobalPos, Boolean> RUNNING = new ConcurrentHashMap<>();

	/**
	 * 活动 loop 的上下文（按助手绑定方块键控）:供 {@link #interrupt} 定位并取消当前循环。
	 * 正常结束时与 {@link #RUNNING} 一起清除；interrupt 时先取走、置 cancelled、立即释放 RUNNING。
	 */
	private static final Map<GlobalPos, LoopSession> LIVE = new ConcurrentHashMap<>();

	/** 正在等待玩家回答的提问（按助手绑定方块键控；answer 或超时恢复时移除）。 */
	private static final Map<GlobalPos, PendingAsk> PENDING_ASKS = new ConcurrentHashMap<>();

	/** 等待动作事件的在途异步动作（按助手绑定方块键控；事件/超时/中断时移除）。 */
	private static final Map<GlobalPos, PendingAction> PENDING_ACTIONS = new ConcurrentHashMap<>();

	/**
	 * 工具执行观察者（e2e 评测日志等用）：每轮工具执行完后回调（工具名 + 结果）。
	 * 观察者不得抛异常（回调内 try/catch 兜底）；注册/移除线程安全。
	 */
	public interface ToolListener {
		void onToolExecuted(String toolName, com.swaydy.opencraft.plugins.ToolResult result);
	}

	/** 已注册的工具观察者。 */
	private static final java.util.List<ToolListener> TOOL_LISTENERS =
			new java.util.concurrent.CopyOnWriteArrayList<>();

	/** 注册工具观察者。 */
	public static void addToolListener(ToolListener listener) {
		if (listener != null) {
			TOOL_LISTENERS.add(listener);
		}
	}

	/** 移除工具观察者。 */
	public static void removeToolListener(ToolListener listener) {
		TOOL_LISTENERS.remove(listener);
	}

	private AgentRuntime() {
	}

	/**
	 * 异步发起一次带工具循环的对话（GUI 模式或命令模式）。
	 *
	 * @param historyKey  对话记忆的键（null = 不写历史,如打招呼）
	 * @param guiBlockPos / guiDimension 非空 = GUI 模式（增量 "delta" 事件、结束 "reply" 事件,
	 *                    不广播世界聊天）；null = 命令模式（action bar + 世界聊天广播）
	 */
	public static void runAsync(ServerPlayer player, AiAssistant assistant,
	                            String question, GlobalPos historyKey,
	                            BlockPos guiBlockPos, ResourceKey<Level> guiDimension) {
		boolean gui = guiBlockPos != null && guiDimension != null;
		GlobalPos lockKey = historyKey != null ? historyKey : keyFor(assistant);
		if (RUNNING.putIfAbsent(lockKey, Boolean.TRUE) != null) {
			// 同一助手正在处理上一个问题:提示"正忙",拒绝重复提问（GUI 模式也推事件让窗口可见）
			com.swaydy.opencraft.logging.DebugLog.log("llm",
					"正忙拒绝:助手 {} 仍在处理上一条指令,玩家 {} 的新提问被丢弃: {}",
					assistant.getConfig().effectiveName(), player.getName().getString(), question);
			Component busy = Component.translatable("command.opencraft.ask.busy");
			if (gui) {
				AiCompanionService.sendGuiEvent(player, guiBlockPos, guiDimension, "error", busy);
			} else {
				player.sendSystemMessage(busy);
			}
			return;
		}
		AiBlockConfig config = assistant.getConfig();
		if (!config.isUsable()) {
			RUNNING.remove(lockKey);
			if (gui) {
				AiCompanionService.sendGuiEvent(player, guiBlockPos, guiDimension, "error",
						Component.translatable("command.opencraft.ask.no_config"));
			} else {
				player.sendSystemMessage(Component.translatable("command.opencraft.ask.no_config"));
			}
			return;
		}
		// 玩家下达指令:助手退出跟随模式,直到本次指令完成（loop 收尾）再回到跟随。
		// 放在 busy 锁与配置可用性检查之后——被"正忙"拒绝或配置不可用时不切换跟随状态。
		beginTask(assistant);
		AgentDefinition agent = AgentRegistry.resolveAgent(config);
		MinecraftServer server = player.level().getServer();
		// 一次提问 = 一个浮层会话（sessionId 按玩家递增；客户端只认最新会话,杜绝并发串扰）
		int sessionId = AiCompanionService.nextStreamSession(player);
		if (gui) {
			AiCompanionService.sendGuiEvent(player, guiBlockPos, guiDimension,
					"thinking", Component.empty());
		} else {
			player.displayClientMessage(Component.translatable("command.opencraft.ask.thinking"), true);
		}
		// 世界内浮层先显示"正在思考…"（带助手名）；第一个 delta 或工具执行状态会替换它
		AiCompanionService.streamOverlay(player, sessionId,
				chatName(assistant),
				Component.translatable("command.opencraft.ask.thinking").getString());
		// 历史过长（超过 maxHistoryMessages×2）先异步压缩再开始本轮:保留更多记忆,防止只裁剪
		if (historyKey != null
				&& HistoryCompactor.needsCompaction(
						AiCompanionService.getHistory(historyKey).size(), config.maxHistoryMessages)) {
			compactThenStart(server, player, config, agent, assistant, question,
					historyKey, lockKey, gui, guiBlockPos, guiDimension, sessionId);
			return;
		}
		startLoop(player, config, agent, assistant, question,
				historyKey, lockKey, gui, guiBlockPos, guiDimension, sessionId);
	}

	/** 浮层/状态提示里用的助手名（配置名；读不到时回退为空串）。 */
	private static String chatName(AiAssistant assistant) {
		if (assistant == null) {
			return "";
		}
		try {
			String n = assistant.getConfig().effectiveName();
			return n == null ? "" : n;
		} catch (Exception e) {
			return "";
		}
	}

	// ------------------------------------------------------------------
	// 跟随模式切换（跟随模式 = 默认；玩家下达指令后退出,指令完成回到跟随）
	// ------------------------------------------------------------------

	/**
	 * 玩家下达指令时调用:助手退出跟随模式,并停掉在途的跟随移动
	 * （否则退出后仍会朝最后一个跟随目标走）。
	 */
	private static void beginTask(AiAssistant assistant) {
		if (assistant == null) {
			return;
		}
		assistant.setFollowing(false);
		if (assistant instanceof com.swaydy.opencraft.assistant.player.AiAssistantPlayer p) {
			p.setPendingFollowResume(false); // 新指令开始:旧任务的"稍后恢复跟随"作废
			if (p.movement() != null) {
				p.movement().stop();
			}
		}
	}

	/**
	 * 指令完成时调用:助手回到跟随模式。若仍有移动/挖掘在途,先标记
	 * "动作空闲后恢复"——立即恢复会让跟随逻辑覆盖/召回在途动作
	 * （表现:助手走一半掉头回家）,由 keepSafeState 每 tick 检查空闲再恢复。
	 */
	private static void endTask(AiAssistant assistant) {
		if (assistant == null) {
			return;
		}
		if (assistant instanceof com.swaydy.opencraft.assistant.player.AiAssistantPlayer p
				&& p.movement() != null && asyncActionInFlight(p)) {
			p.setPendingFollowResume(true);
			com.swaydy.opencraft.logging.DebugLog.log("movement",
					"指令收尾但动作仍在途,跟随恢复延迟到动作空闲");
			return;
		}
		assistant.setFollowing(true);
	}

	/** 收尾一次 loop:释放忙锁 + 清理动作事件通道 + 让助手回到跟随模式。 */
	private static void finishLoop(LoopSession ctx) {
		if (ctx == null) {
			return;
		}
		// 防御性清理:正常路径 pending 在恢复/收尾前已移除,这里兜底防事件通道悬挂
		PENDING_ACTIONS.remove(ctx.lockKey);
		if (ctx.assistant instanceof com.swaydy.opencraft.assistant.player.AiAssistantPlayer p
				&& p.movement() != null) {
			p.movement().clearActionCallback();
		}
		RUNNING.remove(ctx.lockKey);
		LIVE.remove(ctx.lockKey);
		endTask(ctx.assistant);
	}

	// ------------------------------------------------------------------
	// 延迟动作结果（goto/mine/place）:动作完成事件自动续轮
	// （参照 ask_player 的暂停/恢复——工具只启动动作,循环暂停,
	//   [Event] 消息把真实结果送达后续轮,模型无需轮询烧请求）
	// ------------------------------------------------------------------

	/** 启动异步动作的工具:goto/mine/place/container_open 共用一个移动控制器,一次只能有一个在途。 */
	private static boolean isAsyncActionTool(String toolName) {
		return "player_goto".equals(toolName) || "player_mine".equals(toolName)
				|| "player_place".equals(toolName) || "player_container_open".equals(toolName);
	}

	/** 动作等待超时:goto/place 45s（含卡住传送）;mine 50s（30s 挖掘上限 + 走路 + 缓冲）。 */
	private static long actionTimeoutMs(String toolName) {
		return "player_mine".equals(toolName) ? 50_000L : 45_000L;
	}

	/**
	 * 注册一个在途异步动作:给移动控制器装完成回调,循环暂停（本轮不续轮）;
	 * 动作完成/失败/停止的事件到达时 {@link #resumeWithEvent} 自动续轮,
	 * 超时未决由 {@link #timeoutAction} 兜底恢复。服务端线程调用。
	 */
	private static void registerPendingAction(LoopSession ctx, String toolName,
	                                            JsonObject args, int round) {
		MinecraftServer server = ctx.player.level().getServer();
		PendingAction pa = new PendingAction(ctx, round + 1, server, toolName, parseXyz(args),
				"player_mine".equals(toolName) ? snapshotInventory(ctx.assistant) : null);
		PENDING_ACTIONS.put(ctx.lockKey, pa);
		ctx.pausedByAction = true;
		if (ctx.assistant instanceof com.swaydy.opencraft.assistant.player.AiAssistantPlayer p
				&& p.movement() != null) {
			p.movement().setActionCallback((text, success) -> resumeWithEvent(ctx.lockKey, text, success));
		}
		com.swaydy.opencraft.logging.DebugLog.log("tool",
				"延迟动作注册:{} 目标={},循环暂停等待 [Event]（超时 {}ms）",
				toolName, pa.target == null ? "?" : pa.target.toShortString(),
				actionTimeoutMs(toolName));
		// 注:同批后续工具(如 player_stop)把动作停掉的情况不在这里恢复——
		// 此刻 tool 结果尚未入列,提前注入 [Event] 会破坏 assistant(tool_calls) →
		// tool 结果 → 事件的顺序;由 onDone 在结果入列后检查"不在途"再恢复
		long timeout = actionTimeoutMs(toolName);
		CompletableFuture.runAsync(() -> {
			try {
				Thread.sleep(timeout);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			// 捕获 pa 实例:超时只在「当初为之调度的那个动作」仍挂在 map 里时生效
			// （旧动作的事件早已恢复、map 里已换成新动作时,陈旧超时必须 no-op——
			//  否则会误杀新动作并关掉它的事件通道,形成伪超时连锁）
			timeoutAction(ctx.lockKey, pa, server);
		}, EXECUTOR);
	}

	/**
	 * 动作事件恢复循环（移动控制器回调,服务端线程）:移除 pending（与超时/中断互斥）,
	 * 注入 {@code [Event]} user 消息后从暂停点续轮;挖掘附背包差分（捡到了什么）。
	 */
	private static void resumeWithEvent(GlobalPos key, String text, boolean success) {
		PendingAction pa = PENDING_ACTIONS.remove(key);
		if (pa == null) {
			return; // 事件已被超时/中断处理,或重复事件
		}
		LoopSession ctx = pa.ctx;
		if (ctx.cancelled) {
			return; // 已中断（RUNNING 已清,不再恢复）
		}
		if (!isAlive(ctx.assistant)) {
			RUNNING.remove(ctx.lockKey);
			LIVE.remove(ctx.lockKey);
			return;
		}
		ctx.pausedByAction = false;
		StringBuilder event = new StringBuilder("[Event] ").append(pa.toolName)
				.append(success ? " finished: " : " failed: ").append(text);
		if ("player_mine".equals(pa.toolName) && pa.inventoryBefore != null) {
			String diff = ActionEvents.inventoryDiffText(pa.inventoryBefore,
					snapshotInventory(ctx.assistant));
			event.append(diff != null ? diff : ActionEvents.noPickupYetNote());
		}
		ctx.messages.add(LlmClient.Message.user(event.toString()));
		com.swaydy.opencraft.logging.DebugLog.log("tool",
				"动作事件恢复循环:{} → {}", pa.toolName, text);
		resumeRound(ctx, pa.nextRound);
	}

	/**
	 * 超时兜底:动作迟迟没有事件（极端卡死/事件丢失）→ 以超时事件恢复,模型自行决策。
	 * 两参数 {@code remove(key, pa)}（PendingAction 引用相等）保证只处理「当初为之
	 * 调度的那个动作」——它已被事件/中断处理、或 map 里已换成新动作时,本超时 no-op,
	 * 不会误杀新动作/关掉它的事件通道（旧版按 key 删曾造成伪超时连锁）。
	 */
	private static void timeoutAction(GlobalPos key, PendingAction pa, MinecraftServer server) {
		runOnServer(server, () -> {
			if (!PENDING_ACTIONS.remove(key, pa)) {
				return; // 陈旧超时:该动作已由事件恢复/中断清理,或已被新动作替换
			}
			LoopSession ctx = pa.ctx;
			if (ctx.cancelled) {
				return;
			}
			ctx.pausedByAction = false;
			if (ctx.assistant instanceof com.swaydy.opencraft.assistant.player.AiAssistantPlayer p
					&& p.movement() != null) {
				// 事件通道关闭;移动可继续走完,模型下一条指令会覆盖或停止它
				p.movement().clearActionCallback();
			}
			String pos = ctx.assistant == null ? "?"
					: "(" + ctx.assistant.blockPosition().getX() + ","
							+ ctx.assistant.blockPosition().getY() + ","
							+ ctx.assistant.blockPosition().getZ() + ")";
			ctx.messages.add(LlmClient.Message.user(
					"[Event] " + pa.toolName + " timeout: still not finished after "
							+ (actionTimeoutMs(pa.toolName) / 1000) + "s (assistant at " + pos
							+ "). The action may be stuck — take a different approach or stop it with player_stop."));
			com.swaydy.opencraft.logging.DebugLog.log("tool", "延迟动作超时恢复:{}", pa.toolName);
			resumeRound(ctx, pa.nextRound);
		});
	}

	/** 恢复暂停的循环（ask 回答/动作事件/超时共用）:达步数上限走总结轮,否则正常下一轮。 */
	private static void resumeRound(LoopSession ctx, int nextRound) {
		if (nextRound >= ctx.agent.maxToolRounds()) {
			com.swaydy.opencraft.logging.DebugLog.log("llm",
					"循环恢复时已达最大行动轮数（{}）,进入最后一轮总结", ctx.agent.maxToolRounds());
			ctx.messages.add(LlmClient.Message.toolResult("round-limit",
					"You have reached the maximum number of action rounds for this task (" + ctx.agent.maxToolRounds() + ")."
							+ " Stop acting now and summarize in one concise sentence what you have accomplished (do not call tools).",
					true));
			LlmClient.Request summaryRequest = new LlmClient.Request(
					ctx.config.baseUrl, ctx.config.apiKey, ctx.config.model,
					ctx.system, new ArrayList<>(ctx.messages),
					null, ctx.config.temperature, null, null,
					ctx.config.timeoutSeconds);
			streamWithRetry(ctx, nextRound, summaryRequest, true);
		} else {
			runRound(ctx, nextRound);
		}
	}

	/** 助手背包快照（物品短名→数量）,挖掘动作前后差分用。 */
	private static Map<String, Integer> snapshotInventory(AiAssistant assistant) {
		Map<String, Integer> out = new java.util.TreeMap<>();
		if (!(assistant instanceof com.swaydy.opencraft.assistant.player.AiAssistantPlayer p)) {
			return out;
		}
		var inv = p.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			var s = inv.getItem(i);
			if (!s.isEmpty()) {
				out.merge(AiCompanionService.shortName(s.getItem().getDescriptionId()),
						s.getCount(), Integer::sum);
			}
		}
		return out;
	}

	/** 从工具参数取 x/y/z（日志/诊断用）;缺失/非法返回 null。 */
	private static BlockPos parseXyz(JsonObject args) {
		if (args == null || !args.has("x") || !args.has("y") || !args.has("z")) {
			return null;
		}
		try {
			return new BlockPos(args.get("x").getAsInt(), args.get("y").getAsInt(),
					args.get("z").getAsInt());
		} catch (Exception e) {
			return null;
		}
	}

	// ------------------------------------------------------------------
	// 终止守卫与异步动作在途判定
	// ------------------------------------------------------------------

	/**
	 * 玩家形态助手是否有异步动作在途：手动移动进行中（manual=true 且有目标）
	 * 或挖掘进行中。终止守卫与延迟动作机制共用。
	 * 只认手动指令——跟随逻辑产生的移动不是任务动作,不应阻止收尾。
	 */
	static boolean asyncActionInFlight(AiAssistant assistant) {
		// 单一事实来源在 LoopSession（终止守卫钩子与延迟动作机制共用）
		return LoopSession.asyncActionInFlight(assistant);
	}

	/**
	 * 终止守卫触发:把模型的文本当「中间消息」广播给玩家（进度可见）,
	 * 注入提醒并继续循环（服务端线程调用）。不写历史、不释放忙锁——
	 * 这些只有真正收尾时才做。
	 */
	private static void holdForUnfinishedTask(LoopSession ctx, String interimText,
	                                           int round, String reminder) {
		if (interimText != null && !interimText.isBlank()
				&& ctx.player.level() instanceof ServerLevel sl) {
			AiCompanionService.speakAsAssistant(sl, ctx.assistant, interimText);
		}
		ctx.messages.add(LlmClient.Message.user(reminder));
		com.swaydy.opencraft.logging.DebugLog.log("llm",
				"终止守卫:任务未完成,第 {}/{} 次暂缓收尾,注入提醒续轮",
				ctx.terminalHolds, TaskCompletionGuard.MAX_HOLDS);
		CompletableFuture.runAsync(() -> runRound(ctx, round + 1), EXECUTOR);
	}

	/**
	 * {@code player_goto} 是否与当前在途的手动移动同目标:等待到达期间重复下达
	 * 同一目标是"再确认"而非"失败死循环"——豁免重复执行与 {@link RepeatToolGuard},
	 * 由调用方改回一条信息型结果（告诉模型走路需要时间,别烧轮次）。
	 */
	private static boolean isRedundantInFlightGoto(AiAssistant assistant, String toolName,
	                                                JsonObject args) {
		if (!"player_goto".equals(toolName)
				|| !(assistant instanceof com.swaydy.opencraft.assistant.player.AiAssistantPlayer p)
				|| p.movement() == null) {
			return false;
		}
		var m = p.movement();
		Vec3 t = m.target();
		return t != null && m.isMoving() && m.isManual()
				&& MovementQueries.isSameGotoTarget(args, t.x, t.y, t.z, 1.0);
	}

	// ------------------------------------------------------------------
	// 历史压缩（参考 dsh-compaction-basic:最旧区段 → LLM 记忆摘要）
	// ------------------------------------------------------------------

	/**
	 * 异步把历史最旧区段压缩成记忆摘要,落地后开始新一轮对话。
	 * 压缩请求在工作线程执行；摘要替换/失败回退都在服务端线程落地（避免并发写历史）。
	 */
	private static void compactThenStart(MinecraftServer server, ServerPlayer player,
	                                     AiBlockConfig config, AgentDefinition agent,
	                                     AiAssistant assistant, String question,
	                                     GlobalPos historyKey, GlobalPos lockKey, boolean gui,
	                                     BlockPos guiBlockPos, ResourceKey<Level> guiDimension,
	                                     int sessionId) {
		List<LlmClient.Message> live = AiCompanionService.getHistory(historyKey);
		int keep = HistoryCompactor.keepCount(config.maxHistoryMessages);
		int oldCount = Math.max(0, live.size() - keep);
		List<LlmClient.Message> region = new ArrayList<>(live.subList(0, oldCount));
		long regionChars = HistoryCompactor.charsOf(region);
		CompletableFuture.runAsync(() -> {
			String summary = HistoryCompactor.summarize(config, agent, region);
			runOnServer(server, () -> {
				List<LlmClient.Message> current = AiCompanionService.getHistory(historyKey);
				HistoryCompactor.Outcome outcome =
						HistoryCompactor.apply(current, keep, regionChars, summary);
				if (outcome.compacted()) {
					com.swaydy.opencraft.logging.DebugLog.log("history",
							"历史压缩:方块 {} 的 {} 条旧消息 → 1 条记忆摘要（{} 字符）",
							historyKey.pos().toShortString(), outcome.dropped(), outcome.summaryLength());
				} else {
					// 压缩失败/未变短:退回直接裁剪（与旧行为一致）
					com.swaydy.opencraft.logging.DebugLog.log("history",
							"历史裁剪（压缩不可用）:方块 {} 丢弃 {} 条最旧消息",
							historyKey.pos().toShortString(), outcome.dropped());
				}
				startLoop(player, config, agent, assistant, question,
						historyKey, lockKey, gui, guiBlockPos, guiDimension, sessionId);
			});
		}, EXECUTOR);
	}

	/** 启动新的一轮对话（历史已就绪）:写 user 消息 → 组 system+历史 → 跑第 0 轮。 */
	private static void startLoop(ServerPlayer player, AiBlockConfig config, AgentDefinition agent,
	                              AiAssistant assistant, String question, GlobalPos historyKey,
	                              GlobalPos lockKey, boolean gui,
	                              BlockPos guiBlockPos, ResourceKey<Level> guiDimension,
	                              int sessionId) {
		List<LlmClient.Message> history = historyKey == null ? new ArrayList<>()
				: new ArrayList<>(AiCompanionService.getHistory(historyKey));
		// 用户消息立即写入长期历史（与旧行为一致:historySize 同步增长,工具往返不写入）
		if (historyKey != null) {
			AiCompanionService.appendHistory(historyKey, LlmClient.Message.user(question));
		}
		history.add(LlmClient.Message.user(question));
		// system 独立于消息列表（新词汇:Message 只有 USER/ASSISTANT）,随请求经 Request.system 传入
		List<LlmClient.Message> messages =
				HistoryCompactor.trimToRecent(history, config.maxHistoryMessages);

		// 注入初始动态游戏上下文（环境/玩家/助手状态），作为本轮首条 user 消息的内容。
		// 随不可变历史在多轮中保持不变，使后续轮次的 KV Cache 前缀完全稳定（Prompt Caching 命中率 90%+）。
		String initialContext = Prompts.formatGameContext(player, assistant, agent);
		if (!initialContext.isBlank() && !messages.isEmpty()) {
			int lastIdx = messages.size() - 1;
			LlmClient.Message last = messages.get(lastIdx);
			messages.set(lastIdx, LlmClient.Message.user(last.text() + "\n\n" + initialContext));
		}

		LoopSession ctx = new LoopSession(player, assistant, config, agent, messages,
				lockKey, historyKey, guiBlockPos, guiDimension, gui, sessionId);
		ctx.system = Prompts.staticSystem(config, agent);
		LIVE.put(lockKey, ctx);
		runRound(ctx, 0);
	}

	// ------------------------------------------------------------------
	// 一轮循环（带重试）
	// ------------------------------------------------------------------

	/**
	 * 发起一轮循环（LLM 请求 + 回复处理）。
	 *
	 * <p>请求准备与调度在服务端线程执行，网络请求交由工作线程异步执行。</p>
	 */
	private static void runRound(LoopSession ctx, int round) {
		if (ctx == null || ctx.cancelled) {
			return;
		}
		MinecraftServer server = ctx.player.level().getServer();
		if (server == null || server.isStopped()) {
			return;
		}
		if (server.isSameThread()) {
			doRunRound(ctx, round);
		} else {
			runOnServer(server, () -> doRunRound(ctx, round));
		}
	}

	/** 在服务端线程上执行一轮循环（静态 system 保证 + 请求发起）。 */
	private static void doRunRound(LoopSession ctx, int round) {
		if (ctx == null || ctx.cancelled) {
			// 已被中断:不再发起下一轮（RUNNING/LIVE 已由 interrupt 清理）
			return;
		}
		if (!isAlive(ctx.assistant)) {
			// loop 中助手被送走/方块被拆:静默终止
			RUNNING.remove(ctx.lockKey);
			LIVE.remove(ctx.lockKey);
			return;
		}
		// 静态 system 提示词：同预设同助手下全局稳定不变（# Identity + # Capabilities + # Skills），
		// 确保 vLLM / DeepSeek / OpenAI 等平台的前缀缓存（KV Cache）100% 命中前缀。
		if (ctx.system == null) {
			ctx.system = Prompts.staticSystem(ctx.config, ctx.agent);
		}
		// 每轮尾部轻量状态观测（仅变化时追加）:作为 [Current State] user 消息追加到消息流末尾,
		// 之前的历史保持字节稳定 → KV 前缀缓存命中延伸到全部历史;与上一条观测相同则不再追加（省 token）。
		// 第 0 轮不追加——完整 Game Context 快照已随提问消息注入。
		if (round > 0) {
			String digest = GameContext.stateDigest(ctx.player, ctx.assistant, ctx.plan);
			if (digest != null && !digest.equals(ctx.lastStateDigest)) {
				ctx.lastStateDigest = digest;
				ctx.messages.add(LlmClient.Message.user("[Current State] " + digest));
			}
		}
		// 插件工具 + 核心工具（ask_player / task_plan,随每个请求附加）→ 新词汇 ToolSchema
		// 插件工具 + 钩子贡献的核心工具 schema（task_plan / ask_player；对齐 dsh:工具由插件贡献）
		List<com.google.gson.JsonObject> toolJson = new ArrayList<>(ctx.agent.toolsJson());
		for (LoopHook hook : ctx.hooks) {
			toolJson.addAll(hook.tools());
		}
		List<LlmClient.ToolSchema> tools = new ArrayList<>();
		for (com.google.gson.JsonObject t : toolJson) {
			tools.add(LlmClient.ToolSchema.fromJson(t));
		}
		LlmClient.Request request = new LlmClient.Request(
				ctx.config.baseUrl, ctx.config.apiKey, ctx.config.model, ctx.system,
				ctx.messages, tools, ctx.config.temperature, null, null, ctx.config.timeoutSeconds);
		com.swaydy.opencraft.logging.DebugLog.log("llm",
				"第 {} 轮请求 模型={} baseUrl={} 消息数={} 工具数={} 问题={}",
				round + 1, ctx.config.model, ctx.config.baseUrl, ctx.messages.size(),
				tools.size(),
				ctx.messages.isEmpty() ? ""
						: ctx.messages.get(ctx.messages.size() - 1).text());
		streamWithRetry(ctx, round, request, false);
	}

	/**
	 * 带重试的流式请求。瞬时失败（未吐出任何字符前）或空响应（无内容无工具调用）时,
	 * 按 {@link LlmRetryPolicy} 指数退避 + 抖动重试（最多 2 次）；重试期间"正忙"锁保持持有。
	 *
	 * @param summaryRound true = 最后一轮总结（不带 tools,模型必须给文本回复）
	 */
	private static void streamWithRetry(LoopSession ctx, int round,
	                                    LlmClient.Request request, boolean summaryRound) {
		MinecraftServer server = ctx.player.level().getServer();
		// 复用在 runAsync 分配的会话号:一次提问共用一个浮层会话,避免每轮切新会话造成跳动/串扰
		int sessionId = ctx.sessionId;
		LlmClient.ChunkSink sink = new LlmClient.ChunkSink() {
			private final StringBuilder buffer = new StringBuilder();
			private final long[] lastFlushAt = {0L};
			private final int[] revealed = {0};
			private final List<LlmClient.ToolCallBlock> toolCalls = new ArrayList<>();
			private final String[] lastReasoning = {null};

			@Override
			public void onChunk(LlmClient.Chunk chunk) {
				if (chunk instanceof LlmClient.TextDelta td) {
					if (td.text() == null || td.text().isEmpty()) {
						return;
					}
					buffer.append(td.text());
					// 所有轮次的文本都实时上浮层（中间轮是助手"自言自语",最终轮是正式回复）
					maybeReveal(false);
				} else if (chunk instanceof LlmClient.ReasoningDelta rd) {
					// 记录本轮推理内容（思维链）:DeepSeek 等推理模型要求带工具调用的
					// assistant 消息回传 reasoning_content,否则下一轮请求会被 400 拒绝
					if (rd.text() != null && !rd.text().isBlank()) {
						lastReasoning[0] = lastReasoning[0] == null
								? rd.text() : lastReasoning[0] + rd.text();
					}
				} else if (chunk instanceof LlmClient.BlockEnd be && be.block() instanceof LlmClient.ToolCallBlock t) {
					// 新协议:块结束携带组装好的完整工具调用（id/name/arguments）
					toolCalls.add(t);
				} else if (chunk instanceof LlmClient.Usage u) {
					int hit = u.cacheReadTokens() == null ? 0 : u.cacheReadTokens();
					com.swaydy.opencraft.logging.DebugLog.log("llm",
							"第 {} 轮 Token 消耗: 未缓存输入={} 缓存命中={} 输出={} 思维链={}",
							round + 1, u.inputTokens(), hit, u.outputTokens(),
							u.reasoningTokens() == null ? 0 : u.reasoningTokens());
				} else if (chunk instanceof LlmClient.Finish f) {
					if (f.ok()) {
						onDone(f);
					} else {
						onFailed(f.failure());
					}
				}
			}

			/** 成功结束（含工具调用轮与最终文本轮）。 */
			private void onDone(LlmClient.Finish f) {
				String full = buffer.toString();
				boolean hasTools = !toolCalls.isEmpty();
				if (hasTools && !summaryRound) {
					// 决策立即生效（不等打字机 reveal）:工具调用 → 回服务端线程执行并继续下一轮
					runOnServer(server, () -> {
						if (ctx.cancelled) {
							// 已被中断:工具不再执行、不再续轮（RUNNING/LIVE 已由 interrupt 清理）
							return;
						}
						if (!isAlive(ctx.assistant)) {
							RUNNING.remove(ctx.lockKey);
							LIVE.remove(ctx.lockKey);
							return;
						}
						showExecuting(ctx, round, toolCalls, sessionId);
						// 追加 assistant(tool_calls) 消息（含 reasoning_content 回传）
						ctx.messages.add(assistantTurn(full, lastReasoning[0], toolCalls));
						// 统一分派整批工具:核心工具（task_plan / ask_player）由 LoopHook 认领,
						// 其余走插件工具注册表;重复/停滞守卫经 afterTool/afterBatch 钩子追加提醒,
						// ask 暂停与延迟动作注册在分派内部处理（对齐 dsh:loop 只负责调模型/跑工具）。
						ctx.messages.addAll(executeTools(ctx, toolCalls, round));
						if (ctx.pausedByAsk) {
							// ask_player 已暂停等待玩家回答:不续轮;"正忙"锁保持,由 answer/超时 恢复
							return;
						}
						if (ctx.pausedByAction) {
							// 已注册延迟动作（goto/mine/place/container_open）:暂停等待 [Event];
							// "正忙"锁保持持有,由事件/超时恢复。
							// 例外:同批后续工具(如 player_stop)已把动作停掉——此刻 tool
							// 结果已入列,立即以停止事件恢复(顺序正确),不空等超时
							if (!ctx.asyncActionInFlight()) {
								resumeWithEvent(ctx.lockKey, ActionEvents.stoppedText(), false);
							}
							return;
						}
						// 交回工作线程发起下一轮
						CompletableFuture.runAsync(() -> {
							if (round + 1 >= ctx.agent.maxToolRounds()) {
								// 已达最大轮数:把"步数已尽"作为结果喂给模型做最后一轮总结
								com.swaydy.opencraft.logging.DebugLog.log("llm",
										"已达最大行动轮数（{}）,进入最后一轮总结（第 {} 步）",
										ctx.agent.maxToolRounds(), round + 1);
								ctx.messages.add(LlmClient.Message.toolResult("round-limit",
										"You have reached the maximum number of action rounds for this task (" + ctx.agent.maxToolRounds() + ")."
												+ " Stop acting now and summarize in one concise sentence what you have accomplished (do not call tools).",
										true));
								LlmClient.Request summaryRequest = new LlmClient.Request(
										ctx.config.baseUrl, ctx.config.apiKey, ctx.config.model,
										ctx.system, new ArrayList<>(ctx.messages),
										null, ctx.config.temperature, null, null,
										ctx.config.timeoutSeconds);
								streamWithRetry(ctx, round + 1, summaryRequest, true);
							} else {
								runRound(ctx, round + 1);
							}
						}, EXECUTOR);
					});
				} else if (full.isBlank() && canRetry()) {
					// 空响应（无内容也无工具调用）:视为可重试的退化完成（EMPTY_RESPONSE）
					scheduleRetry("空响应");
				} else {
					// 无工具调用 / 最后一轮总结:这是最终文本回复
					// 0) 终止守卫钩子(非总结轮,对齐 dsh agent/turn-stopping):计划未完成或异步动作
					//    仍在途 → 暂缓收尾。文本按"中间消息"广播给玩家,注入提醒续轮(最多 MAX_HOLDS 次,
					//    不与铁了心收尾的模型对抗);held 供 reveal 收尾判断是否跳过最终 reply 事件
					final boolean[] held = {false};
					runOnServer(server, () -> {
						if (ctx.cancelled) {
							// 已被中断:不写最终回复、不释放锁（interrupt 已处理）、不广播
							return;
						}
						if (!summaryRound && round + 1 < ctx.agent.maxToolRounds()) {
							HoldDecision decision = HoldDecision.finish();
							for (LoopHook hook : ctx.hooks) {
								HoldDecision d = hook.onFinalText(ctx, full);
								if (d.hold()) {
									decision = d;
									break;
								}
							}
							if (decision.hold()) {
								ctx.terminalHolds++;
								held[0] = true;
								holdForUnfinishedTask(ctx, full, round, decision.reminder());
								return; // 不写历史、不释放忙锁、不当最终回复收尾
							}
						}
						if (ctx.historyKey != null && !full.isBlank()) {
							AiCompanionService.appendHistory(ctx.historyKey,
									LlmClient.Message.assistant(full));
						}
						// 落账即代表 LLM 工作完成:释放"正忙"锁（避免"历史已 +1 但仍被 正忙 拒绝"竞态）
						// 并让助手回到跟随模式（指令完成）。
						finishLoop(ctx);
						AiCompanionService.finishStreamReply(ctx.player, ctx.assistant, full);
					});
					// 2) 浮层/GUI 窗口的"打字机"reveal（纯展示,独立异步任务,不阻塞 SSE 读取线程）:
					//    期间浮层逐字出现、窗口收 delta；reveal 收尾时浮层发 done、
					//    GUI 窗口回传最终 "reply" 事件（把流式气泡替换为完整文本）
					CompletableFuture.runAsync(() -> {
						while (!ctx.cancelled && revealed[0] < buffer.length()) {
							maybeReveal(true);
							if (revealed[0] >= buffer.length()) {
								break;
							}
							try {
								Thread.sleep(FLUSH_INTERVAL_MS);
							} catch (InterruptedException e) {
								Thread.currentThread().interrupt();
								break;
							}
						}
						runOnServer(server, () -> {
							if (ctx.cancelled) {
								return;
							}
							// 兜底:保证浮层一定收到 done（含空回复 / 收尾 reveal 已标记的重复发送）
							AiCompanionService.finishOverlay(ctx.player, sessionId,
									chatName(ctx.assistant), full);
							// 终止守卫暂缓收尾时任务仍在进行:GUI 不发最终 "reply"
							// （下一轮的 "thinking" 事件会接上）,只有真正收尾才替换为完整回复
							if (ctx.gui && !held[0]) {
								AiCompanionService.finishGuiReply(ctx.player, ctx.guiBlockPos,
										ctx.guiDimension, full);
							}
						});
					}, EXECUTOR);
				}
			}

			/** 失败结束（含 HTTP 错误/STALLED/EMPTY_RESPONSE 等）。 */
			private void onFailed(LlmClient.LlmFailure failure) {
				LlmClient.LlmFailure fail = failure == null
						? LlmClient.LlmFailure.of("未知错误", LlmClient.Codes.TRANSPORT) : failure;
				String reason = fail.message() == null || fail.message().isBlank()
						? fail.code() : fail.message();
				// 尚未吐出任何字符且错误可重试:按退避策略重试（已吐字再重试会重复显示内容）；
				// EMPTY_RESPONSE（退化完成）也按可重试处理
				if (buffer.length() == 0 && canRetry() && LlmRetryPolicy.retryable(fail)) {
					scheduleRetry(reason);
					return;
				}
				com.swaydy.opencraft.logging.DebugLog.log("llm",
						"LLM 请求失败（模型 {}）: {}", ctx.config.model, reason);
				runOnServer(server, () -> {
					if (ctx.cancelled) {
						// 已被中断:不报错、不广播（interrupt 已反馈）
						return;
					}
					// 报错即本次指令结束:任务已死,停掉在途移动/挖掘并关事件通道
					// （否则 bot 会继续走完上一次的异步动作,再被跟随召回,行为不可解）
					PENDING_ACTIONS.remove(ctx.lockKey);
					if (ctx.assistant instanceof com.swaydy.opencraft.assistant.player.AiAssistantPlayer p
							&& p.movement() != null) {
						p.movement().clearActionCallback();
						p.movement().stop();
						p.movement().cancelMining();
					}
					// 报错即本次指令结束:释放忙锁并让助手回到跟随模式
					finishLoop(ctx);
					Component err = Component.translatable("command.opencraft.ask.error", reason);
					if (ctx.gui) {
						AiCompanionService.sendGuiEvent(ctx.player, ctx.guiBlockPos, ctx.guiDimension,
								"error", err);
					} else {
						ctx.player.sendSystemMessage(err);
					}
					// 报错即本轮结束:清掉可能还挂着的"正在行动（第 N/M 步…）"世界内浮层,
					// 换成本轮的失败提示（否则该浮层会残留迷惑玩家）
					AiCompanionService.finishOverlay(ctx.player, ctx.sessionId,
							chatName(ctx.assistant), err.getString());
				});
			}

			private boolean canRetry() {
				return ctx.llmRetries[0] < LlmRetryPolicy.MAX_RETRIES;
			}

			/** 按退避策略在 {@link #EXECUTOR} 上延迟重试同一轮请求（重试期间锁保持持有）。 */
			private void scheduleRetry(String reason) {
				int attempt = ctx.llmRetries[0] + 1;
				ctx.llmRetries[0] = attempt;
				long delay = LlmRetryPolicy.delayMs(attempt);
				com.swaydy.opencraft.logging.DebugLog.log("llm",
						"第 {} 轮请求失败（{}）,{}ms 后第 {}/{} 次重试", round + 1, reason, delay,
						attempt, LlmRetryPolicy.MAX_RETRIES);
				CompletableFuture.runAsync(() -> {
					try {
						Thread.sleep(delay);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return; // 被中断:不再重试（进程/服务器正在停止,RUNNING 由 shutdown 清理）
					}
					runOnServer(server, () -> {
						if (ctx.cancelled) {
							// 已被中断:不再重试上一轮（RUNNING/LIVE 已由 interrupt 清理）
							return;
						}
						if (!isAlive(ctx.assistant)) {
							RUNNING.remove(ctx.lockKey);
							LIVE.remove(ctx.lockKey);
							return;
						}
						streamWithRetry(ctx, round, request, summaryRound);
					});
				}, EXECUTOR);
			}

			/** 按节流节奏推进"已显示"位置并推送快照（世界内浮层 + GUI 窗口 "delta"）。 */
			private void maybeReveal(boolean finalizing) {
				long now = System.currentTimeMillis();
				if (!finalizing && now - lastFlushAt[0] < FLUSH_INTERVAL_MS) {
					return; // 时间未到:等下一个 delta 或收尾阶段再推
				}
				int len = buffer.length();
				if (revealed[0] >= len) {
					return;
				}
				int step = finalizing
						? Math.max(LIVE_REVEAL_CHARS,
								(len - revealed[0] + MAX_REVEAL_FLUSHES - 1) / MAX_REVEAL_FLUSHES)
						: LIVE_REVEAL_CHARS;
				int target = Math.min(len, revealed[0] + step);
				revealed[0] = target;
				lastFlushAt[0] = now;
				String snapshot = buffer.substring(0, target);
				boolean last = finalizing && target >= len;
				runOnServer(server, () -> {
					if (ctx.cancelled) {
						// 已被中断:不再向浮层/窗口推增量（interrupt 已用"已中断"收尾浮层）
						return;
					}
					if (ctx.gui) {
						AiCompanionService.sendGuiEvent(ctx.player, ctx.guiBlockPos, ctx.guiDimension,
								"delta", Component.literal(snapshot));
					}
					// 所有入口的流式都上到世界内浮层（多行可换行、可见完整内容、带助手名）:
					// 收尾最后一帧用 done 标记,客户端去掉光标并开始淡出
					if (last) {
						AiCompanionService.finishOverlay(ctx.player, sessionId,
								chatName(ctx.assistant), snapshot);
					} else {
						AiCompanionService.streamOverlay(ctx.player, sessionId,
								chatName(ctx.assistant), snapshot);
					}
				});
			}
		};
		CompletableFuture.runAsync(() -> LlmClient.stream(request, sink), EXECUTOR);
	}

	/** 组装一条带工具调用（可能含推理）的 assistant 消息（新词汇:Text+Reasoning+ToolCall 块）。 */
	private static LlmClient.Message assistantTurn(String text, String reasoning,
	                                               List<LlmClient.ToolCallBlock> calls) {
		List<LlmClient.Block> blocks = new ArrayList<>();
		if (text != null && !text.isEmpty()) {
			blocks.add(new LlmClient.TextBlock(text));
		}
		if (reasoning != null && !reasoning.isBlank()) {
			blocks.add(new LlmClient.ReasoningBlock(reasoning));
		}
		blocks.addAll(calls);
		return LlmClient.Message.assistant(blocks);
	}

	// ------------------------------------------------------------------
	// 工具执行
	// ------------------------------------------------------------------

	/**
	 * 在服务端线程分派一批工具调用：委托给 {@link ToolExecutor}（受守卫的工具执行管线，
	 * 对齐 dsh {@code tools/} 包）。管线本身只负责跑工具；ask 暂停 / 延迟动作注册 / 移动控制器
	 * 查询 / 观察者回调等 loop 协调副作用经 {@link #TOOL_HOST} 接回本类的状态机。
	 */
	private static List<LlmClient.Message> executeTools(LoopSession ctx,
	                                                    List<LlmClient.ToolCallBlock> calls, int round) {
		return ToolExecutor.executeBatch(ctx, calls, round, TOOL_HOST);
	}

	/** 工具管线 → loop 协调：把管线需要的副作用接回 ask/延迟动作/观察者状态机。 */
	private static final ToolExecutor.Host TOOL_HOST = new ToolExecutor.Host() {
		@Override
		public void pauseForAnswer(LoopSession ctx, String question, int nextRound) {
			AgentRuntime.pauseForAnswer(ctx, question, nextRound);
		}

		@Override
		public void registerPendingAction(LoopSession ctx, String toolName, JsonObject args, int round) {
			AgentRuntime.registerPendingAction(ctx, toolName, args, round);
		}

		@Override
		public boolean isRedundantInFlightGoto(com.swaydy.opencraft.assistant.AiAssistant assistant,
		                                       String toolName, JsonObject args) {
			return AgentRuntime.isRedundantInFlightGoto(assistant, toolName, args);
		}

		@Override
		public boolean isAsyncActionTool(String toolName) {
			return AgentRuntime.isAsyncActionTool(toolName);
		}

		@Override
		public void notifyToolExecuted(String toolName, ToolResult result) {
			AgentRuntime.notifyToolExecuted(toolName, result);
		}
	};

	/** 通知工具观察者（e2e 评测日志等）；观察者异常不影响主流程。 */
	private static void notifyToolExecuted(String toolName, ToolResult result) {
		for (ToolListener l : TOOL_LISTENERS) {
			try {
				l.onToolExecuted(toolName, result);
			} catch (Exception ignored) {
				// 观察者异常不影响主流程
			}
		}
	}

	/** 向玩家提问并暂停循环:记录待回答状态、向玩家呈现、安排超时自动继续。 */
	private static void pauseForAnswer(LoopSession ctx, String question, int nextRound) {
		GlobalPos key = ctx.lockKey;
		if (PENDING_ASKS.containsKey(key)) {
			// 已经在等待上一个问题的回答:不应再重复（模型同批只发了一个 ask_player）
			return;
		}
		ctx.pausedByAsk = true;
		PENDING_ASKS.put(key, new PendingAsk(ctx, nextRound,
				(ctx.player.level() instanceof ServerLevel sl) ? sl.getServer() : ctx.player.level().getServer(),
				question));
		// 玩家侧呈现
		if (ctx.gui) {
			AiCompanionService.sendGuiEvent(ctx.player, ctx.guiBlockPos, ctx.guiDimension,
					"reply", Component.translatable("command.opencraft.ask.question", question));
		} else {
			AiCompanionService.speakAsAssistant((ServerLevel) ctx.player.level(), ctx.assistant,
					"想确认一下:" + question + "（回复 /opencraft answer <你的回答> 继续）");
		}
		com.swaydy.opencraft.logging.DebugLog.log("ask",
				"助手（方块 {}）向玩家提问,等待回答（超时 {}ms）:{}",
				key.pos().toShortString(), ASK_TIMEOUT_MS, question);
		// 超时自动继续（玩家已回答时 resumePending 取不到条目,自然 no-op）
		CompletableFuture.runAsync(() -> {
			try {
				Thread.sleep(ASK_TIMEOUT_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			timeoutPending(key);
		}, EXECUTOR);
	}

	/**
	 * 玩家通过 /opencraft answer 回答等待中的提问（服务端线程调用）。
	 * 返回 true 表示恢复成功；没有待回答的提问/非本人返回 false。
	 */
	public static boolean answer(ServerPlayer player, GlobalPos key, String text) {
		if (key == null || text == null || text.isBlank() || player == null) {
			return false;
		}
		PendingAsk pa = PENDING_ASKS.get(key);
		if (pa == null) {
			return false;
		}
		if (!pa.ctx.player.getUUID().equals(player.getUUID())) {
			return false; // 只有提问的助手的主人才有权限回答
		}
		if (PENDING_ASKS.remove(key, pa)) {
			continueAfterAnswer(pa, "[Player's answer] " + text.trim());
			return true;
		}
		return false;
	}

	/** 超时:玩家没在时限内回答,按合理假设继续（服务端线程可能在 worker 上触发）。 */
	private static void timeoutPending(GlobalPos key) {
		PendingAsk pa = PENDING_ASKS.remove(key);
		if (pa == null) {
			return;
		}
		runOnServer(pa.server, () -> continueAfterAnswer(pa,
				"(The player did not answer \"" + pa.question + "\" within the time limit — continue based on the most reasonable assumption "
						+ "and state your assumption in your final reply.)"));
	}

	/** 把玩家回答写入对话并恢复循环（服务端线程）。 */
	private static void continueAfterAnswer(PendingAsk pa, String userMessage) {
		LoopSession ctx = pa.ctx;
		if (ctx == null || ctx.cancelled) {
			// 已被中断:不再恢复循环（RUNNING 已由 interrupt 释放）
			return;
		}
		if (!isAlive(ctx.assistant)) {
			RUNNING.remove(ctx.lockKey);
			LIVE.remove(ctx.lockKey);
			return;
		}
		ctx.pausedByAsk = false;
		ctx.messages.add(LlmClient.Message.user(userMessage));
		com.swaydy.opencraft.logging.DebugLog.log("ask",
				"助手（方块 {}）的提问已恢复:{}", ctx.lockKey.pos().toShortString(),
				userMessage.length() <= 60 ? userMessage : userMessage.substring(0, 60) + "…");
		// 恢复循环:达到步数上限走总结轮,否则正常下一轮
		resumeRound(ctx, pa.nextRound);
	}

	/**
	 * 指定助手当前是否正忙（正在处理 agentic loop 任务）。
	 * 供 E2E 测试模块轮询任务完成状态。
	 */
	public static boolean isBusy(GlobalPos key) {
		return key != null && RUNNING.containsKey(key);
	}

	/**
	 * 中断指定 AI 徽标方块助手的当前循环（服务端线程调用,来自 /opencraft interrupt 命令
	 * 或配置界面「中断」按钮）:
	 * - 置 {@code cancelled}（其后所有异步回调都会失效:不广播、不续轮、不误删新 loop 的忙锁）；
	 * - 清掉正在等待的 ask_player 提问（超时任务稍后取不到条目自动 no-op）；
	 * - 停掉玩家形态助手的移动（"走一半"的动作停下来）；
	 * - **立即释放忙锁**（RUNNING/LIVE 清除）,玩家可马上重新提问；
	 * - 给主人反馈"已中断"并清理世界内浮层；历史补一条"被玩家中断"的记录保持上下文连贯。
	 *
	 * @return true = 确实中断了一个正在进行的任务；false = 没有在跑的任务
	 */
	public static boolean interrupt(GlobalPos key) {
		if (key == null || !RUNNING.containsKey(key)) {
			return false;
		}
		LoopSession ctx = LIVE.remove(key);
		if (ctx == null) {
			// 理论不发生:有 RUNNING 就该有 LIVE；防御性兜底
			RUNNING.remove(key);
			return false;
		}
		ctx.cancelled = true;
		PENDING_ASKS.remove(key);
		PENDING_ACTIONS.remove(key);
		if (ctx.assistant instanceof com.swaydy.opencraft.assistant.player.AiAssistantPlayer p
				&& p.movement() != null) {
			p.movement().clearActionCallback(); // 先关事件通道,stop() 不再触发恢复
			p.movement().stop();
		}
		RUNNING.remove(key);
		// 中断即本次指令结束:助手回到跟随模式（它会从当前位置走回主人身边）
		endTask(ctx.assistant);
		if (ctx.historyKey != null) {
			AiCompanionService.appendHistory(ctx.historyKey,
					LlmClient.Message.assistant("(The previous task was interrupted by the player and left unfinished)"));
		}
		Component done = Component.translatable("command.opencraft.interrupt.ok");
		if (ctx.gui) {
			AiCompanionService.sendGuiEvent(ctx.player, ctx.guiBlockPos, ctx.guiDimension,
					"reply", done);
		} else {
			ctx.player.sendSystemMessage(done);
		}
		AiCompanionService.finishOverlay(ctx.player, ctx.sessionId,
				chatName(ctx.assistant), done.getString());
		com.swaydy.opencraft.logging.DebugLog.log("interrupt",
				"方块 {} 的助手任务被玩家中断", key.pos().toShortString());
		return true;
	}

	// ------------------------------------------------------------------
	// 杂项
	// ------------------------------------------------------------------

	/** 展示"正在执行":第 N/M 步 + 本轮调用的工具名（GUI "thinking" 事件 / 命令模式系统消息 /
	 *  世界内浮层同步状态）。 */
	private static void showExecuting(LoopSession ctx, int round, List<LlmClient.ToolCallBlock> calls,
	                                  int sessionId) {
		String names = calls.stream().map(LlmClient.ToolCallBlock::name)
				.filter(n -> n != null && !n.isBlank())
				.distinct().limit(3).collect(Collectors.joining(", "));
		Component msg = Component.translatable("command.opencraft.agent.executing",
				round + 1, ctx.agent.maxToolRounds(), names);
		if (ctx.gui) {
			AiCompanionService.sendGuiEvent(ctx.player, ctx.guiBlockPos, ctx.guiDimension,
					"thinking", msg);
		} else {
			ctx.player.sendSystemMessage(msg);
		}
		// 世界内浮层同步展示"正在行动（第 N/M 步…）",让整个交互集中在一个位置
		AiCompanionService.streamOverlay(ctx.player, sessionId,
				chatName(ctx.assistant), msg.getString());
	}

	private static boolean isAlive(AiAssistant assistant) {
		return assistant != null && !assistant.isRemoved() && assistant.isAlive()
				&& assistant.getConfigBlock() != null;
	}

	private static GlobalPos keyFor(AiAssistant assistant) {
		GlobalPos block = assistant.getConfigBlock();
		return block != null ? block
				: GlobalPos.of(assistant.level().dimension(), assistant.blockPosition());
	}

	private static void runOnServer(MinecraftServer server, Runnable task) {
		try {
			server.executeIfPossible(task);
		} catch (Exception e) {
			OpenCraftMod.LOGGER.warn("[OpenCraft] 无法调度到服务端线程: {}", e.toString());
		}
	}

	/** 一次待回答的提问:持有恢复循环所需的全部状态。 */
	private static final class PendingAsk {
		final LoopSession ctx;
		final int nextRound; // 玩家回答后从第几轮恢复（= 提问那一轮的下一轮）
		final MinecraftServer server;
		final String question;

		PendingAsk(LoopSession ctx, int nextRound, MinecraftServer server, String question) {
			this.ctx = ctx;
			this.nextRound = nextRound;
			this.server = server;
			this.question = question;
		}
	}

	/** 一个等待事件的在途异步动作（goto/mine/place）:持有恢复循环所需的全部状态。 */
	private static final class PendingAction {
		final LoopSession ctx;
		final int nextRound; // 事件到达后从第几轮恢复（= 启动动作那一轮的下一轮）
		final MinecraftServer server;
		final String toolName; // player_goto / player_mine / player_place
		final BlockPos target; // 动作目标（日志/诊断;参数异常时 null）
		final Map<String, Integer> inventoryBefore; // player_mine 掉落差分快照（其他工具 null）

		PendingAction(LoopSession ctx, int nextRound, MinecraftServer server, String toolName,
		              BlockPos target, Map<String, Integer> inventoryBefore) {
			this.ctx = ctx;
			this.nextRound = nextRound;
			this.server = server;
			this.toolName = toolName;
			this.target = target;
			this.inventoryBefore = inventoryBefore;
		}
	}

	/** 服务器停止时关闭线程池并清理运行标记（由 {@link AiCompanionService#init()} 的钩子一并调用）。 */
	public static void shutdown() {
		RUNNING.clear();
		LIVE.clear();
		PENDING_ASKS.clear();
		PENDING_ACTIONS.clear();
		EXECUTOR.shutdown();
	}
}
