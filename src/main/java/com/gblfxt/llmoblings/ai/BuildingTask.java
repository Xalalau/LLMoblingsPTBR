package com.gblfxt.llmoblings.ai;

import com.gblfxt.llmoblings.LLMoblings;
import com.gblfxt.llmoblings.compat.AE2Integration;
import com.gblfxt.llmoblings.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Task for building structures from blueprints.
 */
public class BuildingTask {

    private final CompanionEntity companion;
    private final Blueprint blueprint;
    private final BlockPos buildOrigin;
    private final Direction facing;

    // State machine
    private BuildState state = BuildState.STARTING;
    private GatherSubState gatherSubState = GatherSubState.CHECK_INVENTORY;

    // Progress tracking
    private int currentPhase = 0;
    private int currentPlacementIndex = 0;
    private List<Blueprint.BlockPlacement> currentPhasePlacements = new ArrayList<>();

    // Material tracking
    private Map<Item, Integer> missingMaterials = new HashMap<>();
    private Item currentGatherTarget = null;
    private int gatherTargetCount = 0;

    // Mining sub-task for gathering
    private MiningTask miningSubTask = null;
    private BlockPos gatherNavigationTarget = null;

    // Timing
    private int ticksInState = 0;
    private int ticksSinceProgress = 0;
    private int blocksPlaced = 0;

    // Completion
    private boolean completed = false;
    private boolean failed = false;
    private String failReason = null;

    // Constants
    private static final int PLACEMENT_DELAY = 5; // Ticks between block placements
    private static final int GATHER_TIMEOUT = 6000; // 5 minutes max gathering

    public enum BuildState {
        STARTING,
        CHECKING_MATERIALS,
        GATHERING,
        NAVIGATING_TO_STORAGE,
        SITE_PREP,
        BUILDING,
        COMPLETED,
        FAILED
    }

    public enum GatherSubState {
        CHECK_INVENTORY,
        CHECK_ME,
        CHECK_CHESTS,
        MINING,
        CHOPPING,
        CRAFTING
    }

    public BuildingTask(CompanionEntity companion, Blueprint blueprint, BlockPos origin) {
        this(companion, blueprint, origin, Direction.SOUTH);
    }

    public BuildingTask(CompanionEntity companion, Blueprint blueprint, BlockPos origin, Direction facing) {
        this.companion = companion;
        this.blueprint = blueprint;
        this.buildOrigin = origin;
        this.facing = facing;

        LLMoblings.LOGGER.info("Starting building task for {} at {}", blueprint.getName(), origin);
    }

    public void tick() {
        if (completed || failed) {
            return;
        }

        ticksInState++;
        ticksSinceProgress++;

        // Timeout protection
        if (ticksSinceProgress > GATHER_TIMEOUT && state == BuildState.GATHERING) {
            fail("Demorei demais para juntar os materiais. Vou precisar de ajuda!");
            return;
        }

        switch (state) {
            case STARTING -> tickStarting();
            case CHECKING_MATERIALS -> tickCheckingMaterials();
            case GATHERING -> tickGathering();
            case NAVIGATING_TO_STORAGE -> tickNavigatingToStorage();
            case SITE_PREP -> tickSitePrep();
            case BUILDING -> tickBuilding();
        }

        // Pick up nearby items periodically
        if (companion.tickCount % 20 == 0) {
            pickupNearbyItems();
        }
    }

    private void tickStarting() {
        // Validate build location
        if (!(companion.level() instanceof ServerLevel)) {
            fail("Não posso construir no lado do cliente.");
            return;
        }

        // Check we're close enough to start
        double distance = companion.position().distanceTo(Vec3.atCenterOf(buildOrigin));
        if (distance > 32) {
            // Need to navigate to build site first
            companion.getNavigation().moveTo(
                buildOrigin.getX() + 0.5,
                buildOrigin.getY(),
                buildOrigin.getZ() + 0.5,
                1.0
            );
            if (ticksInState > 600) { // 30 seconds to get there
                fail("Não consigo alcançar o local da construção.");
            }
            return;
        }

        changeState(BuildState.CHECKING_MATERIALS);
    }

