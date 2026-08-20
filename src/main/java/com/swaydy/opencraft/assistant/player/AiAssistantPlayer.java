package com.swaydy.opencraft.assistant.player;

import com.mojang.authlib.GameProfile;
import com.swaydy.opencraft.ai.AiBlockConfig;
import com.swaydy.opencraft.ai.AiCompanionService;
import com.swaydy.opencraft.assistant.AiAssistant;
import com.swaydy.opencraft.block.AiLogoBlockEntity;
import com.swaydy.opencraft.block.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.Optional;
import java.util.UUID;

/**
 * “玩家形态”AI 助手：一个真实的 {@link ServerPlayer}（bot），像多人联机客户端一样加入服务器。
 *
 * 与实体版（PathfinderMob）相比，玩家形态自带普通玩家拥有的**全部内容**：
 * - 真正的玩家背包（41 格：36 主背包 + 4 护甲 + 副手）、经验、游戏模式、玩家式交互；
 * - 能通过 {@link net.minecraft.server.level.ServerPlayerGameMode} 执行真正的玩家动作
 *   （破坏/放置/交互/合成），掉落物自动拾取（玩家形态本身就是 Player）；
 * - 会出现在玩家列表 / Tab / 实体追踪中，对其他人就是一个“客户端玩家”。
 *
 * 绑定规则与实体版一致：绑定 AI 徽标方块（配置来源）与主人；
 * 配置方块被拆时由 {@link PlayerAssistantService} 清除。**不自动跟随主人**——
 * 召唤后停留在原地，只受显式指令（player_goto/player_mine 等）驱动。
 * 网络连接是“黑洞”（{@link FakeConnection}），所有发包 no-op。
 *
 * 生命形态：默认生存模式 + 无敌（不会因坠落/溺水/饥饿/怪物而死）+ 食物自动补满——
 * “可以不用，但不能没有”的完整玩家能力，但作为陪玩助手不会轻易死掉。
 */
public class AiAssistantPlayer extends ServerPlayer implements AiAssistant {
	/** 没有绑定配置方块时的默认配置（只读，不修改）。 */
	private static final AiBlockConfig DEFAULT_CONFIG = new AiBlockConfig();

	/** 绑定的 AI 徽标方块位置（服务端专用，随玩家存档持久化）。 */
	private GlobalPos configBlock;
	/** 主人 UUID（随玩家存档持久化）。 */
	private UUID ownerUuid;

	private final PlayerMovementController movement = new PlayerMovementController();

