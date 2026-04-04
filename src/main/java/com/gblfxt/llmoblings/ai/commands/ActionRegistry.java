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

    private static final String MOV = "MOVIMENTO";
    private static final String COMB = "COMBATE";
    private static final String REC = "RECURSOS";
    private static final String INV = "INVENTÁRIO";
    private static final String EQP = "EQUIPAMENTO";
    private static final String UTI = "UTILIDADES";
    private static final String HOME = "CASA";
    private static final String TP = "TELEPORTE";
    private static final String BUILD = "CONSTRUÇÃO";
    private static final String PKM = "POKÉMON BUDDY";
    private static final String GADGET = "BUILDING GADGETS";
    private static final String BACKPACK = "SOPHISTICATED BACKPACKS";

    private static ActionDoc doc(String categoria, String json, String descricao) {
        return new ActionDoc(categoria, json, descricao);
    }

    private static final List<ActionDoc> ACTIONS = List.of(
        doc(MOV, "{\"action\": \"follow\"}", "Seguir o jogador"),
        doc(MOV, "{\"action\": \"stay\"}", "Parar e ficar no lugar"),
        doc(MOV, "{\"action\": \"goto\", \"x\": X, \"y\": Y, \"z\": Z}", "Ir para coordenadas específicas"),
        doc(MOV, "{\"action\": \"come\"}", "Vir até a localização do jogador"),

        doc(COMB, "{\"action\": \"attack\", \"target\": \"zombie\"}", "Atacar um mob específico"),
        doc(COMB, "{\"action\": \"defend\"}", "Defender o jogador de inimigos"),
        doc(COMB, "{\"action\": \"retreat\"}", "Recuar diante do perigo"),

        doc(REC, "{\"action\": \"mine\", \"block\": \"diamond_ore\", \"count\": X}", "Minerar blocos específicos"),
        doc(REC, "{\"action\": \"gather\", \"item\": \"oak_log\", \"count\": X}", "Coletar itens específicos"),
        doc(REC, "{\"action\": \"farm\"}", "Cuidar e colher plantações próximas"),

        doc(INV, "{\"action\": \"equip\"}", "Equipar a melhor arma do inventário"),
        doc(INV, "{\"action\": \"inventory\"}", "Relatar o conteúdo do inventário"),
        doc(INV, "{\"action\": \"give\", \"item\": \"diamond\", \"count\": X}", "Entregar itens ao jogador"),

        doc(EQP, "{\"action\": \"getgear\", \"material\": \"iron\"}", "Buscar conjunto de ferro na rede ME (cria se necessário)"),
        doc(EQP, "{\"action\": \"getgear\", \"material\": \"diamond\"}", "Buscar conjunto de diamante na rede ME"),
        doc(EQP, "{\"action\": \"deposit\"}", "Depositar todos os itens na rede ME ou em um baú próximo, mantendo o equipamento"),
        doc(EQP, "{\"action\": \"deposit\", \"keepGear\": false}", "Depositar tudo, inclusive armas e armaduras"),

        doc(UTI, "{\"action\": \"status\"}", "Relatar vida, fome e inventário"),
        doc(UTI, "{\"action\": \"scan\", \"radius\": X}", "Escanear recursos e mobs em um raio"),
        doc(UTI, "{\"action\": \"auto\"}", "Agir de forma totalmente autônoma"),
        doc(UTI, "{\"action\": \"idle\"}", "Só conversar, sem executar ação"),

        doc(HOME, "{\"action\": \"home\"}", "Teleportar para casa"),
        doc(HOME, "{\"action\": \"sethome\"}", "Definir a posição atual como casa"),
        doc(HOME, "{\"action\": \"sleep\"}", "Dormir na cama mais próxima"),

        doc(TP, "{\"action\": \"tpa\", \"target\": \"player\"}", "Teleportar até um jogador"),
        doc(TP, "{\"action\": \"tpaccept\"}", "Aceitar pedido de teleporte"),
        doc(TP, "{\"action\": \"tpdeny\"}", "Recusar pedido de teleporte"),

        doc(BUILD, "{\"action\": \"build\", \"structure\": \"cottage\", \"here\": true}", "Construir uma cottage no local atual"),
        doc(BUILD, "{\"action\": \"build\", \"structure\": \"cottage\", \"x\": X, \"y\": Y, \"z\": Z}", "Construir em coordenadas específicas"),

        doc(PKM, "{\"action\": \"pokemon\", \"subaction\": \"find\"}", "Criar vínculo com o Pokémon mais próximo do jogador"),
        doc(PKM, "{\"action\": \"pokemon\", \"subaction\": \"find\", \"name\": \"Pikachu\"}", "Criar vínculo com um Pokémon específico"),
        doc(PKM, "{\"action\": \"pokemon\", \"subaction\": \"release\"}", "Liberar o buddy atual"),
        doc(PKM, "{\"action\": \"pokemon\", \"subaction\": \"status\"}", "Verificar o buddy atual"),

        doc(GADGET, "{\"action\": \"gadget\", \"subaction\": \"info\"}", "Ver o gadget atual e sua configuração"),
        doc(GADGET, "{\"action\": \"gadget\", \"subaction\": \"equip\"}", "Equipar um gadget do inventário"),
        doc(GADGET, "{\"action\": \"gadget\", \"subaction\": \"setblock\", \"block\": \"stone\"}", "Definir o bloco que o gadget irá colocar"),
        doc(GADGET, "{\"action\": \"gadget\", \"subaction\": \"setrange\", \"range\": X}", "Definir o alcance do gadget"),
        doc(GADGET, "{\"action\": \"gadget\", \"subaction\": \"configure\", \"block\": \"cobblestone\", \"range\": X}", "Configurar bloco e alcance do gadget"),
        doc(GADGET, "{\"action\": \"gadget\", \"subaction\": \"build\"}", "Usar o gadget para colocar blocos"),

        doc(BACKPACK, "{\"action\": \"backpack\", \"subaction\": \"info\"}", "Ver o status da mochila"),
        doc(BACKPACK, "{\"action\": \"backpack\", \"subaction\": \"store\", \"item\": \"cobblestone\"}", "Guardar um item específico na mochila"),
        doc(BACKPACK, "{\"action\": \"backpack\", \"subaction\": \"storeall\"}", "Guardar todos os itens não essenciais na mochila"),
        doc(BACKPACK, "{\"action\": \"backpack\", \"subaction\": \"get\", \"item\": \"diamond\", \"count\": X}", "Retirar itens da mochila"),
        doc(BACKPACK, "{\"action\": \"backpack\", \"subaction\": \"list\"}", "Listar o conteúdo da mochila")
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
