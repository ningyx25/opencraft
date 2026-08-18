package com.swaydy.opencraft.plugins;

import com.google.gson.JsonObject;
import com.swaydy.opencraft.ai.AiCompanionService;
import com.swaydy.opencraft.agent.AssistantPlugin;
import com.swaydy.opencraft.agent.ToolContext;
import com.swaydy.opencraft.agent.ToolDefinition;
import com.swaydy.opencraft.agent.ToolResult;
import com.swaydy.opencraft.assistant.player.AiAssistantPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 玩家形态插件：让“假玩家”助手像普通玩家一样行动——用真实的
 * {@code ServerPlayerGameMode}（破坏/放置/交互）与真实 PlayerInventory（合成/递物）。
 *
 * 这些是实体形态（PathfinderMob）没有的能力：玩家形态天生拥有普通玩家的一切，
 * “可以不用，但不能没有”。工具全部在服务端线程执行；移动/挖掘/放置是异步的
 * （下达指令立即返回，助手自己走过去执行，模型用 look 观察结果）。
 */
public class PlayerActionsPlugin implements AssistantPlugin {
	/** 玩家交互/破坏/放置的默认触及距离（格）。 */
	private static final double REACH = 4.5;
	/** 上下文里最多列出的物品种类数（防止 system 过长）。 */
	private static final int CONTEXT_MAX_ITEMS = 16;
	/** 玩家主背包格数（36：27 普通 + 9 快捷栏；装备槽由 1.21.11 的 EntityEquipment 管理）。 */
	private static final int MAIN_SLOTS = 36;

	@Override
	public String id() {
		return "player_actions";
	}

	@Override
	public List<ToolDefinition> tools() {
		JsonObject gotoProps = new JsonObject();
		gotoProps.add("x", ToolSchema.prop("integer", "目标 X 坐标（绝对坐标，整数）"));
		gotoProps.add("y", ToolSchema.prop("integer", "目标 Y 坐标（绝对坐标，整数）"));
		gotoProps.add("z", ToolSchema.prop("integer", "目标 Z 坐标（绝对坐标，整数）"));
		JsonObject mineProps = new JsonObject();
		mineProps.add("x", ToolSchema.prop("integer", "方块 X 坐标"));
		mineProps.add("y", ToolSchema.prop("integer", "方块 Y 坐标"));
		mineProps.add("z", ToolSchema.prop("integer", "方块 Z 坐标"));
		JsonObject placeProps = new JsonObject();
		placeProps.add("x", ToolSchema.prop("integer", "要放置处的方块 X 坐标（放到的方块，相邻）"));
		placeProps.add("y", ToolSchema.prop("integer", "要放置处的方块 Y 坐标"));
		placeProps.add("z", ToolSchema.prop("integer", "要放置处的方块 Z 坐标"));
		placeProps.add("face", ToolSchema.prop("string",
				"贴在哪个面放：up/down/north/south/east/west（默认 up）"));
		JsonObject craftProps = new JsonObject();
		craftProps.add("item", ToolSchema.prop("string",
				"要合成的物品 id，如 minecraft:diamond_block。"));
		craftProps.add("amount", ToolSchema.prop("integer", "合成数量（默认 1）。"));
		JsonObject listProps = new JsonObject();
		listProps.add("whose", ToolSchema.prop("string",
				"查看谁的背包：\"self\"（助手自己，默认）或 \"player\"（主人）。"));
		JsonObject handProps = new JsonObject();
		handProps.add("item", ToolSchema.prop("string", "物品 id，如 minecraft:cobblestone。"));
		handProps.add("amount", ToolSchema.prop("integer", "数量（默认 1）。"));
		JsonObject lookProps = new JsonObject();
		lookProps.add("radius", ToolSchema.prop("integer", "观察半径（默认 8，最大 16）。"));
		return List.of(
				new ToolDefinition("player_goto",
						"让助手（以玩家身份）走到指定坐标（绝对坐标 x,y,z）。移动是异步的：调用后立即返回，"
								+ "助手自己走过去；之后用 player_look 观察是否到达。",
						ToolSchema.object(gotoProps, "x", "y", "z"),
						this::gotoTool),
				new ToolDefinition("player_stop",
						"取消助手的当前移动，让它停下来。",
						ToolSchema.object(new JsonObject()),
						this::stopTool),
				new ToolDefinition("player_look",
						"观察助手周围：坐标、朝向、周围方块（按种类计数）、附近的玩家/怪物/掉落物（含距离）、"
								+ "是否正在移动、背包/装备摘要。行动前先观察，行动后再观察确认。",
						ToolSchema.object(lookProps),
						this::lookAround),
				new ToolDefinition("player_mine",
						"让助手（以玩家身份）挖掘指定坐标的方块：走到方块旁，用主手工具像玩家一样破坏，"
								+ "掉落物以物品形式掉出并被助手自动拾进背包。异步：调用后立即返回，之后用 player_look 确认。"
								+ "不能挖空气、基岩、容器（箱子/熔炉等）。",
						ToolSchema.object(mineProps, "x", "y", "z"),
						this::mine),
				new ToolDefinition("player_place",
						"让助手（以玩家身份）用主手物品在指定位置放置方块：贴到 (x,y,z) 方块的 face 面放置。"
								+ "需要主手拿着可放置的物品（如石头/木板）。异步：离得远会先走过去再放，之后用 player_look 确认。",
						ToolSchema.object(placeProps, "x", "y", "z"),
						this::place),
				new ToolDefinition("player_craft",
						"让助手用【自己的玩家背包】材料合成指定物品（与玩家完全一致：2×2 及更小配方随时可合成，"
								+ "3×3 配方需要附近有工作台）。产物进入助手背包，之后可用 player_hand_to_player 递给主人。",
						ToolSchema.object(craftProps, "item"),
						this::craft),
				new ToolDefinition("player_inventory",
						"列出助手（或主人）的玩家背包（36 格 + 装备 + 副手）物品清单。",
						ToolSchema.object(listProps),
						this::listInventory),
				new ToolDefinition("player_hand_to_player",
						"从助手背包取出物品递给主人（进主人背包；主人背包满则掉主人脚边）。",
						ToolSchema.object(handProps, "item"),
						this::handToPlayer));
	}

