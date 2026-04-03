package com.gblfxt.llmoblings;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final ModConfigSpec SPEC;

    // Ollama settings
    public static final ModConfigSpec.ConfigValue<String> OLLAMA_HOST;
    public static final ModConfigSpec.ConfigValue<Integer> OLLAMA_PORT;
    public static final ModConfigSpec.ConfigValue<String> OLLAMA_MODEL;
    public static final ModConfigSpec.ConfigValue<Integer> OLLAMA_TIMEOUT;

    // Companion settings
    public static final ModConfigSpec.ConfigValue<Integer> MAX_COMPANIONS_PER_PLAYER;
    public static final ModConfigSpec.ConfigValue<Boolean> COMPANIONS_TAKE_DAMAGE;
    public static final ModConfigSpec.ConfigValue<Boolean> COMPANIONS_NEED_FOOD;
    public static final ModConfigSpec.ConfigValue<Double> COMPANION_FOLLOW_DISTANCE;
    public static final ModConfigSpec.ConfigValue<Integer> ITEM_PICKUP_RADIUS;
    public static final ModConfigSpec.ConfigValue<Boolean> COMPANIONS_LOAD_CHUNKS;
    public static final ModConfigSpec.ConfigValue<Boolean> ACTION_LOOP_ENABLED;
    public static final ModConfigSpec.ConfigValue<Integer> ACTION_LOOP_MAX_ITERATIONS;

    // Chat settings
    public static final ModConfigSpec.ConfigValue<String> CHAT_PREFIX;
    public static final ModConfigSpec.ConfigValue<Boolean> BROADCAST_COMPANION_CHAT;
    public static final ModConfigSpec.ConfigValue<Boolean> ALLOW_OTHER_PLAYER_INTERACTION;

    static {
        BUILDER.comment("Configuração do Ollama").push("ollama");

        OLLAMA_HOST = BUILDER
                .comment("Hostname ou IP do servidor Ollama")
                .define("host", "127.0.0.1");

        OLLAMA_PORT = BUILDER
                .comment("Porta do servidor Ollama")
                .defineInRange("port", 11434, 1, 65535);

        OLLAMA_MODEL = BUILDER
                .comment("Modelo do Ollama a usar (ex.: llama3:8b, mistral:7b, gemma:2b)")
                .define("model", "llama3:8b");

        OLLAMA_TIMEOUT = BUILDER
                .comment("Tempo limite da requisição em segundos")
                .defineInRange("timeout", 30, 5, 300);

        BUILDER.pop();

        BUILDER.comment("Comportamento do companion").push("companion");

        MAX_COMPANIONS_PER_PLAYER = BUILDER
                .comment("Número máximo de companions por jogador")
                .defineInRange("maxPerPlayer", 3, 1, 10);

        COMPANIONS_TAKE_DAMAGE = BUILDER
                .comment("Se os companions podem receber dano")
                .define("takeDamage", true);

        COMPANIONS_NEED_FOOD = BUILDER
                .comment("Se os companions precisam de comida (sistema de fome)")
                .define("needFood", false);

        COMPANION_FOLLOW_DISTANCE = BUILDER
                .comment("Distância padrão para seguir o dono")
                .defineInRange("followDistance", 5.0, 1.0, 50.0);

        ITEM_PICKUP_RADIUS = BUILDER
                .comment("Raio em blocos para pegar itens")
                .defineInRange("itemPickupRadius", 3, 0, 10);

        COMPANIONS_LOAD_CHUNKS = BUILDER
                .comment("Se os companions mantêm o chunk carregado (permite trabalhar offline)")
                .define("loadChunks", true);

        ACTION_LOOP_ENABLED = BUILDER
                .comment("Ativa o loop iterativo de ações: o companion pode consultar e depois decidir o que fazer")
                .define("actionLoopEnabled", true);

        ACTION_LOOP_MAX_ITERATIONS = BUILDER
                .comment("Número máximo de iterações por loop de ação (1 = resposta única)")
                .defineInRange("actionLoopMaxIterations", 3, 1, 10);

        BUILDER.pop();

        BUILDER.comment("Configurações de chat").push("chat");

        CHAT_PREFIX = BUILDER
                .comment("Prefixo para falar com companions no chat (ex.: '@companion')")
                .define("prefix", "@");

        BROADCAST_COMPANION_CHAT = BUILDER
                .comment("Se as respostas do companion devem ser mostradas para jogadores próximos")
                .define("broadcastChat", true);

        ALLOW_OTHER_PLAYER_INTERACTION = BUILDER
                .comment("Se outros jogadores (não o dono) podem conversar e dar comandos aos companions")
                .define("allowOtherPlayerInteraction", true);

        BUILDER.pop();

        SPEC = BUILDER.build();
    }
}
