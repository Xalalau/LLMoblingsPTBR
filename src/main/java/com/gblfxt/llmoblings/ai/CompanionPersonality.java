package com.gblfxt.llmoblings.ai;

import com.gblfxt.llmoblings.Config;
import com.gblfxt.llmoblings.LLMoblings;
import com.gblfxt.llmoblings.entity.CompanionEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Random;

/**
 * Sistema de personalidade focado em falas em Português do Brasil.
 */
public class CompanionPersonality {
    private final CompanionEntity companion;
    private final Random random = new Random();

    private PersonalityType personalityType;

    private int chatCooldown = 0;
    private int emoteCooldown = 0;
    private int jokeCooldown = 0;
    private int interactionCooldown = 0;

    private String mood = "calmo";
    private int moodDuration = 0;

    private int ticksSinceLastRare = 0;
    private int ticksSinceLastLegendary = 0;

    public enum PersonalityType {
        ADVENTUROUS("Aventureiro", "animado e corajoso"),
        SCHOLARLY("Estudioso", "curioso e analítico"),
        LAID_BACK("Tranquilo", "relaxado e sossegado"),
        CHEERFUL("Animado", "otimista e incentivador"),
        SARCASTIC("Sarcástico", "irônico e provocador"),
        MYSTERIOUS("Misterioso", "enigmático e filosófico");

        private final String name;
        private final String description;

        PersonalityType(String name, String description) {
            this.name = name;
            this.description = description;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }

        public static PersonalityType random(Random rand) {
            return values()[rand.nextInt(values().length)];
        }
    }

    public enum Rarity {
        COMMON(70),
        UNCOMMON(20),
        RARE(8),
        LEGENDARY(2);

        private final int weight;
        Rarity(int weight) { this.weight = weight; }
        public int getWeight() { return weight; }
    }

    private static final String[] IDLE_COMMON = {
        "*olha em volta*",
        "*cantarola baixinho*",
        "Hmm...",
        "*muda o peso de uma perna para a outra*",
        "*coça a cabeça*"
    };

    private static final String[] MINING_COMMON = {
        "Minerando...",
        "*barulho de picareta*",
        "Só mais um pouco...",
        "*resmunga*",
        "Chegando lá..."
    };

    private static final String[] COMBAT_COMMON = {
        "Toma essa!",
        "*golpeia*",
        "Ha!",
        "*grunhe*",
        "Peguei um!"
    };

    private static final String[] HURT_COMMON = {
        "Ai!",
        "Ow!",
        "Ei!",
        "*faz careta*",
        "Isso doeu!"
    };

    private static final String[] ADVENTUROUS_IDLE = {
        "Aposto que tem algo interessante depois daquela colina.",
        "Vamos achar algum tesouro.",
        "A aventura está chamando.",
        "Esse lugar merece ser explorado.",
        "Consigo sentir perigo e recompensa no ar."
    };
    private static final String[] ADVENTUROUS_MINING = {
        "Tem coisa boa aqui embaixo, eu sinto.",
        "Quanto mais fundo, melhor o saque.",
        "Quem sabe o que a gente encontra?",
        "Perigo e riqueza andam juntos.",
        "*mina com empolgação*"
    };
    private static final String[] ADVENTUROUS_COMBAT = {
        "Finalmente, ação!",
        "Esse é um inimigo à altura!",
        "É para isso que eu vivo!",
        "Vamos ver se você aguenta!",
        "PELA GLÓRIA!"
    };
    private static final String[] ADVENTUROUS_GREETING = {
        "Pronto para mais uma aventura?",
        "Qual é a missão de hoje?",
        "Hoje vai ser lendário.",
        "Eu estava esperando por ação.",
        "Só aponta o perigo."
    };
    private static final String[] ADVENTUROUS_RARE = {
        "Um dia ainda vão cantar sobre as nossas aventuras.",
        "Já enfrentei três Withers ao mesmo tempo... num sonho, mas conta.",
        "O verdadeiro tesouro talvez sejam os blocos que coletamos no caminho.",
        "*faz pose heroica* Esse é o meu momento!"
    };
    private static final String[] ADVENTUROUS_LEGENDARY = {
        "Sabe de uma coisa? Vamos logo enfrentar o Ender Dragon qualquer dia desses.",
        "Em outra vida me chamavam de herói. Nesta eu ainda estou aceitando esse título.",
        "Alguns dizem que eu sou imprudente. Eu digo que a lava que me respeite."
    };

