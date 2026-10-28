# lab-desktop

Cliente nativo (Java 21, Swing + FlatLaf) do Painel do Lab: cofre de chaves SSH no Mac, vários servidores, túnel feito pelo próprio app, dashboard de servidor e de projetos, gerenciador de arquivos (SFTP), **terminal nativo** (JediTerm sobre SSH) e, **se o servidor tiver Claude**, as sessões do Claude. O servidor continua sendo o agente Node do painel, consumido só pela API.

Um app só, com IA **opcional**: terminal, arquivos, monitoramento e Git funcionam sem IA; as telas do Claude (sessão, limite, pergunta pendente) só aparecem para um servidor que tem Claude, e o app nunca instala IA. Tema claro, escuro ou o do sistema (padrão), em Visualizar, Aparência.

Documentação em `docs/`: `ARQUITETURA.md` (o desenho), `CONTRATO-API.md` (as rotas do agente).

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
| `./gradlew run -Plabdesktop.acoes=true` | mostra as ações de escrita da lista de projetos (iniciar, parar, Abrir Claude, Retomar... e, **Novo projeto**, **Importar** e **Adicionar container**), que ficam escondidas até o usuário decidir ligá-las por padrão (trocar UMA flag): a lista da fase 1 é somente leitura |
| `./gradlew test -Plabdesktop.dockerReal=true --tests '*DockerDeVerdade*'` | roda o laboratório local e o Novo projeto (a rede `<nome>_default` com os rótulos do compose e o `docker compose config` dos quatro modelos de container) com o Docker de VERDADE deste computador (precisa do Docker no ar; usa só containers, rede, volume e imagem `labteste-*`, e o projeto `labteste-novo`, e os remove no fim). Sem a propriedade esses testes são pulados: o build normal nunca toca no seu Docker |
| `scripts/capturar-fixtures.sh [alvo-ssh]` | grava respostas REAIS do agente (só `GET`, por `ssh lab curl`) em `src/test/resources/lab-real/`. **Ainda não foi executado**: precisa dum servidor real e do painel publicado; é o que destrava os 6 testes de `ContratoComFixturesReaisTest` |
| `python3 -I scripts/gerar-lista-de-senhas.py <pasta>` | regenera `senhas-comuns.txt`, a lista de senhas comuns da régua da senha-mestra (origem e licença no cabeçalho do arquivo) |

Os testes de interface desenham as telas e os diálogos sem abrir janela e gravam PNGs em `build/telas/` (tema claro e
escuro; os do Novo projeto são `dialogo-novo-projeto-*`, `dialogo-adicionar-container-*`, `projetos-lista-vazia-com-convite-*`, `projetos-lista-com-botao-novo-projeto-*`, `visao-geral-com-adicionar-container-*` e `janela-este-computador-projeto*`); abra-os para conferir o visual depois de mexer em layout. Em `run`, o app liga o vigia da EDT
(`labdesktop.dev=true`), que acusa no log qualquer travada de mais de meio segundo na thread da interface.

## Estado e pendências

Em resumo:

