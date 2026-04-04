package com.gblfxt.llmoblings.ai;

import com.gblfxt.llmoblings.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Builds a compact world-state context string for injection into LLM prompts.
 */
public class WorldStateBuilder {

    public static String buildContext(CompanionEntity companion) {
        SpatialAwareness.AreaSummary area = SpatialAwareness.summarizeArea(companion, 24);
        WorldQueries.InventorySummary inventory = WorldQueries.summarizeInventory(companion);
        WorldQueries.ThreatSummary threats = WorldQueries.summarizeThreats(companion, 16);
        WorldQueries.StorageSummary storage = WorldQueries.summarizeStorage(companion, companion.blockPosition(), 24);

        BlockPos pos = area.position();
        StringBuilder sb = new StringBuilder();
        sb.append("[WORLD STATE] ");
        sb.append("Pos: (").append(pos.getX()).append(", ").append(pos.getY()).append(", ").append(pos.getZ()).append(")");
        sb.append(" | Biome: ").append(area.biome());
        sb.append(" | Light: ").append(area.lightLevel());
        sb.append(" | HP: ").append(String.format("%.0f/%.0f", companion.getHealth(), companion.getMaxHealth()));

        ItemStack mainHand = companion.getMainHandItem();
        if (!mainHand.isEmpty()) {
            sb.append(" | Wielding: ").append(mainHand.getHoverName().getString());
        }

        sb.append(" | Inv: ").append(inventory.totalItems()).append(" itens, ")
                .append(inventory.freeSlots()).append(" slots livres")
                .append(", comida ").append(inventory.foodItems())
                .append(", sementes ").append(inventory.seedItems());

        if (storage.totalContainers() > 0) {
            sb.append(" | Storage: ").append(storage.totalContainers()).append(" containers próximos");
        }

        if (companion.level() instanceof ServerLevel serverLevel) {
            WorldQueries.CropSummary crops = WorldQueries.summarizeCrops(serverLevel, companion, companion.blockPosition(), 20);
            if (crops.matureCrops() > 0 || crops.emptyFarmland() > 0) {
                sb.append(" | Farm: maduras ").append(crops.matureCrops())
                        .append(", canteiros vazios ").append(crops.emptyFarmland());
            }
        }

        List<SpatialAwareness.MobInfo> mobs = area.nearbyMobs();
        if (!mobs.isEmpty()) {
            sb.append(threats.dangerous() ? " | DANGER:" : " | Nearby:");
            mobs.stream()
                    .sorted((a, b) -> {
                        if (a.hostile() != b.hostile()) return a.hostile() ? -1 : 1;
                        return Double.compare(a.distance(), b.distance());
                    })
                    .limit(3)
                    .forEach(mob -> {
                        sb.append(" ").append(mob.name());
                        if (mob.count() > 1) sb.append(" x").append(mob.count());
                        sb.append(" @").append(String.format("%.0fm", mob.distance()));
                    });
        }

        if (threats.hostileCount() > 0) {
            sb.append(" | Hostis: ").append(threats.hostileCount());
        }

        return sb.toString();
    }
}
