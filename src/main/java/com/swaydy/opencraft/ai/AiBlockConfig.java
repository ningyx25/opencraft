package com.swaydy.opencraft.ai;

import com.mojang.serialization.Codec;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * AI 徽标方块中存储的 AI 助手配置（每个方块一份，随方块存档持久化）。
 *
 * 配置不再依赖任何外部文件，全部保存在游戏世界的方块实体里：
 * - 右键方块打开配置编辑器读写本配置；
 * - AI 助手的运行时行为（接口/模型/行动参数等）都从这里读取。
 *
 * 安全约定：apiKey 只保存在服务端方块数据里，绝不发送给客户端；
 * AiConfigData 传输时 apiKey 恒为空串，仅以 apiKeySet 告知"已设置/未设置"。
 */
public final class AiBlockConfig {
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
	public String name = "小智";
	public double temperature = 0.8;
	public int maxHistoryMessages = 20;
	public int timeoutSeconds = 60;
	public String language = "zh-CN";
	/**
	 * Agent 预设 id（"chat_agent" / "general_agent"）：只决定助手的 LLM 行为
	 * （人设、可用工具、最大行动轮数），**不决定身体形态**——助手一律是真正的
	 * ServerPlayer bot（玩家形态，像客户端一样进服）。空或未知时用默认预设。
	 */
	public String agent = "general_agent";

	// 助手行为参数（跟随/待命模式已整体移除，不再有 followDistance/stopDistance/teleportDistance）
	public double maxDistance = 64.0;
	public double speed = 1.0;

	/**
	 * 循环事件的显式启用集合（用户保存过才有效；见 {@link #loopsConfigured}）。
	 * 每个方块独立控制哪个内置循环事件（如 heal_aura 治疗光环）运行。
	 */
	public Set<String> enabledLoops = new LinkedHashSet<>();

	/**
	 * 是否已保存过循环事件配置（显式用户选择）。
	 * false（旧存档/新方块默认）= 启用所有已注册的循环事件，向后兼容；
	 * true = 只启用 {@link #enabledLoops} 中列出的 id（可为空 = 全部关闭）。
	 */
	private boolean loopsConfigured = false;

	/**
	 * 指定 id 的循环事件是否已启用。
	 * 未保存过配置时默认启用所有循环事件（向后兼容：旧存档保持原有行为）。
	 */
	public boolean isLoopEnabled(String id) {
		return !loopsConfigured || enabledLoops.contains(id);
	}

	/** 当前生效的循环事件 id 列表（未配置时 = 全部已注册循环事件，供界面渲染与传输）。 */
	public List<String> effectiveEnabledLoops() {
		if (!loopsConfigured) {
			List<String> all = new ArrayList<>();
			for (com.swaydy.opencraft.loop.LoopDefinition def
					: com.swaydy.opencraft.loop.LoopRegistry.all()) {
				all.add(def.id());
			}
			return all;
		}
		return new ArrayList<>(enabledLoops);
	}

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
	 * 转成传输数据（apiKey 恒为空串，绝不外发）。
	 * 注意：人设/能力提示词不再作为配置——由 Agent 预设（persona + 插件提示词）决定。
	 */
	public AiConfigData toData() {
		return new AiConfigData(
				baseUrl, "", false, !apiKey.isEmpty(),
				model,
				temperature, maxHistoryMessages, timeoutSeconds, language,
				maxDistance, speed,
				name, agent, effectiveEnabledLoops());
	}

