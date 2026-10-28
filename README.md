# lab-desktop

Cliente nativo (Java 21, Swing + FlatLaf) do Painel do Lab, para **Mac, Windows e Linux** (o instalador leva o Java junto: `docs/INSTALAR.md`): cofre de chaves SSH, vários servidores, túnel feito pelo próprio app, dashboard de servidor e de projetos, gerenciador de arquivos (SFTP), **terminal nativo** (JediTerm sobre SSH) e, **se o servidor tiver Claude**, as sessões do Claude. O servidor continua sendo o agente Node do painel, consumido só pela API.

Um app só, com IA **opcional**: terminal, arquivos, monitoramento e Git funcionam sem IA; as telas do Claude (sessão, limite, pergunta pendente) só aparecem para um servidor que tem Claude, e o app nunca instala IA. Tema claro, escuro ou o do sistema (padrão), em Visualizar, Aparência.

Documentação em `docs/`: `INSTALAR.md` (instalar no Windows e no Mac, para quem não é técnico), `CI.md` (o CI, os instaladores, o que ver no primeiro run e a assinatura de código), `ARQUITETURA.md` (o desenho), `CONTRATO-API.md` (as rotas do agente).

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
| `./gradlew empacotar` | gera o instalador DO sistema onde roda, com o Java junto, em `build/instaladores/`: `.dmg` no Mac, `.msi` e `.zip` portátil no Windows (precisa do WiX 3), `.tar.gz` no Linux, e `SHA256SUMS-*.txt` (ARQUITETURA 11.4). `jpackageImagem` gera só o `.app` ou a pasta; `verificarModulos` confere os módulos do `jlink` com o `jdeps`; `conferirImagem` roda o app da imagem com `--autoteste` (sem janela) |
| `./gradlew test -Plabdesktop.laf=generico` | a suíte inteira com o LaF do Windows e do Linux (`FlatLightLaf`/`FlatDarkLaf`) num Mac; os PNGs vão para `build/telas-generico/` |
| `./gradlew provaWindows` | as provas que só rodam num Windows de verdade (ACL do NTFS, junção, registro do tema); em outro sistema não roda nada |
| `build/install/lab-desktop/bin/lab-desktop --versao` / `--autoteste` | imprime a versão / confere módulos, bibliotecas, cofre, SSH, tema e PTY SEM janela (é o que o CI roda dentro do instalador) |
| `./gradlew run -Plabdesktop.debug=true --args="--pasta cofre-dev"` | liga o log de requisições: cada chamada ao agente vira uma linha em `~/Library/Logs/LabDesktop/lab-desktop.log` (`[lab] GET /api/projetos -> 200 em 54 ms`; sem query, token nem corpo). É a prova da carga no servidor (critério 6 da fase 1). Também vale `-Dlabdesktop.debug=true` ou `LABDESKTOP_DEBUG=1`. |
| `./gradlew run -Plabdesktop.acoes=true` | mostra as ações de escrita da lista de projetos (iniciar, parar, Abrir Claude, Retomar... e, **Novo projeto**, **Importar** e **Adicionar container**), que ficam escondidas até o usuário decidir ligá-las por padrão (trocar UMA flag): a lista da fase 1 é somente leitura |
| `./gradlew test -Plabdesktop.dockerReal=true --tests '*DockerDeVerdade*'` | roda o laboratório local e o Novo projeto (a rede `<nome>_default` com os rótulos do compose e o `docker compose config` dos quatro modelos de container) com o Docker de VERDADE deste computador (precisa do Docker no ar; usa só containers, rede, volume e imagem `labteste-*`, e o projeto `labteste-novo`, e os remove no fim). Sem a propriedade esses testes são pulados: o build normal nunca toca no seu Docker |
| `scripts/capturar-fixtures.sh [alvo-ssh]` | grava respostas REAIS do agente (só `GET`, por `ssh lab curl`) em `src/test/resources/lab-real/`. **Ainda não foi executado**: precisa dum servidor real e do painel publicado; é o que destrava os 6 testes de `ContratoComFixturesReaisTest` |
| `python3 -I scripts/gerar-lista-de-senhas.py <pasta>` | regenera `senhas-comuns.txt`, a lista de senhas comuns da régua da senha-mestra (origem e licença no cabeçalho do arquivo) |