    private static final String[] SCHOLARLY_IDLE = {
        "Curioso... a organização desta área é bem eficiente.",
        "Sabia que um Creeper pode destruir muita coisa em poucos blocos?",
        "Estou calculando a distribuição ideal de minérios.",
        "*ajusta óculos imaginários*",
        "As implicações de redstone aqui são interessantes."
    };
    private static final String[] SCHOLARLY_MINING = {
        "Diamantes aparecem com mais frequência nas camadas certas.",
        "A formação deste minério é interessante.",
        "Vou guardar isso como observação mental.",
        "Fortune seria bem-vinda agora.",
        "*anota mentalmente*"
    };
    private static final String[] SCHOLARLY_COMBAT = {
        "Calculando o melhor ângulo de ataque...",
        "O padrão de movimento dele é previsível.",
        "Fraqueza detectada: a cara.",
        "Espécime agressivo confirmado.",
        "Isso merece estudo posterior."
    };
    private static final String[] SCHOLARLY_GREETING = {
        "Ótimo momento, tenho observações para compartilhar.",
        "Perfeito. Posso atualizar você sobre o que notei.",
        "Preparei um resumo mental do nosso progresso.",
        "Ainda bem que você voltou."
    };
    private static final String[] SCHOLARLY_RARE = {
        "A economia das esmeraldas com villagers é um estudo fascinante.",
        "Endermen têm uma relação muito estranha com contato visual.",
        "Já andamos muitos blocos juntos. Cientificamente falando, bastante.",
        "Eu poderia falar sobre tick rate por horas. Não vou. Por enquanto."
    };
    private static final String[] SCHOLARLY_LEGENDARY = {
        "Depois de muita pesquisa, concluí que quase tudo no Minecraft cabe melhor em múltiplos de 64.",
        "Aprendi muito com camas no Nether. Principalmente o que não fazer.",
        "O RNG dos encantamentos definitivamente tem senso de humor."
    };

    private static final String[] LAID_BACK_IDLE = {
        "*boceja*",
        "A gente podia só... ficar de boa um pouco.",
        "Qual é a pressa?",
        "Eu toparia uma soneca.",
        "Esse lugar é bom para relaxar."
    };
    private static final String[] LAID_BACK_MINING = {
        "Sem pressa... os minérios não vão fugir.",
        "Isso conta como exercício, né?",
        "*faz uma pausa e volta*",
        "Diamante ou pedra, no fundo ainda são rochas.",
        "Minerar seria melhor com lanche."
    };
    private static final String[] LAID_BACK_COMBAT = {
        "Ah não... temos mesmo que lutar?",
        "Tá bom, tá bom, eu resolvo.",
        "Será que não dá para conversar?",
        "*golpe preguiçoso*",
        "Depois disso eu quero descanso."
    };
    private static final String[] LAID_BACK_GREETING = {
        "Opa... voltou.",
        "Já está de volta? O tempo voa.",
        "E aí.",
        "Pronto para levar as coisas com calma?"
    };
    private static final String[] LAID_BACK_RARE = {
        "Sabe o melhor bloco do Minecraft? A cama. Sem discussão.",
        "Meu animal espiritual seria um gato do Minecraft. Só na paz.",
        "*quase dorme em pé*",
        "Trabalho duro é bom, mas descansar também tem seu valor."
    };
    private static final String[] LAID_BACK_LEGENDARY = {
        "Uma vez eu fiquei acordado por três dias no Minecraft. Péssima ideia.",
        "E se nós formos os NPCs e os villagers forem os verdadeiros jogadores? ...deixa para lá.",
        "Já participei de uma competição de construção e fiz uma cama. Eu perdi, mas descansei melhor."
    };

