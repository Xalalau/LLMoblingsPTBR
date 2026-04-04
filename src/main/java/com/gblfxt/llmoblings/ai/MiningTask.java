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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.gameevent.GameEvent;

import java.text.Normalizer;
import java.util.*;

public class MiningTask {
    private final CompanionEntity companion;
    private final String targetBlockName;
    private final int targetCount;
    private final int searchRadius;

    private int minedCount = 0;
    private int collectedCount = 0;
    private final int initialMatchingInventoryCount;
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
    private BlockPos animatedBreakTarget = null;
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
        this.initialMatchingInventoryCount = recountCollectedItems();

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
            case "terra", "terras", "dirt", "grass_block", "grama", "bloco_de_grama" -> "dirt";
            case "cenoura", "cenouras", "carrot", "carrots" -> "carrot";
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

    private boolean isWoodRequest() {
        return targetBlockName.contains("wood") || targetBlockName.contains("log") || targetBlockName.contains("madeira") ||
                targetBlockName.contains("tronco") || targetBlockName.contains("arvore") || targetBlockName.contains("árvore");
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
            Map.entry("terra", "dirt"),
            Map.entry("terras", "dirt"),
            Map.entry("grama", "grass_block"),
            Map.entry("bloco_de_grama", "grass_block"),
            Map.entry("carrot", "carrots"),
            Map.entry("carrots", "carrots"),
            Map.entry("cenoura", "carrots"),
            Map.entry("cenouras", "carrots"),
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
            clearBreakAnimation();
            return;
        }

        // Pick up nearby items first, then resync the count from inventory before
        // deciding if the task is done.
        pickupNearbyItems();
        collectedCount = Math.max(0, recountCollectedItems() - initialMatchingInventoryCount);

        // A gathering task should complete when the companion has actually obtained
        // enough matching items, not merely broken enough blocks.
        if (collectedCount >= targetCount) {
            completed = true;
            return;
        }

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

        // Move towards a sensible adjacent standing position instead of trying to
        // path into the block being mined.
        double blockDistance = companion.position().distanceTo(Vec3.atCenterOf(currentTarget));
        BlockPos standPos = findBestStandPosition(currentTarget);
        double standDistance = standPos != null
                ? companion.position().distanceTo(Vec3.atCenterOf(standPos))
                : blockDistance;

        if (standPos != null && standDistance > 1.75) {
            if (companion.getNavigation().isDone() || ticksAtCurrentBlock % 20 == 0) {
                companion.getNavigation().moveTo(
                    standPos.getX() + 0.5,
                    standPos.getY(),
                    standPos.getZ() + 0.5,
                    1.0
                );
            }
            ticksAtCurrentBlock = 0;
        } else if (blockDistance > 4.0) {
            // Fallback when we do not have a good standing position yet.
            if (companion.getNavigation().isDone()) {
                companion.getNavigation().moveTo(
                    currentTarget.getX() + 0.5,
                    currentTarget.getY(),
                    currentTarget.getZ() + 0.5,
                    1.0
                );
            }
            ticksAtCurrentBlock = 0;
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
                companion.swing(InteractionHand.MAIN_HAND, true);
            }

            // Calculate mining time based on block hardness and tool
            BlockState state = companion.level().getBlockState(currentTarget);
            int miningTime = calculateMiningTime(state);

            updateBreakAnimation(currentTarget, miningProgress, miningTime);
            miningProgress++;

