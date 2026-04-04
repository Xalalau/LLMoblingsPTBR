package com.gblfxt.llmoblings.entity;

import com.gblfxt.llmoblings.ChunkLoadingManager;
import com.gblfxt.llmoblings.Config;
import com.gblfxt.llmoblings.LLMoblings;
import com.gblfxt.llmoblings.ai.CompanionAI;
import com.gblfxt.llmoblings.compat.ArtifactsIntegration;
import com.gblfxt.llmoblings.compat.JourneyMapIntegration;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.portal.DimensionTransition;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;

public class CompanionEntity extends PathfinderMob implements Container {
    // Synced data
    private static final EntityDataAccessor<String> DATA_NAME =
            SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER =
            SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<String> DATA_SKIN_URL =
            SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.STRING);

    // Inventory (36 slots like player + 4 armor + 1 offhand)
    private final NonNullList<ItemStack> inventory = NonNullList.withSize(36, ItemStack.EMPTY);
    private final NonNullList<ItemStack> armorSlots = NonNullList.withSize(4, ItemStack.EMPTY);
    private ItemStack offhandItem = ItemStack.EMPTY;
    private int selectedSlot = 0;
    private long lastManualEatTick = -200;

    // AI Controller
    private CompanionAI aiController;

    public CompanionEntity(EntityType<? extends CompanionEntity> type, Level level) {
        super(type, level);
        // Step height is set via entity type attributes in 1.21.1
        if (!level.isClientSide) {
            this.aiController = new CompanionAI(this);
        }

        // Enable door opening in navigation
        if (this.getNavigation() instanceof GroundPathNavigation groundNav) {
            groundNav.setCanOpenDoors(true);
            groundNav.setCanPassDoors(true);
        }

        // Add player tag so ethereal glass and similar blocks allow passage
        this.addTag("player");
        this.addTag("Player");
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        // Allow companion to open and close doors
        this.goalSelector.addGoal(1, new OpenDoorGoal(this, true));
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.3D)
                .add(Attributes.ATTACK_DAMAGE, 1.0D)
                .add(Attributes.FOLLOW_RANGE, 48.0D);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_NAME, "Companion");
        builder.define(DATA_OWNER, Optional.empty());
        builder.define(DATA_SKIN_URL, "");
    }

    @Override
    public void tick() {
        super.tick();

        if (!this.level().isClientSide) {
            // Tick AI controller
            if (aiController != null) {
                aiController.tick();
            }

            // Item pickup
            pickupItems();

            // Auto-eat when health is low (every 2 seconds)
            if (this.tickCount % 40 == 0) {
                tryEatFood();
            }

            // Auto-equip best items (every 3 seconds)
            if (this.tickCount % 60 == 0) {
                autoEquipBestItems();
            }

            // Check for flying artifacts (every second)
            if (this.tickCount % 20 == 0) {
                updateFlyingAbility();
            }

            // Start/update chunk loading
            if (this.tickCount == 1) {
                ChunkLoadingManager.startLoadingChunks(this);
            } else if (this.tickCount % 100 == 0) {
                ChunkLoadingManager.updateChunkLoading(this);
            }

            // Portal cooldown to prevent spam
            if (portalCooldown > 0) {
                portalCooldown--;
            }

            // Handle swimming
            if (this.isInWater()) {
                handleSwimming();
            }

            // Check for nearby boats periodically
            if (this.tickCount % 40 == 0 && this.isInWater() && !this.isPassenger()) {
                tryBoardNearbyBoat();
            }

            // Update map marker every 2 seconds
            if (this.tickCount % 40 == 0) {
                JourneyMapIntegration.updateCompanionMarker(this);
            }
        }

        // Handle flying movement
        if (canFlyWithArtifact && !this.onGround()) {
            handleFlyingMovement();
        }
    }

    private boolean canFlyWithArtifact = false;

    // Portal awareness - prevent unintended dimension changes
    private boolean allowPortalUse = false;
    private int portalCooldown = 0;

    private void updateFlyingAbility() {
        boolean couldFly = canFlyWithArtifact;
        canFlyWithArtifact = ArtifactsIntegration.canFly(this);

        if (canFlyWithArtifact && !couldFly) {
            LLMoblings.LOGGER.info("[{}] Now has flying ability from artifact!", getCompanionName());
        } else if (!canFlyWithArtifact && couldFly) {
            LLMoblings.LOGGER.info("[{}] Lost flying ability", getCompanionName());
            this.setNoGravity(false);
        }
    }

    private void handleFlyingMovement() {
        if (!canFlyWithArtifact) return;

        // Allow the companion to fly when they have a flying artifact
        // Reduce fall damage and allow hovering
        Vec3 motion = this.getDeltaMovement();

        // Slow descent when flying
        if (motion.y < -0.1) {
            this.setDeltaMovement(motion.x, -0.05, motion.z);
        }

        // Don't take fall damage when we can fly
        this.fallDistance = 0;
    }

    public boolean canFly() {
        return canFlyWithArtifact;
    }

    /**
     * Handle swimming behavior when in water.
     * Makes the companion swim to surface and move towards their target.
     */
    private void handleSwimming() {
        // Swim upward when below water surface
        if (this.isUnderWater()) {
            Vec3 motion = this.getDeltaMovement();
            // Swim upward
            this.setDeltaMovement(motion.x, 0.04, motion.z);
            this.setSwimming(true);
        } else if (this.isInWater() && this.getDeltaMovement().y < 0) {
            // Stay afloat at surface
            Vec3 motion = this.getDeltaMovement();
            this.setDeltaMovement(motion.x, 0.0, motion.z);
        }

        // Reduce drowning by swimming to surface
        if (this.getAirSupply() < 100) {
            // Prioritize getting air
            Vec3 motion = this.getDeltaMovement();
            this.setDeltaMovement(motion.x, 0.08, motion.z);
        }
    }

    /**
     * Try to board a nearby empty boat.
     */
    private void tryBoardNearbyBoat() {
        if (this.getVehicle() != null) return; // Already in a vehicle

        AABB searchBox = this.getBoundingBox().inflate(5.0);
        List<net.minecraft.world.entity.vehicle.Boat> boats = this.level().getEntitiesOfClass(
                net.minecraft.world.entity.vehicle.Boat.class,
                searchBox,
                boat -> boat.isAlive() && boat.getPassengers().isEmpty()
        );

        if (!boats.isEmpty()) {
            net.minecraft.world.entity.vehicle.Boat nearestBoat = boats.stream()
                    .min(Comparator.comparingDouble(boat -> this.distanceToSqr(boat)))
                    .orElse(null);

            if (nearestBoat != null && this.distanceTo(nearestBoat) < 3.0) {
                this.startRiding(nearestBoat);
                LLMoblings.LOGGER.info("[{}] Boarded a boat!", getCompanionName());

                Player owner = getOwner();
                if (owner != null) {
                    owner.sendSystemMessage(Component.literal(
                            "[" + getCompanionName() + "] Encontrei um barco!"
                    ));
                }
            } else if (nearestBoat != null) {
                // Swim toward the boat
                this.getNavigation().moveTo(nearestBoat, 1.2);
            }
        }
    }

    /**
     * Exit from boat when owner exits or reaches land.
     */
    public void checkBoatExit() {
        if (this.getVehicle() instanceof net.minecraft.world.entity.vehicle.Boat boat) {
            // Exit if near land
            BlockPos belowBoat = boat.blockPosition().below();
            if (!this.level().getFluidState(belowBoat).isEmpty() &&
                this.level().getBlockState(belowBoat.north()).isSolid() ||
                this.level().getBlockState(belowBoat.south()).isSolid() ||
                this.level().getBlockState(belowBoat.east()).isSolid() ||
                this.level().getBlockState(belowBoat.west()).isSolid()) {
                this.stopRiding();
                LLMoblings.LOGGER.info("[{}] Exiting boat near land", getCompanionName());
            }
        }
    }

    /**
     * Check if standing on an elevator block and use it.
     * Supports various elevator mods (Quark, Elevator Mod, etc.)
     */
    public void tryUseElevator(boolean goUp) {
        BlockPos below = this.blockPosition().below();
        net.minecraft.world.level.block.state.BlockState state = this.level().getBlockState(below);
        String blockName = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();

        // Check if standing on an elevator block
        if (blockName.contains("elevator") || blockName.contains("elevatoro")) {
            // Find destination - look for matching elevator above or below
            BlockPos searchStart = this.blockPosition();
            int maxSearch = 64;

            if (goUp) {
                for (int y = 1; y <= maxSearch; y++) {
                    BlockPos checkPos = searchStart.above(y);
                    net.minecraft.world.level.block.state.BlockState checkState = this.level().getBlockState(checkPos.below());
                    String checkName = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(checkState.getBlock()).toString();

                    if (checkName.contains("elevator")) {
                        // Found elevator above - check if space is clear
                        if (this.level().getBlockState(checkPos).isAir() &&
                            this.level().getBlockState(checkPos.above()).isAir()) {
                            this.teleportTo(checkPos.getX() + 0.5, checkPos.getY(), checkPos.getZ() + 0.5);
                            LLMoblings.LOGGER.info("[{}] Used elevator to go up to Y={}", getCompanionName(), checkPos.getY());
                            return;
                        }
                    }
                }
            } else {
                for (int y = 1; y <= maxSearch; y++) {
                    BlockPos checkPos = searchStart.below(y);
                    net.minecraft.world.level.block.state.BlockState checkState = this.level().getBlockState(checkPos.below());
                    String checkName = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(checkState.getBlock()).toString();

                    if (checkName.contains("elevator")) {
                        // Found elevator below - check if space is clear
                        if (this.level().getBlockState(checkPos).isAir() &&
                            this.level().getBlockState(checkPos.above()).isAir()) {
                            this.teleportTo(checkPos.getX() + 0.5, checkPos.getY(), checkPos.getZ() + 0.5);
                            LLMoblings.LOGGER.info("[{}] Used elevator to go down to Y={}", getCompanionName(), checkPos.getY());
                            return;
                        }
                    }
                }
            }
        }
    }

    /**
     * Check if standing on an elevator block.
     */
    public boolean isOnElevator() {
        BlockPos below = this.blockPosition().below();
        net.minecraft.world.level.block.state.BlockState state = this.level().getBlockState(below);
        String blockName = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return blockName.contains("elevator");
    }

    @Override
    public void remove(RemovalReason reason) {
        if (!this.level().isClientSide) {
            ChunkLoadingManager.stopLoadingChunks(this);
            JourneyMapIntegration.removeCompanionMarker(this);
        }
        super.remove(reason);
    }

    /**
     * Control portal usage to prevent unintended dimension changes and duplication.
     * Companions will only use portals when explicitly commanded to.
     */
    @Override
    public Entity changeDimension(DimensionTransition transition) {
        if (!allowPortalUse) {
            // Companion wandered into portal without being told - push them back
            LLMoblings.LOGGER.info("[{}] Blocked unintended portal use - stepping back", getCompanionName());
            // Push companion away from portal
            Vec3 motion = this.getDeltaMovement();
            this.setDeltaMovement(motion.x * -2, 0.3, motion.z * -2);
            return null;
        }

        if (portalCooldown > 0) {
            LLMoblings.LOGGER.info("[{}] Portal on cooldown, waiting...", getCompanionName());
            return null;
        }

        // Stop chunk loading in old dimension before changing
        ChunkLoadingManager.stopLoadingChunks(this);

        LLMoblings.LOGGER.info("[{}] Changing dimension via portal", getCompanionName());

        // Perform the dimension change
        Entity newEntity = super.changeDimension(transition);

        if (newEntity instanceof CompanionEntity newCompanion) {
            // Transfer state to new entity
            newCompanion.portalCooldown = 200; // 10 second cooldown
            newCompanion.allowPortalUse = false; // Reset portal permission

            // Start chunk loading in new dimension
            ChunkLoadingManager.startLoadingChunks(newCompanion);

            LLMoblings.LOGGER.info("[{}] Successfully changed dimension", newCompanion.getCompanionName());
        }

        return newEntity;
    }

    /**
     * Allow the companion to use portals (called when commanded to go through)
     */
    public void allowPortalUse() {
        this.allowPortalUse = true;
        LLMoblings.LOGGER.info("[{}] Portal use enabled", getCompanionName());
    }

    /**
     * Disallow portal use (resets after dimension change)
     */
    public void disallowPortalUse() {
        this.allowPortalUse = false;
    }

    /**
     * Check if companion can use portals
     */
    public boolean canUsePortal() {
        return allowPortalUse && portalCooldown <= 0;
    }

    private void pickupItems() {
        if (!this.isAlive() || !this.level().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            return;
        }

        int radius = Config.ITEM_PICKUP_RADIUS.get();
        AABB pickupBox = this.getBoundingBox().inflate(radius);

        List<ItemEntity> items = this.level().getEntitiesOfClass(ItemEntity.class, pickupBox,
                item -> !item.isRemoved() && !item.getItem().isEmpty() && item.isAlive());

        for (ItemEntity itemEntity : items) {
            ItemStack stack = itemEntity.getItem();
            int originalCount = stack.getCount();

            ItemStack remaining = addToInventory(stack);
            if (remaining.isEmpty()) {
                this.take(itemEntity, originalCount);
                itemEntity.discard();
            } else if (remaining.getCount() < originalCount) {
                this.take(itemEntity, originalCount - remaining.getCount());
                itemEntity.setItem(remaining);
            }
        }
    }

    public ItemStack addToInventory(ItemStack stack) {
        // Try to stack with existing items first
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack slot = inventory.get(i);
            if (ItemStack.isSameItemSameComponents(slot, stack) && slot.getCount() < slot.getMaxStackSize()) {
                int toAdd = Math.min(stack.getCount(), slot.getMaxStackSize() - slot.getCount());
                slot.grow(toAdd);
                stack.shrink(toAdd);
                if (stack.isEmpty()) return ItemStack.EMPTY;
            }
        }

        // Find empty slot
        for (int i = 0; i < inventory.size(); i++) {
            if (inventory.get(i).isEmpty()) {
                inventory.set(i, stack.copy());
                return ItemStack.EMPTY;
            }
        }

        return stack;
    }

    /**
     * Try to eat food from inventory when health is low.
     */
    private void tryEatFood() {
        // Only eat if health is below 75%
        if (this.getHealth() >= this.getMaxHealth() * 0.75f) {
            return;
        }

        eatFoodFromInventory("");
    }

    public boolean eatFoodFromInventory(String preferredItem) {
        // Prevent repeated manual eat calls from consuming multiple foods in a short loop.
        if (this.tickCount - lastManualEatTick < 20) {
            return false;
        }

        int preferredSlot = findFoodSlot(preferredItem);
        if (preferredSlot >= 0) {
            return consumeFoodFromSlot(preferredSlot);
        }

        if (preferredItem != null && !preferredItem.isBlank()) {
            return false;
        }

        for (int i = 0; i < inventory.size(); i++) {
            if (consumeFoodFromSlot(i)) {
                return true;
            }
        }
        return false;
    }

    private int findFoodSlot(String preferredItem) {
        String normalized = normalizeFoodQuery(preferredItem);
        if (normalized.isEmpty()) {
            return -1;
        }

        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.get(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem().getFoodProperties(stack, this) == null) continue;

            String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            String itemDesc = stack.getItem().getDescription().getString();
            if (matchesFoodQuery(normalized, itemId) || matchesFoodQuery(normalized, itemDesc)) {
                return i;
            }
        }
        return -1;
    }

    private String normalizeFoodQuery(String query) {
        if (query == null) return "";
        return java.text.Normalizer.normalize(query, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase()
                .trim()
                .replace(' ', '_');
    }

    private boolean matchesFoodQuery(String normalizedQuery, String value) {
        String normalizedValue = normalizeFoodQuery(value);
        if (normalizedQuery.isEmpty() || normalizedValue.isEmpty()) {
            return false;
        }
        if (normalizedValue.contains(normalizedQuery) || normalizedQuery.contains(normalizedValue)) {
            return true;
        }
        return (normalizedQuery.equals("carrot") && normalizedValue.contains("cenoura"))
                || (normalizedQuery.equals("cenoura") && normalizedValue.contains("carrot"))
                || (normalizedQuery.equals("potato") && normalizedValue.contains("batata"))
                || (normalizedQuery.equals("batata") && normalizedValue.contains("potato"));
    }

    private boolean consumeFoodFromSlot(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= inventory.size()) {
            return false;
        }

        ItemStack stack = inventory.get(slotIndex);
        if (stack.isEmpty()) {
            return false;
        }

        var foodProps = stack.getItem().getFoodProperties(stack, this);
        if (foodProps == null) {
            return false;
        }

        ItemStack before = stack.copy();
        float healAmount = Math.max(2.0f, (float) foodProps.nutrition());
        float newHealth = Math.min(this.getMaxHealth(), this.getHealth() + healAmount);
        this.setHealth(newHealth);

        stack.shrink(1);
        ItemStack after = stack;
        if (after.isEmpty()) {
            inventory.set(slotIndex, ItemStack.EMPTY);
            after = ItemStack.EMPTY;
        }

        if (slotIndex == selectedSlot) {
            setItemSlot(EquipmentSlot.MAINHAND, after.copy());
        }

        lastManualEatTick = this.tickCount;
        this.swing(InteractionHand.MAIN_HAND, true);
        this.playSound(net.minecraft.sounds.SoundEvents.GENERIC_EAT, 0.5f, 1.0f);

        Player owner = getOwner();
        if (owner != null) {
            owner.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[" + getCompanionName() + "] *come " + before.getItem().getDescription().getString() + "* Recuperou " + (int) healAmount + " de vida!"
            ));
        }

        LLMoblings.LOGGER.debug("{} ate {} and healed {} HP", getCompanionName(), before.getItem(), healAmount);
        return true;
    }

    /**
     * Automatically equip the best items from inventory.
     */
    private void autoEquipBestItems() {
        // Check for better weapon
        ItemStack currentWeapon = getMainHandItem();
        float currentDamage = getItemDamage(currentWeapon);

        int bestWeaponSlot = -1;
        float bestDamage = currentDamage;

        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.get(i);
            if (stack.isEmpty()) continue;

            Item item = stack.getItem();
            if (item instanceof SwordItem || item instanceof AxeItem) {
                float damage = getItemDamage(stack);
                if (damage > bestDamage) {
                    bestDamage = damage;
                    bestWeaponSlot = i;
                }
            }
        }

        if (bestWeaponSlot >= 0) {
            // Swap to better weapon
            ItemStack betterWeapon = inventory.get(bestWeaponSlot);
            if (!currentWeapon.isEmpty()) {
                inventory.set(bestWeaponSlot, currentWeapon);
            } else {
                inventory.set(bestWeaponSlot, ItemStack.EMPTY);
            }
            setItemSlot(EquipmentSlot.MAINHAND, betterWeapon);
            LLMoblings.LOGGER.info("[{}] Equipped better weapon: {}", getCompanionName(), betterWeapon.getItem().getDescription().getString());

            Player owner = getOwner();
            if (owner != null) {
                owner.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "[" + getCompanionName() + "] *equipa " + betterWeapon.getItem().getDescription().getString() + "*"
                ));
            }
        }

        // Check for better armor
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack currentArmor = getItemBySlot(slot);
            float currentProtection = getArmorProtection(currentArmor);

            int bestArmorSlot = -1;
            float bestProtection = currentProtection;

            for (int i = 0; i < inventory.size(); i++) {
                ItemStack stack = inventory.get(i);
                if (stack.isEmpty()) continue;

                if (stack.getItem() instanceof ArmorItem armorItem) {
                    if (armorItem.getEquipmentSlot() == slot) {
                        float protection = getArmorProtection(stack);
                        if (protection > bestProtection) {
                            bestProtection = protection;
                            bestArmorSlot = i;
                        }
                    }
                }
            }

            if (bestArmorSlot >= 0) {
                ItemStack betterArmor = inventory.get(bestArmorSlot);
                if (!currentArmor.isEmpty()) {
                    inventory.set(bestArmorSlot, currentArmor);
                } else {
                    inventory.set(bestArmorSlot, ItemStack.EMPTY);
                }
                setItemSlot(slot, betterArmor);
                LLMoblings.LOGGER.info("[{}] Equipped better armor: {}", getCompanionName(), betterArmor.getItem().getDescription().getString());

                Player owner = getOwner();
                if (owner != null) {
                    owner.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                            "[" + getCompanionName() + "] *equipa " + betterArmor.getItem().getDescription().getString() + "*"
                    ));
                }
            }
        }
    }

    private float getItemDamage(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        Item item = stack.getItem();
        if (item instanceof SwordItem sword) {
            return sword.getDamage(stack);
        } else if (item instanceof AxeItem axe) {
            return axe.getDamage(stack);
        }
        return 0;
    }

    private float getArmorProtection(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        if (stack.getItem() instanceof ArmorItem armor) {
            return armor.getDefense();
        }
        return 0;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        // If damage is disabled, companions are invulnerable
        if (!Config.COMPANIONS_TAKE_DAMAGE.get()) {
            return false;
        }
        boolean wasHurt = super.hurt(source, amount);
        if (wasHurt && aiController != null) {
            aiController.onCompanionHurt();
        }
        return wasHurt;
    }

    @Override
    public void die(DamageSource damageSource) {
        LLMoblings.LOGGER.warn("[{}] DIED! Cause: {}, Health was: {}",
            getCompanionName(), damageSource.getMsgId(), getHealth());
        ChunkLoadingManager.stopLoadingChunks(this);
        super.die(damageSource);
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (player.isShiftKeyDown()) {
            // Open companion GUI (inventory/settings)
            // TODO: Implement GUI
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    // Owner management
    public void setOwner(@Nullable Player player) {
        this.entityData.set(DATA_OWNER, player != null ? Optional.of(player.getUUID()) : Optional.empty());
    }

    @Nullable
    public UUID getOwnerUUID() {
        return this.entityData.get(DATA_OWNER).orElse(null);
    }

    @Nullable
    public Player getOwner() {
        UUID uuid = getOwnerUUID();
        if (uuid == null) return null;
        return this.level().getPlayerByUUID(uuid);
    }

    public boolean isOwner(Player player) {
        UUID ownerUUID = getOwnerUUID();
        return ownerUUID != null && ownerUUID.equals(player.getUUID());
    }

    // Name management
    public void setCompanionName(String name) {
        this.entityData.set(DATA_NAME, name);
    }

    public String getCompanionName() {
        return this.entityData.get(DATA_NAME);
    }

    @Override
    public Component getName() {
        String name = getCompanionName();
        return name.isEmpty() ? super.getName() : Component.literal(name);
    }

    @Override
    public Component getDisplayName() {
        String name = getCompanionName();
        return name.isEmpty() ? super.getDisplayName() : Component.literal(name);
    }

    // Skin URL
    public void setSkinUrl(String url) {
        this.entityData.set(DATA_SKIN_URL, url);
    }

    public String getSkinUrl() {
        return this.entityData.get(DATA_SKIN_URL);
    }

    // Inventory slot selection
    public int getSelectedSlot() {
        return selectedSlot;
    }

    public void setSelectedSlot(int slot) {
        this.selectedSlot = Math.max(0, Math.min(slot, 8));
    }

    // Get held items
    @Override
    public ItemStack getMainHandItem() {
        return selectedSlot >= 0 && selectedSlot < inventory.size() ? inventory.get(selectedSlot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack getOffhandItem() {
        return offhandItem;
    }

    @Override
    public Iterable<ItemStack> getArmorSlots() {
        return armorSlots;
    }

    @Override
    public ItemStack getItemBySlot(EquipmentSlot slot) {
        return switch (slot) {
            case MAINHAND -> getMainHandItem();
            case OFFHAND -> offhandItem;
            case HEAD -> armorSlots.get(3);
            case CHEST -> armorSlots.get(2);
            case LEGS -> armorSlots.get(1);
            case FEET -> armorSlots.get(0);
            default -> ItemStack.EMPTY;
        };
    }

    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
        // Store old item for sync notification
        ItemStack oldItem = getItemBySlot(slot);

        // Set the new item
        switch (slot) {
            case MAINHAND -> inventory.set(selectedSlot, stack);
            case OFFHAND -> offhandItem = stack;
            case HEAD -> armorSlots.set(3, stack);
            case CHEST -> armorSlots.set(2, stack);
            case LEGS -> armorSlots.set(1, stack);
            case FEET -> armorSlots.set(0, stack);
        }

        // Trigger equipment change sync to clients
        this.onEquipItem(slot, oldItem, stack);
    }

    @Override
    public HumanoidArm getMainArm() {
        return HumanoidArm.RIGHT;
    }

    // AI Controller access
    public CompanionAI getAIController() {
        return aiController;
    }

    // Process chat message from any player
    public void onChatMessage(Player sender, String message) {
        onChatMessage(sender, message, isOwner(sender));
    }

    // Process chat message with explicit owner flag
    public void onChatMessage(Player sender, String message, boolean hasCommandAccess) {
        if (aiController != null) {
            LLMoblings.LOGGER.info("[{}] Processing message from {} (commandAccess={}): {}",
                    getCompanionName(), sender.getName().getString(), hasCommandAccess, message);

            if (hasCommandAccess) {
                // Owner or teammate can give commands - pass the sender so follow works correctly
                aiController.processMessage(message, sender);
            } else {
                // Non-owner/non-teammate can chat but not command
                aiController.processMessageFromStranger(sender, message);
            }
        }
    }

    // NBT Serialization
    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);

        tag.putString("CompanionName", getCompanionName());
        tag.putString("SkinUrl", getSkinUrl());
        tag.putInt("SelectedSlot", selectedSlot);

        UUID owner = getOwnerUUID();
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }

        // Save inventory
        ListTag inventoryTag = new ListTag();
        for (int i = 0; i < inventory.size(); i++) {
            if (!inventory.get(i).isEmpty()) {
                CompoundTag slotTag = new CompoundTag();
                slotTag.putByte("Slot", (byte) i);
                inventoryTag.add(inventory.get(i).save(this.registryAccess(), slotTag));
            }
        }
        tag.put("Inventory", inventoryTag);

        // Save armor
        ListTag armorTag = new ListTag();
        for (int i = 0; i < armorSlots.size(); i++) {
            if (!armorSlots.get(i).isEmpty()) {
                CompoundTag slotTag = new CompoundTag();
                slotTag.putByte("Slot", (byte) i);
                armorTag.add(armorSlots.get(i).save(this.registryAccess(), slotTag));
            }
        }
        tag.put("Armor", armorTag);

        if (!offhandItem.isEmpty()) {
            tag.put("Offhand", offhandItem.save(this.registryAccess()));
        }

        // Save portal state
        tag.putBoolean("AllowPortalUse", allowPortalUse);
        tag.putInt("PortalCooldown", portalCooldown);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);

        setCompanionName(tag.getString("CompanionName"));
        setSkinUrl(tag.getString("SkinUrl"));
        selectedSlot = tag.getInt("SelectedSlot");

        if (tag.hasUUID("Owner")) {
            this.entityData.set(DATA_OWNER, Optional.of(tag.getUUID("Owner")));
        }

        // Load inventory
        ListTag inventoryTag = tag.getList("Inventory", 10);
        for (int i = 0; i < inventoryTag.size(); i++) {
            CompoundTag slotTag = inventoryTag.getCompound(i);
            int slot = slotTag.getByte("Slot") & 255;
            if (slot < inventory.size()) {
                inventory.set(slot, ItemStack.parse(this.registryAccess(), slotTag).orElse(ItemStack.EMPTY));
            }
        }

        // Load armor
        ListTag armorTag = tag.getList("Armor", 10);
        for (int i = 0; i < armorTag.size(); i++) {
            CompoundTag slotTag = armorTag.getCompound(i);
            int slot = slotTag.getByte("Slot") & 255;
            if (slot < armorSlots.size()) {
                armorSlots.set(slot, ItemStack.parse(this.registryAccess(), slotTag).orElse(ItemStack.EMPTY));
            }
        }

        if (tag.contains("Offhand")) {
            offhandItem = ItemStack.parse(this.registryAccess(), tag.getCompound("Offhand")).orElse(ItemStack.EMPTY);
        }

        // Load portal state
        allowPortalUse = tag.getBoolean("AllowPortalUse");
        portalCooldown = tag.getInt("PortalCooldown");

        // Recreate AI controller on load
        if (!this.level().isClientSide && aiController == null) {
            aiController = new CompanionAI(this);
        }
    }

    // Container implementation for inventory access
    @Override
    public int getContainerSize() {
        return inventory.size();
    }

    @Override
    public boolean isEmpty() {
        return inventory.stream().allMatch(ItemStack::isEmpty);
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot >= 0 && slot < inventory.size() ? inventory.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return ContainerHelper.removeItem(inventory, slot, amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(inventory, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot >= 0 && slot < inventory.size()) {
            inventory.set(slot, stack);
        }
    }

    @Override
    public void setChanged() {
        // Inventory changed callback
    }

    @Override
    public boolean stillValid(Player player) {
        return this.isAlive() && player.distanceToSqr(this) < 64.0D;
    }

    @Override
    public void clearContent() {
        inventory.clear();
    }
}
