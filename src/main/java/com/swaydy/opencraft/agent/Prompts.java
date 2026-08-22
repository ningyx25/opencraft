package com.swaydy.opencraft.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.swaydy.opencraft.ai.AiBlockConfig;
import com.swaydy.opencraft.ai.AiCompanionService;
import com.swaydy.opencraft.assistant.AiAssistant;
import com.swaydy.opencraft.assistant.player.AiAssistantPlayer;
import com.swaydy.opencraft.plugins.ToolContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 非插件引入的提示词集中管理：system 提示词里不属于插件/预设的所有静态与动态片段。
 *
 * <p><b>结构约定（结构化提示词）</b>：整段 system 用 Markdown 组织——
 * 每个来源一个 `#` 大节（Identity / 预设各自的准则 / Capabilities / Game Context /
 * Current Task Plan）,插件片段与状态段用 `##` 小节;<b>数据段一律用 ```json 围栏的
 * JSON</b>（玩家状态、助手状态、任务计划）,字段自描述（radius/note 等）,方便模型解析
 * 与后续扩展。组装顺序：
 * 人设（# Identity：基础 + 名字 + 预设 persona）→ 插件提示词（# Capabilities）→
 * 玩家状态（## Player State）→ 助手状态（## Assistant State）→ 插件状态 →
 * 任务计划（# Current Task Plan）。
 *
 * <p>分工边界（什么在这里、什么不在）：
 * <ul>
 * <li><b>这里管</b>：静态文本（基础人设、历史压缩指令）＋ 配置相关文本（名字指令、
 * 人设组装）＋ 动态上下文（玩家状态、助手状态——近旁方块/大范围方块计数/附近实体/
 * 按槽位背包清单,完整吸收原 player_look/player_inventory）＋ system 整段组装；</li>
 * <li><b>不在这里</b>：各插件的 {@code systemPromptFragment}/{@code gameContextFragment}
 * （留在 plugins/ 各插件内,各自带 `##` 小节标题）、各预设的 personaPrompt
 * （留在 presets/,自带 `#` 大节标题）、守卫与工具结果文案（留在各守卫/执行器内）。</li>
 * </ul>
 *
 * <p>共用游戏工具方法（{@code facingName}/{@code bearingTo}/{@code shortName}）
 * 仍在 {@link AiCompanionService}——它们是通用显示工具，不是提示词文本。
 *
 * <p>每次构建的玩家/助手状态 JSON 会同步落一份快照到
 * {@code logs/opencraft/player.json} 与 {@code logs/opencraft/assistant.json}
 * （见 {@link com.swaydy.opencraft.logging.StateSnapshots}）,方便随时查看
 * “模型本轮看到的状态”。
 */
public final class Prompts {
	private Prompts() {
	}

	/**
	 * 所有预设共享的基础人设（非配置,随代码内置）:简短友好 + 用玩家语言。
	 * 具体"怎么做事/何时用工具"由各预设的 personaPrompt 与插件提示词决定。
	 */
	private static final String BASE_PERSONA = """
			You are an AI game assistant living in Minecraft, accompanying the player through adventures, building, and survival —
			a reliable and slightly humorous friend. Keep replies short (usually no more than 3~4 sentences) and answer in the language the player uses.""";

	/** 历史压缩的指令（拼在旧消息区段之后,要求模型只输出摘要正文——不能要求 markdown,否则改变摘要格式）。 */
	static final String COMPACT_INSTRUCTION = """
			Please compress the chat history between you and the player above into a short memory summary (within 150 words).
			Keep: important information about the player (name, needs, agreements, progress, todos), things you promised, unfinished tasks, key coordinates/items.
			No small talk, no line-by-line retelling — only distilled key points. Output only the summary text, with no prefix or formatting.""";

