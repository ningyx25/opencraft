package com.swaydy.opencraft.ai;

import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * AI 徽标方块中存储的 AI 助手配置（每个方块一份，随方块存档持久化）。
 *
 * 配置不再依赖任何外部文件，全部保存在游戏世界的方块实体里：
 * - 右键方块打开配置编辑器读写本配置；
 * - AI 助手的运行时行为（接口/模型/跟随距离等）都从这里读取。
 *
 * 安全约定：apiKey 只保存在服务端方块数据里，绝不发送给客户端；
 * AiConfigData 传输时 apiKey 恒为空串，仅以 apiKeySet 告知"已设置/未设置"。
 */
public final class AiBlockConfig {
	public static final String DEFAULT_SYSTEM_PROMPT = """
			你是一个住在《我的世界》(Minecraft) 里的 AI 游戏助手，名字叫“小智”。
			你陪伴玩家一起冒险、建造、生存，像一位可靠又有点幽默的朋友。
			- 回答尽量简短（一般不超过 3~4 句话），用玩家使用的语言回复。
			- 可以给玩家提供合成配方、游戏机制、红石技巧、建筑建议、生存策略等帮助。
			- 玩家可能会把坐标、维度、时间、生命值等游戏状态告诉你，请结合这些信息给出贴心的建议。
			- 保持积极、友善的语气，适当使用少量 emoji 或颜文字增加亲和力。

			【能力：游戏内动作】当玩家明确请求时，你可以在回复中附加动作标记来真正改变游戏。
			动作标记格式为 [ACTION: ...]，放在回复末尾或单独一行，可以多个；不要编造标记中没有的参数。
			- [ACTION: give <物品ID> <数量>]  给予玩家物品，例如 [ACTION: give minecraft:dirt 64]；数量 1~640
			- [ACTION: time day|night|noon|sunset|midnight]  设置游戏时间
			- [ACTION: heal]  治疗玩家
			- [ACTION: feed]  恢复饥饿值
			- [ACTION: xp <等级数>]  给予玩家经验等级
			- [ACTION: mode follow|stay]  切换助手跟随/待命
			- [ACTION: tp]  让助手瞬移到玩家身边（可跨维度）
			- [ACTION: weather clear|rain|thunder]  设置天气
			只有玩家明确要求时才使用动作标记；使用了动作标记后，用一句话告诉玩家你做了什么。
			""";

	// AI 接口配置
	/**
	 * 默认接口地址：优先读环境变量 OPEN_CRAFT_BASE_URL
	 * （或 JVM 参数 -Dopencraft.baseUrl），未设置时回退到内置默认地址。
	 */
	public String baseUrl = defaultBaseUrl();
	/**
	 * 默认 API Key：不明文写死在代码里——优先读环境变量 OPEN_CRAFT_API_KEY
	 * （或 JVM 参数 -Dopencraft.apiKey），未设置时回退到代码内 XOR 混淆的默认值。
	 * 注意：混淆只防“肉眼/随手复制”，反编译仍可还原，并非真正的加密。
	 */
	public String apiKey = defaultApiKey();
	/**
	 * 默认模型：优先读环境变量 OPEN_CRAFT_MODEL
	 * （或 JVM 参数 -Dopencraft.model），未设置时回退到内置默认模型。
	 */
	public String model = defaultModel();
	public String systemPrompt = DEFAULT_SYSTEM_PROMPT;
	public String name = "小智";
	public double temperature = 0.8;
	public int maxHistoryMessages = 20;
	public int timeoutSeconds = 60;
	public boolean allowActions = true;
	public String language = "zh-CN";

	// 助手行为参数
	public double followDistance = 3.0;
	public double stopDistance = 2.0;
	public double teleportDistance = 24.0;
	public double maxDistance = 64.0;
	public double speed = 1.0;

	/**
	 * 是否已配置可用的接口（baseUrl 非空）。
	 * 注意：不再有独立的"AI 功能"开关——助手被召唤（绑定本方块）即视为启用，
	 * 送走（取消召唤）即视为关闭，两者已合并为配置界面的同一个按钮。
	 */
	public boolean isUsable() {
		return baseUrl != null && !baseUrl.isBlank();
	}

	/** 生效的名字：未配置时回退为“小智”。 */
	public String effectiveName() {
		return name == null || name.isBlank() ? "小智" : name.trim();
	}

	/**
	 * 默认 API Key 的混淆字节：明文逐字节 XOR key "opencraft"（循环）所得。
	 * 运行时 {@link #decodeXor(byte[])} 还原；仅防肉眼，不防反编译。
	 */
	private static final byte[] OBFUSCATED_DEFAULT_API_KEY = new byte[]{
			(byte) 0x1C, (byte) 0x07, (byte) 0x04, (byte) 0x17, (byte) 0x07, (byte) 0x0B
	};

