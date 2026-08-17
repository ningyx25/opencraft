package com.swaydy.opencraft.plugins;

import com.google.gson.JsonObject;
import com.swaydy.opencraft.agent.AssistantPlugin;
import com.swaydy.opencraft.agent.ToolContext;
import com.swaydy.opencraft.agent.ToolDefinition;
import com.swaydy.opencraft.agent.ToolResult;
import com.swaydy.opencraft.entity.MoveToBlockTask;

import java.util.List;

/**
 * 移动插件：让助手像普通玩家一样在世界里走动。
 *
 * - goto：下达移动指令（MoveToBlockTask，异步）；立即返回“正在前往”；
 * - stop：取消当前移动/挖掘任务。
 * 模型通过后续 look_around 观察是否到达。
 */
public class MovementPlugin implements AssistantPlugin {
	@Override
	public String id() {
		return "movement";
	}

	@Override
	public List<ToolDefinition> tools() {
		JsonObject gotoProps = new JsonObject();
		gotoProps.add("x", ToolSchema.prop("integer", "目标 X 坐标（绝对坐标，整数）"));
		gotoProps.add("y", ToolSchema.prop("integer", "目标 Y 坐标（绝对坐标，整数）"));
		gotoProps.add("z", ToolSchema.prop("integer", "目标 Z 坐标（绝对坐标，整数）"));
		return List.of(
				new ToolDefinition("goto",
						"让助手移动到指定坐标（绝对坐标 x,y,z）。移动是异步的：调用后立即返回，"
								+ "助手会自行寻路走过去；之后用 look_around 观察是否到达。"
								+ "不要一次移动太远（超过 32 格建议分几步走）。",
						ToolSchema.object(gotoProps, "x", "y", "z"),
						this::gotoTool),
				new ToolDefinition("stop",
						"取消助手的当前移动/挖掘/攻击任务，让它停下来。",
						ToolSchema.object(new JsonObject()),
						this::stopTool));
	}

	@Override
	public String systemPromptFragment() {
		return "【移动】你有两条移动工具：goto（走到指定坐标）和 stop（停下）。"
				+ "移动是异步的——调用 goto 后助手会自动走过去，你需要用 look_around 确认是否到达；"
				+ "无法到达（如悬崖、水、岩浆）时用 look_around 看环境并换路线。";
	}

	@Override
	public String gameContextFragment(ToolContext ctx) {
		var task = ctx.assistant().getCurrentTask();
		return task == null ? null : "当前任务：" + task.describe();
	}

	private ToolResult gotoTool(ToolContext ctx, JsonObject args) {
		ToolArgs a = new ToolArgs(args);
		int x = a.intOf("x", Integer.MIN_VALUE);
		int y = a.intOf("y", Integer.MIN_VALUE);
		int z = a.intOf("z", Integer.MIN_VALUE);
		if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
			return ToolResult.error("goto 需要整数参数 x、y、z（绝对坐标）。");
		}
		// 距离安全检查（不超出主人 maxDistance 配置太远）
		double maxDist = ctx.assistant().getConfig().maxDistance;
		double distSq = ctx.assistant().distanceToSqr(x + 0.5, y + 0.5, z + 0.5);
		if (distSq > maxDist * maxDist) {
			return ToolResult.error("目标离主人超过 " + (int) maxDist + " 格，太远了；请分步走或选更近的目标。");
		}
		ctx.assistant().setCurrentTask(new MoveToBlockTask(ctx.assistant(),
				new net.minecraft.core.BlockPos(x, y, z)));
		return ToolResult.ok("正在前往 (" + x + "," + y + "," + z + ")。到达后请用 look_around 确认。");
	}

	private ToolResult stopTool(ToolContext ctx, JsonObject args) {
		ctx.assistant().cancelCurrentTask();
		return ToolResult.ok("已停止当前任务。");
	}
}