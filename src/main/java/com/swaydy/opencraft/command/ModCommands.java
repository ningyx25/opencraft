package com.swaydy.opencraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.swaydy.opencraft.agent.AgentRuntime;
import com.swaydy.opencraft.ai.AiBlockConfig;
import com.swaydy.opencraft.ai.AiCompanionService;
import com.swaydy.opencraft.assistant.AiAssistant;
import com.swaydy.opencraft.assistant.AssistantFacade;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * /opencraft 指令树：
 *   /opencraft ask <消息...>        —— 让“最近的”AI 助手回答你（配置来自其绑定的 AI 徽标方块）
 *   /opencraft ask <名字> <消息...> —— 和【指定名字】的助手对话（多助手时精确指定；Tab 可补全名字，
 *                                     同名助手用 名字(坐标) 消歧；名字不存在时回退到最近的助手）
 *   /opencraft summon               —— 召唤一个助手（自动绑定最近的、未绑定的 AI 徽标方块，按形态路由）
 *   /opencraft dismiss [all]        —— 送走最近的助手；加 all 送走全部助手
 *   /opencraft status               —— 列出你的全部助手与各自的配置状态
 *   /opencraft reset [all]          —— 清空最近助手的记忆；加 all 清空全部
 *   /opencraft interrupt            —— 中断「最近的」助手当前正在进行的任务（卡住时可立即重新提问）
 *   /opencraft help                 —— 显示帮助
 *
 * 多助手规则：每个 AI 徽标方块最多绑定一个助手（实体形态或玩家形态，见 AssistantFacade），
 * 一个玩家可以同时拥有多个助手（各绑定不同的方块）。配置只保存在游戏内的 AI 徽标方块里。
 */
