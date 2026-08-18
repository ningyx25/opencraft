package com.swaydy.opencraft.plugins;

import com.google.gson.JsonObject;
import com.swaydy.opencraft.agent.AssistantPlugin;
import com.swaydy.opencraft.agent.ToolContext;
import com.swaydy.opencraft.agent.ToolDefinition;
import com.swaydy.opencraft.agent.ToolResult;
import com.swaydy.opencraft.entity.AiAssistantEntity;
import com.swaydy.opencraft.entity.AttackTask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * 战斗插件：让助手攻击敌人（怪物）。
 *
 * attack：按名字/类型找主人附近 16 格内匹配的实体（默认打最近的非玩家活体），
 * 下达攻击任务（AttackTask，异步）。找不到目标返回错误。
 */
public class CombatPlugin implements AssistantPlugin {
	@Override
	public String id() {
		return "combat";
	}

	@Override
	public List<ToolDefinition> tools() {
		JsonObject props = new JsonObject();
		props.add("target", ToolSchema.prop("string",
				"要攻击的目标：实体名字（如 \"zombie\"）或类型描述；留空 = 攻击最近的怪物。"));
		return List.of(new ToolDefinition("attack",
				"让助手攻击附近（16 格内）的敌人。target 可以填实体名（如 \"zombie\"、\"spider\"）"
						+ "或留空攻击最近的怪物。只攻击敌对生物，不攻击玩家。攻击是异步的，助手会走过去打。",
				ToolSchema.object(props),
				this::attack));
	}

	@Override
	public String systemPromptFragment() {
		return "【战斗】你有 attack 工具攻击附近的怪物（不攻击玩家）。"
				+ "先 look_around 看有哪些怪物再决定攻击谁；打不过就 tp 回主人身边或求助主人。";
	}

	private ToolResult attack(ToolContext ctx, JsonObject args) {
		AiAssistantEntity assistant = ctx.assistantEntity();
		if (assistant == null) {
			return ToolResult.error("attack 只对实体形态助手可用。");
		}
		ToolArgs a = new ToolArgs(args);
		String targetDesc = a.strOf("target", "").toLowerCase(java.util.Locale.ROOT);
		ServerLevel level = ctx.level();
		LivingEntity target = findTarget(ctx, assistant, targetDesc);
		if (target == null) {
			if (targetDesc.isEmpty()) {
				return ToolResult.error("附近 16 格内没有可攻击的怪物。");
			}
			return ToolResult.error("附近 16 格内没有名为 \"" + targetDesc + "\" 的怪物。先 look_around 看看有哪些。");
		}
		assistant.setCurrentTask(new AttackTask(assistant, level, target));
		return ToolResult.ok("正在攻击 " + target.getName().getString() + "。");
	}

	/** 按名字/类型找最近的匹配目标（非玩家活体）；留空 = 最近的怪物。 */
	private LivingEntity findTarget(ToolContext ctx, AiAssistantEntity assistant, String desc) {
		ServerLevel level = ctx.level();
		BlockPos pos = assistant.blockPosition();
		AABB box = new AABB(pos).inflate(16);
		List<Entity> entities = level.getEntities((Entity) null, box,
				e -> e != assistant && e.isAlive() && e instanceof LivingEntity
						&& !(e instanceof Player));
		LivingEntity best = null;
		double bestDist = Double.MAX_VALUE;
		for (Entity e : entities) {
			LivingEntity le = (LivingEntity) e;
			// 优先怪物；攻击对象只限敌对生物（Monster）或明确按名字匹配
			boolean nameMatch = desc.isEmpty()
					|| le.getType().getDescriptionId().toLowerCase(java.util.Locale.ROOT).contains(desc)
					|| (le.getName().getString().toLowerCase(java.util.Locale.ROOT).contains(desc));
			if (desc.isEmpty() && !(le instanceof Monster)) {
				continue; // 未指定目标：只攻击怪物
			}
			if (!nameMatch) {
				continue;
			}
			double d = assistant.distanceToSqr(le);
			if (d < bestDist) {
				bestDist = d;
				best = le;
			}
		}
		return best;
	}
}