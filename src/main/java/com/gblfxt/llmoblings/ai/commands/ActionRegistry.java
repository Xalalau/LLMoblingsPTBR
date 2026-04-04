package com.gblfxt.llmoblings.ai.commands;

import com.gblfxt.llmoblings.ai.CompanionAction;
import com.gblfxt.llmoblings.entity.CompanionEntity;
import com.google.gson.JsonObject;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Resolver pequeno e conservador para comandos diretos.
 *
 * Objetivo: só interceptar ordens inequívocas. Qualquer frase vaga, contextual
 * ou ambígua deve retornar null para cair no restante do pipeline.
 */
public final class ActionRegistry {

    private ActionRegistry() {}

    private record ActionDoc(String group, String json, String description) {}

    private static final List<ActionDoc> ACTIONS = List.of(
        new ActionDoc(
            "MOVIMENTO",
            "{\"action\": \"follow\"}",
            "Seguir o jogador"
        ),
        new ActionDoc("MOVIMENTO", "{\"action\": \"stay\"}", "Parar no lugar"),
        new ActionDoc(
            "MOVIMENTO",
            "{\"action\": \"come\"}",
            "Vir até o jogador"
        ),
        new ActionDoc(
            "MOVIMENTO",
            "{\"action\": \"goto\", \"x\": 100, \"y\": 64, \"z\": 200}",
            "Ir para coordenadas"
        ),
        new ActionDoc(
            "RECURSOS",
            "{\"action\": \"gather\", \"item\": \"dirt\", \"count\": 16}",
            "Coletar um recurso nomeado"
        ),
        new ActionDoc(
            "UTILIDADES",
            "{\"action\": \"status\"}",
            "Relatar estado atual"
        ),
        new ActionDoc(
            "UTILIDADES",
            "{\"action\": \"inventory\"}",
            "Relatar inventário"
        ),
        new ActionDoc(
            "UTILIDADES",
            "{\"action\": \"deposit\"}",
            "Guardar itens próximos"
        ),
        new ActionDoc(
            "COMBATE",
            "{\"action\": \"defend\"}",
            "Defender o jogador"
        ),
        new ActionDoc("COMBATE", "{\"action\": \"retreat\"}", "Recuar"),
        new ActionDoc(
            "UTILIDADES",
            "{\"action\": \"equip\"}",
            "Equipar o melhor equipamento disponível"
        ),
        new ActionDoc("CASA", "{\"action\": \"sethome\"}", "Marcar casa aqui"),
        new ActionDoc("CASA", "{\"action\": \"home\"}", "Ir para casa"),
        new ActionDoc(
            "CASA",
            "{\"action\": \"sleep\"}",
            "Dormir na cama mais próxima"
        )
    );

    private static final Pattern COUNT_PATTERN = Pattern.compile(
        "(?:^|\\D)(\\d{1,3})(?:\\D|$)"
    );
    private static final Pattern GOTO_PATTERN = Pattern.compile(
        "(?:^|.*\\b(?:vai|ir|segue|siga|anda|andar|goto)\\b.*?)(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)(?:$|.*)"
    );

    private static final Map<String, String> RESOURCE_ALIASES =
        new LinkedHashMap<>();

    static {
        RESOURCE_ALIASES.put("terra", "dirt");
        RESOURCE_ALIASES.put("dirt", "dirt");
        RESOURCE_ALIASES.put("pedra", "stone");
        RESOURCE_ALIASES.put("stone", "stone");
        RESOURCE_ALIASES.put("cobblestone", "stone");
        RESOURCE_ALIASES.put("cobble", "stone");
        RESOURCE_ALIASES.put("madeira", "wood");
        RESOURCE_ALIASES.put("tronco", "wood");
        RESOURCE_ALIASES.put("lenha", "wood");
        RESOURCE_ALIASES.put("log", "wood");
        RESOURCE_ALIASES.put("logs", "wood");
        RESOURCE_ALIASES.put("carvao", "coal");
        RESOURCE_ALIASES.put("coal", "coal");
        RESOURCE_ALIASES.put("ferro", "iron");
        RESOURCE_ALIASES.put("iron", "iron");
        RESOURCE_ALIASES.put("cenoura", "carrot");
        RESOURCE_ALIASES.put("cenouras", "carrot");
        RESOURCE_ALIASES.put("carrot", "carrot");
        RESOURCE_ALIASES.put("carrots", "carrot");
        RESOURCE_ALIASES.put("diamante", "diamond");
        RESOURCE_ALIASES.put("diamond", "diamond");
        RESOURCE_ALIASES.put("ouro", "gold");
        RESOURCE_ALIASES.put("gold", "gold");
    }

