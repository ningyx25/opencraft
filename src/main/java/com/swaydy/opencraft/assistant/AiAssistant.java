package com.swaydy.opencraft.assistant;

import com.swaydy.opencraft.ai.AiBlockConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * AI 助手的统一抽象，覆盖两种“身体”形态：
 * - {@code player}：真正的 ServerPlayer 假玩家（{@link com.swaydy.opencraft.assistant.player.AiAssistantPlayer}），
 *   像多人联机客户端一样进服，拥有完整玩家背包/游戏模式/玩家式动作——**当前唯一的助手形态**；
 * - {@code entity}：PathfinderMob 底座（{@link com.swaydy.opencraft.entity.AiAssistantEntity}），
 *   仅旧存档遗留兼容。
 *
 * Agentic loop、对话、历史、命令、界面只依赖这里的“身体无关”方法；
 * 身体专属能力（背包格数、任务、Goal）由各形态自己的实现/插件提供。
 *
 * <p><b>重映射注意事项</b>：凡是委托 {@link Entity} 实现的方法（{@link #level()}/
 * {@link #isAlive()}/{@link #isRemoved()}/{@link #blockPosition()}/{@link #getDisplayName()}）
 * 一律写成 {@code default} 方法并以 {@code (Entity) this} 委托——不能写成抽象方法让实体类继承：
 * Loom 把模组代码重映射到中间名（intermediary）时，接口里与 Minecraft 类同名的抽象方法
 * 可能被映射成与实体类继承方法不一致的符号，生产环境会抛
 * {@link java.lang.AbstractMethodError}（dev/mojmap 命名下一切正常，gametest 测不出来；
 * 实测 1.21.11 的 isAlive/isRemoved/blockPosition 会踩中）。default 方法在接口里持有
 * 确切的方法引用，重映射时按 owner（Entity）正确改写，两端永远对齐。
 */
public interface AiAssistant {
	/** 当前生效的 AI 配置（优先取绑定方块的配置，否则默认值）。 */
	AiBlockConfig getConfig();

	/** 绑定的 AI 徽标方块（配置来源）；无绑定返回 null。 */
	GlobalPos getConfigBlock();

	/** 主人 UUID（无主时为 null）。 */
	UUID getOwnerUuid();

	/** 助手所在维度/世界（委托实体实现，见类注释的重映射说明）。 */
	default Level level() {
		return ((Entity) this).level();
	}

	/** 是否存活（委托实体实现，见类注释的重映射说明）。 */
	default boolean isAlive() {
		return ((Entity) this).isAlive();
	}

	/** 是否已移除（委托实体实现，见类注释的重映射说明）。 */
	default boolean isRemoved() {
		return ((Entity) this).isRemoved();
	}

	/** 脚底方块坐标（委托实体实现，见类注释的重映射说明）。 */
	default BlockPos blockPosition() {
		return ((Entity) this).blockPosition();
	}

	/** 显示名（实体版 = 配置名字 + 方块坐标，玩家版 = 玩家名/配置名字；委托实体实现，见类注释）。 */
	default Component getDisplayName() {
		return ((Entity) this).getDisplayName();
	}

	/** 形态 id：{@code "player"}（假玩家/客户端形态）或 {@code "entity"}（PathfinderMob 底座）。 */
	String formId();
}
