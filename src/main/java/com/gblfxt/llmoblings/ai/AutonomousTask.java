package com.gblfxt.llmoblings.ai;

import com.gblfxt.llmoblings.ChunkLoadingManager;
import com.gblfxt.llmoblings.LLMoblings;
import com.gblfxt.llmoblings.compat.AE2Integration;
import com.gblfxt.llmoblings.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.function.Predicate;

/**
 * Autonomous behavior for companions - independent survival and base management.
 */
public class AutonomousTask {
    private final CompanionEntity companion;
    private final int baseRadius;

    // Current autonomous sub-task
    private AutonomousState currentState = AutonomousState.ASSESSING;
    private int ticksInState = 0;
    private int reportCooldown = 0;

    // Targets
    private BlockPos targetStorage = null;
    private BlockPos meAccessPoint = null;  // AE2 ME network access
    private net.minecraft.world.entity.Entity huntTarget = null;
    private BlockPos homePos = null;
    private BlockPos exploreTarget = null;
    private final List<BlockPos> visitedLocations = new ArrayList<>();

    // Stuck detection
    private Vec3 lastPosition = null;
    private int stuckTicks = 0;

    // Resource tracking
    private final Map<String, Integer> baseResources = new HashMap<>();
    private final List<String> needs = new ArrayList<>();
    private int foodCount = 0;
    private boolean hasWeapon = false;
    private boolean hasArmor = false;
    private int storedFoodCount = 0;
    private boolean storageHasWeapon = false;
    private boolean storageHasArmor = false;
    private FarmingTask farmingTask = null;

    // Behavior pacing / anti-loop protection
    private int totalTicks = 0;
    private int lastStorageScanTick = -1200;
    private int nextHuntAllowedTick = 0;
    private int consecutiveHuntFailures = 0;
    private String lastStorageSummary = "";

    public enum AutonomousState {
        ASSESSING,      // Scanning base, checking resources
        HUNTING,        // Hunting animals for food
        GATHERING,      // Mining/gathering resources
        FARMING,        // Harvesting and replanting crops
        EQUIPPING,      // Equipping armor/weapons
        STORING,        // Depositing items in chests
        RETRIEVING_STORAGE, // Getting food/gear from nearby storage
        RETRIEVING_ME,  // Going to ME terminal for items
        EXPLORING,      // Wandering and exploring the base
        PATROLLING,     // Guarding the area
        RESTING         // Idle near base
    }

    public AutonomousTask(CompanionEntity companion, int baseRadius) {
        this.companion = companion;
        // Cap base radius to loaded chunk boundaries (32 blocks)
        this.baseRadius = Math.min(baseRadius, ChunkLoadingManager.getWorkingRadius());
        this.homePos = companion.blockPosition();
    }

    /**
     * Set the task to start in exploring mode directly.
     */
    public void setExploring() {
        this.currentState = AutonomousState.EXPLORING;
        this.ticksInState = 0;
    }

    public void setHomePos(BlockPos homePos) {
        this.homePos = homePos;
    }

    public void tick() {
        totalTicks++;
        ticksInState++;
        reportCooldown--;

        switch (currentState) {
            case ASSESSING -> tickAssessing();
            case HUNTING -> tickHunting();
            case GATHERING -> tickGathering();
            case FARMING -> tickFarming();
            case EQUIPPING -> tickEquipping();
            case STORING -> tickStoring();
            case RETRIEVING_STORAGE -> tickRetrievingStorage();
            case RETRIEVING_ME -> tickRetrievingME();
            case EXPLORING -> tickExploring();
            case PATROLLING -> tickPatrolling();
            case RESTING -> tickResting();
        }
    }

    private void tickAssessing() {
        if (ticksInState == 1) {
            report("Avaliando a área...");
        }

        // Scan for storage containers periodically. This is expensive, so avoid
        // rescanning every assessment cycle unless we have no known storage yet.
        if (ticksInState == 20 && (targetStorage == null || meAccessPoint == null || totalTicks - lastStorageScanTick >= 200)) {
            scanStorage();
            lastStorageScanTick = totalTicks;
        }

        // Check own inventory
        if (ticksInState == 40) {
            assessSelf();
        }

        // Determine needs and next action
        if (ticksInState >= 60) {
            determineNextAction();
        }
    }

    private void scanStorage() {
        baseResources.clear();
        storedFoodCount = 0;
        storageHasWeapon = false;
        storageHasArmor = false;

        List<BlockPos> storageBlocks = findStorageContainers();

        for (BlockPos pos : storageBlocks) {
            BlockEntity be = companion.level().getBlockEntity(pos);
            if (be instanceof Container container) {
                for (int i = 0; i < container.getContainerSize(); i++) {
                    ItemStack stack = container.getItem(i);
                    if (!stack.isEmpty()) {
                        recordAvailableStack(stack);
                    }
                }
            }
        }

        targetStorage = storageBlocks.isEmpty() ? null : storageBlocks.get(0);

        // Also check for AE2 ME networks
        List<BlockPos> meAccessPoints = AE2Integration.findMEAccessPoints(
                companion.level(), homePos != null ? homePos : companion.blockPosition(), baseRadius);

        if (!meAccessPoints.isEmpty()) {
            meAccessPoint = meAccessPoints.get(0);

            // Query ME network for available items
            List<ItemStack> meItems = AE2Integration.queryAvailableItems(
                    companion.level(), meAccessPoint,
                    stack -> true  // Get all items
            );

            for (ItemStack stack : meItems) {
                recordAvailableStack(stack);
            }

            report("Encontrei um ponto de acesso da rede ME!");
        } else {
            meAccessPoint = null;
        }

        LLMoblings.LOGGER.debug("Scanned {} storage containers + {} ME access points, found {} item types (food={}, weapon={}, armor={})",
                storageBlocks.size(), meAccessPoints.size(), baseResources.size(), storedFoodCount, storageHasWeapon, storageHasArmor);
    }

    private void recordAvailableStack(ItemStack stack) {
        String name = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        baseResources.merge(name, stack.getCount(), Integer::sum);

        Item item = stack.getItem();
        if (item.getFoodProperties(stack, companion) != null) {
            storedFoodCount += stack.getCount();
        }
        if (item instanceof SwordItem || item instanceof AxeItem) {
            storageHasWeapon = true;
        }
        if (item instanceof ArmorItem) {
            storageHasArmor = true;
        }
    }