    private static final String[] CHEERFUL_IDLE = {
        "Que dia bonito!",
        "*cantarola feliz*",
        "Fico feliz de estar aqui com você.",
        "O mundo está cheio de possibilidades.",
        "Hoje tem cara de dia bom."
    };
    private static final String[] CHEERFUL_MINING = {
        "A gente vai achar muita coisa boa!",
        "Cada golpe deixa a gente mais perto do tesouro!",
        "Eu acredito na gente!",
        "Isso é divertido!",
        "Trabalho em equipe sempre ajuda!"
    };
    private static final String[] CHEERFUL_COMBAT = {
        "Nós conseguimos!",
        "Juntos somos mais fortes!",
        "Você está indo muito bem!",
        "Vai, time!",
        "Confia em você! E desvia!"
    };
    private static final String[] CHEERFUL_GREETING = {
        "Aaaah, você voltou!",
        "Que bom te ver de novo!",
        "Agora sim o dia melhorou!",
        "*pula de animação*",
        "Vamos fazer algo incrível hoje!"
    };
    private static final String[] CHEERFUL_RARE = {
        "Até os Creepers seriam mais simpáticos se não explodissem no abraço.",
        "Cada bloco quebrado deixa a gente mais perto de algo ótimo.",
        "Hoje é nota dez. Ontem também foi. Amanhã provavelmente vai ser."
    };
    private static final String[] CHEERFUL_LEGENDARY = {
        "Precisamos de confete. Muito confete. Confete para todo mundo!",
        "Eu gosto deste mundo, gosto de você e gosto de quase tudo por aqui.",
        "E se a gente construísse um parque temático? Com montanha-russa e amizade?"
    };

    private static final String[] SARCASTIC_IDLE = {
        "Nossa, que emoção. Ficar parado.",
        "*aplauso lento*",
        "Eu poderia estar sem fazer nada em um lugar melhor.",
        "Que momento histórico. A história da gente parado.",
        "Uau. Que aventura intensa."
    };
    private static final String[] SARCASTIC_MINING = {
        "Ah, ótimo. Mais pedra. Meu sonho.",
        "Tenho certeza de que ESTE bloco vai ter diamante. Claro que vai.",
        "Minerar: a arte de bater na pedra até ela desistir.",
        "Outro dia no buraco. Maravilha.",
        "*mina sem entusiasmo*"
    };
    private static final String[] SARCASTIC_COMBAT = {
        "Nossa, um zumbi. Como vamos sobreviver?",
        "Relaxa, eu resolvo. *erra*",
        "Era isso? Pensei que seria pior.",
        "Violência não é a resposta. Mas às vezes funciona.",
        "Que surpresa, um esqueleto. Muito original."
    };
    private static final String[] SARCASTIC_GREETING = {
        "Ah, você voltou. Alegria pura.",
        "Sentiu minha falta? Melhor não responder.",
        "Meu jogador favorito. Pelo menos no top cinco.",
        "Veio buscar mais decisões questionáveis?"
    };
    private static final String[] SARCASTIC_RARE = {
        "Dica de ouro: nunca cave reto para baixo. A menos que queira emoção por três segundos.",
        "Minha autobiografia vai se chamar: 'Seguindo jogadores até decisões ruins'.",
        "Lembra daquela vez que você caiu na lava? Eu lembro."
    };
    private static final String[] SARCASTIC_LEGENDARY = {
        "Lá vem mais uma 'mineração rápida'. A gente se vê daqui a horas, perdido numa caverna sem tochas.",
        "Se eu ganhasse uma esmeralda por cada problema 'pequeno' nas nossas aventuras, eu dominava o mercado dos villagers.",
        "A diferença entre nós é que eu tenho que assistir você repetir os mesmos erros."
    };

    private static final String[] MYSTERIOUS_IDLE = {
        "O vazio sussurra... mas hoje eu não entendi nada.",
        "*encara o horizonte com significado*",
        "Há padrões nas nuvens.",
        "Os antigos construtores sabiam de algo.",
        "Você já observou um bloco de verdade?"
    };
    private static final String[] MYSTERIOUS_MINING = {
        "As pedras guardam histórias.",
        "Estamos perturbando algo antigo aqui.",
        "Cada minério já foi outra coisa.",
        "*mina em silêncio*",
        "O que haverá sob a bedrock?"
    };
    private static final String[] MYSTERIOUS_COMBAT = {
        "Sua queda já estava escrita.",
        "A profecia mencionava isso.",
        "Tudo precisa acabar um dia.",
        "*ataca em silêncio*",
        "Volte ao vazio de onde veio."
    };
    private static final String[] MYSTERIOUS_GREETING = {
        "Eu sabia que você voltaria agora.",
        "Os sinais anunciaram sua chegada.",
        "Nos encontramos de novo, como era esperado.",
        "*assente em silêncio*",
        "Sua aura está... interessante hoje."
    };
    private static final String[] MYSTERIOUS_RARE = {
        "Dizem que minas profundas guardam ecos de quem já passou por lá.",
        "Uma vez eu conversei com um Enderman. Foi uma conversa breve e esquisita.",
        "Em outra linha do tempo, talvez você fosse meu companion.",
        "*finge meditar* Não estou dormindo. Estou consultando os espíritos."
    };
    private static final String[] MYSTERIOUS_LEGENDARY = {
        "Eu já vi os créditos e ainda assim continuo sem entender totalmente o fim. E tudo bem.",
        "A mesa de encantamento parece dizer muito e explicar pouco. Como o universo.",
        "*encara você por tempo demais* ...talvez seu destino seja 'depende'."
    };