Os testes de interface desenham as telas e os diálogos sem abrir janela e gravam PNGs em `build/telas/` (tema claro e
escuro; os do Novo projeto são `dialogo-novo-projeto-*`, `dialogo-adicionar-container-*`, `projetos-lista-vazia-com-convite-*`, `projetos-lista-com-botao-novo-projeto-*`, `visao-geral-com-adicionar-container-*` e `janela-este-computador-projeto*`; os da Cloudflare são `dialogo-cloudflare-*`, `dialogo-publicar-*`, `dialogo-remover-*`, `pagina-cloudflare-*` e `aba-internet-*`); abra-os para conferir o visual depois de mexer em layout. Em `run`, o app liga o vigia da EDT
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
| **Cloudflare dentro do app**: aba **Cloudflare** do servidor (conectar a conta com um token de API que fica só no cofre cifrado, ver a conta, o domínio e o túnel, conferir o token, trocar, desconectar, listar os endereços que o app criou) e aba **Internet** de cada projeto (**Publicar na internet** com prévia de tudo o que muda e confirmação, Abrir, Remover digitando o endereço); túnel gerido por arquivo (o `config.yml` do cloudflared no servidor) ou pela API da Cloudflare; se algo falha no meio, o app desfaz só o que ele mesmo fez | **feito** (`docs/ARQUITETURA.md` 7.11); Conectar só lê e grava no cofre local; Publicar e Remover ficam atrás da MESMA trava das outras ações (`-Plabdesktop.acoes=true`); testado com uma Cloudflare FALSA e um servidor simulado (SSH e SFTP de verdade, Docker de mentira). **Falta a prova real**: conta da Cloudflare, o lab e o Windows (como provar: abaixo). **Criar túnel** pelo app ficou de fora: o app detecta e diz o que falta |
| `.app`, `.dmg`, `.msi` e instaladores | **feito**: `./gradlew empacotar`; ícone, `Info.plist` com Rede Local e assinatura ad-hoc no Mac; sem assinatura de código paga (SmartScreen e Gatekeeper avisam: `docs/INSTALAR.md`). Notificações do sistema e ícone na barra de menus continuam sem existir |
| Adicionar, editar e remover servidor | feito (assistente de três passos); rotação de chave e de token e "redefinir pin" faltam |
| Instalador do agente (instalar, atualizar e remover o agente do perfil monitor, sempre pelo clique do usuário) | feito; falta a validação real no lab. O pacote do agente é montado por `./gradlew empacotarAgente` (manual, commit fixado em `gradle.properties`) |
| **Este computador**: o laboratório no próprio Mac, sem SSH, sem chave e sem túnel: pergunta "Este computador ou Outro servidor (SSH)", "Preparar este computador" (confere Docker, disco, memória, porta e git só lendo; instala o que falta só com clique e confirmação; abre o Docker; cria e remove o laboratório local), terminal por PTY (pty4j), arquivos por `java.nio`, agente em `127.0.0.1` | **feito** (`docs/ARQUITETURA.md` 7.7); testado com um computador simulado e, no Mac, com o Docker de verdade (`./gradlew test -Plabdesktop.dockerReal=true --tests '*DockerDeVerdade*'`, containers `labteste-*`). **Windows e Linux**: o código é neutro de sistema e testado como texto; a prova no Windows é |
| **Resumo com tendências e Registro**: seis gráficos (CPU, Memória, Disco, Escrita no disco, Download, Upload) em 10 min, 1 h, 1 dia, 1 semana e 1 mês; botão **Relatório** (mínimo, média, máximo, P95, hora do pico, estimativa de quando o disco enche só com 3 dias ou mais de dados (média de dias inteiros), CSV e texto para copiar); caixa **Registro do servidor** (journal, syslog ou `docker logs`, só leitura, filtro, pausa, cópia, "Ver tudo") | **feito** (ARQUITETURA 8.4); precisa do agente novo (`recursos.historico`, commit fixado em `gradle.properties`): sem ele o app mostra 10 min e avisa. O gráfico de 1 h só enche depois de uma hora com o agente no ar. Em "Este computador" não há Download nem Upload (a rede medida é a do container do agente) e CPU, memória e disco são os da máquina virtual do Docker. Sem prova num servidor real |
| **Windows e Linux** | **feito** (ARQUITETURA 11.4): pastas, claro/escuro, LaF genérico com os mesmos tokens, atalhos Ctrl, fechar a janela sai do app, ACL do NTFS, sono por relógio, ícone, argumentos de processo validados, `wsl` em UTF-16, nativos conferidos. **Sem prova num Windows de verdade** (como provar: abaixo) |