    private static final Set<String> STORAGE_WORDS = Set.of(
        "bau",
        "baú",
        "barril",
        "chest",
        "barrel",
        "container",
        "armazenamento",
        "deposito",
        "depósito"
    );

    public static String buildPromptSection() {
        StringBuilder sb = new StringBuilder();
        String currentGroup = null;
        for (ActionDoc action : ACTIONS) {
            if (!action.group.equals(currentGroup)) {
                currentGroup = action.group;
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                sb.append(currentGroup).append(":\n");
            }
            sb
                .append("- ")
                .append(action.json)
                .append(" - ")
                .append(action.description)
                .append("\n");
        }
        return sb.toString().trim();
    }

    public static @Nullable CompanionAction tryResolveDirectCommand(
        CompanionEntity companion,
        @Nullable Player sender,
        String message,
        @Nullable CompanionAction lastExplicitCommand,
        int lastActionAgeTicks
    ) {
        if (message == null || message.isBlank()) {
            return null;
        }

        String normalized = normalize(message);
        if (normalized.isBlank()) {
            return null;
        }

        // Não tentar adivinhar continuação vaga ou comandos que citam armazenamento.
        if (
            containsStorageWord(normalized) || isExplicitlyAmbiguous(normalized)
        ) {
            return null;
        }

        CompanionAction action = tryResolveHomeCommand(
            companion,
            sender,
            normalized
        );
        if (action != null) {
            return action;
        }

        action = tryResolveTeleportCommand(sender, normalized);
        if (action != null) {
            return action;
        }

        action = tryResolveMovementCommand(normalized);
        if (action != null) {
            return action;
        }

        action = tryResolveUtilityCommand(normalized);
        if (action != null) {
            return action;
        }

        action = tryResolveCombatCommand(normalized);
        if (action != null) {
            return action;
        }

        action = tryResolveBuildCommand(normalized);
        if (action != null) {
            return action;
        }

        action = tryResolveGotoCommand(normalized);
        if (action != null) {
            return action;
        }

        action = tryResolveGiveCommand(normalized);
        if (action != null) {
            return action;
        }

        return tryResolveGatherCommand(normalized);
    }

    private static @Nullable CompanionAction tryResolveHomeCommand(
        CompanionEntity companion,
        @Nullable Player sender,
        String normalized
    ) {
        if (
            containsAny(
                normalized,
                "marca aqui como casa ",
                "marca aqui como sua casa ",
                "define aqui como casa ",
                "define aqui como sua casa ",
                "essa e sua casa ",
                "essa é sua casa ",
                "aqui e sua casa ",
                "aqui é sua casa "
            )
        ) {
            BlockPos pos =
                sender != null
                    ? sender.blockPosition()
                    : companion.blockPosition();
            JsonObject data = new JsonObject();
            data.addProperty("x", pos.getX());
            data.addProperty("y", pos.getY());
            data.addProperty("z", pos.getZ());
            return new CompanionAction("sethome", null, data);
        }

        if (
            containsAny(
                normalized,
                "vai pra casa ",
                "vai para casa ",
                "volta pra casa ",
                "volta para casa ",
                "vai pra sua casa ",
                "vai para sua casa ",
                "volta pra sua casa ",
                "volta para sua casa "
            )
        ) {
            return new CompanionAction("home", null);
        }

        return null;
    }

    private static @Nullable CompanionAction tryResolveTeleportCommand(
        @Nullable Player sender,
        String normalized
    ) {
        if (sender == null) {
            return null;
        }

        if (
            containsAny(
                normalized,
                "teleporta pra mim ",
                "teleporta para mim ",
                "teletransporta pra mim ",
                "teletransporta para mim ",
                "tp pra mim ",
                "tp para mim ",
                "tpa pra mim ",
                "tpa para mim "
            )
        ) {
            JsonObject data = new JsonObject();
            data.addProperty("target", sender.getName().getString());
            return new CompanionAction("tpa", null, data);
        }

        return null;
    }