	/** 上下文里注入的近旁方块扫描半径（格,以助手为中心）。 */
	private static final int CONTEXT_NEARBY_RADIUS = 2;
	/** 近旁方块每层最多列出的类型数（防止 system 过长）。 */
	private static final int NEARBY_MAX_TYPES_PER_LAYER = 5;
	/** 单类型数量不超过该值时逐个给出 (dx,dz) 相对坐标（稀有/功能方块可定位）,超过只给计数。 */
	private static final int NEARBY_COORDS_MAX = 3;
	/** 上下文里注入的较大范围方块类型计数半径（格,吸收原 player_look 的方块观察）。 */
	private static final int CONTEXT_WIDE_RADIUS = 8;
	/** 大范围方块计数最多列出的类型数。 */
	private static final int WIDE_MAX_TYPES = 8;
	/** 上下文里注入的附近实体扫描半径（格,吸收原 player_look 的实体观察）。 */
	private static final int ENTITY_RADIUS = 8;
	/** 附近实体最多列出的个数。 */
	private static final int ENTITY_MAX = 8;
	/** 主人背包清单最多列出的物品条目数（防止 system 过长）。 */
	private static final int CONTEXT_MAX_ITEMS = 16;

	// ------------------------------------------------------------------
	// 人设段（静态 + 配置）
	// ------------------------------------------------------------------

	/**
	 * 组装"人设 + 名字"的 system 文本（供对话与打招呼共用）:
	 * # Identity（基础人设）+ ## Name（名字指令）+ 预设 personaPrompt（自带 # 大节）。
	 * 不再有玩家可编辑的系统提示词——人设完全由 Agent 预设决定。
	 */
	public static String persona(AiBlockConfig config, AgentDefinition agent) {
		StringBuilder sb = new StringBuilder("# Identity\n\n");
		sb.append(BASE_PERSONA);
		sb.append("\n\n## Name\n\nYour name is ").append(config.effectiveName())
				.append(". Always refer to yourself by this name and use no other.");
		if (agent != null && agent.personaPrompt() != null && !agent.personaPrompt().isBlank()) {
			sb.append("\n\n").append(agent.personaPrompt());
		}
		return sb.toString();
	}

	// ------------------------------------------------------------------
	// 动态上下文段（## 小节 + ```json 数据）
	// ------------------------------------------------------------------

	/**
	 * 玩家（主人）状态段（{@code ## Player State} + JSON）：每轮请求都会重建
	 * （见 {@code AgentRuntime.runRound} 对 system 的刷新）,因此位置/天气/装备等都是最新的,
	 * 不是提问那一刻的静态快照。
	 */
	public static String playerState(ServerPlayer player) {
		try {
			Level level = player.level();
			long dayTime = level.getDayTime();
			long day = dayTime / 24000 + 1;
			long timeOfDay = dayTime % 24000;
			int hour = (int) ((timeOfDay / 1000 + 6) % 24);
			int minute = (int) ((timeOfDay % 1000) / 100 * 6);
			BlockPos pos = player.blockPosition();

			JsonObject o = new JsonObject();
			o.addProperty("name", player.getName().getString());
			o.addProperty("dimension", String.valueOf(level.dimension().identifier()));
			JsonObject p = new JsonObject();
			p.addProperty("x", pos.getX());
			p.addProperty("y", pos.getY());
			p.addProperty("z", pos.getZ());
			o.add("position", p);
			o.addProperty("facing", AiCompanionService.facingName(player.getYRot()));
			JsonElement env = environmentJson(level, pos, 16);
			if (env != null) {
				o.add("environment", env);
			}
			JsonObject t = new JsonObject();
			t.addProperty("day", day);
			t.addProperty("clock", String.format("%02d:%02d", hour, minute));
			t.addProperty("phase", phaseOfTime(timeOfDay));
			o.add("time", t);
			o.addProperty("health", (int) Math.round(player.getHealth()));
			o.addProperty("hunger", player.getFoodData().getFoodLevel());
			o.addProperty("xp_level", player.experienceLevel);
			o.addProperty("game_mode", player.gameMode.getGameModeForPlayer().name());
			o.addProperty("body", bodyState(player));
			o.add("effects", effectsJson(player));
			o.add("equipment", equipmentJson(player));
			o.add("looking_at", lookingAtJson(level, player));
			ItemStack mainHand = player.getMainHandItem();
			o.addProperty("main_hand", mainHand.isEmpty() ? "empty hand"
					: AiCompanionService.shortName(mainHand.getItem().getDescriptionId()));
			o.add("inventory", inventoryArray(player.getInventory()));
			com.swaydy.opencraft.logging.StateSnapshots.write("player.json", o);
			return "## Player State\n\n" + jsonBlock(o);
		} catch (Exception e) {
			return "## Player State\n\n(unavailable)";
		}
	}

