package com.swaydy.opencraft.plugins;

import com.google.gson.JsonObject;
import com.swaydy.opencraft.agent.AssistantPlugin;
import com.swaydy.opencraft.agent.ToolContext;
import com.swaydy.opencraft.agent.ToolDefinition;
import com.swaydy.opencraft.agent.ToolResult;
import com.swaydy.opencraft.entity.AiAssistantEntity;
import com.swaydy.opencraft.entity.AssistantTask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 感知插件：agentic loop 的“眼睛”。
 *
 * - look_around：返回助手坐标、朝向、任务状态、周围方块摘要（按种类计数）、
 *   附近实体（玩家/怪物/掉落物，含距离）、脚下与头顶安全性；
 * - inspect_block：返回指定方块 id、是否可挖掘、硬度、是否需要工具。
 */
public class PerceptionPlugin implements AssistantPlugin {
	@Override
	public String id() {
		return "perception";
	}

	@Override
	public List<ToolDefinition> tools() {
		JsonObject lookProps = new JsonObject();
		lookProps.add("radius", ToolSchema.prop("integer",
				"观察半径（默认 8，最大 16）。"));
		JsonObject inspectProps = new JsonObject();
		inspectProps.add("x", ToolSchema.prop("integer", "方块 X 坐标"));
		inspectProps.add("y", ToolSchema.prop("integer", "方块 Y 坐标"));
		inspectProps.add("z", ToolSchema.prop("integer", "方块 Z 坐标"));
		return List.of(
				new ToolDefinition("look_around",
						"观察你周围的环境。返回：你的坐标、任务状态、周围方块（按种类计数）、"
								+ "附近的玩家/怪物/掉落物（含距离）、脚下与头顶是否安全。"
								+ "行动前先 look_around 观察，行动后再 look_around 确认结果。",
						ToolSchema.object(lookProps),
						this::lookAround),
				new ToolDefinition("inspect_block",
						"查看指定坐标的方块：id、是否空气/可挖掘、硬度、是否需要正确工具。"
								+ "挖掘前建议先 inspect_block 确认。",
						ToolSchema.object(inspectProps, "x", "y", "z"),
						this::inspectBlock));
	}

	@Override
	public String systemPromptFragment() {
		return "【观察】你像普通玩家一样有“眼睛”：look_around 观察周围（方块/实体/任务状态），"
				+ "inspect_block 查看某个方块。"
				+ "规则：行动前先观察、行动后再观察确认；不要假设工具一定成功，以返回文本为准。";
	}

	@Override
	public String gameContextFragment(ToolContext ctx) {
		AiAssistantEntity assistant = ctx.assistant();
		AssistantTask task = assistant.getCurrentTask();
		String taskDesc = task == null ? "空闲" : task.describe();
		return "【助手当前状态】坐标: x=" + Math.round(assistant.getX())
				+ ", y=" + Math.round(assistant.getY()) + ", z=" + Math.round(assistant.getZ())
				+ " | 任务: " + taskDesc
				+ " | 跟随: " + (assistant.isFollowing() ? "跟随" : "待命");
	}

