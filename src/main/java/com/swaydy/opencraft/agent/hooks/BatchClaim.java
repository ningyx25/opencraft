package com.swaydy.opencraft.agent.hooks;

import com.swaydy.opencraft.ai.LlmClient;

/**
 * 工具批处理前的整批认领（{@link LoopHook#beforeBatch} 返回）。
 *
 * <p>仅 ask_player 用：模型常在<b>破坏性动作之前</b>用 ask_player 请求确认，因此有效提问必须
 * <b>先于</b>同批任何其它工具生效——钩子在逐 call 分派前扫描整批，若发现有效 ask_player，
 * 只处理该调用并暂停循环、跳过同批其余工具（对齐旧 runtime 的 findAskCall 短路语义）。
 *
 * @param claimed 是否认领本批
 * @param call    被认领的那次调用（ask_player）；未认领为 null
 * @param handle  认领处理结果（含结果与可选的暂停提问）
 */
public record BatchClaim(boolean claimed, LlmClient.ToolCallBlock call, ToolHandle handle) {
	/** 不认领：逐 call 正常分派。 */
	public static BatchClaim none() {
		return new BatchClaim(false, null, null);
	}

	/** 认领本批（通常是 ask_player）：只处理 {@code call}，按 {@code handle} 决定是否暂停。 */
	public static BatchClaim of(LlmClient.ToolCallBlock call, ToolHandle handle) {
		return new BatchClaim(true, call, handle);
	}
}
