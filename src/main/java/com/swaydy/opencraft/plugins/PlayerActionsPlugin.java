package com.swaydy.opencraft.plugins;

import com.google.gson.JsonObject;
import com.swaydy.opencraft.ai.AiCompanionService;
import com.swaydy.opencraft.assistant.player.AiAssistantPlayer;
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
import java.util.Set;

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
		gotoProps.add("x", ToolSchema.prop("integer", "Target X coordinate (absolute, integer)"));
		gotoProps.add("y", ToolSchema.prop("integer", "Target Y coordinate (absolute, integer)"));
		gotoProps.add("z", ToolSchema.prop("integer", "Target Z coordinate (absolute, integer)"));
		JsonObject mineProps = new JsonObject();
		mineProps.add("x", ToolSchema.prop("integer", "Block X coordinate"));
		mineProps.add("y", ToolSchema.prop("integer", "Block Y coordinate"));
		mineProps.add("z", ToolSchema.prop("integer", "Block Z coordinate"));
		JsonObject placeProps = new JsonObject();
		placeProps.add("x", ToolSchema.prop("integer", "X coordinate of the block to place against (the adjacent block)"));
		placeProps.add("y", ToolSchema.prop("integer", "Y coordinate of the block to place against"));
		placeProps.add("z", ToolSchema.prop("integer", "Z coordinate of the block to place against"));
		placeProps.add("face", ToolSchema.prop("string",
				"Which face to place on: up/down/north/south/east/west (default up)"));
		JsonObject craftProps = new JsonObject();
		craftProps.add("item", ToolSchema.prop("string",
				"Item id to craft, e.g. minecraft:diamond_block."));
		craftProps.add("amount", ToolSchema.prop("integer", "Amount to craft (default 1)."));
		JsonObject listProps = new JsonObject();
		listProps.add("whose", ToolSchema.prop("string",
				"Whose inventory to view: \"self\" (the assistant, default) or \"player\" (the owner)."));
		JsonObject handProps = new JsonObject();
		handProps.add("item", ToolSchema.prop("string", "Item id, e.g. minecraft:cobblestone."));
		handProps.add("amount", ToolSchema.prop("integer", "Amount (default 1)."));
		JsonObject lookProps = new JsonObject();
		lookProps.add("radius", ToolSchema.prop("integer", "Observation radius (default 8, max 16)."));
		JsonObject findProps = new JsonObject();
		findProps.add("target", ToolSchema.prop("string",
				"What to find: a block/item ID (minecraft:oak_log, oak_log) or a keyword (log, chest, iron, player, monster…)."));
		findProps.add("radius", ToolSchema.prop("integer", "Search radius (default 12, max 20)."));
		return List.of(
				new ToolDefinition("player_goto",
						"Have the assistant (as a player) walk to the given coordinates (absolute x,y,z). Movement is asynchronous: "
								+ "the call returns immediately and the assistant walks over by itself; then use player_look to check arrival.",
						ToolSchema.object(gotoProps, "x", "y", "z"),
						this::gotoTool),
				new ToolDefinition("player_stop",
						"Cancel the assistant's current movement and make it stop.",
						ToolSchema.object(new JsonObject()),
						this::stopTool),
				new ToolDefinition("player_jump",
						"Have the assistant jump in place (to hop over 1-block steps/small gaps; can be combined with player_goto "
								+ "for a running jump; only takes effect when on the ground).",
						ToolSchema.object(new JsonObject()),
						this::jump),
				new ToolDefinition("player_look",
						"Observe the assistant's surroundings: coordinates, facing, nearby blocks (counted by type), "
								+ "nearby players/monsters/dropped items (with distance), whether it is moving, and inventory/equipment summary. "
								+ "Observe before acting, and observe again to confirm after acting.",
						ToolSchema.object(lookProps),
						this::lookAround),
				new ToolDefinition("player_find",
						"Find things around the assistant by keyword/ID, returning exact coordinates + bearing "
								+ "(how many blocks east/south/west/north) + distance. "
								+ "target can be a block/item ID (e.g. minecraft:oak_log, oak_log) or a plain keyword "
								+ "(e.g. \"log\", \"chest\", \"iron\", \"player\", \"monster\"). "
								+ "Before acting (mining/placing/going to an item), call player_find to get exact coordinates — don't guess coordinates.",
						ToolSchema.object(findProps, "target"),
						this::findTarget),
				new ToolDefinition("player_mine",
						"Have the assistant (as a player) mine the block at the given coordinates: walk up to it and break it with the "
								+ "main-hand tool like a player; drops fall out as items and are auto-picked into the assistant's inventory. "
								+ "Asynchronous: returns immediately; confirm later with player_look. Cannot mine air, bedrock, or containers (chests/furnaces etc.).",
						ToolSchema.object(mineProps, "x", "y", "z"),
						this::mine),
				new ToolDefinition("player_place",
						"Have the assistant (as a player) place a block at the given position with its main-hand item: place it against the "
								+ "face of the block at (x,y,z). Requires a placeable item in the main hand (e.g. stone/planks). "
								+ "Asynchronous: if far away it walks over first, then places; confirm later with player_look.",
						ToolSchema.object(placeProps, "x", "y", "z"),
						this::place),
				new ToolDefinition("player_craft",
						"Have the assistant craft the given item using materials from its own player inventory (exactly like a player: "
								+ "2×2 and smaller recipes can be crafted anytime, 3×3 recipes need a nearby crafting table). "
								+ "Products go into the assistant's inventory; later you can hand them to the owner with player_hand_to_player.",
						ToolSchema.object(craftProps, "item"),
						this::craft),
				new ToolDefinition("player_inventory",
						"List the items in the assistant's (or the owner's) player inventory (36 slots + equipment + offhand).",
						ToolSchema.object(listProps),
						this::listInventory),
				new ToolDefinition("player_hand_to_player",
						"Take an item out of the assistant's inventory and hand it to the owner (goes into the owner's inventory; "
								+ "drops at the owner's feet if their inventory is full).",
						ToolSchema.object(handProps, "item"),
						this::handToPlayer));
	}

	@Override
	public String systemPromptFragment() {
		return "[Player form] You have joined the Minecraft server as a real player: with a full player inventory, equipment slots "
				+ "and player-style actions. player_goto/player_stop move, player_jump jumps (over 1-block steps/small gaps, "
				+ "combined with a movement target for a running jump), player_mine/player_place break/place blocks the player way "
				+ "(drops go straight into the inventory), player_craft crafts from inventory materials (same rules as a player; "
				+ "3×3 needs a crafting table), player_hand_to_player hands items to the owner, player_inventory/player_look observe "
				+ "state and surroundings, player_find finds things by keyword/ID and returns exact coordinates "
				+ "(always player_find first to get coordinates — don't guess). Observe before acting and confirm after acting; "
				+ "tool results begin with [tool success/failure] — read the marker first; never assume a tool succeeded; "
				+ "on failure try a different approach rather than retrying identically.";
	}

	@Override
	public String gameContextFragment(ToolContext ctx) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		sb.append("[Assistant state] position: x=").append(Math.round(a.getX()))
				.append(", y=").append(Math.round(a.getY()))
				.append(", z=").append(Math.round(a.getZ()))
				.append(", facing: ").append(AiCompanionService.facingName(a.getYRot()));
		sb.append(a.movement().isMoving() ? " | moving" : " | still");
		ServerLevel level = ctx.level();
		if (a.level() instanceof ServerLevel al) {
			level = al;
		}
		if (level != null) {
			sb.append(" | ").append(AiCompanionService.environmentCapsule(level, a.blockPosition(), 16));
		}
		sb.append(" | form: player");
		return sb.toString();
	}

	// ------------------------------------------------------------------
	// 工具实现
	// ------------------------------------------------------------------

	private ToolResult gotoTool(ToolContext ctx, JsonObject args) {
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
			return ToolResult.error("Target is more than " + (int) maxDist + " blocks from the owner — too far; "
					+ "move in steps or pick a closer target.");
		}
		a.movement().moveTo(new Vec3(x + 0.5, y, z + 0.5), a.getConfig().speed, true);
		return ToolResult.ok("Heading to (" + x + "," + y + "," + z + "). Use player_look to confirm arrival.");
	}

	private ToolResult stopTool(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("Tool player_stop requires a player-form assistant.");
		}
		a.movement().stop();
		return ToolResult.ok("Movement stopped.");
	}

	private ToolResult jump(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("Tool player_jump requires a player-form assistant.");
		}
		return a.movement().jump()
				? ToolResult.ok("Jumped (combine with a movement target to run over steps/gaps).")
				: ToolResult.error("Cannot jump right now: in mid-air or flying; land first and try again.");
	}

	private ToolResult lookAround(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("Tool player_look requires a player-form assistant.");
		}
		ToolArgs t = new ToolArgs(args);
		int radius = Math.max(1, Math.min(16, t.intOf("radius", 8)));
		ServerLevel level = ctx.level();
		BlockPos pos = a.blockPosition();

		StringBuilder sb = new StringBuilder();
		sb.append("position: x=").append(pos.getX()).append(", y=").append(pos.getY())
				.append(", z=").append(pos.getZ());
		sb.append(", facing: ").append(AiCompanionService.facingName(a.getYRot()));
		sb.append(", movement: ").append(a.movement().isMoving() ? "moving" : "still");
		sb.append(" | ").append(AiCompanionService.environmentCapsule(level, pos, 0));

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
			sb.append(". Almost no blocks within ").append(radius).append(" blocks.");
		} else {
			sb.append(". Nearby blocks: ");
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
			sb.append(". Nearby entities: ");
			int count = 0;
			for (Entity e : entities) {
				if (count >= 10) {
					sb.append("…");
					break;
				}
				double dist = Math.round(a.distanceTo(e) * 10.0) / 10.0;
				String type = e instanceof Player ? "Player"
						: e instanceof Monster ? "Monster"
						: e instanceof ItemEntity ? "Dropped item" : shortName(e.getType().getDescriptionId());
				// 带精确坐标 + 方位：模型据此才能判断“东西在哪”
				sb.append(type).append(" ").append(e.blockPosition().toShortString()).append(" ")
						.append(bearingTo(pos, e.blockPosition())).append("(").append(dist).append(" blocks) ")
						.append(" ");
				count++;
			}
		} else {
			sb.append(". No other entities nearby.");
		}
		sb.append(" | inventory: ").append(formatBackpack(a));
		return ToolResult.ok(sb.toString());
	}

	/**
	 * 按关键词/ID 找方块或实体，返回【精确坐标 + 方位 + 距离】——模型据此才能判断
	 * “东西在哪”，而不是拿着 oak_log 计数去猜坐标。方块：精确解析失败时按关键词
	 * 遍历注册表展开（id 路径或描述键子串匹配，限 6 种），再对半径内立方体扫描一次；
	 * 实体：按关键词/实体类型 id 查询指定类型。
	 */
	private ToolResult findTarget(ToolContext ctx, JsonObject args) {
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
						: e instanceof ItemEntity ? "Dropped item" : shortName(e.getType().getDescriptionId());
				lines.add(formatTarget(e.blockPosition(), label, a));
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
		return ToolResult.ok(sb.toString());
	}

	private ToolResult mine(ToolContext ctx, JsonObject args) {
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
		// 安全校验（与实体版挖掘一致）
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
		// 走到方块旁，到达后用真实的 ServerPlayerGameMode.destroyBlock 破坏
		a.movement().moveTo(new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5),
				a.getConfig().speed, true);
		a.movement().whenArrived(() -> doBreak(a, level, pos));
		return ToolResult.ok("Walking over to mine (" + x + "," + y + "," + z + ") as a player; "
				+ "drops will fall out and be auto-picked into the inventory.");
	}

	private ToolResult place(ToolContext ctx, JsonObject args) {
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
		if (a.getEyePosition().distanceTo(hitLoc) <= REACH) {
			return ToolResult.ok(doPlace(a, level, mainHand, hit));
		}
		// 太远：先走到放置位置旁，到达后再放
		a.movement().moveTo(new Vec3(target.getX() + 0.5, target.getY(), target.getZ() + 0.5),
				a.getConfig().speed, true);
		a.movement().whenArrived(() -> doPlace(a, level, a.getMainHandItem(), hit));
		return ToolResult.ok("Walking to the placement spot to place the main-hand item at (" + target.getX() + ","
				+ target.getY() + "," + target.getZ() + ").");
	}

	private ToolResult craft(ToolContext ctx, JsonObject args) {
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
			return ToolResult.ok("Crafted " + shortName(item.value().getDescriptionId()) + " ×"
					+ crafted.getCount() + " (put into the assistant's inventory)"
					+ (sets < amount ? ". Only enough materials for " + sets + " set(s)" : "") + ".");
		}
		if (sawWorkbenchRecipe) {
			return ToolResult.error("Crafting " + shortName(item.value().getDescriptionId())
					+ " requires a crafting table (3×3 grid, same as a player). Walk to a crafting table first and try again.");
		}
		return ToolResult.error("Cannot craft "
				+ shortName(item.value().getDescriptionId()) + " from the materials in my inventory. "
				+ "Not enough materials or no recipe.");
	}

	private ToolResult listInventory(ToolContext ctx, JsonObject args) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return ToolResult.error("Tool player_inventory requires a player-form assistant.");
		}
		ToolArgs t = new ToolArgs(args);
		String whose = t.strOf("whose", "self").toLowerCase(java.util.Locale.ROOT);
		if (whose.equals("player")) {
			return ToolResult.ok("Owner inventory: " + formatPlayerInventory(ctx.owner().getInventory()));
		}
		return ToolResult.ok("Assistant inventory: " + formatPlayerInventory(a.getInventory()));
	}

	private ToolResult handToPlayer(ToolContext ctx, JsonObject args) {
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
			return ToolResult.error("The assistant's inventory has no " + shortName(item.value().getDescriptionId())
					+ "; can't hand it to you.");
		}
		return ToolResult.ok("Handed you " + shortName(item.value().getDescriptionId()) + " ×" + given + ".");
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
		return tryResolveEntityType(target) != null;
	}

	private static boolean matchesEntity(Entity e, String target) {
		if (target.contains("玩家") || target.contains("player")) {
			return e instanceof Player;
		}
		if (target.contains("怪物") || target.contains("monster")
				|| target.contains("zombie") || target.contains("skeleton")) {
			return e instanceof Monster; // 僵尸/骷髅是怪物，一网打尽
		}
		if (target.contains("掉落") || target.contains("drop")) {
			return e instanceof ItemEntity;
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

	/** 一条定位结果：`(x,y,z) 3 east 2 south distance 2.4 blocks(类型)`。 */
	private static String formatTarget(BlockPos pos, String descId, AiAssistantPlayer a) {
		double dist = Math.round(Math.sqrt(a.distanceToSqr(
				pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) * 10.0) / 10.0;
		return "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ") "
				+ bearingTo(a.blockPosition(), pos) + " distance " + dist + " blocks(" + shortName(descId) + ")";
	}

	/** 从 / 到目标的方位（东南西北格数；原地返回 here）。 */
	private static String bearingTo(BlockPos from, BlockPos to) {
		int dx = to.getX() - from.getX();
		int dz = to.getZ() - from.getZ();
		StringBuilder sb = new StringBuilder();
		if (dx > 0) {
			sb.append(dx).append(" east");
		} else if (dx < 0) {
			sb.append(-dx).append(" west");
		}
		if (dz > 0) {
			sb.append(dz).append(" south");
		} else if (dz < 0) {
			sb.append(-dz).append(" north");
		}
		return sb.length() == 0 ? "here" : sb.toString();
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

	/** 执行真正的玩家式放置（ServerPlayerGameMode.useItemOn）。 */
	private static String doPlace(AiAssistantPlayer a, ServerLevel level, ItemStack item,
	                              BlockHitResult hit) {
		if (a.isRemoved() || item.isEmpty()) {
			return "The assistant is gone or the main hand is empty; cannot place.";
		}
		
		InteractionResult result = a.gameMode.useItemOn(a, level, item, InteractionHand.MAIN_HAND, hit);
		BlockPos target = hit.getBlockPos().relative(hit.getDirection());
		boolean placed = result.consumesAction()
				&& !level.getBlockState(target).isAir();
		com.swaydy.opencraft.debug.DebugLog.log("player_action",
				"玩家形态助手 useItemOn({}, {}, {}) → {}", target.getX(), target.getY(), target.getZ(), result);
		return placed ? "Placed the main-hand item at (" + target.getX() + "," + target.getY() + "," + target.getZ() + ")."
				: "Placement had no effect (result " + result + "); the item may not be placeable or the spot is occupied.";
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
		return shown == 0 ? "empty" : sb.toString();
	}

	private static String formatBackpack(AiAssistantPlayer a) {
		return formatPlayerInventory(a.getInventory());
	}

	private static String slotName(int index) {
		return switch (index) {
			case 36 -> "boots";
			case 37 -> "leggings";
			case 38 -> "chestplate";
			case 39 -> "helmet";
			case 40 -> "offhand";
			case 41 -> "body";
			case 42 -> "saddle";
			default -> "items";
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