	@Override
	public String systemPromptFragment() {
		return "【玩家形态】你以一个真正的玩家身份加入了《我的世界》服务器：拥有普通玩家的完整背包、"
				+ "装备栏与玩家式动作。player_goto/player_stop 移动，player_mine/player_place 用玩家方式破坏/放置方块"
				+ "（掉落物自动进背包），player_craft 用背包材料合成（规则与玩家一致，3×3 需工作台），"
				+ "player_hand_to_player 把物品递给主人，player_inventory/player_look 观察状态与环境。"
				+ "行动前先观察、行动后再观察确认；工具结果以 [工具名 成功/失败] 开头，先读标记再读内容；"
				+ "不要假设工具一定成功，失败时换方法而不是原样重试。";
	}

	@Override
	public String gameContextFragment(ToolContext ctx) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return null;
		}
		String moving = a.movement().isMoving() ? "正在移动" : "静止";
		return "【助手状态】坐标: x=" + Math.round(a.getX())
				+ ", y=" + Math.round(a.getY()) + ", z=" + Math.round(a.getZ())
				+ " | " + moving
				+ " | 形态: 玩家";
	}

	// ------------------------------------------------------------------
	// 工具实现
	// ------------------------------------------------------------------

	private ToolResult gotoTool(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("玩家形态工具（player_goto）需要玩家形态助手。");
		}
		ToolArgs t = new ToolArgs(args);
		int x = t.intOf("x", Integer.MIN_VALUE);
		int y = t.intOf("y", Integer.MIN_VALUE);
		int z = t.intOf("z", Integer.MIN_VALUE);
		if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
			return ToolResult.error("player_goto 需要整数参数 x、y、z（绝对坐标）。");
		}
		double maxDist = a.getConfig().maxDistance;
		if (a.distanceToSqr(x + 0.5, y + 0.5, z + 0.5) > maxDist * maxDist) {
			return ToolResult.error("目标离主人超过 " + (int) maxDist + " 格，太远了；请分步走或选更近的目标。");
		}
		a.movement().moveTo(new Vec3(x + 0.5, y, z + 0.5), a.getConfig().speed, true);
		return ToolResult.ok("正在前往 (" + x + "," + y + "," + z + ")。到达后请用 player_look 确认。");
	}

	private ToolResult stopTool(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("玩家形态工具（player_stop）需要玩家形态助手。");
		}
		a.movement().stop();
		return ToolResult.ok("已停止移动。");
	}

	private ToolResult lookAround(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("玩家形态工具（player_look）需要玩家形态助手。");
		}
		ToolArgs t = new ToolArgs(args);
		int radius = Math.max(1, Math.min(16, t.intOf("radius", 8)));
		ServerLevel level = ctx.level();
		BlockPos pos = a.blockPosition();

		StringBuilder sb = new StringBuilder();
		sb.append("坐标: x=").append(pos.getX()).append(", y=").append(pos.getY())
				.append(", z=").append(pos.getZ());
		sb.append(", 朝向: ").append(directionName(a.getYRot()));
		sb.append(", 移动: ").append(a.movement().isMoving() ? "正在移动" : "静止");

		Map<String, Integer> blockCounts = new LinkedHashMap<>();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dy = -radius; dy <= radius; dy += 2) {
					BlockState state = level.getBlockState(pos.offset(dx, dy, dz));
					if (state.isAir()) {
						continue;
					}
					blockCounts.merge(state.getBlock().getDescriptionId(), 1, Integer::sum);
				}
			}
		}
		if (blockCounts.isEmpty()) {
			sb.append("。周围 ").append(radius).append(" 格内几乎没有方块。");
		} else {
			sb.append("。周围方块: ");
			int i = 0;
			for (Map.Entry<String, Integer> e : blockCounts.entrySet()) {
				if (i >= 8) {
					sb.append("…");
					break;
				}
				if (i > 0) {
					sb.append(", ");
				}
				sb.append(shortName(e.getKey())).append("×").append(e.getValue());
				i++;
			}
		}

		AABB box = new AABB(pos).inflate(radius);
		List<Entity> entities = level.getEntities((Entity) null, box,
				e -> e != a && e.isAlive()
						&& (e instanceof LivingEntity || e instanceof ItemEntity));
		if (!entities.isEmpty()) {
			sb.append("。附近实体: ");
			int count = 0;
			for (Entity e : entities) {
				if (count >= 10) {
					sb.append("…");
					break;
				}
				double dist = Math.round(a.distanceTo(e) * 10.0) / 10.0;
				String type = e instanceof Player ? "玩家"
						: e instanceof net.minecraft.world.entity.monster.Monster ? "怪物"
						: e instanceof ItemEntity ? "掉落物" : e.getType().getDescriptionId();
				sb.append(type).append("(").append(dist).append("格)").append(" ");
				count++;
			}
		} else {
			sb.append("。附近没有其他实体。");
		}
		sb.append(" | 背包: ").append(formatBackpack(a));
		return ToolResult.ok(sb.toString());
	}

	private ToolResult mine(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("玩家形态工具（player_mine）需要玩家形态助手。");
		}
		ToolArgs t = new ToolArgs(args);
		int x = t.intOf("x", Integer.MIN_VALUE);
		int y = t.intOf("y", Integer.MIN_VALUE);
		int z = t.intOf("z", Integer.MIN_VALUE);
		if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
			return ToolResult.error("player_mine 需要整数参数 x、y、z。");
		}
		ServerLevel level = ctx.level();
		BlockPos pos = new BlockPos(x, y, z);
		// 安全校验（与实体版挖掘一致）
		if (level != ctx.owner().level()) {
			return ToolResult.error("只能挖掘主人当前所在维度的方块。");
		}
		double maxDist = a.getConfig().maxDistance;
		if (ctx.owner().distanceToSqr(pos.getCenter()) > maxDist * maxDist) {
			return ToolResult.error("目标方块离主人超过 " + (int) maxDist + " 格，太远了。");
		}
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) {
			return ToolResult.error("(" + x + "," + y + "," + z + ") 是空气，没有可挖的方块。");
		}
		if (state.is(net.minecraft.world.level.block.Blocks.BEDROCK)
				|| state.getDestroySpeed(level, pos) < 0) {
			return ToolResult.error("(" + x + "," + y + "," + z + ") 是基岩/不可破坏方块，挖不动。");
		}
		BlockEntity be = level.getBlockEntity(pos);
		if (be != null) {
			return ToolResult.error("(" + x + "," + y + "," + z + ") 是功能方块（有数据），为了安全不破坏它。");
		}
		GlobalPos cfgBlock = a.getConfigBlock();
		if (cfgBlock != null && cfgBlock.dimension().equals(level.dimension())
				&& cfgBlock.pos().equals(pos)) {
			return ToolResult.error("那是我的配置方块（AI 徽标方块），不能挖。");
		}
		// 走到方块旁，到达后用真实的 ServerPlayerGameMode.destroyBlock 破坏
		a.movement().moveTo(new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5),
				a.getConfig().speed, true);
		a.movement().whenArrived(() -> doBreak(a, level, pos));
		return ToolResult.ok("正在以玩家身份走到 (" + x + "," + y + "," + z + ") 旁挖掘；"
				+ "掉落物会掉出来并被自动拾进背包。");
	}

	/** 到达后执行真正的玩家式破坏（ServerPlayerGameMode.destroyBlock）。 */
	private static void doBreak(AiAssistantPlayer a, ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || a.isRemoved()) {
			return;
		}
		boolean ok = a.gameMode.destroyBlock(pos);
		com.swaydy.opencraft.debug.DebugLog.log("player_action",
				"玩家形态助手 destroyBlock({}, {}, {}) → {}", pos.getX(), pos.getY(), pos.getZ(), ok);
		if (ok) {
			level.playSound(null, pos, net.minecraft.sounds.SoundEvents.STONE_BREAK,
					net.minecraft.sounds.SoundSource.BLOCKS, 0.8F, 1.0F);
		}
	}

	private ToolResult place(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("玩家形态工具（player_place）需要玩家形态助手。");
		}
		ToolArgs t = new ToolArgs(args);
		int x = t.intOf("x", Integer.MIN_VALUE);
		int y = t.intOf("y", Integer.MIN_VALUE);
		int z = t.intOf("z", Integer.MIN_VALUE);
		if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
			return ToolResult.error("player_place 需要整数参数 x、y、z。");
		}
		String faceName = t.strOf("face", "up").toLowerCase(java.util.Locale.ROOT);
		Direction face = parseDirection(faceName);
		if (face == null) {
			return ToolResult.error("face 必须是 up/down/north/south/east/west，收到: " + faceName);
		}
		ItemStack mainHand = a.getMainHandItem();
		if (mainHand.isEmpty()) {
			return ToolResult.error("主手没有可放置的物品（先 player_hand_to_player 或让主人给一个，"
					+ "再要求装备到主手）。");
		}
		ServerLevel level = ctx.level();
		BlockPos anchor = new BlockPos(x, y, z); // 贴着这个方块放
		BlockPos target = anchor.relative(face); // 放置位置
		if (!level.getBlockState(target).isAir()) {
			return ToolResult.error("(" + target.getX() + "," + target.getY() + "," + target.getZ()
					+ ") 不是空气，放不下。");
		}
		double maxDist = a.getConfig().maxDistance;
		if (ctx.owner().distanceToSqr(target.getCenter()) > maxDist * maxDist) {
			return ToolResult.error("目标离主人超过 " + (int) maxDist + " 格，太远了。");
		}
		Vec3 hitLoc = Vec3.atCenterOf(anchor).add(
				face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
		BlockHitResult hit = new BlockHitResult(hitLoc, face, anchor, false);
		if (a.getEyePosition().distanceTo(hitLoc) <= REACH) {
			return ToolResult.ok(doPlace(a, level, mainHand, hit));
		}
		// 太远：先走到放置位置旁，到达后再放
		a.movement().moveTo(new Vec3(target.getX() + 0.5, target.getY(), target.getZ() + 0.5),
				a.getConfig().speed, true);
		a.movement().whenArrived(() -> doPlace(a, level, a.getMainHandItem(), hit));
		return ToolResult.ok("正在走到放置位置旁，把主手物品放到 (" + target.getX() + ","
				+ target.getY() + "," + target.getZ() + ")。");
	}

	/** 执行真正的玩家式放置（ServerPlayerGameMode.useItemOn）。 */
	private static String doPlace(AiAssistantPlayer a, ServerLevel level, ItemStack item,
	                              BlockHitResult hit) {
		if (a.isRemoved() || item.isEmpty()) {
			return "助手已消失或主手空了，无法放置。";
		}
		InteractionResult result = a.gameMode.useItemOn(a, level, item, InteractionHand.MAIN_HAND, hit);
		BlockPos target = hit.getBlockPos().relative(hit.getDirection());
		boolean placed = result.consumesAction()
				&& !level.getBlockState(target).isAir();
		com.swaydy.opencraft.debug.DebugLog.log("player_action",
				"玩家形态助手 useItemOn({}, {}, {}) → {}", target.getX(), target.getY(), target.getZ(), result);
		return placed ? "已把主手物品放到 (" + target.getX() + "," + target.getY() + "," + target.getZ() + ")。"
				: "放置未生效（结果 " + result + "），可能物品不能放置或位置被占。";
	}

	private ToolResult craft(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("玩家形态工具（player_craft）需要玩家形态助手。");
		}
		ToolArgs t = new ToolArgs(args);
		String itemId = t.strOf("item", "");
		int amount = Math.max(1, Math.min(64, t.intOf("amount", 1)));
		Holder<net.minecraft.world.item.Item> item = AiCompanionService.resolveItem(itemId);
		if (item == null) {
			return ToolResult.error("我不认识物品 \"" + itemId + "\"，请用类似 minecraft:diamond_block 的物品 ID。");
		}
		ServerLevel level = ctx.level();
		RegistryAccess registryAccess = level.registryAccess();
		Inventory inv = a.getInventory();
		boolean hasWorkbench = hasWorkbenchNearby(a);
		boolean sawWorkbenchRecipe = false;

		for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof CraftingRecipe recipe) || recipe.isSpecial()) {
				continue;
			}
			ItemStack result = recipe.assemble(EMPTY_INPUT, registryAccess);
			if (result.isEmpty() || !result.is(item)) {
				continue;
			}
			if (needsWorkbench(recipe)) {
				if (!hasWorkbench) {
					sawWorkbenchRecipe = true;
					continue;
				}
			}
			GridMatch match = tryFillGrid(recipe, inv);
			if (match == null) {
				continue;
			}
			CraftingInput input = CraftingInput.of(match.width, match.height, match.grid);
			if (!recipe.matches(input, level)) {
				continue;
			}
			int perCraft = recipe.assemble(input, registryAccess).getCount();
			int sets = match.maxSets(amount, inv);
			match.consume(inv, sets);
			ItemStack crafted = recipe.assemble(input, registryAccess).copy();
			crafted.setCount(Math.min(perCraft * sets, crafted.getMaxStackSize()));
			if (!inv.add(crafted)) {
				// 背包放不下：掉落在助手脚边（玩家式）
				level.addFreshEntity(new ItemEntity(level,
						a.getX(), a.getY() + 1.0, a.getZ(), crafted));
			}
			return ToolResult.ok("已合成 " + shortName(item.value().getDescriptionId()) + " ×"
					+ crafted.getCount() + "（放进助手背包）"
					+ (sets < amount ? "。材料只够合成 " + sets + " 套" : "") + "。");
		}
		if (sawWorkbenchRecipe) {
			return ToolResult.error("合成 " + shortName(item.value().getDescriptionId())
					+ " 需要工作台（3×3 合成格，和玩家一样）。请先走到工作台旁边再试一次。");
		}
		return ToolResult.error("用我背包里的材料无法合成 "
				+ shortName(item.value().getDescriptionId()) + "。材料不足或没有配方。");
	}

	private ToolResult listInventory(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("玩家形态工具（player_inventory）需要玩家形态助手。");
		}
		ToolArgs t = new ToolArgs(args);
		String whose = t.strOf("whose", "self").toLowerCase(java.util.Locale.ROOT);
		if (whose.equals("player")) {
			return ToolResult.ok("主人背包: " + formatPlayerInventory(ctx.owner().getInventory()));
		}
		return ToolResult.ok("助手背包: " + formatPlayerInventory(a.getInventory()));
	}

	private ToolResult handToPlayer(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("玩家形态工具（player_hand_to_player）需要玩家形态助手。");
		}
		ToolArgs t = new ToolArgs(args);
		String itemId = t.strOf("item", "");
		int amount = Math.max(1, Math.min(640, t.intOf("amount", 1)));
		Holder<net.minecraft.world.item.Item> item = AiCompanionService.resolveItem(itemId);
		if (item == null) {
			return ToolResult.error("我不认识物品 \"" + itemId + "\"。");
		}
		Inventory inv = a.getInventory();
		int given = 0;
		// 只从主背包 36 格取（装备槽/副手不参与递给主人）
		for (int i = 0; i < MAIN_SLOTS && given < amount; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty() || !stack.is(item)) {
				continue;
			}
			int toTake = Math.min(amount - given, stack.getCount());
			ItemStack taken = inv.removeItem(i, toTake);
			int takenCount = taken.getCount();
			if (ctx.owner().getInventory().add(taken)) {
				given += takenCount;
			} else {
				ctx.owner().drop(taken, false);
				given += takenCount;
			}
		}
		if (given == 0) {
			return ToolResult.error("助手背包里没有 " + shortName(item.value().getDescriptionId())
					+ "，无法递给你。");
		}
		return ToolResult.ok("已把 " + shortName(item.value().getDescriptionId()) + " ×" + given + " 给你。");
	}

	// ------------------------------------------------------------------
	// 合成辅助（与实体版 CraftingPlugin 同款网格匹配，作用于玩家背包 36 格）
	// ------------------------------------------------------------------

	private static final CraftingInput EMPTY_INPUT =
			CraftingInput.of(1, 1, List.of(ItemStack.EMPTY));

	private static boolean needsWorkbench(CraftingRecipe recipe) {
		if (recipe instanceof ShapedRecipe shaped) {
			return shaped.getWidth() > 2 || shaped.getHeight() > 2;
		}
		if (recipe instanceof ShapelessRecipe shapeless) {
			return recipe.placementInfo().ingredients().size() > 4;
		}
		return false;
	}

	private static boolean hasWorkbenchNearby(AiAssistantPlayer a) {
		if (!(a.level() instanceof ServerLevel level)) {
			return false;
		}
		BlockPos center = a.blockPosition();
		for (int dx = -5; dx <= 5; dx++) {
			for (int dy = -3; dy <= 3; dy++) {
				for (int dz = -5; dz <= 5; dz++) {
					if (level.getBlockState(center.offset(dx, dy, dz))
							.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static GridMatch tryFillGrid(CraftingRecipe recipe, Inventory inv) {
		if (recipe instanceof ShapedRecipe shaped) {
			int w = shaped.getWidth();
			int h = shaped.getHeight();
			return fill(w, h, recipe.placementInfo().ingredients(), inv);
		}
		if (recipe instanceof ShapelessRecipe shapeless) {
			List<Ingredient> ingredients = recipe.placementInfo().ingredients();
			return fill(ingredients.size(), 1, ingredients, inv);
		}
		return null;
	}

	private static GridMatch fill(int w, int h, List<Ingredient> ingredients, Inventory inv) {
		int cells = w * h;
		if (ingredients.size() < cells) {
			return null;
		}
		List<ItemStack> grid = new ArrayList<>(cells);
		for (int i = 0; i < cells; i++) {
			grid.add(ItemStack.EMPTY);
		}
		int[] slotPerCell = new int[cells];
		Arrays.fill(slotPerCell, -1);
		int[] usedPerSlot = new int[inv.getContainerSize()];
		for (int c = 0; c < cells; c++) {
			Ingredient ingredient = ingredients.get(c);
			if (ingredient.isEmpty()) {
				continue;
			}
			int slot = findSlot(ingredient, inv, usedPerSlot);
			if (slot < 0) {
				return null;
			}
			usedPerSlot[slot]++;
			grid.set(c, inv.getItem(slot).copyWithCount(1));
			slotPerCell[c] = slot;
		}
		return new GridMatch(grid, slotPerCell, w, h);
	}

	private static int findSlot(Ingredient ingredient, Inventory inv, int[] usedPerSlot) {
		for (int i = 0; i < MAIN_SLOTS; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty() || usedPerSlot[i] >= stack.getCount()) {
				continue;
			}
			if (ingredient.test(stack)) {
				return i;
			}
		}
		return -1;
	}

	/** 一次成功的网格匹配：网格内容 + 每个格子对应的背包槽位。 */
	private static final class GridMatch {
		final List<ItemStack> grid;
		final int[] slotPerCell;
		final int width;
		final int height;

		GridMatch(List<ItemStack> grid, int[] slotPerCell, int width, int height) {
			this.grid = grid;
			this.slotPerCell = slotPerCell;
			this.width = width;
			this.height = height;
		}

		int maxSets(int amount, Inventory inv) {
			int[] usagePerSlot = new int[inv.getContainerSize()];
			for (int slot : slotPerCell) {
				if (slot >= 0) {
					usagePerSlot[slot]++;
				}
			}
			int sets = amount;
			for (int slot = 0; slot < inv.getContainerSize(); slot++) {
				if (usagePerSlot[slot] > 0) {
					sets = Math.min(sets, inv.getItem(slot).getCount() / usagePerSlot[slot]);
				}
			}
			return Math.max(1, sets);
		}

		void consume(Inventory inv, int sets) {
			for (int slot : slotPerCell) {
				if (slot >= 0) {
					inv.removeItem(slot, sets);
				}
			}
		}
	}

	// ------------------------------------------------------------------
	// 显示辅助
	// ------------------------------------------------------------------

	private static Direction parseDirection(String name) {
		return switch (name) {
			case "up" -> Direction.UP;
			case "down" -> Direction.DOWN;
			case "north" -> Direction.NORTH;
			case "south" -> Direction.SOUTH;
			case "east" -> Direction.EAST;
			case "west" -> Direction.WEST;
			default -> null;
		};
	}

	/** 玩家背包摘要（36 主背包 + 装备/副手/身体/坐骑鞍）。 */
	private static String formatPlayerInventory(Inventory inv) {
		StringBuilder sb = new StringBuilder();
		int shown = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) {
				continue;
			}
			if (shown > 0) {
				sb.append(", ");
			}
			if (i >= MAIN_SLOTS) {
				sb.append(slotName(i)).append("=");
			}
			sb.append(shortName(stack.getItem().getDescriptionId())).append("×").append(stack.getCount());
			shown++;
			if (shown >= CONTEXT_MAX_ITEMS) {
				sb.append(" …");
				break;
			}
		}
		return shown == 0 ? "空" : sb.toString();
	}

	private static String formatBackpack(AiAssistantPlayer a) {
		return formatPlayerInventory(a.getInventory());
	}

	private static String slotName(int index) {
		return switch (index) {
			case 36 -> "靴子";
			case 37 -> "护腿";
			case 38 -> "胸甲";
			case 39 -> "头盔";
			case 40 -> "副手";
			case 41 -> "身体";
			case 42 -> "坐骑鞍";
			default -> "物品";
		};
	}

	private static String directionName(float yaw) {
		int dir = Math.floorMod(Math.round(yaw / 90.0F), 4);
		return switch (dir) {
			case 0 -> "南(+Z)";
			case 1 -> "西(-X)";
			case 2 -> "北(-Z)";
			default -> "东(+X)";
		};
	}

	private static String shortName(String key) {
		if (key == null) {
			return "?";
		}
		int idx = key.lastIndexOf('.');
		return idx < 0 ? key : key.substring(idx + 1);
	}
}
