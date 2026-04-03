package com.gblfxt.llmoblings.ai;

import com.gblfxt.llmoblings.ChunkLoadingManager;
import com.gblfxt.llmoblings.LLMoblings;
import com.gblfxt.llmoblings.compat.AE2Integration;
import com.gblfxt.llmoblings.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.levelgen.Heightmap;

import java.text.Normalizer;
import java.util.*;

public class MiningTask {
    private final CompanionEntity companion;
    private final String targetBlockName;
    private final int targetCount;
    private final int searchRadius;

    private int minedCount = 0;
    private BlockPos currentTarget = null;
    private int miningProgress = 0;
    private int ticksAtCurrentBlock = 0;
    private int ticksSinceLastProgress = 0;
    private boolean completed = false;
    private boolean failed = false;
    private String failReason = null;

    // Search behavior for requests like "go find wood".
    private final BlockPos searchOrigin;
    private BlockPos explorationTarget = null;
    private int explorationStep = 0;
    private int ticksMovingToExplorationTarget = 0;
    private boolean announcedExploration = false;
    private static final int MAX_EXPLORATION_STEPS = 12;
    private static final int EXPLORATION_TIMEOUT_TICKS = 240;
    private static final int EXPLORATION_REACH_DISTANCE = 6;

    // Block types that match the request
    private final Set<Block> targetBlocks = new HashSet<>();

    // Spatial awareness - protected zones
    private final Set<BlockPos> protectedPositions = new HashSet<>();
    private BlockPos homePos;
    private static final int BASE_PROTECTION_RADIUS = 8;  // Don't mine within 8 blocks of base structures

    // Mining speeds (ticks to break)
    private static final int BASE_MINING_TICKS = 30; // About 1.5 seconds base

    // Ultimine-style mining queue
    private final Queue<BlockPos> miningQueue = new LinkedList<>();
    private boolean isVeinMining = false;
    private boolean isTreeFelling = false;
    private boolean hasEquippedTool = false;
    private boolean toolCheckDone = false;

    // ME network access for tool retrieval
    private BlockPos meAccessPoint = null;

    public MiningTask(CompanionEntity companion, String blockName, int count, int searchRadius) {
        this.companion = companion;
        this.targetBlockName = normalizeResourceName(blockName);
        this.targetCount = count;
        this.searchRadius = searchRadius;
        this.homePos = companion.blockPosition();
        this.searchOrigin = companion.blockPosition();

        resolveTargetBlocks();
        scanProtectedZones();
        findMEAccessPoint();

        if (targetBlocks.isEmpty()) {
            failed = true;
            failReason = "I don't know what '" + blockName + "' is.";
        }
    }

    /**
     * Find nearby ME network access points for tool retrieval.
     */
    private void findMEAccessPoint() {
        List<BlockPos> meAccessPoints = AE2Integration.findMEAccessPoints(
                companion.level(), companion.blockPosition(), searchRadius);

        if (!meAccessPoints.isEmpty()) {
            meAccessPoint = meAccessPoints.get(0);
            LLMoblings.LOGGER.debug("[{}] Found ME access point at {} for tool retrieval",
                    companion.getCompanionName(), meAccessPoint);
        }
    }

    /**
     * Scan the area to identify structures and protected zones.
     */
    private void scanProtectedZones() {
        BlockPos center = companion.blockPosition();

        for (int x = -searchRadius; x <= searchRadius; x++) {
            for (int y = -10; y <= 10; y++) {
                for (int z = -searchRadius; z <= searchRadius; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    BlockEntity be = companion.level().getBlockEntity(pos);

                    // Protect areas around containers, crafting stations, etc.
                    if (be instanceof Container || isImportantBlock(companion.level().getBlockState(pos))) {
                        markProtectedZone(pos, BASE_PROTECTION_RADIUS);
                    }
                }
            }
        }

        LLMoblings.LOGGER.debug("Identified {} protected positions", protectedPositions.size());
    }

