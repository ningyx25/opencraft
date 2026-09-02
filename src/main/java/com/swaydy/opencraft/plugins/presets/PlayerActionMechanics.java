package com.swaydy.opencraft.plugins.presets;

import com.google.gson.JsonObject;
import com.swaydy.opencraft.agent.ActionEvents;
import com.swaydy.opencraft.ai.AiCompanionService;
import com.swaydy.opencraft.assistant.player.AiAssistantPlayer;
import com.swaydy.opencraft.plugins.ToolArgs;
import com.swaydy.opencraft.plugins.ToolContext;
import com.swaydy.opencraft.plugins.ToolResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 玩家形态助手的动作实现（capability provider）：每个工具方法都是<b>无状态静态方法</b>，
 * 直接驱动真 {@code AiAssistantPlayer}（移动/挖掘/放置控制器、玩家背包、容器菜单），
 * 返回喂给模型的 {@link ToolResult}。本类不是插件——模型可见的工具 surface（schema/描述/提示词）
 * 与能力分组在同包的 6 个 capability 插件里（{@code PlayerMovementPlugin} 等，见 plugins/README），
 * 它们的 executor 引用本类的静态方法。
 *
 * <p>与 deepseek-harness 的对应：dsh 把能力拆成 shell/fs/container 等 capability family
 * （Service Definition / Provider / Consumer）；这里把「模型可调用的工具族」（插件）与
 * 「玩家 bot 的操作实现」（本类）分离，工具族可单独组合进不同 Agent 预设。
 */
final class PlayerActionMechanics {
	/** 玩家交互/破坏/放置的默认触及距离（格）。 */
	private static final double REACH = 4.5;
	/** 玩家主背包格数（36：27 普通 + 9 快捷栏；装备槽由 1.21.11 的 EntityEquipment 管理）。 */
	private static final int MAIN_SLOTS = 36;

	// ------------------------------------------------------------------
	// 工具实现
	// ------------------------------------------------------------------