	public AiAssistantPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
		super(server, level, profile, ClientInformation.createDefault());
	}

	/** 移动控制器（跟随 / goto / 走到旁再破坏）。 */
	public PlayerMovementController movement() {
		return movement;
	}

	@Override
	public String formId() {
		return "player";
	}

	// ------------------------------------------------------------------
	// 绑定（主人 / 配置方块）
	// ------------------------------------------------------------------

	public void setConfigBlock(GlobalPos configBlock) {
		this.configBlock = configBlock;
	}

	@Override
	public GlobalPos getConfigBlock() {
		return configBlock;
	}

	public void setOwner(Player owner) {
		this.ownerUuid = owner.getUUID();
	}

	public void setOwnerUuid(UUID ownerUuid) {
		this.ownerUuid = ownerUuid;
	}

	@Override
	public UUID getOwnerUuid() {
		return ownerUuid;
	}

	public boolean hasOwner() {
		return ownerUuid != null;
	}

	/** 在线的主人（跨维度；不在线返回 null）。 */
	public Player getOwner() {
		if (ownerUuid == null) {
			return null;
		}
		MinecraftServer server = this.level().getServer();
		return server == null ? null : server.getPlayerList().getPlayer(ownerUuid);
	}

	/** 解析绑定的配置方块实体（跨维度）；方块被拆掉/不存在时返回 null。 */
	public AiLogoBlockEntity getConfigBlockEntity() {
		GlobalPos globalPos = configBlock;
		if (globalPos == null || !(this.level() instanceof ServerLevel level)) {
			return null;
		}
		ServerLevel target = level.getServer().getLevel(globalPos.dimension());
		if (target == null) {
			return null;
		}
		if (target.getBlockEntity(globalPos.pos()) instanceof AiLogoBlockEntity blockEntity) {
			return blockEntity;
		}
		return null;
	}

	/** 当前生效的 AI 配置：优先取绑定方块的配置，否则用默认值。 */
	@Override
	public AiBlockConfig getConfig() {
		AiLogoBlockEntity blockEntity = getConfigBlockEntity();
		return blockEntity == null ? DEFAULT_CONFIG : blockEntity.getConfig();
	}

	@Override
	public Component getDisplayName() {
		if (this.level() instanceof ServerLevel) {
			AiBlockConfig config = getConfig();
			if (config != null) {
				String name = config.effectiveName();
				GlobalPos block = configBlock;
				if (block != null) {
					return Component.translatable("entity.opencraft.ai_assistant.named",
							name,
							block.pos().getX() + "," + block.pos().getY() + "," + block.pos().getZ());
				}
				return Component.literal(name);
			}
		}
		return super.getDisplayName();
	}

	// ------------------------------------------------------------------
	// 右键交互（玩家形态也是实体，可被真实玩家右键）
	// ------------------------------------------------------------------

	@Override
	public InteractionResult interact(Player player, InteractionHand hand) {
		if (this.level().isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (player == null) {
			return InteractionResult.PASS;
		}
		UUID ownerUuid = getOwnerUuid();
		if (ownerUuid == null) {
			// 第一次右键：绑定主人
			setOwner(player);
			player.displayClientMessage(Component.translatable("entity.opencraft.ai_assistant.bind"), true);
			com.swaydy.opencraft.debug.DebugLog.log("bind",
					"玩家 {} 右键绑定了玩家形态助手（玩家名 {}）", player.getName().getString(),
					getName().getString());
			return InteractionResult.SUCCESS;
		}
		if (ownerUuid.equals(player.getUUID())) {
			// 主人右键：打开“互动界面”（和这个助手聊天 / 送走）
			if (player instanceof ServerPlayer serverPlayer) {
				openInteractScreen(serverPlayer);
			}
			return InteractionResult.SUCCESS;
		}
		player.displayClientMessage(Component.translatable("entity.opencraft.ai_assistant.not_owner"), true);
		return InteractionResult.SUCCESS;
	}

	/** 给主人发送互动界面数据（打开/刷新右键互动 GUI，与实体版同款）。 */
	private void openInteractScreen(ServerPlayer player) {
		AiBlockConfig config = getConfig();
		String model = config.model == null ? "" : config.model;
		String agent = config.agent == null ? "general_agent" : config.agent;
		GlobalPos block = getConfigBlock();
		BlockPos blockPos = block == null ? this.blockPosition() : block.pos();
		ResourceKey<Level> dimension = block == null ? this.level().dimension() : block.dimension();
		try {
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
					new com.swaydy.opencraft.net.AssistantPayloads.AssistantInteractPayload(
							this.getId(), getDisplayName().getString(), true, model,
							agent, blockPos, dimension));
		} catch (Exception e) {
			// 模拟连接等场景发送失败：静默忽略（与配置界面一致）
			com.swaydy.opencraft.OpenCraftMod.LOGGER.debug(
					"[OpenCraft] 发送互动界面数据失败（可能是模拟连接）: {}", e.toString());
		}
	}

	// ------------------------------------------------------------------
	// 每 tick：跟随/移动/安全（服务端）
	// ------------------------------------------------------------------

	@Override
	public void tick() {
		super.tick();
		if (!this.level().isClientSide()) {
			PlayerAssistantService.keepSafeState(this);
			movement.tick(this);
			// 玩家形态助手自己是 Player，掉落物不会凭空自动进背包（服务器不重放玩家移动包），
			// 因此像实体版一样主动拾取脚边物品（真实玩家式自动拾取）
			if (this.tickCount % 5 == 0) {
				pickupNearbyItems();
			}
			if (this.tickCount % 40 == 0) {
				PlayerAssistantService.onSlowTick(this);
			}
		}
	}

	/** 像普通生存玩家一样自动拾取脚边物品（真实玩家式自动拾取，掉落物进玩家背包）。 */
	private void pickupNearbyItems() {
		if (!(this.level() instanceof ServerLevel level) || this.isRemoved()) {
			return;
		}
		java.util.List<net.minecraft.world.entity.item.ItemEntity> items =
				level.getEntitiesOfClass(
						net.minecraft.world.entity.item.ItemEntity.class,
						this.getBoundingBox().inflate(1.5, 0.6, 1.5),
						item -> item.isAlive() && !item.hasPickUpDelay() && !item.getItem().isEmpty());
		for (net.minecraft.world.entity.item.ItemEntity item : items) {
			ItemStack stack = item.getItem();
			int count = stack.getCount();
			if (count <= 0) {
				item.discard();
				continue;
			}
			// PlayerInventory.add 返回 boolean；先把物品复制放进背包，取不下的留原地
			String itemName = stack.getHoverName().getString();
			boolean added = this.getInventory().add(stack.copy());
			int taken = added ? count : 0;
			if (taken > 0) {
				stack.setCount(0);
				this.take(item, taken);
				this.onItemPickup(item);
				com.swaydy.opencraft.debug.DebugLog.log("pickup",
						"玩家形态助手拾取了 {}×{}（位置 {}, {}, {}）",
						itemName, taken, (int) getX(), (int) getY(), (int) getZ());
				item.discard();
			}
		}
	}

	@Override
	public boolean causeFallDamage(double distance, float damageMultiplier, DamageSource source) {
		// 陪伴助手不摔伤（但保留“摔落距离”语义供其他逻辑判断）
		return false;
	}

	// ------------------------------------------------------------------
	// 持久化：随玩家存档保存绑定与跟随状态（背包/装备由 ServerPlayer 原样持久化）
	// ------------------------------------------------------------------

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putString("OpenCraftOwner", ownerUuid == null ? "" : ownerUuid.toString());
		if (configBlock != null) {
			output.putString("OpenCraftDim", configBlock.dimension().identifier().toString());
			output.putInt("OpenCraftX", configBlock.pos().getX());
			output.putInt("OpenCraftY", configBlock.pos().getY());
			output.putInt("OpenCraftZ", configBlock.pos().getZ());
		}
		com.swaydy.opencraft.debug.DebugLog.log("save",
				"玩家形态助手存档写入（主人 {}, 绑定方块 {}, 背包物品 {} 种）",
				ownerUuid == null ? "无" : ownerUuid.toString().substring(0, 8),
				configBlock == null ? "无" : configBlock.pos().toShortString(),
				getInventory().getNonEquipmentItems().stream()
						.filter(s -> !s.isEmpty()).count());
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		String owner = input.getString("OpenCraftOwner").orElse("");
		if (!owner.isBlank()) {
			try {
				this.ownerUuid = UUID.fromString(owner);
			} catch (IllegalArgumentException ignored) {
				this.ownerUuid = null;
			}
		}
		Optional<String> dim = input.getString("OpenCraftDim");
		if (dim.isPresent() && !dim.get().isBlank()) {
			try {
				Identifier id = Identifier.parse(dim.get());
				this.configBlock = GlobalPos.of(
						ResourceKey.create(Registries.DIMENSION, id),
						new BlockPos(input.getInt("OpenCraftX").orElse(0),
								input.getInt("OpenCraftY").orElse(0),
								input.getInt("OpenCraftZ").orElse(0)));
			} catch (Exception ignored) {
				this.configBlock = null;
			}
		}
		com.swaydy.opencraft.debug.DebugLog.log("save",
				"玩家形态助手存档读回（主人 {}, 绑定方块 {}, 背包物品 {} 种）",
				ownerUuid == null ? "无" : ownerUuid.toString().substring(0, 8),
				configBlock == null ? "无" : configBlock.pos().toShortString(),
				getInventory().getNonEquipmentItems().stream()
						.filter(s -> !s.isEmpty()).count());
	}

	/** 供 PlayerAssistantService / 插件判断绑定方块是否还在。 */
	public boolean isBoundBlockGone() {
		GlobalPos block = configBlock;
		if (block == null) {
			return true;
		}
		if (!(this.level() instanceof ServerLevel level)) {
			return false;
		}
		ServerLevel target = level.getServer().getLevel(block.dimension());
		return target == null || !target.getBlockState(block.pos()).is(ModBlocks.AI_LOGO_BLOCK);
	}
}