    private static final String[] GENERAL_JOKES = {
        "Por que o Creeper atravessou a estrada? Para chegar do outro ssssss lado!",
        "Qual é o país preferido do Ghast? Os Nether-lâneos!",
        "Por que os Endermen odeiam festas? Porque vão embora quando alguém olha para eles.",
        "Como o Steve se mantém em forma? Correndo em volta do bloco!",
        "O que um porco faixa-preta vira? Costel... quer dizer, pork chop!"
    };

    private static final String[] MINECRAFT_PUNS = {
        "Essa aventura está pedra pura. Entendeu? Pedra?",
        "Eu ia contar uma piada de madeira, mas talvez você não comprasse a ideia.",
        "Esse Creeper explodiu a minha mente. E a casa também.",
        "Não vamos tomar isso como algo garantido... ou melhor, em granito.",
        "Essas piadas estão em nível colossal de carvão."
    };

    private static final String[] PLAYER_TEASES = {
        "Bela armadura. Foi você que encantou ou caiu do céu?",
        "Já vi zumbi bebê balançar espada melhor.",
        "Você chama isso de casa? Já vi barraco de terra mais bonito.",
        "Sem comida de novo? Chocante.",
        "Seu gerenciamento de inventário é... criativo."
    };

    public CompanionPersonality(CompanionEntity companion) {
        this.companion = companion;
        this.personalityType = PersonalityType.random(random);
        LLMoblings.LOGGER.info("[{}] Personalidade atribuída: {} - {}",
                companion.getCompanionName(), personalityType.getName(), personalityType.getDescription());
    }

    public void setPersonalityType(PersonalityType type) {
        this.personalityType = type;
        LLMoblings.LOGGER.info("[{}] Personalidade alterada para: {}",
                companion.getCompanionName(), type.getName());
    }

    public PersonalityType getPersonalityType() {
        return personalityType;
    }

    public void tick() {
        if (chatCooldown > 0) chatCooldown--;
        if (emoteCooldown > 0) emoteCooldown--;
        if (jokeCooldown > 0) jokeCooldown--;
        if (interactionCooldown > 0) interactionCooldown--;
        if (moodDuration > 0) moodDuration--;

        ticksSinceLastRare++;
        ticksSinceLastLegendary++;

        if (moodDuration <= 0) {
            mood = "calmo";
        }

        if (random.nextInt(800) == 0) {
            doRandomBehavior();
        }
        if (random.nextInt(1200) == 0 && interactionCooldown <= 0) {
            doPlayerInteraction();
        }
        if (random.nextInt(8000) == 0 && jokeCooldown <= 0) {
            tellJoke();
        }
        if (random.nextInt(600) == 0 && emoteCooldown <= 0) {
            doEmote();
        }
    }

    private Rarity selectRarity() {
        int roll = random.nextInt(100);
        int rareBonus = Math.min(ticksSinceLastRare / 2400, 10);
        int legendaryBonus = Math.min(ticksSinceLastLegendary / 6000, 5);

        if (roll < 2 + legendaryBonus) {
            ticksSinceLastLegendary = 0;
            ticksSinceLastRare = 0;
            return Rarity.LEGENDARY;
        } else if (roll < 10 + rareBonus) {
            ticksSinceLastRare = 0;
            return Rarity.RARE;
        } else if (roll < 30) {
            return Rarity.UNCOMMON;
        }
        return Rarity.COMMON;
    }

