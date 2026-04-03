package com.gblfxt.llmoblings.ai;

import com.gblfxt.llmoblings.Config;
import com.gblfxt.llmoblings.LLMoblings;
import com.google.gson.JsonObject;
import com.gblfxt.llmoblings.ai.blueprints.CottageBlueprint;
import com.gblfxt.llmoblings.compat.AE2Integration;
import com.gblfxt.llmoblings.compat.BuildingGadgetsIntegration;
import com.gblfxt.llmoblings.compat.CobblemonIntegration;
import com.gblfxt.llmoblings.compat.SophisticatedBackpacksIntegration;
import com.gblfxt.llmoblings.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.entity.EquipmentSlot;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public class CompanionAI {
    private final CompanionEntity companion;
    private final OllamaClient ollamaClient;
    private final CompanionPersonality personality;

    // Current state
    private AIState currentState = AIState.IDLE;
    private CompletableFuture<CompanionAction> pendingAction = null;
    private CompletableFuture<Void> pendingLoopFuture = null;

    // Task-specific data
    private BlockPos targetPos = null;
    private Entity targetEntity = null;
    private MiningTask miningTask = null;
    private AutonomousTask autonomousTask = null;
    private BuildingTask buildingTask = null;

    // Pokemon buddy (Cobblemon integration)
    private Entity pokemonBuddy = null;
    private String pokemonBuddyName = null;

    // Owner tracking for greetings
    private boolean ownerWasNearby = false;

    // Home position tracking
    private BlockPos homePos = null;
    private BlockPos bedPos = null;

    // Track who gave the last command (for follow, etc.)
    private Player commandGiver = null;

    // Keep lightweight context so short follow-up commands like "vai atrás dela"
    // can continue the last explicit task instead of being reinterpreted badly.
    private CompanionAction lastExplicitCommand = null;
    private int lastExplicitCommandTick = 0;

    public CompanionAI(CompanionEntity companion) {
        this.companion = companion;
        this.ollamaClient = new OllamaClient(companion.getCompanionName());
        this.personality = new CompanionPersonality(companion);
    }

    public void tick() {
        // Tick personality for random chatter/emotes
        personality.tick();

        // Tick Pokemon buddy to follow companion
        tickPokemonBuddy();

        // Check if owner just came nearby (for greetings)
        checkOwnerProximity();

        // Check for pending loop completion (errors only — loop handles actions itself)
        if (pendingLoopFuture != null && pendingLoopFuture.isDone()) {
            try {
                pendingLoopFuture.get(); // surface any exceptions
            } catch (Exception e) {
                LLMoblings.LOGGER.error("Error in action loop: ", e);
            }
            pendingLoopFuture = null;
        }

        // Check for pending single-shot LLM response (non-loop mode)
        if (pendingAction != null && pendingAction.isDone()) {
            try {
                CompanionAction action = pendingAction.get();
                executeAction(action);
            } catch (Exception e) {
                LLMoblings.LOGGER.error("Error getting LLM response: ", e);
            }
            pendingAction = null;
        }

        // Execute current state behavior
        switch (currentState) {
            case FOLLOWING -> tickFollow();
            case GOING_TO -> tickGoTo();
            case MINING -> tickMining();
            case ATTACKING -> tickAttacking();
            case DEFENDING -> tickDefending();
            case AUTONOMOUS -> tickAutonomous();
            case BUILDING -> tickBuilding();
            case IDLE -> tickIdle();
        }
    }

    public void processMessage(String message) {
        processMessage(message, companion.getOwner());
    }

    public void processMessage(String message, Player sender) {
        if ((pendingAction != null && !pendingAction.isDone()) ||
            (pendingLoopFuture != null && !pendingLoopFuture.isDone())) {
            sendMessageTo(sender, "Ainda estou pensando no seu último pedido...");
            return;
        }

        // Track who gave the command
        this.commandGiver = sender;

        LLMoblings.LOGGER.info("[{}] Processing message from {}: {}", companion.getCompanionName(),
                sender != null ? sender.getName().getString() : "unknown", message);

        CompanionAction directAction = tryResolveDirectCommand(message, sender);
        if (directAction != null) {
            rememberExplicitCommand(directAction);
            LLMoblings.LOGGER.info("[{}] Resolved direct command locally: {}",
                    companion.getCompanionName(), directAction);
            executeAction(directAction);
            return;
        }

        sendMessageToAll("Pensando...");

        if (Config.ACTION_LOOP_ENABLED.get()) {
            processMessageWithLoop(message, sender);
        } else {
            pendingAction = ollamaClient.chat(message);
        }
    }

    private void rememberExplicitCommand(CompanionAction action) {
        this.lastExplicitCommand = copyAction(action);
        this.lastExplicitCommandTick = companion.tickCount;
    }

    private CompanionAction copyAction(CompanionAction action) {
        return new CompanionAction(action.getAction(), action.getMessage(), action.getData().deepCopy());
    }

    private String normalizeCommandText(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase();
        return normalized;
    }

    private int extractRequestedCount(String normalized) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?:^|\\D)(\\d{1,3})(?:\\D|$)").matcher(normalized);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        if (normalized.contains("um ") || normalized.contains("uma ")) return 1;
        if (normalized.contains("alguma") || normalized.contains("achar") || normalized.contains("quando achar")) return 1;
        if (normalized.contains("pilha") || normalized.contains("bastante")) return 32;
        return 16;
    }

    private CompanionAction buildGatherAction(String item, int count, int radius) {
        JsonObject data = new JsonObject();
        data.addProperty("item", item);
        data.addProperty("count", count);
        data.addProperty("radius", radius);
        return new CompanionAction("gather", null, data);
    }

    private CompanionAction tryResolveDirectCommand(String message, Player sender) {
        if (message == null || message.isBlank()) {
            return null;
        }

        String normalized = normalizeCommandText(message);

        // Follow-up references like "vai atras dela entao" should continue the last
        // explicit gathering/mining request instead of hallucinating a new intent.
        if ((normalized.contains("vai atras") || normalized.contains("vai nela") || normalized.contains("vai buscar entao") ||
                normalized.contains("procura entao") || normalized.contains("vai procurar entao")) &&
                lastExplicitCommand != null && companion.tickCount - lastExplicitCommandTick < 20 * 45) {
            String actionName = lastExplicitCommand.getAction().toLowerCase();
            if (actionName.equals("gather") || actionName.equals("mine") || actionName.equals("farm") || actionName.equals("build")) {
                return copyAction(lastExplicitCommand);
            }
        }

        boolean mentionsHome = normalized.contains("casa");
        boolean mentionsHere = normalized.contains("aqui") || normalized.contains("minha posicao") || normalized.contains("minha posicao") || normalized.contains("neste lugar") || normalized.contains("nesse lugar");
        boolean mentionsSet = normalized.contains("marca") || normalized.contains("define") || normalized.contains("seta") || normalized.contains("salva") || normalized.contains("lembra") || normalized.contains("considera") || normalized.contains("vai ficar");
        if (mentionsHome && mentionsHere && mentionsSet) {
            JsonObject data = new JsonObject();
            BlockPos pos = sender != null ? sender.blockPosition() : companion.blockPosition();
            data.addProperty("x", pos.getX());
            data.addProperty("y", pos.getY());
            data.addProperty("z", pos.getZ());
            return new CompanionAction("sethome", null, data);
        }

        if ((normalized.contains("vai ate sua casa") || normalized.contains("vai para sua casa") || normalized.contains("volta para sua casa") ||
                normalized.contains("vai pra sua casa") || normalized.contains("vai pra casa") || normalized.contains("volta pra casa") ||
                normalized.contains("vai para casa")) && !mentionsSet) {
            return new CompanionAction("home", null);
        }

        if ((normalized.contains("teletransporta pra mim") || normalized.contains("teleporta pra mim") || normalized.contains("tp pra mim") ||
                normalized.contains("tpa pra mim") || normalized.contains("teletransporte ate mim") || normalized.contains("teletransporta ate mim")) && sender != null) {
            JsonObject data = new JsonObject();
            data.addProperty("target", sender.getName().getString());
            return new CompanionAction("tpa", null, data);
        }

        if (normalized.contains("vem exatamente na minha posicao") || normalized.contains("vem ate mim") ||
                normalized.contains("vem aqui") || normalized.contains("venha aqui") || normalized.contains("cola em mim")) {
            return new CompanionAction("come", null);
        }

        if (normalized.contains("me segue") || normalized.contains("segue me") || normalized.contains("segue comigo") ||
                normalized.contains("vem comigo") || normalized.contains("siga me")) {
            return new CompanionAction("follow", null);
        }

        if ((normalized.contains("seja autonomo") || normalized.contains("fica autonomo") || normalized.contains("fique autonomo") ||
                normalized.contains("modo autonomo") || normalized.contains("seja independente") || normalized.contains("aja por conta propria"))) {
            return new CompanionAction("auto", null);
        }

        if ((normalized.contains("fica aqui") || normalized.contains("espere aqui") || normalized.contains("para aqui") ||
                normalized.equals("fica") || normalized.equals("parar") || normalized.equals("pare"))) {
            return new CompanionAction("stay", null);
        }

        if (normalized.contains("colhe") || normalized.contains("colher") || normalized.contains("plantacao") || normalized.contains("plantacoes") ||
                normalized.contains("planta") || normalized.contains("fazenda")) {
            JsonObject data = new JsonObject();
            data.addProperty("radius", 24);
            return new CompanionAction("farm", null, data);
        }

        boolean wantsResource = normalized.contains("procura") || normalized.contains("buscar") || normalized.contains("coleta") ||
                normalized.contains("coletar") || normalized.contains("pega") || normalized.contains("corta") ||
                normalized.contains("achar") || normalized.contains("acha") || normalized.contains("vai atras");
        if (wantsResource) {
            int count = extractRequestedCount(normalized);
            int radius = normalized.contains("longe") || normalized.contains("floresta") || normalized.contains("mais longe") ? 40 : 32;

            if (normalized.contains("madeira") || normalized.contains("tronco") || normalized.contains("arvore") || normalized.contains("lenha") || normalized.contains("log")) {
                return buildGatherAction("wood", count, radius);
            }
            if (normalized.contains("carvao") || normalized.contains("coal")) {
                return buildGatherAction("coal", count, radius);
            }
            if (normalized.contains("ferro") || normalized.contains("iron")) {
                return buildGatherAction("iron", count, radius);
            }
            if (normalized.contains("pedra") || normalized.contains("stone") || normalized.contains("cobblestone") || normalized.contains("cobble")) {
                return buildGatherAction("stone", count, radius);
            }
            if (normalized.contains("diamante") || normalized.contains("diamond")) {
                return buildGatherAction("diamond", count, radius);
            }
        }

        return null;
    }

    /**
     * Process a message using the iterative action loop.
     * Runs async: LLM call -> execute -> if query, feed result back -> repeat.
     */
    private void processMessageWithLoop(String message, Player sender) {
        int maxIterations = Config.ACTION_LOOP_MAX_ITERATIONS.get();

        pendingLoopFuture = CompletableFuture.runAsync(() -> {
            String currentMessage = message;
            int messagesAdded = 0;

            try {
                for (int iteration = 0; iteration < maxIterations; iteration++) {
                    LLMoblings.LOGGER.info("[{}] Action loop iteration {} of {}",
                            companion.getCompanionName(), iteration, maxIterations);

                    // Build world state on the main thread
                    String worldState = executeOnMainThreadAndWait(
                            () -> WorldStateBuilder.buildContext(companion));

                    // Call LLM (blocking, already on async thread)
                    CompanionAction action = ollamaClient.chatBlocking(currentMessage, worldState);
                    messagesAdded += 2; // user + assistant messages

                    // Send the LLM's chat message on the main thread (blocking to preserve order)
                    if (action.getMessage() != null && !action.getMessage().isEmpty()) {
                        executeOnMainThreadAndWait(() -> {
                            sendMessage(action.getMessage());
                            return null;
                        });
                    }

                    // If LLM chose idle, stop the loop
                    if ("idle".equalsIgnoreCase(action.getAction())) {
                        executeOnMainThreadAndWait(() -> {
                            currentState = AIState.IDLE;
                            return null;
                        });
                        break;
                    }

                    // Execute the action on the main thread (with null message to avoid double-send)
                    ActionResult result = executeOnMainThreadAndWait(() -> {
                        CompanionAction silentAction = new CompanionAction(
                                action.getAction(), null, action.getData());
                        return executeAction(silentAction);
                    });

                    if (result.isTerminal()) {
                        LLMoblings.LOGGER.info("[{}] Loop ended: terminal action '{}'",
                                companion.getCompanionName(), result.actionName());
                        break;
                    }

                    // Query result — feed back to LLM for next iteration
                    ollamaClient.addSystemObservation(result.resultText());
                    messagesAdded += 1; // observation message
                    currentMessage = "[Continue com base na observação acima.]";
                }
            } catch (Exception e) {
                LLMoblings.LOGGER.error("[{}] Action loop error: ", companion.getCompanionName(), e);
                scheduleMainThread(() -> sendMessage("Desculpa, me perdi no meio do raciocínio."));
            } finally {
                // Compact history to avoid consuming the 20-message window
                if (messagesAdded > 2) {
                    ollamaClient.compactLoopHistory(messagesAdded);
                }
            }
        });
    }

    /**
     * Execute a supplier on the main server thread and block until it completes.
     * Has a 10-second timeout to prevent deadlocks.
     */
    private <T> T executeOnMainThreadAndWait(Supplier<T> supplier) {
        if (companion.level().getServer() == null) {
            throw new IllegalStateException("No server available for main thread dispatch");
        }

        CompletableFuture<T> future = new CompletableFuture<>();
        companion.level().getServer().execute(() -> {
            try {
                future.complete(supplier.get());
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new CompletionException("Main thread execution timed out after 10s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompletionException("Main thread execution interrupted", e);
        } catch (ExecutionException e) {
            throw new CompletionException("Main thread execution failed", e.getCause());
        }
    }

    /**
     * Fire-and-forget dispatch to the main server thread.
     */
    private void scheduleMainThread(Runnable runnable) {
        if (companion.level().getServer() != null) {
            companion.level().getServer().execute(runnable);
        }
    }

    /**
     * Process a message from someone who is not the owner (and not a teammate).
     * They can chat but not give commands.
     */
    public void processMessageFromStranger(Player stranger, String message) {
        if ((pendingAction != null && !pendingAction.isDone()) ||
            (pendingLoopFuture != null && !pendingLoopFuture.isDone())) {
            sendMessageTo(stranger, "Ainda estou pensando em outra coisa...");
            return;
        }

        LLMoblings.LOGGER.info("[{}] Processing stranger message from {}: {}",
                companion.getCompanionName(), stranger.getName().getString(), message);

        // For strangers, we add context that this is not the owner
        String contextMessage = "[Um jogador chamado " + stranger.getName().getString() +
                " (não é meu dono) disse: " + message + ". Eu devo ser amigável, mas só aceito comandos do meu dono.]";

        sendMessageToAll("Hm?");
        pendingAction = ollamaClient.chat(contextMessage);
    }

    private void sendMessageTo(Player player, String message) {
        if (player != null && Config.BROADCAST_COMPANION_CHAT.get()) {
            String formatted = "[" + companion.getCompanionName() + "] " + message;
            player.sendSystemMessage(Component.literal(formatted));
        }
    }

    private ActionResult executeAction(CompanionAction action) {
        // Send message if present
        if (action.getMessage() != null && !action.getMessage().isEmpty()) {
            sendMessage(action.getMessage());
        }

        String actionName = action.getAction().toLowerCase();
        LLMoblings.LOGGER.debug("Executing action: {}", action);

        switch (actionName) {
            // --- Query actions (loop continues) ---
            case "status" -> {
                String report = buildStatusReport();
                sendMessage(report);
                return ActionResult.query("status", report);
            }
            case "scan" -> {
                int radius = action.getInt("radius", 32);
                String report = buildScanReport(radius);
                sendMessage(report);
                return ActionResult.query("scan", report);
            }
            case "inventory", "inv", "items" -> {
                String report = buildInventoryReport();
                sendMessage(report);
                return ActionResult.query("inventory", report);
            }
            case "cobblestats" -> {
                String detail = action.getString("detail", "full");
                String target = action.getString("target", "");
                handleCobblestatsCommand(detail, target);
                return ActionResult.query("cobblestats", "Estatísticas do Pokémon enviadas no chat");
            }

            // --- Terminal actions (loop stops) ---
            case "follow" -> {
                startFollowing();
                return ActionResult.terminal("follow", "Agora estou seguindo");
            }
            case "stay", "stop" -> {
                stopAndStay();
                return ActionResult.terminal("stay", "Agora estou parado no lugar");
            }
            case "goto" -> {
                int x = action.getInt("x", (int) companion.getX());
                int y = action.getInt("y", (int) companion.getY());
                int z = action.getInt("z", (int) companion.getZ());
                goTo(new BlockPos(x, y, z));
                return ActionResult.terminal("goto", "Indo para " + x + ", " + y + ", " + z);
            }
            case "come" -> {
                comeToOwner();
                return ActionResult.terminal("come", "Indo até meu dono");
            }
            case "mine", "gather" -> {
                String block = action.getString("block", action.getString("item", "stone"));
                int count = action.getInt("count", 1);
                int radius = action.getInt("radius", 32);
                startMining(block, count, radius);
                return ActionResult.terminal("mine", "Comecei a minerar " + block);
            }
            case "farm", "harvest" -> {
                int radius = action.getInt("radius", 16);
                int harvested = farmNearbyCrops(radius);
                return ActionResult.terminal("farm", harvested > 0
                        ? "Colhi " + harvested + " plantações maduras"
                        : "Não encontrei plantações maduras por perto");
            }
            case "attack" -> {
                String target = action.getString("target", "hostile");
                startAttacking(target);
                return ActionResult.terminal("attack", "Atacando " + target);
            }
            case "defend" -> {
                startDefending();
                return ActionResult.terminal("defend", "Defendendo");
            }
            case "retreat" -> {
                retreat();
                return ActionResult.terminal("retreat", "Recuando");
            }
            case "give" -> {
                String item = action.getString("item", "");
                int count = action.getInt("count", 1);
                giveItems(item, count);
                return ActionResult.terminal("give", "Entreguei os itens");
            }
            case "explore", "wander", "look around" -> {
                int radius = action.getInt("radius", 32);
                startExploring(radius);
                return ActionResult.terminal("explore", "Explorando");
            }
            case "auto", "autonomous", "independent", "survive" -> {
                int radius = action.getInt("radius", 32);
                startAutonomous(radius);
                return ActionResult.terminal("auto", "Entrando no modo autônomo");
            }
            case "idle" -> {
                currentState = AIState.IDLE;
                return ActionResult.terminal("idle", "Parado");
            }
            case "setbed" -> {
                findAndSetBed();
                return ActionResult.terminal("setbed", "Cama definida");
            }
            case "sethome" -> {
                if (action.has("x") && action.has("y") && action.has("z")) {
                    setHomeAt(new BlockPos(action.getInt("x", (int) companion.getX()),
                            action.getInt("y", (int) companion.getY()),
                            action.getInt("z", (int) companion.getZ())));
                } else {
                    setHomeHere();
                }
                return ActionResult.terminal("sethome", "Casa definida");
            }
            case "home" -> {
                goHome();
                return ActionResult.terminal("home", "Indo para casa");
            }
            case "sleep" -> {
                tryToSleep();
                return ActionResult.terminal("sleep", "Tentando dormir");
            }
            case "tpa" -> {
                String target = action.getString("target", action.getString("player", ""));
                requestTeleport(target);
                return ActionResult.terminal("tpa", "Teleportando até " + target);
            }
            case "tpaccept" -> {
                acceptTeleport();
                return ActionResult.terminal("tpaccept", "Teleporte aceito");
            }
            case "tpdeny" -> {
                denyTeleport();
                return ActionResult.terminal("tpdeny", "Teleporte recusado");
            }
            case "portal" -> {
                String portalAction = action.getString("action", "enter");
                handlePortalCommand(portalAction);
                return ActionResult.terminal("portal", "Ação de portal: " + portalAction);
            }
            case "elevator" -> {
                String direction = action.getString("direction", "up");
                handleElevatorCommand(direction);
                return ActionResult.terminal("elevator", "Elevador " + direction);
            }
            case "equip", "gear", "arm" -> {
                equipBestGear();
                return ActionResult.terminal("equip", "Equipei meu melhor equipamento");
            }
            case "getgear", "getarmor", "craftgear", "ironset", "meget" -> {
                String material = action.getString("material", "iron");
                getGearFromME(material);
                return ActionResult.terminal("getgear", "Buscando equipamento de " + material);
            }
            case "deposit", "store", "stash", "putaway" -> {
                boolean keepGear = action.getBoolean("keepGear", true);
                depositItems(keepGear);
                return ActionResult.terminal("deposit", "Depositando itens");
            }
            case "build" -> {
                String structure = action.getString("structure", "cottage");
                boolean here = action.getBoolean("here", false);
                int x = action.getInt("x", (int) companion.getX());
                int y = action.getInt("y", (int) companion.getY());
                int z = action.getInt("z", (int) companion.getZ());
                BlockPos location = here ? companion.blockPosition() : new BlockPos(x, y, z);
                startBuilding(structure, location);
                return ActionResult.terminal("build", "Construindo " + structure);
            }
            case "pokemon", "buddy", "pokemonbuddy" -> {
                String subAction = action.getString("subaction", "find");
                handlePokemonBuddy(subAction, action.getString("name", null));
                return ActionResult.terminal("pokemon", "Pokémon: " + subAction);
            }
            case "gadget", "buildinggadget", "gadgets" -> {
                String subAction = action.getString("subaction", "info");
                String blockName = action.getString("block", null);
                int range = action.getInt("range", -1);
                handleBuildingGadget(subAction, blockName, range);
                return ActionResult.terminal("gadget", "Gadget executado: " + subAction);
            }
            case "backpack", "pack", "bag" -> {
                String subAction = action.getString("subaction", "info");
                String itemName = action.getString("item", null);
                int count = action.getInt("count", -1);
                handleBackpack(subAction, itemName, count);
                return ActionResult.terminal("backpack", "Mochila: " + subAction);
            }
            default -> {
                LLMoblings.LOGGER.warn("[{}] Unknown action: {}", companion.getCompanionName(), action.getAction());
                currentState = AIState.IDLE;
                return ActionResult.failure(actionName, "Ação desconhecida: " + actionName);
            }
        }
    }

    // State behaviors
    private void tickFollow() {
        // Follow whoever gave the command, or owner if no one specified
        Player followTarget = (commandGiver != null && commandGiver.isAlive()) ? commandGiver : companion.getOwner();
        if (followTarget == null) {
            currentState = AIState.IDLE;
            return;
        }

        double distance = companion.distanceTo(followTarget);
        double followDist = Config.COMPANION_FOLLOW_DISTANCE.get();

        if (distance > followDist) {
            // Move towards target
            companion.getNavigation().moveTo(followTarget, 1.0);
        } else if (distance < followDist - 1) {
            // Close enough, stop
            companion.getNavigation().stop();
        }

        // Teleport if too far
        if (distance > 32) {
            Vec3 targetPos = followTarget.position();
            companion.teleportTo(targetPos.x, targetPos.y, targetPos.z);
        }
    }

    private void tickGoTo() {
        if (targetPos == null) {
            currentState = AIState.IDLE;
            return;
        }

        double distance = companion.position().distanceTo(Vec3.atCenterOf(targetPos));
        if (distance < 3.0) {
            // Check for pending gear request first
            if (pendingGearRequest != null && companion.level() instanceof ServerLevel serverLevel) {
                GearRequest req = pendingGearRequest;
                pendingGearRequest = null;
                retrieveOrCraftGear(serverLevel, req.terminal(), req.items(), req.material());
                targetPos = null;
                return;
            }

            // Check for pending deposit request
            if (pendingDepositRequest != null && companion.level() instanceof ServerLevel serverLevel) {
                DepositRequest req = pendingDepositRequest;
                pendingDepositRequest = null;
                executeDeposit(serverLevel, req.pos(), req.isME(), req.keepGear());
                targetPos = null;
                return;
            }

            sendMessage("Cheguei ao destino.");
            currentState = AIState.IDLE;
            targetPos = null;
        } else if (companion.getNavigation().isDone()) {
            // Recalculate path
            companion.getNavigation().moveTo(targetPos.getX(), targetPos.getY(), targetPos.getZ(), 1.0);
        }
    }

    private void tickMining() {
        if (miningTask == null) {
            currentState = AIState.IDLE;
            return;
        }

        // Tick the mining task
        miningTask.tick();

        // Check for completion
        if (miningTask.isCompleted()) {
            sendMessage("Pronto! Recolhi " + miningTask.getMinedCount() + " de " + miningTask.getTargetBlockName() + ".");
            personality.onTaskComplete();
            miningTask = null;
            currentState = AIState.IDLE;
            return;
        }

        // Check for failure
        if (miningTask.isFailed()) {
            sendMessage(miningTask.getFailReason());
            personality.doSadEmote();
            miningTask = null;
            currentState = AIState.IDLE;
            return;
        }

        // Random mining chatter
        if (companion.tickCount % 200 == 0 && companion.getRandom().nextInt(3) == 0) {
            personality.onTaskStart("mining");
        }

        // Progress report every 5 seconds
        if (companion.tickCount % 100 == 0) {
            sendMessage("Minerando " + miningTask.getTargetBlockName() + "... (" +
                miningTask.getMinedCount() + "/" + miningTask.getTargetCount() + ")");
        }
    }

    private void tickAttacking() {
        if (targetEntity == null || !targetEntity.isAlive()) {
            // Find new target
            targetEntity = findAttackTarget();
            if (targetEntity == null) {
                sendMessage("Não há mais inimigos por perto.");
                personality.onTaskComplete();
                currentState = AIState.IDLE;
                return;
            }
            LLMoblings.LOGGER.info("[{}] New attack target: {}", companion.getCompanionName(),
                    targetEntity.getType().getDescriptionId());
        }

        double distance = companion.distanceTo(targetEntity);
        if (distance < 3.0) {
            // Face the target
            companion.getLookControl().setLookAt(targetEntity, 30.0F, 30.0F);

            // Attack!
            if (companion.tickCount % 20 == 0) {  // Attack once per second
                boolean hit = companion.doHurtTarget(targetEntity);
                if (hit) {
                    LLMoblings.LOGGER.debug("[{}] Hit {} for damage", companion.getCompanionName(),
                            targetEntity.getType().getDescriptionId());
                    personality.onCombat();
                }
            }
        } else {
            // Move towards target aggressively
            companion.getNavigation().moveTo(targetEntity, 1.4);  // Faster movement in combat
        }

        // Combat chatter every 5 seconds
        if (companion.tickCount % 100 == 0) {
            String targetName = targetEntity.hasCustomName() ?
                    targetEntity.getCustomName().getString() :
                    targetEntity.getType().getDescription().getString();
            personality.onCombat();
        }
    }

    private void tickDefending() {
        Player owner = companion.getOwner();
        if (owner == null) {
            currentState = AIState.IDLE;
            return;
        }

        // Look for threats near owner - include any mob targeting the owner or companion
        List<LivingEntity> threats = companion.level().getEntitiesOfClass(
                LivingEntity.class,
                owner.getBoundingBox().inflate(12),
                entity -> {
                    if (!entity.isAlive() || entity == companion || entity == owner) {
                        return false;
                    }
                    if (entity instanceof Player || entity instanceof CompanionEntity) {
                        return false;
                    }
                    // Check if it's a monster
                    if (entity instanceof Monster) {
                        return true;
                    }
                    // Check if any mob is targeting owner or companion
                    if (entity instanceof Mob mob) {
                        LivingEntity target = mob.getTarget();
                        return target != null && (target == owner || target == companion);
                    }
                    return false;
                }
        );

        if (!threats.isEmpty()) {
            // Sort by distance and get closest
            targetEntity = threats.stream()
                    .min(Comparator.comparingDouble(e -> companion.distanceTo(e)))
                    .orElse(null);

            if (targetEntity != null) {
                double distance = companion.distanceTo(targetEntity);
                if (distance < 3.0) {
                    companion.getLookControl().setLookAt(targetEntity, 30.0F, 30.0F);
                    if (companion.tickCount % 20 == 0) {
                        companion.doHurtTarget(targetEntity);
                        personality.onCombat();
                    }
                } else {
                    companion.getNavigation().moveTo(targetEntity, 1.4);
                }
            }
        } else {
            // No threats, stay near owner
            tickFollow();
        }
    }

    private void tickAutonomous() {
        if (autonomousTask == null) {
            currentState = AIState.IDLE;
            return;
        }

        autonomousTask.tick();
    }

    private void tickBuilding() {
        if (buildingTask == null) {
            currentState = AIState.IDLE;
            return;
        }

        buildingTask.tick();

        // Check for completion
        if (buildingTask.isCompleted()) {
            sendMessage("Pronto! Terminei de construir " + buildingTask.getStructureName() + "!");
            personality.onTaskComplete();
            buildingTask = null;
            currentState = AIState.IDLE;
            return;
        }

        // Check for failure
        if (buildingTask.isFailed()) {
            sendMessage(buildingTask.getFailReason());
            personality.doSadEmote();
            buildingTask = null;
            currentState = AIState.IDLE;
            return;
        }

        // Progress report every 5 seconds
        if (companion.tickCount % 100 == 0) {
            sendMessage(buildingTask.getProgressReport());
        }
    }

    private void tickIdle() {
        // Occasionally look around
        if (companion.getRandom().nextInt(100) == 0) {
            companion.setYRot(companion.getYRot() + (companion.getRandom().nextFloat() - 0.5F) * 30);
        }
    }

    private void checkOwnerProximity() {
        Player owner = companion.getOwner();
        if (owner == null) {
            ownerWasNearby = false;
            return;
        }

        double distance = companion.distanceTo(owner);
        boolean isNearby = distance < 32;

        // Owner just arrived
        if (isNearby && !ownerWasNearby) {
            personality.onOwnerNearby();
        }

        ownerWasNearby = isNearby;
    }

    // Action implementations
    private void startFollowing() {
        currentState = AIState.FOLLOWING;
        sendMessage("Vou te seguir!");
    }

    private void stopAndStay() {
        currentState = AIState.IDLE;
        companion.getNavigation().stop();
        sendMessage("Vou ficar aqui.");
    }

    private void goTo(BlockPos pos) {
        targetPos = pos;
        currentState = AIState.GOING_TO;
        companion.getNavigation().moveTo(pos.getX(), pos.getY(), pos.getZ(), 1.0);
    }

    private void comeToOwner() {
        Player target = (commandGiver != null && commandGiver.isAlive()) ? commandGiver : companion.getOwner();
        if (target != null) {
            goTo(target.blockPosition());
        }
    }

    private int farmNearbyCrops(int radius) {
        if (!(companion.level() instanceof ServerLevel serverLevel)) {
            sendMessage("Não consegui acessar o mundo para colher as plantações.");
            return 0;
        }

        List<BlockPos> crops = UltimineHelper.findMatureCrops(serverLevel, companion.blockPosition(), radius);
        if (crops.isEmpty()) {
            sendMessage("Não encontrei plantações maduras por perto.");
            return 0;
        }

        int harvested = 0;
        for (BlockPos cropPos : crops) {
            if (UltimineHelper.harvestAndReplant(serverLevel, cropPos, companion)) {
                harvested++;
                if (harvested >= 32) {
                    break;
                }
            }
        }

        if (harvested > 0) {
            sendMessage("Colhi " + harvested + " plantações maduras.");
        } else {
            sendMessage("Não consegui colher as plantações que encontrei.");
        }

        return harvested;
    }

    private void startMining(String blockType, int count, int radius) {
        int effectiveRadius = Math.max(24, radius);
        miningTask = new MiningTask(companion, blockType, count, effectiveRadius);

        if (miningTask.isFailed()) {
            sendMessage(miningTask.getFailReason());
            personality.doSadEmote();
            miningTask = null;
            return;
        }

        currentState = AIState.MINING;
        sendMessage("Vou começar a coletar " + count + " de " + blockType + ". Vou procurar por perto e também vou sair para buscar se não encontrar aqui.");
        personality.onTaskStart("mining");
    }

    private void startAttacking(String targetType) {
        currentState = AIState.ATTACKING;
        targetEntity = findAttackTarget(targetType);
    }

    private Entity findAttackTarget(String targetType) {
        // Try to find entity type by name
        EntityType<?> specificType = null;
        if (targetType != null && !targetType.isEmpty() && !targetType.equalsIgnoreCase("hostile")) {
            // Try with minecraft namespace first, then without
            ResourceLocation typeId = targetType.contains(":")
                    ? ResourceLocation.tryParse(targetType)
                    : ResourceLocation.withDefaultNamespace(targetType);
            if (typeId != null && BuiltInRegistries.ENTITY_TYPE.containsKey(typeId)) {
                specificType = BuiltInRegistries.ENTITY_TYPE.get(typeId);
            }
        }

        final EntityType<?> searchType = specificType;
        Player owner = companion.getOwner();

        List<LivingEntity> entities = companion.level().getEntitiesOfClass(
                LivingEntity.class,
                companion.getBoundingBox().inflate(16),
                e -> {
                    if (!e.isAlive() || e == companion || e == owner) {
                        return false;
                    }
                    // Never attack players or companions
                    if (e instanceof Player || e instanceof CompanionEntity) {
                        return false;
                    }
                    // If specific type requested, match it
                    if (searchType != null) {
                        return e.getType() == searchType;
                    }
                    // Target anything that is hostile (Monster is a subclass)
                    if (e instanceof Monster) {
                        return true;
                    }
                    // Target any mob that is targeting the owner or companion
                    if (e instanceof Mob mob) {
                        LivingEntity target = mob.getTarget();
                        if (target != null && (target == owner || target == companion)) {
                            return true;
                        }
                    }
                    // Target anything with "hostile" or aggressive in its name (for modded mobs)
                    String entityName = e.getType().getDescriptionId().toLowerCase();
                    if (entityName.contains("hostile") || entityName.contains("titan") ||
                        entityName.contains("boss") || entityName.contains("monster")) {
                        return true;
                    }
                    return false;
                }
        );

        return entities.stream()
                .min(Comparator.comparingDouble(e -> companion.distanceTo(e)))
                .orElse(null);
    }

    private Entity findAttackTarget() {
        return findAttackTarget("hostile");
    }

    private void startDefending() {
        currentState = AIState.DEFENDING;
        sendMessage("Eu vou te proteger!");
    }

    private void startAutonomous(int radius) {
        autonomousTask = new AutonomousTask(companion, radius);
        currentState = AIState.AUTONOMOUS;
        sendMessage("Entrando no modo autônomo. Vou avaliar a área, procurar comida, me equipar e patrulhar. Diga 'parar' ou 'seguir' para eu voltar ao normal.");
    }

    private void startExploring(int radius) {
        autonomousTask = new AutonomousTask(companion, radius);
        autonomousTask.setExploring();  // Start directly in explore mode
        currentState = AIState.AUTONOMOUS;
        sendMessage("Vou explorar a área. Posso abrir portas e verificar lugares interessantes.");
    }

    private void startBuilding(String structureType, BlockPos location) {
        Blueprint blueprint = null;

        // Get blueprint by type
        if (structureType.equalsIgnoreCase("cottage") || structureType.equalsIgnoreCase("house")) {
            blueprint = new CottageBlueprint();
        }

        if (blueprint == null) {
            sendMessage("Eu não sei construir " + structureType + ". No momento eu consigo construir: cottage.");
            return;
        }

        buildingTask = new BuildingTask(companion, blueprint, location);
        currentState = AIState.BUILDING;
        sendMessage("Começando a construir " + blueprint.getName() + " em [" +
                location.getX() + ", " + location.getY() + ", " + location.getZ() + "]. Isso pode levar um tempo.");
        personality.onTaskStart("building");
    }

    // ========== POKEMON BUDDY SYSTEM ==========

    /**
     * Tick the Pokemon buddy to follow the companion.
     */
    private void tickPokemonBuddy() {
        if (!CobblemonIntegration.isCobblemonLoaded()) {
            return;
        }

        // Check if buddy is still valid
        if (pokemonBuddy != null) {
            if (!pokemonBuddy.isAlive() || pokemonBuddy.isRemoved()) {
                sendMessage("Ah não, " + pokemonBuddyName + " sumiu!");
                pokemonBuddy = null;
                pokemonBuddyName = null;
                return;
            }

            // Make buddy follow companion (every 20 ticks = 1 second)
            if (companion.tickCount % 20 == 0) {
                double distance = companion.distanceTo(pokemonBuddy);

                // If too far, teleport the buddy
                if (distance > 20) {
                    pokemonBuddy.teleportTo(
                            companion.getX() + (companion.getRandom().nextDouble() - 0.5) * 2,
                            companion.getY(),
                            companion.getZ() + (companion.getRandom().nextDouble() - 0.5) * 2
                    );
                } else if (distance > 3) {
                    // Make it follow
                    CobblemonIntegration.makePokemonFollow(pokemonBuddy, companion);
                }
            }
        }
    }

    /**
     * Handle Pokemon buddy commands.
     */
    private void handlePokemonBuddy(String subAction, String targetName) {
        if (!CobblemonIntegration.isCobblemonLoaded()) {
            sendMessage("Não estou vendo nenhum Pokémon por perto... Cobblemon está instalado?");
            return;
        }

        switch (subAction.toLowerCase()) {
            case "find", "bond", "get" -> findPokemonBuddy(targetName);
            case "release", "bye", "dismiss" -> releasePokemonBuddy();
            case "status", "check" -> checkPokemonBuddy();
            default -> findPokemonBuddy(targetName);
        }
    }

    /**
     * Find and bond with a Pokemon buddy.
     */
    private void findPokemonBuddy(String targetName) {
        // Already have a buddy?
        if (pokemonBuddy != null && pokemonBuddy.isAlive()) {
            sendMessage("Eu já estou com " + pokemonBuddyName + " comigo! Diga 'liberar buddy' primeiro se quiser que eu procure outro.");
            return;
        }

        // Look for the owner's Pokemon nearby
        Entity foundPokemon = CobblemonIntegration.findNearestPlayerPokemon(companion, 32);

        if (foundPokemon == null) {
            sendMessage("Não vejo nenhum dos seus Pokémon por perto. Solte um e eu vou criar vínculo com ele!");
            return;
        }

        // If target name specified, try to find that specific Pokemon
        if (targetName != null && !targetName.isEmpty()) {
            List<Entity> allPokemon = CobblemonIntegration.findPlayerPokemon(
                    companion,
                    (net.minecraft.server.level.ServerPlayer) companion.getOwner(),
                    32);

            for (Entity pokemon : allPokemon) {
                String name = CobblemonIntegration.getPokemonDisplayName(pokemon);
                if (name != null && name.toLowerCase().contains(targetName.toLowerCase())) {
                    foundPokemon = pokemon;
                    break;
                }
            }
        }

        // Bond with the Pokemon
        pokemonBuddy = foundPokemon;
        pokemonBuddyName = CobblemonIntegration.getPokemonSummary(foundPokemon);

        String speciesName = CobblemonIntegration.getPokemonSpeciesName(foundPokemon);
        boolean isShiny = CobblemonIntegration.isPokemonShiny(foundPokemon);
        int level = CobblemonIntegration.getPokemonLevel(foundPokemon);

        StringBuilder msg = new StringBuilder();
        msg.append("Olá, ");
        if (isShiny) {
            msg.append("shiny ");
        }
        msg.append(speciesName).append("! ");
        msg.append("We're going to be adventure buddies! ");
        msg.append("(Lv. ").append(level).append(")");

        sendMessage(msg.toString());
        LLMoblings.LOGGER.info("[{}] Bonded with Pokemon: {}", companion.getCompanionName(), pokemonBuddyName);
    }

    /**
     * Release the current Pokemon buddy.
     */
    private void releasePokemonBuddy() {
        if (pokemonBuddy == null) {
            sendMessage("Eu não tenho um Pokémon parceiro agora.");
            return;
        }

        sendMessage("Tchau, " + CobblemonIntegration.getPokemonDisplayName(pokemonBuddy) + "! Foi divertido se aventurar com você!");
        pokemonBuddy = null;
        pokemonBuddyName = null;
    }

    /**
     * Check on the Pokemon buddy.
     */
    private void checkPokemonBuddy() {
        if (pokemonBuddy == null || !pokemonBuddy.isAlive()) {
            sendMessage("Eu não tenho um Pokémon parceiro agora. Solte um dos seus Pokémon e peça para eu procurar um parceiro Pokémon!");
            return;
        }

        String summary = CobblemonIntegration.getPokemonSummary(pokemonBuddy);
        double distance = companion.distanceTo(pokemonBuddy);

        sendMessage("Meu parceiro " + summary + " está a " + (int) distance + " blocos daqui. Está tudo certo com a gente!");
    }

    /**
     * Get the current Pokemon buddy (for other systems).
     */
    public Entity getPokemonBuddy() {
        return pokemonBuddy;
    }

    /**
     * Check if companion has a Pokemon buddy.
     */
    public boolean hasPokemonBuddy() {
        return pokemonBuddy != null && pokemonBuddy.isAlive();
    }

    // ========== END POKEMON BUDDY SYSTEM ==========

    // ========== BUILDING GADGETS SYSTEM ==========

    /**
     * Handle Building Gadgets commands.
     */
    private void handleBuildingGadget(String subAction, String blockName, int range) {
        if (!BuildingGadgetsIntegration.isBuildingGadgetsLoaded()) {
            sendMessage("Building Gadgets não está instalado. Eu não consigo usar gadgets sem ele!");
            return;
        }

        switch (subAction.toLowerCase()) {
            case "info", "check", "status" -> gadgetInfo();
            case "equip", "hold", "use" -> equipGadget();
            case "setblock", "block", "set" -> setGadgetBlock(blockName);
            case "setrange", "range" -> setGadgetRange(range);
            case "configure", "config", "setup" -> configureGadget(blockName, range);
            case "build", "place" -> useGadgetAtTarget();
            default -> gadgetInfo();
        }
    }

    /**
     * Report info about the current gadget.
     */
    private void gadgetInfo() {
        ItemStack gadget = BuildingGadgetsIntegration.findAnyGadget(companion);
        if (gadget.isEmpty()) {
            sendMessage("Eu não tenho nenhum Building Gadget. Me dá um!");
            return;
        }

        String desc = BuildingGadgetsIntegration.getGadgetDescription(gadget);
        boolean equipped = BuildingGadgetsIntegration.isGadget(companion.getMainHandItem());
        sendMessage("Eu tenho " + desc + (equipped ? " (equipado)" : " (no inventário)"));
    }

    /**
     * Equip a building gadget to main hand.
     */
    private void equipGadget() {
        if (BuildingGadgetsIntegration.isGadget(companion.getMainHandItem())) {
            String desc = BuildingGadgetsIntegration.getGadgetDescription(companion.getMainHandItem());
            sendMessage("Eu já estou com meu " + desc + " equipado!");
            return;
        }

        if (BuildingGadgetsIntegration.equipGadget(companion)) {
            String desc = BuildingGadgetsIntegration.getGadgetDescription(companion.getMainHandItem());
            sendMessage("Equipei " + desc + "! Pronto para construir!");
        } else {
            sendMessage("Eu não tenho nenhum Building Gadget para equipar.");
        }
    }

    /**
     * Set the block type on the gadget.
     */
    private void setGadgetBlock(String blockName) {
        ItemStack gadget = companion.getMainHandItem();
        if (!BuildingGadgetsIntegration.isGadget(gadget)) {
            gadget = BuildingGadgetsIntegration.findBuildingGadget(companion);
            if (gadget.isEmpty()) {
                sendMessage("Eu preciso segurar um Building Gadget primeiro!");
                return;
            }
            // Equip it
            BuildingGadgetsIntegration.equipGadget(companion);
            gadget = companion.getMainHandItem();
        }

        if (blockName == null || blockName.isEmpty()) {
            // Try to find a block from inventory
            net.minecraft.world.level.block.Block block = BuildingGadgetsIntegration.findBuildableBlock(companion);
            if (block != null) {
                BuildingGadgetsIntegration.setGadgetBlock(gadget, block.defaultBlockState());
                sendMessage("Configurei o gadget para colocar " + BuiltInRegistries.BLOCK.getKey(block).getPath().replace("_", " ") + "!");
            } else {
                sendMessage("Eu não tenho blocos no inventário para usar. Me dá alguns materiais de construção!");
            }
            return;
        }

        // Try to parse block name
        ResourceLocation blockId = blockName.contains(":")
            ? ResourceLocation.tryParse(blockName)
            : ResourceLocation.withDefaultNamespace(blockName.toLowerCase().replace(" ", "_"));

        if (blockId != null && BuiltInRegistries.BLOCK.containsKey(blockId)) {
            net.minecraft.world.level.block.Block block = BuiltInRegistries.BLOCK.get(blockId);
            BuildingGadgetsIntegration.setGadgetBlock(gadget, block.defaultBlockState());
            sendMessage("Configurei o gadget para colocar " + blockName + "!");
        } else {
            sendMessage("Eu não sei qual bloco é '" + blockName + "'.");
        }
    }

    /**
     * Set the range on the gadget.
     */
    private void setGadgetRange(int range) {
        ItemStack gadget = companion.getMainHandItem();
        if (!BuildingGadgetsIntegration.isGadget(gadget)) {
            gadget = BuildingGadgetsIntegration.findAnyGadget(companion);
            if (gadget.isEmpty()) {
                sendMessage("Eu preciso de um Building Gadget primeiro!");
                return;
            }
        }

        if (range < 1) {
            range = 3; // Default range
        }

        if (BuildingGadgetsIntegration.setGadgetRange(gadget, range)) {
            sendMessage("Defini o alcance do gadget para " + range + "!");
        } else {
            sendMessage("Não consegui definir o alcance desse gadget.");
        }
    }

    /**
     * Configure gadget with both block and range.
     */
    private void configureGadget(String blockName, int range) {
        ItemStack gadget = companion.getMainHandItem();
        if (!BuildingGadgetsIntegration.isGadget(gadget)) {
            if (!BuildingGadgetsIntegration.equipGadget(companion)) {
                sendMessage("Eu não tenho nenhum Building Gadget!");
                return;
            }
            gadget = companion.getMainHandItem();
        }

        // Set block if provided
        if (blockName != null && !blockName.isEmpty()) {
            setGadgetBlock(blockName);
        }

        // Set range if provided
        if (range > 0) {
            setGadgetRange(range);
        }

        String desc = BuildingGadgetsIntegration.getGadgetDescription(gadget);
        sendMessage("Gadget configurado! " + desc);
    }

    /**
     * Use the gadget at the target position.
     */
    private void useGadgetAtTarget() {
        ItemStack gadget = companion.getMainHandItem();
        if (!BuildingGadgetsIntegration.isGadget(gadget)) {
            sendMessage("Eu preciso segurar um Building Gadget para usar!");
            return;
        }

        // Check if gadget has a block set
        net.minecraft.world.level.block.state.BlockState currentBlock = BuildingGadgetsIntegration.getGadgetBlock(gadget);
        if (currentBlock.isAir()) {
            sendMessage("Meu gadget ainda não tem um bloco definido. Diga 'gadget set block [blockname]' primeiro!");
            return;
        }

        // Use at current target or ground in front
        BlockPos targetPos = companion.blockPosition().relative(companion.getDirection());
        if (BuildingGadgetsIntegration.useGadget(companion, targetPos, net.minecraft.core.Direction.UP)) {
            sendMessage("*usa o gadget* Construindo!");
        } else {
            sendMessage("Eu não consegui usar o gadget aqui.");
        }
    }

    /**
     * Rotate the gadget's build mode.
     */
    private void rotateGadgetMode() {
        ItemStack gadget = companion.getMainHandItem();
        if (!BuildingGadgetsIntegration.isGadget(gadget)) {
            sendMessage("Eu preciso segurar um Building Gadget primeiro!");
            return;
        }

        if (BuildingGadgetsIntegration.rotateMode(gadget)) {
            String newMode = BuildingGadgetsIntegration.getModeName(gadget);
            sendMessage("Troquei para o modo " + newMode + "!");
        } else {
            sendMessage("Não consegui mudar o modo desse gadget.");
        }
    }

    // ========== END BUILDING GADGETS SYSTEM ==========

    // ========== SOPHISTICATED BACKPACKS SYSTEM ==========

    /**
     * Handle backpack commands.
     */
    private void handleBackpack(String subAction, String itemName, int count) {
        if (!SophisticatedBackpacksIntegration.isSophisticatedBackpacksLoaded()) {
            sendMessage("Sophisticated Backpacks não está instalado. Eu não consigo usar mochilas sem ele!");
            return;
        }

        switch (subAction.toLowerCase()) {
            case "info", "check", "status" -> backpackInfo();
            case "store", "stash", "put" -> storeInBackpack(itemName);
            case "storeall", "empty" -> storeAllInBackpack();
            case "get", "take", "retrieve" -> retrieveFromBackpack(itemName, count);
            case "list", "contents", "show" -> listBackpackContents();
            case "organize", "sort" -> organizeBackpack();
            default -> backpackInfo();
        }
    }

    /**
     * Report info about the current backpack.
     */
    private void backpackInfo() {
        ItemStack backpack = SophisticatedBackpacksIntegration.findBackpack(companion);
        if (backpack.isEmpty()) {
            sendMessage("Eu não tenho mochila. Me dá uma e eu consigo carregar muito mais coisa!");
            return;
        }

        String desc = SophisticatedBackpacksIntegration.getBackpackDescription(backpack);
        sendMessage("Eu tenho " + desc);
    }

    /**
     * Store a specific item type into backpack.
     */
    private void storeInBackpack(String itemName) {
        ItemStack backpack = SophisticatedBackpacksIntegration.findBackpack(companion);
        if (backpack.isEmpty()) {
            sendMessage("Eu não tenho mochila para guardar itens!");
            return;
        }

        if (itemName == null || itemName.isEmpty()) {
            // Store all non-essential items
            storeAllInBackpack();
            return;
        }

        String searchName = itemName.toLowerCase().replace(" ", "_");
        int stored = 0;

        for (int i = 0; i < companion.getContainerSize(); i++) {
            ItemStack stack = companion.getItem(i);
            if (stack.isEmpty() || stack == backpack) continue;

            String stackId = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().toLowerCase();
            String stackName = stack.getItem().getDescription().getString().toLowerCase();

            if (stackId.contains(searchName) || stackName.contains(searchName)) {
                ItemStack remaining = SophisticatedBackpacksIntegration.insertIntoBackpack(backpack, stack);
                if (remaining.isEmpty()) {
                    companion.setItem(i, ItemStack.EMPTY);
                    stored += stack.getCount();
                } else if (remaining.getCount() < stack.getCount()) {
                    stored += stack.getCount() - remaining.getCount();
                    companion.setItem(i, remaining);
                }
            }
        }

        if (stored > 0) {
            sendMessage("Guardei " + stored + " de " + itemName + " na minha mochila!");
        } else {
            sendMessage("Eu não tenho " + itemName + " para guardar.");
        }
    }

    /**
     * Store all non-essential items into backpack.
     */
    private void storeAllInBackpack() {
        ItemStack backpack = SophisticatedBackpacksIntegration.findBackpack(companion);
        if (backpack.isEmpty()) {
            sendMessage("Eu não tenho mochila!");
            return;
        }

        int stored = SophisticatedBackpacksIntegration.storeItemsInBackpack(companion, backpack, true);

        if (stored > 0) {
            String desc = SophisticatedBackpacksIntegration.getBackpackDescription(backpack);
            sendMessage("Guardei " + stored + " pilhas de itens na minha mochila! " + desc);
        } else {
            sendMessage("Nada para guardar: meu inventário já está organizado.");
        }
    }

    /**
     * Retrieve items from backpack.
     */
    private void retrieveFromBackpack(String itemName, int count) {
        ItemStack backpack = SophisticatedBackpacksIntegration.findBackpack(companion);
        if (backpack.isEmpty()) {
            sendMessage("Eu não tenho mochila!");
            return;
        }

        if (itemName == null || itemName.isEmpty()) {
            sendMessage("Qual item você quer que eu pegue da minha mochila?");
            return;
        }

        if (count <= 0) count = 64;

        String searchName = itemName.toLowerCase().replace(" ", "_");
        int retrieved = 0;

        // Search backpack contents
        java.util.List<ItemStack> contents = SophisticatedBackpacksIntegration.getBackpackContents(backpack);
        for (ItemStack stack : contents) {
            String stackId = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().toLowerCase();
            String stackName = stack.getItem().getDescription().getString().toLowerCase();

            if (stackId.contains(searchName) || stackName.contains(searchName)) {
                int toGet = Math.min(count - retrieved, stack.getCount());
                ItemStack extracted = SophisticatedBackpacksIntegration.extractFromBackpack(
                        backpack, stack.getItem(), toGet);

                if (!extracted.isEmpty()) {
                    // Add to companion inventory
                    for (int i = 0; i < companion.getContainerSize(); i++) {
                        ItemStack slot = companion.getItem(i);
                        if (slot.isEmpty()) {
                            companion.setItem(i, extracted);
                            retrieved += extracted.getCount();
                            break;
                        } else if (ItemStack.isSameItemSameComponents(slot, extracted) &&
                                   slot.getCount() < slot.getMaxStackSize()) {
                            int space = slot.getMaxStackSize() - slot.getCount();
                            int toAdd = Math.min(space, extracted.getCount());
                            slot.grow(toAdd);
                            retrieved += toAdd;
                            extracted.shrink(toAdd);
                            if (extracted.isEmpty()) break;
                        }
                    }
                }

                if (retrieved >= count) break;
            }
        }

        if (retrieved > 0) {
            sendMessage("Peguei " + retrieved + " de " + itemName + " da minha mochila!");
        } else {
            sendMessage("Eu não tenho " + itemName + " na minha mochila.");
        }
    }

    /**
     * List backpack contents.
     */
    private void listBackpackContents() {
        ItemStack backpack = SophisticatedBackpacksIntegration.findBackpack(companion);
        if (backpack.isEmpty()) {
            sendMessage("Eu não tenho mochila!");
            return;
        }

        java.util.List<ItemStack> contents = SophisticatedBackpacksIntegration.getBackpackContents(backpack);
        if (contents.isEmpty()) {
            sendMessage("Minha mochila está vazia!");
            return;
        }

        // Group and summarize items
        java.util.Map<String, Integer> itemCounts = new java.util.LinkedHashMap<>();
        for (ItemStack stack : contents) {
            String name = stack.getItem().getDescription().getString();
            itemCounts.merge(name, stack.getCount(), Integer::sum);
        }

        StringBuilder sb = new StringBuilder("Conteúdo da mochila: ");
        int shown = 0;
        for (java.util.Map.Entry<String, Integer> entry : itemCounts.entrySet()) {
            if (shown > 0) sb.append(", ");
            sb.append(entry.getValue()).append("x ").append(entry.getKey());
            shown++;
            if (shown >= 8) {
                int remaining = itemCounts.size() - shown;
                if (remaining > 0) {
                    sb.append(" e mais ").append(remaining).append(" tipos...");
                }
                break;
            }
        }

        sendMessage(sb.toString());
    }

    /**
     * Organize backpack (sort/consolidate stacks).
     */
    private void organizeBackpack() {
        ItemStack backpack = SophisticatedBackpacksIntegration.findBackpack(companion);
        if (backpack.isEmpty()) {
            sendMessage("Eu não tenho mochila!");
            return;
        }

        // For now, just report status - actual sorting would require more complex logic
        String desc = SophisticatedBackpacksIntegration.getBackpackDescription(backpack);
        sendMessage("Minha mochila está organizada! " + desc);
    }

    // ========== END SOPHISTICATED BACKPACKS SYSTEM ==========

    private void retreat() {
        Player owner = companion.getOwner();
        if (owner != null) {
            // Run to owner
            companion.getNavigation().moveTo(owner, 1.5);
        }
        currentState = AIState.FOLLOWING;
        sendMessage("Recuando!");
    }

    private void giveItems(String itemName, int count) {
        // Give to whoever gave the command, not just owner
        Player target = (commandGiver != null && commandGiver.isAlive()) ? commandGiver : companion.getOwner();
        if (target == null) {
            sendMessage("Não vejo ninguém para quem entregar os itens!");
            return;
        }

        String searchName = itemName.toLowerCase().replace(" ", "_");
        int givenCount = 0;

        for (int i = 0; i < companion.getContainerSize() && givenCount < count; i++) {
            net.minecraft.world.item.ItemStack stack = companion.getItem(i);
            if (stack.isEmpty()) continue;

            // Check if item matches the search term
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().toLowerCase();
            String itemDesc = stack.getItem().getDescription().getString().toLowerCase();

            if (itemId.contains(searchName) || itemDesc.contains(searchName) || searchName.isEmpty()) {
                int toGive = Math.min(stack.getCount(), count - givenCount);

                // Create stack to give
                net.minecraft.world.item.ItemStack giveStack = stack.copy();
                giveStack.setCount(toGive);

                // Try to add to player inventory
                if (target.getInventory().add(giveStack)) {
                    stack.shrink(toGive);
                    if (stack.isEmpty()) {
                        companion.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
                    }
                    givenCount += toGive;
                    LLMoblings.LOGGER.info("[{}] Gave {} x{} to {}",
                            companion.getCompanionName(), giveStack.getItem().getDescription().getString(),
                            toGive, target.getName().getString());
                } else {
                    // Player inventory full, drop at their feet
                    target.drop(giveStack, false);
                    stack.shrink(toGive);
                    if (stack.isEmpty()) {
                        companion.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
                    }
                    givenCount += toGive;
                    sendMessage("Seu inventário está cheio, então deixei os itens aos seus pés.");
                }
            }
        }

        if (givenCount > 0) {
            sendMessage("Aqui está! Entreguei " + givenCount + " de " + itemName + ".");
        } else if (itemName.isEmpty()) {
            sendMessage("Que item você quer que eu te entregue?");
        } else {
            sendMessage("Eu não tenho " + itemName + " no meu inventário.");
        }
    }

    private String buildStatusReport() {
        float health = companion.getHealth();
        float maxHealth = companion.getMaxHealth();
        int itemCount = 0;
        for (int i = 0; i < companion.getContainerSize(); i++) {
            if (!companion.getItem(i).isEmpty()) itemCount++;
        }

        return String.format(
                "Vida: %.0f/%.0f, Inventário: %d/%d slots usados, Estado: %s",
                health, maxHealth, itemCount, companion.getContainerSize(), currentState
        );
    }

    private void reportStatus() {
        sendMessage(buildStatusReport());
    }

    private String buildScanReport(int radius) {
        AABB scanBox = companion.getBoundingBox().inflate(radius);

        List<Monster> hostiles = companion.level().getEntitiesOfClass(Monster.class, scanBox);
        List<LivingEntity> friendlies = companion.level().getEntitiesOfClass(LivingEntity.class, scanBox,
                e -> !(e instanceof Monster) && !(e instanceof Player));

        String report = String.format(
                "Varredura concluída! Encontrei %d mobs hostis e %d mobs passivos em um raio de %d blocos.",
                hostiles.size(), friendlies.size(), radius
        );
        LLMoblings.LOGGER.info("[{}] Scan: {} hostiles, {} friendlies in {}m radius",
                companion.getCompanionName(), hostiles.size(), friendlies.size(), radius);
        return report;
    }

    private void scanArea(int radius) {
        sendMessage(buildScanReport(radius));
    }

    private void findAndSetBed() {
        LLMoblings.LOGGER.info("[{}] Searching for bed...", companion.getCompanionName());
        BlockPos companionPos = companion.blockPosition();
        int searchRadius = 16;

        // Search for nearby bed
        for (int x = -searchRadius; x <= searchRadius; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -searchRadius; z <= searchRadius; z++) {
                    BlockPos checkPos = companionPos.offset(x, y, z);
                    BlockState state = companion.level().getBlockState(checkPos);
                    if (state.getBlock() instanceof BedBlock) {
                        bedPos = checkPos;
                        sendMessage("Encontrei uma cama! Vou lembrar desta posição em [" +
                                checkPos.getX() + ", " + checkPos.getY() + ", " + checkPos.getZ() + "].");
                        LLMoblings.LOGGER.info("[{}] Set bed position to {}", companion.getCompanionName(), checkPos);
                        return;
                    }
                }
            }
        }
        sendMessage("Não consegui encontrar uma cama por perto. Coloque uma a até 16 blocos de mim.");
        LLMoblings.LOGGER.info("[{}] No bed found in range", companion.getCompanionName());
    }

    private void setHomeHere() {
        setHomeAt(companion.blockPosition());
    }

    private void setHomeAt(BlockPos pos) {
        homePos = pos;
        sendMessage("Casa definida! Vou lembrar deste lugar em [" +
                homePos.getX() + ", " + homePos.getY() + ", " + homePos.getZ() + "].");
        LLMoblings.LOGGER.info("[{}] Set home position to {}", companion.getCompanionName(), homePos);

        // Also try to execute /sethome if server has the command
        if (companion.level() instanceof ServerLevel serverLevel) {
            try {
                String command = "sethome " + companion.getCompanionName().toLowerCase().replace(" ", "_");
                serverLevel.getServer().getCommands().performPrefixedCommand(
                        serverLevel.getServer().createCommandSourceStack()
                                .withPosition(Vec3.atCenterOf(homePos))
                                .withPermission(2),
                        command
                );
                LLMoblings.LOGGER.info("[{}] Attempted server /sethome command", companion.getCompanionName());
            } catch (Exception e) {
                LLMoblings.LOGGER.debug("[{}] /sethome command not available: {}", companion.getCompanionName(), e.getMessage());
            }
        }
    }

    private void goHome() {
        if (homePos != null) {
            goTo(homePos);
            sendMessage("Indo para casa em [" + homePos.getX() + ", " + homePos.getY() + ", " + homePos.getZ() + "]!");
            LLMoblings.LOGGER.info("[{}] Going to home position {}", companion.getCompanionName(), homePos);
        } else if (bedPos != null) {
            goTo(bedPos);
            sendMessage("Indo para minha cama em [" + bedPos.getX() + ", " + bedPos.getY() + ", " + bedPos.getZ() + "]!");
            LLMoblings.LOGGER.info("[{}] Going to bed position {}", companion.getCompanionName(), bedPos);
        } else {
            sendMessage("Eu ainda não tenho uma casa definida. Diga 'sethome' primeiro!");
            LLMoblings.LOGGER.info("[{}] No home or bed position set", companion.getCompanionName());
        }
    }

    private void tryToSleep() {
        LLMoblings.LOGGER.info("[{}] Attempting to sleep...", companion.getCompanionName());
        if (bedPos == null) {
            findAndSetBed();
        }

        if (bedPos != null) {
            double dist = companion.position().distanceTo(Vec3.atCenterOf(bedPos));
            if (dist > 3) {
                goTo(bedPos);
                sendMessage("Indo para a cama...");
            } else {
                // Check if it's night time
                if (companion.level() instanceof ServerLevel serverLevel) {
                    long dayTime = serverLevel.getDayTime() % 24000;
                    if (dayTime >= 12542 && dayTime <= 23459) {
                        sendMessage("*deita na cama* Boa noite!");
                        LLMoblings.LOGGER.info("[{}] Going to sleep", companion.getCompanionName());
                        // Note: Actual sleeping mechanics would require more complex implementation
                    } else {
                        sendMessage("Ainda não é noite. Eu só consigo dormir quando escurece.");
                        LLMoblings.LOGGER.info("[{}] Cannot sleep - not night time (dayTime={})", companion.getCompanionName(), dayTime);
                    }
                }
            }
        } else {
            sendMessage("Eu preciso de uma cama para dormir! Encontre uma para mim primeiro.");
        }
    }

    private void equipBestGear() {
        // Check current weapon - only skip if already holding a WEAPON
        ItemStack currentWeapon = companion.getMainHandItem();
        boolean holdingWeapon = !currentWeapon.isEmpty() &&
            (currentWeapon.getItem() instanceof SwordItem || currentWeapon.getItem() instanceof AxeItem);

        // Search inventory for best weapon
        ItemStack bestWeapon = ItemStack.EMPTY;
        int bestSlot = -1;
        double bestDamage = holdingWeapon ? getWeaponDamage(currentWeapon) : 0;

        for (int i = 0; i < companion.getContainerSize(); i++) {
            ItemStack stack = companion.getItem(i);
            if (stack.isEmpty()) continue;

            Item item = stack.getItem();
            if (item instanceof SwordItem || item instanceof AxeItem) {
                double damage = getWeaponDamage(stack);
                if (damage > bestDamage) {
                    bestWeapon = stack;
                    bestSlot = i;
                    bestDamage = damage;
                }
            }
        }

        if (!bestWeapon.isEmpty() && bestSlot >= 0) {
            // Put current item back in inventory if holding something
            if (!currentWeapon.isEmpty()) {
                companion.addToInventory(currentWeapon.copy());
            }
            // Equip the better weapon
            companion.setItemSlot(EquipmentSlot.MAINHAND, bestWeapon.copy());
            companion.setItem(bestSlot, ItemStack.EMPTY);
            sendMessage("Equipei " + bestWeapon.getHoverName().getString() + "!");
        } else if (holdingWeapon) {
            sendMessage("Eu já estou usando minha melhor arma: " + currentWeapon.getHoverName().getString());
        } else {
            // No weapon in inventory, go look for one
            sendMessage("Eu não tenho armas no meu inventário. Vou procurar uma!");
            startAutonomous(32);
        }
    }

    private double getWeaponDamage(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof SwordItem sword) {
            return sword.getTier().getAttackDamageBonus();
        } else if (item instanceof AxeItem axe) {
            return axe.getTier().getAttackDamageBonus();
        }
        return 0;
    }

    private String buildInventoryReport() {
        StringBuilder sb = new StringBuilder();

        // Report equipped items
        ItemStack mainHand = companion.getMainHandItem();
        ItemStack offHand = companion.getOffhandItem();

        if (!mainHand.isEmpty()) {
            sb.append("Empunhando: ").append(mainHand.getHoverName().getString());
        }
        if (!offHand.isEmpty()) {
            if (sb.length() > 0) sb.append(". ");
            sb.append("Mão secundária: ").append(offHand.getHoverName().getString());
        }

        // Count inventory items
        int itemCount = 0;
        int weaponCount = 0;
        int foodCount = 0;
        int armorCount = 0;

        for (int i = 0; i < companion.getContainerSize(); i++) {
            ItemStack stack = companion.getItem(i);
            if (stack.isEmpty()) continue;

            itemCount += stack.getCount();
            Item item = stack.getItem();

            if (item instanceof SwordItem || item instanceof AxeItem) {
                weaponCount++;
            } else if (item instanceof ArmorItem) {
                armorCount++;
            } else if (stack.getFoodProperties(companion) != null) {
                foodCount += stack.getCount();
            }
        }

        if (sb.length() > 0) sb.append(". ");

        if (itemCount == 0) {
            sb.append("Meu inventário está vazio.");
        } else {
            sb.append("Inventário: ").append(itemCount).append(" itens");
            if (weaponCount > 0) sb.append(", ").append(weaponCount).append(" armas");
            if (armorCount > 0) sb.append(", ").append(armorCount).append(" peças de armadura");
            if (foodCount > 0) sb.append(", ").append(foodCount).append(" de comida");
        }

        return sb.toString();
    }

    private void reportInventory() {
        sendMessage(buildInventoryReport());
    }

    private void getGearFromME(String material) {
        if (!AE2Integration.isAE2Loaded()) {
            sendMessage("Eu não consigo encontrar uma rede ME aqui!");
            return;
        }

        if (!(companion.level() instanceof ServerLevel serverLevel)) {
            sendMessage("Tem algo errado com o mundo...");
            return;
        }

        // Find ME access point
        List<BlockPos> meAccessPoints = AE2Integration.findMEAccessPoints(
                serverLevel, companion.blockPosition(), 32);

        if (meAccessPoints.isEmpty()) {
            sendMessage("Eu não consigo encontrar um terminal ME por perto!");
            return;
        }

        BlockPos terminal = meAccessPoints.get(0);
        sendMessage("Encontrei um terminal ME! Vou buscar equipamento de " + material + "...");

        // Determine which items to get based on material
        List<Item> targetItems;
        if (material.toLowerCase().contains("diamond")) {
            targetItems = AE2Integration.getDiamondArmorItems();
        } else {
            targetItems = AE2Integration.getIronArmorItems();
        }

        // Navigate to the terminal first
        currentState = AIState.GOING_TO;
        targetPos = terminal;

        // We'll need to process this in tick() - for now start moving
        // and set up a task to extract/craft when we arrive
        companion.getNavigation().moveTo(terminal.getX() + 0.5, terminal.getY(), terminal.getZ() + 0.5, 1.0);

        // Schedule gear retrieval after reaching terminal
        // For now, do it immediately if close enough
        double distance = companion.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(terminal));
        if (distance < 5.0) {
            retrieveOrCraftGear(serverLevel, terminal, targetItems, material);
        } else {
            // Store pending gear request for when we arrive
            pendingGearRequest = new GearRequest(terminal, targetItems, material);
            sendMessage("Estou indo até o terminal...");
        }
    }

    private GearRequest pendingGearRequest = null;

    private record GearRequest(BlockPos terminal, List<Item> items, String material) {}

    private void retrieveOrCraftGear(ServerLevel level, BlockPos terminal, List<Item> targetItems, String material) {
        int retrieved = 0;
        int craftRequested = 0;

        for (Item item : targetItems) {
            // First try to extract from ME network
            List<ItemStack> extracted = AE2Integration.extractItems(level, terminal,
                    stack -> stack.getItem() == item, 1);

            if (!extracted.isEmpty()) {
                // Add to companion inventory and equip
                for (ItemStack stack : extracted) {
                    addToInventoryAndEquip(stack);
                    retrieved++;
                }
            } else {
                // Item not in network, request crafting
                boolean craftStarted = AE2Integration.requestCrafting(level, terminal, item, 1);
                if (craftStarted) {
                    craftRequested++;
                    LLMoblings.LOGGER.info("[{}] Requested crafting of {}", companion.getCompanionName(), item);
                }
            }
        }

        // Report results
        StringBuilder result = new StringBuilder();
        if (retrieved > 0) {
            result.append("Consegui ").append(retrieved).append(" peças de ").append(material).append("! ");
        }
        if (craftRequested > 0) {
            result.append("Solicitei a fabricação de ").append(craftRequested).append(" itens. ");
        }
        if (retrieved == 0 && craftRequested == 0) {
            result.append("Não consegui encontrar nem fabricar equipamento de ").append(material).append(". Verifique se os patterns estão configurados!");
        }

        sendMessage(result.toString().trim());

        // After getting gear, equip it
        if (retrieved > 0) {
            equipAllGear();
        }

        currentState = AIState.IDLE;
    }

    private void addToInventoryAndEquip(ItemStack stack) {
        Item item = stack.getItem();

        // Equip directly if it's armor or weapon
        if (item instanceof ArmorItem armorItem) {
            EquipmentSlot slot = armorItem.getEquipmentSlot();
            ItemStack current = companion.getItemBySlot(slot);
            if (current.isEmpty()) {
                companion.setItemSlot(slot, stack.copy());
                LLMoblings.LOGGER.info("[{}] Equipped {}", companion.getCompanionName(), item);
                return;
            }
        }

        if (item instanceof SwordItem || item instanceof AxeItem) {
            if (companion.getMainHandItem().isEmpty()) {
                companion.setItemSlot(EquipmentSlot.MAINHAND, stack.copy());
                LLMoblings.LOGGER.info("[{}] Equipped {}", companion.getCompanionName(), item);
                return;
            }
        }

        // Otherwise add to inventory
        for (int i = 0; i < companion.getContainerSize(); i++) {
            if (companion.getItem(i).isEmpty()) {
                companion.setItem(i, stack.copy());
                return;
            }
        }
    }

    private void equipAllGear() {
        // Go through inventory and equip any armor/weapons
        for (int i = 0; i < companion.getContainerSize(); i++) {
            ItemStack stack = companion.getItem(i);
            if (stack.isEmpty()) continue;

            Item item = stack.getItem();

            if (item instanceof ArmorItem armorItem) {
                EquipmentSlot slot = armorItem.getEquipmentSlot();
                if (companion.getItemBySlot(slot).isEmpty()) {
                    companion.setItemSlot(slot, stack.copy());
                    companion.setItem(i, ItemStack.EMPTY);
                }
            }

            if ((item instanceof SwordItem || item instanceof AxeItem) &&
                    companion.getMainHandItem().isEmpty()) {
                companion.setItemSlot(EquipmentSlot.MAINHAND, stack.copy());
                companion.setItem(i, ItemStack.EMPTY);
            }
        }
    }

    private void depositItems(boolean keepGear) {
        if (!(companion.level() instanceof ServerLevel serverLevel)) {
            sendMessage("Tem algo errado com o mundo...");
            return;
        }

        // Count items to deposit
        int itemCount = 0;
        for (int i = 0; i < companion.getContainerSize(); i++) {
            if (!companion.getItem(i).isEmpty()) {
                itemCount++;
            }
        }

        if (itemCount == 0) {
            sendMessage("Meu inventário está vazio, não há nada para depositar!");
            return;
        }

        // First try ME network
        if (AE2Integration.isAE2Loaded()) {
            List<BlockPos> meTerminals = AE2Integration.findMEAccessPoints(
                    serverLevel, companion.blockPosition(), 32);

            if (!meTerminals.isEmpty()) {
                BlockPos terminal = meTerminals.get(0);
                sendMessage("Encontrei um terminal ME! Vou depositar os itens...");

                // Navigate to terminal
                currentState = AIState.GOING_TO;
                targetPos = terminal;
                pendingDepositRequest = new DepositRequest(terminal, true, keepGear);
                companion.getNavigation().moveTo(terminal.getX() + 0.5, terminal.getY(), terminal.getZ() + 0.5, 1.0);

                double distance = companion.position().distanceTo(Vec3.atCenterOf(terminal));
                if (distance < 5.0) {
                    executeDeposit(serverLevel, terminal, true, keepGear);
                }
                return;
            }
        }

        // Try regular chests
        BlockPos chest = findNearbyChest(serverLevel, 16);
        if (chest != null) {
            sendMessage("Encontrei um baú! Vou depositar os itens...");
            currentState = AIState.GOING_TO;
            targetPos = chest;
            pendingDepositRequest = new DepositRequest(chest, false, keepGear);
            companion.getNavigation().moveTo(chest.getX() + 0.5, chest.getY(), chest.getZ() + 0.5, 1.0);

            double distance = companion.position().distanceTo(Vec3.atCenterOf(chest));
            if (distance < 3.0) {
                executeDeposit(serverLevel, chest, false, keepGear);
            }
            return;
        }

        sendMessage("Eu não consigo encontrar nenhum armazenamento por perto!");
    }

    private DepositRequest pendingDepositRequest = null;

    private record DepositRequest(BlockPos pos, boolean isME, boolean keepGear) {}

    private BlockPos findNearbyChest(ServerLevel level, int radius) {
        BlockPos center = companion.blockPosition();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -3; y <= 3; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    if (level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.BaseContainerBlockEntity) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    private void executeDeposit(ServerLevel level, BlockPos storagePos, boolean isME, boolean keepGear) {
        int deposited = 0;

        if (isME) {
            // Deposit into ME network
            deposited = depositToME(level, storagePos, keepGear);
        } else {
            // Deposit into chest
            deposited = depositToChest(level, storagePos, keepGear);
        }

        if (deposited > 0) {
            sendMessage("Depositei " + deposited + " pilhas de itens!" + (keepGear ? " (Mantive meu equipamento)" : ""));
        } else {
            sendMessage("Não consegui depositar nenhum item. O armazenamento pode estar cheio!");
        }

        pendingDepositRequest = null;
        currentState = AIState.IDLE;
    }

    private int depositToME(ServerLevel level, BlockPos terminal, boolean keepGear) {
        int deposited = 0;

        try {
            // Get ME network access
            net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(terminal);
            if (be == null) return 0;

            // Use reflection to insert items into ME network
            Object grid = getGridFromBlockEntity(be);
            if (grid == null) return 0;

            Object storageService = getStorageService(grid);
            if (storageService == null) return 0;

            Object inventory = getInventory(storageService);
            if (inventory == null) return 0;

            // Deposit each item from companion's inventory
            for (int i = 0; i < companion.getContainerSize(); i++) {
                ItemStack stack = companion.getItem(i);
                if (stack.isEmpty()) continue;

                // Skip weapons/armor if keepGear is true
                if (keepGear) {
                    Item item = stack.getItem();
                    if (item instanceof SwordItem || item instanceof AxeItem ||
                        item instanceof ArmorItem) {
                        continue;
                    }
                }

                // Insert into ME network
                if (insertIntoME(inventory, stack)) {
                    companion.setItem(i, ItemStack.EMPTY);
                    deposited++;
                }
            }
        } catch (Exception e) {
            LLMoblings.LOGGER.warn("Error depositing to ME: {}", e.getMessage());
        }

        return deposited;
    }

    private Object getGridFromBlockEntity(net.minecraft.world.level.block.entity.BlockEntity be) {
        try {
            for (var method : be.getClass().getMethods()) {
                if (method.getName().equals("getGridNode") || method.getName().equals("getMainNode")) {
                    Object node = null;
                    if (method.getParameterCount() == 1) {
                        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                            try {
                                node = method.invoke(be, dir);
                                if (node != null) break;
                            } catch (Exception ignored) {}
                        }
                    } else if (method.getParameterCount() == 0) {
                        node = method.invoke(be);
                    }
                    if (node != null) {
                        for (var nodeMethod : node.getClass().getMethods()) {
                            if (nodeMethod.getName().equals("getGrid") && nodeMethod.getParameterCount() == 0) {
                                return nodeMethod.invoke(node);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LLMoblings.LOGGER.debug("Could not get grid: {}", e.getMessage());
        }
        return null;
    }

    private Object getStorageService(Object grid) {
        try {
            for (var method : grid.getClass().getMethods()) {
                if (method.getName().equals("getStorageService")) {
                    return method.invoke(grid);
                }
            }
        } catch (Exception e) {
            LLMoblings.LOGGER.debug("Could not get storage service: {}", e.getMessage());
        }
        return null;
    }

    private Object getInventory(Object storageService) {
        try {
            for (var method : storageService.getClass().getMethods()) {
                if (method.getName().equals("getInventory") && method.getParameterCount() == 0) {
                    return method.invoke(storageService);
                }
            }
        } catch (Exception e) {
            LLMoblings.LOGGER.debug("Could not get inventory: {}", e.getMessage());
        }
        return null;
    }

    private boolean insertIntoME(Object inventory, ItemStack stack) {
        try {
            // Create AEItemKey
            Class<?> aeItemKeyClass = Class.forName("appeng.api.stacks.AEItemKey");
            var ofMethod = aeItemKeyClass.getMethod("of", ItemStack.class);
            Object aeItemKey = ofMethod.invoke(null, stack);

            if (aeItemKey == null) return false;

            // Get Actionable.MODULATE
            Class<?> actionableClass = Class.forName("appeng.api.config.Actionable");
            Object modulate = actionableClass.getField("MODULATE").get(null);

            // Create action source
            Object actionSource = Class.forName("appeng.me.helpers.BaseActionSource")
                    .getDeclaredConstructor().newInstance();

            // Insert: MEStorage.insert(AEKey, long, Actionable, IActionSource)
            var insertMethod = inventory.getClass().getMethod("insert",
                    Class.forName("appeng.api.stacks.AEKey"),
                    long.class,
                    actionableClass,
                    Class.forName("appeng.api.networking.security.IActionSource"));

            long inserted = (long) insertMethod.invoke(inventory, aeItemKey, (long) stack.getCount(), modulate, actionSource);
            return inserted > 0;
        } catch (Exception e) {
            LLMoblings.LOGGER.trace("Could not insert into ME: {}", e.getMessage());
        }
        return false;
    }

    private int depositToChest(ServerLevel level, BlockPos chestPos, boolean keepGear) {
        int deposited = 0;

        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(chestPos);
        if (!(be instanceof net.minecraft.world.level.block.entity.BaseContainerBlockEntity container)) {
            return 0;
        }

        for (int i = 0; i < companion.getContainerSize(); i++) {
            ItemStack stack = companion.getItem(i);
            if (stack.isEmpty()) continue;

            // Skip weapons/armor if keepGear is true
            if (keepGear) {
                Item item = stack.getItem();
                if (item instanceof SwordItem || item instanceof AxeItem ||
                    item instanceof ArmorItem) {
                    continue;
                }
            }

            // Try to insert into chest
            for (int j = 0; j < container.getContainerSize(); j++) {
                ItemStack chestStack = container.getItem(j);
                if (chestStack.isEmpty()) {
                    container.setItem(j, stack.copy());
                    companion.setItem(i, ItemStack.EMPTY);
                    deposited++;
                    break;
                } else if (ItemStack.isSameItemSameComponents(chestStack, stack) &&
                           chestStack.getCount() < chestStack.getMaxStackSize()) {
                    int space = chestStack.getMaxStackSize() - chestStack.getCount();
                    int toTransfer = Math.min(space, stack.getCount());
                    chestStack.grow(toTransfer);
                    stack.shrink(toTransfer);
                    if (stack.isEmpty()) {
                        companion.setItem(i, ItemStack.EMPTY);
                        deposited++;
                        break;
                    }
                }
            }
        }

        container.setChanged();
        return deposited;
    }

    private void requestTeleport(String targetPlayer) {
        String normalizedTarget = targetPlayer == null ? "" : normalizeCommandText(targetPlayer).trim();
        if (normalizedTarget.isEmpty() || normalizedTarget.equals("player") || normalizedTarget.equals("me") || normalizedTarget.equals("mim") || normalizedTarget.equals("owner")) {
            // Default to teleporting to whoever gave the command
            Player target = (commandGiver != null && commandGiver.isAlive()) ? commandGiver : companion.getOwner();
            if (target != null) {
                teleportToPlayer(target);
            } else {
                sendMessage("Para quem eu devo me teleportar? Me diga o nome.");
            }
            return;
        }

        if (companion.level() instanceof ServerLevel serverLevel) {
            // Find the target player by name
            Player target = serverLevel.getServer().getPlayerList().getPlayerByName(targetPlayer);
            if (target != null) {
                teleportToPlayer(target);
            } else {
                sendMessage("Eu não consigo encontrar um jogador chamado " + targetPlayer + ".");
                LLMoblings.LOGGER.info("[{}] Could not find player {} for TPA", companion.getCompanionName(), targetPlayer);
            }
        }
    }

    private void teleportToPlayer(Player target) {
        Vec3 targetPos = target.position();
        companion.teleportTo(targetPos.x, targetPos.y, targetPos.z);
        sendMessage("Teleportei até " + target.getName().getString() + "!");
        LLMoblings.LOGGER.info("[{}] Teleported to player {}", companion.getCompanionName(), target.getName().getString());
    }

    private void acceptTeleport() {
        if (companion.level() instanceof ServerLevel serverLevel) {
            try {
                serverLevel.getServer().getCommands().performPrefixedCommand(
                        serverLevel.getServer().createCommandSourceStack()
                                .withEntity(companion)
                                .withPosition(companion.position())
                                .withPermission(2),
                        "tpaccept"
                );
                sendMessage("Aceitei o pedido de teleporte!");
                LLMoblings.LOGGER.info("[{}] Accepted TPA request", companion.getCompanionName());
            } catch (Exception e) {
                sendMessage("Eu não consegui aceitar o pedido de teleporte.");
                LLMoblings.LOGGER.warn("[{}] TPAccept command failed: {}", companion.getCompanionName(), e.getMessage());
            }
        }
    }

    private void denyTeleport() {
        if (companion.level() instanceof ServerLevel serverLevel) {
            try {
                serverLevel.getServer().getCommands().performPrefixedCommand(
                        serverLevel.getServer().createCommandSourceStack()
                                .withEntity(companion)
                                .withPosition(companion.position())
                                .withPermission(2),
                        "tpdeny"
                );
                sendMessage("Recusei o pedido de teleporte.");
                LLMoblings.LOGGER.info("[{}] Denied TPA request", companion.getCompanionName());
            } catch (Exception e) {
                sendMessage("Eu não consegui recusar o pedido de teleporte.");
                LLMoblings.LOGGER.warn("[{}] TPDeny command failed: {}", companion.getCompanionName(), e.getMessage());
            }
        }
    }

    /**
     * Handle portal/dimension travel commands.
     */
    private void handlePortalCommand(String action) {
        switch (action) {
            case "enter" -> {
                // Allow portal use and walk towards nearest portal
                companion.allowPortalUse();
                sendMessage("Certo, eu vou atravessar o portal!");

                // Find nearest portal block
                BlockPos nearestPortal = findNearestPortal(16);
                if (nearestPortal != null) {
                    companion.getNavigation().moveTo(
                            nearestPortal.getX() + 0.5,
                            nearestPortal.getY(),
                            nearestPortal.getZ() + 0.5,
                            1.0
                    );
                    LLMoblings.LOGGER.info("[{}] Walking to portal at {}", companion.getCompanionName(), nearestPortal);
                } else {
                    sendMessage("Não vejo um portal por perto. Vou seguir você até um!");
                    // Start following instead
                    currentState = AIState.FOLLOWING;
                }
            }
            case "follow" -> {
                // Follow owner through portal when they use it
                companion.allowPortalUse();
                sendMessage("Vou seguir você através do portal!");
                currentState = AIState.FOLLOWING;
            }
            case "stay" -> {
                companion.disallowPortalUse();
                sendMessage("Certo, vou ficar nesta dimensão.");
                currentState = AIState.IDLE;
            }
        }
    }

    /**
     * Find the nearest portal block within the given radius.
     */
    private BlockPos findNearestPortal(int radius) {
        BlockPos companionPos = companion.blockPosition();
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius / 2; y <= radius / 2; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos checkPos = companionPos.offset(x, y, z);
                    if (companion.level() instanceof ServerLevel serverLevel) {
                        net.minecraft.world.level.block.state.BlockState state = serverLevel.getBlockState(checkPos);
                        // Check for nether portal or end portal
                        if (state.is(net.minecraft.world.level.block.Blocks.NETHER_PORTAL) ||
                            state.is(net.minecraft.world.level.block.Blocks.END_PORTAL)) {
                            double dist = companionPos.distSqr(checkPos);
                            if (dist < nearestDist) {
                                nearestDist = dist;
                                nearest = checkPos;
                            }
                        }
                    }
                }
            }
        }
        return nearest;
    }

    /**
     * Handle elevator commands.
     */
    private void handleElevatorCommand(String direction) {
        if (!companion.isOnElevator()) {
            sendMessage("Eu preciso estar em cima de um bloco de elevador para usar isso!");
            // Try to find nearby elevator
            BlockPos elevatorPos = findNearbyElevator(16);
            if (elevatorPos != null) {
                sendMessage("Estou vendo um elevador por perto. Vou até ele.");
                companion.getNavigation().moveTo(
                        elevatorPos.getX() + 0.5,
                        elevatorPos.getY() + 1,
                        elevatorPos.getZ() + 0.5,
                        1.0
                );
            }
            return;
        }

        boolean goUp = direction.equalsIgnoreCase("up");
        sendMessage(goUp ? "Subindo!" : "Descendo!");
        companion.tryUseElevator(goUp);
    }

    /**
     * Handle cobblestats command to show Pokemon stats.
     */
    private void handleCobblestatsCommand(String detail, String targetName) {
        if (!CobblemonIntegration.isCobblemonLoaded()) {
            sendMessage("Cobblemon não está instalado. Eu não consigo verificar estatísticas de Pokémon!");
            return;
        }

        // Find nearby Pokemon
        List<Entity> nearbyPokemon = companion.level().getEntities(
                companion,
                companion.getBoundingBox().inflate(16),
                CobblemonIntegration::isPokemon
        );

        if (nearbyPokemon.isEmpty()) {
            sendMessage("Não vejo nenhum Pokémon por perto para verificar as estatísticas.");
            return;
        }

        // If a target name was specified, try to find that Pokemon
        Entity targetPokemon = null;
        if (targetName != null && !targetName.isEmpty()) {
            String searchName = targetName.toLowerCase();
            for (Entity pokemon : nearbyPokemon) {
                String pokemonName = CobblemonIntegration.getPokemonDisplayName(pokemon);
                String speciesName = CobblemonIntegration.getPokemonSpeciesName(pokemon);
                if (pokemonName != null && pokemonName.toLowerCase().contains(searchName)) {
                    targetPokemon = pokemon;
                    break;
                }
                if (speciesName != null && speciesName.toLowerCase().contains(searchName)) {
                    targetPokemon = pokemon;
                    break;
                }
            }

            if (targetPokemon == null) {
                sendMessage("Eu não consigo encontrar um Pokémon chamado '" + targetName + "' por perto.");
                // List what's available
                StringBuilder sb = new StringBuilder("Eu consigo ver: ");
                for (int i = 0; i < Math.min(nearbyPokemon.size(), 5); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(CobblemonIntegration.getPokemonSummary(nearbyPokemon.get(i)));
                }
                sendMessage(sb.toString());
                return;
            }
        } else {
            // No target specified - use the nearest Pokemon
            targetPokemon = nearbyPokemon.stream()
                    .min((a, b) -> Double.compare(companion.distanceTo(a), companion.distanceTo(b)))
                    .orElse(null);
        }

        if (targetPokemon == null) {
            sendMessage("Eu não consegui encontrar um Pokémon para verificar.");
            return;
        }

        // Get and display stats
        if (detail.equals("brief")) {
            String stats = CobblemonIntegration.getBriefPokemonStats(targetPokemon);
            sendMessage(stats != null ? stats : "Não consegui ler as estatísticas.");
        } else {
            String stats = CobblemonIntegration.getFullPokemonStats(targetPokemon);
            if (stats != null) {
                // Split into multiple messages for readability
                String[] lines = stats.split("\n");
                for (String line : lines) {
                    if (!line.isEmpty()) {
                        sendMessage(line);
                    }
                }
            } else {
                sendMessage("Não consegui ler as estatísticas do Pokémon.");
            }
        }
    }

    /**
     * Find nearby elevator block.
     */
    private BlockPos findNearbyElevator(int radius) {
        BlockPos companionPos = companion.blockPosition();

        for (int x = -radius; x <= radius; x++) {
            for (int y = -5; y <= 5; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos checkPos = companionPos.offset(x, y, z);
                    if (companion.level() instanceof ServerLevel serverLevel) {
                        net.minecraft.world.level.block.state.BlockState state = serverLevel.getBlockState(checkPos);
                        String blockName = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                .getKey(state.getBlock()).toString();
                        if (blockName.contains("elevator")) {
                            return checkPos;
                        }
                    }
                }
            }
        }
        return null;
    }

    private void sendMessage(String message) {
        sendMessageToAll(message);
    }

    /**
     * Send message to all nearby players.
     */
    private void sendMessageToAll(String message) {
        if (!Config.BROADCAST_COMPANION_CHAT.get()) return;

        String formatted = "[" + companion.getCompanionName() + "] " + message;
        Component component = Component.literal(formatted);

        // Send to all players within 64 blocks
        List<Player> nearbyPlayers = companion.level().getEntitiesOfClass(
                Player.class,
                companion.getBoundingBox().inflate(64),
                Player::isAlive
        );

        for (Player player : nearbyPlayers) {
            player.sendSystemMessage(component);
        }

        LLMoblings.LOGGER.debug("[{}] Broadcast to {} players: {}", companion.getCompanionName(), nearbyPlayers.size(), message);
    }

    public AIState getCurrentState() {
        return currentState;
    }

    public void onCompanionHurt() {
        personality.onHurt();
    }

    public enum AIState {
        IDLE,
        FOLLOWING,
        GOING_TO,
        MINING,
        ATTACKING,
        DEFENDING,
        AUTONOMOUS,
        BUILDING
    }
}
