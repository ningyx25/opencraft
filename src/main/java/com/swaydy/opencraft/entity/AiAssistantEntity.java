package com.swaydy.opencraft.entity;

import com.swaydy.opencraft.OpenCraftMod;
import com.swaydy.opencraft.ai.AiBlockConfig;
import com.swaydy.opencraft.ai.AiCompanionService;
import com.swaydy.opencraft.ai.AiConfigHandler;
import com.swaydy.opencraft.block.AiLogoBlockEntity;
import com.swaydy.opencraft.block.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
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
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.UUID;

/**
 * AI 游戏助手实体：陪伴玩家、跟随玩家、通过聊天陪玩家玩游戏。
 *
 * - 被玩家右键绑定后成为该玩家的专属助手；
 * - 右键（绑定后）在“跟随”与“待命”两种模式间切换；
 * - 主人下线/走远时原地待命，不会自然消失；
 * - 主人、跟随状态、配置方块引用都会写入存档，重新进入世界后仍然有效；
 * - **像普通生存玩家一样拥有完整背包与装备**：36 格背包（{@link #INVENTORY_SIZE}）
 *   自动拾取地上的物品/挖掘掉落物；头盔/胸甲/护腿/靴子/手/副手装备槽由
 *   LivingEntity 原生支持并持久化，装备护甲会如实增加护甲值（vanilla 每 tick
 *   的 equipment 属性变更检测自动生效）；死亡时掉落全部物品。
 *   实现上**不继承 Player/ServerPlayer**（Player 不是 Mob，没有 goalSelector，
 *   ServerPlayer 绑定网络连接且会进 PlayerList），而是保持 PathfinderMob 底座、
 *   以「36 格背包 + 原生装备槽 + 自动拾取」复刻玩家能力。
 *
 * 配置来源：助手绑定的 AI 徽标方块（方块实体中保存全部 AI 配置）；
 * 没有绑定方块时使用代码内默认值。
 */
public class AiAssistantEntity extends PathfinderMob implements com.swaydy.opencraft.assistant.AiAssistant {
	private static final EntityDataAccessor<Optional<EntityReference<LivingEntity>>> DATA_OWNER =
			SynchedEntityData.defineId(AiAssistantEntity.class, EntityDataSerializers.OPTIONAL_LIVING_ENTITY_REFERENCE);
	private static final EntityDataAccessor<Boolean> DATA_FOLLOWING =
			SynchedEntityData.defineId(AiAssistantEntity.class, EntityDataSerializers.BOOLEAN);

	/** 形态 id：实体形态（PathfinderMob 底座）。 */
	@Override
	public String formId() {
		return "entity";
	}

	/** 没有绑定配置方块时的默认配置（只读，不修改）。 */
	private static final AiBlockConfig DEFAULT_CONFIG = new AiBlockConfig();

	/**
	 * 助手背包容量：与生存玩家主背包一致（27 普通格 + 9 快捷栏 = 36 格）。
	 * 装备栏（头盔/胸甲/护腿/靴子/手/副手）使用 LivingEntity 原生装备槽
	 * （{@link #getItemBySlot}/{@link #setItemSlot}），随存档自动持久化。
	 */
	public static final int INVENTORY_SIZE = 36;

	/** 物品拾取扫描间隔（tick；像玩家一样走过物品自动拾取）。 */
	private static final int PICKUP_SCAN_INTERVAL = 5;

	/**
	 * 拾取范围（水平方向各方向的格数）：比玩家略大，因为助手不会主动走到每个物品上，
	 * 且挖掘掉落物落地后可能滑动 1~2 格——范围太小会捡不到滑出去的掉落物。
	 * 拾取时通过 take+onItemPickup 广播“物品飞向助手”的动画，视觉上很自然。
	 */
	private static final double PICKUP_RANGE_XZ = 2.5;
	private static final double PICKUP_RANGE_Y = 0.6;

	/** 绑定的 AI 徽标方块位置（服务端专用，随存档持久化）。 */
	private GlobalPos configBlock;