	/**
	 * 助手自身状态段（{@code ## Assistant State} + JSON）：坐标/朝向/是否移动 + 环境 +
	 * 半径 {@link #CONTEXT_NEARBY_RADIUS} 格近旁方块（稀有方块带相对坐标）+
	 * 半径 {@link #CONTEXT_WIDE_RADIUS} 格方块类型计数 + 附近实体（带坐标/方位/距离）+
	 * 按槽位的详细背包/装备清单（含耐久与主手标记,槽号与背包工具参数一致）。
	 * 信息完整吸收原 player_look/player_inventory 工具——模型不再需要调用观察类工具,
	 * 每轮 system 都自带最新状态。核心段——不管装配哪些插件,任何预设都需要它,
	 * 由 {@link #system} 直接组装;非玩家形态返回 null（跳过该段）。
	 */
	public static String assistantState(ToolContext ctx) {
		AiAssistantPlayer a = ctx.assistantPlayer();
		if (a == null) {
			return null;
		}
		try {
			ServerLevel level = ctx.level();
			if (a.level() instanceof ServerLevel al) {
				level = al;
			}
			JsonObject o = new JsonObject();
			JsonObject p = new JsonObject();
			p.addProperty("x", (int) Math.round(a.getX()));
			p.addProperty("y", (int) Math.round(a.getY()));
			p.addProperty("z", (int) Math.round(a.getZ()));
			o.add("position", p);
			o.addProperty("facing", AiCompanionService.facingName(a.getYRot()));
			o.addProperty("moving", a.movement().isMoving());
			if (level != null) {
				JsonElement env = environmentJson(level, a.blockPosition(), 16);
				if (env != null) {
					o.add("environment", env);
				}
				JsonElement nearby = nearbyBlocksJson(level, a.blockPosition());
				if (nearby != null) {
					o.add("nearby_blocks", nearby);
				}
				JsonElement wide = wideBlocksJson(level, a.blockPosition());
				if (wide != null) {
					o.add("blocks_by_type", wide);
				}
				o.add("entities", entitiesJson(level, a));
			}
			JsonElement inv = inventoryJson(a);
			if (inv != null) {
				o.add("inventory", inv);
			}
			o.addProperty("form", "player");
			// 身份字段：多助手共用 assistant.json 快照时能分辨是谁的状态
			String assistantName = null;
			try {
				assistantName = ctx.assistant() == null ? null
						: ctx.assistant().getConfig().effectiveName();
			} catch (Exception ignore) {
				// 读不到名字不影响状态段
			}
			if (assistantName != null && !assistantName.isBlank()) {
				o.addProperty("assistant", assistantName);
			}
			com.swaydy.opencraft.logging.StateSnapshots.write("assistant.json", o);
			return "## Assistant State\n\n" + jsonBlock(o);
		} catch (Exception e) {
			return "## Assistant State\n\n(unavailable)";
		}
	}

	/** ```json 围栏包裹的紧凑 JSON（结构化提示词的数据段统一出口）。 */
	private static String jsonBlock(JsonObject json) {
		return "```json\n" + json + "\n```";
	}

