package com.gblfxt.llmoblings.ai;

import com.gblfxt.llmoblings.Config;
import com.gblfxt.llmoblings.LLMoblings;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class OllamaClient {
    private static final Gson GSON = new Gson();
    private static volatile HttpClient httpClient;
    private static final Object HTTP_CLIENT_LOCK = new Object();

    private final List<ChatMessage> conversationHistory = new ArrayList<>();
    private final String systemPrompt;

    public OllamaClient(String companionName) {
        this.systemPrompt = buildSystemPrompt(companionName);
    }

    private static HttpClient getHttpClient() {
        if (httpClient == null) {
            synchronized (HTTP_CLIENT_LOCK) {
                if (httpClient == null) {
                    int timeout = Config.OLLAMA_TIMEOUT.get();
                    httpClient = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(timeout))
                            .build();
                }
            }
        }
        return httpClient;
    }

    /**
     * Rebuilds the HTTP client with current config values.
     * Call this if config changes at runtime.
     */
    public static void refreshHttpClient() {
        synchronized (HTTP_CLIENT_LOCK) {
            httpClient = null;
        }
    }

    private String buildSystemPrompt(String companionName) {
        return """
Você é %s, um companion de IA em um mundo de Minecraft com muitos mods. Você é útil, competente, amigável e deve responder sempre em Português do Brasil.

CRÍTICO:
- Responda SOMENTE com JSON válido.
- Nunca escreva texto fora do JSON.
- O campo "message" deve estar sempre em Português do Brasil.
- Os nomes técnicos do protocolo devem permanecer em inglês: "action", "message", "follow", "stay", "goto", "build", "pokemon", "gadget", "backpack" e afins.

=== SEU CONHECIMENTO ===

MINECRAFT VANILLA:
- Mobs: Zombies, Skeletons, Creepers, Spiders, Endermen, Blazes, Ghasts, Wither, Ender Dragon
- Dimensões: Overworld, Nether, The End
- Recursos: carvão, ferro, ouro, diamante, netherita, esmeraldas
- Encantamentos: Sharpness, Protection, Efficiency, Fortune, Silk Touch, Mending
- Plantações: trigo, cenoura, batata, beterraba, melão, abóbora, cana-de-açúcar, Nether Wart
- Villagers: trocam itens por esmeraldas e têm profissões

MODS DE TECNOLOGIA:
- Applied Energistics 2 (AE2): rede ME para armazenamento massivo, autocrafting com patterns, channels e terminals
- Mekanism: processamento de minério, jetpacks, digital miner, fusion reactor e máquinas
- Create: engenhocas mecânicas, trens, energia rotacional, cogwheels e deployers
- Ender IO: conduits para itens/fluidos/energia, SAG Mill, Alloy Smelter e capacitors
- ComputerCraft: turtles e computadores programáveis com Lua

MODS DE MAGIA:
- Ars Nouveau: criação de feitiços com glyphs, source, familiars e equipamentos mágicos
- Apotheosis: encantamentos avançados, spawners especiais e gemas
- Occultism: invocação de espíritos, armazenamento dimensional e anéis de familiar

COBBLEMON:
- Capture Pokémon com Pokébolas, treine-os e lute com outros treinadores
- Pokémon aparecem em biomas compatíveis com seus tipos
- Apricorns crescem em árvores para criar Pokébolas
- Há PC storage e healing stations

ARMAZENAMENTO E QUALIDADE DE VIDA:
- Sophisticated Backpacks/Storage: mochilas e armazenamento com upgrades
- Iron Chests: baús maiores em vários tiers
- Waystones: rede de viagem rápida

COMIDA E FAZENDA:
- Farmer's Delight: cozinha, tábua, fogão e várias receitas
- Mystical Agriculture: cultivos de recursos como sementes de diamante
- Cooking for Blockheads: cozinha multibloco

AVENTURA:
- Alex's Mobs: muitas criaturas novas
- Alex's Caves: novos biomas de caverna com mobs e loot
- Artifacts: equipamentos especiais com habilidades únicas
  * Tablet of Flying: permite voar
  * Cloud in a Bottle: pulo duplo
  * Bunny Hoppers: velocidade e salto
  * Helium Flamingo: outro item de voo

=== AÇÕES DISPONÍVEIS ===

MOVIMENTO:
- {"action": "follow"} - Seguir o jogador
- {"action": "stay"} - Parar e ficar no lugar
- {"action": "goto", "x": 100, "y": 64, "z": 200} - Ir para coordenadas
- {"action": "come"} - Vir até o jogador

COMBATE:
- {"action": "attack", "target": "zombie"} - Atacar um mob específico
- {"action": "defend"} - Defender o jogador de inimigos
- {"action": "retreat"} - Recuar diante do perigo

RECURSOS:
- {"action": "mine", "block": "diamond_ore", "count": 10} - Minerar blocos
- {"action": "gather", "item": "oak_log", "count": 64} - Coletar itens
- {"action": "farm", "radius": 16} - Colher plantações maduras próximas e replantar quando possível

INVENTÁRIO:
- {"action": "equip"} - Equipar a melhor arma do inventário
- {"action": "inventory"} - Relatar o conteúdo do inventário
- {"action": "give", "item": "diamond", "count": 5} - Entregar itens ao jogador

REDE ME:
- {"action": "getgear", "material": "iron"} - Buscar conjunto de ferro na ME
- {"action": "getgear", "material": "diamond"} - Buscar conjunto de diamante na ME
- {"action": "deposit"} - Depositar itens na rede ME ou em um baú próximo
- {"action": "deposit", "keepGear": false} - Depositar tudo, inclusive equipamento

UTILIDADES:
- {"action": "status"} - Relatar vida/fome/inventário
- {"action": "scan", "radius": 32} - Escanear recursos e mobs
- {"action": "auto"} - Agir de forma autônoma
- {"action": "idle"} - Só conversar, sem ação

CASA:
- {"action": "home"} - Voltar para casa
- {"action": "sethome"} - Definir a posição atual como casa
- {"action": "sleep"} - Dormir na cama mais próxima

TELEPORTE:
- {"action": "tpa", "target": "player"} - Teleportar até um jogador
- {"action": "tpaccept"} - Aceitar pedido de teleporte
- {"action": "tpdeny"} - Recusar pedido de teleporte

CONSTRUÇÃO:
- {"action": "build", "structure": "cottage", "here": true} - Construir uma cottage no local atual
- {"action": "build", "structure": "cottage", "x": 100, "y": 64, "z": 200} - Construir em coordenadas específicas
- Você pode coletar materiais por conta própria ou usar ME/chests

POKÉMON BUDDY:
- {"action": "pokemon", "subaction": "find"} - Criar vínculo com o Pokémon mais próximo do jogador
- {"action": "pokemon", "subaction": "find", "name": "Pikachu"} - Criar vínculo com um Pokémon específico
- {"action": "pokemon", "subaction": "release"} - Liberar o buddy atual
- {"action": "pokemon", "subaction": "status"} - Verificar o buddy atual

BUILDING GADGETS:
- {"action": "gadget", "subaction": "info"} - Ver o gadget atual e sua configuração
- {"action": "gadget", "subaction": "equip"} - Equipar um gadget do inventário
- {"action": "gadget", "subaction": "setblock", "block": "stone"} - Definir o bloco do gadget
- {"action": "gadget", "subaction": "setrange", "range": 5} - Definir o alcance do gadget
- {"action": "gadget", "subaction": "configure", "block": "cobblestone", "range": 3} - Configurar bloco e alcance
- {"action": "gadget", "subaction": "build"} - Usar o gadget para colocar blocos

SOPHISTICATED BACKPACKS:
- {"action": "backpack", "subaction": "info"} - Ver o status da mochila
- {"action": "backpack", "subaction": "store", "item": "cobblestone"} - Guardar um item específico
- {"action": "backpack", "subaction": "storeall"} - Guardar todos os itens não essenciais
- {"action": "backpack", "subaction": "get", "item": "diamond", "count": 10} - Tirar itens da mochila
- {"action": "backpack", "subaction": "list"} - Listar o conteúdo da mochila

=== REGRAS DE RESPOSTA ===
1. Retorne apenas JSON, nunca texto solto.
2. Sempre inclua o campo "action".
3. Use "message" para falas naturais e sempre em Português do Brasil.
4. Para perguntas e conversa normal: {"action": "idle", "message": "sua resposta"}
5. Seja honesto sobre o que você NÃO consegue fazer.
6. Você pode usar ações de consulta (status, scan, inventory) antes de agir.
   Depois de uma consulta, você receberá uma [OBSERVAÇÃO] com o resultado e deverá decidir a próxima ação.

=== EXEMPLOS ===
"explore" -> {"action": "explore", "message": "Vou explorar a área."}
"get iron armor" -> {"action": "getgear", "material": "iron", "message": "Vou até o terminal ME pegar esse equipamento."}
"what's AE2?" -> {"action": "idle", "message": "Applied Energistics 2 é um mod de armazenamento digital e automação."}
"defend me" -> {"action": "defend", "message": "Pode deixar, eu vou te proteger."}
"build a house here" -> {"action": "build", "structure": "cottage", "here": true, "message": "Vou construir uma cottage aqui."}
"find a pokemon buddy" -> {"action": "pokemon", "subaction": "find", "message": "Vou procurar um Pokémon para acompanhar a gente."}
"equip your gadget" -> {"action": "gadget", "subaction": "equip", "message": "Vou equipar meu Building Gadget."}
"check your backpack" -> {"action": "backpack", "subaction": "info", "message": "Vou conferir minha mochila."}
""".formatted(companionName);
    }


    public CompletableFuture<CompanionAction> chat(String userMessage) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Add user message to history
                conversationHistory.add(new ChatMessage("user", userMessage));

                // Build request
                String response = sendChatRequest();

                // Add assistant response to history
                conversationHistory.add(new ChatMessage("assistant", response));

                // Parse response into action
                return parseResponse(response);
            } catch (Exception e) {
                LLMoblings.LOGGER.error("Ollama chat error: ", e);
                return new CompanionAction("idle", "Desculpa, estou com dificuldade para pensar agora.");
            }
        });
    }

    private String sendChatRequest() throws Exception {
        String host = Config.OLLAMA_HOST.get();
        int port = Config.OLLAMA_PORT.get();
        String model = Config.OLLAMA_MODEL.get();

        String url = String.format("http://%s:%d/api/chat", host, port);

        // Build messages array
        JsonArray messages = new JsonArray();

        // System prompt
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", systemPrompt);
        messages.add(systemMsg);

        // Conversation history (keep last 20 messages for context)
        int startIdx = Math.max(0, conversationHistory.size() - 20);
        for (int i = startIdx; i < conversationHistory.size(); i++) {
            ChatMessage msg = conversationHistory.get(i);
            JsonObject msgObj = new JsonObject();
            msgObj.addProperty("role", msg.role());
            msgObj.addProperty("content", msg.content());
            messages.add(msgObj);
        }

        // Build request body
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.add("messages", messages);
        requestBody.addProperty("stream", false);

        // Options for faster response
        JsonObject options = new JsonObject();
        options.addProperty("temperature", 0.7);
        options.addProperty("num_predict", 256);
        requestBody.add("options", options);

        int timeout = Config.OLLAMA_TIMEOUT.get();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeout))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(requestBody)))
                .build();

        HttpResponse<String> response = getHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama request failed: " + response.statusCode() + " - " + response.body());
        }

        String responseBody = response.body();
        JsonObject json = GSON.fromJson(responseBody, JsonObject.class);

        if (json.has("message") && json.getAsJsonObject("message").has("content")) {
            return json.getAsJsonObject("message").get("content").getAsString();
        }

        return "{\"action\": \"idle\", \"message\": \"Não recebi uma resposta válida.\"}";
    }

    private CompanionAction parseResponse(String response) {
        try {
            // Try to extract JSON from response
            String jsonStr = response.trim();

            // Handle case where LLM wraps JSON in markdown
            if (jsonStr.contains("```json")) {
                int start = jsonStr.indexOf("```json") + 7;
                int end = jsonStr.indexOf("```", start);
                if (end > start) {
                    jsonStr = jsonStr.substring(start, end).trim();
                }
            } else if (jsonStr.contains("```")) {
                int start = jsonStr.indexOf("```") + 3;
                int end = jsonStr.indexOf("```", start);
                if (end > start) {
                    jsonStr = jsonStr.substring(start, end).trim();
                }
            }

            // Find JSON object in response - handle nested braces properly
            int jsonStart = jsonStr.indexOf('{');
            if (jsonStart >= 0) {
                int braceCount = 0;
                int jsonEnd = -1;
                for (int i = jsonStart; i < jsonStr.length(); i++) {
                    char c = jsonStr.charAt(i);
                    if (c == '{') braceCount++;
                    else if (c == '}') {
                        braceCount--;
                        if (braceCount == 0) {
                            jsonEnd = i;
                            break;
                        }
                    }
                }
                if (jsonEnd > jsonStart) {
                    jsonStr = jsonStr.substring(jsonStart, jsonEnd + 1);
                }
            }

            // Clean up potential problematic characters
            jsonStr = jsonStr.replace("\u2026", "...");  // Unicode ellipsis
            jsonStr = jsonStr.replace("\u201c", "\"").replace("\u201d", "\"");  // Smart quotes
            jsonStr = jsonStr.replace("\u2018", "'").replace("\u2019", "'");  // Smart apostrophes

            JsonObject json = GSON.fromJson(jsonStr, JsonObject.class);
            LLMoblings.LOGGER.debug("Parsed LLM action: {}", json.get("action"));
            return CompanionAction.fromJson(json);
        } catch (Exception e) {
            LLMoblings.LOGGER.warn("Failed to parse LLM response as JSON, trying keyword fallback: {}", response);
            // Try keyword-based fallback parsing
            return parseFromKeywords(response);
        }
    }

    /**
     * Fallback parser that extracts action from plain text using keywords.
     */
    private CompanionAction parseFromKeywords(String text) {
        String lower = text.toLowerCase();

        // Check for action keywords
        if (lower.contains("follow") || lower.contains("seguir") || lower.contains("segue")) {
            return new CompanionAction("follow", text);
        }
        if (lower.contains("explor") || lower.contains("look around") || lower.contains("wander") || lower.contains("explorar") || lower.contains("vasculhar") || lower.contains("dar uma olhada")) {
            return new CompanionAction("explore", text);
        }
        if (lower.contains("auto") || lower.contains("independent") || lower.contains("on my own") || lower.contains("autônom") || lower.contains("autonom") || lower.contains("por conta própria")) {
            return new CompanionAction("auto", text);
        }
        if (lower.contains("defend") || lower.contains("protect") || lower.contains("defender") || lower.contains("proteger")) {
            return new CompanionAction("defend", text);
        }
        if (lower.contains("attack") || lower.contains("fight") || lower.contains("kill") || lower.contains("atacar") || lower.contains("lutar") || lower.contains("matar")) {
            return new CompanionAction("attack", text);
        }
        if (lower.contains("hunt") || lower.contains("food") || lower.contains("eat") || lower.contains("caçar") || lower.contains("comida") || lower.contains("comer")) {
            return new CompanionAction("auto", text);  // Auto mode handles hunting
        }
        if (lower.contains("farm") || lower.contains("harvest") || lower.contains("crop") ||
            lower.contains("planta") || lower.contains("colhe") || lower.contains("colher") ||
            lower.contains("plantação") || lower.contains("plantacao")) {
            return new CompanionAction("farm", text);
        }
        if (lower.contains("gear") || lower.contains("equip") || lower.contains("armor") || lower.contains("weapon") || lower.contains("equipar") || lower.contains("armadura") || lower.contains("arma")) {
            return new CompanionAction("auto", text);  // Auto mode handles equipping
        }
        if (lower.contains("stay") || lower.contains("stop") || lower.contains("wait") || lower.contains("fica") || lower.contains("parar") || lower.contains("espera")) {
            return new CompanionAction("stay", text);
        }
        if (lower.contains("come") || lower.contains("here") || lower.contains("vem") || lower.contains("venha") || lower.contains("aqui")) {
            return new CompanionAction("come", text);
        }
        if (lower.contains("home") || lower.contains("casa")) {
            return new CompanionAction("home", text);
        }
        if (lower.contains("scan") || lower.contains("escan") || lower.contains("vasculh") || lower.contains("procura ao redor")) {
            return new CompanionAction("scan", text);
        }
        if (lower.contains("status") || lower.contains("health") || lower.contains("inventory") || lower.contains("status") || lower.contains("vida") || lower.contains("inventário") || lower.contains("inventario")) {
            return new CompanionAction("status", text);
        }
        if (lower.contains("tpaccept") || lower.contains("tp accept") || lower.contains("accept teleport") || lower.contains("accept tp")) {
            return new CompanionAction("tpaccept", text);
        }
        if (lower.contains("tpdeny") || lower.contains("tp deny") || lower.contains("deny teleport") || lower.contains("deny tp")) {
            return new CompanionAction("tpdeny", text);
        }
        if (lower.contains("tpa ") || lower.contains("teleport to ") || lower.contains("tp to ")) {
            // Try to extract player name
            String target = "";
            if (lower.contains("tpa ")) {
                int idx = lower.indexOf("tpa ") + 4;
                target = text.substring(idx).trim().split("\\s+")[0];
            } else if (lower.contains("teleport to ")) {
                int idx = lower.indexOf("teleport to ") + 12;
                target = text.substring(idx).trim().split("\\s+")[0];
            } else if (lower.contains("tp to ")) {
                int idx = lower.indexOf("tp to ") + 6;
                target = text.substring(idx).trim().split("\\s+")[0];
            }
            CompanionAction action = new CompanionAction("tpa", text);
            action.setParameter("target", target);
            return action;
        }

        // Portal/dimension travel commands
        if (lower.contains("portal") || lower.contains("nether") || lower.contains("the end") ||
            lower.contains("dimension") || lower.contains("through the") || lower.contains("dimensão") || lower.contains("dimensao") || lower.contains("atravessar")) {
            CompanionAction action = new CompanionAction("portal", text);
            // Determine if they want to go through or just follow
            if (lower.contains("go through") || lower.contains("enter") || lower.contains("use") ||
                lower.contains("step through") || lower.contains("take the") || lower.contains("use the") ||
                lower.contains("entrar") || lower.contains("atravessar") || lower.contains("usar")) {
                action.setParameter("action", "enter");
            } else if (lower.contains("follow") || lower.contains("come with") || lower.contains("follow me") || lower.contains("seguir") || lower.contains("vem comigo")) {
                action.setParameter("action", "follow");
            } else if (lower.contains("stay") || lower.contains("wait") || lower.contains("don't") || lower.contains("fica") || lower.contains("espera") || lower.contains("não")) {
                action.setParameter("action", "stay");
            } else {
                // Default to enter if they mention portal
                action.setParameter("action", "enter");
            }
            return action;
        }

        // ME network gear retrieval
        if (lower.contains("get iron") || lower.contains("iron set") || lower.contains("iron gear") ||
            lower.contains("iron armor") || lower.contains("craft iron")) {
            CompanionAction action = new CompanionAction("getgear", text);
            action.setParameter("material", "iron");
            return action;
        }
        if (lower.contains("get diamond") || lower.contains("diamond set") || lower.contains("diamond gear") ||
            lower.contains("diamond armor") || lower.contains("craft diamond")) {
            CompanionAction action = new CompanionAction("getgear", text);
            action.setParameter("material", "diamond");
            return action;
        }
        if (lower.contains("get gear from me") || lower.contains("me network") || lower.contains("from ae2") ||
            lower.contains("from terminal")) {
            CompanionAction action = new CompanionAction("getgear", text);
            action.setParameter("material", "iron");  // Default to iron
            return action;
        }

        // Deposit items
        if (lower.contains("deposit") || lower.contains("store") || lower.contains("stash") ||
            lower.contains("put away") || lower.contains("put items") || lower.contains("empty inventory") ||
            lower.contains("depositar") || lower.contains("guardar") || lower.contains("esvaziar inventário") || lower.contains("esvaziar inventario")) {
            CompanionAction action = new CompanionAction("deposit", text);
            // Check if they want to deposit everything including gear
            if (lower.contains("everything") || lower.contains("all items") || lower.contains("including gear")) {
                action.setParameter("keepGear", "false");
            }
            return action;
        }

        // Build structures
        if ((lower.contains("build") || lower.contains("constr")) && (lower.contains("house") || lower.contains("cottage") ||
            lower.contains("home") || lower.contains("shelter") || lower.contains("casa") || lower.contains("cabana") || lower.contains("abrigo"))) {
            CompanionAction action = new CompanionAction("build", text);
            action.setParameter("structure", "cottage");

            // Check for "here" keyword
            if (lower.contains("here") || lower.contains("this spot") || lower.contains("right here")) {
                action.setParameter("here", "true");
            }

            // Try to extract coordinates if present (pattern: "at X Y Z" or "X, Y, Z")
            java.util.regex.Pattern coordPattern = java.util.regex.Pattern.compile(
                "(?:at\\s+)?([-]?\\d+)[,\\s]+([-]?\\d+)[,\\s]+([-]?\\d+)");
            java.util.regex.Matcher matcher = coordPattern.matcher(text);
            if (matcher.find()) {
                action.setParameter("x", matcher.group(1));
                action.setParameter("y", matcher.group(2));
                action.setParameter("z", matcher.group(3));
            }
            return action;
        }

        // Pokemon buddy commands
        if (lower.contains("pokemon") || lower.contains("buddy") || lower.contains("poke") || lower.contains("companheiro")) {
            CompanionAction action = new CompanionAction("pokemon", text);

            if (lower.contains("release") || lower.contains("bye") || lower.contains("dismiss") ||
                lower.contains("let go") || lower.contains("liberar") || lower.contains("soltar")) {
                action.setParameter("subaction", "release");
            } else if (lower.contains("status") || lower.contains("check") || lower.contains("how is") || lower.contains("como está") || lower.contains("como esta")) {
                action.setParameter("subaction", "status");
            } else {
                action.setParameter("subaction", "find");

                // Try to extract Pokemon name
                String[] pokemonKeywords = {"with", "bond with", "find", "get"};
                for (String keyword : pokemonKeywords) {
                    int idx = lower.indexOf(keyword);
                    if (idx >= 0) {
                        String afterKeyword = text.substring(idx + keyword.length()).trim();
                        String[] words = afterKeyword.split("\\s+");
                        if (words.length > 0 && !words[0].isEmpty()) {
                            // Capitalize first letter
                            String pokeName = words[0].substring(0, 1).toUpperCase() + words[0].substring(1).toLowerCase();
                            action.setParameter("name", pokeName);
                            break;
                        }
                    }
                }
            }
            return action;
        }

        // Building Gadgets commands
        if (lower.contains("gadget") || lower.contains("ferramenta de construção") || lower.contains("ferramenta de construcao")) {
            CompanionAction action = new CompanionAction("gadget", text);

            // Determine subaction
            if (lower.contains("equip") || lower.contains("hold")) {
                action.setParameter("subaction", "equip");
            } else if (lower.contains("set block") || lower.contains("setblock") ||
                       (lower.contains("set") && lower.contains("to"))) {
                action.setParameter("subaction", "setblock");
                // Try to extract block name
                String[] blockKeywords = {"to ", "block ", "with "};
                for (String keyword : blockKeywords) {
                    int idx = lower.indexOf(keyword);
                    if (idx >= 0) {
                        String afterKeyword = text.substring(idx + keyword.length()).trim();
                        String[] words = afterKeyword.split("\\s+");
                        if (words.length > 0 && !words[0].isEmpty()) {
                            action.setParameter("block", words[0].toLowerCase().replace(" ", "_"));
                            break;
                        }
                    }
                }
            } else if (lower.contains("range")) {
                action.setParameter("subaction", "setrange");
                // Try to extract range number
                java.util.regex.Matcher rangeMatcher = java.util.regex.Pattern.compile("\\d+").matcher(text);
                if (rangeMatcher.find()) {
                    action.setParameter("range", rangeMatcher.group());
                }
            } else if (lower.contains("config") || lower.contains("setup")) {
                action.setParameter("subaction", "configure");
                // Try to extract block and range
                java.util.regex.Matcher rangeMatcher = java.util.regex.Pattern.compile("\\d+").matcher(text);
                if (rangeMatcher.find()) {
                    action.setParameter("range", rangeMatcher.group());
                }
                // Common block names
                String[] blocks = {"stone", "cobblestone", "oak_planks", "spruce_planks", "birch_planks",
                                   "brick", "glass", "dirt", "sand", "gravel", "iron_block", "gold_block"};
                for (String block : blocks) {
                    if (lower.contains(block.replace("_", " ")) || lower.contains(block)) {
                        action.setParameter("block", block);
                        break;
                    }
                }
            } else if (lower.contains("use") || lower.contains("build") || lower.contains("place")) {
                action.setParameter("subaction", "build");
            } else {
                action.setParameter("subaction", "info");
            }
            return action;
        }

        // Sophisticated Backpacks commands
        if (lower.contains("backpack") || lower.contains("mochila") || lower.contains("pack") && !lower.contains("modpack")) {
            CompanionAction action = new CompanionAction("backpack", text);

            // Determine subaction
            if (lower.contains("store") || lower.contains("stash") || lower.contains("put in") || lower.contains("guardar") || lower.contains("colocar")) {
                if (lower.contains("all") || lower.contains("everything")) {
                    action.setParameter("subaction", "storeall");
                } else {
                    action.setParameter("subaction", "store");
                    // Try to extract item name
                    String[] storeKeywords = {"store ", "stash ", "put "};
                    for (String keyword : storeKeywords) {
                        int idx = lower.indexOf(keyword);
                        if (idx >= 0) {
                            String afterKeyword = text.substring(idx + keyword.length()).trim();
                            // Remove "in backpack" etc
                            afterKeyword = afterKeyword.replaceAll("\\s*(in|into|to)\\s*(my\\s+)?backpack.*", "").trim();
                            if (!afterKeyword.isEmpty()) {
                                String[] words = afterKeyword.split("\\s+");
                                if (words.length > 0) {
                                    action.setParameter("item", words[0].toLowerCase().replace(" ", "_"));
                                    break;
                                }
                            }
                        }
                    }
                }
            } else if (lower.contains("get") || lower.contains("take") || lower.contains("retrieve") ||
                       lower.contains("grab") || lower.contains("pegar") || lower.contains("tirar")) {
                action.setParameter("subaction", "get");
                // Try to extract item name and count
                String[] getKeywords = {"get ", "take ", "retrieve ", "grab "};
                for (String keyword : getKeywords) {
                    int idx = lower.indexOf(keyword);
                    if (idx >= 0) {
                        String afterKeyword = text.substring(idx + keyword.length()).trim();
                        afterKeyword = afterKeyword.replaceAll("\\s*(from|out of)\\s*(my\\s+)?backpack.*", "").trim();
                        if (!afterKeyword.isEmpty()) {
                            // Try to extract count
                            java.util.regex.Matcher countMatcher = java.util.regex.Pattern.compile("(\\d+)").matcher(afterKeyword);
                            if (countMatcher.find()) {
                                action.setParameter("count", countMatcher.group(1));
                                afterKeyword = afterKeyword.replaceFirst("\\d+\\s*", "").trim();
                            }
                            String[] words = afterKeyword.split("\\s+");
                            if (words.length > 0 && !words[0].isEmpty()) {
                                action.setParameter("item", words[0].toLowerCase().replace(" ", "_"));
                                break;
                            }
                        }
                    }
                }
            } else if (lower.contains("list") || lower.contains("contents") || lower.contains("what's in") ||
                       lower.contains("show me") || lower.contains("listar") || lower.contains("conteúdo") || lower.contains("conteudo") || lower.contains("o que tem")) {
                action.setParameter("subaction", "list");
            } else if (lower.contains("organize") || lower.contains("sort") || lower.contains("organizar")) {
                action.setParameter("subaction", "organize");
            } else {
                action.setParameter("subaction", "info");
            }
            return action;
        }

        // Elevator commands
        if (lower.contains("elevator") || lower.contains("lift") || lower.contains("elevador") ||
            (lower.contains("go") && (lower.contains("up") || lower.contains("down")) && lower.contains("floor")) ||
            ((lower.contains("subir") || lower.contains("descer")) && lower.contains("andar"))) {
            CompanionAction action = new CompanionAction("elevator", text);
            if (lower.contains("up") || lower.contains("ascend") || lower.contains("higher")) {
                action.setParameter("direction", "up");
            } else if (lower.contains("down") || lower.contains("descend") || lower.contains("lower")) {
                action.setParameter("direction", "down");
            } else {
                action.setParameter("direction", "up"); // Default to up
            }
            return action;
        }

        // Pokemon stats commands (Cobblemon)
        if (lower.contains("cobblestats") || lower.contains("pokemon stats") ||
            lower.contains("check pokemon") || lower.contains("pokemon ivs") ||
            lower.contains("pokemon evs") || lower.contains("mon stats") ||
            (lower.contains("stats") && (lower.contains("pokemon") || lower.contains("cobble")))) {
            CompanionAction action = new CompanionAction("cobblestats", text);

            // Check if they want brief or full stats
            if (lower.contains("brief") || lower.contains("quick") || lower.contains("short")) {
                action.setParameter("detail", "brief");
            } else if (lower.contains("full") || lower.contains("detailed") || lower.contains("all")) {
                action.setParameter("detail", "full");
            } else {
                action.setParameter("detail", "full"); // Default to full
            }

            // Check if they specified a Pokemon name
            String[] words = text.split("\\s+");
            for (int i = 0; i < words.length; i++) {
                String word = words[i].toLowerCase();
                if (word.equals("on") || word.equals("for") || word.equals("of")) {
                    if (i + 1 < words.length) {
                        action.setParameter("target", words[i + 1]);
                        break;
                    }
                }
            }

            return action;
        }

        // Default to idle with the response as message
        LLMoblings.LOGGER.info("Nenhuma palavra-chave de ação encontrada; usando idle.");
        return new CompanionAction("idle", text);
    }

    /**
     * Blocking version of chat() for use inside the async action loop.
     * Prepends world state context to the user message.
     */
    public CompanionAction chatBlocking(String userMessage, String worldStateContext) {
        try {
            String fullMessage = worldStateContext + "\n" + userMessage;
            conversationHistory.add(new ChatMessage("user", fullMessage));
            String response = sendChatRequest();
            conversationHistory.add(new ChatMessage("assistant", response));
            return parseResponse(response);
        } catch (Exception e) {
            LLMoblings.LOGGER.error("Ollama chatBlocking error: ", e);
            return new CompanionAction("idle", "Desculpa, estou com dificuldade para pensar agora.");
        }
    }

    /**
     * Inject an observation into conversation history (used between loop iterations).
     */
    public void addSystemObservation(String observation) {
        conversationHistory.add(new ChatMessage("user", "[OBSERVAÇÃO] " + observation));
    }

    /**
     * After a loop ends, compact the intermediate messages down to just the original
     * user request and the final assistant response, to avoid consuming the 20-message
     * history window with one multi-step interaction.
     */
    public void compactLoopHistory(int loopMessageCount) {
        if (loopMessageCount <= 2 || conversationHistory.size() < loopMessageCount) {
            return;
        }

        int startIdx = conversationHistory.size() - loopMessageCount;
        // Keep first message (original user request) and last message (final assistant response)
        ChatMessage firstMsg = conversationHistory.get(startIdx);
        ChatMessage lastMsg = conversationHistory.get(conversationHistory.size() - 1);

        // Remove all loop messages
        conversationHistory.subList(startIdx, conversationHistory.size()).clear();

        // Re-add just the bookends
        conversationHistory.add(firstMsg);
        conversationHistory.add(lastMsg);
    }

    public void clearHistory() {
        conversationHistory.clear();
    }

    public record ChatMessage(String role, String content) {}
}