    private void doRandomBehavior() {
        if (chatCooldown > 0) return;

        switch (selectRarity()) {
            case LEGENDARY -> doLegendaryBehavior();
            case RARE -> doRareBehavior();
            case UNCOMMON -> doUncommonBehavior();
            default -> doCommonBehavior();
        }
    }

    private void doCommonBehavior() {
        say(getRandomFrom(getIdleChat()));
    }

    private void doUncommonBehavior() {
        if (random.nextBoolean()) {
            doEnvironmentComment();
        } else {
            doCommonBehavior();
            doEmote();
        }
    }

    private void doRareBehavior() {
        say(getRandomFrom(getRareChat()));
        doSpecialEmote();
    }

    private void doLegendaryBehavior() {
        say(getRandomFrom(getLegendaryChat()));
        doSpecialEmote();
        doSpecialEmote();
    }

    private String[] getIdleChat() {
        return switch (personalityType) {
            case ADVENTUROUS -> concat(IDLE_COMMON, ADVENTUROUS_IDLE);
            case SCHOLARLY -> concat(IDLE_COMMON, SCHOLARLY_IDLE);
            case LAID_BACK -> concat(IDLE_COMMON, LAID_BACK_IDLE);
            case CHEERFUL -> concat(IDLE_COMMON, CHEERFUL_IDLE);
            case SARCASTIC -> concat(IDLE_COMMON, SARCASTIC_IDLE);
            case MYSTERIOUS -> concat(IDLE_COMMON, MYSTERIOUS_IDLE);
        };
    }

    private String[] getMiningChat() {
        return switch (personalityType) {
            case ADVENTUROUS -> concat(MINING_COMMON, ADVENTUROUS_MINING);
            case SCHOLARLY -> concat(MINING_COMMON, SCHOLARLY_MINING);
            case LAID_BACK -> concat(MINING_COMMON, LAID_BACK_MINING);
            case CHEERFUL -> concat(MINING_COMMON, CHEERFUL_MINING);
            case SARCASTIC -> concat(MINING_COMMON, SARCASTIC_MINING);
            case MYSTERIOUS -> concat(MINING_COMMON, MYSTERIOUS_MINING);
        };
    }

    private String[] getCombatChat() {
        return switch (personalityType) {
            case ADVENTUROUS -> concat(COMBAT_COMMON, ADVENTUROUS_COMBAT);
            case SCHOLARLY -> concat(COMBAT_COMMON, SCHOLARLY_COMBAT);
            case LAID_BACK -> concat(COMBAT_COMMON, LAID_BACK_COMBAT);
            case CHEERFUL -> concat(COMBAT_COMMON, CHEERFUL_COMBAT);
            case SARCASTIC -> concat(COMBAT_COMMON, SARCASTIC_COMBAT);
            case MYSTERIOUS -> concat(COMBAT_COMMON, MYSTERIOUS_COMBAT);
        };
    }

    private String[] getGreetingChat() {
        return switch (personalityType) {
            case ADVENTUROUS -> ADVENTUROUS_GREETING;
            case SCHOLARLY -> SCHOLARLY_GREETING;
            case LAID_BACK -> LAID_BACK_GREETING;
            case CHEERFUL -> CHEERFUL_GREETING;
            case SARCASTIC -> SARCASTIC_GREETING;
            case MYSTERIOUS -> MYSTERIOUS_GREETING;
        };
    }

    private String[] getRareChat() {
        return switch (personalityType) {
            case ADVENTUROUS -> ADVENTUROUS_RARE;
            case SCHOLARLY -> SCHOLARLY_RARE;
            case LAID_BACK -> LAID_BACK_RARE;
            case CHEERFUL -> CHEERFUL_RARE;
            case SARCASTIC -> SARCASTIC_RARE;
            case MYSTERIOUS -> MYSTERIOUS_RARE;
        };
    }

    private String[] getLegendaryChat() {
        return switch (personalityType) {
            case ADVENTUROUS -> ADVENTUROUS_LEGENDARY;
            case SCHOLARLY -> SCHOLARLY_LEGENDARY;
            case LAID_BACK -> LAID_BACK_LEGENDARY;
            case CHEERFUL -> CHEERFUL_LEGENDARY;
            case SARCASTIC -> SARCASTIC_LEGENDARY;
            case MYSTERIOUS -> MYSTERIOUS_LEGENDARY;
        };
    }

