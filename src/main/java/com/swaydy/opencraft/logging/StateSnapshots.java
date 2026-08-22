package com.swaydy.opencraft.logging;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.swaydy.opencraft.OpenCraftMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 状态快照日志：把每轮注入 system 提示词的玩家状态 / 助手状态 JSON 同步落一份到
 * {@code <游戏目录>/logs/opencraft/player.json} 与 {@code logs/opencraft/assistant.json}，
 * 方便随时查看“模型本轮看到的状态”（与提示词中的数据段完全一致）。
 *
 * <p>写入约定：
 * <ul>
 * <li><b>覆盖式快照</b>：每次写入都整体替换文件（追加 {@code _updated_at} 时间戳字段、
 *     pretty-print），文件始终是一份合法且最新的 JSON,不会随轮数膨胀；</li>
 * <li><b>无条件记录</b>：与 {@link DebugLog} 的开关无关——这是给用户看的状态落盘,
 *     不是调试开关的一部分；</li>
 * <li>并发安全：多个助手同时刷新状态时用锁串行化写入；</li>
 * <li>写入失败只向 SLF4J 告警一次（避免刷屏）,失败不影响提示词组装。</li>
 * </ul>
 */
public final class StateSnapshots {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
	/** 快照目录：{@code <游戏目录>/logs/opencraft/}（首次写入时创建）。 */
	private static volatile Path dir;
	/** 串行化写入（多助手 / 工作线程并发刷新时防止交错写坏文件）。 */
	private static final Object LOCK = new Object();
	private static final AtomicBoolean writeFailed = new AtomicBoolean(false);

	private StateSnapshots() {
	}

	/** 把状态 JSON 写成 {@code logs/opencraft/<fileName>} 快照（深拷贝后追加时间戳,不改原对象）。 */
	public static void write(String fileName, JsonObject json) {
		if (json == null) {
			return;
		}
		try {
			JsonObject copy = json.deepCopy();
			copy.addProperty("_updated_at", LocalDateTime.now().format(TIME));
			String text = new GsonBuilder().setPrettyPrinting().create().toJson(copy);
			synchronized (LOCK) {
				Files.writeString(directory().resolve(fileName), text, StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
						StandardOpenOption.WRITE);
			}
			writeFailed.set(false);
		} catch (Exception e) {
			if (writeFailed.compareAndSet(false, true)) {
				OpenCraftMod.LOGGER.warn("[OpenCraft] 状态快照写入失败({}): {}", fileName, e.toString());
			}
		}
	}

	private static Path directory() throws IOException {
		Path d = dir;
		if (d == null) {
			d = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("opencraft");
			Files.createDirectories(d);
			dir = d;
		}
		return d;
	}
}