	/**
	 * 助手自己的背包（36 格，随存档持久化，NBT 键 {@code Inventory}）。
	 * 拾取/挖掘掉落物/合成材料默认进这里；InventoryPlugin 据此清单/装备/递给主人。
	 * 注意：registerGoals 在字段初始化前调用，背包不能在 registerGoals 里引用。
	 */
	private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);

	/** 当前正在执行的任务（服务端字段，不持久化；tick 驱动，见 {@link TaskHostGoal}）。 */
	private AssistantTask currentTask;

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
		// 优先级 0：任务宿主（代理当前任务，任务活跃时压制其他 Goal）
		this.goalSelector.addGoal(0, new TaskHostGoal(this));
		// 优先级 0：漂浮（防止溺水/坠落判定）
		this.goalSelector.addGoal(0, new FloatGoal(this));
		// 插件 Goal（如跟随）由当前 Agent 预设注册；getConfig 在字段初始化后才可用，
		// 因此这里的插件注册放到首次 tick 时懒加载（见 ensureAgentGoals）
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

	/** 是否已按当前 Agent 预设注册过插件 Goal（懒加载一次）。 */
	private boolean agentGoalsRegistered = false;

	/**
	 * 确保已按当前 Agent 预设注册插件 Goal（跟随等）。
	 * registerGoals 在字段初始化前调用，无法读配置；因此延迟到第一次 tick 再注册。
	 */
	private void ensureAgentGoals() {
		if (agentGoalsRegistered) {
			return;
		}
		agentGoalsRegistered = true;
		try {
			com.swaydy.opencraft.agent.AgentDefinition agent =
					com.swaydy.opencraft.agent.AgentRegistry.resolveAgent(getConfig());
			agent.registerGoals(this);
		} catch (Exception e) {
			OpenCraftMod.LOGGER.warn("[OpenCraft] 注册插件 Goal 失败: {}", e.toString());
		}
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
	// 任务系统
	// ------------------------------------------------------------------

	/** 当前正在执行的任务（可能为 null）。 */
	public AssistantTask getCurrentTask() {
		return currentTask;
	}

	/**
	 * 下达一个新任务：取消旧任务（停止导航），挂上新的。由插件（工具执行时）在服务端线程调用。
	 */
	public void setCurrentTask(AssistantTask task) {
		if (currentTask != null && !currentTask.isFinished()) {
			getNavigation().stop();
		}
		this.currentTask = task;
		com.swaydy.opencraft.debug.DebugLog.log("task",
				"助手下达新任务: {}（tick {}）",
				task == null ? "null" : task.describe(), tickCount);
	}

	/** 任务终结时由 {@link TaskHostGoal} 调用：清空当前任务（不再驱动）。 */
	public void completeCurrentTask() {
		if (currentTask != null) {
			com.swaydy.opencraft.debug.DebugLog.log("task",
					"任务结束: {}（完成={} 失败={}，tick {}）",
					currentTask.describe(), currentTask.isDone(), currentTask.isFailed(), tickCount);
			getNavigation().stop();
		}
		this.currentTask = null;
	}

	/** 取消当前任务（如新任务到达、助手被送走）。 */
	public void cancelCurrentTask() {
		if (currentTask != null) {
			com.swaydy.opencraft.debug.DebugLog.log("task", "任务被取消: {}（tick {}）",
					currentTask.describe(), tickCount);
			getNavigation().stop();
			this.currentTask = null;
		}
	}

	/**
	 * 供插件注册 AI Goal（插件在实体类外，无法访问 protected goalSelector）。
	 * 插件应在 {@code ensureAgentGoals()}（首次 tick）被调用时通过本方法注册。
	 */
	public void addAssistantGoal(int priority, net.minecraft.world.entity.ai.goal.Goal goal) {
		this.goalSelector.addGoal(priority, goal);
	}

	/** 供插件移除已注册的 Goal（如 Agent 预设切换时清理）。 */
	public void removeAssistantGoal(net.minecraft.world.entity.ai.goal.Goal goal) {
		this.goalSelector.removeGoal(goal);
	}

	// ------------------------------------------------------------------
	// 背包 / 拾取
	// ------------------------------------------------------------------

	/** 助手自己的背包（36 格，挖掘掉落物、拾取的物品、合成材料都放这里）。 */
	public SimpleContainer getInventory() {
		return inventory;
	}

	/**
	 * 尝试把物品放入助手背包（能放则放满可容纳的量），返回放不下的剩余。
	 * 背包满时剩余原样返回，调用方决定丢弃/递给主人。
	 */
	public ItemStack giveToInventory(ItemStack stack) {
		ItemStack remaining = inventory.addItem(stack);
		return remaining == null ? ItemStack.EMPTY : remaining;
	}

	/** 背包里指定物品的总数量（用于工具/插件判断材料是否充足）。 */
	public int countOf(net.minecraft.core.Holder<net.minecraft.world.item.Item> item) {
		int count = 0;
		for (ItemStack stack : inventory.getItems()) {
			if (!stack.isEmpty() && stack.is(item)) {
				count += stack.getCount();
			}
		}
		return count;
	}

	/**
	 * 像普通生存玩家一样自动拾取地上的物品：
	 * - 每 5 tick 扫描脚下小范围内的 ItemEntity（等待掉落物的 40 tick 保护期结束）；
	 * - 物品进入 36 格背包（合并到已有堆叠，背包满则留在原地）；
	 * - 播放拾取动画/音效（onItemPickup + take 向客户端广播）。
	 */
	private void pickupNearbyItems() {
		if (!(this.level() instanceof ServerLevel level) || isRemoved()) {
			return;
		}
		java.util.List<net.minecraft.world.entity.item.ItemEntity> items = level.getEntitiesOfClass(
				net.minecraft.world.entity.item.ItemEntity.class,
				getBoundingBox().inflate(PICKUP_RANGE_XZ, PICKUP_RANGE_Y, PICKUP_RANGE_XZ),
				item -> item.isAlive() && !item.hasPickUpDelay() && !item.getItem().isEmpty());
		for (net.minecraft.world.entity.item.ItemEntity item : items) {
			tryPickUp(item);
		}
	}

	/** 把单个物品实体收进背包；放不下就留在原地。 */
	private void tryPickUp(net.minecraft.world.entity.item.ItemEntity item) {
		ItemStack stack = item.getItem();
		int count = stack.getCount();
		if (count <= 0) {
			item.discard();
			return;
		}
		// 物品名要在 setCount 归零前记录（setCount(0) 会把栈变成“空气”）
		String itemName = stack.getHoverName().getString();
		// 合并进背包（内部会优先并入已有堆叠，再占空槽）
		if (!stack.isEmpty()) {
			ItemStack leftover = inventory.addItem(stack);
			stack.setCount(leftover.getCount());
		}
		int taken = count - stack.getCount();
		if (taken > 0) {
			com.swaydy.opencraft.debug.DebugLog.log("pickup",
					"助手拾取了 {}×{}（位置 {}, {}, {}）", itemName, taken,
					(int) getX(), (int) getY(), (int) getZ());
			this.take(item, taken);
			this.onItemPickup(item);
		}
		if (stack.isEmpty()) {
			item.discard();
		}
	}

	/**
	 * 自动为挖掘选择背包里“最快”的工具并换到主手（玩家式自动换镐）。
	 * 没有更快工具时不改动主手。旧主手物品放回背包。
	 */
	public void autoSelectMiningTool(net.minecraft.world.level.block.state.BlockState state) {
		float bestSpeed = getMainHandItem().isEmpty()
				? 1.0F : getMainHandItem().getDestroySpeed(state);
		int bestSlot = -1;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.isEmpty()) {
				continue;
			}
			float speed = stack.getDestroySpeed(state);
			if (speed > bestSpeed && speed > 1.0F) {
				bestSpeed = speed;
				bestSlot = i;
			}
		}
		if (bestSlot < 0) {
			return;
		}
		ItemStack tool = inventory.removeItem(bestSlot, 1);
		ItemStack mainHand = getMainHandItem();
		setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, tool);
		if (!mainHand.isEmpty()) {
			ItemStack left = giveToInventory(mainHand);
			if (!left.isEmpty() && this.level() instanceof net.minecraft.server.level.ServerLevel sl) {
				// 背包满：旧主手掉落到脚边，不凭空消失
				spawnAtLocation(sl, left);
			}
		}
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
			// 第一次右键：绑定主人
			setOwner(player);
			player.displayClientMessage(Component.translatable("entity.opencraft.ai_assistant.bind"),
					true);
			OpenCraftMod.LOGGER.info("[OpenCraft] 玩家 {} 绑定了 AI 助手", player.getName().getString());
			com.swaydy.opencraft.debug.DebugLog.log("bind",
					"玩家 {} 右键绑定了助手（实体 ID {}）", player.getName().getString(), getId());
		} else if (ownerUuid.equals(player.getUUID())) {
			if (player.isShiftKeyDown()) {
				// 潜行右键：快速切换跟随/待命（保留原有快捷操作）
				boolean following = !isFollowing();
				setFollowing(following);
				player.displayClientMessage(
						Component.translatable(following
								? "entity.opencraft.ai_assistant.following"
								: "entity.opencraft.ai_assistant.staying"),
						true);
			} else {
				// 普通右键：给主人打开“互动界面”（和这个助手聊天 / 跟随待命 / 送走）
				openInteractScreen((ServerPlayer) player);
			}
		} else {
			player.displayClientMessage(Component.translatable("entity.opencraft.ai_assistant.not_owner"),
					true);
		}
		return InteractionResult.SUCCESS;
	}

	/** 给主人发送互动界面数据（打开/刷新右键互动 GUI）。 */
	private void openInteractScreen(ServerPlayer player) {
		com.swaydy.opencraft.ai.AiBlockConfig config = getConfig();
		String model = config.model == null ? "" : config.model;
		String agent = config.agent == null ? "general_agent" : config.agent;
		// 聊天回复的 S2C 事件按“绑定方块坐标”路由回互动界面，因此把方块坐标一起下发
		GlobalPos block = getConfigBlock();
		BlockPos blockPos = block == null ? this.blockPosition() : block.pos();
		ResourceKey<Level> dimension = block == null ? this.level().dimension() : block.dimension();
		try {
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
					new com.swaydy.opencraft.net.AssistantPayloads.AssistantInteractPayload(
							this.getId(), getDisplayName().getString(), isFollowing(), true, model,
							agent, blockPos, dimension));
		} catch (Exception e) {
			// 模拟连接等场景发送失败：静默忽略（与配置界面一致）
			OpenCraftMod.LOGGER.debug("[OpenCraft] 发送互动界面数据失败（可能是模拟连接）: {}", e.toString());
		}
	}

	// ------------------------------------------------------------------
	// 生命周期
	// ------------------------------------------------------------------

	@Override
	public void tick() {
		super.tick();
		if (!this.level().isClientSide()) {
			// 首次 tick：按当前 Agent 预设注册插件 Goal（registerGoals 在字段初始化前，无法读配置）
			ensureAgentGoals();
			// 像普通玩家一样自动拾取地上的物品（进自己的 36 格背包）
			if (this.tickCount % PICKUP_SCAN_INTERVAL == 0) {
				pickupNearbyItems();
			}
		}
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
		com.swaydy.opencraft.debug.DebugLog.log("teleport",
				"助手跨维度跟随主人 {} 到 {}", owner.getName().getString(),
				owner.level().dimension().identifier());
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

	/**
	 * 死亡时像玩家一样掉落全部物品：36 格背包 + 全部装备槽（手/副手/头盔/胸甲/护腿/靴子）。
	 */
	@Override
	protected void dropEquipment(net.minecraft.server.level.ServerLevel level) {
		super.dropEquipment(level);
		int dropped = 0;
		for (ItemStack stack : inventory.getItems()) {
			if (!stack.isEmpty()) {
				spawnAtLocation(level, stack);
				dropped += stack.getCount();
			}
		}
		inventory.clearContent();
		for (net.minecraft.world.entity.EquipmentSlot slot
				: net.minecraft.world.entity.EquipmentSlot.VALUES) {
			ItemStack stack = getItemBySlot(slot);
			if (!stack.isEmpty()) {
				spawnAtLocation(level, stack);
				dropped += stack.getCount();
				setItemSlot(slot, ItemStack.EMPTY);
			}
		}
		com.swaydy.opencraft.debug.DebugLog.log("death",
				"助手死亡，掉落物品共 {} 个（背包+装备）", dropped);
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		this.entityData.get(DATA_OWNER)
				.ifPresent(ref -> EntityReference.store(ref, output, "Owner"));
		output.putBoolean("Following", isFollowing());
		output.storeNullable("ConfigBlock", GlobalPos.CODEC, configBlock);
		// 背包持久化：SimpleContainer 提供 storeAsItemList，用 ItemStack 列表写入
		inventory.storeAsItemList(
				output.list("Inventory", ItemStack.OPTIONAL_CODEC));
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
		// 读回背包（缺省留空）
		input.listOrEmpty("Inventory", ItemStack.OPTIONAL_CODEC)
				.forEach(inventory::addItem);
	}
}