	private ToolResult lookAround(ToolContext ctx, JsonObject args) {
		ToolArgs a = new ToolArgs(args);
		int radius = Math.max(1, Math.min(16, a.intOf("radius", 8)));
		ServerLevel level = ctx.level();
		AiAssistantEntity assistant = ctx.assistant();
		BlockPos pos = assistant.blockPosition();

		StringBuilder sb = new StringBuilder();
		sb.append("坐标: x=").append(pos.getX()).append(", y=").append(pos.getY())
				.append(", z=").append(pos.getZ());
		// 朝向（yaw 转为方向）
		float yaw = assistant.getYRot();
		sb.append(", 朝向: ").append(directionName(yaw));
		// 任务状态
		AssistantTask task = assistant.getCurrentTask();
		sb.append(", 任务: ").append(task == null ? "空闲" : task.describe());

		// 周围方块摘要（按种类计数，只数半径内的非空气方块）
		Map<String, Integer> blockCounts = new LinkedHashMap<>();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dy = -radius; dy <= radius; dy += 2) {
					BlockState state = level.getBlockState(pos.offset(dx, dy, dz));
					if (state.isAir()) {
						continue;
					}
					String id = state.getBlock().getDescriptionId();
					blockCounts.merge(id, 1, Integer::sum);
				}
			}
		}
		if (blockCounts.isEmpty()) {
			sb.append("。周围 ").append(radius).append(" 格内几乎没有方块（可能在空旷地带或天上）。");
		} else {
			sb.append("。周围方块: ");
			int i = 0;
			for (Map.Entry<String, Integer> e : blockCounts.entrySet()) {
				if (i > 0) {
					sb.append(", ");
				}
				if (i >= 8) {
					sb.append("…");
					break;
				}
				sb.append(shortName(e.getKey())).append("×").append(e.getValue());
				i++;
			}
		}

		// 附近实体（玩家/怪物/掉落物，含距离，最多 10 个）
		AABB box = new AABB(pos).inflate(radius);
		List<Entity> entities = level.getEntities((Entity) null, box,
				e -> e != assistant && e.isAlive()
						&& (e instanceof LivingEntity || e instanceof ItemEntity));
		if (!entities.isEmpty()) {
			sb.append("。附近实体: ");
			int count = 0;
			for (Entity e : entities) {
				if (count >= 10) {
					sb.append("…");
					break;
				}
				double dist = Math.round(assistant.distanceTo(e) * 10.0) / 10.0;
				String type = e instanceof net.minecraft.world.entity.player.Player ? "玩家"
						: e instanceof net.minecraft.world.entity.monster.Monster ? "怪物"
						: e instanceof ItemEntity ? "掉落物" : e.getType().getDescriptionId();
				sb.append(type).append("(").append(dist).append("格)").append(" ");
				count++;
			}
		} else {
			sb.append("。附近没有其他实体。");
		}

		// 脚下与头顶安全性
		BlockState below = level.getBlockState(pos.below());
		BlockState at = level.getBlockState(pos);
		BlockState above = level.getBlockState(pos.above());
		sb.append(" | 脚下: ").append(below.isAir() ? "悬空" : shortName(below.getBlock().getDescriptionId()))
				.append(", 自己所在: ").append(at.isAir() ? "空气" : "非空气")
				.append(", 头顶: ").append(above.isAir() ? "安全" : "有方块");
		return ToolResult.ok(sb.toString());
	}

	private ToolResult inspectBlock(ToolContext ctx, JsonObject args) {
		ToolArgs a = new ToolArgs(args);
		int x = a.intOf("x", Integer.MIN_VALUE);
		int y = a.intOf("y", Integer.MIN_VALUE);
		int z = a.intOf("z", Integer.MIN_VALUE);
		if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
			return ToolResult.error("inspect_block 需要整数参数 x、y、z。");
		}
		ServerLevel level = ctx.level();
		BlockPos pos = new BlockPos(x, y, z);
		BlockState state = level.getBlockState(pos);
		String id = state.getBlock().getDescriptionId();
		float hardness = state.getDestroySpeed(level, pos);
		String result = "方块 " + shortName(id) + " @(" + x + "," + y + "," + z + ")"
				+ " | 空气: " + state.isAir()
				+ " | 硬度: " + hardness
				+ " | 可破坏: " + (state.isAir() ? "否（空气）" : hardness < 0 ? "否（不可破坏）" : "是");
		return ToolResult.ok(result);
	}

	/** 把 yaw 角度转成方向名。 */
	private static String directionName(float yaw) {
		int dir = Math.floorMod(Math.round(yaw / 90.0F), 4);
		return switch (dir) {
			case 0 -> "南(+Z)";
			case 1 -> "西(-X)";
			case 2 -> "北(-Z)";
			default -> "东(+X)";
		};
	}

	/** 把 "block.minecraft.stone" 缩成 "stone"。 */
	private static String shortName(String key) {
		if (key == null) {
			return "?";
		}
		int idx = key.lastIndexOf('.');
		return idx < 0 ? key : key.substring(idx + 1);
	}
}