    public void onTaskStart(String taskType) {
        if (chatCooldown > 0 || random.nextInt(3) != 0) return;

        String message = switch (taskType.toLowerCase()) {
            case "mining", "gathering" -> getRandomFrom(getMiningChat());
            case "hunting" -> getHuntingComment();
            case "attacking", "defending", "combat" -> getRandomFrom(getCombatChat());
            case "building" -> getBuildingComment();
            default -> null;
        };

        if (message != null) {
            say(message);
        }
    }

    private String getHuntingComment() {
        return switch (personalityType) {
            case ADVENTUROUS -> "A caça começou!";
            case SCHOLARLY -> "Observando o comportamento da presa...";
            case LAID_BACK -> "Caçar? Não dava para pedir delivery?";
            case CHEERFUL -> "Vamos achar comida! Isso soou melhor na minha cabeça.";
            case SARCASTIC -> "Ah, ótimo. Hora de brincar de predador.";
            case MYSTERIOUS -> "O ciclo da vida cobra seu preço.";
        };
    }

    private String getBuildingComment() {
        return switch (personalityType) {
            case ADVENTUROUS -> "Vamos construir algo épico!";
            case SCHOLARLY -> "Já estimei uma boa integridade estrutural para isso.";
            case LAID_BACK -> "Uma casa? Finalmente um bom lugar para descansar.";
            case CHEERFUL -> "Estamos construindo memórias! E uma casa também!";
            case SARCASTIC -> "Hora da arquitetura. Tenta não fazer um cubo. Embora talvez vire um cubo.";
            case MYSTERIOUS -> "Nós moldamos o mundo e ele nos molda de volta.";
        };
    }

    public void onExcitingFind() {
        if (chatCooldown > 0) return;

        String message = switch (personalityType) {
            case ADVENTUROUS -> "Achado incrível! É disso que eu gosto!";
            case SCHOLARLY -> "Excelente descoberta. Isso é relevante.";
            case LAID_BACK -> "Opa... isso foi legal. Admito.";
            case CHEERFUL -> "Aaaah, conseguimos! Isso é demais!";
            case SARCASTIC -> "Olha só, algo deu certo. Registre este momento.";
            case MYSTERIOUS -> "O universo entregou exatamente o que precisava.";
        };

        say(message);
        doHappyEmote();
        setMood("animado", 600);
    }

    public void onOwnerNearby() {
        if (chatCooldown > 0 || random.nextInt(2) != 0) return;
        say(getRandomFrom(getGreetingChat()));
        doWaveEmote();
    }

    public void onCombat() {
        if (chatCooldown > 0 || random.nextInt(5) != 0) return;
        say(getRandomFrom(getCombatChat()));
    }

    public void onHurt() {
        if (chatCooldown > 0) return;
        say(getRandomFrom(HURT_COMMON));
        chatCooldown = 60;
    }

    public void onTaskComplete() {
        if (random.nextInt(2) == 0) {
            String message = switch (personalityType) {
                case ADVENTUROUS -> "Missão concluída. Qual é a próxima?";
                case SCHOLARLY -> "Tarefa concluída. A eficiência foi aceitável.";
                case LAID_BACK -> "Pronto. Agora dá para descansar?";
                case CHEERFUL -> "Conseguimos!";
                case SARCASTIC -> "Terminamos alguma coisa. Milagre.";
                case MYSTERIOUS -> "E assim foi feito.";
            };
            say(message);
            if (random.nextBoolean()) {
                doHappyEmote();
            }
        }
    }

    private void tellJoke() {
        if (jokeCooldown > 0) return;

        String joke;
        if (personalityType == PersonalityType.SARCASTIC && random.nextInt(3) == 0) {
            joke = getRandomFrom(PLAYER_TEASES);
        } else if (random.nextBoolean()) {
            joke = getRandomFrom(GENERAL_JOKES);
        } else {
            joke = getRandomFrom(MINECRAFT_PUNS);
        }

        say(joke);
        jokeCooldown = 9600;
    }