    private void tickCheckingMaterials() {
        missingMaterials.clear();

        Map<Item, Integer> required = blueprint.getRequiredMaterials();
        Map<Item, Integer> available = countInventoryItems();

        // Log what we have in inventory
        LLMoblings.LOGGER.info("[Building] Inventory contents:");
        for (Map.Entry<Item, Integer> entry : available.entrySet()) {
            LLMoblings.LOGGER.info("  - {} x{}", BuiltInRegistries.ITEM.getKey(entry.getKey()), entry.getValue());
        }

        for (Map.Entry<Item, Integer> entry : required.entrySet()) {
            Item item = entry.getKey();
            int needed = entry.getValue();
            int have = available.getOrDefault(item, 0);

            // Also check for equivalent items (e.g., cobblestone variants)
            have += countEquivalentItems(item, available);

            if (have < needed) {
                missingMaterials.put(item, needed - have);
                LLMoblings.LOGGER.info("[Building] Missing {} x{} (have {})",
                    BuiltInRegistries.ITEM.getKey(item), needed - have, have);
            }
        }

        if (missingMaterials.isEmpty()) {
            LLMoblings.LOGGER.info("[Building] Have all materials! Starting site prep.");
            changeState(BuildState.SITE_PREP);
        } else {
            LLMoblings.LOGGER.info("[Building] Missing {} material types, starting gathering.", missingMaterials.size());
            // Pick first missing material
            selectNextGatherTarget();
            changeState(BuildState.GATHERING);
        }
    }

    /**
     * Count equivalent items that can substitute for the required item.
     * For example, deepslate cobblestone can substitute for cobblestone.
     */
    private int countEquivalentItems(Item required, Map<Item, Integer> available) {
        String requiredName = BuiltInRegistries.ITEM.getKey(required).getPath();
        int extra = 0;

        for (Map.Entry<Item, Integer> entry : available.entrySet()) {
            Item item = entry.getKey();
            if (item == required) continue; // Already counted

            String itemName = BuiltInRegistries.ITEM.getKey(item).getPath();

            // Cobblestone variants
            if (requiredName.equals("cobblestone")) {
                if (itemName.contains("cobblestone") || itemName.equals("blackstone")) {
                    extra += entry.getValue();
                }
            }
            // Planks variants
            else if (requiredName.contains("planks")) {
                if (itemName.contains("planks")) {
                    extra += entry.getValue();
                }
            }
            // Log variants
            else if (requiredName.contains("log")) {
                if (itemName.contains("log") && !itemName.contains("stripped")) {
                    extra += entry.getValue();
                }
            }
        }

        return extra;
    }

    private void selectNextGatherTarget() {
        if (missingMaterials.isEmpty()) {
            currentGatherTarget = null;
            gatherTargetCount = 0;
            return;
        }

        // Prioritize basic materials first
        Item[] priorityOrder = {
            Items.COBBLESTONE, Items.OAK_LOG, Items.OAK_PLANKS,
            Items.GLASS_PANE, Items.OAK_STAIRS, Items.OAK_SLAB,
            Items.OAK_DOOR, Items.RED_BED, Items.TORCH
        };

        for (Item priority : priorityOrder) {
            if (missingMaterials.containsKey(priority)) {
                currentGatherTarget = priority;
                gatherTargetCount = missingMaterials.get(priority);
                return;
            }
        }

        // Fall back to first missing
        Map.Entry<Item, Integer> first = missingMaterials.entrySet().iterator().next();
        currentGatherTarget = first.getKey();
        gatherTargetCount = first.getValue();
    }