	// ------------------------------------------------------------------
	// 环境摘要（JSON,玩家/助手共用）
	// ------------------------------------------------------------------

	/**
	 * 紧凑环境摘要（JSON）：群系/气候/降水/天气/亮度/脚下方块/附近怪物数。
	 *
	 * @param hostileRadius 附近怪物统计半径；&lt;1 表示不统计
	 */
	private static JsonElement environmentJson(Level level, BlockPos pos, int hostileRadius) {
		try {
			Holder<Biome> biome = level.getBiome(pos);
			JsonObject o = new JsonObject();
			float temp = biome.value().getBaseTemperature();
			o.addProperty("biome", biomeName(biome));
			o.addProperty("climate", temp < 0.2 ? "cold" : temp > 0.9 ? "hot" : "mild");
			o.addProperty("precipitation", biome.value().hasPrecipitation() ? "rainy" : "dry");
			o.addProperty("weather", level.isThundering() ? "thunderstorm"
					: level.isRaining() ? "raining" : "clear");
			int sky = level.getSkyDarken();
			o.addProperty("brightness", sky == 0 ? "bright" : sky >= 15 ? "dark" : "dim(" + sky + ")");
			o.addProperty("ground", AiCompanionService.shortName(
					level.getBlockState(pos.below()).getBlock().getDescriptionId()));
			if (hostileRadius > 0) {
				int hostiles = level.getEntities((net.minecraft.world.entity.Entity) null,
						new AABB(pos).inflate(hostileRadius),
						e -> e instanceof net.minecraft.world.entity.monster.Monster).size();
				o.addProperty("nearby_monsters", hostiles);
			}
			return o;
		} catch (Exception e) {
			return null;
		}
	}

	private static String biomeName(Holder<Biome> biome) {
		try {
			return biome.unwrapKey()
					.map(k -> k.identifier().getPath().replace('_', ' '))
					.orElse(biome.getRegisteredName());
		} catch (Exception e) {
			return "unknown";
		}
	}

	// ------------------------------------------------------------------
	// 近旁方块 / 大范围方块计数 / 附近实体（JSON）
	// ------------------------------------------------------------------