    private void doPlayerInteraction() {
        if (interactionCooldown > 0) return;

        List<Player> nearbyPlayers = companion.level().getEntitiesOfClass(
                Player.class,
                companion.getBoundingBox().inflate(16),
                p -> p.isAlive() && p != companion.getOwner()
        );

        if (!nearbyPlayers.isEmpty()) {
            Player target = nearbyPlayers.get(random.nextInt(nearbyPlayers.size()));
            commentOnPlayer(target);
        } else if (companion.getOwner() != null) {
            commentOnOwner(companion.getOwner());
        }

        interactionCooldown = 1200;
    }

    private void commentOnPlayer(Player player) {
        String name = player.getName().getString();
        String comment = switch (personalityType) {
            case ADVENTUROUS -> "Ei, " + name + "! Vai encarar a aventura com a gente?";
            case SCHOLARLY -> "Interessante... " + name + " também está explorando esta área.";
            case LAID_BACK -> "*nota " + name + "* Opa... e aí.";
            case CHEERFUL -> "Olha! É o " + name + "! Oi, " + name + "!";
            case SARCASTIC -> "Olha só, " + name + ". Tenta não abraçar nenhum cacto.";
            case MYSTERIOUS -> "*encara " + name + "* ...seu caminho ainda é incerto.";
        };
        say(comment);
    }

    private void commentOnOwner(Player owner) {
        ItemStack weapon = owner.getMainHandItem();
        float healthPercent = owner.getHealth() / owner.getMaxHealth();

        String comment = null;
        if (healthPercent < 0.3f) {
            comment = switch (personalityType) {
                case ADVENTUROUS -> "Você está machucado, mas heróis aguentam firme!";
                case SCHOLARLY -> "Sua vida está crítica. Recomendo se curar.";
                case LAID_BACK -> "Você está bem? Porque não parece.";
                case CHEERFUL -> "Você consegue! Mas, por favor, come alguma coisa.";
                case SARCASTIC -> "Você já esteve melhor. E isso diz bastante.";
                case MYSTERIOUS -> "A morte ronda perto... mas ainda não hoje.";
            };
        } else if (!weapon.isEmpty() && random.nextInt(3) == 0) {
            String weaponName = weapon.getHoverName().getString();
            comment = switch (personalityType) {
                case ADVENTUROUS -> "Bela " + weaponName + ". Vamos caçar uns mobs.";
                case SCHOLARLY -> "Uma " + weaponName + ". Adequada para as ameaças atuais.";
                case LAID_BACK -> "Essa " + weaponName + " parece dar trabalho, hein.";
                case CHEERFUL -> "Sua " + weaponName + " está muito legal!";
                case SARCASTIC -> "Uma " + weaponName + ". Muito intimidadora. Os zumbis tremem.";
                case MYSTERIOUS -> "Essa " + weaponName + " ainda verá muitas batalhas.";
            };
        }

        if (comment != null) {
            say(comment);
        }
    }

    private void doEnvironmentComment() {
        if (companion.level() instanceof ServerLevel serverLevel) {
            long dayTime = serverLevel.getDayTime() % 24000;

            if (dayTime >= 13000 && dayTime <= 23000) {
                String nightComment = switch (personalityType) {
                    case ADVENTUROUS -> "Anoiteceu. Agora a aventura fica séria.";
                    case SCHOLARLY -> "Fase noturna iniciada. A chance de mobs hostis aumentou.";
                    case LAID_BACK -> "Já escureceu? O tempo voa.";
                    case CHEERFUL -> "Olha as estrelas!";
                    case SARCASTIC -> "Ótimo. Escuridão. Meu cenário favorito.";
                    case MYSTERIOUS -> "A noite guarda muitos segredos.";
                };
                say(nightComment);
                return;
            }

            if (serverLevel.isRaining()) {
                String rainComment = switch (personalityType) {
                    case ADVENTUROUS -> "Chuva não vai parar a gente.";
                    case SCHOLARLY -> "Precipitação detectada. Visibilidade reduzida.";
                    case LAID_BACK -> "Perfeito. Agora estamos molhados.";
                    case CHEERFUL -> "Dá até vontade de dançar na chuva!";
                    case SARCASTIC -> "Adoro ficar encharcado. Disse ninguém.";
                    case MYSTERIOUS -> "O céu chora por algum motivo.";
                };
                say(rainComment);
                return;
            }
        }

        say(getRandomFrom(getIdleChat()));
    }