	/** 用编辑器传来的数据覆盖本配置（apiKey 仅在 apiKeyChanged 时更新）。 */
	public void applyData(AiConfigData data) {
		baseUrl = data.baseUrl() == null ? "" : data.baseUrl().trim();
		if (data.apiKeyChanged()) {
			apiKey = data.apiKey() == null ? "" : data.apiKey().trim();
		}
		model = data.model() == null || data.model().isBlank() ? defaultModel() : data.model().trim();
		name = data.name() == null || data.name().isBlank() ? "小智" : data.name().trim();
		temperature = clamp(data.temperature(), 0.0, 2.0, 0.8);
		maxHistoryMessages = (int) clamp(data.maxHistoryMessages(), 2, 200, 20);
		timeoutSeconds = (int) clamp(data.timeoutSeconds(), 5, 300, 60);
		language = data.language() == null || data.language().isBlank() ? "zh-CN" : data.language();
		// 预设只决定 LLM 行为；旧存档残留的 "player_agent"（旧形态选择器）在此归一为默认
		String requestedAgent = data.agent() == null || data.agent().isBlank()
				? "general_agent" : data.agent().trim();
		agent = com.swaydy.opencraft.agent.AgentRegistry.agent(requestedAgent) != null
				? requestedAgent : "general_agent";

		// 跟随/待命模式已整体移除：followDistance/stopDistance/teleportDistance 不再使用
		maxDistance = clamp(data.maxDistance(), 8.0, 512.0, 64.0);
		speed = clamp(data.speed(), 0.1, 5.0, 1.0);

		// 循环事件开关：保存即视为显式配置（此后只启用列表中的 id，空列表 = 全部关闭）；
		// 只保留 LoopRegistry 中已注册的 id（未知 id 静默过滤）
		loopsConfigured = true;
		enabledLoops = new LinkedHashSet<>();
		List<String> raw = data.enabledLoops();
		if (raw != null) {
			for (String id : raw) {
				if (id != null && !id.isBlank()
						&& com.swaydy.opencraft.loop.LoopRegistry.def(id) != null) {
					enabledLoops.add(id);
				}
			}
		}
	}

	public void saveAdditional(ValueOutput output) {
		output.putString("BaseUrl", baseUrl);
		output.putString("ApiKey", apiKey);
		output.putString("Model", model);
		output.putString("Name", name);
		output.putDouble("Temperature", temperature);
		output.putInt("MaxHistory", maxHistoryMessages);
		output.putInt("Timeout", timeoutSeconds);
		output.putString("Language", language);
		output.putString("Agent", agent);
		output.putDouble("MaxDistance", maxDistance);
		output.putDouble("Speed", speed);
		output.putBoolean("LoopsConfigured", loopsConfigured);
		// 空集合不写列表（加载时按缺失处理，配合 LoopsConfigured 区分"全部关闭"）
		if (!enabledLoops.isEmpty()) {
			ValueOutput.TypedOutputList<String> loops = output.list("EnabledLoops", Codec.STRING);
			for (String id : enabledLoops) {
				loops.add(id);
			}
		}
	}

	public void loadAdditional(ValueInput input) {
		// 旧存档的 "AIEnabled" 标签已废弃：AI 功能的开/关由“是否召唤助手绑定本方块”决定
		baseUrl = input.getStringOr("BaseUrl", defaultBaseUrl());
		apiKey = input.getStringOr("ApiKey", defaultApiKey());
		model = input.getStringOr("Model", defaultModel());
		name = input.getStringOr("Name", "小智");
		// 旧存档的 "SystemPrompt" 标签已废弃：人设由 Agent 预设决定
		temperature = input.getDoubleOr("Temperature", 0.8);
		maxHistoryMessages = input.getIntOr("MaxHistory", 20);
		timeoutSeconds = input.getIntOr("Timeout", 60);
		language = input.getStringOr("Language", "zh-CN");
		// 旧存档的 "AllowActions" 标签已废弃（动作能力改由 Agent 预设的插件决定）
		// 旧存档的 FollowDistance/StopDistance/TeleportDistance 标签已废弃（跟随模式已移除）
		agent = input.getStringOr("Agent", "general_agent");
		maxDistance = input.getDoubleOr("MaxDistance", 64.0);
		speed = input.getDoubleOr("Speed", 1.0);
		// 循环事件开关：旧存档无该标签 → 未配置 = 所有内置循环事件启用（向后兼容）
		loopsConfigured = input.getBooleanOr("LoopsConfigured", false);
		enabledLoops = new LinkedHashSet<>();
		for (String id : input.listOrEmpty("EnabledLoops", Codec.STRING)) {
			if (id != null && !id.isBlank()) {
				enabledLoops.add(id);
			}
		}
	}

	private static double clamp(double value, double min, double max, double fallback) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return fallback;
		}
		return Math.max(min, Math.min(max, value));
	}
}