	/**
	 * 以助手为中心、半径 {@link #CONTEXT_NEARBY_RADIUS} 格的逐层方块概览（JSON）：
	 * 按 y 层组织（层键 = 相对脚下的 y 偏移,如 "-1" = 脚下、"+1" = 头顶）,层内非空气方块
	 * 按类型聚合——数量 ≤ {@link #NEARBY_COORDS_MAX} 的类型值为 [{dx,dz}] 偏移列表
	 * （箱子/矿物等功能或稀有方块可定位）,数量多的类型值为计数;全空气的层省略。
	 */
	private static JsonElement nearbyBlocksJson(ServerLevel level, BlockPos pos) {
		try {
			JsonObject layers = new JsonObject();
			for (int dy = -CONTEXT_NEARBY_RADIUS; dy <= CONTEXT_NEARBY_RADIUS; dy++) {
				Map<String, List<int[]>> byType = new LinkedHashMap<>();
				for (int dx = -CONTEXT_NEARBY_RADIUS; dx <= CONTEXT_NEARBY_RADIUS; dx++) {
					for (int dz = -CONTEXT_NEARBY_RADIUS; dz <= CONTEXT_NEARBY_RADIUS; dz++) {
						BlockState state = level.getBlockState(pos.offset(dx, dy, dz));
						if (state.isAir()) {
							continue;
						}
						String name = AiCompanionService.shortName(state.getBlock().getDescriptionId());
						byType.computeIfAbsent(name, k -> new ArrayList<>()).add(new int[]{dx, dz});
					}
				}
				if (byType.isEmpty()) {
					continue;
				}
				JsonObject layer = new JsonObject();
				int shown = 0;
				for (Map.Entry<String, List<int[]>> e : byType.entrySet()) {
					if (shown >= NEARBY_MAX_TYPES_PER_LAYER) {
						break;
					}
					if (e.getValue().size() <= NEARBY_COORDS_MAX) {
						JsonArray coords = new JsonArray();
						for (int[] c : e.getValue()) {
							JsonObject off = new JsonObject();
							off.addProperty("dx", c[0]);
							off.addProperty("dz", c[1]);
							coords.add(off);
						}
						layer.add(e.getKey(), coords);
					} else {
						layer.addProperty(e.getKey(), e.getValue().size());
					}
					shown++;
				}
				layers.add("y" + (dy >= 0 ? "+" + dy : dy), layer);
			}
			if (layers.size() == 0) {
				return null;
			}
			JsonObject root = new JsonObject();
			root.addProperty("radius", CONTEXT_NEARBY_RADIUS);
			root.addProperty("note", "layers keyed by y offset from feet (omitted layers = air); "
					+ "value = count, or a list of {dx,dz} offsets from feet for rare blocks");
			root.add("layers", layers);
			return root;
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * 半径 {@link #CONTEXT_WIDE_RADIUS} 格的方块类型计数（JSON,吸收原 player_look 的方块观察）:
	 * 只按类型聚合计数、不给坐标（贴身定位看 nearby_blocks,远处定位用 player_find）;
	 * y 按 2 格步长抽样控制扫描成本——该段每轮都注入 system。
	 */
	private static JsonElement wideBlocksJson(ServerLevel level, BlockPos pos) {
		try {
			Map<String, Integer> counts = new LinkedHashMap<>();
			for (int dx = -CONTEXT_WIDE_RADIUS; dx <= CONTEXT_WIDE_RADIUS; dx++) {
				for (int dz = -CONTEXT_WIDE_RADIUS; dz <= CONTEXT_WIDE_RADIUS; dz++) {
					for (int dy = -CONTEXT_WIDE_RADIUS; dy <= CONTEXT_WIDE_RADIUS; dy += 2) {
						BlockState state = level.getBlockState(pos.offset(dx, dy, dz));
						if (state.isAir()) {
							continue;
						}
						counts.merge(AiCompanionService.shortName(state.getBlock().getDescriptionId()), 1, Integer::sum);
					}
				}
			}
			if (counts.isEmpty()) {
				return null;
			}
			JsonObject c = new JsonObject();
			int i = 0;
			for (Map.Entry<String, Integer> e : counts.entrySet()) {
				if (i >= WIDE_MAX_TYPES) {
					break;
				}
				c.addProperty(e.getKey(), e.getValue());
				i++;
			}
			JsonObject root = new JsonObject();
			root.addProperty("radius", CONTEXT_WIDE_RADIUS);
			root.addProperty("note", "block counts by type, sampled every 2 layers");
			root.add("counts", c);
			return root;
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * 附近实体清单（JSON,吸收原 player_look 的实体观察）:半径 {@link #ENTITY_RADIUS} 格内的
	 * 玩家/生物/掉落物,最多 {@link #ENTITY_MAX} 个,每个带精确坐标 + 方位 + 距离
	 * （模型据此判断"东西在哪、离多远",不需要再调用观察工具）。
	 */
	private static JsonObject entitiesJson(ServerLevel level, AiAssistantPlayer a) {
		JsonObject root = new JsonObject();
		root.addProperty("radius", ENTITY_RADIUS);
		JsonArray list = new JsonArray();
		try {
			BlockPos pos = a.blockPosition();
			List<Entity> entities = level.getEntities((Entity) null, new AABB(pos).inflate(ENTITY_RADIUS),
					e -> e != a && e.isAlive() && (e instanceof LivingEntity || e instanceof ItemEntity));
			int count = 0;
			for (Entity e : entities) {
				if (count >= ENTITY_MAX) {
					break;
				}
				JsonObject entry = new JsonObject();
				if (e instanceof Player pl) {
					entry.addProperty("kind", "player");
					entry.addProperty("name", pl.getName().getString());
				} else if (e instanceof ItemEntity ie) {
					entry.addProperty("kind", "dropped_item");
					entry.addProperty("item", AiCompanionService.shortName(
							ie.getItem().getItem().getDescriptionId()));
					entry.addProperty("count", ie.getItem().getCount());
				} else {
					entry.addProperty("kind", "mob");
					entry.addProperty("type", AiCompanionService.shortName(e.getType().getDescriptionId()));
				}
				JsonArray p = new JsonArray();
				p.add(e.blockPosition().getX());
				p.add(e.blockPosition().getY());
				p.add(e.blockPosition().getZ());
				entry.add("pos", p);
				entry.addProperty("bearing", AiCompanionService.bearingTo(pos, e.blockPosition()));
				entry.addProperty("distance", Math.round(a.distanceTo(e) * 10.0) / 10.0);
				list.add(entry);
				count++;
			}
		} catch (Exception e) {
			root.addProperty("error", "unavailable");
		}
		root.add("list", list);
		return root;
	}

	// ------------------------------------------------------------------
	// 助手背包详细清单（JSON,按槽位）
	// ------------------------------------------------------------------

	/**
	 * 助手背包的详细清单（JSON,按槽位列出,供模型管理自己的背包）:
	 * hotbar 逐槽列出（空槽 item=null）并给当前主手槽加 selected 标记;backpack 列非空槽
	 * 并统计空位;equipment 按槽位名列出;可受损物品附 durability（"剩余/上限"）。
	 * 槽位编号与 player_item_move / player_hotbar_select 的参数一致。
	 */
	private static JsonElement inventoryJson(AiAssistantPlayer a) {
		try {
			Inventory inv = a.getInventory();
			int selected = Math.max(0, Math.min(8, inv.getSelectedSlot()));
			JsonObject root = new JsonObject();
			// 当前主手（player_mine/player_place 用它）
			JsonObject mh = new JsonObject();
			mh.addProperty("slot", selected);
			ItemStack m = a.getMainHandItem();
			mh.addProperty("item", m.isEmpty() ? "empty"
					: AiCompanionService.shortName(m.getItem().getDescriptionId()));
			String md = durability(m);
			if (md != null) {
				mh.addProperty("durability", md);
			}
			root.add("mainhand", mh);
			// 快捷栏 [0-8]:逐槽（空槽 item=null）
			JsonArray hotbar = new JsonArray();
			for (int i = 0; i < 9; i++) {
				hotbar.add(slotJson(i, inv.getItem(i), i == selected));
			}
			root.add("hotbar", hotbar);
			// 主背包 [9-35]:非空槽 + 空位计数
			JsonArray backpack = new JsonArray();
			int empty = 0;
			for (int i = 9; i < 36; i++) {
				ItemStack s = inv.getItem(i);
				if (s.isEmpty()) {
					empty++;
					continue;
				}
				backpack.add(slotJson(i, s, false));
			}
			root.add("backpack", backpack);
			if (empty > 0) {
				root.addProperty("backpack_empty_slots", empty);
			}
			// 装备/副手（36 起的命名槽）:只列非空
			JsonObject equip = new JsonObject();
			for (int i = 36; i < inv.getContainerSize(); i++) {
				ItemStack s = inv.getItem(i);
				if (s.isEmpty()) {
					continue;
				}
				JsonObject e = new JsonObject();
				e.addProperty("item", AiCompanionService.shortName(s.getItem().getDescriptionId()));
				String d = durability(s);
				if (d != null) {
					e.addProperty("durability", d);
				}
				equip.add(equipSlotName(i), e);
			}
			if (equip.size() > 0) {
				root.add("equipment", equip);
			}
			return root;
		} catch (Exception e) {
			return null;
		}
	}

	/** 单个槽位条目:槽号 + 物品（空槽 null）+ 数量（>1 时）+ 耐久 + 主手标记。 */
	private static JsonObject slotJson(int slot, ItemStack s, boolean selected) {
		JsonObject o = new JsonObject();
		o.addProperty("slot", slot);
		if (s.isEmpty()) {
			o.add("item", JsonNull.INSTANCE);
			return o;
		}
		o.addProperty("item", AiCompanionService.shortName(s.getItem().getDescriptionId()));
		if (s.getCount() > 1) {
			o.addProperty("count", s.getCount());
		}
		String d = durability(s);
		if (d != null) {
			o.addProperty("durability", d);
		}
		if (selected) {
			o.addProperty("selected", true);
		}
		return o;
	}

	/** 可受损物品的剩余耐久（"剩余/上限"）;非工具/装备返回 null。 */
	private static String durability(ItemStack s) {
		if (s.getMaxDamage() <= 0) {
			return null;
		}
		return (s.getMaxDamage() - s.getDamageValue()) + "/" + s.getMaxDamage();
	}

	/** 装备槽序号（36 主背包之后）→ 展示名。 */
	private static String equipSlotName(int index) {
		return switch (index) {
			case 36 -> "boots";
			case 37 -> "leggings";
			case 38 -> "chestplate";
			case 39 -> "helmet";
			case 40 -> "offhand";
			case 41 -> "body";
			case 42 -> "saddle";
			default -> "equipment";
		};
	}

	/** 主人背包摘要（JSON 数组,"item×n" 字符串,装备槽带标签）,最多 {@link #CONTEXT_MAX_ITEMS} 条。 */
	private static JsonArray inventoryArray(Inventory inv) {
		JsonArray arr = new JsonArray();
		int shown = 0;
		for (int i = 0; i < inv.getContainerSize() && shown < CONTEXT_MAX_ITEMS; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) {
				continue;
			}
			StringBuilder e = new StringBuilder(AiCompanionService.shortName(s.getItem().getDescriptionId()));
			if (s.getCount() > 1) {
				e.append('×').append(s.getCount());
			}
			if (i >= 36) {
				e.insert(0, equipSlotName(i) + "=");
			}
			arr.add(e.toString());
			shown++;
		}
		return arr;
	}

	// ------------------------------------------------------------------
	// system 整段组装
	// ------------------------------------------------------------------

	/**
	 * 组装完整 system 文本（Markdown 结构）:
	 * 人设（# Identity：基础 + 名字 + 预设 persona）→ 插件能力提示（# Capabilities）→
	 * 玩家状态（## Player State,JSON）→ 助手状态（## Assistant State,JSON）→ 插件状态。
	 */
	public static String system(AiBlockConfig config, AgentDefinition agent,
	                            ServerPlayer player, AiAssistant assistant) {
		StringBuilder sb = new StringBuilder();
		sb.append(persona(config, agent));
		String frags = agent.systemPromptFragments();
		if (!frags.isBlank()) {
			sb.append("\n\n# Capabilities\n\n").append(frags);
		}
		sb.append("\n\n# Game Context\n\n").append(playerState(player));
		ToolContext ctx = new ToolContext(player.level().getServer(), assistant, player,
				(ServerLevel) player.level());
		String assistantFrag = assistantState(ctx);
		if (assistantFrag != null && !assistantFrag.isBlank()) {
			sb.append("\n\n").append(assistantFrag);
		}
		String ctxFrags = agent.gameContextFragments(ctx);
		if (!ctxFrags.isBlank()) {
			sb.append("\n\n").append(ctxFrags);
		}
		return sb.toString();
	}

	/** 在基础 system 上追加当前任务计划（# Current Task Plan + ```json;planText 本身是 JSON）。 */
	public static String systemWithPlan(AiBlockConfig config, AgentDefinition agent,
	                                    ServerPlayer player, AiAssistant assistant, String planText) {
		String base = system(config, agent, player, assistant);
		if (planText == null || planText.isBlank()) {
			return base;
		}
		return base + "\n\n# Current Task Plan\n\n```json\n" + planText + "\n```";
	}

	// ------------------------------------------------------------------
	// 玩家状态的私有辅助（身体/时段/装备/效果/注视目标）
	// ------------------------------------------------------------------

	/** 身体状态描述:正常/水下/氧气不足,着火追加。 */
	private static String bodyState(ServerPlayer player) {
		String body = "normal";
		if (player.isUnderWater()) {
			body = "underwater (air " + player.getAirSupply() + "/" + player.getMaxAirSupply() + ")";
		} else if (player.getAirSupply() < player.getMaxAirSupply()) {
			body = "air " + player.getAirSupply() + "/" + player.getMaxAirSupply();
		}
		if (player.isOnFire()) {
			body += ", on fire";
		}
		return body;
	}

	/** 一天内时刻（24000 tick）→ 时段名。 */
	private static String phaseOfTime(long timeOfDay) {
		if (timeOfDay < 6000) {
			return "dawn";
		}
		if (timeOfDay < 11000) {
			return "day";
		}
		if (timeOfDay < 13000) {
			return "dusk";
		}
		if (timeOfDay < 18000) {
			return "night";
		}
		return "deep night";
	}

	/** 装备摘要（JSON 数组,"name(slot)" 字符串,36 主背包之后的槽位所戴物品）。 */
	private static JsonArray equipmentJson(ServerPlayer player) {
		JsonArray arr = new JsonArray();
		Inventory inv = player.getInventory();
		for (int i = 36; i < inv.getContainerSize(); i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) {
				continue;
			}
			arr.add(AiCompanionService.shortName(s.getItem().getDescriptionId())
					+ "(" + equipSlotName(i) + ")");
		}
		return arr;
	}

	/** 状态效果摘要（JSON 数组,"名字 等级(时长s)" 字符串,最多 3 个）。 */
	private static JsonArray effectsJson(ServerPlayer player) {
		JsonArray arr = new JsonArray();
		List<MobEffectInstance> effects = new ArrayList<>(player.getActiveEffects());
		int n = 0;
		for (MobEffectInstance e : effects) {
			if (n >= 3) {
				arr.add("…");
				break;
			}
			StringBuilder sb = new StringBuilder(
					AiCompanionService.shortName(e.getEffect().value().getDescriptionId()));
			int amp = e.getAmplifier();
			if (amp > 0) {
				sb.append(romanNumeral(amp + 1));
			}
			sb.append("(").append(Math.max(1, e.getDuration() / 20)).append("s)");
			arr.add(sb.toString());
			n++;
		}
		return arr;
	}

	private static String romanNumeral(int x) {
		return switch (x) {
			case 2 -> "II";
			case 3 -> "III";
			case 4 -> "IV";
			default -> "";
		};
	}

	/** 玩家视线命中的方块（JSON {"block","distance"};未命中为 null）。 */
	private static JsonElement lookingAtJson(Level level, ServerPlayer player) {
		try {
			Vec3 eye = player.getEyePosition();
			Vec3 look = player.getLookAngle();
			BlockHitResult hit = level.clip(new ClipContext(
					eye, eye.add(look.scale(10.0)),
					ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
			if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
				return JsonNull.INSTANCE;
			}
			JsonObject o = new JsonObject();
			o.addProperty("block", AiCompanionService.shortName(
					level.getBlockState(hit.getBlockPos()).getBlock().getDescriptionId()));
			o.addProperty("distance",
					Math.round(player.position().distanceTo(hit.getLocation()) * 10.0) / 10.0);
			return o;
		} catch (Exception e) {
			return JsonNull.INSTANCE;
		}
	}
}