**Testes pulados (11)**: 6 em `ContratoComFixturesReaisTest`, que só rodam com as respostas reais do agente em `src/test/resources/lab-real/` (o `scripts/capturar-fixtures.sh` as grava; nunca foi executado, então os DTOs só foram exercitados com mocks do front e fixtures sintéticas), e 2 em `ProvisionarCofreDev`, uma ferramenta manual que só roda com `LABDEV_FASE` (veja "Desenvolvimento: cofre de teste"), e 3 que só rodam com o Docker de verdade (`-Plabdesktop.dockerReal=true`: 2 do laboratório local e 1 do Novo projeto). Os spikes de risco da fase 1: o do terminal (B1) foi superado pelo terminal de verdade; o do `.app` pelo Finder (B2: Rede Local, notificações, ícone na barra de menus) continua sem ser feito. O que falta, com o motivo e a ordem, está em ``, seção "O que falta".

Observação sobre testes que pulam: `GabaritoIgualAoDoAgenteTest` (o esqueleto de projeto do app igual ao do agente) só roda onde há `node` no PATH; sem `node` ele é pulado e conta entre os testes pulados.

**CI** (`docs/CI.md`): `.github/workflows/build.yml` roda `./gradlew build` (compila, testes de interface sem janela, verificação de dependências) em todo push na `main` e em todo pull request, em **Linux, Windows e Mac** (Temurin 21), e no Windows também as provas `provaWindows` (informativo). `.github/workflows/instaladores.yml` gera os instaladores de cada sistema, **só à mão ou em tag `v*`**, e os guarda como artefatos; publicar numa Release é um job à parte que exige marcar "publicar" E aprovar o ambiente `publicacao`. Não há segredos nem deploy; nada foi exercitado no GitHub ainda (só roda com o seu "pode publicar").

Ao acrescentar uma dependência: `./gradlew --write-verification-metadata sha256 build` e revise o diff do `verification-metadata.xml`.

## Desenvolvimento: cofre de teste

`./gradlew run --args="--pasta <dir> --senha-de-teste"` abre um cofre de TESTE sem pedir a senha: lê só
`<dir>/SENHA-DE-TESTE.txt`, e apenas se `--pasta` foi dada (nunca na pasta padrão, a do cofre de verdade), se for arquivo
comum, do usuário e com permissão 0600 (`util/SenhaDeTeste`). Qualquer dúvida, o app pede a senha como sempre.

`src/test/.../dev/ProvisionarCofreDev` monta esse cofre já ligado a um servidor real, em duas fases, só com as variáveis
`LABDEV_FASE`, `LABDEV_PASTA` (e `LABDEV_IMPRESSAO` na fase 2). Fase 1: cria cofre, chave e servidor e grava
`linha-authorized-keys.txt`; depois de instalar essa linha no servidor, fase 2: confere a impressão digital do host,
grava o pin, conecta e registra o token. A pasta `cofre-dev/` fica fora do git.
