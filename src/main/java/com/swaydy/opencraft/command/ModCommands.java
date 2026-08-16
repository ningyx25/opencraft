package com.swaydy.opencraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.swaydy.opencraft.ai.AiBlockConfig;
import com.swaydy.opencraft.ai.AiCompanionService;
import com.swaydy.opencraft.entity.AiAssistantEntity;
import com.swaydy.opencraft.entity.ModEntities;
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
 *   /opencraft summon               —— 召唤一个助手（自动绑定最近的、未绑定的 AI 徽标方块）
 *   /opencraft dismiss [all]        —— 送走最近的助手；加 all 送走全部助手
 *   /opencraft status               —— 列出你的全部助手与各自的配置状态
 *   /opencraft reset [all]          —— 清空最近助手的记忆；加 all 清空全部
 *   /opencraft help                 —— 显示帮助
 *
 * 多助手规则：每个 AI 徽标方块最多绑定一个助手，一个玩家可以同时拥有多个助手
 * （各绑定不同的方块）。配置只保存在游戏内的 AI 徽标方块里，没有 reload 指令。
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
						// /opencraft ask <内容...> —— 单分支贪婪参数，避免两个“都能解析”的
						// 分支在 Brigadier 里按注册顺序互相抢占（1.3.10 平局取先注册者）。
						// “ask <名字> <消息>”的指定助手解析完全在 ask() 执行器里做：
						// 开头是某个助手名字（或引号括起的“名字 (x,y,z)”）且后面还有消息时
						// 就路由到该助手，否则整段作为普通消息问“最近的”助手（原行为）。
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
				.then(Commands.literal("help")
						.executes(ctx -> help(ctx.getSource()))));
	}

	/**
	 * /opencraft ask <内容...>：默认问“最近的”助手（原行为）。
	 * 多助手时可以在开头指定助手名字：当内容的开头是“名字”（或引号括起的
	 * “名字 (x,y,z)”/“名字(x,y,z)”/“名字@x,y,z”，匹配规则见
	 * {@link ModEntities#findAssistantsBySelector}）且后面还有消息时，就精确路由到
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
			List<AiAssistantEntity> matches = ModEntities.findAssistantsBySelector(player, selector);
			if (matches.size() == 1) {
				AiAssistantEntity target = matches.get(0);
				source.sendSuccess(() -> Component.translatable(
						"command.opencraft.ask.target", target.getDisplayName()), false);
				AiCompanionService.ask(player, target, rest);
				return 1;
			}
			if (matches.size() > 1) {
				StringBuilder list = new StringBuilder();
				for (AiAssistantEntity assistant : matches) {
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
			for (AiAssistantEntity assistant : ModEntities.findAssistantsFor(player)) {
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

	private static int summon(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		if (AiCompanionService.summonFor(player) != null) {
			source.sendSuccess(() -> Component.translatable("command.opencraft.summon.success"), false);
		} else {
			source.sendFailure(Component.translatable("command.opencraft.summon.failed"));
		}
		return 1;
	}

	private static int dismiss(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		if (AiCompanionService.dismissFor(player)) {
			source.sendSuccess(() -> Component.translatable("command.opencraft.dismiss.success"), false);
		} else {
			source.sendFailure(Component.translatable("command.opencraft.dismiss.failed"));
		}
		return 1;
	}

	private static int dismissAll(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		if (AiCompanionService.dismissAllFor(player)) {
			source.sendSuccess(() -> Component.translatable("command.opencraft.dismiss.all.success"), false);
		} else {
			source.sendFailure(Component.translatable("command.opencraft.dismiss.failed"));
		}
		return 1;
	}

	private static int status(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		List<AiAssistantEntity> assistants = ModEntities.findAssistantsFor(player);
		if (assistants.isEmpty()) {
			source.sendSuccess(() -> Component.translatable("command.opencraft.status.no_block"), false);
			return 1;
		}
		source.sendSuccess(() -> Component.translatable(
				"command.opencraft.status.header", assistants.size()), false);
		for (AiAssistantEntity assistant : assistants) {
			AiBlockConfig config = assistant.getConfig();
			GlobalPos block = assistant.getConfigBlock();
			String blockLabel = block == null ? config.effectiveName()
					: config.effectiveName() + " (" + block.pos().toShortString() + ")";
			String status = String.format(
					"  [%s] 模型: %s | API Key: %s | 记忆: %d 条",
					blockLabel,
					config.model,
					config.apiKey.isEmpty() ? "未设置" : "已设置",
					AiCompanionService.historySize(block));
			source.sendSuccess(() -> Component.literal(status), false);
		}
		return 1;
	}

	private static int resetHistory(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		AiAssistantEntity assistant = ModEntities.findNearestAssistantFor(player);
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

	private static int help(CommandSourceStack source) {
		source.sendSuccess(() -> Component.translatable("command.opencraft.help"), false);
		return 1;
	}
}