            if (miningProgress >= miningTime) {
                // Break the block and immediately try to secure the drops.
                boolean brokeBlock = breakBlock(currentTarget);
                clearBreakAnimation();
                if (brokeBlock) {
                    minedCount++;
                    pickupNearbyItems();
                    collectedCount = Math.max(0, recountCollectedItems() - initialMatchingInventoryCount);
                } else {
                    LLMoblings.LOGGER.warn("[{}] Mining task failed to remove block at {} ({})",
                        companion.getCompanionName(), currentTarget, state.getBlock());
                }
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
            clearBreakAnimation();
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
                // Skip the first one (it's our current target). For wood gathering we
                // only queue log/wood/stem blocks, not leaves.
                for (int i = 1; i < tree.size() && miningQueue.size() < 64; i++) {
                    BlockPos pos = tree.get(i);
                    String queuedId = BuiltInRegistries.BLOCK.getKey(companion.level().getBlockState(pos).getBlock()).getPath();
                    boolean isWoodPiece = queuedId.endsWith("_log") || queuedId.endsWith("_wood") || queuedId.endsWith("_stem") || queuedId.endsWith("_hyphae");
                    if (isWoodPiece && isSafeToMine(pos)) {
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

        // Direct match, but only keep it if the block still makes sense for the
        // requested resource.
        if (targetBlocks.contains(state.getBlock()) && wouldYieldRequestedItem(pos, state)) {
            return true;
        }

        // During tree felling, keep the task focused on actual wood blocks.
        if (isTreeFelling) {
            String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            if ((blockId.endsWith("_log") || blockId.endsWith("_wood") || blockId.endsWith("_stem") || blockId.endsWith("_hyphae"))
                    && wouldYieldRequestedItem(pos, state)) {
                return true;
            }
        }

        // During vein mining, accept deepslate variants if they still correspond to
        // the requested ore/item.
        if (isVeinMining) {
            String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            for (Block target : targetBlocks) {
                String targetId = BuiltInRegistries.BLOCK.getKey(target).getPath();
                String normalizedTarget = targetId.replace("deepslate_", "");
                String normalizedBlock = blockId.replace("deepslate_", "");
                if (normalizedTarget.equals(normalizedBlock) && wouldYieldRequestedItem(pos, state)) {
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

        if (isWoodRequest()) {
            BlockPos visibleLog = findNearestVisibleLog(companionPos, effectiveRadius);
            if (visibleLog != null) {
                return visibleLog;
            }
        }

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

                        if (targetBlocks.contains(state.getBlock()) && wouldYieldRequestedItem(checkPos, state)) {
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

    private BlockPos findNearestVisibleLog(BlockPos companionPos, int effectiveRadius) {
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (int x = -effectiveRadius; x <= effectiveRadius; x++) {
            for (int z = -effectiveRadius; z <= effectiveRadius; z++) {
                for (int y = -3; y <= 20; y++) {
                    BlockPos checkPos = companionPos.offset(x, y, z);
                    if (!ChunkLoadingManager.isBlockInLoadedChunks(companion, checkPos)) {
                        continue;
                    }
                    BlockState state = companion.level().getBlockState(checkPos);
                    String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                    if (!(blockId.endsWith("_log") || blockId.endsWith("_wood") || blockId.endsWith("_stem"))) {
                        continue;
                    }
                    if (!isSafeToMine(checkPos) || !isReachable(checkPos)) {
                        continue;
                    }
                    // Prefer outdoor or forest logs.
                    if (!companion.level().canSeeSky(checkPos.above()) && !companion.level().canSeeSky(checkPos)) {
                        continue;
                    }
                    double dist = companionPos.distSqr(checkPos);
                    if (dist < nearestDist) {
                        nearestDist = dist;
                        nearest = checkPos;
                    }
                }
            }
        }

        return nearest;
    }

    private boolean isReachable(BlockPos pos) {
        return findBestStandPosition(pos) != null;
    }

    private BlockPos findBestStandPosition(BlockPos target) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (BlockPos candidate : getStandPositionCandidates(target)) {
            if (!canStandAt(candidate)) {
                continue;
            }
            double dist = companion.position().distanceTo(Vec3.atCenterOf(candidate));
            if (dist < bestDistance) {
                bestDistance = dist;
                best = candidate;
            }
        }

        return best;
    }

    private List<BlockPos> getStandPositionCandidates(BlockPos target) {
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos adjacent : new BlockPos[]{target.north(), target.south(), target.east(), target.west()}) {
            candidates.add(adjacent);
            candidates.add(adjacent.above());
        }
        candidates.add(companion.blockPosition());
        return candidates;
    }

    private boolean canStandAt(BlockPos feetPos) {
        BlockState feet = companion.level().getBlockState(feetPos);
        BlockState head = companion.level().getBlockState(feetPos.above());
        BlockState ground = companion.level().getBlockState(feetPos.below());
        return (!feet.isSolid() || feet.isAir()) && (!head.isSolid() || head.isAir()) && ground.isSolid();
    }

    private boolean wouldYieldRequestedItem(BlockPos pos, BlockState state) {
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();

        if (isWoodRequest()) {
            return blockId.endsWith("_log") || blockId.endsWith("_wood") || blockId.endsWith("_stem") || blockId.endsWith("_hyphae");
        }

        if (targetBlockName.equals("dirt")) {
            return blockId.equals("dirt") || blockId.equals("grass_block") || blockId.equals("coarse_dirt")
                    || blockId.equals("rooted_dirt") || blockId.equals("dirt_path") || blockId.equals("podzol")
                    || blockId.equals("mycelium");
        }

        if (!(companion.level() instanceof ServerLevel serverLevel)) {
            return targetBlocks.contains(state.getBlock());
        }

        ItemStack tool = companion.getMainHandItem().copy();
        List<ItemStack> drops = Block.getDrops(state, serverLevel, pos, null, companion, tool);
        for (ItemStack drop : drops) {
            if (countMatchingItems(drop) > 0) {
                return true;
            }
        }

        // Fallback to block identity for things like ores that may still be worth mining
        // even when the current tool simulation is imperfect.
        if (targetBlocks.contains(state.getBlock())) {
            return true;
        }

        return false;
    }

    private int recountCollectedItems() {
        int total = 0;
        for (int i = 0; i < companion.getContainerSize(); i++) {
            total += countMatchingItems(companion.getItem(i));
        }
        return total;
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

    private boolean breakBlock(BlockPos pos) {
        if (!(companion.level() instanceof ServerLevel serverLevel)) {
            return false;
        }

        BlockState state = serverLevel.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }

        BlockEntity blockEntity = serverLevel.getBlockEntity(pos);
        ItemStack tool = companion.getMainHandItem().copy();
        List<ItemStack> drops = Block.getDrops(state, serverLevel, pos, blockEntity, companion, tool);

        // Visual and audio feedback first.
        serverLevel.levelEvent(2001, pos, Block.getId(state));

        boolean removed = serverLevel.destroyBlock(pos, false, companion);
        BlockState afterState = serverLevel.getBlockState(pos);

        if (!afterState.isAir()) {
            removed = serverLevel.removeBlock(pos, false) || removed;
            afterState = serverLevel.getBlockState(pos);
        }

        if (!afterState.isAir()) {
            removed = serverLevel.setBlock(pos, Blocks.AIR.defaultBlockState(), 11) || removed;
            afterState = serverLevel.getBlockState(pos);
        }

        if (!afterState.isAir()) {
            LLMoblings.LOGGER.warn("[{}] Failed to remove block {} at {} while mining; state after attempts is {}",
                    companion.getCompanionName(), state.getBlock(), pos, afterState.getBlock());
            return false;
        }

        serverLevel.sendBlockUpdated(pos, state, Blocks.AIR.defaultBlockState(), 3);
        serverLevel.gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(companion, state));

        for (ItemStack drop : drops) {
            ItemStack toInsert = drop.copy();
            int originalCount = toInsert.getCount();
            ItemStack remaining = companion.addToInventory(toInsert);
            int inserted = originalCount - remaining.getCount();
            if (inserted > 0) {
                collectedCount += countMatchingItems(drop.copyWithCount(inserted));
            }
            if (!remaining.isEmpty()) {
                ItemEntity itemEntity = new ItemEntity(
                    serverLevel,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    remaining
                );
                itemEntity.setNoPickUpDelay();
                serverLevel.addFreshEntity(itemEntity);
            }
        }

        LLMoblings.LOGGER.debug("[{}] Companion mined {} at {}",
                companion.getCompanionName(), state.getBlock(), pos);
        return true;
    }

    private void updateBreakAnimation(BlockPos target, int currentProgress, int miningTime) {
        if (!(companion.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        if (target == null) {
            clearBreakAnimation();
            return;
        }

        if (animatedBreakTarget != null && !animatedBreakTarget.equals(target)) {
            serverLevel.destroyBlockProgress(companion.getId(), animatedBreakTarget, -1);
        }

        int stage = Math.max(0, Math.min(9, (currentProgress * 10) / Math.max(1, miningTime)));
        serverLevel.destroyBlockProgress(companion.getId(), target, stage);
        animatedBreakTarget = target;
    }

    private void clearBreakAnimation() {
        if (animatedBreakTarget == null) {
            return;
        }
        if (companion.level() instanceof ServerLevel serverLevel) {
            serverLevel.destroyBlockProgress(companion.getId(), animatedBreakTarget, -1);
        }
        animatedBreakTarget = null;
    }

    private void pickupNearbyItems() {
        AABB pickupBox = companion.getBoundingBox().inflate(3.0);
        List<ItemEntity> items = companion.level().getEntitiesOfClass(ItemEntity.class, pickupBox);

        for (ItemEntity item : items) {
            if (!item.isAlive()) {
                continue;
            }

            ItemStack stack = item.getItem();
            int originalCount = stack.getCount();

            // Try to add to companion inventory. We intentionally do not wait for the
            // vanilla pickup delay here because this task is responsible for finishing
            // the gather/mining action reliably.
            ItemStack remaining = companion.addToInventory(stack.copy());
            int inserted = originalCount - remaining.getCount();
            if (inserted > 0) {
                collectedCount += countMatchingItems(stack.copyWithCount(inserted));
            }

            if (remaining.isEmpty()) {
                item.discard();
            } else {
                item.setItem(remaining);
            }
        }
    }

    private int countMatchingItems(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }

        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();

        if (isWoodRequest()) {
            if (itemId.endsWith("_log") || itemId.endsWith("_wood") || itemId.endsWith("_stem") || itemId.endsWith("_hyphae")) {
                return stack.getCount();
            }
            return 0;
        }

        return switch (targetBlockName) {
            case "stone" -> itemId.equals("stone") || itemId.equals("cobblestone") ? stack.getCount() : 0;
            case "dirt" -> itemId.equals("dirt") || itemId.equals("grass_block") || itemId.equals("coarse_dirt")
                    || itemId.equals("rooted_dirt") || itemId.equals("podzol") || itemId.equals("mycelium") ? stack.getCount() : 0;
            case "coal" -> itemId.equals("coal") ? stack.getCount() : 0;
            case "iron" -> itemId.equals("raw_iron") || itemId.equals("iron_ore") || itemId.equals("deepslate_iron_ore") ? stack.getCount() : 0;
            case "gold" -> itemId.equals("raw_gold") || itemId.equals("gold_ore") || itemId.equals("deepslate_gold_ore") ? stack.getCount() : 0;
            case "diamond" -> itemId.equals("diamond") || itemId.equals("diamond_ore") || itemId.equals("deepslate_diamond_ore") ? stack.getCount() : 0;
            case "copper" -> itemId.equals("raw_copper") || itemId.equals("copper_ore") || itemId.equals("deepslate_copper_ore") ? stack.getCount() : 0;
            default -> {
                for (Block block : targetBlocks) {
                    String blockId = BuiltInRegistries.BLOCK.getKey(block).getPath();
                    if (itemId.equals(blockId) || itemId.contains(targetBlockName) || targetBlockName.contains(itemId)) {
                        yield stack.getCount();
                    }
                }
                yield 0;
            }
        };
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
        return collectedCount;
    }

    public int getBrokenBlockCount() {
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