    private static @Nullable CompanionAction tryResolveMovementCommand(
        String normalized
    ) {
        if (
            containsAny(
                normalized,
                "me segue ",
                "segue me ",
                "segue comigo ",
                "vem comigo ",
                "siga me "
            )
        ) {
            return new CompanionAction("follow", null);
        }

        if (
            containsAny(
                normalized,
                "fica aqui ",
                "espera aqui ",
                "espere aqui ",
                "para aqui ",
                "pare aqui "
            ) ||
            normalized.equals("fica") ||
            normalized.equals("pare") ||
            normalized.equals("parar")
        ) {
            return new CompanionAction("stay", null);
        }

        if (
            containsAny(
                normalized,
                "vem aqui ",
                "venha aqui ",
                "vem ate mim ",
                "vem até mim ",
                "vai ate mim ",
                "vai até mim ",
                "cola em mim "
            )
        ) {
            return new CompanionAction("come", null);
        }

        if (
            containsAny(
                normalized,
                "modo autonomo '",
                "modo autônomo ",
                "fica autonomo ",
                "fique autonomo ",
                "fica autonomo ",
                "seja autonomo ",
                "aja sozinho ",
                "aja por conta propria ",
                "faz o que você quiser "
            )
        ) {
            return new CompanionAction("auto", null);
        }

        return null;
    }

    private static @Nullable CompanionAction tryResolveUtilityCommand(
        String normalized
    ) {
        if (
            containsAny(
                normalized,
                "qual é o status ",
                "como voce esta ",
                "como vc esta ",
                "diga o relatório ",
                "me dá um relatório "
            )
        ) {
            return new CompanionAction("status", null);
        }

        if (
            containsAny(
                normalized,
                "inventario ",
                "inventário ",
                "o que voce tem ",
                "o que vc tem ",
                "mostra o inventario ",
                "me mostra o inventário "
            )
        ) {
            return new CompanionAction("inventory", null);
        }

        if (
            containsAny(
                normalized,
                "guarda seus itens",
                "guarda tudo ",
                "deposita tudo ",
                "esvazia o inventario ",
                "esvazia o inventário "
            )
        ) {
            return new CompanionAction("deposit", null);
        }

        if (
            containsAny(
                normalized,
                "equipa um ",
                "equipa a ",
                "equipa uma ",
                "equipa o ",
                "equipe um ",
                "equipe uma ",
                "usa sua melhor arma ",
                "pega sua melhor arma "
            )
        ) {
            return new CompanionAction("equip", null);
        }

        if (containsAny(normalized, "dorme", "vai dormir", "deita na cama")) {
            return new CompanionAction("sleep", null);
        }

        if (
            containsAny(
                normalized,
                "escaneia a área ",
                "escaneie a área ",
                "olha ao redor ",
                "olha ao redor ",
                "ve a area ",
                "vê a área "
            )
        ) {
            JsonObject data = new JsonObject();
            data.addProperty("radius", normalized.contains("longe") ? 48 : 24);
            return new CompanionAction("scan", null, data);
        }

        if (
            containsAny(
                normalized,
                "colhe a fazenda ",
                "cuida da fazenda ",
                "vai pra fazenda ",
                "vai para a fazenda "
            )
        ) {
            JsonObject data = new JsonObject();
            data.addProperty("radius", normalized.contains("longe") ? 32 : 24);
            return new CompanionAction("farm", null, data);
        }

        return null;
    }

    private static @Nullable CompanionAction tryResolveCombatCommand(
        String normalized
    ) {
        if (
            containsAny(
                normalized,
                "me defende ",
                "me defenda ",
                "me protege ",
                "me proteja "
            )
        ) {
            return new CompanionAction("defend", null);
        }

        if (
            containsAny(
                normalized,
                "recuar ",
                "recuar agora ",
                "foge ",
                "fuja ",
                "retreat "
            )
        ) {
            return new CompanionAction("retreat", null);
        }

        return null;
    }