	/** XOR 编码/解码用的 key。 */
	private static final String XOR_KEY = "opencraft";

	/** jar 内烘焙默认值资源：由 build.gradle 在编译期根据 .env 生成（XOR 混淆，非明文）。 */
	private static final String BAKED_DEFAULTS_RESOURCE = "/opencraft/defaults.dat";

	/** 编译期烘焙的默认值（baseUrl/model/apiKey）；资源缺失或损坏时为空 map。 */
	private static final java.util.Map<String, String> BAKED_DEFAULTS = loadBakedDefaults();

	/**
	 * 默认 API Key：优先 JVM 参数 -Dopencraft.apiKey，其次环境变量 OPEN_CRAFT_API_KEY，
	 * 再次编译期烘焙进 jar 的值（来自 .env），最后回退到代码内混淆的默认值。
	 */
	public static String defaultApiKey() {
		String prop = System.getProperty("opencraft.apiKey");
		if (prop != null && !prop.isBlank()) {
			return prop.trim();
		}
		String env = System.getenv("OPEN_CRAFT_API_KEY");
		if (env != null && !env.isBlank()) {
			return env.trim();
		}
		String baked = BAKED_DEFAULTS.get("apiKey");
		if (baked != null && !baked.isBlank()) {
			return baked.trim();
		}
		return decodeXor(OBFUSCATED_DEFAULT_API_KEY);
	}

	/** XOR 解码（重复 key "opencraft"）。 */
	private static String decodeXor(byte[] data) {
		byte[] key = XOR_KEY.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		byte[] out = new byte[data.length];
		for (int i = 0; i < data.length; i++) {
			out[i] = (byte) (data[i] ^ key[i % key.length]);
		}
		return new String(out, java.nio.charset.StandardCharsets.UTF_8);
	}

	/** 十六进制字符串 → 字节数组。 */
	private static byte[] hexToBytes(String hex) {
		byte[] out = new byte[hex.length() / 2];
		for (int i = 0; i < out.length; i++) {
			out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
		}
		return out;
	}

