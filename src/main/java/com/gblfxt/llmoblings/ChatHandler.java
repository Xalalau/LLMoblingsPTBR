package com.gblfxt.llmoblings;

import com.gblfxt.llmoblings.compat.FTBTeamsIntegration;
import com.gblfxt.llmoblings.entity.CompanionEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ServerChatEvent;

import java.util.ArrayList;
import java.util.List;

@EventBusSubscriber(modid = LLMoblings.MOD_ID)
public class ChatHandler {

    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        String message = event.getRawText();
        String prefix = Config.CHAT_PREFIX.get();

        if (!message.startsWith(prefix)) {
            return;
        }

        ServerPlayer player = event.getPlayer();
        String afterPrefix = message.substring(prefix.length()).trim();
        boolean allowOtherPlayers = Config.ALLOW_OTHER_PLAYER_INTERACTION.get();

        LLMoblings.LOGGER.debug("Chat message with prefix from {}: {}", player.getName().getString(), afterPrefix);

        String targetName = "";
        String actualMessage = afterPrefix;
        boolean explicitTarget = false;

        int spaceIndex = afterPrefix.indexOf(' ');
        if (spaceIndex > 0) {
            String potentialName = afterPrefix.substring(0, spaceIndex).trim();
            String restOfMessage = afterPrefix.substring(spaceIndex + 1).trim();
            List<CompanionEntity> namedCompanions = findAccessibleCompanionsAnyDistance(player, potentialName, allowOtherPlayers);

            if (!namedCompanions.isEmpty()) {
                explicitTarget = true;
                targetName = potentialName;
                actualMessage = restOfMessage;
            }
        }

        List<CompanionEntity> companions = explicitTarget
                ? findAccessibleCompanionsAnyDistance(player, targetName, allowOtherPlayers)
                : findNearbyAccessibleCompanions(player, allowOtherPlayers);

        if (companions.isEmpty()) {
            LLMoblings.LOGGER.debug("No companions found for player {} with target name '{}' (explicitTarget={})",
                    player.getName().getString(), targetName, explicitTarget);
            return;
        }

        for (CompanionEntity companion : companions) {
            boolean isOwner = companion.isOwner(player);
            boolean isTeammate = false;

            if (!isOwner && FTBTeamsIntegration.isModLoaded()) {
                Player owner = companion.getOwner();
                if (owner != null) {
                    isTeammate = FTBTeamsIntegration.areOnSameTeam(player, owner);
                    if (isTeammate) {
                        LLMoblings.LOGGER.info("[{}] {} is a teammate of owner - granting command access",
                                companion.getCompanionName(), player.getName().getString());
                    }
                }
            }

            LLMoblings.LOGGER.info("[{}] Received message from {} (owner={}, teammate={}, explicitTarget={}): {}",
                    companion.getCompanionName(), player.getName().getString(), isOwner, isTeammate, explicitTarget, actualMessage);

            companion.onChatMessage(player, actualMessage, isOwner || isTeammate);

            if (explicitTarget) {
                break;
            }
        }

        event.setCanceled(true);
    }

    private static List<CompanionEntity> findNearbyAccessibleCompanions(ServerPlayer player, boolean allowOtherPlayers) {
        return player.serverLevel().getEntitiesOfClass(
                CompanionEntity.class,
                player.getBoundingBox().inflate(64),
                companion -> canInteract(player, companion, allowOtherPlayers)
        );
    }

    private static List<CompanionEntity> findAccessibleCompanionsAnyDistance(ServerPlayer player, String targetName, boolean allowOtherPlayers) {
        List<CompanionEntity> companions = new ArrayList<>();
        if (player.getServer() == null || targetName == null || targetName.isBlank()) {
            return companions;
        }

        for (ServerLevel level : player.getServer().getAllLevels()) {
            level.getEntities(LLMoblings.COMPANION.get(), entity -> {
                if (!(entity instanceof CompanionEntity companion)) {
                    return true;
                }
                if (!companion.getCompanionName().equalsIgnoreCase(targetName)) {
                    return true;
                }
                if (!canInteract(player, companion, allowOtherPlayers)) {
                    return true;
                }
                companions.add(companion);
                return true;
            });
        }
        return companions;
    }

    private static boolean canInteract(ServerPlayer player, CompanionEntity companion, boolean allowOtherPlayers) {
        if (companion.isOwner(player) || allowOtherPlayers) {
            return true;
        }
        if (FTBTeamsIntegration.isModLoaded()) {
            Player owner = companion.getOwner();
            return owner != null && FTBTeamsIntegration.areOnSameTeam(player, owner);
        }
        return false;
    }
}
