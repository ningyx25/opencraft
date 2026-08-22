package com.swaydy.opencraft.assistant.player;

import com.mojang.authlib.GameProfile;
import com.swaydy.opencraft.ai.AiBlockConfig;
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
import net.minecraft.world.SimpleMenuProvider;
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
 * "玩家形态"AI 助手：一个真实的 {@link ServerPlayer}（bot），像多人联机客户端一样加入服务器。
 *
 * 玩家形态自带普通玩家拥有的**全部内容**：
 * - 真正的玩家背包（41 格：36 主背包 + 4 护甲 + 副手）、经验、游戏模式、玩家式交互；
 * - 能通过 {@link net.minecraft.server.level.ServerPlayerGameMode} 执行真正的玩家动作
 *   （破坏/放置/交互/合成），掉落物自动拾取（玩家形态本身就是 Player）；
 * - 会出现在玩家列表 / Tab / 实体追踪中，对其他人就是一个"客户端玩家"。
 *
 * 绑定规则：绑定 AI 徽标方块（配置来源）与主人；
 * 配置方块被拆时由 {@link PlayerAssistantService} 清除。**跟随模式**：
 * 默认跟随主人（同维度走近、跨维度/太远时瞬移跟随）；当玩家给助手下达
 * 指令（/opencraft ask 或聊天）后退出跟随专注执行，指令完成自动回到跟随。
 * 网络连接是"黑洞"（{@link FakeConnection}），所有发包 no-op。
 *
 * 生命形态：默认生存模式 + 无敌（不会因坠落/溺水/饥饿/怪物而死）+ 食物自动补满——
 * "可以不用，但不能没有"的完整玩家能力，但作为陪玩助手不会轻易死掉。
 */
public class AiAssistantPlayer extends ServerPlayer implements AiAssistant {
	/** 没有绑定配置方块时的默认配置（只读，不修改）。 */
	private static final AiBlockConfig DEFAULT_CONFIG = new AiBlockConfig();

	/** 绑定的 AI 徽标方块位置（服务端专用，随玩家存档持久化）。 */
	private GlobalPos configBlock;
	/** 主人 UUID（随玩家存档持久化）。 */
	private UUID ownerUuid;
	/**
	 * 是否跟随主人。默认 {@code true}（召唤即跟随）；玩家下达指令时由
	 * AgentRuntime 置 false，指令完成/中断后置回 true。不持久化——
	 * 任务生命周期是瞬态的，重进/重召唤一律默认跟随。
	 */
	private boolean following = true;

	private final PlayerMovementController movement = new PlayerMovementController();

	public AiAssistantPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
		super(server, level, profile, ClientInformation.createDefault());
	}

	/** 移动控制器（跟随 / goto / 走到旁再破坏）。 */
	public PlayerMovementController movement() {
		return movement;
	}

	@Override
	public boolean isFollowing() {
		return following;
	}

	@Override
	public void setFollowing(boolean following) {
		this.following = following;
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

	@Override
	public UUID getOwnerUuid() {
		return ownerUuid;
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
			com.swaydy.opencraft.logging.DebugLog.log("bind",
					"玩家 {} 右键绑定了玩家形态助手（玩家名 {}）", player.getName().getString(),
					getName().getString());
			return InteractionResult.SUCCESS;
		}
		if (ownerUuid.equals(player.getUUID())) {
			// 主人右键：打开助手背包界面（左侧助手背包 + 右侧玩家背包，原版 E 背包布局）
			if (player instanceof ServerPlayer serverPlayer) {
				openInventoryScreen(serverPlayer);
			}
			return InteractionResult.SUCCESS;
		}
		player.displayClientMessage(Component.translatable("entity.opencraft.ai_assistant.not_owner"), true);
		return InteractionResult.SUCCESS;
	}

	/** 右键主人：打开助手的双面板背包界面（左半=助手按 E 的背包，右半=玩家自己的）。 */
	private void openInventoryScreen(ServerPlayer player) {
		try {
			player.openMenu(new SimpleMenuProvider(
					(id, playerInv, p) -> new com.swaydy.opencraft.inventory.AssistantInventoryMenu(
							id, playerInv, this.getInventory(), () -> !this.isRemoved()),
					getDisplayName()));
		} catch (Exception e) {
			// 模拟连接等场景打开失败：静默忽略
			com.swaydy.opencraft.OpenCraftMod.LOGGER.debug(
					"[OpenCraft] 打开背包界面失败（可能是模拟连接）: {}", e.toString());
			return;
		}
		sendInventoryScreenEntityId(player);
	}

	/**
	 * 把助手实体 ID 发给客户端（复用 {@code AssistantInteractPayload} 通道，紧随打开包
	 * 按序到达）：背包界面左侧要用原版 {@code renderEntityInInventory} 渲染这个实体的模型。
	 */
	private void sendInventoryScreenEntityId(ServerPlayer player) {
		try {
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
					new com.swaydy.opencraft.net.AssistantPayloads.AssistantInteractPayload(
							this.getId()));
		} catch (Exception e) {
			// 模拟连接等场景发送失败：静默忽略（只是少了模型渲染，界面不受影响）
			com.swaydy.opencraft.OpenCraftMod.LOGGER.debug(
					"[OpenCraft] 发送背包界面实体 ID 失败（可能是模拟连接）: {}", e.toString());
		}
	}

	// ------------------------------------------------------------------
	// 每 tick：跟随/移动/安全（服务端）
	// ------------------------------------------------------------------

	@Override
	public void tick() {
		if (!this.level().isClientSide()) {
			// 与真实进服玩家完全一致的 tick 链：真实玩家每 tick 被驱动两次——连接监听器
			// （Connection.tick → ServerGamePacketListenerImpl.tick → doTick，含
			// Player.tick → LivingEntity.tick：装备变更同步检测、状态效果、食物等）
			// + 实体循环的 ServerPlayer.tick。bot 的 FakeConnection 不在服务器连接列表里、
			// 监听器永远不会被 tick，这里按同样的顺序（先 doTick 后 ServerPlayer.tick）
			// 手动补上——装备穿/脱、状态等行为从此与真实玩家无异。
			// （移动仍由 PlayerMovementController 自管：keepSafeState 维持 noGravity，
			// 原版旅行物理不会与控制器双重驱动。）
			this.doTick();
		}
		super.tick();
		if (!this.level().isClientSide()) {
			PlayerAssistantService.keepSafeState(this);
			movement.tick(this);
			// 玩家形态助手自己是 Player，掉落物不会凭空自动进背包（服务器不重放玩家移动包），
			// 主动拾取脚边物品（真实玩家式自动拾取）
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
				com.swaydy.opencraft.logging.DebugLog.log("pickup",
						"玩家形态助手拾取了 {}×{}（位置 {}, {}, {}）",
						itemName, taken, (int) getX(), (int) getY(), (int) getZ());
				item.discard();
			}
		}
	}

	@Override
	public boolean causeFallDamage(double distance, float damageMultiplier, DamageSource source) {
		// 陪伴助手不摔伤（但保留"摔落距离"语义供其他逻辑判断）
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
		com.swaydy.opencraft.logging.DebugLog.log("save",
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
		com.swaydy.opencraft.logging.DebugLog.log("save",
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
