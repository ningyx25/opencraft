package com.swaydy.opencraft.loop;

/**
 * 一个活动循环实例的只读状态快照（供 {@code /opencraft loop status} 与测试断言）。
 *
 * @param defId        所属循环定义 id
 * @param anchor       实例锚点（引擎键控;通常为绑定方块的 GlobalPos,格式化由调用方处理）
 * @param phase        当前阶段（WAITING / EXECUTING / MONITORING）
 * @param iteration    已成功执行事件的次数
 * @param nextCheckTick 下次触发条件评估的 tick（仅 WAITING 阶段有意义）
 */
public record LoopStatus(String defId, Object anchor, LoopPhase phase,
                         long iteration, long nextCheckTick) {
}