| Área | Estado |
|---|---|
| Cofre, SSH, túnel, token, 2FA do agente (opcional), lista de projetos que atualiza sozinha | implementado e testado com um SSH embutido |
| Telas de leitura: servidor (Resumo, Segurança, Containers, Processos, Máquina, Projetos, Arquivos, Terminal), projeto (Visão geral, Logs, Terminal, Arquivos, Backups, Consumo e limite) | implementadas; a tela Consumo da conta toda (`TelaConsumo`) ainda não existe |
| Gerenciador de arquivos por SFTP (servidor e projeto; abrir, editar, enviar, baixar, apagar) | feito |
| **Terminal nativo** (JediTerm sobre SSH com PTY): aba Terminal do servidor (vários shells) e aba Terminal de cada projeto, que abre sozinha num shell na pasta do projeto e, com Claude no servidor, tem o seletor Shell \| Claude | **feito** (ARQUITETURA 7); testado de ponta a ponta contra um SSH embutido; a matriz de teclado no teclado do usuário (ABNT2, US-internacional) ainda não foi conferida |
| Ações de escrita (iniciar, parar, abrir sessão do Claude, limites, Freio global), barra lateral da sessão | **falta**; o código existe e é testado, mas só liga com `-Plabdesktop.acoes=true` (o terminal NÃO depende disso) |
| **Novo projeto**: criar do zero (pelo agente do lab ou, em Este computador, pela pasta), **importar** (Git, pasta do computador, pasta do servidor) e **Adicionar container** (Node, Postgres, Redis, imagem por nome, com a diferença do compose à vista antes de gravar); botão e convite na aba Projetos, item Arquivo > Novo projeto; Este computador passou a ter a aba Projetos | **feito** (`docs/ARQUITETURA.md` 7.10), atrás da MESMA trava das outras ações (`-Plabdesktop.acoes=true`); testado com agente falso, pasta temporária e, no Mac, com o Docker de verdade; **falta a prova real no lab e no Windows** |
| Notificações, ícone na barra de menus, `.app` | só o **esqueleto** do empacotamento (`-Pempacotar`: `jlink` e `jpackage`); sem ícone, `Info.plist` final nem assinatura |
| Adicionar, editar e remover servidor | feito (assistente de três passos); rotação de chave e de token e "redefinir pin" faltam |
| Instalador do agente (instalar, atualizar e remover o agente do perfil monitor, sempre pelo clique do usuário) | feito; falta a validação real no lab. O pacote do agente é montado por `./gradlew empacotarAgente` (manual, commit fixado em `gradle.properties`) |
| **Este computador**: o laboratório no próprio Mac, sem SSH, sem chave e sem túnel: pergunta "Este computador ou Outro servidor (SSH)", "Preparar este computador" (confere Docker, disco, memória, porta e git só lendo; instala o que falta só com clique e confirmação; abre o Docker; cria e remove o laboratório local), terminal por PTY (pty4j), arquivos por `java.nio`, agente em `127.0.0.1` | **feito** (`docs/ARQUITETURA.md` 7.7); testado com um computador simulado e, no Mac, com o Docker de verdade (`./gradlew test -Plabdesktop.dockerReal=true --tests '*DockerDeVerdade*'`, containers `labteste-*`). **Windows e Linux**: o código é neutro de sistema e testado como texto; a prova no Windows é |
| Windows (instalador, ConPTY, winget, WSL 2) | não existe item 10 do "O que falta") |

**Testes pulados (8)**: 6 em `ContratoComFixturesReaisTest`, que só rodam com as respostas reais do agente em `src/test/resources/lab-real/` (o `scripts/capturar-fixtures.sh` as grava; nunca foi executado, então os DTOs só foram exercitados com mocks do front e fixtures sintéticas), e 2 em `ProvisionarCofreDev`, uma ferramenta manual que só roda com `LABDEV_FASE` (veja "Desenvolvimento: cofre de teste"). Os spikes de risco da fase 1: o do terminal (B1) foi superado pelo terminal de verdade; o do `.app` pelo Finder (B2: Rede Local, notificações, ícone na barra de menus) continua sem ser feito. O que falta, com o motivo e a ordem, está em ``, seção "O que falta".

**CI**: `.github/workflows/build.yml` roda `./gradlew build` (compila, testes de interface sem janela, verificação de dependências) em todo push na `main` e em todo pull request, em `ubuntu-latest` com Temurin 21. Não há segredos nem deploy.

Ao acrescentar uma dependência: `./gradlew --write-verification-metadata sha256 build` e revise o diff do `verification-metadata.xml`.

## Desenvolvimento: cofre de teste

`./gradlew run --args="--pasta <dir> --senha-de-teste"` abre um cofre de TESTE sem pedir a senha: lê só
`<dir>/SENHA-DE-TESTE.txt`, e apenas se `--pasta` foi dada (nunca na pasta padrão, a do cofre de verdade), se for arquivo
comum, do usuário e com permissão 0600 (`util/SenhaDeTeste`). Qualquer dúvida, o app pede a senha como sempre.

`src/test/.../dev/ProvisionarCofreDev` monta esse cofre já ligado a um servidor real, em duas fases, só com as variáveis
`LABDEV_FASE`, `LABDEV_PASTA` (e `LABDEV_IMPRESSAO` na fase 2). Fase 1: cria cofre, chave e servidor e grava
`linha-authorized-keys.txt`; depois de instalar essa linha no servidor, fase 2: confere a impressão digital do host,
grava o pin, conecta e registra o token. A pasta `cofre-dev/` fica fora do git.