public final class ModCommands {
	private ModCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register(ModCommands::registerCommands);
	}

	private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher,
	                                     net.minecraft.commands.CommandBuildContext context,
	                                     net.minecraft.commands.Commands.CommandSelection environment) {
		dispatcher.register(Commands.literal("opencraft")
				.then(Commands.literal("ask")
						.then(Commands.argument("message", StringArgumentType.greedyString())
								.suggests(ModCommands::suggestAskTargets)
								.executes(ctx -> ask(ctx.getSource(),
										StringArgumentType.getString(ctx, "message")))))
				.then(Commands.literal("summon")
						.executes(ctx -> summon(ctx.getSource())))
				.then(Commands.literal("dismiss")
						.executes(ctx -> dismiss(ctx.getSource()))
						.then(Commands.literal("all")
								.executes(ctx -> dismissAll(ctx.getSource()))))
				.then(Commands.literal("status")
						.executes(ctx -> status(ctx.getSource())))
				.then(Commands.literal("reset")
						.executes(ctx -> resetHistory(ctx.getSource()))
						.then(Commands.literal("all")
								.executes(ctx -> resetAllHistory(ctx.getSource()))))
				.then(Commands.literal("answer")
						.then(Commands.argument("message", StringArgumentType.greedyString())
								.executes(ctx -> answer(ctx.getSource(),
										StringArgumentType.getString(ctx, "message")))))
				.then(Commands.literal("interrupt")
						.executes(ctx -> interrupt(ctx.getSource())))
				.then(Commands.literal("stop")
						.executes(ctx -> interrupt(ctx.getSource())))
				.then(Commands.literal("debug")
						.executes(ctx -> debugStatus(ctx.getSource()))
						.then(Commands.literal("on")
								.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.executes(ctx -> debugSet(ctx.getSource(), true)))
						.then(Commands.literal("off")
								.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.executes(ctx -> debugSet(ctx.getSource(), false))))
				.then(Commands.literal("help")
						.executes(ctx -> help(ctx.getSource()))));
	}

	/**
	 * /opencraft ask <内容...>：默认问“最近的”助手（原行为）。
	 * 多助手时可以在开头指定助手名字：当内容的开头是“名字”（或引号括起的
	 * “名字 (x,y,z)”/“名字(x,y,z)”/“名字@x,y,z”，匹配规则见
	 * {@link AssistantFacade#findAssistantsBySelector}）且后面还有消息时，就精确路由到
	 * 该助手：
	 * - 匹配到 1 个 → 问它（先提示一句“正在询问谁”）；
	 * - 匹配到多个（同名）→ 报错并列出来，要求带坐标消歧；
	 * - 匹配不到 → 整段当成普通消息问“最近的”助手（保持旧行为，附一句提示）；
	 * - 只有一个词（或没有消息）→ 整段当成普通消息问“最近的”助手。
	 */
	private static int ask(CommandSourceStack source, String rawMessage) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		String text = rawMessage.trim();
		if (text.isEmpty()) {
			source.sendFailure(Component.translatable("command.opencraft.ask.blank"));
			return 0;
		}
		// 尝试把开头解析成“助手选择器 + 消息”：引号括起的片段，或第一个空白前的词
		String selector = null;
		String rest = null;
		if (text.startsWith("\"")) {
			int close = text.indexOf('"', 1);
			if (close > 1) {
				selector = text.substring(1, close);
				rest = text.substring(close + 1).trim();
			}
		} else {
			int space = text.indexOf(' ');
			if (space > 0) {
				selector = text.substring(0, space);
				rest = text.substring(space + 1).trim();
			}
		}
		if (selector != null && !rest.isEmpty()) {
			List<AiAssistant> matches = AssistantFacade.findAssistantsBySelector(player, selector);
			if (matches.size() == 1) {
				AiAssistant target = matches.get(0);
				source.sendSuccess(() -> Component.translatable(
						"command.opencraft.ask.target", target.getDisplayName()), false);
				AiCompanionService.ask(player, target, rest);
				return 1;
			}
			if (matches.size() > 1) {
				StringBuilder list = new StringBuilder();
				for (AiAssistant assistant : matches) {
					if (list.length() > 0) {
						list.append("，");
					}
					list.append(assistant.getDisplayName().getString());
				}
				source.sendFailure(Component.translatable("command.opencraft.ask.ambiguous", selector, list));
				return 0;
			}
			// 名字没匹配上：整段当普通消息问最近，并提示（避免玩家以为指定了却没用上）
			String notFoundSelector = selector;
			source.sendSuccess(() -> Component.translatable("command.opencraft.ask.not_found", notFoundSelector), false);
		}
		AiCompanionService.ask(player, text);
		return 1;
	}

	/**
	 * ask 的 Tab 补全：当玩家刚输入的内容是某个助手名字的前缀（含空串）时，
	 * 建议“名字 ”（补全后直接输入消息）以及带坐标的显示名（引号括起，用于同名消歧）。
	 * 只有严格前缀才补全，避免覆盖玩家已经输入完的名字和消息。
	 */
	private static CompletableFuture<Suggestions> suggestAskTargets(
			CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
		try {
			ServerPlayer player = ctx.getSource().getPlayerOrException();
			String typed = builder.getRemaining();
			for (AiAssistant assistant : AssistantFacade.findAssistantsFor(player)) {
				AiBlockConfig config = assistant.getConfig();
				String name = config == null ? "" : config.effectiveName();
				if (!name.isBlank() && name.startsWith(typed) && !name.equals(typed)) {
					builder.suggest(name + " ");
				}
				GlobalPos block = assistant.getConfigBlock();
				if (block != null && !name.isBlank()) {
					String display = "\"" + name + " (" + block.pos().toShortString() + ")\"";
					if (display.startsWith(typed) && !display.equals(typed)) {
						builder.suggest(display + " ");
					}
				}
			}
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException ignored) {
			// 非玩家源（控制台）没有可补全的助手
		}
		return builder.buildFuture();
	}

	/**
	 * /opencraft answer <回答>：回答「最近的」助手（即正在等待的那个）的 ask_player 提问，
	 * 恢复被暂停的循环。非原提问者或无待回答的提问时失败。
	 */
	private static int answer(CommandSourceStack source, String text) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		String t = text.trim();
		if (t.isEmpty()) {
			source.sendFailure(Component.translatable("command.opencraft.answer.blank"));
			return 0;
		}
		AiAssistant target = AssistantFacade.findNearestFor(player);
		GlobalPos key = target == null ? null : target.getConfigBlock();
		if (key == null || !AgentRuntime.answer(player, key, t)) {
			source.sendFailure(Component.translatable("command.opencraft.answer.none"));
			return 0;
		}
		source.sendSuccess(() -> Component.translatable("command.opencraft.answer.ok"), false);
		return 1;
	}

	/**
	 * /opencraft interrupt：中断「最近的」助手当前正在进行的任务（循环/提问/移动）。
	 * 立即释放忙锁并可马上重新提问；没有在跑的任务时失败。
	 */
	private static int interrupt(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		AiAssistant target = AssistantFacade.findNearestFor(player);
		GlobalPos key = target == null ? null : target.getConfigBlock();
		if (key == null || !AgentRuntime.interrupt(key)) {
			source.sendFailure(Component.translatable("command.opencraft.interrupt.none"));
			return 0;
		}
		source.sendSuccess(() -> Component.translatable("command.opencraft.interrupt.ok"), false);
		return 1;
	}

	private static int summon(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {		ServerPlayer player = source.getPlayerOrException();
		if (AssistantFacade.summonNearest(player) != null) {
			source.sendSuccess(() -> Component.translatable("command.opencraft.summon.success"), false);
		} else {
			source.sendFailure(Component.translatable("command.opencraft.summon.failed"));
		}
		return 1;
	}

	private static int dismiss(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		if (AssistantFacade.dismissFor(player)) {
			source.sendSuccess(() -> Component.translatable("command.opencraft.dismiss.success"), false);
		} else {
			source.sendFailure(Component.translatable("command.opencraft.dismiss.failed"));
		}
		return 1;
	}

	private static int dismissAll(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		if (AssistantFacade.dismissAllFor(player)) {
			source.sendSuccess(() -> Component.translatable("command.opencraft.dismiss.all.success"), false);
		} else {
			source.sendFailure(Component.translatable("command.opencraft.dismiss.failed"));
		}
		return 1;
	}

	private static int status(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		List<AiAssistant> assistants = AssistantFacade.findAssistantsFor(player);
		if (assistants.isEmpty()) {
			source.sendSuccess(() -> Component.translatable("command.opencraft.status.no_block"), false);
			return 1;
		}
		source.sendSuccess(() -> Component.translatable(
				"command.opencraft.status.header", assistants.size()), false);
		for (AiAssistant assistant : assistants) {
			AiBlockConfig config = assistant.getConfig();
			GlobalPos block = assistant.getConfigBlock();
			String blockLabel = block == null ? config.effectiveName()
					: config.effectiveName() + " (" + block.pos().toShortString() + ")";
			String status = String.format(
					"  [%s] 形态: %s | 模型: %s | API Key: %s | 记忆: %d 条",
					blockLabel,
					assistant.formId(),
					config.model,
					config.apiKey.isEmpty() ? "未设置" : "已设置",
					AiCompanionService.historySize(block));
			source.sendSuccess(() -> Component.literal(status), false);
		}
		return 1;
	}

	private static int resetHistory(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		AiAssistant assistant = AssistantFacade.findNearestFor(player);
		if (assistant == null) {
			source.sendFailure(Component.translatable("command.opencraft.reset.failed"));
			return 0;
		}
		AiCompanionService.resetHistory(assistant.getConfigBlock());
		source.sendSuccess(() -> Component.translatable("command.opencraft.reset.success"), false);
		return 1;
	}

	private static int resetAllHistory(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		AiCompanionService.resetAllHistory(player);
		source.sendSuccess(() -> Component.translatable("command.opencraft.reset.all.success"), false);
		return 1;
	}

	private static int debugStatus(CommandSourceStack source) {
		source.sendSuccess(() -> Component.literal(
				"调试模式: " + (com.swaydy.opencraft.debug.DebugLog.isEnabled() ? "开" : "关")
						+ " | 日志文件: " + com.swaydy.opencraft.debug.DebugLog.logFilePath()), false);
		return 1;
	}

	private static int debugSet(CommandSourceStack source, boolean on) {
		if (on) {
			com.swaydy.opencraft.debug.DebugLog.enable();
		} else {
			com.swaydy.opencraft.debug.DebugLog.disable();
		}
		source.sendSuccess(() -> Component.literal(
				"调试模式已" + (on ? "开启" : "关闭")
						+ (on ? "，日志写入: " + com.swaydy.opencraft.debug.DebugLog.logFilePath() : "")), false);
		return 1;
	}

	private static int help(CommandSourceStack source) {
		source.sendSuccess(() -> Component.translatable("command.opencraft.help"), false);
		return 1;
	}
}
