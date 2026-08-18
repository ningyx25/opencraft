package com.swaydy.opencraft.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;


/**
 * AI 配置编辑器的数据载体：与 AI 徽标方块中保存的可编辑配置一一对应（方块实体，无外部文件），
 * 通过 Gson 序列化为 JSON 在客户端/服务器之间传输。
 *
 * 安全约定：**API Key 的任何部分都不会发送给客户端**。
 * - 服务端下发时 apiKey 恒为空串，仅提供 apiKeySet（是否已配置密钥）供界面显示"已设置/未设置"；
 * - 客户端若要更换密钥：把 apiKeyChanged 置为 true 并填入新值（留空 = 清除密钥）；
 * - 否则（apiKeyChanged=false）服务端在保存时保留原密钥。
 */
public record AiConfigData(
		String baseUrl,
		String apiKey,
		boolean apiKeyChanged,
		boolean apiKeySet,
		String model,
		double temperature,
		int maxHistoryMessages,
		int timeoutSeconds,
		String language,
		double followDistance,
		double stopDistance,
		double teleportDistance,
		double maxDistance,
		double speed,
		String name,
		String agent) {

	private static final Gson GSON = new GsonBuilder().create();

	public String toJson() {
		return GSON.toJson(this);
	}

	public static AiConfigData fromJson(String json) {
		return GSON.fromJson(json, AiConfigData.class);
	}

	/** 从方块配置构建。注意：apiKey 恒为空串（不发送密钥），apiKeySet 反映是否已配置。 */
	public static AiConfigData fromConfig(AiBlockConfig config) {
		return config.toData();
	}

	/** 套用到方块配置对象。apiKey 只在 apiKeyChanged 时采用（留空 = 清除密钥）。 */
	public void applyTo(AiBlockConfig config) {
		config.applyData(this);
	}
}