    private List<BlockPos> findStorageContainers() {
        List<BlockPos> containers = new ArrayList<>();
        BlockPos center = homePos != null ? homePos : companion.blockPosition();
        Map<String, Integer> storageTypes = new HashMap<>();

        for (int x = -baseRadius; x <= baseRadius; x++) {
            for (int y = -5; y <= 5; y++) {
                for (int z = -baseRadius; z <= baseRadius; z++) {
                    BlockPos pos = center.offset(x, y, z);

                    // Skip blocks outside loaded chunks
                    if (!ChunkLoadingManager.isBlockInLoadedChunks(companion, pos)) {
                        continue;
                    }

                    BlockEntity be = companion.level().getBlockEntity(pos);

                    // Check for various storage types
                    if (be instanceof Container) {
                        String blockName = companion.level().getBlockState(pos).getBlock().getName().getString().toLowerCase();

                        // Vanilla storage
                        if (be instanceof ChestBlockEntity) {
                            containers.add(pos);
                            storageTypes.merge("Baús", 1, Integer::sum);
                        } else if (be instanceof BarrelBlockEntity) {
                            containers.add(pos);
                            storageTypes.merge("Barris", 1, Integer::sum);
                        }
                        // Shulker boxes
                        else if (blockName.contains("shulker")) {
                            containers.add(pos);
                            storageTypes.merge("Caixas de Shulker", 1, Integer::sum);
                        }
                        // Storage Drawers mod
                        else if (blockName.contains("drawer")) {
                            containers.add(pos);
                            storageTypes.merge("Gavetas", 1, Integer::sum);
                        }
                        // Iron Chests / variants
                        else if (blockName.contains("iron_chest") || blockName.contains("gold_chest") ||
                                 blockName.contains("diamond_chest") || blockName.contains("obsidian_chest")) {
                            containers.add(pos);
                            storageTypes.merge("Baús metálicos", 1, Integer::sum);
                        }
                        // Crates
                        else if (blockName.contains("crate")) {
                            containers.add(pos);
                            storageTypes.merge("Caixotes", 1, Integer::sum);
                        }
                        // Sophisticated Storage
                        else if (blockName.contains("sophisticated")) {
                            containers.add(pos);
                            storageTypes.merge("Sophisticated Storage", 1, Integer::sum);
                        }
                        // Generic fallback for any other container
                        else if (((Container) be).getContainerSize() > 0) {
                            containers.add(pos);
                            storageTypes.merge("Outros armazenamentos", 1, Integer::sum);
                        }
                    }
                }
            }
        }

        // Report what storage was found
        if (!storageTypes.isEmpty()) {
            StringBuilder sb = new StringBuilder("Armazenamento encontrado: ");
            storageTypes.forEach((type, count) -> sb.append(count).append(" ").append(type).append(", "));
            String report = sb.toString();
            if (report.endsWith(", ")) {
                report = report.substring(0, report.length() - 2);
            }
            if (!report.equals(lastStorageSummary)) {
                report(report);
                lastStorageSummary = report;
            }
        } else {
            lastStorageSummary = "";
        }

        return containers;
    }

    private void assessSelf() {
        foodCount = 0;
        hasWeapon = false;
        hasArmor = false;

        // Check inventory
        for (int i = 0; i < companion.getContainerSize(); i++) {
            ItemStack stack = companion.getItem(i);
            if (!stack.isEmpty()) {
                Item item = stack.getItem();

                // Food check
                if (item.getFoodProperties(stack, companion) != null) {
                    foodCount += stack.getCount();
                }

                // Weapon check
                if (item instanceof SwordItem || item instanceof AxeItem) {
                    hasWeapon = true;
                }
            }
        }

        // Check equipped armor
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (!companion.getItemBySlot(slot).isEmpty()) {
                hasArmor = true;
                break;
            }
        }

