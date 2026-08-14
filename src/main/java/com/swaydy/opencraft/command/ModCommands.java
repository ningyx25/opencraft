package com.swaydy.opencraft.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
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

/**
 * /opencraft 指令树：
 *   /opencraft ask <消息...>        —— 让“最近的”AI 助手回答你（配置来自其绑定的 AI 徽标方块）
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
						.then(Commands.argument("message", StringArgumentType.greedyString())
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

	private static int ask(CommandSourceStack source, String message) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		if (message.isBlank()) {
			source.sendFailure(Component.translatable("command.opencraft.ask.blank"));
			return 0;
		}
		AiCompanionService.ask(player, message.trim());
		return 1;
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
					"  [%s] 模型: %s | AI 功能: %s | API Key: %s | 记忆: %d 条",
					blockLabel,
					config.model,
					config.aiEnabled ? "开启" : "关闭",
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
