package com.swaydy.opencraft.agent.hooks;

import com.swaydy.opencraft.ai.LlmClient;
import com.swaydy.opencraft.plugins.ToolResult;

/**
 * 一次工具执行的事实（{@link LoopHook#afterTool} 入参），供观察型钩子（重复调用守卫）使用。
 * 参考 deepseek-harness 的 {@code tools/post-execute} 事件：钩子只观察/增补上下文，不否决。
 *
 * @param call           原始工具调用（id/name/arguments）
 * @param name           规范化工具名
 * @param result         执行结果（ok/失败/deferred）
 * @param countForRepeat 是否计入重复调用链：冗余 goto（等待到达的再确认）不计、
 *                       task_plan 成功（正常更新进度）不计；其余（含失败）计入
 */
public record ToolExec(LlmClient.ToolCallBlock call, String name, ToolResult result,
                       boolean countForRepeat) {
}
