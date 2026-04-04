package com.gblfxt.llmoblings.ai;

import com.gblfxt.llmoblings.ChunkLoadingManager;
import com.gblfxt.llmoblings.LLMoblings;
import com.gblfxt.llmoblings.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Deterministic farming loop: scan -> approach -> harvest -> replant -> collect.
 */
public class FarmingTask {
    private final CompanionEntity companion;
    private final int radius;
    private final BlockPos farmOrigin;
    private final @Nullable BlockPos storagePos;

    private boolean completed = false;
    private boolean failed = false;
    private String failReason = null;

    private @Nullable BlockPos currentCrop = null;
    private @Nullable BlockPos explorationTarget = null;
    private int explorationStep = 0;
    private int ticksMoving = 0;
    private int ticksWithoutHarvest = 0;
    private int harvestedCount = 0;
    private int failedCropAttempts = 0;

    private static final int MAX_EXPLORATION_STEPS = 8;
    private static final int MOVE_TIMEOUT_TICKS = 160;
    private static final int REACH_DISTANCE = 3;

    public FarmingTask(CompanionEntity companion, int radius) {
        this.companion = companion;
        this.radius = Math.max(12, radius);
        this.farmOrigin = companion.blockPosition();
        WorldQueries.StorageSummary storage = WorldQueries.summarizeStorage(companion, farmOrigin, Math.min(this.radius, 24));
        this.storagePos = storage.nearestStorage();
    }

    public void tick() {
        if (completed || failed) {
            return;
        }
        if (!(companion.level() instanceof ServerLevel serverLevel)) {
            failed = true;
            failReason = "Não consegui acessar o mundo para cuidar da fazenda.";
            return;
        }

        pickupNearbyDrops();

        WorldQueries.ThreatSummary threats = WorldQueries.summarizeThreats(companion, 8);
        if (threats.dangerous()) {
            failed = true;
            failReason = "Abortei o trabalho na fazenda porque há inimigos perigosos por perto.";
            return;
        }

        maybeStoreProduce(serverLevel);

        if (currentCrop != null && !UltimineHelper.isMatureCrop(serverLevel.getBlockState(currentCrop))) {
            currentCrop = null;
        }

        if (currentCrop == null) {
            currentCrop = WorldQueries.findNearestMatureCrop(serverLevel, companion, companion.blockPosition(), radius);
            if (currentCrop == null) {
                ticksWithoutHarvest++;
                if (handleExploration(serverLevel)) {
                    return;
                }
                completed = true;
                return;
            }
            explorationTarget = null;
            ticksMoving = 0;
        }

        double distance = companion.position().distanceTo(Vec3.atCenterOf(currentCrop));
        if (distance > REACH_DISTANCE) {
            moveTo(currentCrop);
            return;
        }

        if (!ChunkLoadingManager.isBlockInLoadedChunks(companion, currentCrop)) {
            currentCrop = null;
            return;
        }

        boolean success = UltimineHelper.harvestAndReplant(serverLevel, currentCrop, companion);
        if (success) {
            harvestedCount++;
            ticksWithoutHarvest = 0;
            failedCropAttempts = 0;
            pickupNearbyDrops();
        } else {
            failedCropAttempts++;
            if (failedCropAttempts >= 5) {
                completed = true;
            }
        }
        currentCrop = null;
    }

    private void moveTo(BlockPos target) {
        if (companion.getNavigation().isDone() || ticksMoving % 20 == 0) {
            companion.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
        }
        ticksMoving++;
        if (ticksMoving > MOVE_TIMEOUT_TICKS) {
            currentCrop = null;
            ticksMoving = 0;
        }
    }

    private boolean handleExploration(ServerLevel serverLevel) {
        WorldQueries.CropSummary cropSummary = WorldQueries.summarizeCrops(serverLevel, companion, farmOrigin, radius);
        if (cropSummary.matureCrops() == 0 && cropSummary.emptyFarmland() == 0) {
            return false;
        }

        if (explorationStep >= MAX_EXPLORATION_STEPS) {
            return false;
        }

        if (explorationTarget == null) {
            explorationTarget = chooseExplorationTarget(serverLevel);
            ticksMoving = 0;
            if (explorationTarget == null) {
                explorationStep = MAX_EXPLORATION_STEPS;
                return false;
            }
        }

        double distance = companion.position().distanceTo(Vec3.atCenterOf(explorationTarget));
        if (distance <= 4) {
            explorationTarget = null;
            explorationStep++;
            ticksMoving = 0;
            return true;
        }

        moveTo(explorationTarget);
        return true;
    }

