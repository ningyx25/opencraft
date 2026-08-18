package com.swaydy.opencraft.plugins;

import com.google.gson.JsonObject;
import com.swaydy.opencraft.agent.AssistantPlugin;
import com.swaydy.opencraft.agent.ToolContext;
import com.swaydy.opencraft.agent.ToolDefinition;
import com.swaydy.opencraft.agent.ToolResult;
import com.swaydy.opencraft.entity.MineBlockTask;
import com.swaydy.opencraft.entity.AiAssistantEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 挖掘插件：让助手像普通玩家一样挖方块。
 *
 * mine 只【下达指令】——助手走过去持续挥动并破坏方块；掉落物进**助手自己的背包**
 * （像玩家挖矿一样；背包满则掉助手脚边），之后可 hand_to_player 递给主人。
 * 安全约束：
 * - 只能挖掘主人所在维度、与主人距离 ≤ maxDistance 的方块；
 * - 不破坏容器/带方块实体的功能方块（避免吞数据）；基岩/空气直接拒绝。
 */
public class MiningPlugin implements AssistantPlugin {
	@Override
	public String id() {
		return "mining";
	}

	@Override
	public List<ToolDefinition> tools() {
		JsonObject props = new JsonObject();
		props.add("x", ToolSchema.prop("integer", "方块 X 坐标"));
		props.add("y", ToolSchema.prop("integer", "方块 Y 坐标"));
		props.add("z", ToolSchema.prop("integer", "方块 Z 坐标"));
		return List.of(new ToolDefinition("mine",
				"让助手挖掘指定坐标的方块。挖掘是异步的：调用后立即返回，助手会走过去挖；"
						+ "掉落物像玩家挖矿一样进助手自己的背包（满则掉助手脚边），"
						+ "之后需要时用 hand_to_player 递给主人，或用 list_inventory 查看挖到了什么。"
						+ "不能挖掘空气、基岩、容器（箱子/熔炉等）。挖完后用 look_around 确认。",
				ToolSchema.object(props, "x", "y", "z"),
				this::mine));
	}

	@Override
	public String systemPromptFragment() {
		return "【挖掘】你有 mine 工具挖指定坐标的方块（异步：下达后助手自己走过去挖，"
				+ "掉落物进自己的背包，可以用 list_inventory 看挖到了什么、hand_to_player 递给玩家）。"
				+ "只挖能挖的：先 inspect_block 看是否空气/基岩/容器；挖完用 look_around 确认。";
	}

	private ToolResult mine(ToolContext ctx, JsonObject args) {
		AiAssistantEntity assistant = ctx.assistantEntity();
		if (assistant == null) {
			return ToolResult.error("mine 只对实体形态助手可用（玩家形态用 player_mine）。");
		}
		ToolArgs a = new ToolArgs(args);
		int x = a.intOf("x", Integer.MIN_VALUE);
		int y = a.intOf("y", Integer.MIN_VALUE);
		int z = a.intOf("z", Integer.MIN_VALUE);
		if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
			return ToolResult.error("mine 需要整数参数 x、y、z。");
		}
		ServerLevel level = ctx.level();
		BlockPos pos = new BlockPos(x, y, z);

		// 维度校验：只能挖主人所在维度
		if (level != ctx.owner().level()) {
			return ToolResult.error("只能挖掘主人当前所在维度的方块。");
		}
		// 距离校验：与主人距离 ≤ maxDistance
		double maxDist = assistant.getConfig().maxDistance;
		if (ctx.owner().distanceToSqr(pos.getCenter()) > maxDist * maxDist) {
			return ToolResult.error("目标方块离主人超过 " + (int) maxDist + " 格，太远了。");
		}
		// 可挖掘性校验
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) {
			return ToolResult.error("(" + x + "," + y + "," + z + ") 是空气，没有可挖的方块。");
		}
		if (state.is(net.minecraft.world.level.block.Blocks.BEDROCK)
				|| state.getDestroySpeed(level, pos) < 0) {
			return ToolResult.error("(" + x + "," + y + "," + z + ") 是基岩/不可破坏方块，挖不动。");
		}
		// 容器/方块实体保护：不破坏有 BlockEntity 的功能方块（箱子、熔炉等）
		BlockEntity be = level.getBlockEntity(pos);
		if (be != null) {
			return ToolResult.error("(" + x + "," + y + "," + z + ") 是功能方块（有数据），"
					+ "为了安全我（助手）不破坏它。");
		}
		// 跨维度配置方块保护：不挖主人绑定的 AI 徽标方块
		GlobalPos cfgBlock = assistant.getConfigBlock();
		if (cfgBlock != null && cfgBlock.dimension().equals(level.dimension())
				&& cfgBlock.pos().equals(pos)) {
			return ToolResult.error("那是我的配置方块（AI 徽标方块），不能挖。");
		}
		assistant.setCurrentTask(new MineBlockTask(assistant, level, pos));
		return ToolResult.ok("正在挖掘 (" + x + "," + y + "," + z + ")。挖到的物品会进我的背包，需要时递给你。");
	}
}