    private void tickGathering() {
        if (currentGatherTarget == null) {
            changeState(BuildState.CHECKING_MATERIALS);
            return;
        }

        // Check if we now have enough
        int have = countItem(currentGatherTarget);
        if (have >= blueprint.getRequiredMaterials().getOrDefault(currentGatherTarget, 0)) {
            missingMaterials.remove(currentGatherTarget);
            selectNextGatherTarget();
            ticksSinceProgress = 0;

            if (currentGatherTarget == null) {
                changeState(BuildState.CHECKING_MATERIALS);
            }
            return;
        }

        switch (gatherSubState) {
            case CHECK_INVENTORY -> {
                // Already checked, move to ME
                gatherSubState = GatherSubState.CHECK_ME;
            }
            case CHECK_ME -> {
                if (tryExtractFromME()) {
                    ticksSinceProgress = 0;
                    gatherSubState = GatherSubState.CHECK_INVENTORY;
                } else {
                    gatherSubState = GatherSubState.CHECK_CHESTS;
                }
            }
            case CHECK_CHESTS -> {
                BlockPos chest = findNearbyChest();
                if (chest != null) {
                    gatherNavigationTarget = chest;
                    changeState(BuildState.NAVIGATING_TO_STORAGE);
                } else {
                    // Need to mine/chop
                    gatherSubState = determineGatherMethod();
                }
            }
            case MINING -> tickMining();
            case CHOPPING -> tickChopping();
            case CRAFTING -> tickCrafting();
        }
    }

    private boolean tryExtractFromME() {
        if (!AE2Integration.isAE2Loaded()) {
            return false;
        }

        List<BlockPos> mePoints = AE2Integration.findMEAccessPoints(
            companion.level(), companion.blockPosition(), 32);

        if (mePoints.isEmpty()) {
            return false;
        }

        // Navigate to ME first if far
        BlockPos nearest = mePoints.get(0);
        double dist = companion.position().distanceTo(Vec3.atCenterOf(nearest));

        if (dist > 4) {
            gatherNavigationTarget = nearest;
            changeState(BuildState.NAVIGATING_TO_STORAGE);
            return false;
        }

        // Try to extract
        int needed = gatherTargetCount - countItem(currentGatherTarget);
        Item target = currentGatherTarget;

        List<ItemStack> extracted = AE2Integration.extractItems(
            companion.level(), nearest,
            stack -> stack.getItem() == target,
            needed
        );

        for (ItemStack stack : extracted) {
            companion.addToInventory(stack);
        }

        return !extracted.isEmpty();
    }