    private @Nullable BlockPos chooseExplorationTarget(ServerLevel serverLevel) {
        int ring = explorationStep / 4 + 1;
        int spoke = explorationStep % 4;
        double angle = Math.toRadians(spoke * 90.0);
        int distance = Math.min(8 * ring, radius);
        int x = farmOrigin.getX() + (int) Math.round(Math.cos(angle) * distance);
        int z = farmOrigin.getZ() + (int) Math.round(Math.sin(angle) * distance);
        int y = serverLevel.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }

    private void pickupNearbyDrops() {
        AABB box = companion.getBoundingBox().inflate(3.0);
        java.util.List<ItemEntity> items = companion.level().getEntitiesOfClass(ItemEntity.class, box);
        for (ItemEntity item : items) {
            if (!item.isAlive() || item.hasPickUpDelay()) {
                continue;
            }
            ItemStack remaining = companion.addToInventory(item.getItem());
            if (remaining.isEmpty()) {
                item.discard();
            } else {
                item.setItem(remaining);
            }
        }
    }

    private void maybeStoreProduce(ServerLevel level) {
        if (storagePos == null) {
            return;
        }
        WorldQueries.InventorySummary inventory = WorldQueries.summarizeInventory(companion);
        if (inventory.freeSlots() > 1) {
            return;
        }
        if (companion.position().distanceTo(Vec3.atCenterOf(storagePos)) > 4.5) {
            return;
        }

        BlockEntity be = level.getBlockEntity(storagePos);
        if (!(be instanceof Container container)) {
            return;
        }

        for (int i = 0; i < companion.getContainerSize(); i++) {
            ItemStack stack = companion.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (!isFarmItem(stack)) {
                continue;
            }
            ItemStack remainder = moveIntoContainer(container, stack.copy());
            int moved = stack.getCount() - remainder.getCount();
            if (moved > 0) {
                stack.shrink(moved);
                if (stack.isEmpty()) {
                    companion.setItem(i, ItemStack.EMPTY);
                }
            }
        }
        container.setChanged();
    }

    private boolean isFarmItem(ItemStack stack) {
        String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return itemId.contains("wheat") || itemId.contains("carrot") || itemId.contains("potato") ||
                itemId.contains("beetroot") || itemId.contains("melon") || itemId.contains("pumpkin") ||
                itemId.contains("berry") || itemId.contains("seed") || itemId.contains("wart");
    }

    private ItemStack moveIntoContainer(Container container, ItemStack input) {
        ItemStack remainder = input.copy();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty()) {
                container.setItem(slot, remainder.copy());
                return ItemStack.EMPTY;
            }
            if (ItemStack.isSameItemSameComponents(existing, remainder) && existing.getCount() < existing.getMaxStackSize()) {
                int transferable = Math.min(existing.getMaxStackSize() - existing.getCount(), remainder.getCount());
                existing.grow(transferable);
                remainder.shrink(transferable);
                if (remainder.isEmpty()) {
                    return ItemStack.EMPTY;
                }
            }
        }
        return remainder;
    }

    public boolean isCompleted() {
        return completed;
    }

    public boolean isFailed() {
        return failed;
    }

    public String getFailReason() {
        return failReason != null ? failReason : "Não consegui terminar o trabalho na fazenda.";
    }

    public int getHarvestedCount() {
        return harvestedCount;
    }

    public String getProgressReport() {
        if (currentCrop != null) {
            return "Estou cuidando da fazenda. Já colhi " + harvestedCount + " colheitas e agora vou até o próximo canteiro.";
        }
        return "Estou cuidando da fazenda. Já colhi " + harvestedCount + " colheitas até agora.";
    }
}
