# lab-desktop

Cliente nativo (Java 21, Swing + FlatLaf) do Painel do Lab: cofre de chaves SSH no Mac, vários servidores, túnel feito pelo próprio app, dashboard, sessões do Claude com terminal nativo (JediTerm) e notificações do macOS. O servidor continua sendo o agente Node do painel, consumido só pela API.

Documentação de arquitetura em `docs/`.

## Desenvolvimento

O shell não carrega o `.zshrc`; em cada comando:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"
```

| Comando | O que faz |
|---|---|
| `./gradlew build` | compila, roda os testes e confere as dependências contra `gradle/verification-metadata.xml` |
| `./gradlew test` | só os testes (o Argon2id de produção leva cerca de 1 s; o resto usa o KDF mínimo) |
| `./gradlew run --args="--pasta cofre-dev"` | abre o app com uma pasta de dados de desenvolvimento (já ignorada pelo git) |
| `./gradlew -Pempacotar jpackageApp` | esqueleto do `.app`; sem `-Pempacotar` as tarefas de empacotamento são puladas |
| `./gradlew run -Plabdesktop.debug=true --args="--pasta cofre-dev"` | liga o log de requisições: cada chamada ao agente vira uma linha em `~/Library/Logs/LabDesktop/lab-desktop.log` (`[lab] GET /api/projetos -> 200 em 54 ms`; sem query, token nem corpo). É a prova da carga no servidor (critério 6 da fase 1). Também vale `-Dlabdesktop.debug=true` ou `LABDESKTOP_DEBUG=1`. |
| `./gradlew run -Plabdesktop.acoes=true` | mostra as ações de escrita da lista de projetos (iniciar, parar, Abrir Claude, Retomar...), que ficam escondidas até a fase 3: a lista da fase 1 é somente leitura |
| `scripts/capturar-fixtures.sh [alvo-ssh]` | grava respostas REAIS do agente (só `GET`, por `ssh lab curl`) em `src/test/resources/lab-real/`. **Ainda não foi executado**: precisa dum servidor real e do painel publicado |
| `python3 -I scripts/gerar-lista-de-senhas.py <pasta>` | regenera `senhas-comuns.txt`, a lista de senhas comuns da régua da senha-mestra (origem e licença no cabeçalho do arquivo) |

Os testes de interface desenham as telas e os diálogos sem abrir janela e gravam PNGs em `build/telas/` (tema claro e
escuro); abra-os para conferir o visual depois de mexer em layout. Em `run`, o app liga o vigia da EDT
(`labdesktop.dev=true`), que acusa no log qualquer travada de mais de meio segundo na thread da interface.

## Estado e pendências

A fase 1 está implementada e testada (`./gradlew build`), mas NÃO está fechada: nada foi executado contra um servidor real. Pendem, e estão detalhadas em `` ("Pendências que dependem dum servidor real"): publicar o painel atualizado (com `GET /api/versao`); capturar as fixtures reais (`scripts/capturar-fixtures.sh`; até lá `ContratoComFixturesReaisTest` fica "skipped" e o critério "contrato" é parcial); os spikes B1 (terminal) e B2 (`.app`) com `docs/spikes.md`;.

Ao acrescentar uma dependência: `./gradlew --write-verification-metadata sha256 build` e revise o diff do `verification-metadata.xml`.
