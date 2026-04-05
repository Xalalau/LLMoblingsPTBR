package com.gblfxt.llmoblings.network;

import com.gblfxt.llmoblings.LLMoblings;
import com.gblfxt.llmoblings.entity.CompanionEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record CompanionChatPacket(String companionName, String message) implements CustomPacketPayload {

    public static final Type<CompanionChatPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(LLMoblings.MOD_ID, "companion_chat"));

    public static final StreamCodec<ByteBuf, CompanionChatPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8,
            CompanionChatPacket::companionName,
            ByteBufCodecs.STRING_UTF8,
            CompanionChatPacket::message,
            CompanionChatPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CompanionChatPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                List<CompanionEntity> companions = new ArrayList<>();
                String requestedName = packet.companionName() == null ? "" : packet.companionName().trim();

                if (requestedName.isEmpty()) {
                    companions.addAll(player.serverLevel().getEntitiesOfClass(
                            CompanionEntity.class,
                            player.getBoundingBox().inflate(64),
                            c -> c.isOwner(player)
                    ));
                } else if (player.getServer() != null) {
                    for (ServerLevel level : player.getServer().getAllLevels()) {
                        level.getEntities(LLMoblings.COMPANION.get(), entity -> {
                            if (entity instanceof CompanionEntity companion
                                    && companion.isOwner(player)
                                    && companion.getCompanionName().equalsIgnoreCase(requestedName)) {
                                companions.add(companion);
                            }
                            return true;
                        });
                    }
                }

                if (!companions.isEmpty()) {
                    // Send message to first matching companion (or all if name is empty)
                    for (CompanionEntity companion : companions) {
                        companion.onChatMessage(player, packet.message());
                        if (!requestedName.isEmpty()) break; // Only first if specific name given
                    }
                }
            }
        });
    }
}
