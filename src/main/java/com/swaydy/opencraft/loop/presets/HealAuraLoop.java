package com.swaydy.opencraft.loop.presets;

import com.swaydy.opencraft.assistant.player.PlayerAssistantService;
import com.swaydy.opencraft.loop.LoopCondition;
import com.swaydy.opencraft.loop.LoopContext;
import com.swaydy.opencraft.loop.LoopEvent;
import com.swaydy.opencraft.loop.LoopMonitor;
import com.swaydy.opencraft.loop.LoopModule;
import com.swaydy.opencraft.loop.LoopVerdict;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 内置循环事件最小实现：{@code heal_aura}（治疗光环）。
 *
 * <p>本包（{@code loop.presets}）集中存放内置循环事件——每个预设继承
 * {@link LoopPreset}（base SPI,见该基类）,由 {@code LoopModule.init()} 注册进
 * {@code LoopRegistry}（同 {@code plugins/presets/AssistantPlugin} 的插件管理思路）。
 *
 * <p>循环语义（{@code 触发条件 → 执行事件 → 监测条件 → 触发条件 → …}）：
 * <ul>
 * <li><b>触发条件</b>：绑定方块的主人玩家在线、存活、且生命值不满;</li>
 * <li><b>执行事件</b>：给主人恢复 1 点生命值;</li>
 * <li><b>监测函数</b>：主人仍在线存活且生命仍不满 → {@link LoopVerdict#CONTINUE} 继续下一轮;
 *     否则（满血/离线/助手没了）→ {@link LoopVerdict#STOP}——本预设为 persistent,
 *     STOP 只结束本轮,实例回到等待状态继续监视：主人再次受伤时自动再次治疗。</li>
 * </ul>
 *
 * <p>参数：每 2 秒（40 tick）一轮;不限制总迭代次数。锚点 = 绑定方块的 {@link GlobalPos}
 * （一方块一助手一循环实例）。生命周期由召唤/送走接线（见 {@code PlayerAssistantService}）。
 *
 * <p>纯逻辑全部在 lambda 闭包里（经 {@link LoopModule#server()} 取实时服务端,
 * 经 {@code ctx.anchor()} 定位绑定方块）,因此核心引擎保持纯 Java 可单测。
 */
public class HealAuraLoop extends LoopPreset {
	/** 循环事件 id（LoopRegistry 键）。 */
	public static final String ID = "heal_aura";
	/** 每轮间隔：40 tick = 2 秒。 */
	private static final int INTERVAL_TICKS = 40;
	/** 每轮恢复的生命值（半颗心 = 1 HP）。 */
	private static final float HEAL_AMOUNT = 1.0F;

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String displayName() {
		return "治疗光环";
	}

	@Override
	public String description() {
		return "当绑定方块的主人玩家生命值不满时,每 2 秒恢复 1 点生命值,直到满血（循环监测,持续守护）。";
	}

	@Override
	public LoopCondition trigger() {
		return HealAuraLoop::ownerStillHurt;
	}

	@Override
	public LoopEvent event() {
		return HealAuraLoop::healOwner;
	}

	@Override
	public LoopMonitor monitor() {
		return ctx -> ownerStillHurt(ctx) ? LoopVerdict.CONTINUE : LoopVerdict.STOP;
	}

	@Override
	public int intervalTicks() {
		return INTERVAL_TICKS;
	}

	@Override
	public boolean persistent() {
		return true;
	}

	// ------------------------------------------------------------------
	// 三个组成部分（闭包捕获 LoopModule.server() 取实时服务端）
	// ------------------------------------------------------------------

	/** 触发条件 + 监测条件的共同判定：绑定方块的主人是否"需要治疗"。 */
	private static boolean ownerStillHurt(LoopContext ctx) {
		ServerPlayer owner = ownerOf(ctx);
		return owner != null && owner.isAlive()
				&& owner.getHealth() < owner.getMaxHealth();
	}

	/** 执行事件：给主人恢复 1 点生命值。 */
	private static void healOwner(LoopContext ctx) {
		ServerPlayer owner = ownerOf(ctx);
		if (owner == null) {
			return;
		}
		owner.heal(HEAL_AMOUNT);
		com.swaydy.opencraft.logging.DebugLog.log("loop",
				"heal_aura: 治疗 {}（生命 {}/{}）",
				owner.getName().getString(),
				(float) Math.floor(owner.getHealth() * 10) / 10,
				(float) Math.floor(owner.getMaxHealth() * 10) / 10);
	}

	/** 从锚点解析绑定方块的主人玩家：无服务端/方块/绑定助手/主人在线 → null。 */
	private static ServerPlayer ownerOf(LoopContext ctx) {
		Object anchor = ctx.anchor();
		if (!(anchor instanceof GlobalPos block)) {
			return null;
		}
		MinecraftServer server = LoopModule.server();
		if (server == null) {
			return null;
		}
		ServerLevel level = server.getLevel(block.dimension());
		if (level == null) {
			return null;
		}
		com.swaydy.opencraft.assistant.AiAssistant assistant =
				PlayerAssistantService.findBoundTo(block);
		if (assistant == null) {
			return null;
		}
		UUID ownerUuid = assistant.getOwnerUuid();
		return ownerUuid == null ? null : server.getPlayerList().getPlayer(ownerUuid);
	}
}