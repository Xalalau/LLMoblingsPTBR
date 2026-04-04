package com.gblfxt.llmoblings.command;

import com.gblfxt.llmoblings.Config;
import com.gblfxt.llmoblings.LLMoblings;
import com.gblfxt.llmoblings.data.CompanionSaveData;
import com.gblfxt.llmoblings.entity.CompanionEntity;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public class CompanionCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("companion")
                .then(Commands.literal("summon")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> summonCompanion(ctx, StringArgumentType.getString(ctx, "name")))
                        )
                        .executes(ctx -> summonCompanion(ctx, "Companion"))
                )
                .then(Commands.literal("dismiss")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> dismissCompanion(ctx, StringArgumentType.getString(ctx, "name")))
                        )
                        .executes(ctx -> dismissAllCompanions(ctx))
                )
                .then(Commands.literal("list")
                        .executes(CompanionCommand::listCompanions)
                )
                .then(Commands.literal("help")
                        .executes(CompanionCommand::showHelp)
                )
                .executes(CompanionCommand::showHelp)
        );
    }

    private static int summonCompanion(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Este comando só pode ser usado por jogadores."));
            return 0;
        }

        // Check companion limit
        List<CompanionEntity> existing = player.level().getEntitiesOfClass(
                CompanionEntity.class,
                player.getBoundingBox().inflate(256),
                c -> c.isOwner(player)
        );

        int maxCompanions = Config.MAX_COMPANIONS_PER_PLAYER.get();
        if (existing.size() >= maxCompanions) {
            source.sendFailure(Component.literal("Você já tem " + maxCompanions + " companions. Dispense um primeiro."));
            return 0;
        }

        // Check if name is already taken
        boolean nameTaken = existing.stream()
                .anyMatch(c -> c.getCompanionName().equalsIgnoreCase(name));
        if (nameTaken) {
            source.sendFailure(Component.literal("Você já tem um companion chamado '" + name + "'."));
            return 0;
        }

        // Spawn companion
        CompanionEntity companion = new CompanionEntity(LLMoblings.COMPANION.get(), player.level());
        companion.setCompanionName(name);
        companion.setOwner(player);
        companion.setPos(player.getX() + 1, player.getY(), player.getZ() + 1);

        // Try to load saved data for this companion
        if (player.level() instanceof ServerLevel serverLevel) {
            CompanionSaveData saveData = CompanionSaveData.get(serverLevel);
            CompoundTag savedData = saveData.loadCompanion(player.getUUID(), name);
            if (savedData != null) {
                // Load the companion's previous inventory, armor, etc.
                companion.readAdditionalSaveData(savedData);
                // Re-set name and owner in case they were overwritten
                companion.setCompanionName(name);
                companion.setOwner(player);
                source.sendSuccess(() -> Component.literal("Bem-vindo de volta, " + name + "! (Inventário restaurado)"), false);
            } else {
                source.sendSuccess(() -> Component.literal("Companion '" + name + "' invocado! Use @" + name + " <mensagem> para falar com ele."), false);
            }
        } else {
            source.sendSuccess(() -> Component.literal("Companion '" + name + "' invocado! Use @" + name + " <mensagem> para falar com ele."), false);
        }

        player.level().addFreshEntity(companion);
        LLMoblings.LOGGER.info("Player {} summoned companion '{}'", player.getName().getString(), name);

        return 1;
    }

    private static int dismissCompanion(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Este comando só pode ser usado por jogadores."));
            return 0;
        }

        List<CompanionEntity> companions = player.level().getEntitiesOfClass(
                CompanionEntity.class,
                player.getBoundingBox().inflate(256),
                c -> c.isOwner(player) && c.getCompanionName().equalsIgnoreCase(name)
        );

        if (companions.isEmpty()) {
            source.sendFailure(Component.literal("Nenhum companion chamado '" + name + "' foi encontrado."));
            return 0;
        }

        // Save companion data before dismissing
        if (player.level() instanceof ServerLevel serverLevel) {
            CompanionSaveData saveData = CompanionSaveData.get(serverLevel);
            for (CompanionEntity companion : companions) {
                CompoundTag data = new CompoundTag();
                companion.addAdditionalSaveData(data);
                data.putString("Name", companion.getCompanionName());
                saveData.saveCompanion(player.getUUID(), companion.getCompanionName(), data);
                companion.discard();
            }
        } else {
            for (CompanionEntity companion : companions) {
                companion.discard();
            }
        }

        source.sendSuccess(() -> Component.literal("Companion '" + name + "' dispensado. (Inventário salvo)"), false);
        return 1;
    }

    private static int dismissAllCompanions(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Este comando só pode ser usado por jogadores."));
            return 0;
        }

        List<CompanionEntity> companions = player.level().getEntitiesOfClass(
                CompanionEntity.class,
                player.getBoundingBox().inflate(256),
                c -> c.isOwner(player)
        );

        if (companions.isEmpty()) {
            source.sendFailure(Component.literal("Você não tem companions para dispensar."));
            return 0;
        }

        int count = companions.size();

        // Save all companions before dismissing
        if (player.level() instanceof ServerLevel serverLevel) {
            CompanionSaveData saveData = CompanionSaveData.get(serverLevel);
            for (CompanionEntity companion : companions) {
                CompoundTag data = new CompoundTag();
                companion.addAdditionalSaveData(data);
                data.putString("Name", companion.getCompanionName());
                saveData.saveCompanion(player.getUUID(), companion.getCompanionName(), data);
                companion.discard();
            }
        } else {
            for (CompanionEntity companion : companions) {
                companion.discard();
            }
        }

        source.sendSuccess(() -> Component.literal("Dispensei " + count + " companion(s). (Inventário salvo)"), false);
        return 1;
    }

    private static int listCompanions(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Este comando só pode ser usado por jogadores."));
            return 0;
        }

        // Search across ALL dimensions for companions
        java.util.ArrayList<CompanionEntity> allCompanions = new java.util.ArrayList<>();

        if (player.getServer() != null) {
            for (ServerLevel level : player.getServer().getAllLevels()) {
                // Get all entities of type CompanionEntity in this level
                level.getEntities(LLMoblings.COMPANION.get(), entity -> {
                    if (entity instanceof CompanionEntity companion && companion.isOwner(player)) {
                        allCompanions.add(companion);
                    }
                    return true; // Continue iterating
                });
            }
        }

        if (allCompanions.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Você não tem companions. Use /companion summon <nome> para criar um."), false);
            return 1;
        }

        StringBuilder sb = new StringBuilder("Seus companions:\n");
        for (CompanionEntity companion : allCompanions) {
            String state = companion.getAIController() != null ?
                    switch (companion.getAIController().getCurrentState()) {
                        case IDLE -> "PARADO";
                        case FOLLOWING -> "SEGUINDO";
                        case GOING_TO -> "INDO";
                        case MINING -> "MINERANDO";
                        case FARMING -> "FAZENDO FARM";
                        case ATTACKING -> "ATACANDO";
                        case DEFENDING -> "DEFENDENDO";
                        case AUTONOMOUS -> "AUTÔNOMO";
                        case BUILDING -> "CONSTRUINDO";
                    } : "DESCONHECIDO";

            // Get dimension name
            String dimName = companion.level().dimension().location().toString();
            if (dimName.equals("minecraft:overworld")) dimName = "Overworld";
            else if (dimName.equals("minecraft:the_nether")) dimName = "Nether";
            else if (dimName.equals("minecraft:the_end")) dimName = "The End";

            // Calculate distance only if same dimension
            String distStr;
            if (companion.level().dimension().equals(player.level().dimension())) {
                double dist = player.distanceTo(companion);
                distStr = " (" + (int) dist + "m de distância)";
            } else {
                distStr = " [" + dimName + "]";
            }

            sb.append(" - ").append(companion.getCompanionName())
                    .append(" (").append(state).append(")")
                    .append(" Vida: ").append((int) companion.getHealth())
                    .append("/").append((int) companion.getMaxHealth())
                    .append(" em [").append((int) companion.getX())
                    .append(", ").append((int) companion.getY())
                    .append(", ").append((int) companion.getZ()).append("]")
                    .append(distStr)
                    .append("\n");
        }

        source.sendSuccess(() -> Component.literal(sb.toString().trim()), false);
        return 1;
    }

    private static int showHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        String help = """
Comandos do LLMoblings:
  /companion summon <nome> - Invoca um novo companion de IA
  /companion dismiss <nome> - Dispensa um companion específico
  /companion dismiss - Dispensa todos os companions
  /companion list - Lista os seus companions

Fale com os companions usando: @<nome> <mensagem>
Exemplo: @Alex me siga""";

        source.sendSuccess(() -> Component.literal(help), false);
        return 1;
    }
}
