package com.swaydy.opencraft.agent;

import java.util.Locale;

/**
 * LLM 请求重试策略（参考 deepseek-harness 的 {@code dsh-llm-retry} 插件）：
 * 对瞬时性失败（限流 / 服务端 5xx / 超时 / 网络传输错误）做指数退避 + 抖动重试，
 * 避免"网络抖一下整轮对话就报错"的糟糕体验。
 *
 * <p>纯 Java、无 Minecraft 依赖，便于 JUnit 单测。
 */
public final class LlmRetryPolicy {
	/** 最大重试次数（一次请求失败最多再试 2 次，与 dsh-llm-retry 默认一致）。 */
	public static final int MAX_RETRIES = 2;
	/** 首次重试前的初始等待（毫秒）。 */
	public static final long INITIAL_DELAY_MS = 500L;
	/** 重试等待上限（毫秒）。 */
	public static final long MAX_DELAY_MS = 10_000L;
	/** 抖动比例（±10%，避免多助手同时重试打满上游）。 */
	private static final double JITTER_RATIO = 0.1;

	private LlmRetryPolicy() {
	}

	/**
	 * 该错误是否值得重试。
	 *
	 * <p>可重试：HTTP 429（限流）、HTTP 5xx（服务端错误）、超时、连接/IO/SSL 等传输错误，
	 * 以及未知/空错误（保守按可重试处理）。不可重试：参数/鉴权类 4xx（除 429）、
	 * 响应无法解析（非瞬时）、模型侧明确拒绝等。
	 */
	public static boolean retryable(String error) {
		if (error == null || error.isBlank()) {
			return true; // 未知错误：保守地重试一次
		}
		String e = error.toLowerCase(Locale.ROOT);
		if (e.contains("http 429") || e.contains("rate limit") || e.contains("too many requests")) {
			return true;
		}
		for (int code = 500; code <= 599; code++) {
			if (e.contains("http " + code)) {
				return true;
			}
		}
		return e.contains("timeout") || e.contains("timed out")
				|| e.contains("connect") || e.contains("ioexception") || e.contains("ssl");
	}

	/**
	 * 第 {@code retry} 次（从 1 开始）重试前应等待的毫秒数：指数退避 × 抖动，
	 * 上限 {@link #MAX_DELAY_MS}。
	 */
	public static long delayMs(int retry) {
		int exponent = Math.min(Math.max(1, retry) - 1, 10);
		long exponential = Math.min(INITIAL_DELAY_MS * (1L << exponent), MAX_DELAY_MS);
		double jitter = 1.0 - JITTER_RATIO + 2.0 * JITTER_RATIO * Math.random();
		return Math.min((long) (exponential * jitter), MAX_DELAY_MS);
	}
}