    private BlockPos findNearbyChest() {
        BlockPos center = companion.blockPosition();
        int radius = 16;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -5; y <= 5; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    BlockEntity be = companion.level().getBlockEntity(pos);
                    if (be instanceof Container container) {
                        // Check if chest has what we need
                        for (int i = 0; i < container.getContainerSize(); i++) {
                            ItemStack stack = container.getItem(i);
                            if (stack.getItem() == currentGatherTarget) {
                                return pos;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private void tickNavigatingToStorage() {
        if (gatherNavigationTarget == null) {
            changeState(BuildState.GATHERING);
            return;
        }

        double dist = companion.position().distanceTo(Vec3.atCenterOf(gatherNavigationTarget));

        if (dist > 3) {
            if (companion.getNavigation().isDone()) {
                companion.getNavigation().moveTo(
                    gatherNavigationTarget.getX() + 0.5,
                    gatherNavigationTarget.getY(),
                    gatherNavigationTarget.getZ() + 0.5,
                    1.0
                );
            }
        } else {
            // Arrived, try to extract
            BlockEntity be = companion.level().getBlockEntity(gatherNavigationTarget);
            if (be instanceof Container container) {
                extractFromContainer(container);
            }
            gatherNavigationTarget = null;
            changeState(BuildState.GATHERING);
        }

        if (ticksInState > 300) { // 15 seconds
            gatherNavigationTarget = null;
            changeState(BuildState.GATHERING);
        }
    }

    private void extractFromContainer(Container container) {
        int needed = gatherTargetCount - countItem(currentGatherTarget);

        for (int i = 0; i < container.getContainerSize() && needed > 0; i++) {
            ItemStack stack = container.getItem(i);
            if (stack.getItem() == currentGatherTarget) {
                int take = Math.min(stack.getCount(), needed);
                ItemStack taken = stack.split(take);
                companion.addToInventory(taken);
                needed -= take;
                ticksSinceProgress = 0;
            }
        }
    }

    private GatherSubState determineGatherMethod() {
        String itemName = BuiltInRegistries.ITEM.getKey(currentGatherTarget).getPath();

        // Cobblestone - mine stone
        if (itemName.contains("cobblestone") || itemName.contains("stone")) {
            return GatherSubState.MINING;
        }

        // Wood products - chop trees first
        if (itemName.contains("oak") || itemName.contains("log") ||
            itemName.contains("plank") || itemName.contains("stair") ||
            itemName.contains("slab") || itemName.contains("door")) {
            return GatherSubState.CHOPPING;
        }

        // Default to mining
        return GatherSubState.MINING;
    }

    private void tickMining() {
        if (miningSubTask == null) {
            // Start mining stone
            miningSubTask = new MiningTask(companion, "stone", gatherTargetCount * 2, 32);
        }

        miningSubTask.tick();

        if (miningSubTask.isCompleted() || miningSubTask.isFailed()) {
            miningSubTask = null;
            gatherSubState = GatherSubState.CHECK_INVENTORY;
            ticksSinceProgress = 0;
        }
    }

    private void tickChopping() {
        if (miningSubTask == null) {
            // Start chopping logs
            miningSubTask = new MiningTask(companion, "oak_log", 16, 48);
        }

        miningSubTask.tick();

        if (miningSubTask.isCompleted() || miningSubTask.isFailed()) {
            miningSubTask = null;
            // After chopping, try to craft what we need
            gatherSubState = GatherSubState.CRAFTING;
            ticksSinceProgress = 0;
        }
    }

    private void tickCrafting() {
        // Simple crafting logic - convert logs to planks, planks to stairs/slabs
        String itemName = BuiltInRegistries.ITEM.getKey(currentGatherTarget).getPath();

        if (itemName.contains("plank")) {
            // Craft logs -> planks (1:4)
            int logs = countItem(Items.OAK_LOG);
            if (logs > 0) {
                removeItem(Items.OAK_LOG, logs);
                addItem(Items.OAK_PLANKS, logs * 4);
                ticksSinceProgress = 0;
            }
        } else if (itemName.contains("stair")) {
            // Craft planks -> stairs (6:4)
            int planks = countItem(Items.OAK_PLANKS);
            int batches = planks / 6;
            if (batches > 0) {
                removeItem(Items.OAK_PLANKS, batches * 6);
                addItem(Items.OAK_STAIRS, batches * 4);
                ticksSinceProgress = 0;
            }
        } else if (itemName.contains("slab")) {
            // Craft planks -> slabs (3:6)
            int planks = countItem(Items.OAK_PLANKS);
            int batches = planks / 3;
            if (batches > 0) {
                removeItem(Items.OAK_PLANKS, batches * 3);
                addItem(Items.OAK_SLAB, batches * 6);
                ticksSinceProgress = 0;
            }
        }

        gatherSubState = GatherSubState.CHECK_INVENTORY;
    }

    private void tickSitePrep() {
        ServerLevel level = (ServerLevel) companion.level();

        // Clear the build area
        boolean clearedSomething = false;
        int width = blueprint.getWidth();
        int depth = blueprint.getDepth();
        int height = blueprint.getHeight();

        for (int x = -1; x <= width; x++) {
            for (int z = -1; z <= depth; z++) {
                for (int y = 0; y < height + 3; y++) {
                    BlockPos worldPos = buildOrigin.offset(x, y, z);
                    BlockState state = level.getBlockState(worldPos);

                    if (shouldClear(state)) {
                        level.destroyBlock(worldPos, true, companion);
                        clearedSomething = true;
                    }
                }
            }
        }

        if (!clearedSomething) {
            // Site is clear, start building
            currentPhase = 0;
            loadPhase();
            changeState(BuildState.BUILDING);
        }
    }

    private boolean shouldClear(BlockState state) {
        if (state.isAir()) return false;

        String blockName = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return blockName.contains("grass") ||
               blockName.contains("flower") ||
               blockName.contains("sapling") ||
               blockName.contains("leaves") ||
               blockName.contains("fern") ||
               blockName.contains("tallgrass") ||
               blockName.contains("tall_grass") ||
               blockName.contains("vine") ||
               blockName.contains("mushroom") ||
               blockName.contains("dead_bush") ||
               blockName.contains("seagrass") ||
               blockName.contains("kelp");
    }

    private void loadPhase() {
        currentPhasePlacements = blueprint.getPlacementsForPhase(currentPhase);
        currentPlacementIndex = 0;
        LLMoblings.LOGGER.debug("Loaded phase {} with {} placements", currentPhase, currentPhasePlacements.size());
    }

    private void tickBuilding() {
        if (!(companion.level() instanceof ServerLevel level)) {
            return;
        }

        // Delay between placements
        if (ticksInState % PLACEMENT_DELAY != 0) {
            return;
        }

        // Check if current phase is done
        if (currentPlacementIndex >= currentPhasePlacements.size()) {
            currentPhase++;
            if (currentPhase > Blueprint.PHASE_INTERIOR) {
                // All done!
                completed = true;
                state = BuildState.COMPLETED;
                return;
            }
            loadPhase();
            if (currentPhasePlacements.isEmpty()) {
                // Skip empty phases
                tickBuilding();
                return;
            }
        }

        // Get next placement
        Blueprint.BlockPlacement placement = currentPhasePlacements.get(currentPlacementIndex);
        BlockPos worldPos = placement.getWorldPos(buildOrigin, facing);

        // Navigate close to placement
        double dist = companion.position().distanceTo(Vec3.atCenterOf(worldPos));
        if (dist > 5) {
            companion.getNavigation().moveTo(
                worldPos.getX() + 0.5,
                worldPos.getY(),
                worldPos.getZ() + 0.5,
                1.0
            );
            return;
        }

        // Look at the block position
        companion.getLookControl().setLookAt(
            worldPos.getX() + 0.5,
            worldPos.getY() + 0.5,
            worldPos.getZ() + 0.5
        );

        // Place the block
        if (placeBlock(level, worldPos, placement.state())) {
            blocksPlaced++;
            ticksSinceProgress = 0;

            // Swing arm for visual feedback
            companion.swing(InteractionHand.MAIN_HAND);
        }

        currentPlacementIndex++;
    }

    private boolean placeBlock(ServerLevel level, BlockPos pos, BlockState state) {
        // Check if position is valid
        BlockState existing = level.getBlockState(pos);
        if (!existing.isAir() && !existing.canBeReplaced()) {
            // Try to break it first if it's a simple block
            if (canBreakForBuilding(existing)) {
                level.destroyBlock(pos, true, companion);
            } else {
                LLMoblings.LOGGER.debug("Cannot place at {} - blocked by {}", pos, existing);
                return false;
            }
        }

        // Consume material from inventory
        Item requiredItem = state.getBlock().asItem();
        if (requiredItem != Items.AIR) {
            if (!consumeMaterial(requiredItem)) {
                LLMoblings.LOGGER.debug("Missing material {} for placement", requiredItem);
                return false;
            }
        }

        // Place the block
        level.setBlockAndUpdate(pos, state);
        LLMoblings.LOGGER.debug("Placed {} at {}", state.getBlock(), pos);
        return true;
    }

    private boolean canBreakForBuilding(BlockState state) {
        String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return name.contains("grass") || name.contains("flower") ||
               name.contains("fern") || name.contains("vine") ||
               name.contains("mushroom") || name.contains("sapling");
    }

    private boolean consumeMaterial(Item item) {
        // First try exact match
        for (int i = 0; i < companion.getContainerSize(); i++) {
            ItemStack stack = companion.getItem(i);
            if (stack.getItem() == item) {
                stack.shrink(1);
                if (stack.isEmpty()) {
                    companion.setItem(i, ItemStack.EMPTY);
                }
                return true;
            }
        }

        // Try equivalent items
        String requiredName = BuiltInRegistries.ITEM.getKey(item).getPath();
        for (int i = 0; i < companion.getContainerSize(); i++) {
            ItemStack stack = companion.getItem(i);
            if (stack.isEmpty()) continue;

            String stackName = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            boolean isEquivalent = false;

            // Cobblestone variants
            if (requiredName.equals("cobblestone") &&
                (stackName.contains("cobblestone") || stackName.equals("blackstone"))) {
                isEquivalent = true;
            }
            // Planks variants
            else if (requiredName.contains("planks") && stackName.contains("planks")) {
                isEquivalent = true;
            }
            // Log variants
            else if (requiredName.contains("log") && stackName.contains("log") && !stackName.contains("stripped")) {
                isEquivalent = true;
            }

            if (isEquivalent) {
                stack.shrink(1);
                if (stack.isEmpty()) {
                    companion.setItem(i, ItemStack.EMPTY);
                }
                LLMoblings.LOGGER.debug("[Building] Used {} as substitute for {}",
                    stackName, requiredName);
                return true;
            }
        }
        return false;
    }

    private Map<Item, Integer> countInventoryItems() {
        Map<Item, Integer> counts = new HashMap<>();
        for (int i = 0; i < companion.getContainerSize(); i++) {
            ItemStack stack = companion.getItem(i);
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    private int countItem(Item item) {
        int count = 0;
        for (int i = 0; i < companion.getContainerSize(); i++) {
            ItemStack stack = companion.getItem(i);
            if (stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private void removeItem(Item item, int count) {
        int remaining = count;
        for (int i = 0; i < companion.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = companion.getItem(i);
            if (stack.getItem() == item) {
                int take = Math.min(stack.getCount(), remaining);
                stack.shrink(take);
                remaining -= take;
                if (stack.isEmpty()) {
                    companion.setItem(i, ItemStack.EMPTY);
                }
            }
        }
    }

    private void addItem(Item item, int count) {
        companion.addToInventory(new ItemStack(item, count));
    }

    private void pickupNearbyItems() {
        AABB pickupBox = companion.getBoundingBox().inflate(3.0);
        List<ItemEntity> items = companion.level().getEntitiesOfClass(ItemEntity.class, pickupBox);

        for (ItemEntity itemEntity : items) {
            if (itemEntity.isAlive() && !itemEntity.hasPickUpDelay()) {
                ItemStack stack = itemEntity.getItem();
                ItemStack remaining = companion.addToInventory(stack);
                if (remaining.isEmpty()) {
                    itemEntity.discard();
                } else {
                    itemEntity.setItem(remaining);
                }
            }
        }
    }

    private void changeState(BuildState newState) {
        LLMoblings.LOGGER.debug("BuildingTask state: {} -> {}", state, newState);
        state = newState;
        ticksInState = 0;
    }

    private void fail(String reason) {
        failed = true;
        failReason = reason;
        state = BuildState.FAILED;
    }

    // Getters

    public boolean isCompleted() {
        return completed;
    }

    public boolean isFailed() {
        return failed;
    }

    public String getFailReason() {
        return failReason;
    }

    public String getStructureName() {
        return blueprint.getName();
    }

    public String getProgressReport() {
        int total = blueprint.getTotalBlocks();
        int percent = total > 0 ? (blocksPlaced * 100 / total) : 0;

        return switch (state) {
            case STARTING -> "Me preparando...";
            case CHECKING_MATERIALS -> "Verificando materiais...";
            case GATHERING -> "Coletando " + (currentGatherTarget != null ?
                BuiltInRegistries.ITEM.getKey(currentGatherTarget).getPath() : "materiais") + "...";
            case NAVIGATING_TO_STORAGE -> "Buscando suprimentos...";
            case SITE_PREP -> "Limpando o local da construção...";
            case BUILDING -> percent + "% concluído (" + blocksPlaced + "/" + total + " blocos)";
            case COMPLETED -> "Pronto!";
            case FAILED -> "Falhou: " + failReason;
        };
    }

    public BuildState getState() {
        return state;
    }
}
