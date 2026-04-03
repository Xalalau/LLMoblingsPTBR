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