    private boolean isImportantBlock(BlockState state) {
        String blockName = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        // Protect crafting stations, furnaces, chests, beds, etc.
        return blockName.contains("chest") ||
               blockName.contains("barrel") ||
               blockName.contains("furnace") ||
               blockName.contains("crafting") ||
               blockName.contains("anvil") ||
               blockName.contains("enchant") ||
               blockName.contains("bed") ||
               blockName.contains("door") ||
               blockName.contains("torch") ||
               blockName.contains("lantern") ||
               blockName.contains("campfire") ||
               blockName.contains("table") ||
               blockName.contains("workbench") ||
               blockName.contains("terminal") ||  // AE2
               blockName.contains("interface") || // AE2
               blockName.contains("drive");       // AE2
    }

    private void markProtectedZone(BlockPos center, int radius) {
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    protectedPositions.add(center.offset(x, y, z));
                }
            }
        }
    }

    /**
     * Check if a block is safe to mine (not part of a structure).
     */
    private boolean isSafeToMine(BlockPos pos) {
        // Don't mine blocks outside loaded chunks
        if (!ChunkLoadingManager.isBlockInLoadedChunks(companion, pos)) {
            return false;
        }

        // Don't mine in protected zones
        if (protectedPositions.contains(pos)) {
            return false;
        }

        BlockState state = companion.level().getBlockState(pos);

        // Don't mine if it would remove a floor (block with air below and solid above)
        BlockState below = companion.level().getBlockState(pos.below());
        BlockState above = companion.level().getBlockState(pos.above());
        if (!below.isSolid() && above.isSolid()) {
            // This might be a floor block
            return false;
        }

        // Don't mine blocks that are clearly structural (walls)
        int adjacentAir = 0;
        int adjacentSolid = 0;
        for (BlockPos adj : new BlockPos[]{pos.north(), pos.south(), pos.east(), pos.west()}) {
            if (companion.level().getBlockState(adj).isAir()) {
                adjacentAir++;
            } else if (companion.level().getBlockState(adj).isSolid()) {
                adjacentSolid++;
            }
        }

        // If block has exactly one air side and is above ground, it might be a wall
        if (adjacentAir == 1 && adjacentSolid >= 2 && !below.isAir()) {
            // Check if this looks like a wall (vertical line of same blocks)
            BlockState aboveState = companion.level().getBlockState(pos.above());
            BlockState belowState = companion.level().getBlockState(pos.below());
            if (state.getBlock() == aboveState.getBlock() || state.getBlock() == belowState.getBlock()) {
                return false;  // Likely a wall
            }
        }

        // Prefer natural generation - ores are always safe
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (blockId.contains("ore") || blockId.contains("_log") || blockId.contains("leaves")) {
            return true;  // Natural blocks are safe
        }

        // For stone/dirt, only mine if underground (Y < 60 or has blocks above)
        if (blockId.equals("stone") || blockId.equals("cobblestone") ||
            blockId.equals("dirt") || blockId.equals("grass_block")) {
            return pos.getY() < 60 || !companion.level().canSeeSky(pos);
        }

        return true;
    }


    private String normalizeResourceName(String blockName) {
        String normalized = Normalizer.normalize(blockName, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replace(' ', '_');

        return switch (normalized) {
            case "madeira", "tronco", "arvore", "arvores", "lenha", "wood", "logs" -> "wood";
            case "pedra" -> "stone";
            case "carvao" -> "coal";
            case "ferro" -> "iron";
            case "ouro" -> "gold";
            case "diamante" -> "diamond";
            default -> normalized;
        };
    }

    private boolean isOreRequest() {
        return targetBlockName.contains("ore") || targetBlockName.contains("coal") || targetBlockName.contains("iron") ||
                targetBlockName.contains("gold") || targetBlockName.contains("diamond") || targetBlockName.contains("copper");
    }

    private void resolveTargetBlocks() {
        // Try to match block by name (partial matching for convenience)
        String searchTerm = targetBlockName.replace(" ", "_");

        // Common aliases
        Map<String, String> aliases = Map.ofEntries(
            Map.entry("wood", "oak_log"),
            Map.entry("madeira", "oak_log"),
            Map.entry("tronco", "oak_log"),
            Map.entry("logs", "oak_log"),
            Map.entry("stone", "stone"),
            Map.entry("pedra", "stone"),
            Map.entry("cobble", "cobblestone"),
            Map.entry("dirt", "dirt"),
            Map.entry("iron", "iron_ore"),
            Map.entry("ferro", "iron_ore"),
            Map.entry("gold", "gold_ore"),
            Map.entry("ouro", "gold_ore"),
            Map.entry("diamond", "diamond_ore"),
            Map.entry("diamante", "diamond_ore"),
            Map.entry("coal", "coal_ore"),
            Map.entry("carvao", "coal_ore"),
            Map.entry("copper", "copper_ore")
        );

        if (aliases.containsKey(searchTerm)) {
            searchTerm = aliases.get(searchTerm);
        }

        // Search all registered blocks
        for (var entry : BuiltInRegistries.BLOCK.entrySet()) {
            ResourceLocation id = entry.getKey().location();
            String blockId = id.getPath();

            // Match if the block ID contains our search term
            if (blockId.contains(searchTerm) || searchTerm.contains(blockId)) {
                targetBlocks.add(entry.getValue());
            }
        }

        // Special handling for "log" to get all log types
        if (searchTerm.contains("log") || searchTerm.equals("wood")) {
            for (var entry : BuiltInRegistries.BLOCK.entrySet()) {
                String blockId = entry.getKey().location().getPath();
                if (blockId.endsWith("_log") || blockId.endsWith("_wood")) {
                    targetBlocks.add(entry.getValue());
                }
            }
        }

        // Special handling for ores
        if (searchTerm.contains("ore")) {
            for (var entry : BuiltInRegistries.BLOCK.entrySet()) {
                String blockId = entry.getKey().location().getPath();
                if (blockId.contains(searchTerm.replace("_ore", "")) && blockId.contains("ore")) {
                    targetBlocks.add(entry.getValue());
                }
            }
        }

        LLMoblings.LOGGER.debug("Resolved '{}' to {} block types", targetBlockName, targetBlocks.size());
    }

    public void tick() {
        if (completed || failed) {
            return;
        }

        // Check if we've collected enough
        if (minedCount >= targetCount) {
            completed = true;
            return;
        }

        // Pick up nearby items
        pickupNearbyItems();

        // Get next target from queue or find new one
        if (currentTarget == null || !isValidTarget(currentTarget)) {
            // Try to get from queue first
            while (!miningQueue.isEmpty()) {
                BlockPos queued = miningQueue.poll();
                if (isValidTarget(queued) && isSafeToMine(queued)) {
                    currentTarget = queued;
                    break;
                }
            }

            // If queue is empty, find a new vein/tree
            if (currentTarget == null) {
                currentTarget = findNearestTargetBlock();
                miningProgress = 0;
                ticksAtCurrentBlock = 0;
                isVeinMining = false;
                isTreeFelling = false;

                if (currentTarget == null) {
                    ticksSinceLastProgress++;
                    if (handleExplorationSearch()) {
                        return;
                    }
                    if (ticksSinceLastProgress > 200) { // 10 seconds without finding anything nearby
                        failed = true;
                        failReason = "Não consegui encontrar mais " + targetBlockName + ". Procurei pela área e mesmo saindo para buscar não achei nada útil.";
                    }
                    return;
                }

                explorationTarget = null;
                ticksMovingToExplorationTarget = 0;
                announcedExploration = false;

                // Queue up connected blocks for ultimine-style mining
                queueConnectedBlocks(currentTarget);
            }
        }

        ticksSinceLastProgress = 0;

        // Ensure we have the right tool (from inventory, ME network, or crafting)
        if (!toolCheckDone) {
            BlockState targetState = companion.level().getBlockState(currentTarget);
            UltimineHelper.EnsureToolResult toolResult = UltimineHelper.ensureTool(companion, targetState, meAccessPoint);

            if (toolResult.success()) {
                LLMoblings.LOGGER.info("[{}] Tool ready: {}",
                        companion.getCompanionName(), toolResult.message());
                hasEquippedTool = true;
            } else {
                // Log the issue but continue - companion will mine slowly with bare hands
                LLMoblings.LOGGER.warn("[{}] Tool issue: {} - mining anyway",
                        companion.getCompanionName(), toolResult.message());
            }
            toolCheckDone = true;
        }

        // Check if we're using the wrong tool and should re-check
        if (hasEquippedTool && currentTarget != null) {
            BlockState targetState = companion.level().getBlockState(currentTarget);
            if (!UltimineHelper.hasCorrectToolEquipped(companion, targetState)) {
                // Try to switch to correct tool
                UltimineHelper.equipBestTool(companion, targetState);
            }
        }

        // Move towards target
        double distance = companion.position().distanceTo(Vec3.atCenterOf(currentTarget));

        if (distance > 4.0) {
            // Too far, pathfind to it
            if (companion.getNavigation().isDone()) {
                companion.getNavigation().moveTo(
                    currentTarget.getX() + 0.5,
                    currentTarget.getY(),
                    currentTarget.getZ() + 0.5,
                    1.0
                );
            }
            ticksAtCurrentBlock = 0;
        } else if (distance > 2.5) {
            // Getting close, keep moving
            companion.getNavigation().moveTo(
                currentTarget.getX() + 0.5,
                currentTarget.getY(),
                currentTarget.getZ() + 0.5,
                0.8
            );
            ticksAtCurrentBlock++;
        } else {
            // In range, mine the block
            companion.getNavigation().stop();
            ticksAtCurrentBlock++;

            // Look at the block
            companion.getLookControl().setLookAt(
                currentTarget.getX() + 0.5,
                currentTarget.getY() + 0.5,
                currentTarget.getZ() + 0.5
            );

            // Swing arm for visual feedback
            if (ticksAtCurrentBlock % 5 == 0) {
                companion.swing(companion.getUsedItemHand());
            }

            // Calculate mining time based on block hardness and tool
            BlockState state = companion.level().getBlockState(currentTarget);
            int miningTime = calculateMiningTime(state);

            miningProgress++;

            if (miningProgress >= miningTime) {
                // Break the block
                breakBlock(currentTarget);
                minedCount++;
                currentTarget = null;
                miningProgress = 0;

                // Log progress for veins/trees
                if ((isVeinMining || isTreeFelling) && !miningQueue.isEmpty()) {
                    LLMoblings.LOGGER.debug("Ultimine progress: {} mined, {} in queue",
                        minedCount, miningQueue.size());
                }
            }
        }

        // Timeout if stuck at one block too long
        if (ticksAtCurrentBlock > 300) { // 15 seconds
            LLMoblings.LOGGER.debug("Mining timeout, skipping block at {}", currentTarget);
            currentTarget = null;
            miningProgress = 0;
            ticksAtCurrentBlock = 0;
        }
    }

    /**
     * Queue connected blocks for ultimine-style mining.
     */
    private void queueConnectedBlocks(BlockPos start) {
        BlockState state = companion.level().getBlockState(start);
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();

        // Check if this is a log (tree felling)
        if (blockId.contains("log") || blockId.contains("wood")) {
            List<BlockPos> tree = UltimineHelper.findTree(companion.level(), start);
            if (tree.size() > 1) {
                isTreeFelling = true;
                // Skip the first one (it's our current target)
                for (int i = 1; i < tree.size() && miningQueue.size() < 64; i++) {
                    BlockPos pos = tree.get(i);
                    if (isSafeToMine(pos)) {
                        miningQueue.add(pos);
                    }
                }
                LLMoblings.LOGGER.info("[{}] Tree felling: {} blocks queued",
                    companion.getCompanionName(), miningQueue.size() + 1);
            }
            return;
        }

        // Check if this is an ore (vein mining)
        if (blockId.contains("ore")) {
            List<BlockPos> vein = UltimineHelper.findConnectedBlocks(companion.level(), start, 32);
            if (vein.size() > 1) {
                isVeinMining = true;
                // Skip the first one (it's our current target)
                for (int i = 1; i < vein.size(); i++) {
                    BlockPos pos = vein.get(i);
                    if (isSafeToMine(pos)) {
                        miningQueue.add(pos);
                    }
                }
                LLMoblings.LOGGER.info("[{}] Vein mining: {} blocks queued",
                    companion.getCompanionName(), miningQueue.size() + 1);
            }
        }
    }


    private boolean handleExplorationSearch() {
        if (explorationStep >= MAX_EXPLORATION_STEPS) {
            return false;
        }

        if (explorationTarget == null) {
            explorationTarget = chooseNextExplorationTarget();
            ticksMovingToExplorationTarget = 0;
            announcedExploration = false;
            if (explorationTarget == null) {
                explorationStep = MAX_EXPLORATION_STEPS;
                return false;
            }
        }

        double distance = companion.position().distanceTo(Vec3.atCenterOf(explorationTarget));
        if (distance <= EXPLORATION_REACH_DISTANCE) {
            LLMoblings.LOGGER.info("[{}] Reached exploration point {} while searching for {}",
                    companion.getCompanionName(), explorationTarget, targetBlockName);
            explorationTarget = null;
            explorationStep++;
            ticksMovingToExplorationTarget = 0;
            announcedExploration = false;
            return true;
        }

        if (!announcedExploration) {
            LLMoblings.LOGGER.info("[{}] Couldn't find {} nearby, moving to search area {}",
                    companion.getCompanionName(), targetBlockName, explorationTarget);
            announcedExploration = true;
        }

        if (companion.getNavigation().isDone() || ticksMovingToExplorationTarget % 20 == 0) {
            companion.getNavigation().moveTo(
                    explorationTarget.getX() + 0.5,
                    explorationTarget.getY(),
                    explorationTarget.getZ() + 0.5,
                    1.0
            );
        }

        ticksMovingToExplorationTarget++;
        if (ticksMovingToExplorationTarget > EXPLORATION_TIMEOUT_TICKS) {
            LLMoblings.LOGGER.info("[{}] Search point {} timed out while looking for {}",
                    companion.getCompanionName(), explorationTarget, targetBlockName);
            explorationTarget = null;
            explorationStep++;
            ticksMovingToExplorationTarget = 0;
            announcedExploration = false;
        }

        return true;
    }

    private BlockPos chooseNextExplorationTarget() {
        if (!(companion.level() instanceof ServerLevel serverLevel)) {
            return null;
        }

        int ring = explorationStep / 8 + 1;
        int spoke = explorationStep % 8;
        double angle = Math.toRadians(spoke * 45.0);
        int distance = Math.min(24 * ring, 96);
        int targetX = searchOrigin.getX() + (int) Math.round(Math.cos(angle) * distance);
        int targetZ = searchOrigin.getZ() + (int) Math.round(Math.sin(angle) * distance);

        int targetY;
        if (isOreRequest()) {
            targetY = Math.max(companion.blockPosition().getY(), 12);
        } else {
            targetY = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, targetX, targetZ);
        }

        return new BlockPos(targetX, targetY, targetZ);
    }

    private boolean isValidTarget(BlockPos pos) {
        BlockState state = companion.level().getBlockState(pos);

        // Direct match
        if (targetBlocks.contains(state.getBlock())) {
            return true;
        }

        // During tree felling, also accept leaves
        if (isTreeFelling) {
            String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            if (blockId.contains("log") || blockId.contains("leaves") || blockId.contains("wood")) {
                return true;
            }
        }

        // During vein mining, accept deepslate variants
        if (isVeinMining) {
            String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            for (Block target : targetBlocks) {
                String targetId = BuiltInRegistries.BLOCK.getKey(target).getPath();
                String normalizedTarget = targetId.replace("deepslate_", "");
                String normalizedBlock = blockId.replace("deepslate_", "");
                if (normalizedTarget.equals(normalizedBlock)) {
                    return true;
                }
            }
        }

        return false;
    }

    private BlockPos findNearestTargetBlock() {
        BlockPos companionPos = companion.blockPosition();
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;

        // Limit search radius to loaded chunks (32 blocks from center)
        int effectiveRadius = Math.min(searchRadius, ChunkLoadingManager.getWorkingRadius());

        // Search in expanding shells for efficiency
        for (int radius = 1; radius <= effectiveRadius; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int y = -radius; y <= radius; y++) {
                    for (int z = -radius; z <= radius; z++) {
                        // Only check outer shell of this radius
                        if (Math.abs(x) != radius && Math.abs(y) != radius && Math.abs(z) != radius) {
                            continue;
                        }

                        BlockPos checkPos = companionPos.offset(x, y, z);

                        // Skip blocks outside loaded chunks
                        if (!ChunkLoadingManager.isBlockInLoadedChunks(companion, checkPos)) {
                            continue;
                        }

                        BlockState state = companion.level().getBlockState(checkPos);

                        if (targetBlocks.contains(state.getBlock())) {
                            double dist = companionPos.distSqr(checkPos);
                            if (dist < nearestDist) {
                                // Check if safe to mine and reachable
                                if (isSafeToMine(checkPos) && isReachable(checkPos)) {
                                    nearest = checkPos;
                                    nearestDist = dist;
                                }
                            }
                        }
                    }
                }
            }

            // If we found something in this shell, return it
            if (nearest != null) {
                return nearest;
            }
        }

        return nearest;
    }

    private boolean isReachable(BlockPos pos) {
        // Check if there's an air block adjacent that the companion could stand near
        for (BlockPos adjacent : new BlockPos[]{
            pos.above(), pos.below(), pos.north(), pos.south(), pos.east(), pos.west()
        }) {
            BlockState state = companion.level().getBlockState(adjacent);
            if (!state.isSolid() || state.isAir()) {
                return true;
            }
        }
        return false;
    }

    private int calculateMiningTime(BlockState state) {
        float hardness = state.getDestroySpeed(companion.level(), currentTarget);
        if (hardness < 0) {
            return 1000; // Unbreakable
        }

        // Base time scaled with hardness
        int baseTime = (int) (BASE_MINING_TICKS + hardness * 10);

        // Apply tool speed multiplier
        float toolMultiplier = UltimineHelper.getMiningSpeedMultiplier(companion, state);
        if (toolMultiplier > 1.0f) {
            baseTime = (int) (baseTime / toolMultiplier);
        }

        // Minimum 5 ticks (0.25 seconds)
        return Math.max(5, baseTime);
    }

    private void breakBlock(BlockPos pos) {
        if (!(companion.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        BlockState state = serverLevel.getBlockState(pos);

        // Get drops
        List<ItemStack> drops = Block.getDrops(state, serverLevel, pos, null, companion, ItemStack.EMPTY);

        // Spawn drops
        for (ItemStack drop : drops) {
            ItemEntity itemEntity = new ItemEntity(
                serverLevel,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                drop
            );
            itemEntity.setDefaultPickUpDelay();
            serverLevel.addFreshEntity(itemEntity);
        }

        // Remove the block
        serverLevel.destroyBlock(pos, false, companion);

        LLMoblings.LOGGER.debug("Companion mined {} at {}", state.getBlock(), pos);
    }

    private void pickupNearbyItems() {
        AABB pickupBox = companion.getBoundingBox().inflate(3.0);
        List<ItemEntity> items = companion.level().getEntitiesOfClass(ItemEntity.class, pickupBox);

        for (ItemEntity item : items) {
            if (item.isAlive() && !item.hasPickUpDelay()) {
                ItemStack stack = item.getItem();

                // Try to add to companion inventory
                ItemStack remaining = companion.addToInventory(stack);

                if (remaining.isEmpty()) {
                    item.discard();
                } else {
                    item.setItem(remaining);
                }
            }
        }
    }

    public boolean isCompleted() {
        return completed;
    }

    public boolean isFailed() {
        return failed;
    }

    public String getFailReason() {
        return failReason;
    }

    public int getMinedCount() {
        return minedCount;
    }

    public int getTargetCount() {
        return targetCount;
    }

    public String getTargetBlockName() {
        return targetBlockName;
    }

    public BlockPos getCurrentTarget() {
        return currentTarget;
    }
}