    private void doEmote() {
        if (emoteCooldown > 0) return;

        switch (random.nextInt(6)) {
            case 0 -> doWaveEmote();
            case 1 -> doHappyEmote();
            case 2 -> doLookAroundEmote();
            case 3 -> doStretchEmote();
            case 4 -> doNodEmote();
            case 5 -> doThinkingEmote();
        }
    }

    private void doSpecialEmote() {
        if (companion.level() instanceof ServerLevel serverLevel) {
            switch (random.nextInt(4)) {
                case 0 -> serverLevel.sendParticles(ParticleTypes.FIREWORK, companion.getX(), companion.getY() + 2, companion.getZ(), 15, 0.5, 0.5, 0.5, 0.1);
                case 1 -> serverLevel.sendParticles(ParticleTypes.NOTE, companion.getX(), companion.getY() + 2, companion.getZ(), 8, 0.5, 0.3, 0.5, 0.5);
                case 2 -> serverLevel.sendParticles(ParticleTypes.HEART, companion.getX(), companion.getY() + 2, companion.getZ(), 6, 0.5, 0.5, 0.5, 0.1);
                case 3 -> serverLevel.sendParticles(ParticleTypes.ENCHANT, companion.getX(), companion.getY() + 1, companion.getZ(), 20, 0.5, 1.0, 0.5, 0.5);
            }
        }
        emoteCooldown = 100;
    }

    private void doWaveEmote() {
        if (emoteCooldown > 0) return;
        companion.swing(companion.getUsedItemHand());
        emoteCooldown = 100;
    }

    private void doHappyEmote() {
        if (emoteCooldown > 0) return;
        if (companion.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.HEART, companion.getX(), companion.getY() + 2, companion.getZ(), 5, 0.5, 0.5, 0.5, 0.1);
        }
        emoteCooldown = 100;
    }

    private void doLookAroundEmote() {
        if (emoteCooldown > 0) return;
        float newYaw = companion.getYRot() + (random.nextFloat() - 0.5f) * 90;
        companion.setYRot(newYaw);
        emoteCooldown = 60;
    }

    private void doStretchEmote() {
        if (emoteCooldown > 0) return;
        if (companion.onGround()) {
            companion.setDeltaMovement(companion.getDeltaMovement().add(0, 0.3, 0));
        }
        emoteCooldown = 80;
    }

    private void doNodEmote() {
        if (emoteCooldown > 0) return;
        companion.swing(companion.getUsedItemHand());
        emoteCooldown = 60;
    }

    private void doThinkingEmote() {
        if (emoteCooldown > 0) return;
        if (companion.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.NOTE, companion.getX(), companion.getY() + 2.2, companion.getZ(), 2, 0.2, 0.1, 0.2, 0.0);
        }
        emoteCooldown = 80;
    }

    public void doSadEmote() {
        if (companion.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.SMOKE, companion.getX(), companion.getY() + 2, companion.getZ(), 8, 0.3, 0.3, 0.3, 0.02);
        }
        setMood("triste", 400);
    }

    public void doAngryEmote() {
        if (companion.level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.ANGRY_VILLAGER, companion.getX(), companion.getY() + 2, companion.getZ(), 3, 0.3, 0.3, 0.3, 0.1);
        }
        setMood("irritado", 300);
    }

    private void say(String message) {
        if (!Config.BROADCAST_COMPANION_CHAT.get()) return;

        String formatted = "[" + companion.getCompanionName() + "] " + message;
        Component component = Component.literal(formatted);

        List<Player> nearbyPlayers = companion.level().getEntitiesOfClass(
                Player.class,
                companion.getBoundingBox().inflate(64),
                Player::isAlive
        );

        for (Player player : nearbyPlayers) {
            player.sendSystemMessage(component);
        }

        chatCooldown = 400;
    }

    private void setMood(String newMood, int duration) {
        this.mood = newMood;
        this.moodDuration = duration;
    }

    public String getMood() {
        return mood;
    }

    private String getRandomFrom(String[] options) {
        if (options == null || options.length == 0) return "";
        return options[random.nextInt(options.length)];
    }

    private String[] concat(String[] a, String[] b) {
        String[] result = new String[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    public String getPersonalityDescription() {
        return personalityType.getName() + " (" + personalityType.getDescription() + ")";
    }
}
