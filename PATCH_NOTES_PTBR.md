# Alterações aplicadas

Este pacote contém uma versão modificada do projeto com foco em reduzir o uso de inglês nas partes que mais afetam a experiência no jogo.

## O que foi alterado

- `OllamaClient.java`
  - prompt-base reescrito para Português do Brasil
  - regras reforçadas para manter `message` em PT-BR
  - observações internas mudadas para PT-BR
  - parser de fallback ampliado com palavras-chave em português

- `CompanionAI.java`
  - falas do companion traduzidas para PT-BR
  - mensagens internas do loop traduzidas
  - respostas de ações (`ActionResult`) ajustadas para PT-BR
  - mensagens de mochila, gadget, teleporte, portal, Pokémon e construção traduzidas

- `CompanionPersonality.java`
  - sistema de personalidade reescrito com falas em PT-BR
  - cumprimentos, piadas, comentários e reações traduzidos

- `CompanionCommand.java`
  - mensagens de comando e ajuda traduzidas
  - listagem de companions com estados em PT-BR

- `BuildingTask.java`
  - progresso e falhas de construção traduzidos

- `AutonomousTask.java`
  - relatórios automáticos principais traduzidos

- `CompanionEntity.java`
  - algumas mensagens automáticas do companion traduzidas

- `CobblemonIntegration.java`
  - vários rótulos visíveis de estatísticas e nomes padrão traduzidos

- `Config.java`
  - comentários traduzidos
  - host padrão alterado para `127.0.0.1`

## Observação importante

Eu não consegui validar a compilação no ambiente em que fiz o patch porque o Gradle Wrapper tentou baixar a distribuição do Gradle da internet e o acesso externo não estava disponível.

Erro encontrado ao validar:
- `UnknownHostException` ao tentar baixar `https://services.gradle.org/distributions/gradle-8.10-bin.zip`

## Próximo passo recomendado

No seu Linux local, rode:

```bash
chmod +x gradlew
./gradlew build
```

Se aparecer algum erro de compilação, o ponto mais provável é alguma string traduzida que precise de um ajuste fino. O núcleo das mudanças de idioma já está aplicado.

# Patch notes - autonomous behavior fixes

## Corrigido

### 1. Loop `ASSESSING -> HUNTING -> ASSESSING`
- Adicionado cooldown de caça (`nextHuntAllowedTick`)
- Falha de caça agora desvia para `PATROLLING` ou `EXPLORING` em vez de voltar direto para `ASSESSING`
- Falhas repetidas aumentam o tempo antes de tentar caçar de novo

### 2. Uso de armazenamento local
- O modo autônomo agora detecta melhor comida, armas e armaduras em armazenamento local
- Novo estado `RETRIEVING_STORAGE`
- O companion agora pode pegar comida de baús/containers próximos
- O companion agora pode pegar arma/armadura de baús/containers próximos

### 3. Scan pesado demais da base
- O scan de storage deixou de rodar em toda avaliação
- Scan agora usa cooldown e evita repetir o mesmo relatório sem mudança
- Scan é centralizado na base/casa em vez da posição momentânea do companion

### 4. Melhor fallback do terminal ME
- Se o terminal ME falhar ou não tiver comida, o companion tenta storage local ou explorar
- Evita cair direto no mesmo ciclo de caça impossível

### 5. `farm` prometido no prompt mas não implementado
- Adicionado suporte à ação `farm` / `harvest` em `CompanionAI`
- Colhe plantações maduras próximas e replanta quando possível usando `UltimineHelper`
- Parser de fallback do `OllamaClient` agora entende termos de colheita em inglês e português

## Arquivos alterados
- `src/main/java/com/gblfxt/llmoblings/ai/AutonomousTask.java`
- `src/main/java/com/gblfxt/llmoblings/ai/CompanionAI.java`
- `src/main/java/com/gblfxt/llmoblings/ai/OllamaClient.java`

## Observação
- A compilação não foi validada neste ambiente porque o Gradle Wrapper tentou baixar a distribuição do Gradle da internet e a rede externa não estava disponível.


- v12 backport: MovementRecoveryController separado e integrado ao CompanionAI do pacote v11.
