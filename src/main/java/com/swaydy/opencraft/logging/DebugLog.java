package com.swaydy.opencraft.logging;

import com.swaydy.opencraft.OpenCraftMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenCraft 调试模式：把 mod 的业务日志（对话、LLM 请求/回复、工具调用、
 * 任务、拾取、配置变更等）单独写入 {@code <游戏目录>/logs/opencraft-debug.log}，
 * 方便开发测试排查问题（不依赖服务端控制台日志）。
 *
 * 日志文件为**覆盖式**：每次开启调试模式（启动参数开启 或 游戏内
 * {@code /opencraft debug on}）都会清空旧日志，只保留本次会话新写入的内容。
 *
 * 开关方式：
 * - 启动参数 {@code -Dopencraft.debug=true}（或环境变量 {@code OPEN_CRAFT_DEBUG=true}）
 *   在加载时默认开启（首次写入前清空旧日志）；
 * - 游戏内 {@code /opencraft debug on|off|status} 动态切换（需要 op 权限）。
 *
 * 安全约定：**绝不记录 API Key 等敏感信息**——埋点时只传脱敏内容
 * （LLM 请求只记 baseUrl/model，不记密钥）。
 */
public final class DebugLog {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
	/** 单次会话内日志文件超过该大小后自动从头重写（避免无限膨胀）。 */
	private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

	private static volatile boolean enabled = readInitialFlag();
	private static volatile Path logFile;
	private static final AtomicBoolean writeFailed = new AtomicBoolean(false);
	/** 本次“会话”（开启后的第一段连续写入）是否已清空过旧文件。 */
	private static final AtomicBoolean sessionCleared = new AtomicBoolean(false);

	private DebugLog() {
	}

	private static boolean readInitialFlag() {
		String fromProps = System.getProperty("opencraft.debug");
		if (fromProps != null) {
			return isTrue(fromProps);
		}
		String fromEnv = System.getenv("OPEN_CRAFT_DEBUG");
		if (fromEnv != null) {
			return isTrue(fromEnv);
		}
		return false;
	}

	private static boolean isTrue(String value) {
		String s = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
		return s.equals("true") || s.equals("1") || s.equals("on") || s.equals("yes");
	}

	/** 打开调试模式：清空旧日志（覆盖式），然后写一条开启记录。 */
	public static void enable() {
		sessionCleared.set(false); // 下一次写入前删除旧文件，从空白开始记录本次会话
		enabled = true;
		log("debug", "调试模式已开启，日志文件: {}", logFilePath());
	}

	/** 关闭调试模式（先写一条关闭记录）。 */
	public static void disable() {
		log("debug", "调试模式已关闭");
		enabled = false;
	}

	public static boolean isEnabled() {
		return enabled;
	}

	/** 当前日志文件路径（首次调用时创建 logs 目录）。 */
	public static Path logFilePath() {
		Path file = logFile;
		if (file == null) {
			try {
				Path dir = FabricLoader.getInstance().getGameDir().resolve("logs");
				Files.createDirectories(dir);
				file = dir.resolve("opencraft-debug.log");
			} catch (IOException | IllegalStateException | NoClassDefFoundError e) {
				// 无 Fabric 运行时（纯 JVM 单测里 OPEN_CRAFT_DEBUG 被误带进环境时走到这里）：
				// 退到临时目录，绝不向上抛——调试日志永远不能弄崩宿主流程。
				file = Path.of(System.getProperty("java.io.tmpdir"), "opencraft-debug.log");
			}
			logFile = file;
		}
		return file;
	}

	/**
	 * 写一条调试日志，格式 {@code [HH:mm:ss.SSS] [分类] 内容}。
	 * 调试模式关闭时 no-op；写入失败只向 SLF4J 告警一次（避免刷屏）。
	 */
	public static void log(String category, String format, Object... args) {
		if (!enabled) {
			return;
		}
		// 覆盖式：本次会话第一次写入前清空旧文件（启动参数开启的路径也会在这里触发）
		if (sessionCleared.compareAndSet(false, true)) {
			try {
				Files.deleteIfExists(logFilePath());
			} catch (IOException e) {
				OpenCraftMod.LOGGER.warn("[OpenCraft] 清理旧调试日志失败: {}", e.toString());
			}
		}
		String message = (args == null || args.length == 0) ? format : safeFormat(format, args);
		String line = "[" + LocalTime.now().format(TIME) + "] [" + category + "] " + message;
		try {
			Path file = logFilePath();
			if (Files.exists(file) && Files.size(file) > MAX_FILE_BYTES) {
				Files.deleteIfExists(file); // 单次会话内超限：从头重写
			}
			try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
				writer.write(line);
				writer.newLine();
			}
			writeFailed.set(false);
		} catch (IOException e) {
			if (writeFailed.compareAndSet(false, true)) {
				OpenCraftMod.LOGGER.warn("[OpenCraft] 调试日志写入失败: {}", e.toString());
			}
		}
	}

	/**
	 * 埋点用 SLF4J 风格的 {@code {}} 占位符；这里转成 {@code String.format} 的
	 * {@code %s} 再格式化。先把字面 {@code %} 转义成 {@code %%}，避免文本里的
	 * 百分号被误当格式符。
	 */
	private static String safeFormat(String format, Object... args) {
		try {
			String fmt = format.replace("%", "%%").replace("{}", "%s");
			return String.format(Locale.ROOT, fmt, args);
		} catch (Exception e) {
			return format + " " + Arrays.toString(args);
		}
	}
}
