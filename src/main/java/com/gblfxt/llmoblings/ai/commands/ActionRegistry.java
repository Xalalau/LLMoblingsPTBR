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
import net.minecraft.world.entity.EquipmentSlot;
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
    private static final String ME = "REDE ME"; // ME NETWORK
    private static final String UTI = "UTILIDADES";
    private static final String HOME = "CASA";
    private static final String TP = "TELEPORTE";
    private static final String BUILD = "CONSTRUÇÃO";
    private static final String PKM = "POKÉMON BUDDY";
    private static final String GADGET = "BUILDING GADGETS";
    private static final String BACKPACK = "SOPHISTICATED BACKPACKS";

    // Exemplos necessários para o bot saber o que ele pode fazer caso nenhum comando seja resolvido no código
    private static ActionDoc doc(String categoria, String json, String descricao) {
        return new ActionDoc(categoria, json, descricao);
    }

        private static final List<ActionDoc> ACTIONS = List.of(
        doc(MOV, "{\"action\": \"follow\"}", "Seguir o jogador"),
        doc(MOV, "{\"action\": \"stay\"}", "Parar e ficar no lugar"),
        doc(MOV, "{\"action\": \"goto\", \"x\": X, \"y\": Y, \"z\": Z}", "Ir para coordenadas específicas"),
        doc(MOV, "{\"action\": \"come\"}", "Vir até a localização do jogador"),
        doc(MOV, "{\"action\": \"jump\"}", "Pular uma vez"),

        doc(COMB, "{\"action\": \"attack\", \"target\": \"zombie\"}", "Atacar um mob específico"),
        doc(COMB, "{\"action\": \"defend\"}", "Defender o jogador de inimigos"),
        doc(COMB, "{\"action\": \"retreat\"}", "Recuar diante do perigo"),

        doc(REC, "{\"action\": \"mine\", \"block\": \"diamond_ore\", \"count\": X}", "Minerar blocos específicos"),
        doc(REC, "{\"action\": \"gather\", \"item\": \"oak_log\", \"count\": X}", "Coletar itens específicos"),
        doc(REC, "{\"action\": \"farm\"}", "Cuidar e colher plantações próximas"),

        doc(INV, "{\"action\": \"equip\"}", "Equipar a melhor arma do inventário"),
        doc(INV, "{\"action\": \"inventory\"}", "Relatar o conteúdo do inventário"),
        doc(INV, "{\"action\": \"give\", \"item\": \"diamond\", \"count\": X}", "Entregar itens ao jogador"),
        doc(INV, "{\"action\": \"give\", \"item\": \"all\", \"count\": 999}", "Entregar tudo o que estiver no inventário ao jogador"),
        doc(INV, "{\"action\": \"give\", \"item\": \"food\", \"count\": X}", "Entregar comida ao jogador"),
        doc(INV, "{\"action\": \"deposititem\", \"item\": \"wheat\", \"count\": 1}", "Guardar um item específico no baú mais próximo"),
        doc(INV, "{\"action\": \"takefromchest\", \"item\": \"carrot\", \"count\": 1}", "Retirar um item específico de um baú próximo ou do baú indicado pelo jogador"),
        doc(INV, "{\"action\": \"takefromchest\", \"item\": \"iron\", \"count\": X}", "Pegar uma quantidade específica de um item de um baú"),

        doc(ME, "{\"action\": \"getgear\", \"material\": \"iron\"}", "Buscar conjunto de ferro na rede ME, em baús próximos ou fabricar se possível"),
        doc(ME, "{\"action\": \"getgear\", \"material\": \"diamond\"}", "Buscar conjunto de diamante na rede ME ou em baús próximos"),
        doc(ME, "{\"action\": \"getgear\", \"material\": \"any\"}", "Buscar uma arma ou armadura em baús próximos, na rede ME ou fabricar se possível"),
        doc(ME, "{\"action\": \"getgear\", \"gearType\": \"armor\", \"slot\": \"feet\"}", "Buscar botas ou peça equivalente de armadura"),
        doc(ME, "{\"action\": \"getgear\", \"gearType\": \"weapon\", \"match\": \"sword\"}", "Buscar uma espada específica"),
        doc(ME, "{\"action\": \"deposit\"}", "Depositar todos os itens na rede ME ou em um baú próximo, mantendo o equipamento"),
        doc(ME, "{\"action\": \"deposit\", \"keepGear\": false}", "Depositar tudo, inclusive armas e armaduras"),

        doc(UTI, "{\"action\": \"status\"}", "Relatar vida, fome e inventário"),
        doc(UTI, "{\"action\": \"scan\", \"radius\": X}", "Escanear recursos e mobs em um raio"),
        doc(UTI, "{\"action\": \"auto\"}", "Agir de forma totalmente autônoma"),
        doc(UTI, "{\"action\": \"idle\"}", "Só conversar, sem executar ação"),

        doc(HOME, "{\"action\": \"home\"}", "Teleportar para casa"),
        doc(HOME, "{\"action\": \"sethome\"}", "Definir a posição atual como casa"),
        doc(HOME, "{\"action\": \"sleep\"}", "Dormir na cama mais próxima"),

        doc(TP, "{\"action\": \"tpa\", \"target\": \"player\"}", "Teleportar até ou para um jogador"),
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
        RESOURCE_ALIASES.put("comida", "food");
        RESOURCE_ALIASES.put("alimento", "food");
        RESOURCE_ALIASES.put("alimentos", "food");
        RESOURCE_ALIASES.put("rango", "food");
        RESOURCE_ALIASES.put("trigo", "wheat");
        RESOURCE_ALIASES.put("wheat", "wheat");
        RESOURCE_ALIASES.put("pao", "bread");
        RESOURCE_ALIASES.put("pão", "bread");
        RESOURCE_ALIASES.put("bread", "bread");
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

        if (isExplicitlyAmbiguous(normalized)) {
            return null;
        }

        CompanionAction storageAction = tryResolveStorageCommand(companion, sender, normalized);
        if (storageAction != null) {
            return storageAction;
        }

        // Não tentar adivinhar continuação vaga para frases restantes que citam armazenamento.
        if (containsStorageWord(normalized)) {
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


    private static @Nullable CompanionAction tryResolveStorageCommand(
        CompanionEntity companion,
        @Nullable Player sender,
        String normalized
    ) {
        if (!containsStorageWord(normalized)) {
            return null;
        }

        boolean wantsTake = containsAny(normalized,
            "pega", "pegue", "tirar", "tira", "retira", "retire", "busca", "buscar", "get", "take", "retrieve", "grab"
        );
        boolean wantsDeposit = containsAny(normalized,
            "guarda", "guardar", "deposita", "deposita", "coloca", "colocar", "stash", "store", "put"
        );

        if (!wantsTake && !wantsDeposit) {
            return null;
        }

        if (wantsTake) {
            String resource = extractResource(normalized);
            if (resource == null || resource.isBlank()) {
                return null;
            }
            JsonObject data = new JsonObject();
            data.addProperty("item", resource);
            data.addProperty("count", extractCount(normalized));
            BlockPos ref = sender != null ? sender.blockPosition() : companion.blockPosition();
            data.addProperty("x", ref.getX());
            data.addProperty("y", ref.getY());
            data.addProperty("z", ref.getZ());
            data.addProperty("preferSenderChest", containsAny(normalized, "esse bau", "esse baú", "desse bau", "desse baú", "daqui", "aqui", "deste bau", "deste baú"));
            return new CompanionAction("takefromchest", null, data);
        }

        String resource = extractResource(normalized);
        if (resource != null && !resource.isBlank() && !containsAny(normalized, "guarda tudo", "deposita tudo", "esvazia")) {
            JsonObject data = new JsonObject();
            data.addProperty("item", resource);
            data.addProperty("count", extractRequestedCount(normalized));
            data.addProperty("keepGear", true);
            return new CompanionAction("deposititem", null, data);
        }

        JsonObject data = new JsonObject();
        data.addProperty("keepGear", !containsAny(normalized, "tudo", "inclusive armadura", "inclusive arma"));
        return new CompanionAction("deposit", null, data);
    }

    private static int extractCount(String normalized) {
        Matcher matcher = COUNT_PATTERN.matcher(normalized);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 16;
    }

    private static @Nullable String extractResource(String normalized) {
        for (Map.Entry<String, String> entry : RESOURCE_ALIASES.entrySet()) {
            if (normalized.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
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

    private static @Nullable EquipmentSlot resolveRequestedArmorSlot(String normalized) {
        if (containsAny(normalized, "capacete", "helmet", "elmo")) {
            return EquipmentSlot.HEAD;
        }
        if (containsAny(normalized, "peitoral", "chestplate", "couraca", "couracas")) {
            return EquipmentSlot.CHEST;
        }
        if (containsAny(normalized, "calca", "calcas", "leggings", "legging", "perneira", "perneiras")) {
            return EquipmentSlot.LEGS;
        }
        if (containsAny(normalized, "bota", "botas", "boots", "boot", "sapato", "sapatos")) {
            return EquipmentSlot.FEET;
        }
        return null;
    }

    private static @Nullable CompanionAction buildGearAction(String action, String normalized) {
        JsonObject data = new JsonObject();
        String material = containsAny(normalized, "diamante", "diamond") ? "diamond" : containsAny(normalized, "ferro", "iron") ? "iron" : "any";
        data.addProperty("material", material);

        EquipmentSlot slot = resolveRequestedArmorSlot(normalized);
        if (slot != null) {
            data.addProperty("gearType", "armor");
            data.addProperty("slot", slot.getName());
            return new CompanionAction(action, null, data);
        }

        if (containsAny(normalized, "armadura", "armor", "equipamento")) {
            data.addProperty("gearType", "armor");
            return new CompanionAction(action, null, data);
        }

        if (containsAny(normalized, "espada", "sword")) {
            data.addProperty("gearType", "weapon");
            data.addProperty("match", "sword");
            return new CompanionAction(action, null, data);
        }

        if (containsAny(normalized, "machado", "axe")) {
            data.addProperty("gearType", "weapon");
            data.addProperty("match", "axe");
            return new CompanionAction(action, null, data);
        }

        if (containsAny(normalized, "arma", "weapon")) {
            data.addProperty("gearType", "weapon");
            return new CompanionAction(action, null, data);
        }

        if (containsAny(normalized, "luva", "luvas", "glove", "gloves", "gauntlet", "gauntlets", "manopla", "manoplas")) {
            data.addProperty("gearType", "item");
            data.addProperty("match", "glove");
            return new CompanionAction(action, null, data);
        }

        if (action.equals("equip")) {
            return new CompanionAction(action, null);
        }
        data.addProperty("gearType", "any");
        return new CompanionAction(action, null, data);
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
            normalized.equals("pula") || normalized.equals("pule") || normalized.equals("salta") || normalized.equals("salte") ||
            containsAny(normalized, "da um pulo", "dá um pulo", "pula aqui", "pule aqui")
        ) {
            return new CompanionAction("jump", null);
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
                "equipe o ",
                "usa sua melhor arma ",
                "pega sua melhor arma ",
                "equipa uma arma ",
                "equipe uma arma ",
                "equipa a espada ",
                "equipe a espada ",
                "usa uma arma ",
                "use uma arma ",
                "equipa uma bota ",
                "equipa botas ",
                "equipa um peitoral ",
                "equipa um capacete ",
                "equipa uma calca ",
                "equipa uma calca ",
                "equipa uma luva "
            )
        ) {
            return buildGearAction("equip", normalized);
        }

        if (containsAny(normalized,
                "pega uma armadura", "pegue uma armadura", "arranja uma armadura", "busca uma armadura",
                "pega um equipamento", "pegue um equipamento", "traz uma armadura", "quero uma armadura",
                "pega uma bota", "pegue uma bota", "pega botas", "pegue botas",
                "pega uma luva", "pegue uma luva", "pega luvas", "pegue luvas",
                "pega um peitoral", "pegue um peitoral", "pega um capacete", "pegue um capacete",
                "pega uma espada", "pegue uma espada", "pega uma arma", "pegue uma arma")) {
            return buildGearAction("getgear", normalized);
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

        if (containsAny(
            normalized,
            "me da tudo",
            "me dá tudo",
            "me de tudo",
            "me dê tudo",
            "me passa tudo",
            "me entregue tudo",
            "tudo o que voce tem",
            "tudo que voce tem",
            "tudo o que vc tem",
            "tudo que vc tem",
            "seus recursos",
            "seus itens",
            "seu inventario",
            "seu inventário",
            "todo seu inventario",
            "todo seu inventário"
        )) {
            JsonObject data = new JsonObject();
            data.addProperty("item", "all");
            data.addProperty("count", 999);
            return new CompanionAction("give", null, data);
        }

        String item = resolveSingleResource(normalized);
        if (item == null && containsAny(normalized, "comida", "alimento", "alimentos", "food", "rango")) {
            item = "food";
        }
        if (item == null && containsAny(normalized, "recurso", "recursos", "itens", "items", "inventario", "inventário")) {
            item = "all";
        }
        if (item == null) {
            return null;
        }

        int count = extractRequestedCount(normalized);
        if (item.equals("food") || item.equals("all") || containsAny(normalized, "todas as", "todos os", "todo o", "toda a", "tudo de")) {
            count = 999;
        }

        JsonObject data = new JsonObject();
        data.addProperty("item", item);
        data.addProperty("count", count);
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
        if (containsAny(normalized, "todas as", "todos os", "todo o", "toda a")) {
            return 999;
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
