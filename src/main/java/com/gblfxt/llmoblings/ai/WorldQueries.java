package com.gblfxt.llmoblings.ai;

import com.gblfxt.llmoblings.ChunkLoadingManager;
import com.gblfxt.llmoblings.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

public final class WorldQueries {
    private WorldQueries() {}

    public record InventorySummary(
            int usedSlots,
            int freeSlots,
            int totalItems,
            int foodItems,
            int seedItems,
            int logItems,
            boolean hasWeapon,
            boolean hasArmor,
            boolean hasPickaxe,
            boolean hasAxe,
            boolean hasHoe,
            boolean hasShovel
    ) {}

    public record ThreatSummary(int hostileCount, double nearestHostileDistance, boolean dangerous) {}

    public record StorageSummary(int totalContainers, int chests, int barrels, int others, @Nullable BlockPos nearestStorage) {}

    public record CropSummary(int matureCrops, int emptyFarmland, int seedCount, @Nullable BlockPos nearestMatureCrop) {}

    public static InventorySummary summarizeInventory(CompanionEntity companion) {
        int usedSlots = 0;
        int totalItems = 0;
        int foodItems = 0;
        int seedItems = 0;
        int logItems = 0;
        boolean hasWeapon = false;
        boolean hasArmor = false;
        boolean hasPickaxe = false;
        boolean hasAxe = false;
        boolean hasHoe = false;
        boolean hasShovel = false;

        for (int i = 0; i < companion.getContainerSize(); i++) {
            ItemStack stack = companion.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            usedSlots++;
            totalItems += stack.getCount();
            Item item = stack.getItem();
            String itemId = BuiltInRegistries.ITEM.getKey(item).getPath();

            if (item.getFoodProperties(stack, companion) != null) {
                foodItems += stack.getCount();
            }
            if (item instanceof SwordItem || item instanceof AxeItem) {
                hasWeapon = true;
            }
            if (item instanceof ArmorItem) {
                hasArmor = true;
            }
            if (item instanceof PickaxeItem) {
                hasPickaxe = true;
            }
            if (item instanceof AxeItem) {
                hasAxe = true;
            }
            if (item instanceof HoeItem) {
                hasHoe = true;
            }
            if (item instanceof ShovelItem) {
                hasShovel = true;
            }
            if (itemId.contains("seed") || itemId.equals("carrot") || itemId.equals("potato") || itemId.equals("beetroot_seeds") || itemId.equals("nether_wart")) {
                seedItems += stack.getCount();
            }
            if (itemId.endsWith("_log") || itemId.endsWith("_wood") || itemId.contains("stem")) {
                logItems += stack.getCount();
            }
        }

        for (var slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            ItemStack equipped = companion.getItemBySlot(slot);
            if (equipped.isEmpty()) {
                continue;
            }
            Item item = equipped.getItem();
            if (item instanceof SwordItem || item instanceof AxeItem) hasWeapon = true;
            if (item instanceof ArmorItem) hasArmor = true;
            if (item instanceof PickaxeItem) hasPickaxe = true;
            if (item instanceof AxeItem) hasAxe = true;
            if (item instanceof HoeItem) hasHoe = true;
            if (item instanceof ShovelItem) hasShovel = true;
        }

        return new InventorySummary(
                usedSlots,
                companion.getContainerSize() - usedSlots,
                totalItems,
                foodItems,
                seedItems,
                logItems,
                hasWeapon,
                hasArmor,
                hasPickaxe,
                hasAxe,
                hasHoe,
                hasShovel
        );
    }

    public static ThreatSummary summarizeThreats(CompanionEntity companion, int radius) {
        java.util.List<LivingEntity> threats = companion.level().getEntitiesOfClass(
                LivingEntity.class,
                companion.getBoundingBox().inflate(radius),
                entity -> {
                    if (!entity.isAlive() || entity == companion || entity == companion.getOwner()) {
                        return false;
                    }
                    if (entity instanceof Monster) {
                        return true;
                    }
                    if (entity instanceof Mob mob) {
                        LivingEntity target = mob.getTarget();
                        return target == companion || target == companion.getOwner();
                    }
                    return false;
                }
        );

        double nearest = Double.MAX_VALUE;
        for (LivingEntity threat : threats) {
            nearest = Math.min(nearest, companion.distanceTo(threat));
        }

        if (threats.isEmpty()) {
            nearest = Double.POSITIVE_INFINITY;
        }

        return new ThreatSummary(threats.size(), nearest, !threats.isEmpty() && nearest <= 10.0);
    }

    public static StorageSummary summarizeStorage(CompanionEntity companion, BlockPos center, int radius) {
        int chests = 0;
        int barrels = 0;
        int others = 0;
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -5; y <= 5; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    if (!ChunkLoadingManager.isBlockInLoadedChunks(companion, pos)) {
                        continue;
                    }
                    BlockEntity be = companion.level().getBlockEntity(pos);
                    if (!(be instanceof Container)) {
                        continue;
                    }
                    if (be instanceof ChestBlockEntity) {
                        chests++;
                    } else if (be instanceof BarrelBlockEntity) {
                        barrels++;
                    } else {
                        others++;
                    }
                    double dist = companion.blockPosition().distSqr(pos);
                    if (dist < nearestDist) {
                        nearestDist = dist;
                        nearest = pos;
                    }
                }
            }
        }

        return new StorageSummary(chests + barrels + others, chests, barrels, others, nearest);
    }

    public static CropSummary summarizeCrops(ServerLevel level, CompanionEntity companion, BlockPos center, int radius) {
        int mature = 0;
        int emptyFarmland = 0;
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    if (!ChunkLoadingManager.isBlockInLoadedChunks(companion, pos)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (UltimineHelper.isMatureCrop(state)) {
                        mature++;
                        double dist = companion.blockPosition().distSqr(pos);
                        if (dist < nearestDist) {
                            nearestDist = dist;
                            nearest = pos;
                        }
                    }
                    if (state.is(Blocks.FARMLAND) && level.getBlockState(pos.above()).isAir()) {
                        emptyFarmland++;
                    }
                }
            }
        }

        return new CropSummary(mature, emptyFarmland, summarizeInventory(companion).seedItems(), nearest);
    }

    public static @Nullable BlockPos findNearestMatureCrop(ServerLevel level, CompanionEntity companion, BlockPos center, int radius) {
        return summarizeCrops(level, companion, center, radius).nearestMatureCrop();
    }
}