	static ToolResult gotoTool(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("Tool player_goto requires a player-form assistant.");
		}
		ToolArgs t = new ToolArgs(args);
		int x = t.intOf("x", Integer.MIN_VALUE);
		int y = t.intOf("y", Integer.MIN_VALUE);
		int z = t.intOf("z", Integer.MIN_VALUE);
		if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
			return ToolResult.error("player_goto requires integer parameters x, y, z (absolute coordinates).");
		}
		double maxDist = a.getConfig().maxDistance;
		if (a.distanceToSqr(x + 0.5, y + 0.5, z + 0.5) > maxDist * maxDist) {
			// 校验的是目标距助手自身当前位置的距离（不是距主人）——文案如实说明
			return ToolResult.error("Target is more than " + (int) maxDist
					+ " blocks from your current position — too far; "
					+ "move toward it in steps or pick a closer target.");
		}
		a.movement().moveTo(new Vec3(x + 0.5, y, z + 0.5), a.getConfig().speed, true);
		return ToolResult.deferred("Heading to (" + x + "," + y + "," + z + ") — walking takes a few seconds. "
				+ "This is an async action: the arrival [Event] will arrive automatically; do not re-issue the command.");
	}

	static ToolResult stopTool(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("Tool player_stop requires a player-form assistant.");
		}
		a.movement().stop();
		a.movement().cancelMining(); // player_stop 语义是"全部停下"：连挖掘一起取消
		return ToolResult.ok("Movement and mining stopped.");
	}

	/**
	 * 瞬移到指定坐标（同维度）——player_goto 的"够不到就走不了"的兜底：悬崖/岩浆/
	 * 水面/深坑等走路难以到达的地方直接传送过去。传送前停掉在途移动/挖掘（与
	 * teleport_to_player 相同:传送后旧目标已无意义,不停的话 bot 会朝旧目标走回去）;
	 * 落点经 findSafeSpawnPos 向上找安全位置,避免传进墙里/地下。同步完成,结果立即返回。
	 */
	static ToolResult teleportTool(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("Tool player_teleport requires a player-form assistant.");
		}
		ToolArgs t = new ToolArgs(args);
		int x = t.intOf("x", Integer.MIN_VALUE);
		int y = t.intOf("y", Integer.MIN_VALUE);
		int z = t.intOf("z", Integer.MIN_VALUE);
		if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
			return ToolResult.error("player_teleport requires integer parameters x, y, z (absolute coordinates).");
		}
		double maxDist = a.getConfig().maxDistance;
		if (a.distanceToSqr(x + 0.5, y + 0.5, z + 0.5) > maxDist * maxDist) {
			// 与 player_goto 同一根距离缰绳（目标距助手自身当前位置）——文案一致
			return ToolResult.error("Target is more than " + (int) maxDist
					+ " blocks from your current position — too far; "
					+ "move toward it in steps or pick a closer target.");
		}
		// 传送前停掉在途移动/挖掘,避免传送后 bot 朝旧目标走回去/继续挖
		a.movement().stop();
		a.movement().cancelMining();
		// 落点向上找安全位置（空气+空气+脚下实体）,防止传进墙里/地下。
		// ⚠ 用助手自身所在维度扫描（teleportTo 三参重载在 a.level() 内传送）——
		// 不能扫 ctx.level()：AgentRuntime 构造 ToolContext 时 level 是主人的维度,
		// 任务执行期间主人可能已跨维度,扫错维度会让落点与实际传送维度不一致
		if (!(a.level() instanceof ServerLevel level)) {
			return ToolResult.error("The assistant is not in a server world; cannot teleport.");
		}
		Vec3 safe = AiCompanionService.findSafeSpawnPos(level,
				new Vec3(x + 0.5, y, z + 0.5));
		a.teleportTo(safe.x, safe.y, safe.z);
		com.swaydy.opencraft.logging.DebugLog.log("teleport",
				"玩家形态助手 player_teleport 到 ({},{},{})（安全点 ({},{},{})）",
				x, y, z, (int) safe.x, (int) safe.y, (int) safe.z);
		boolean adjusted = Math.abs(safe.x - (x + 0.5)) > 0.01
				|| Math.abs(safe.y - y) > 0.01
				|| Math.abs(safe.z - (z + 0.5)) > 0.01;
		return ToolResult.ok(adjusted
				? "Teleported to (" + x + "," + y + "," + z + ") — adjusted to the safe spot ("
						+ (int) safe.x + "," + (int) safe.y + "," + (int) safe.z + ")."
				: "Teleported to (" + x + "," + y + "," + z + ").");
	}

	static ToolResult jump(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("Tool player_jump requires a player-form assistant.");
		}
		return a.movement().jump()
				? ToolResult.ok("Jumped (combine with a movement target to run over steps/gaps).")
				: ToolResult.error("Cannot jump right now: in mid-air or flying; land first and try again.");
	}

	// player_look 已移除（坐标/朝向/移动、近旁方块（带坐标）、大范围方块计数、附近实体
	// （带坐标/方位/距离）全部由 agent.Prompts 的 Assistant State 每轮注入 system 上下文,
	// 模型无需再调用观察类工具;定向找坐标仍用 player_find）。
	// player_inventory 已加回：上下文里的背包只是摘要（截断/聚合）,模型在需要精确完整
	// 的背包视图（哪个槽有什么、耐久、装备）时调用 player_inventory 按需获取。

	/**
	 * 按关键词/ID 找方块或实体，返回【精确坐标 + 方位 + 距离】——模型据此才能判断
	 * “东西在哪”，而不是拿着 oak_log 计数去猜坐标。方块：精确解析失败时按关键词
	 * 遍历注册表展开（id 路径或描述键子串匹配，限 6 种），再对半径内立方体扫描一次；
	 * 实体：按关键词/实体类型 id 查询指定类型。
	 */
	static ToolResult findTarget(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("Tool player_find requires a player-form assistant.");
		}
		ToolArgs t = new ToolArgs(args);
		String target = t.strOf("target", "").trim().toLowerCase(java.util.Locale.ROOT);
		if (target.isEmpty()) {
			return ToolResult.error("player_find requires a target (ID or keyword of what to find).");
		}
		int radius = Math.max(1, Math.min(20, t.intOf("radius", 12)));
		ServerLevel level = ctx.level();
		BlockPos center = a.blockPosition();

		Set<Block> wantedBlocks = wantedBlocks(target);
		boolean entityQuery = isEntityQuery(target);

		List<String> lines = new ArrayList<>();
		List<Integer> listedDy = new ArrayList<>(); // 已列出条目相对助手的垂直偏移(可达性提示用)
		int total = 0;

		// 方块扫描：一次过立方体，命中即记
		if (!wantedBlocks.isEmpty()) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dy = -radius; dy <= radius; dy++) {
					for (int dz = -radius; dz <= radius; dz++) {
						BlockState st = level.getBlockState(center.offset(dx, dy, dz));
						if (st.isAir() || !wantedBlocks.contains(st.getBlock())) {
							continue;
						}
						total++;
						if (lines.size() < 6) {
							lines.add(formatTarget(center.offset(dx, dy, dz),
									st.getBlock().getDescriptionId(), a));
							listedDy.add(dy);
						}
					}
				}
			}
		}
		// 实体查询（关键词命中或实体类型 id 可解析）
		if (entityQuery) {
			AABB box = new AABB(center).inflate(radius);
			List<Entity> ents = level.getEntities((Entity) null, box,
					e -> e != a && e.isAlive() && matchesEntity(e, target));
			total += ents.size();
			int n = 0;
			for (Entity e : ents) {
				if (n >= 6) {
					break;
				}
				String label = e instanceof Player ? "Player"
						: e instanceof Monster ? "Monster"
						: e instanceof ItemEntity ie
								? "Dropped " + AiCompanionService.shortName(ie.getItem().getItem().getDescriptionId())
						: AiCompanionService.shortName(e.getType().getDescriptionId());
				lines.add(formatTarget(e.blockPosition(), label, a));
				listedDy.add(e.blockPosition().getY() - center.getY());
				n++;
			}
		}

		if (lines.isEmpty()) {
			return ToolResult.error("Nothing related to \"" + target + "\" found within " + radius + " blocks. "
					+ "Try a shorter keyword (e.g. \"log\"), or an exact ID (e.g. minecraft:oak_log).");
		}
		StringBuilder sb = new StringBuilder("Found ").append(total).append(" match(es) for \"").append(target)
				.append("\" (within ").append(radius).append(" blocks):");
		for (int i = 0; i < lines.size(); i++) {
			sb.append("\n").append(i + 1).append(". ").append(lines.get(i));
		}
		if (total > lines.size()) {
			sb.append("\n… ").append(total).append(" total (listing the nearest ").append(lines.size()).append(" here)");
		}
		// 可达性提示:列出的方块全部在脚下 ≥5 格(典型:地表下方的石头/矿物)——
		// 走过去只会停在其正上方,必须向下挖才能到达
		if (!listedDy.isEmpty() && listedDy.stream().allMatch(d -> d <= -5)) {
			sb.append("\nnote: all listed matches are at least 5 blocks below you (underground) — "
					+ "walking only reaches the ground above them; mine down step by step to reach them.");
		}
		return ToolResult.ok(sb.toString());
	}

	static ToolResult mine(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("Tool player_mine requires a player-form assistant.");
		}
		ToolArgs t = new ToolArgs(args);
		int x = t.intOf("x", Integer.MIN_VALUE);
		int y = t.intOf("y", Integer.MIN_VALUE);
		int z = t.intOf("z", Integer.MIN_VALUE);
		if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
			return ToolResult.error("player_mine requires integer parameters x, y, z.");
		}
		ServerLevel level = ctx.level();
		BlockPos pos = new BlockPos(x, y, z);
		// 安全校验
		if (level != ctx.owner().level()) {
			return ToolResult.error("Can only mine blocks in the dimension the owner is currently in.");
		}
		double maxDist = a.getConfig().maxDistance;
		if (ctx.owner().distanceToSqr(pos.getCenter()) > maxDist * maxDist) {
			return ToolResult.error("Target block is more than " + (int) maxDist + " blocks from the owner — too far.");
		}
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) {
			return ToolResult.error("(" + x + "," + y + "," + z + ") is air; nothing to mine there.");
		}
		if (state.is(net.minecraft.world.level.block.Blocks.BEDROCK)
				|| state.getDestroySpeed(level, pos) < 0) {
			return ToolResult.error("(" + x + "," + y + "," + z + ") is bedrock/an unbreakable block; can't mine it.");
		}
		BlockEntity be = level.getBlockEntity(pos);
		if (be != null) {
			return ToolResult.error("(" + x + "," + y + "," + z + ") is a functional block (has data); won't break it for safety.");
		}
		GlobalPos cfgBlock = a.getConfigBlock();
		if (cfgBlock != null && cfgBlock.dimension().equals(level.dimension())
				&& cfgBlock.pos().equals(pos)) {
			return ToolResult.error("That is my config block (AI Logo Block); can't mine it.");
		}
		// 原版规则预检：当前主手工具的破坏进度（挖掘耗时 = 1/进度 tick，与真实玩家一致）。
		// 过慢（>30s）直接拒绝——真玩家也会放弃去换工具，别让助手对着黑曜石徒手抠。
		float perTick = state.getDestroyProgress(a, level, pos);
		if (perTick <= 0.0F || perTick < 1.0F / 600.0F) {
			return ToolResult.error("(" + x + "," + y + "," + z + ") is too hard for my current main-hand tool "
					+ "(would take over 30s, same as a player). Equip a proper tool "
					+ "(player_hotbar_select) and try again.");
		}
		int seconds = Math.max(1, Math.round(1.0F / perTick / 20.0F));
		// 走到目标方块旁的可站位（不是 y+1 硬编码——树是叠的，log 上方还是 log，
		// 站不进去；找四周/上下所有可达且有支撑的邻格，选离玩家最近的，
		// 这样掉落物就落在玩家脚边，用原版拾取即可收集）
		Vec3 standPos = mineStandPos(level, pos, a.position());
		a.movement().moveTo(standPos, a.getConfig().speed, true);
		a.movement().whenArrived(() -> {
			int started = a.movement().startMining(a, level, pos);
			if (started == -1) {
				// 不在触及范围内/方块已消失:如实上报失败,让模型换策略
				String text = level.getBlockState(pos).isAir()
						? ActionEvents.miningBlockGoneText(x, y, z)
						: ActionEvents.miningAbortedRangeText(x, y, z);
				a.movement().completeAction(a.movement().currentActionToken(), text, false);
			} else if (started == 0) {
				// 当前工具秒破（服务器已就地破坏并掉落）
				a.movement().completeAction(a.movement().currentActionToken(),
						ActionEvents.miningCompleteText(x, y, z), true);
			}
			// started ≥ 1：挖掘进行中,PlayerMovementController.tickMining 完成时上报
		});
		return ToolResult.deferred("Walking over to mine (" + x + "," + y + "," + z + ") as a player — "
				+ "digging takes ~" + seconds + "s with my current tool (real mining speed). "
				+ "Async action: the outcome [Event] will arrive automatically; do not re-issue.");
	}

	/**
	 * 找离玩家最近的可达站立位，使玩家能触及目标方块（标准生存互动距离 4.5）。
	 * 候选：目标正上方（y+1，适用于地面单方块）、四周同层/上方/下方、目标正下方。
	 * 优先选"自身所在格可站 + 头顶可站 + 脚下有支撑"的候选（否则 bot 会悬空/掉下去），
	 * 无支撑候选兜底。
	 */
	private static Vec3 mineStandPos(ServerLevel level, BlockPos pos, Vec3 from) {
		Vec3 best = null;
		double bestDist = Double.MAX_VALUE;
		Vec3 fallback = null;
		java.util.List<Vec3> candidates = new java.util.ArrayList<>();
		candidates.add(Vec3.atBottomCenterOf(pos.above())); // 正上方
		candidates.add(Vec3.atBottomCenterOf(pos.below())); // 正下方
		for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			BlockPos side = pos.relative(d);
			candidates.add(Vec3.atBottomCenterOf(side));         // 同层侧面
			candidates.add(Vec3.atBottomCenterOf(side.above())); // 侧面上方
			candidates.add(Vec3.atBottomCenterOf(side.below())); // 侧面下方
		}
		for (Vec3 cand : candidates) {
			BlockPos cell = BlockPos.containing(cand);
			if (!level.getBlockState(cell).canBeReplaced()) {
				continue; // 自身格被实心方块占据
			}
			if (!level.getBlockState(cell.above()).canBeReplaced()) {
				continue; // 头顶被挡
			}
			boolean hasGround = !level.getBlockState(cell.below()).canBeReplaced();
			double d = from.distanceToSqr(cand);
			if (hasGround) {
				if (d < bestDist) {
					bestDist = d;
					best = cand;
				}
			} else if (fallback == null) {
				fallback = cand;
			}
		}
		return best != null ? best : (fallback != null ? fallback : Vec3.atBottomCenterOf(pos.above()));
	}

	static ToolResult place(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("Tool player_place requires a player-form assistant.");
		}
		ToolArgs t = new ToolArgs(args);
		int x = t.intOf("x", Integer.MIN_VALUE);
		int y = t.intOf("y", Integer.MIN_VALUE);
		int z = t.intOf("z", Integer.MIN_VALUE);
		if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
			return ToolResult.error("player_place requires integer parameters x, y, z.");
		}
		String faceName = t.strOf("face", "up").toLowerCase(java.util.Locale.ROOT);
		Direction face = parseDirection(faceName);
		if (face == null) {
			return ToolResult.error("face must be up/down/north/south/east/west, got: " + faceName);
		}
		ItemStack mainHand = a.getMainHandItem();
		if (mainHand.isEmpty()) {
			return ToolResult.error("No placeable item in the main hand (first use player_hand_to_player "
					+ "or have the owner give one and equip it to the main hand).");
		}
		ServerLevel level = ctx.level();
		BlockPos anchor = new BlockPos(x, y, z); // 贴着这个方块放
		BlockPos target = anchor.relative(face); // 放置位置
		if (!level.getBlockState(target).isAir()) {
			return ToolResult.error("(" + target.getX() + "," + target.getY() + "," + target.getZ()
					+ ") is not air; can't place there.");
		}
		double maxDist = a.getConfig().maxDistance;
		if (ctx.owner().distanceToSqr(target.getCenter()) > maxDist * maxDist) {
			return ToolResult.error("Target is more than " + (int) maxDist + " blocks from the owner — too far.");
		}
		Vec3 hitLoc = Vec3.atCenterOf(anchor).add(
				face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
		BlockHitResult hit = new BlockHitResult(hitLoc, face, anchor, false);
		boolean sneak = t.boolOf("sneak", false);
		if (a.getEyePosition().distanceTo(hitLoc) <= REACH) {
			return doPlace(a, level, mainHand, hit, sneak); // 就近:同步完成,直接给真实结果
		}
		// 太远：先走到放置位置旁，到达后再放;结果经动作回调上报
		a.movement().moveTo(new Vec3(target.getX() + 0.5, target.getY(), target.getZ() + 0.5),
				a.getConfig().speed, true);
		a.movement().whenArrived(() -> {
			ToolResult r = doPlace(a, level, a.getMainHandItem(), hit, sneak);
			a.movement().completeAction(a.movement().currentActionToken(), r.message(), r.ok());
		});
		return ToolResult.deferred("Walking to the placement spot to place the main-hand item at ("
				+ target.getX() + "," + target.getY() + "," + target.getZ() + ") — async action: "
				+ "the outcome [Event] will arrive automatically; do not re-issue.");
	}

	static ToolResult craft(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("Tool player_craft requires a player-form assistant.");
		}
		ToolArgs t = new ToolArgs(args);
		String itemId = t.strOf("item", "");
		int amount = Math.max(1, Math.min(64, t.intOf("amount", 1)));
		Holder<net.minecraft.world.item.Item> item = AiCompanionService.resolveItem(itemId);
		if (item == null) {
			return ToolResult.error("I don't know the item \"" + itemId + "\"; use an item ID like minecraft:diamond_block.");
		}
		ServerLevel level = ctx.level();
		RegistryAccess registryAccess = level.registryAccess();
		Inventory inv = a.getInventory();
		BlockPos workbench = findWorkbenchNearby(a);
		boolean sawWorkbenchRecipe = false;

		for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
			if (!(holder.value() instanceof CraftingRecipe recipe) || recipe.isSpecial()) {
				continue;
			}
			ItemStack result = recipe.assemble(EMPTY_INPUT, registryAccess);
			if (result.isEmpty() || !result.is(item)) {
				continue;
			}
			if (needsWorkbench(recipe) && workbench == null) {
				sawWorkbenchRecipe = true;
				continue;
			}
			// 原版配方书流程（与真实玩家在配方书里点合成完全一致）：
			// 1) 在正确的菜单里 handlePlacement 自动摆料（2×2 用随身背包菜单，
			//    3×3 用工作台的 CraftingMenu——必须旁边真有工作台）；
			// 2) shift 点结果槽（quickMoveStack）：取走产物、按配方消耗材料；
			//    循环到数量够或材料用尽；
			// 3) removed 关菜单：网格里剩的材料退回背包。
			net.minecraft.world.inventory.RecipeBookMenu menu = needsWorkbench(recipe)
					? new net.minecraft.world.inventory.CraftingMenu(0, inv,
							net.minecraft.world.inventory.ContainerLevelAccess.create(level, workbench))
					: a.inventoryMenu;
			int crafted = 0;
			while (crafted < amount) {
				menu.handlePlacement(false, false, holder, level, inv);
				if (menu.slots.get(0).getItem().isEmpty()) {
					break; // 摆料失败：材料不足（或配方放不进该网格）
				}
				ItemStack out = menu.quickMoveStack(a, 0);
				if (out.isEmpty()) {
					break;
				}
				crafted += out.getCount();
			}
			menu.removed(a);
			if (crafted > 0) {
				com.swaydy.opencraft.logging.DebugLog.log("player_action",
						"玩家形态助手按配方书流程合成 {} ×{}（菜单 {}）",
						AiCompanionService.shortName(item.value().getDescriptionId()), crafted,
						menu == a.inventoryMenu ? "随身 2×2" : "工作台 3×3");
				return ToolResult.ok("Crafted " + AiCompanionService.shortName(item.value().getDescriptionId()) + " ×"
						+ crafted + " (put into the assistant's inventory)"
						+ (crafted < amount ? ". Only enough materials for " + crafted : "") + ".");
			}
		}
		if (sawWorkbenchRecipe) {
			return ToolResult.error("Crafting " + AiCompanionService.shortName(item.value().getDescriptionId())
					+ " requires a crafting table (3×3 grid, same as a player). Walk to a crafting table first and try again.");
		}
		// 诊断：合成失败时打印背包内容
		com.swaydy.opencraft.OpenCraftMod.LOGGER.info(
				"[OpenCraft] player_craft {} 失败：背包={}, 附近工作台={}",
				itemId, inventorySnapshot(inv), workbench == null ? "无" : workbench.toShortString());
		return ToolResult.error("Cannot craft "
				+ AiCompanionService.shortName(item.value().getDescriptionId()) + " from the materials in my inventory. "
				+ "Not enough materials or no recipe.");
	}

	/** 背包内容快照（诊断用）：非空槽位按 物品x数量 列出。 */
	private static String inventorySnapshot(Inventory inv) {
		StringBuilder sb = new StringBuilder();
		int shown = 0;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) continue;
			if (shown++ > 0) sb.append(", ");
			sb.append("[").append(i).append("]")
					.append(AiCompanionService.shortName(stack.getItem().getDescriptionId()))
					.append("x").append(stack.getCount());
		}
		return shown == 0 ? "空" : sb.toString();
	}

	static ToolResult itemMove(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("Tool player_item_move requires a player-form assistant.");
		}
		ToolArgs t = new ToolArgs(args);
		String fromStr = t.strOf("from", "").trim();
		String toStr   = t.strOf("to",   "").trim();
		if (fromStr.isEmpty() || toStr.isEmpty()) {
			return ToolResult.error("Both 'from' and 'to' slots are required.");
		}

		Inventory inv = a.getInventory();

		// 解析槽位：数字 → 主背包，名字 → 装备槽，-1 → 丢弃
		SlotRef from = parseSlot(fromStr);
		SlotRef to   = "-1".equals(toStr) ? null : parseSlot(toStr);
		if (from == null) return ToolResult.error("Unknown slot \"" + fromStr + "\". Use 0–35 or: mainhand, offhand, helmet, chestplate, leggings, boots.");
		if (!"-1".equals(toStr) && to == null) return ToolResult.error("Unknown slot \"" + toStr + "\". Use 0–35, -1 (drop), or: mainhand, offhand, helmet, chestplate, leggings, boots.");

		// -1：把 from 槽的物品全部丢到地上（原版 Player.drop：朝看向抛出，同 Q 键）
		if ("-1".equals(toStr)) {
			ItemStack toDrop = from.get(a, inv).copy();
			if (toDrop.isEmpty()) return ToolResult.error("Slot " + fromStr + " is empty, nothing to drop.");
			from.set(a, inv, ItemStack.EMPTY);
			a.drop(toDrop, false);
			com.swaydy.opencraft.logging.DebugLog.log("player_action",
					"玩家形态助手丢弃 {} × {} [{}]",
					AiCompanionService.shortName(toDrop.getItem().getDescriptionId()), toDrop.getCount(), fromStr);
			return ToolResult.ok("Dropped " + toDrop.getCount() + "× "
					+ AiCompanionService.shortName(toDrop.getItem().getDescriptionId()) + " from slot " + fromStr + ".");
		}

		// 交换：走真实玩家路径——在自己的背包菜单（InventoryMenu）里三次点击
		//（拿起 from → 放到 to（光标交换）→ 放回 from），护甲部位校验/穿戴回调/
		// 音效全部是原版逻辑；装备变更由 doTick 的装备检测自动同步给其他玩家
		Integer fromMenu = menuSlotIndex(a, fromStr);
		Integer toMenu = menuSlotIndex(a, toStr);
		if (fromMenu == null || toMenu == null) {
			return ToolResult.error("Unknown slot. Use 0–35 or: mainhand, offhand, helmet, chestplate, leggings, boots.");
		}
		ItemStack stackFrom = a.inventoryMenu.getSlot(fromMenu).getItem().copy();
		ItemStack stackTo = a.inventoryMenu.getSlot(toMenu).getItem().copy();
		if (stackFrom.isEmpty() && stackTo.isEmpty()) {
			return ToolResult.error("Both slots are empty; nothing to move.");
		}
		net.minecraft.world.inventory.InventoryMenu menu = a.inventoryMenu;
		menu.clicked(fromMenu, 0, net.minecraft.world.inventory.ClickType.PICKUP, a); // 拿起 from
		menu.clicked(toMenu, 0, net.minecraft.world.inventory.ClickType.PICKUP, a);   // 放到 to（交换）
		menu.clicked(fromMenu, 0, net.minecraft.world.inventory.ClickType.PICKUP, a); // 放回 from

		// 以点击后的真实内容为准汇报：原版 mayPlace 可能拒绝放置（如剑放进头盔槽），
		// 三次点击的净效果是“什么都没动”——和真实玩家一样，不能误报“已交换”
		ItemStack afterFrom = menu.getSlot(fromMenu).getItem().copy();
		ItemStack afterTo = menu.getSlot(toMenu).getItem().copy();
		boolean moved = !ItemStack.matches(afterFrom, stackFrom) || !ItemStack.matches(afterTo, stackTo);

		com.swaydy.opencraft.logging.DebugLog.log("player_action",
				"玩家形态助手移动物品 {} [{}] ↔ {} [{}]（背包菜单点击，实际生效={}）",
				stackFrom.isEmpty() ? "empty" : AiCompanionService.shortName(stackFrom.getItem().getDescriptionId()), fromStr,
				stackTo.isEmpty()   ? "empty" : AiCompanionService.shortName(stackTo.getItem().getDescriptionId()),   toStr,
				moved);

		String fromName = afterFrom.isEmpty() ? "empty" : AiCompanionService.shortName(afterFrom.getItem().getDescriptionId());
		String toName   = afterTo.isEmpty()   ? "empty" : AiCompanionService.shortName(afterTo.getItem().getDescriptionId());
		if (!moved) {
			return ToolResult.error("Nothing moved: the vanilla inventory rejected it "
					+ "(e.g. armor slots only accept matching armor). [" + fromStr + "] and [" + toStr
					+ "] are unchanged (" + fromName + " / " + toName + ").");
		}
		return ToolResult.ok("Swapped: [" + fromStr + "] " + fromName + " ↔ [" + toStr + "] " + toName + ".");
	}

	static ToolResult hotbarSelect(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("Tool player_hotbar_select requires a player-form assistant.");
		}
		ToolArgs t = new ToolArgs(args);
		int slot = t.intOf("slot", -1);
		if (slot < 0 || slot > 8) {
			return ToolResult.error("Slot must be 0–8 (hotbar slots).");
		}
		// 与原版 ServerboundSetCarriedItemPacket 的服务端处理一致（setSelectedSlot）；
		// 主手物品变更由 doTick 的装备检测自动同步给其他玩家
		a.getInventory().setSelectedSlot(slot);
		com.swaydy.opencraft.logging.DebugLog.log("player_action",
				"玩家形态助手切换主手槽位 → {}", slot);
		ItemStack held = a.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
		return ToolResult.ok("Selected hotbar slot " + slot + " as main hand"
				+ (held.isEmpty() ? " (empty)."
						: " (holding " + AiCompanionService.shortName(held.getItem().getDescriptionId()) + ")."));
	}

	/**
	 * 槽位名/数字 → 助手自己的背包菜单（InventoryMenu）槽位索引：
	 * 结果 0 | 合成 1-4 | 护甲 5-8（头/胸/腿/靴）| 副手 9 | 主背包 10-36（容器 9-35）| 快捷栏 37-45（容器 0-8）。
	 */
	private static Integer menuSlotIndex(AiAssistantPlayer a, String s) {
		try {
			int idx = Integer.parseInt(s);
			if (idx < 0 || idx >= MAIN_SLOTS) {
				return null;
			}
			// 玩家槽位 → InventoryMenu 槽位映射（1.21.11 的 InventoryMenu 布局）：
			//   0-8 快捷栏 (playerInventory 0-8) → 菜单 37-45
			//   9-35 主背包 (playerInventory 9-35) → 菜单 10-36
			// 旧版 36+idx 是错误的（菜单 36 是主背包最后一行，不是快捷栏第一格）
			return idx < 9 ? 37 + idx : idx + 1;
		} catch (NumberFormatException ignored) {
			// 命名槽位
		}
		return switch (s.toLowerCase(java.util.Locale.ROOT)) {
			case "mainhand", "main_hand", "main" -> 37 + a.getInventory().getSelectedSlot();
			case "offhand", "off_hand", "off" -> 9;
			case "helmet", "head" -> 5;
			case "chestplate", "chest" -> 6;
			case "leggings", "legs" -> 7;
			case "boots", "feet" -> 8;
			default -> null;
		};
	}

	/** 槽位引用：主背包格（index）或装备槽（equipSlot）。 */
	private static final class SlotRef {
		final int index;                                          // ≥0 → 主背包
		final net.minecraft.world.entity.EquipmentSlot equipSlot; // null → 主背包

		SlotRef(int index) { this.index = index; this.equipSlot = null; }
		SlotRef(net.minecraft.world.entity.EquipmentSlot s) { this.index = -1; this.equipSlot = s; }

		ItemStack get(AiAssistantPlayer a, Inventory inv) {
			return equipSlot != null ? a.getItemBySlot(equipSlot) : inv.getItem(index);
		}
		void set(AiAssistantPlayer a, Inventory inv, ItemStack stack) {
			if (equipSlot != null) a.setItemSlot(equipSlot, stack);
			else inv.setItem(index, stack);
		}
	}

	private static SlotRef parseSlot(String s) {
		// 数字 → 主背包
		try {
			int idx = Integer.parseInt(s);
			if (idx >= 0 && idx < MAIN_SLOTS) return new SlotRef(idx);
			return null;
		} catch (NumberFormatException ignored) {}
		// 命名装备槽
		net.minecraft.world.entity.EquipmentSlot eq =
				resolveEquipmentSlot(s.toLowerCase(java.util.Locale.ROOT), ItemStack.EMPTY);
		return eq != null ? new SlotRef(eq) : null;
	}

	/** 附近（水平 5 格/上下 3 格）最近的工作台位置；没有返回 null（3×3 合成必须真站在它旁）。 */
	private static BlockPos findWorkbenchNearby(AiAssistantPlayer a) {
		if (!(a.level() instanceof ServerLevel level)) {
			return null;
		}
		BlockPos center = a.blockPosition();
		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;
		for (int dx = -5; dx <= 5; dx++) {
			for (int dy = -3; dy <= 3; dy++) {
				for (int dz = -5; dz <= 5; dz++) {
					BlockPos p = center.offset(dx, dy, dz);
					if (!level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) {
						continue;
					}
					double d = center.distSqr(p);
					if (d < bestDist) {
						bestDist = d;
						best = p;
					}
				}
			}
		}
		return best;
	}

	/**
	 * 解析目标装备槽：优先按名字，否则按物品类型自动检测（实现了 Equipable 的护甲
	 * 自动定位正确槽；其余默认主手）。
	 */
	private static net.minecraft.world.entity.EquipmentSlot resolveEquipmentSlot(
			String slotName, ItemStack stack) {
		if (!slotName.isEmpty()) {
			return switch (slotName) {
				case "mainhand", "main_hand", "main" ->
						net.minecraft.world.entity.EquipmentSlot.MAINHAND;
				case "offhand", "off_hand", "off" ->
						net.minecraft.world.entity.EquipmentSlot.OFFHAND;
				case "helmet", "head" ->
						net.minecraft.world.entity.EquipmentSlot.HEAD;
				case "chestplate", "chest" ->
						net.minecraft.world.entity.EquipmentSlot.CHEST;
				case "leggings", "legs" ->
						net.minecraft.world.entity.EquipmentSlot.LEGS;
				case "boots", "feet" ->
						net.minecraft.world.entity.EquipmentSlot.FEET;
				default -> null;
			};
		}
		// 按物品 ID 路径自动检测（helmet/chestplate/leggings/boots/shield → 对应槽；其余 → 主手）
		if (!stack.isEmpty()) {
			String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
			if (path.contains("helmet") || path.contains("cap") || path.contains("hood")) {
				return net.minecraft.world.entity.EquipmentSlot.HEAD;
			}
			if (path.contains("chestplate") || path.contains("tunic") || path.contains("jacket")) {
				return net.minecraft.world.entity.EquipmentSlot.CHEST;
			}
			if (path.contains("leggings") || path.contains("pants")) {
				return net.minecraft.world.entity.EquipmentSlot.LEGS;
			}
			if (path.contains("boots") || path.contains("shoes")) {
				return net.minecraft.world.entity.EquipmentSlot.FEET;
			}
			if (path.contains("shield")) {
				return net.minecraft.world.entity.EquipmentSlot.OFFHAND;
			}
		}
		// 默认：主手（武器/工具/其他）
		return net.minecraft.world.entity.EquipmentSlot.MAINHAND;
	}

	static ToolResult listInventory(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("Tool player_inventory requires a player-form assistant.");
		}
		ToolArgs t = new ToolArgs(args);
		String whose = t.strOf("whose", "self").trim().toLowerCase(java.util.Locale.ROOT);
		boolean owner = whose.equals("player") || whose.equals("owner")
				|| whose.equals("玩家") || whose.equals("主人");
		return ToolResult.ok(owner
				? "Owner inventory: " + formatPlayerInventory(ctx.owner())
				: "Assistant inventory: " + formatPlayerInventory(a));
	}

	/**
	 * 完整背包详情（供 player_inventory）：逐非空槽列出——带槽号（与 player_item_move
	 * 的参数一致：0–8 快捷栏、9–35 主背包）、当前选中主手标记、数量、工具/装备耐久、
	 * 装备/副手，并统计空槽数。空槽不逐格列出（避免噪音），但总数给出——模型需要的是
	 * "我有哪些、各在几号槽、还剩多少空位"。
	 */
	private static String formatPlayerInventory(Player p) {
		Inventory inv = p.getInventory();
		int selected = Math.max(0, Math.min(8, inv.getSelectedSlot()));
		int empty = 0;
		List<String> hotbar = new ArrayList<>();
		for (int i = 0; i < 9; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) {
				empty++;
				continue;
			}
			hotbar.add("[" + i + "] " + itemLabel(s) + (i == selected ? " (selected mainhand)" : ""));
		}
		List<String> backpack = new ArrayList<>();
		for (int i = 9; i < MAIN_SLOTS; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) {
				empty++;
				continue;
			}
			backpack.add("[" + i + "] " + itemLabel(s));
		}
		List<String> equip = new ArrayList<>();
		for (net.minecraft.world.entity.EquipmentSlot es : new net.minecraft.world.entity.EquipmentSlot[]{
				net.minecraft.world.entity.EquipmentSlot.HEAD,
				net.minecraft.world.entity.EquipmentSlot.CHEST,
				net.minecraft.world.entity.EquipmentSlot.LEGS,
				net.minecraft.world.entity.EquipmentSlot.FEET,
				net.minecraft.world.entity.EquipmentSlot.OFFHAND}) {
			ItemStack s = p.getItemBySlot(es);
			if (s.isEmpty()) {
				continue;
			}
			equip.add(equipSlotDisplay(es) + "=" + itemLabel(s));
		}
		StringBuilder sb = new StringBuilder("hotbar[0-8]: ");
		sb.append(hotbar.isEmpty() ? "empty" : String.join(" | ", hotbar));
		sb.append("; backpack[9-35]: ");
		sb.append(backpack.isEmpty() ? "empty" : String.join(" | ", backpack));
		sb.append("; equipment: ");
		sb.append(equip.isEmpty() ? "none" : String.join(" | ", equip));
		if (empty > 0) {
			sb.append("; empty slots: ").append(empty);
		}
		return sb.toString();
	}

	/** 单个物品标签：短名 + 数量（&gt;1 时）+ 耐久（可受损物品的"剩余/上限"）。 */
	private static String itemLabel(ItemStack s) {
		StringBuilder b = new StringBuilder(AiCompanionService.shortName(s.getItem().getDescriptionId()));
		if (s.getCount() > 1) {
			b.append('×').append(s.getCount());
		}
		if (s.getMaxDamage() > 0) {
			b.append(" (").append(s.getMaxDamage() - s.getDamageValue()).append('/').append(s.getMaxDamage()).append(')');
		}
		return b.toString();
	}

	private static String equipSlotDisplay(net.minecraft.world.entity.EquipmentSlot es) {
		return switch (es) {
			case HEAD -> "helmet";
			case CHEST -> "chestplate";
			case LEGS -> "leggings";
			case FEET -> "boots";
			default -> "offhand";
		};
	}

	static ToolResult handToPlayer(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("Tool player_hand_to_player requires a player-form assistant.");
		}
		ToolArgs t = new ToolArgs(args);
		String itemId = t.strOf("item", "");
		int amount = Math.max(1, Math.min(640, t.intOf("amount", 1)));
		Holder<net.minecraft.world.item.Item> item = AiCompanionService.resolveItem(itemId);
		if (item == null) {
			return ToolResult.error("I don't know the item \"" + itemId + "\".");
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
			return ToolResult.error("The assistant's inventory has no " + AiCompanionService.shortName(item.value().getDescriptionId())
					+ "; can't hand it to you.");
		}
		return ToolResult.ok("Handed you " + AiCompanionService.shortName(item.value().getDescriptionId()) + " ×" + given + ".");
	}

	// ------------------------------------------------------------------
	// 容器交互（箱子/桶/潜影盒/熔炉/漏斗/发射器/末影箱…）
	// 打开 = ServerPlayerGameMode.useItemOn(sneak=false)（真实右键路径）→ BlockState.useWithoutItem
	//   → player.openMenu → a.containerMenu 变成 ChestMenu/FurnaceMenu/…；
	// 取放 = AbstractContainerMenu.quickMoveStack（原版 shift-click，容器↔玩家侧双向路由）；
	// 关闭 = ServerPlayer.closeContainer。
	// 容器侧槽位判定：菜单里 container != a.getInventory() 的前导槽（vanilla 保证容器槽在前）。
	// ------------------------------------------------------------------

	static ToolResult containerOpen(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("Tool player_container_open requires a player-form assistant.");
		}
		ToolArgs t = new ToolArgs(args);
		int x = t.intOf("x", Integer.MIN_VALUE);
		int y = t.intOf("y", Integer.MIN_VALUE);
		int z = t.intOf("z", Integer.MIN_VALUE);
		if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || z == Integer.MIN_VALUE) {
			return ToolResult.error("player_container_open requires integer parameters x, y, z.");
		}
		ServerLevel level = ctx.level();
		if (level != ctx.owner().level()) {
			return ToolResult.error("Can only open containers in the dimension the owner is currently in.");
		}
		BlockPos pos = new BlockPos(x, y, z);
		double maxDist = a.getConfig().maxDistance;
		if (ctx.owner().distanceToSqr(pos.getCenter()) > maxDist * maxDist) {
			return ToolResult.error("The container is more than " + (int) maxDist + " blocks from the owner — too far.");
		}
		GlobalPos cfgBlock = a.getConfigBlock();
		if (cfgBlock != null && cfgBlock.dimension().equals(level.dimension())
				&& cfgBlock.pos().equals(pos)) {
			return ToolResult.error("That is my config block (AI Logo Block); can't open it.");
		}
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) {
			return ToolResult.error("(" + x + "," + y + "," + z + ") is air; no container there.");
		}
		net.minecraft.world.MenuProvider provider = state.getMenuProvider(level, pos);
		if (provider == null) {
			return ToolResult.error("(" + x + "," + y + "," + z + ") is not a container ("
					+ AiCompanionService.shortName(state.getBlock().getDescriptionId())
					+ "). Use player_find \"chest\" to locate a container block.");
		}
		Vec3 standPos = mineStandPos(level, pos, a.position());
		Vec3 hitLoc = Vec3.atCenterOf(pos);
		if (a.getEyePosition().distanceTo(hitLoc) <= REACH) {
			return doOpenContainer(a, level, pos);
		}
		a.movement().moveTo(standPos, a.getConfig().speed, true);
		a.movement().whenArrived(() -> {
			ToolResult r = doOpenContainer(a, level, pos);
			a.movement().completeAction(a.movement().currentActionToken(), r.message(), r.ok());
		});
		return ToolResult.deferred("Walking over to open the container at (" + x + "," + y + "," + z
				+ ") — async action: the outcome [Event] will arrive automatically; do not re-issue.");
	}

	/** 真实玩家右键打开容器（sneak=false，直接调 useItemOn）。 */
	private static ToolResult doOpenContainer(AiAssistantPlayer a, ServerLevel level, BlockPos pos) {
		if (a.isRemoved()) {
			return ToolResult.error("The assistant is gone; cannot open the container.");
		}
		boolean wasSneaking = a.isShiftKeyDown();
		if (wasSneaking) {
			a.setShiftKeyDown(false);
		}
		BlockState state = level.getBlockState(pos);
		Vec3 center = Vec3.atCenterOf(pos);
		Direction face = Direction.getApproximateNearest(a.getEyePosition().subtract(center));
		BlockHitResult hit = new BlockHitResult(center, face, pos, false);
		InteractionResult result;
		try {
			result = a.gameMode.useItemOn(a, level, a.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
		} catch (Exception e) {
			com.swaydy.opencraft.logging.DebugLog.log("player_action",
					"玩家形态助手打开容器 {} 异常: {}", pos.toShortString(), e.toString());
			return ToolResult.error("Opening the container failed: " + e.getClass().getSimpleName());
		} finally {
			a.setShiftKeyDown(wasSneaking);
		}
		net.minecraft.world.inventory.AbstractContainerMenu menu = a.containerMenu;
		int n = containerRegionSize(a, menu);
		if (n <= 0) {
			return ToolResult.error("Right-clicking the block did not open a container menu (result " + result
					+ "); it may be locked or already opened elsewhere.");
		}
		com.swaydy.opencraft.logging.DebugLog.log("player_action",
				"玩家形态助手打开容器 {}（{} 槽，菜单 {}）", pos.toShortString(), n,
				menu.getClass().getSimpleName());
		return ToolResult.ok("Opened " + containerName(a, menu) + " (" + n + " slots). "
				+ "Container slots are numbered 0-" + (n - 1) + "; my inventory is on the other side. "
				+ "Use player_container_list to see the contents, player_container_take/put to move items, "
				+ "and player_container_close to close it.");
	}

	/**
	 * 容器侧槽位数：菜单里 container != 玩家背包 的前导槽个数（vanilla 保证容器槽在前）。
	 * 菜单为 null / inventoryMenu 返回 0（表示没有打开容器）。
	 */
	private static int containerRegionSize(Player p, net.minecraft.world.inventory.AbstractContainerMenu menu) {
		if (menu == null || menu == p.inventoryMenu) {
			return 0;
		}
		int n = 0;
		for (net.minecraft.world.inventory.Slot slot : menu.slots) {
			if (slot.container == p.getInventory()) {
				break;
			}
			n++;
		}
		return n;
	}

	/** 容器的显示名（优先取容器侧的 Nameable 名；失败回退通用名）。 */
	private static String containerName(Player p, net.minecraft.world.inventory.AbstractContainerMenu menu) {
		int n = containerRegionSize(p, menu);
		if (n > 0 && n <= menu.slots.size()) {
			net.minecraft.world.inventory.Slot slot = menu.slots.get(n - 1);
			net.minecraft.world.Container c = slot.container;
			if (c instanceof net.minecraft.world.Nameable nameable) {
				String name = nameable.getDisplayName().getString();
				if (name != null && !name.isEmpty()) {
					return name;
				}
			}
		}
		// 更精确的 fallback：ChestMenu 有 getRowCount
		if (menu instanceof net.minecraft.world.inventory.ChestMenu cm) {
			int rows = cm.getRowCount();
			return rows == 1 ? "Chest (single)" : rows == 6 ? "Double chest" : "Chest (" + rows + " rows)";
		}
		return "Container";
	}

	static ToolResult containerList(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("Tool player_container_list requires a player-form assistant.");
		}
		net.minecraft.world.inventory.AbstractContainerMenu menu = a.containerMenu;
		int n = containerRegionSize(a, menu);
		if (n <= 0) {
			return ToolResult.error("No container is open right now. Use player_container_open <x y z> first.");
		}
		List<String> containerItems = new ArrayList<>();
		for (int i = 0; i < n && i < menu.slots.size(); i++) {
			ItemStack s = menu.slots.get(i).getItem();
			if (s.isEmpty()) {
				continue;
			}
			containerItems.add("[" + i + "] " + itemLabel(s));
		}
		String name = containerName(a, menu);
		return ToolResult.ok("Open " + name + " (" + n + " slots): "
				+ (containerItems.isEmpty() ? "empty" : String.join(" | ", containerItems))
				+ "; My inventory: " + formatPlayerInventory(a));
	}

	static ToolResult containerTake(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("Tool player_container_take requires a player-form assistant.");
		}
		ToolArgs t = new ToolArgs(args);
		String itemId = t.strOf("item", "");
		int amount = Math.max(1, Math.min(6400, t.intOf("amount", Integer.MAX_VALUE)));
		Holder<net.minecraft.world.item.Item> item = AiCompanionService.resolveItem(itemId);
		if (item == null) {
			return ToolResult.error("I don't know the item \"" + itemId + "\"; use an item ID like minecraft:oak_planks.");
		}
		net.minecraft.world.inventory.AbstractContainerMenu menu = a.containerMenu;
		int n = containerRegionSize(a, menu);
		if (n <= 0) {
			return ToolResult.error("No container is open right now. Use player_container_open <x y z> first.");
		}
		int taken = 0;
		for (int i = 0; i < n && taken < amount; i++) {
			ItemStack s = menu.slots.get(i).getItem();
			if (s.isEmpty() || !s.is(item)) {
				continue;
			}
			int before = s.getCount();
			menu.quickMoveStack(a, i);
			int after = menu.slots.get(i).getItem().getCount();
			int moved = before - after;
			if (moved <= 0) {
				break; // 没移动成功（背包满等）——避免死循环
			}
			taken += moved;
		}
		String shortName = AiCompanionService.shortName(item.value().getDescriptionId());
		if (taken == 0) {
			return ToolResult.error("Found no " + shortName + " in the open container, or my inventory is full.");
		}
		return ToolResult.ok("Took " + shortName + " ×" + taken + " from the container into my inventory."
				+ (taken < amount ? "" : " (amount cap reached)"));
	}

	static ToolResult containerPut(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("Tool player_container_put requires a player-form assistant.");
		}
		ToolArgs t = new ToolArgs(args);
		String itemId = t.strOf("item", "");
		int amount = Math.max(1, Math.min(6400, t.intOf("amount", Integer.MAX_VALUE)));
		Holder<net.minecraft.world.item.Item> item = AiCompanionService.resolveItem(itemId);
		if (item == null) {
			return ToolResult.error("I don't know the item \"" + itemId + "\"; use an item ID like minecraft:oak_planks.");
		}
		net.minecraft.world.inventory.AbstractContainerMenu menu = a.containerMenu;
		int n = containerRegionSize(a, menu);
		if (n <= 0) {
			return ToolResult.error("No container is open right now. Use player_container_open <x y z> first.");
		}
		// 玩家侧槽位 = 容器区域之后的所有槽（背包+快捷栏）
		int moved = 0;
		for (int i = n; i < menu.slots.size() && moved < amount; i++) {
			ItemStack s = menu.slots.get(i).getItem();
			if (s.isEmpty() || !s.is(item)) {
				continue;
			}
			int before = s.getCount();
			menu.quickMoveStack(a, i);
			int after = menu.slots.get(i).getItem().getCount();
			int m = before - after;
			if (m <= 0) {
				break; // 容器满等——避免死循环
			}
			moved += m;
		}
		String shortName = AiCompanionService.shortName(item.value().getDescriptionId());
		if (moved == 0) {
			return ToolResult.error("Found no " + shortName + " in my inventory, or the container is full.");
		}
		return ToolResult.ok("Put " + shortName + " ×" + moved + " from my inventory into the container."
				+ (moved < amount ? "" : " (amount cap reached)"));
	}

	static ToolResult containerClose(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("Tool player_container_close requires a player-form assistant.");
		}
		if (a.containerMenu == a.inventoryMenu) {
			return ToolResult.ok("No container was open (my inventory menu is already active).");
		}
		a.closeContainer();
		com.swaydy.opencraft.logging.DebugLog.log("player_action", "玩家形态助手关闭容器");
		return ToolResult.ok("Closed the container (my inventory menu is active again).");
	}


	/** 想找的方块集合：先精确解析；失败则按关键词展开注册表（id/描述键子串匹配，限 6 种）。 */
	private static Set<Block> wantedBlocks(String target) {
		Set<Block> out = new java.util.HashSet<>();
		Holder<net.minecraft.world.level.block.Block> exact = AiCompanionService.resolveBlock(target);
		if (exact != null) {
			out.add(exact.value());
			return out;
		}
		for (Map.Entry<ResourceKey<Block>, Block> e : BuiltInRegistries.BLOCK.entrySet()) {
			if (out.size() >= 6) {
				break;
			}
			String id = e.getKey().identifier().getPath();
			String desc = e.getValue().getDescriptionId().toLowerCase(java.util.Locale.ROOT);
			if (id.contains(target) || desc.contains(target)) {
				out.add(e.getValue());
			}
		}
		return out;
	}

	/** 该关键词是否应做实体查询（玩家/怪物/掉落物/生物 或 可解析的实体类型 id）。 */
	private static boolean isEntityQuery(String target) {
		if (target.contains("玩家") || target.contains("player")
				|| target.contains("怪物") || target.contains("monster")
				|| target.contains("zombie") || target.contains("skeleton")
				|| target.contains("掉落") || target.contains("drop")
				|| target.contains("animal") || target.contains("生物") || target.contains("living")) {
			return true;
		}
		// 物品名/ID 也要查实体:掉落物（地上的圆石/木头等）只有实体查询能定位——
		// 方块查询只会命中世界里的方块,搜 "cobblestone" 找不到掉在地上的圆石
		return tryResolveEntityType(target) != null
				|| AiCompanionService.resolveItem(target) != null;
	}

	private static boolean matchesEntity(Entity e, String target) {
		if (target.contains("玩家") || target.contains("player")) {
			return e instanceof Player;
		}
		if (target.contains("怪物") || target.contains("monster")
				|| target.contains("zombie") || target.contains("skeleton")) {
			return e instanceof Monster; // 僵尸/骷髅是怪物，一网打尽
		}
		if (e instanceof ItemEntity ie) {
			// 掉落物:泛关键词命中,或手持物品名/ID 命中——掉落物的实体类型描述是
			// "entity.minecraft.item"不含物品名,按名搜索必须看手持物品
			return target.contains("掉落") || target.contains("drop") || itemMatches(ie, target);
		}
		if (target.contains("掉落") || target.contains("drop")) {
			return false; // 掉落关键词只匹配 ItemEntity,已在上面处理
		}
		if (target.contains("living") || target.contains("生物") || target.contains("animal")) {
			return e instanceof LivingEntity;
		}
		EntityType<?> et = tryResolveEntityType(target);
		if (et != null) {
			return e.getType() == et || e.getType().getDescriptionId().contains(target);
		}
		// 其它：把关键词当实体类型描述子串匹配
		return e.getType().getDescriptionId().toLowerCase(java.util.Locale.ROOT).contains(target);
	}

	/** 掉落物手持物品是否与搜索词匹配:短名/描述键子串,或解析成同一物品 ID。 */
	private static boolean itemMatches(ItemEntity ie, String target) {
		String descId = ie.getItem().getItem().getDescriptionId(); // 如 item.minecraft.cobblestone
		String lower = target.toLowerCase(java.util.Locale.ROOT);
		if (AiCompanionService.shortName(descId).contains(lower)
				|| descId.toLowerCase(java.util.Locale.ROOT).contains(lower)) {
			return true;
		}
		var resolved = AiCompanionService.resolveItem(target);
		return resolved != null && ie.getItem().is(resolved);
	}

	private static EntityType<?> tryResolveEntityType(String id) {
		String[] candidates = id.contains(":")
				? new String[] {id}
				: new String[] {id, "minecraft:" + id};
		for (String c : candidates) {
			try {
				
				Identifier ident = Identifier.parse(c);
				var opt = BuiltInRegistries.ENTITY_TYPE.get(ident);
				if (!opt.isEmpty()) {
					return opt.get().value();
				}
			} catch (Exception ignored) {
				// 非法 ID 形状：试下一个候选
			}
		}
		return null;
	}

	/** 一条定位结果：`(x,y,z) 3 east 2 south distance 2.4 blocks(类型)`;与助手垂直差 ≥3 格时
	 * 追加 `(N blocks below/above you)`——移动只改变水平位置,模型需要知道目标在脚下还是头顶。 */
	private static String formatTarget(BlockPos pos, String descId, AiAssistantPlayer a) {
		double dist = Math.round(Math.sqrt(a.distanceToSqr(
				pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) * 10.0) / 10.0;
		String line = "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ") "
				+ AiCompanionService.bearingTo(a.blockPosition(), pos) + " distance " + dist + " blocks(" + AiCompanionService.shortName(descId) + ")";
		int dy = pos.getY() - a.blockPosition().getY();
		if (Math.abs(dy) >= 3) {
			line += " (" + Math.abs(dy) + " blocks " + (dy < 0 ? "below" : "above") + " you)";
		}
		return line;
	}

	/** 执行真正的玩家式放置（ServerPlayerGameMode.useItemOn）;结果可直接回给模型或经动作回调上报。 */
	private static ToolResult doPlace(AiAssistantPlayer a, ServerLevel level, ItemStack item,
	                                  BlockHitResult hit, boolean sneak) {
		if (a.isRemoved() || item.isEmpty()) {
			return ToolResult.error("The assistant is gone or the main hand is empty; cannot place.");
		}
		BlockPos target = hit.getBlockPos().relative(hit.getDirection());
		// 记录放置前主手物品数量，用于判断物品是否被消耗（比仅查块状态更可靠）
		int countBefore = a.getMainHandItem().getCount();
		// 潜行放置（原版 useItemOn 的 shift 分支：跳过方块交互直接用物品，
		// 才能“对着箱子/熔炉放方块”而不是打开它们）；完成后恢复
		boolean wasSneaking = a.isShiftKeyDown();
		if (sneak) {
			a.setShiftKeyDown(true);
		}
		InteractionResult result;
		try {
			result = a.gameMode.useItemOn(a, level, item, InteractionHand.MAIN_HAND, hit);
		} finally {
			a.setShiftKeyDown(wasSneaking);
		}
		int countAfter = a.getMainHandItem().getCount();
		// 物品减少 OR 目标位置出现方块 → 放置成功（两条件取其一，防止某些方块
		// 落在非 target 坐标导致漏报；若物品被消耗就相信游戏的结果）
		boolean placed = countAfter < countBefore || !level.getBlockState(target).isAir();
		com.swaydy.opencraft.logging.DebugLog.log("player_action",
				"玩家形态助手 useItemOn({}, {}, {}) → {} (before={} after={})",
				target.getX(), target.getY(), target.getZ(), result, countBefore, countAfter);
		return placed ? ToolResult.ok("Placed the main-hand item at (" + target.getX() + "," + target.getY() + "," + target.getZ() + ").")
				: ToolResult.error("Placement had no effect (result " + result + "); the item may not be placeable or the spot is occupied.");
	}

	// ------------------------------------------------------------------
	// 合成辅助（网格匹配作用于玩家背包 36 格）
	// ------------------------------------------------------------------

	private static final CraftingInput EMPTY_INPUT =
			CraftingInput.of(1, 1, List.of(ItemStack.EMPTY));

	private static boolean needsWorkbench(CraftingRecipe recipe) {
		if (recipe instanceof ShapedRecipe shaped) {
			return shaped.getWidth() > 2 || shaped.getHeight() > 2;
		}
		if (recipe instanceof ShapelessRecipe) {
			return recipe.placementInfo().ingredients().size() > 4;
		}
		return false;
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
}
