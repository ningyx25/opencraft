package com.swaydy.opencraft.entity;

import com.swaydy.opencraft.OpenCraftMod;
import com.swaydy.opencraft.ai.AiBlockConfig;
import com.swaydy.opencraft.ai.AiCompanionService;
import com.swaydy.opencraft.ai.AiConfigHandler;
import com.swaydy.opencraft.block.AiLogoBlockEntity;
import com.swaydy.opencraft.block.ModBlocks;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.util.Optional;
import java.util.UUID;

/**
 * AI 游戏助手实体：陪伴玩家、跟随玩家、通过聊天陪玩家玩游戏。
 *
 * - 被玩家右键绑定后成为该玩家的专属助手；
 * - 右键（绑定后）在“跟随”与“待命”两种模式间切换；
 * - 主人下线/走远时原地待命，不会自然消失；
 * - 主人、跟随状态、配置方块引用都会写入存档，重新进入世界后仍然有效。
 *
 * 配置来源：助手绑定的 AI 徽标方块（方块实体中保存全部 AI 配置）；
 * 没有绑定方块时使用代码内默认值。
 */
public class AiAssistantEntity extends PathfinderMob {
	private static final EntityDataAccessor<Optional<EntityReference<LivingEntity>>> DATA_OWNER =
			SynchedEntityData.defineId(AiAssistantEntity.class, EntityDataSerializers.OPTIONAL_LIVING_ENTITY_REFERENCE);
	private static final EntityDataAccessor<Boolean> DATA_FOLLOWING =
			SynchedEntityData.defineId(AiAssistantEntity.class, EntityDataSerializers.BOOLEAN);

	/** 没有绑定配置方块时的默认配置（只读，不修改）。 */
	private static final AiBlockConfig DEFAULT_CONFIG = new AiBlockConfig();

	/** 绑定的 AI 徽标方块位置（服务端专用，随存档持久化）。 */
	private GlobalPos configBlock;

	/** 无主状态下的散步目标（有主人后停用）。 */

	public AiAssistantEntity(EntityType<? extends AiAssistantEntity> type, Level level) {
		super(type, level);
		// 持久化标记：写入存档后重进世界不会自然消失
		this.setPersistenceRequired();
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(DATA_OWNER, Optional.empty());
		builder.define(DATA_FOLLOWING, true);
	}

