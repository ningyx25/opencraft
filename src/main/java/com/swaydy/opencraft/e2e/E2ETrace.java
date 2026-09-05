package com.swaydy.opencraft.e2e;

import com.google.gson.JsonObject;
import com.swaydy.opencraft.OpenCraftMod;
import com.swaydy.opencraft.assistant.AiAssistant;
import com.swaydy.opencraft.assistant.player.AiAssistantPlayer;
import com.swaydy.opencraft.plugins.ToolResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;

/**
 * e2e 结构化事件时间线（JSONL）：任务开始、每次工具调用参数/结果、结束判定与
 * 背包快照逐行写入 {@code logs/e2e-<task>-<stamp>.jsonl}，供跑测间 diff、
 * 回归分析与 eval 数据使用。非 e2e 场景（普通游玩）所有方法均为空操作。
 *
 * <p>每行一个 JSON 对象，公共字段：{@code type}（事件类型）、{@code tMs}
 * （相对任务开始的毫秒）。文件随任务开始创建、任务结束后停止写入。</p>
 */
public final class E2ETrace {
	/** args/result 文本截断长度（防止单行无限膨胀）。 */
	private static final int MAX_ARGS = 2000;
	private static final int MAX_TEXT = 500;

	private static final class Session {
		final Path file;
		final long startMs = System.currentTimeMillis();

		Session(Path file) {
			this.file = file;
		}
	}

	private static volatile Session CURRENT;

	private E2ETrace() {
	}

	/** 开始一个任务的事件记录（服务端线程）。 */
	public static void begin(String taskId, String stamp) {
		try {
			Path path = Path.of("logs", "e2e-" + taskId + "-" + stamp + ".jsonl");
			Files.createDirectories(path.getParent());
			CURRENT = new Session(path);
		} catch (Exception e) {
			CURRENT = null;
			OpenCraftMod.LOGGER.debug("[OpenCraft] [E2E] trace 初始化失败: {}", e.toString());
		}
	}

	/** 任务结束后停止记录。 */
	public static void close() {
		CURRENT = null;
	}

	/** task_start：任务 id、给助手的指令、自然落点。 */
	public static void taskStart(String taskId, String prompt, BlockPos spawn) {
		emit("task_start", o -> {
			o.addProperty("task", taskId);
			o.addProperty("prompt", prompt);
			if (spawn != null) {
				o.addProperty("spawnX", spawn.getX());
				o.addProperty("spawnY", spawn.getY());
				o.addProperty("spawnZ", spawn.getZ());
			}
		});
	}

	/** tool_call：工具名、原始参数 JSON、结果消息/成败/延迟标志、助手坐标。 */
	public static void toolCall(String tool, String argsJson, ToolResult result, AiAssistant assistant) {
		Session s = CURRENT;
		if (s == null) {
			return;
		}
		final String args = truncate(argsJson == null ? "{}" : argsJson, MAX_ARGS);
		final boolean ok = result != null && result.ok();
		final boolean deferred = result != null && result.deferred();
		final String message = truncate(result == null ? "" : result.message(), MAX_TEXT);
		final AiAssistantPlayer p = assistant instanceof AiAssistantPlayer player ? player : null;
		emit("tool_call", o -> {
			o.addProperty("tool", tool);
			o.addProperty("args", args);
			o.addProperty("ok", ok);
			o.addProperty("deferred", deferred);
			o.addProperty("result", message);
			if (p != null) {
				o.addProperty("x", (int) p.getX());
				o.addProperty("y", (int) p.getY());
				o.addProperty("z", (int) p.getZ());
			}
		});
	}

	/**
	 * task_finish：成败/超时、耗时、历史条数、助手背包快照、最后回复、回放文件。
	 */
	public static void taskFinish(String taskId, boolean passed, boolean timedOut, long durationMs,
	                              int historySize, AiAssistantPlayer assistant,
	                              String replayFile, String message) {
		emit("task_finish", o -> {
			o.addProperty("task", taskId);
			o.addProperty("passed", passed);
			o.addProperty("timedOut", timedOut);
			o.addProperty("durationMs", durationMs);
			o.addProperty("historySize", historySize);
			o.addProperty("message", truncate(message, MAX_TEXT));
			if (replayFile != null && !replayFile.isBlank()) {
				o.addProperty("replayFile", replayFile);
			}
			if (assistant != null) {
				o.addProperty("x", (int) assistant.getX());
				o.addProperty("y", (int) assistant.getY());
				o.addProperty("z", (int) assistant.getZ());
				JsonObject inv = new JsonObject();
				for (ItemStack stack : assistant.getInventory().getNonEquipmentItems()) {
					if (stack.isEmpty()) {
						continue;
					}
					String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
					inv.addProperty(id, inv.has(id) ? inv.get(id).getAsInt() + stack.getCount() : stack.getCount());
				}
				o.add("inventory", inv);
			}
		});
	}

	private static void emit(String type, Consumer<JsonObject> fill) {
		Session s = CURRENT;
		if (s == null) {
			return;
		}
		try {
			JsonObject o = new JsonObject();
			o.addProperty("type", type);
			o.addProperty("tMs", System.currentTimeMillis() - s.startMs);
			fill.accept(o);
			synchronized (s) {
				Files.writeString(s.file, o + System.lineSeparator(), StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}
		} catch (Exception e) {
			OpenCraftMod.LOGGER.debug("[OpenCraft] [E2E] trace 写入失败: {}", e.toString());
		}
	}

	private static String truncate(String text, int max) {
		if (text == null) {
			return "";
		}
		String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
		return flat.length() <= max ? flat : flat.substring(0, max) + "…";
	}
}