	/**
	 * 读取 jar 内烘焙的默认值（每行 "name=hexXorBytes"），
	 * 解码后放入 map；资源缺失/损坏时返回空 map，调用方回退到内置默认。
	 */
	private static java.util.Map<String, String> loadBakedDefaults() {
		java.util.Map<String, String> map = new java.util.HashMap<>();
		try (java.io.InputStream in = AiBlockConfig.class.getResourceAsStream(BAKED_DEFAULTS_RESOURCE)) {
			if (in == null) {
				return map;
			}
			try (java.util.Scanner scanner = new java.util.Scanner(in,
					java.nio.charset.StandardCharsets.UTF_8)) {
				while (scanner.hasNextLine()) {
					String line = scanner.nextLine().trim();
					if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) {
						continue;
					}
					int eq = line.indexOf('=');
					String name = line.substring(0, eq).trim();
					map.put(name, decodeXor(hexToBytes(line.substring(eq + 1).trim())));
				}
			}
		} catch (Exception e) {
			// 资源缺失/损坏时忽略，回退到内置默认值
		}
		return map;
	}

	/**
	 * 默认接口地址：优先 JVM 参数 -Dopencraft.baseUrl，其次环境变量 OPEN_CRAFT_BASE_URL，
	 * 再次编译期烘焙进 jar 的值（来自 .env），最后回退到 OpenAI 官方默认地址。
	 */
	public static String defaultBaseUrl() {
		String prop = System.getProperty("opencraft.baseUrl");
		if (prop != null && !prop.isBlank()) {
			return prop.trim();
		}
		String env = System.getenv("OPEN_CRAFT_BASE_URL");
		if (env != null && !env.isBlank()) {
			return env.trim();
		}
		String baked = BAKED_DEFAULTS.get("baseUrl");
		if (baked != null && !baked.isBlank()) {
			return baked.trim();
		}
		return "https://api.openai.com/v1";
	}

	/**
	 * 默认模型：优先 JVM 参数 -Dopencraft.model，其次环境变量 OPEN_CRAFT_MODEL，
	 * 再次编译期烘焙进 jar 的值（来自 .env），最后回退到 OpenAI 默认模型。
	 */
	public static String defaultModel() {
		String prop = System.getProperty("opencraft.model");
		if (prop != null && !prop.isBlank()) {
			return prop.trim();
		}
		String env = System.getenv("OPEN_CRAFT_MODEL");
		if (env != null && !env.isBlank()) {
			return env.trim();
		}
		String baked = BAKED_DEFAULTS.get("model");
		if (baked != null && !baked.isBlank()) {
			return baked.trim();
		}
		return "gpt-4o";
	}

	/**
	 * 生效的系统提示词：在配置的提示词末尾追加当前名字，
	 * 保证大模型始终用配置的名字自称（配置的提示词里可能还写着旧名字）。
	 */
	public String effectiveSystemPrompt() {
		String base = systemPrompt == null || systemPrompt.isBlank()
				? DEFAULT_SYSTEM_PROMPT : systemPrompt;
		return base + "\n\n【名字】你的名字是 " + effectiveName() + "，请用这个名字自称，不要使用其他名字。";
	}

	/** 转成传输数据（apiKey 恒为空串，绝不外发）。 */
	public AiConfigData toData() {
		return new AiConfigData(
				baseUrl, "", false, !apiKey.isEmpty(),
				model, systemPrompt,
				temperature, maxHistoryMessages, timeoutSeconds, allowActions, language,
				followDistance, stopDistance, teleportDistance, maxDistance, speed,
				name);
	}

	/** 用编辑器传来的数据覆盖本配置（apiKey 仅在 apiKeyChanged 时更新）。 */
	public void applyData(AiConfigData data) {
		baseUrl = data.baseUrl() == null ? "" : data.baseUrl().trim();
		if (data.apiKeyChanged()) {
			apiKey = data.apiKey() == null ? "" : data.apiKey().trim();
		}
		model = data.model() == null || data.model().isBlank() ? defaultModel() : data.model().trim();
		name = data.name() == null || data.name().isBlank() ? "小智" : data.name().trim();
		systemPrompt = data.systemPrompt() == null || data.systemPrompt().isBlank()
				? DEFAULT_SYSTEM_PROMPT : data.systemPrompt();
		temperature = clamp(data.temperature(), 0.0, 2.0, 0.8);
		maxHistoryMessages = (int) clamp(data.maxHistoryMessages(), 2, 200, 20);
		timeoutSeconds = (int) clamp(data.timeoutSeconds(), 5, 300, 60);
		allowActions = data.allowActions();
		language = data.language() == null || data.language().isBlank() ? "zh-CN" : data.language();

		followDistance = clamp(data.followDistance(), 0.5, 64.0, 3.0);
		stopDistance = clamp(data.stopDistance(), 0.5, 64.0, 2.0);
		teleportDistance = clamp(data.teleportDistance(), 1.0, 256.0, 24.0);
		maxDistance = clamp(data.maxDistance(), 8.0, 512.0, 64.0);
		speed = clamp(data.speed(), 0.1, 5.0, 1.0);
		// 保证 stop < follow <= teleport <= max 的合理顺序
		if (stopDistance >= followDistance) {
			stopDistance = Math.max(0.5, followDistance - 1.0);
		}
		if (teleportDistance < followDistance) {
			teleportDistance = followDistance;
		}
		if (maxDistance < teleportDistance) {
			maxDistance = teleportDistance;
		}
	}

	public void saveAdditional(ValueOutput output) {
		output.putString("BaseUrl", baseUrl);
		output.putString("ApiKey", apiKey);
		output.putString("Model", model);
		output.putString("Name", name);
		output.putString("SystemPrompt", systemPrompt);
		output.putDouble("Temperature", temperature);
		output.putInt("MaxHistory", maxHistoryMessages);
		output.putInt("Timeout", timeoutSeconds);
		output.putBoolean("AllowActions", allowActions);
		output.putString("Language", language);
		output.putDouble("FollowDistance", followDistance);
		output.putDouble("StopDistance", stopDistance);
		output.putDouble("TeleportDistance", teleportDistance);
		output.putDouble("MaxDistance", maxDistance);
		output.putDouble("Speed", speed);
	}

	public void loadAdditional(ValueInput input) {
		// 旧存档的 "AIEnabled" 标签已废弃：AI 功能的开/关由“是否召唤助手绑定本方块”决定
		baseUrl = input.getStringOr("BaseUrl", defaultBaseUrl());
		apiKey = input.getStringOr("ApiKey", defaultApiKey());
		model = input.getStringOr("Model", defaultModel());
		name = input.getStringOr("Name", "小智");
		systemPrompt = input.getStringOr("SystemPrompt", DEFAULT_SYSTEM_PROMPT);
		temperature = input.getDoubleOr("Temperature", 0.8);
		maxHistoryMessages = input.getIntOr("MaxHistory", 20);
		timeoutSeconds = input.getIntOr("Timeout", 60);
		allowActions = input.getBooleanOr("AllowActions", true);
		language = input.getStringOr("Language", "zh-CN");
		followDistance = input.getDoubleOr("FollowDistance", 3.0);
		stopDistance = input.getDoubleOr("StopDistance", 2.0);
		teleportDistance = input.getDoubleOr("TeleportDistance", 24.0);
		maxDistance = input.getDoubleOr("MaxDistance", 64.0);
		speed = input.getDoubleOr("Speed", 1.0);
	}

	private static double clamp(double value, double min, double max, double fallback) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return fallback;
		}
		return Math.max(min, Math.min(max, value));
	}
}