    private static @Nullable CompanionAction tryResolveBuildCommand(
        String normalized
    ) {
        if (
            containsAny(
                normalized,
                "construa uma cottage aqui ",
                "construir uma cottage aqui ",
                "constrói uma cottage aqui "
            )
        ) {
            JsonObject data = new JsonObject();
            data.addProperty("structure", "cottage");
            data.addProperty("here", true);
            return new CompanionAction("build", null, data);
        }

        return null;
    }

    private static @Nullable CompanionAction tryResolveGotoCommand(
        String normalized
    ) {
        Matcher matcher = GOTO_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            return null;
        }

        JsonObject data = new JsonObject();
        data.addProperty("x", Integer.parseInt(matcher.group(1)));
        data.addProperty("y", Integer.parseInt(matcher.group(2)));
        data.addProperty("z", Integer.parseInt(matcher.group(3)));
        return new CompanionAction("goto", null, data);
    }

    private static @Nullable CompanionAction tryResolveGiveCommand(
        String normalized
    ) {
        if (
            !containsAny(
                normalized,
                "me da ",
                "me dá ",
                "me de ",
                "me dê ",
                "me passa ",
                "me entregue ",
                "entrega pra mim ",
                "entregue pra mim "
            )
        ) {
            return null;
        }

        String item = resolveSingleResource(normalized);
        if (item == null) {
            return null;
        }

        JsonObject data = new JsonObject();
        data.addProperty("item", item);
        data.addProperty("count", extractRequestedCount(normalized));
        return new CompanionAction("give", null, data);
    }

    private static @Nullable CompanionAction tryResolveGatherCommand(
        String normalized
    ) {
        if (!startsWithResourceVerb(normalized)) {
            return null;
        }

        String item = resolveSingleResource(normalized);
        if (item == null) {
            return null;
        }

        JsonObject data = new JsonObject();
        data.addProperty("item", item);
        data.addProperty("count", extractRequestedCount(normalized));
        data.addProperty(
            "radius",
            normalized.contains("longe") || normalized.contains("mais longe")
                ? 48
                : 32
        );
        return new CompanionAction("gather", null, data);
    }

    private static boolean startsWithResourceVerb(String normalized) {
        return startsWithAny(
            normalized,
            "me vê ",
            "pega ",
            "pegue ",
            "acha ",
            "arranja ",
            "coleta ",
            "colete ",
            "busca ",
            "buscar ",
            "procura ",
            "procure ",
            "minera ",
            "minerar ",
            "mina ",
            "mine ",
            "cata ",
            "corta ",
            "corte ",
            "traz ",
            "vai buscar "
        );
    }

    private static @Nullable String resolveSingleResource(String normalized) {
        Set<String> found = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : RESOURCE_ALIASES.entrySet()) {
            if (containsWord(normalized, entry.getKey())) {
                found.add(entry.getValue());
            }
        }

        return found.size() == 1 ? found.iterator().next() : null;
    }

    private static boolean containsStorageWord(String normalized) {
        for (String word : STORAGE_WORDS) {
            if (containsWord(normalized, word)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExplicitlyAmbiguous(String normalized) {
        return containsAny(
            normalized,
            "pega mais ",
            "pegue mais ",
            "mais do mesmo ",
            "continua ",
            "continue ",
            "vai nisso ",
            "isso ai ",
            "isso aí "
        );
    }

    private static int extractRequestedCount(String normalized) {
        Matcher matcher = COUNT_PATTERN.matcher(normalized);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        if (containsWord(normalized, "um") || containsWord(normalized, "uma")) {
            return 1;
        }
        if (
            containsAny(
                normalized,
                " pilha ",
                " bastante ",
                " muito ",
                " muita "
            )
        ) {
            return 32;
        }
        return 16;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "")
            .toLowerCase(Locale.ROOT)
            .trim()
            .replaceAll("\\s+", " ");
    }

    private static boolean containsWord(String haystack, String word) {
        return Pattern.compile("(?:^|\\s)" + Pattern.quote(word) + "(?:$|\\s)")
            .matcher(haystack)
            .find();
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithAny(String haystack, String... prefixes) {
        for (String prefix : prefixes) {
            if (haystack.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