        LLMoblings.LOGGER.debug("Self assessment: food={}, hasWeapon={}, hasArmor={}",
                foodCount, hasWeapon, hasArmor);
    }

    private void determineNextAction() {
        needs.clear();

        boolean canRetrieveFoodFromStorage = storedFoodCount > 0 && targetStorage != null;
        boolean canRetrieveGearFromStorage = targetStorage != null &&
                ((!hasWeapon && storageHasWeapon) || (!hasArmor && storageHasArmor));

        // Priority 1: Equip available gear from inventory
        if (shouldEquip()) {
            changeState(AutonomousState.EQUIPPING);
            return;
        }

        // Priority 2: Get gear from nearby storage or ME network if lacking weapons/armor
        if (!hasWeapon || !hasArmor) {
            needs.add("gear");
            if (canRetrieveGearFromStorage) {
                changeState(AutonomousState.RETRIEVING_STORAGE);
                return;
            }
            if (meAccessPoint != null) {
                changeState(AutonomousState.RETRIEVING_ME);
                return;
            }
            needs.clear();
        }

        // Priority 3: Get food if low
        if (foodCount < 5) {
            needs.add("food");

            // Prefer nearby storage before ME/hunting
            if (canRetrieveFoodFromStorage) {
                changeState(AutonomousState.RETRIEVING_STORAGE);
                return;
            }

            if (meAccessPoint != null) {
                changeState(AutonomousState.RETRIEVING_ME);
                return;
            }

            if (companion.level() instanceof ServerLevel serverLevel) {
                WorldQueries.CropSummary crops = WorldQueries.summarizeCrops(serverLevel, companion, homePos != null ? homePos : companion.blockPosition(), Math.min(baseRadius, 24));
                if (crops.matureCrops() > 0) {
                    changeState(AutonomousState.FARMING);
                    return;
                }
            }

            // Avoid assess->hunt->assess loops when no animals are available.
            if (totalTicks >= nextHuntAllowedTick) {
                changeState(AutonomousState.HUNTING);
            } else {
                changeState(AutonomousState.EXPLORING);
            }
            return;
        }

        // Priority 4: Take care of nearby mature crops before generic wandering.
        if (companion.level() instanceof ServerLevel serverLevel) {
            WorldQueries.CropSummary crops = WorldQueries.summarizeCrops(serverLevel, companion, homePos != null ? homePos : companion.blockPosition(), Math.min(baseRadius, 24));
            if (crops.matureCrops() > 0 && !WorldQueries.summarizeThreats(companion, 10).dangerous()) {
                changeState(AutonomousState.FARMING);
                return;
            }
        }

        // Priority 4: Store excess items
        if (hasExcessItems() && targetStorage != null) {
            changeState(AutonomousState.STORING);
            return;
        }

        // Reset hunt failure pressure once needs are satisfied
        consecutiveHuntFailures = 0;
        nextHuntAllowedTick = 0;

        // Priority 5: Patrol the area (hunt hostile mobs)
        // Patrol even without weapon - can still punch mobs!
        // With weapon: 70% patrol, 30% explore
        // Without weapon: 40% patrol, 40% explore, 20% rest
        int roll = companion.getRandom().nextInt(10);
        if (hasWeapon) {
            if (roll < 7) {
                changeState(AutonomousState.PATROLLING);
            } else {
                changeState(AutonomousState.EXPLORING);
            }
            return;
        } else {
            // Still patrol sometimes even without weapon
            if (roll < 4) {
                changeState(AutonomousState.PATROLLING);
                return;
            } else if (roll < 8) {
                changeState(AutonomousState.EXPLORING);
                return;
            }
        }

        // Default: Rest (but still look for threats while resting)
        changeState(AutonomousState.RESTING);
    }

    private boolean storageHasUsefulItems() {
        return storedFoodCount > 0 || storageHasWeapon || storageHasArmor;
    }

    private void handleFoodSearchFailure(String message) {
        consecutiveHuntFailures++;
        int cooldown = Math.min(200 + consecutiveHuntFailures * 100, 600);
        nextHuntAllowedTick = totalTicks + cooldown;
        report(message);

        // After repeated failures, move around before trying again.
        if (consecutiveHuntFailures >= 2) {
            report("Vou explorar a área antes de tentar caçar de novo.");
            changeState(AutonomousState.EXPLORING);
        } else {
            changeState(AutonomousState.PATROLLING);
        }
    }

    private boolean tryGetFoodFromStorage(Container container) {
        int totalMoved = 0;

        for (int i = 0; i < container.getContainerSize() && totalMoved < 16; i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty() || stack.getItem().getFoodProperties(stack, companion) == null) {
                continue;
            }

            int desired = Math.min(16 - totalMoved, stack.getCount());
            ItemStack toMove = stack.copy();
            toMove.setCount(desired);

            ItemStack remaining = companion.addToInventory(toMove);
            int moved = desired - remaining.getCount();
            if (moved > 0) {
                stack.shrink(moved);
                if (stack.isEmpty()) {
                    container.setItem(i, ItemStack.EMPTY);
                }
                totalMoved += moved;
            }
        }

        if (totalMoved > 0) {
            container.setChanged();
            report("Peguei " + totalMoved + " de comida do armazenamento.");
            return true;
        }

        return false;
    }

    private boolean tryGetGearFromStorage(Container container) {
        boolean gotGear = false;

        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) continue;

            Item item = stack.getItem();

            if (!hasWeapon && (item instanceof SwordItem || item instanceof AxeItem) && companion.getMainHandItem().isEmpty()) {
                ItemStack equipped = stack.copy();
                equipped.setCount(1);
                companion.setItemSlot(EquipmentSlot.MAINHAND, equipped);
                stack.shrink(1);
                if (stack.isEmpty()) {
                    container.setItem(i, ItemStack.EMPTY);
                }
                container.setChanged();
                report("Peguei " + item.getDescription().getString() + " do armazenamento.");
                hasWeapon = true;
                gotGear = true;
                continue;
            }

            if (item instanceof ArmorItem armorItem) {
                EquipmentSlot slot = armorItem.getEquipmentSlot();
                if (companion.getItemBySlot(slot).isEmpty()) {
                    ItemStack equipped = stack.copy();
                    equipped.setCount(1);
                    companion.setItemSlot(slot, equipped);
                    stack.shrink(1);
                    if (stack.isEmpty()) {
                        container.setItem(i, ItemStack.EMPTY);
                    }
                    container.setChanged();
                    report("Peguei " + item.getDescription().getString() + " do armazenamento.");
                    hasArmor = true;
                    gotGear = true;
                }
            }
        }

        return gotGear;
    }

    private boolean tryGetFoodFromME() {
        if (meAccessPoint == null) return false;

        // Move to ME access point if not close
        double distance = companion.position().distanceTo(Vec3.atCenterOf(meAccessPoint));
        if (distance > 5.0) {
            companion.getNavigation().moveTo(
                    meAccessPoint.getX() + 0.5,
                    meAccessPoint.getY(),
                    meAccessPoint.getZ() + 0.5,
                    1.0
            );
            return false;  // Will try again next tick
        }

        // Extract food from ME network
        List<ItemStack> food = AE2Integration.extractItems(
                companion.level(),
                meAccessPoint,
                stack -> stack.getItem().getFoodProperties(stack, companion) != null,
                16  // Get up to 16 food items
        );

        LLMoblings.LOGGER.info("AE2 food extraction returned {} stacks", food.size());

        if (!food.isEmpty()) {
            int totalFood = 0;
            for (ItemStack stack : food) {
                LLMoblings.LOGGER.info("Adding {} x {} to inventory", stack.getCount(), stack.getItem());
                ItemStack remaining = companion.addToInventory(stack);
                int added = stack.getCount() - remaining.getCount();
                totalFood += added;
                LLMoblings.LOGGER.info("Added {} items, {} remaining", added, remaining.getCount());
            }
            if (totalFood > 0) {
                report("Recuperei " + totalFood + " de comida da rede ME.");
                return true;
            }
        }

        return false;
    }

    /**
     * Try to retrieve weapons and armor from ME network.
     * @return true if gear was successfully retrieved
     */
    private boolean tryGetGearFromME() {
        if (meAccessPoint == null) return false;

        // Move to ME access point if not close
        double distance = companion.position().distanceTo(Vec3.atCenterOf(meAccessPoint));
        if (distance > 5.0) {
            companion.getNavigation().moveTo(
                    meAccessPoint.getX() + 0.5,
                    meAccessPoint.getY(),
                    meAccessPoint.getZ() + 0.5,
                    1.0
            );
            return false;  // Will try again next tick
        }

        boolean gotGear = false;

        // Try to get a weapon if we don't have one
        if (!hasWeapon) {
            List<ItemStack> weapons = AE2Integration.extractItems(
                    companion.level(),
                    meAccessPoint,
                    stack -> stack.getItem() instanceof SwordItem || stack.getItem() instanceof AxeItem,
                    1  // Just get one weapon
            );

            if (!weapons.isEmpty()) {
                for (ItemStack stack : weapons) {
                    ItemStack remaining = companion.addToInventory(stack);
                    if (remaining.getCount() < stack.getCount()) {
                        report("Recuperei " + stack.getItem().getDescription().getString() + " da rede ME!");
                        gotGear = true;
                        break;
                    }
                }
            }
        }

        // Try to get armor pieces we're missing
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (companion.getItemBySlot(slot).isEmpty()) {
                final EquipmentSlot targetSlot = slot;
                List<ItemStack> armor = AE2Integration.extractItems(
                        companion.level(),
                        meAccessPoint,
                        stack -> {
                            if (stack.getItem() instanceof ArmorItem armorItem) {
                                return armorItem.getEquipmentSlot() == targetSlot;
                            }
                            return false;
                        },
                        1
                );

                if (!armor.isEmpty()) {
                    for (ItemStack stack : armor) {
                        ItemStack remaining = companion.addToInventory(stack);
                        if (remaining.getCount() < stack.getCount()) {
                            report("Recuperei " + stack.getItem().getDescription().getString() + " da rede ME!");
                            gotGear = true;
                            break;
                        }
                    }
                }
            }
        }

        return gotGear;
    }

    private boolean shouldEquip() {
        // Check inventory for unequipped armor/weapons
        for (int i = 0; i < companion.getContainerSize(); i++) {
            ItemStack stack = companion.getItem(i);
            if (stack.isEmpty()) continue;

            Item item = stack.getItem();

            // Check for better weapon
            if ((item instanceof SwordItem || item instanceof AxeItem) &&
                    companion.getMainHandItem().isEmpty()) {
                return true;
            }

            // Check for armor
            if (item instanceof ArmorItem armorItem) {
                EquipmentSlot slot = armorItem.getEquipmentSlot();
                if (companion.getItemBySlot(slot).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void tickHunting() {
        if (ticksInState == 1) {
            report("Caçando comida...");
            huntTarget = findHuntTarget();
            lastPosition = companion.position();
            stuckTicks = 0;

            if (huntTarget != null) {
                String targetName = BuiltInRegistries.ENTITY_TYPE.getKey(huntTarget.getType()).getPath()
                        .replace("_", " ");
                report("Encontrei " + targetName + "! Vou atrás.");
            }
        }

        if (huntTarget == null || !huntTarget.isAlive()) {
            huntTarget = findHuntTarget();
            if (huntTarget == null) {
                handleFoodSearchFailure("Não há animais por perto para caçar. Vou procurar em outro lugar ou tentar outro recurso.");
                return;
            } else {
                String targetName = BuiltInRegistries.ENTITY_TYPE.getKey(huntTarget.getType()).getPath()
                        .replace("_", " ");
                report("Avistei " + targetName + "!");
            }
        }

        // Check if stuck (hasn't moved much in 3 seconds)
        if (ticksInState % 60 == 0) {
            Vec3 currentPos = companion.position();
            if (lastPosition != null && currentPos.distanceTo(lastPosition) < 1.0) {
                stuckTicks += 60;
                if (stuckTicks > 120) {  // Stuck for 6+ seconds
                    report("Não consigo alcançar esse animal. Vou procurar outro...");
                    huntTarget = null;  // Give up on this target
                    stuckTicks = 0;

                    // Try to unstick by jumping or recalculating
                    if (companion.onGround()) {
                        companion.setDeltaMovement(companion.getDeltaMovement().add(0, 0.4, 0));
                    }
                    companion.getNavigation().stop();
                    return;
                }
            } else {
                stuckTicks = 0;  // Reset if we moved
            }
            lastPosition = currentPos;
        }

        double distance = companion.distanceTo(huntTarget);

        if (distance < 2.5) {
            // Attack
            companion.doHurtTarget(huntTarget);
            companion.swing(companion.getUsedItemHand());
            stuckTicks = 0;  // We're attacking, not stuck

            if (!huntTarget.isAlive()) {
                report("Consegui pegar um!");
                huntTarget = null;
                consecutiveHuntFailures = 0;
                nextHuntAllowedTick = 0;
                // Check if we have enough food now
                assessSelf();
                if (foodCount >= 10) {
                    changeState(AutonomousState.ASSESSING);
                }
            }
        } else {
            // Chase - recalculate path periodically
            if (ticksInState % 20 == 0 || companion.getNavigation().isDone()) {
                companion.getNavigation().moveTo(huntTarget, 1.2);
            }
        }

        // Timeout
        if (ticksInState > 600) {
            handleFoodSearchFailure("A caça está demorando demais. Vou tentar outra abordagem.");
        }
    }

    private net.minecraft.world.entity.Entity findHuntTarget() {
        AABB searchBox = companion.getBoundingBox().inflate(baseRadius);

        // Search for all living entities, then filter and score them
        List<LivingEntity> candidates = companion.level().getEntitiesOfClass(LivingEntity.class, searchBox,
                entity -> {
                    if (!entity.isAlive()) return false;
                    if (entity == companion) return false;
                    if (entity == companion.getOwner()) return false;

                    // Get entity info for filtering
                    String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().toLowerCase();
                    String className = entity.getClass().getName().toLowerCase();

                    // === ALWAYS EXCLUDE ===

                    // Cobblemon Pokemon - never hunt
                    if (className.contains("cobblemon") || className.contains("pokemon") ||
                        entityId.contains("cobblemon")) {
                        return false;
                    }

                    // Players and villagers
                    if (entity instanceof net.minecraft.world.entity.player.Player) return false;
                    if (entityId.contains("villager") || entityId.contains("wandering_trader")) return false;

                    // Pets and tameable animals that might be tamed
                    if (entity instanceof Wolf wolf && wolf.isTame()) return false;
                    if (entity instanceof Cat cat && cat.isTame()) return false;
                    if (entity instanceof Parrot) return false;  // Parrots are always pets
                    if (entity instanceof Fox) return false;  // Foxes are cute, don't hunt

                    // Horses/mounts - don't hunt mounts
                    if (entity instanceof AbstractHorse) return false;
                    if (entityId.contains("horse") || entityId.contains("donkey") ||
                        entityId.contains("mule") || entityId.contains("llama")) return false;

                    // Alex's Mobs pets/mounts
                    if (entityId.contains("elephant") || entityId.contains("gorilla") ||
                        entityId.contains("capuchin") || entityId.contains("crow") ||
                        entityId.contains("roadrunner")) return false;

                    // Bees - important for farms
                    if (entity instanceof Bee) return false;

                    // Iron golems and snow golems
                    if (entityId.contains("golem")) return false;

                    // Axolotls and dolphins - cute/friendly
                    if (entityId.contains("axolotl") || entityId.contains("dolphin")) return false;

                    // Allays
                    if (entityId.contains("allay")) return false;

                    // === CHECK IF FARM ANIMAL ===
                    if (isFarmAnimal(entity)) {
                        return false;
                    }

                    // === CHECK IF HUNTABLE ===
                    return isHuntableForFood(entity, entityId, className);
                }
        );

        if (candidates.isEmpty()) {
            LLMoblings.LOGGER.debug("No hunt targets found in {} block radius", baseRadius);
            return null;
        }

        // Score and sort candidates - prefer high-value targets that are close
        candidates.sort((a, b) -> {
            double scoreA = getHuntScore(a) / (1 + companion.distanceTo(a) * 0.1);
            double scoreB = getHuntScore(b) / (1 + companion.distanceTo(b) * 0.1);
            return Double.compare(scoreB, scoreA);  // Higher score first
        });

        LivingEntity chosen = candidates.get(0);
        String chosenId = BuiltInRegistries.ENTITY_TYPE.getKey(chosen.getType()).getPath();
        LLMoblings.LOGGER.info("[{}] Hunting target selected: {} (score: {}, distance: {})",
                companion.getCompanionName(), chosenId, getHuntScore(chosen), (int) companion.distanceTo(chosen));

        return chosen;
    }

    /**
     * Check if an animal appears to be a farm animal (owned/penned).
     */
    private boolean isFarmAnimal(LivingEntity entity) {
        // Named animals are usually farm animals or pets
        if (entity.hasCustomName()) {
            LLMoblings.LOGGER.debug("Skipping {} - has custom name: {}",
                    entity.getType().getDescriptionId(), entity.getCustomName().getString());
            return true;
        }

        // Leashed animals are farm animals
        if (entity instanceof net.minecraft.world.entity.Mob mob && mob.isLeashed()) {
            LLMoblings.LOGGER.debug("Skipping {} - is leashed", entity.getType().getDescriptionId());
            return true;
        }

        // Check if animal is in a fenced/enclosed area
        if (isInEnclosure(entity)) {
            LLMoblings.LOGGER.debug("Skipping {} - appears to be in an enclosure", entity.getType().getDescriptionId());
            return true;
        }

        return false;
    }

    /**
     * Check if an entity is inside a fenced enclosure or near farm structures.
     */
    private boolean isInEnclosure(LivingEntity entity) {
        BlockPos pos = entity.blockPosition();
        int fenceCount = 0;
        int farmBlockCount = 0;

        // Check surrounding blocks in a 5 block radius
        for (int x = -5; x <= 5; x++) {
            for (int z = -5; z <= 5; z++) {
                for (int y = -1; y <= 2; y++) {
                    BlockPos checkPos = pos.offset(x, y, z);

                    // Skip blocks outside loaded chunks
                    if (!ChunkLoadingManager.isBlockInLoadedChunks(companion, checkPos)) {
                        continue;
                    }

                    String blockName = companion.level().getBlockState(checkPos).getBlock().getName().getString().toLowerCase();

                    // Count fences and walls
                    if (blockName.contains("fence") || blockName.contains("wall") ||
                        blockName.contains("gate")) {
                        fenceCount++;
                    }

                    // Count farm-related blocks
                    if (blockName.contains("hay") || blockName.contains("trough") ||
                        blockName.contains("feeder") || blockName.contains("barn") ||
                        blockName.contains("stable") || blockName.contains("coop") ||
                        blockName.contains("pen")) {
                        farmBlockCount++;
                    }
                }
            }
        }

        // If surrounded by fences (at least 8 fence blocks nearby) or near farm structures
        if (fenceCount >= 8) {
            return true;
        }

        // If near farm-related blocks
        if (farmBlockCount >= 2) {
            return true;
        }

        // Additional check: is the animal on a non-natural block? (cobblestone, planks, etc.)
        BlockPos below = pos.below();
        String groundBlock = companion.level().getBlockState(below).getBlock().getName().getString().toLowerCase();
        if (groundBlock.contains("plank") || groundBlock.contains("cobblestone") ||
            groundBlock.contains("stone_brick") || groundBlock.contains("hay")) {
            // Standing on player-made flooring - likely a farm
            return true;
        }

        return false;
    }

    /**
     * Check if an entity can be hunted for food.
     */
    private boolean isHuntableForFood(LivingEntity entity, String entityId, String className) {
        // === VANILLA FOOD ANIMALS ===
        if (entity instanceof Cow) return true;      // Beef + leather
        if (entity instanceof Pig) return true;      // Porkchop
        if (entity instanceof Sheep) return true;    // Mutton + wool
        if (entity instanceof Chicken) return true;  // Chicken + feathers
        if (entity instanceof Rabbit) return true;   // Rabbit meat

        // Fish (use entity ID since fish classes may vary)
        if (entityId.contains("cod") || entityId.contains("salmon") ||
            entityId.contains("tropical_fish") || entityId.contains("pufferfish")) return true;

        // Squid
        if (entityId.contains("squid") || entityId.contains("glow_squid")) return true;

        // Turtle
        if (entityId.contains("turtle")) return true;

        // Mooshrooms - special cows
        if (entityId.contains("mooshroom")) return true;

        // Goats can be hunted but usually for horns
        if (entityId.contains("goat")) return true;

        // === MODDED ANIMALS - Alex's Mobs ===
        // High value meat animals
        if (entityId.contains("bison")) return true;
        if (entityId.contains("moose")) return true;
        if (entityId.contains("gazelle")) return true;
        if (entityId.contains("kangaroo")) return true;
        if (entityId.contains("capybara")) return true;
        if (entityId.contains("mungus")) return true;
        if (entityId.contains("catfish")) return true;
        if (entityId.contains("flying_fish")) return true;
        if (entityId.contains("giant_squid")) return true;
        if (entityId.contains("hammerhead_shark")) return true;
        if (entityId.contains("lobster")) return true;
        if (entityId.contains("orca")) return true;

        // Alex's Caves huntable
        if (entityId.contains("tremorsaurus")) return true;
        if (entityId.contains("grottoceratops")) return true;
        if (entityId.contains("vallumraptor")) return true;

        // === FARMER'S DELIGHT ===
        if (entityId.contains("farmersdelight")) return true;  // Any FD animals

        // === OTHER MODDED ===
        // Ice and Fire dragons are too dangerous, but some animals
        if (entityId.contains("iceandfire") &&
            (entityId.contains("hippogryph_egg") || entityId.contains("amphithere"))) {
            return false;  // Don't hunt these
        }

        // Generic check - if it's in the Animal class and not explicitly excluded
        if (entity instanceof Animal) {
            // Additional exclusions for modded
            if (entityId.contains("familiar") || entityId.contains("pet") ||
                entityId.contains("tamed") || entityId.contains("companion")) {
                return false;
            }
            return true;
        }

        return false;
    }

    /**
     * Score a hunt target - higher means more valuable.
     */
    private int getHuntScore(LivingEntity entity) {
        String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().toLowerCase();

        // High value - lots of food
        if (entity instanceof Cow) return 100;
        if (entityId.contains("mooshroom")) return 100;
        if (entityId.contains("bison") || entityId.contains("moose")) return 100;

        // Medium-high value
        if (entity instanceof Pig) return 80;
        if (entity instanceof Sheep) return 70;
        if (entityId.contains("gazelle") || entityId.contains("kangaroo")) return 75;

        // Medium value
        if (entity instanceof Chicken) return 50;
        if (entity instanceof Rabbit) return 40;
        if (entityId.contains("capybara")) return 60;

        // Fish - easy but low value
        if (entityId.contains("cod") || entityId.contains("salmon")) return 30;
        if (entityId.contains("tropical_fish")) return 20;

        // Low value or risky
        if (entityId.contains("goat")) return 25;  // Can ram you
        if (entityId.contains("pufferfish")) return 10;  // Poisonous

        // Default for other animals
        if (entity instanceof Animal) return 35;

        return 20;
    }

    private void tickGathering() {
        // Gathering is not fully implemented yet. Explore a bit instead of
        // stalling in place, then reassess.
        if (ticksInState == 1) {
            report("Procurando recursos próximos...");
        }
        if (ticksInState > 40) {
            changeState(AutonomousState.EXPLORING);
        }
    }

    private void tickFarming() {
        if (farmingTask == null) {
            farmingTask = new FarmingTask(companion, Math.min(baseRadius, 24));
            report("Indo cuidar da fazenda...");
        }

        farmingTask.tick();

        if (farmingTask.isFailed()) {
            report(farmingTask.getFailReason());
            farmingTask = null;
            changeState(AutonomousState.ASSESSING);
            return;
        }

        if (farmingTask.isCompleted()) {
            report("Terminei a rodada na fazenda. Colhi " + farmingTask.getHarvestedCount() + " colheitas maduras.");
            farmingTask = null;
            changeState(AutonomousState.ASSESSING);
            return;
        }

        if (ticksInState % 100 == 0) {
            report(farmingTask.getProgressReport());
        }
    }

    private void tickEquipping() {
        if (ticksInState == 1) {
            report("Equipando equipamento...");
        }

        // Find and equip items
        for (int i = 0; i < companion.getContainerSize(); i++) {
            ItemStack stack = companion.getItem(i);
            if (stack.isEmpty()) continue;

            Item item = stack.getItem();

            // Equip weapon to mainhand
            if ((item instanceof SwordItem || item instanceof AxeItem) &&
                    companion.getMainHandItem().isEmpty()) {
                companion.setItemSlot(EquipmentSlot.MAINHAND, stack.copy());
                companion.setItem(i, ItemStack.EMPTY);
                report("Equipei " + item.getDescription().getString());
                continue;
            }

            // Equip armor
            if (item instanceof ArmorItem armorItem) {
                EquipmentSlot slot = armorItem.getEquipmentSlot();
                if (companion.getItemBySlot(slot).isEmpty()) {
                    companion.setItemSlot(slot, stack.copy());
                    companion.setItem(i, ItemStack.EMPTY);
                    report("Equipei " + item.getDescription().getString());
                }
            }
        }

        if (ticksInState >= 20) {
            assessSelf();
            changeState(AutonomousState.ASSESSING);
        }
    }

    private void tickStoring() {
        if (targetStorage == null) {
            changeState(AutonomousState.ASSESSING);
            return;
        }

        double distance = companion.position().distanceTo(Vec3.atCenterOf(targetStorage));

        if (distance > 3.0) {
            companion.getNavigation().moveTo(
                    targetStorage.getX() + 0.5,
                    targetStorage.getY(),
                    targetStorage.getZ() + 0.5,
                    1.0
            );
        } else {
            // Deposit items
            BlockEntity be = companion.level().getBlockEntity(targetStorage);
            if (be instanceof Container container) {
                depositItems(container);
            }
            changeState(AutonomousState.ASSESSING);
        }

        if (ticksInState > 200) {
            changeState(AutonomousState.ASSESSING);
        }
    }

    private void depositItems(Container container) {
        int deposited = 0;

        for (int i = 0; i < companion.getContainerSize(); i++) {
            ItemStack stack = companion.getItem(i);
            if (stack.isEmpty()) continue;

            // Keep food, weapons, armor
            Item item = stack.getItem();
            if (item.getFoodProperties(stack, companion) != null || item instanceof SwordItem ||
                    item instanceof AxeItem || item instanceof ArmorItem) {
                continue;
            }

            // Try to deposit
            for (int j = 0; j < container.getContainerSize(); j++) {
                ItemStack containerStack = container.getItem(j);
                if (containerStack.isEmpty()) {
                    container.setItem(j, stack.copy());
                    companion.setItem(i, ItemStack.EMPTY);
                    deposited++;
                    break;
                } else if (ItemStack.isSameItemSameComponents(containerStack, stack) &&
                        containerStack.getCount() < containerStack.getMaxStackSize()) {
                    int space = containerStack.getMaxStackSize() - containerStack.getCount();
                    int toAdd = Math.min(space, stack.getCount());
                    containerStack.grow(toAdd);
                    stack.shrink(toAdd);
                    if (stack.isEmpty()) {
                        companion.setItem(i, ItemStack.EMPTY);
                        deposited++;
                        break;
                    }
                }
            }
        }

        if (deposited > 0) {
            report("Guardei " + deposited + " itens.");
        }
    }

    private void tickRetrievingStorage() {
        if (targetStorage == null) {
            changeState(AutonomousState.ASSESSING);
            return;
        }

        boolean needsGear = needs.contains("gear");
        boolean needsFood = needs.contains("food");

        if (ticksInState == 1) {
            if (needsGear && needsFood) {
                report("Indo ao armazenamento buscar comida e equipamento...");
            } else if (needsGear) {
                report("Indo ao armazenamento buscar equipamento...");
            } else {
                report("Indo ao armazenamento buscar comida...");
            }
            lastPosition = companion.position();
            stuckTicks = 0;
        }

        double distance = companion.position().distanceTo(Vec3.atCenterOf(targetStorage));
        if (distance > 3.0) {
            if (ticksInState % 20 == 0 || companion.getNavigation().isDone()) {
                companion.getNavigation().moveTo(
                        targetStorage.getX() + 0.5,
                        targetStorage.getY(),
                        targetStorage.getZ() + 0.5,
                        1.0
                );
            }
            if (ticksInState > 300) {
                report("Estou demorando demais para chegar ao armazenamento...");
                changeState(AutonomousState.ASSESSING);
            }
            return;
        }

        BlockEntity be = companion.level().getBlockEntity(targetStorage);
        if (!(be instanceof Container container)) {
            report("Perdi o acesso ao armazenamento.");
            targetStorage = null;
            changeState(AutonomousState.ASSESSING);
            return;
        }

        boolean gotSomething = false;
        if (needsGear) {
            gotSomething |= tryGetGearFromStorage(container);
        }
        if (needsFood) {
            gotSomething |= tryGetFoodFromStorage(container);
        }

        if (gotSomething) {
            assessSelf();
            changeState(AutonomousState.ASSESSING);
            return;
        }

        // Nothing useful was found. Force a rescan soon and choose a sensible fallback.
        lastStorageScanTick = -1200;
        scanStorage();

        if (needsFood) {
            if (meAccessPoint != null) {
                report("Não achei comida útil no armazenamento. Vou tentar a rede ME.");
                changeState(AutonomousState.RETRIEVING_ME);
            } else if (totalTicks >= nextHuntAllowedTick) {
                report("Não achei comida útil no armazenamento. Vou tentar caçar.");
                changeState(AutonomousState.HUNTING);
            } else {
                report("Não achei comida útil no armazenamento. Vou explorar um pouco antes de tentar de novo.");
                changeState(AutonomousState.EXPLORING);
            }
            return;
        }

        if (needsGear) {
            if (meAccessPoint != null) {
                report("Não achei equipamento útil no armazenamento. Vou tentar a rede ME.");
                changeState(AutonomousState.RETRIEVING_ME);
            } else {
                report("Não achei equipamento útil no armazenamento.");
                changeState(AutonomousState.PATROLLING);
            }
            return;
        }

        changeState(AutonomousState.ASSESSING);
    }

    private void tickRetrievingME() {
        if (meAccessPoint == null) {
            report("Perdi o rastro do terminal ME...");
            if (needs.contains("food") && targetStorage != null && storedFoodCount > 0) {
                changeState(AutonomousState.RETRIEVING_STORAGE);
            } else if (needs.contains("food")) {
                changeState(AutonomousState.EXPLORING);
            } else {
                changeState(AutonomousState.ASSESSING);
            }
            return;
        }

        boolean needsGear = needs.contains("gear");
        boolean needsFood = needs.contains("food");

        if (ticksInState == 1) {
            if (needsGear) {
                report("Indo ao terminal ME buscar equipamento...");
            } else {
                report("Indo ao terminal ME buscar comida...");
            }
            lastPosition = companion.position();
            stuckTicks = 0;
        }

        double distance = companion.position().distanceTo(Vec3.atCenterOf(meAccessPoint));

        // Check if stuck
        if (ticksInState % 60 == 0 && ticksInState > 60) {
            Vec3 currentPos = companion.position();
            if (lastPosition != null && currentPos.distanceTo(lastPosition) < 1.0) {
                stuckTicks += 60;
                if (stuckTicks > 180) {  // Stuck for 9+ seconds
                    report("Não consigo alcançar o terminal ME...");
                    stuckTicks = 0;
                    if (needsFood) {
                        if (targetStorage != null && storedFoodCount > 0) {
                            changeState(AutonomousState.RETRIEVING_STORAGE);
                        } else {
                            changeState(AutonomousState.EXPLORING);
                        }
                    } else {
                        changeState(AutonomousState.PATROLLING);
                    }
                    return;
                }
            } else {
                stuckTicks = 0;
            }
            lastPosition = currentPos;
        }

        if (distance > 3.0) {
            // Navigate to ME point
            if (ticksInState % 20 == 0 || companion.getNavigation().isDone()) {
                companion.getNavigation().moveTo(
                        meAccessPoint.getX() + 0.5,
                        meAccessPoint.getY(),
                        meAccessPoint.getZ() + 0.5,
                        1.0
                );
            }
        } else {
            // Close enough - try to get what we need
            boolean gotSomething = false;

            // Try to get gear first if we need it
            if (needsGear) {
                gotSomething = tryGetGearFromME();
                if (gotSomething) {
                    assessSelf();
                    // Check if we still need more gear
                    if (hasWeapon && hasArmor) {
                        needs.remove("gear");
                    }
                }
            }

            // Try to get food if we need it
            if (needsFood && tryGetFoodFromME()) {
                gotSomething = true;
                needs.remove("food");
            }

            // Reassess after getting items
            if (gotSomething) {
                assessSelf();
                changeState(AutonomousState.ASSESSING);
                return;
            }

            // ME didn't have what we needed
            if (needsFood) {
                if (targetStorage != null && storedFoodCount > 0) {
                    report("Não consegui comida na rede ME. Vou tentar o armazenamento.");
                    changeState(AutonomousState.RETRIEVING_STORAGE);
                } else if (totalTicks >= nextHuntAllowedTick) {
                    report("Não consegui comida na rede ME. Vou caçar...");
                    changeState(AutonomousState.HUNTING);
                } else {
                    report("Não consegui comida na rede ME. Vou explorar um pouco antes de tentar de novo.");
                    changeState(AutonomousState.EXPLORING);
                }
            } else if (needsGear) {
                if (targetStorage != null && storageHasUsefulItems()) {
                    report("Não há equipamento útil disponível na rede ME. Vou verificar o armazenamento.");
                    changeState(AutonomousState.RETRIEVING_STORAGE);
                } else {
                    report("Não há equipamento disponível na rede ME. Vou patrulhar mesmo assim...");
                    changeState(AutonomousState.PATROLLING);
                }
            } else {
                changeState(AutonomousState.ASSESSING);
            }
        }

        // Timeout
        if (ticksInState > 400) {
            report("Estou demorando demais para chegar ao terminal ME...");
            if (needsFood) {
                if (targetStorage != null && storedFoodCount > 0) {
                    changeState(AutonomousState.RETRIEVING_STORAGE);
                } else {
                    changeState(AutonomousState.EXPLORING);
                }
            } else {
                changeState(AutonomousState.ASSESSING);
            }
        }
    }

    private void tickExploring() {
        if (ticksInState == 1) {
            report("Explorando a base...");
        }

        // Choose a new exploration target if we don't have one or reached it
        if (exploreTarget == null || companion.position().distanceTo(Vec3.atCenterOf(exploreTarget)) < 2.0) {
            exploreTarget = findExplorationTarget();
            if (exploreTarget != null) {
                visitedLocations.add(exploreTarget);
                // Keep visited list manageable
                if (visitedLocations.size() > 50) {
                    visitedLocations.remove(0);
                }
            }
        }

        // Navigate to exploration target
        if (exploreTarget != null && companion.getNavigation().isDone()) {
            companion.getNavigation().moveTo(
                    exploreTarget.getX() + 0.5,
                    exploreTarget.getY(),
                    exploreTarget.getZ() + 0.5,
                    0.7  // Walk speed for exploring
            );
        }

        // Look around occasionally
        if (ticksInState % 40 == 0) {
            companion.setYRot(companion.getYRot() + (companion.getRandom().nextFloat() - 0.5F) * 60);
        }

        // Comment on interesting things found
        if (ticksInState % 200 == 0 && companion.getRandom().nextInt(3) == 0) {
            String[] explorationComments = {
                "Layout interessante por aqui...",
                "*olha em volta com curiosidade*",
                "Eu deveria lembrar deste lugar.",
                "Você montou um lugar legal aqui!",
                "O que tem por aqui?",
                "*observa os arredores*"
            };
            report(explorationComments[companion.getRandom().nextInt(explorationComments.length)]);
        }

        // Transition to other states periodically
        if (ticksInState > 400) {
            changeState(AutonomousState.ASSESSING);
        }
    }

    private BlockPos findExplorationTarget() {
        // Try to find interesting locations: doors, containers, unexplored areas
        BlockPos currentPos = companion.blockPosition();
        BlockPos bestTarget = null;
        double bestScore = -1;

        for (int attempt = 0; attempt < 10; attempt++) {
            // Random position within base radius
            int offsetX = companion.getRandom().nextInt(baseRadius * 2) - baseRadius;
            int offsetZ = companion.getRandom().nextInt(baseRadius * 2) - baseRadius;
            BlockPos candidate = currentPos.offset(offsetX, 0, offsetZ);

            // Skip positions outside loaded chunks
            if (!ChunkLoadingManager.isBlockInLoadedChunks(companion, candidate)) {
                continue;
            }

            // Find ground level
            BlockPos groundCandidate = candidate;
            for (int y = 5; y >= -5; y--) {
                BlockPos check = candidate.offset(0, y, 0);
                if (companion.level().getBlockState(check).isAir() &&
                    !companion.level().getBlockState(check.below()).isAir()) {
                    groundCandidate = check;
                    break;
                }
            }
            final BlockPos finalCandidate = groundCandidate;

            // Score this location
            double score = 0;

            // Prefer unvisited locations
            boolean visited = visitedLocations.stream()
                    .anyMatch(pos -> pos.distSqr(finalCandidate) < 25);  // Within 5 blocks
            if (!visited) {
                score += 50;
            }

            // Prefer locations with interesting blocks nearby (chests, doors, etc.)
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos nearby = finalCandidate.offset(dx, 0, dz);
                    String blockName = companion.level().getBlockState(nearby).getBlock().getName().getString().toLowerCase();
                    if (blockName.contains("door") || blockName.contains("chest") ||
                        blockName.contains("furnace") || blockName.contains("crafting") ||
                        blockName.contains("enchant") || blockName.contains("anvil")) {
                        score += 10;
                    }
                }
            }

            // Prefer moderate distance (not too close, not too far)
            double distance = finalCandidate.distSqr(currentPos);
            if (distance > 9 && distance < 400) {  // 3-20 blocks
                score += 20;
            }

            if (score > bestScore) {
                bestScore = score;
                bestTarget = finalCandidate;
            }
        }

        return bestTarget;
    }

    private void tickPatrolling() {
        if (ticksInState == 1) {
            report("Patrulhando a área...");
        }

        // Check for threats
        AABB patrolBox = companion.getBoundingBox().inflate(baseRadius);
        List<Monster> threats = companion.level().getEntitiesOfClass(Monster.class, patrolBox,
                Monster::isAlive);

        if (!threats.isEmpty()) {
            Monster nearest = threats.stream()
                    .min(Comparator.comparingDouble(m -> companion.distanceTo(m)))
                    .orElse(null);

            if (nearest != null) {
                double distance = companion.distanceTo(nearest);

                if (distance < 2.5) {
                    companion.doHurtTarget(nearest);
                    companion.swing(companion.getUsedItemHand());
                } else {
                    companion.getNavigation().moveTo(nearest, 1.2);
                }

                if (!nearest.isAlive() && reportCooldown <= 0) {
                    report("Ameaça eliminada!");
                    reportCooldown = 100;
                }
                return;
            }
        }

        // Wander around home (within loaded chunks)
        if (companion.getNavigation().isDone() && ticksInState % 100 == 0) {
            BlockPos wanderTarget = null;
            for (int i = 0; i < 5; i++) {  // Try up to 5 times to find a valid target
                BlockPos candidate = homePos.offset(
                        companion.getRandom().nextInt(baseRadius * 2) - baseRadius,
                        0,
                        companion.getRandom().nextInt(baseRadius * 2) - baseRadius
                );
                if (ChunkLoadingManager.isBlockInLoadedChunks(companion, candidate)) {
                    wanderTarget = candidate;
                    break;
                }
            }
            if (wanderTarget != null) {
                companion.getNavigation().moveTo(wanderTarget.getX(), wanderTarget.getY(), wanderTarget.getZ(), 0.8);
            }
        }

        // Periodically reassess
        if (ticksInState > 600) {
            changeState(AutonomousState.ASSESSING);
        }
    }

    private void tickResting() {
        if (ticksInState == 1) {
            report("Fazendo uma pausa perto da base.");
        }

        // Stay alert for threats even while resting!
        if (ticksInState % 40 == 0) {
            AABB searchBox = companion.getBoundingBox().inflate(baseRadius / 2);
            List<Monster> nearbyThreats = companion.level().getEntitiesOfClass(Monster.class, searchBox,
                    Monster::isAlive);

            if (!nearbyThreats.isEmpty()) {
                Monster nearest = nearbyThreats.stream()
                        .min(Comparator.comparingDouble(m -> companion.distanceTo(m)))
                        .orElse(null);

                if (nearest != null && companion.distanceTo(nearest) < 16) {
                    report("Mob hostil avistado! Mudando para o modo de patrulha!");
                    changeState(AutonomousState.PATROLLING);
                    return;
                }
            }
        }

        // Stay near home
        double distFromHome = companion.position().distanceTo(Vec3.atCenterOf(homePos));
        if (distFromHome > baseRadius) {
            companion.getNavigation().moveTo(homePos.getX(), homePos.getY(), homePos.getZ(), 0.8);
        }

        // Look around occasionally while resting
        if (ticksInState % 60 == 0) {
            companion.setYRot(companion.getYRot() + (companion.getRandom().nextFloat() - 0.5F) * 90);
        }

        // Periodically reassess
        if (ticksInState > 400) {
            changeState(AutonomousState.ASSESSING);
        }
    }

    private boolean hasExcessItems() {
        int usedSlots = 0;
        for (int i = 0; i < companion.getContainerSize(); i++) {
            if (!companion.getItem(i).isEmpty()) {
                usedSlots++;
            }
        }
        return usedSlots > companion.getContainerSize() / 2;
    }

    private void changeState(AutonomousState newState) {
        LLMoblings.LOGGER.info("[{}] Autonomous state: {} -> {}", companion.getCompanionName(), currentState, newState);
        if (newState != currentState) {
            companion.getNavigation().stop();
        }
        if (currentState == AutonomousState.FARMING && newState != AutonomousState.FARMING) {
            farmingTask = null;
        }
        currentState = newState;
        ticksInState = 0;
        if (newState != AutonomousState.HUNTING) {
            huntTarget = null;
        }
    }

    private void report(String message) {
        LLMoblings.LOGGER.info("[{}] {}", companion.getCompanionName(), message);
        if (companion.getOwner() != null) {
            companion.getOwner().sendSystemMessage(
                    net.minecraft.network.chat.Component.literal("[" + companion.getCompanionName() + "] " + message)
            );
        }
    }

    public AutonomousState getCurrentState() {
        return currentState;
    }

    public String getStatusReport() {
        return String.format("Modo: Autônomo (%s) | Comida: %d | Armado: %s | Com armadura: %s",
                currentState, foodCount, hasWeapon ? "Sim" : "Não", hasArmor ? "Sim" : "Não");
    }
}