	@Override
	protected void registerGoals() {
		// 注意：Mob.<init> 会先于本实体的字段初始化调用 registerGoals，
		// 所以这里不能引用任何实例字段，所有 Goal 都要在方法内 new。
		this.goalSelector.addGoal(0, new FloatGoal(this));
		this.goalSelector.addGoal(1, new FollowAssistantOwnerGoal(this));
		this.goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, 8.0F));
		this.goalSelector.addGoal(3, new RandomLookAroundGoal(this));
		// 无主人时才原地散步
		this.goalSelector.addGoal(4, new WaterAvoidingRandomStrollGoal(this, 0.5) {
			@Override
			public boolean canUse() {
				return !AiAssistantEntity.this.hasOwner() && super.canUse();
			}
		});
	}

	public static AttributeSupplier.Builder createAttributes() {
		return PathfinderMob.createMobAttributes()
				.add(Attributes.MAX_HEALTH, 40.0)
				.add(Attributes.MOVEMENT_SPEED, 0.32)
				.add(Attributes.FOLLOW_RANGE, 64.0)
				.add(Attributes.ATTACK_DAMAGE, 0.0);
	}

	// ------------------------------------------------------------------
	// 主人 / 模式
	// ------------------------------------------------------------------

	/** 绑定主人。 */
	public void setOwner(Player owner) {
		this.entityData.set(DATA_OWNER, Optional.of(EntityReference.<LivingEntity>of(owner)));
	}

	/** 设置主人引用（读取存档时使用）。 */
	public void setOwnerReference(EntityReference<LivingEntity> reference) {
		this.entityData.set(DATA_OWNER, Optional.ofNullable(reference));
	}

	public Optional<EntityReference<LivingEntity>> getOwnerReference() {
		return this.entityData.get(DATA_OWNER);
	}

	/** 主人 UUID（无主时为 null）。 */
	public UUID getOwnerUuid() {
		return this.entityData.get(DATA_OWNER).map(EntityReference::getUUID).orElse(null);
	}

	/** 在线的主人（仅限同维度；跨维度返回 null）。 */
	public Player getOwner() {
		Optional<EntityReference<LivingEntity>> ref = this.entityData.get(DATA_OWNER);
		if (ref.isEmpty()) {
			return null;
		}
		LivingEntity entity = ref.get().getEntity(this.level(), LivingEntity.class);
		return entity instanceof Player player ? player : null;
	}

	public boolean hasOwner() {
		return this.entityData.get(DATA_OWNER).isPresent();
	}

	public boolean isFollowing() {
		return this.entityData.get(DATA_FOLLOWING);
	}

	public void setFollowing(boolean following) {
		this.entityData.set(DATA_FOLLOWING, following);
	}

	// ------------------------------------------------------------------
	// 配置方块（AI 徽标方块 = 配置来源）
	// ------------------------------------------------------------------

	public void setConfigBlock(GlobalPos configBlock) {
		this.configBlock = configBlock;
	}

	public GlobalPos getConfigBlock() {
		return configBlock;
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
	public AiBlockConfig getConfig() {
		AiLogoBlockEntity blockEntity = getConfigBlockEntity();
		return blockEntity == null ? DEFAULT_CONFIG : blockEntity.getConfig();
	}

	/**
	 * 显示名：服务端实时取“绑定方块配置的名字”（改名即时生效），格式 “名字 (x,y,z)”
	 * 以区分多个助手；客户端/无绑定时回退到召唤时设置的 customName（super）。
	 */
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
	// 交互
	// ------------------------------------------------------------------

	@Override
	protected InteractionResult mobInteract(Player player, InteractionHand hand) {
		if (this.level().isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		UUID ownerUuid = getOwnerUuid();
		if (ownerUuid == null) {
			setOwner(player);
			player.displayClientMessage(Component.translatable("entity.opencraft.ai_assistant.bind"),
					true);
			OpenCraftMod.LOGGER.info("[OpenCraft] 玩家 {} 绑定了 AI 助手", player.getName().getString());
		} else if (ownerUuid.equals(player.getUUID())) {
			boolean following = !isFollowing();
			setFollowing(following);
			player.displayClientMessage(
					Component.translatable(following
							? "entity.opencraft.ai_assistant.following"
							: "entity.opencraft.ai_assistant.staying"),
					true);
		} else {
			player.displayClientMessage(Component.translatable("entity.opencraft.ai_assistant.not_owner"),
					true);
		}
		return InteractionResult.SUCCESS;
	}

	// ------------------------------------------------------------------
	// 生命周期
	// ------------------------------------------------------------------

	@Override
	public void tick() {
		super.tick();
		if (!this.level().isClientSide() && this.tickCount % 40 == 0) {
			// 共存性安全网：助手必须绑定一个 AI 徽标方块——
			// 无绑定（configBlock 为空）或绑定方块已消失 → 助手随之消失。
			// 这也能清除刷怪蛋 / 旧存档遗留的无绑定助手（约 2 秒内）。
			GlobalPos block = getConfigBlock();
			if (block == null) {
				this.discard();
				return;
			}
			if (this.level() instanceof ServerLevel level) {
				ServerLevel target = level.getServer().getLevel(block.dimension());
				if (target == null || !target.getBlockState(block.pos()).is(ModBlocks.AI_LOGO_BLOCK)) {
					this.discard();
					return;
				}
			}
			// 跨维度跟随：主人换到别的维度时传送过去
			if (isFollowing()) {
				maybeFollowAcrossDimensions();
			}
		}
	}

	@Override
	public void remove(net.minecraft.world.entity.Entity.RemovalReason reason) {
		super.remove(reason);
		// 助手被送走/死亡时，绑定的 AI 徽标方块应熄灭（若没有其他助手仍绑定它）
		if (reason == RemovalReason.DISCARDED || reason == RemovalReason.KILLED) {
			GlobalPos block = configBlock;
			if (block != null && this.level() instanceof ServerLevel level) {
				AiConfigHandler.syncBoundBlockPoweredState(
						level.getServer().getLevel(block.dimension()), block);
			}
		}
	}

	/** 主人已在线但不在同一维度时，传送到主人身边（跟随模式下）。 */
	private void maybeFollowAcrossDimensions() {
		UUID ownerUuid = getOwnerUuid();
		if (ownerUuid == null || !(this.level() instanceof ServerLevel level)) {
			return;
		}
		ServerPlayer owner = level.getServer().getPlayerList().getPlayer(ownerUuid);
		if (owner == null || owner.level() == this.level()) {
			return;
		}
		AiCompanionService.teleportAssistantToPlayer(owner, this);
		OpenCraftMod.LOGGER.info("[OpenCraft] 助手跟随主人 {} 跨维度传送到了 {}",
				owner.getName().getString(), owner.level().dimension().identifier());
	}

	@Override
	public boolean isPersistenceRequired() {
		return true;
	}

	@Override
	public boolean removeWhenFarAway(double distanceToClosestPlayer) {
		return false;
	}

	@Override
	public boolean shouldDropExperience() {
		return false;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		this.entityData.get(DATA_OWNER)
				.ifPresent(ref -> EntityReference.store(ref, output, "Owner"));
		output.putBoolean("Following", isFollowing());
		output.storeNullable("ConfigBlock", GlobalPos.CODEC, configBlock);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		// 无论以何种方式创建/加载（召唤、刷怪蛋、存档 NBT），助手都需要持久化
		this.setPersistenceRequired();
		EntityReference<LivingEntity> ref =
				EntityReference.readWithOldOwnerConversion(input, "Owner", this.level());
		if (ref != null) {
			setOwnerReference(ref);
		}
		setFollowing(input.getBooleanOr("Following", true));
		this.configBlock = input.read("ConfigBlock", GlobalPos.CODEC).orElse(null);
	}
}
