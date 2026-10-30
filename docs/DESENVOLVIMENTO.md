# Desenvolvimento

Como compilar, testar, empacotar e conferir o Lab Desktop. Para o resumo do projeto, veja o [README](../README.md).

## Compilar e testar

Use um JDK 21 completo (`javac`, `jlink`, `jpackage`) e defina `JAVA_HOME` para ele; o Gradle 9 usa por padrão o JDK que achar primeiro. Em cada comando, no Mac com o Homebrew, por exemplo:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
```

| Comando | O que faz |
|---|---|
| `./gradlew build` | compila, roda os testes e confere as dependências contra `gradle/verification-metadata.xml` |
| Versão do app | A versão muda a cada build: `<base>.<n>` (por exemplo `0.1.320`), com `n` = número de commits do git. Ela aparece em **Ajustes, Sobre** (com o commit e a data da compilação; `-alterado` no commit quer dizer que havia mudanças não commitadas), em `--versao` e na primeira linha do log. A base (`MAJOR.MINOR`) fica em `gradle.properties`, em `labdesktop.versaoBase`: para uma versão nova de verdade, troque só essa linha. Sem git ou com clone raso a versão é `<base>.0` (ARQUITETURA 11.5). Rodando solto (`./gradlew run`) o app diz `dev` e o commit |
| `./gradlew test` | só os testes (o Argon2id de produção leva cerca de 1 s; o resto usa o KDF mínimo) |
| `./gradlew run --args="--pasta cofre-dev"` | abre o app com uma pasta de dados de desenvolvimento (já ignorada pelo git) |
| `./gradlew empacotar` | gera o instalador DO sistema onde roda, com o Java junto, em `build/instaladores/`: `.dmg` no Mac, `.msi` e `.zip` portátil no Windows (precisa do WiX 3), `.tar.gz` no Linux, e `SHA256SUMS-*.txt` (ARQUITETURA 11.4). `jpackageImagem` gera só o `.app` ou a pasta; `verificarModulos` confere os módulos do `jlink` com o `jdeps`; `conferirImagem` roda o app da imagem com `--autoteste` (sem janela) |
| `./gradlew test -Plabdesktop.laf=generico` | a suíte inteira com o LaF do Windows e do Linux (`FlatLightLaf`/`FlatDarkLaf`) num Mac; os PNGs vão para `build/telas-generico/` |
| `scripts/gerar-galeria.sh` | depois de `./gradlew test`, copia as telas de `build/galeria/` (janela do macOS desenhada em volta, dados inventados) para `img/` e gera as miniaturas de 460 px em `img/mini/` (precisa do `cwebp`) |
| `./gradlew provaWindows` | as provas que só rodam num Windows de verdade (ACL do NTFS, junção, registro do tema); em outro sistema não roda nada |
| `build/install/lab-desktop/bin/lab-desktop --versao` / `--autoteste` | imprime a versão / confere módulos, bibliotecas, cofre, SSH, tema e PTY SEM janela (é o que o CI roda dentro do instalador) |
| `./gradlew run -Plabdesktop.debug=true --args="--pasta cofre-dev"` | liga o log de requisições: cada chamada ao agente vira uma linha no log do app (`[lab] GET /api/projetos -> 200 em 54 ms`; sem query, token nem corpo). Também vale `-Dlabdesktop.debug=true` ou `LABDESKTOP_DEBUG=1`. |
| `./gradlew run -Plabdesktop.acoes=false` | DESLIGA as ações de escrita da lista de projetos (iniciar, parar, Abrir Claude, Retomar, Novo projeto, Importar, Adicionar container...), que vêm LIGADAS por padrão e continuam com a confirmação de cada ação; os botões que a trava desliga APARECEM desligados, com a explicação, e não somem |
| `./gradlew test -Plabdesktop.dockerReal=true --tests '*DockerDeVerdade*'` | roda o laboratório local e o Novo projeto (a rede `<nome>_default` com os rótulos do compose e o `docker compose config` dos quatro modelos de container) com o Docker de VERDADE deste computador (precisa do Docker no ar; usa só containers, rede, volume e imagem `labteste-*`, e o projeto `labteste-novo`, e os remove no fim). Sem a propriedade esses testes são pulados: o build normal nunca toca no seu Docker. Também precisa do pacote do agente (veja abaixo) |
| `./gradlew empacotarAgente -PagenteCommit=<hash de 40 dígitos>` | monta o pacote do agente que o instalador envia ao servidor (`src/main/resources/agente/`) a partir de `../lab-desktop-agente` (ou `-PpainelDir=<pasta>`) no commit indicado, e repina `agenteCommit` em `gradle.properties`. Só se roda quando o agente muda; o build normal só confere o pacote |
| `scripts/capturar-fixtures.sh [alvo-ssh]` | grava respostas REAIS do agente (só `GET`, por `ssh <alvo> curl`) em `src/test/resources/lab-real/` (ignorada pelo git: contém nomes de projetos de verdade); é o que destrava os testes de `ContratoComFixturesReaisTest` |
| `python3 -I scripts/gerar-lista-de-senhas.py <pasta>` | regenera `senhas-comuns.txt`, a lista de senhas comuns da régua da senha-mestra (origem e licença no cabeçalho do arquivo) |

Os testes de interface desenham as telas e os diálogos sem abrir janela e gravam PNGs em `build/telas/` (tema claro e escuro); abra-os para conferir o visual depois de mexer em layout. Em `run`, o app liga o vigia da EDT (`labdesktop.dev=true`), que acusa no log qualquer travada de mais de meio segundo na thread da interface.

**O pacote do agente vem no repositório.** O app leva dentro do jar o agente público de monitoramento (só leitura; `src/main/resources/agente/`), montado a partir do repositório `lab-desktop-agente` do mesmo autor (a tarefa `empacotarAgente`, acima). O pacote é conferido a cada build (`PacoteDoAgenteTest`: integridade, arquivos, rotas, token obrigatório, nada que execute programa); a comparação com `../lab-desktop-agente` só roda onde esse repositório está ao lado (a CI não o tem). Os testes do instalador e das janelas usam o pacote real.

**Testes pulados**: os de `ContratoComFixturesReaisTest` (só rodam com as respostas reais do agente em `src/test/resources/lab-real/`), os 2 de `ProvisionarCofreDev` (uma ferramenta manual que só roda com `LABDEV_FASE`, veja "Cofre de teste"), os que só rodam com o Docker de verdade (`-Plabdesktop.dockerReal=true`), a comparação do pacote com `../lab-desktop-agente` (pulada sem esse repositório ao lado) e o `GabaritoIgualAoDoAgenteTest` (o esqueleto de projeto do app igual ao de um agente que cria projetos: só roda com `-Plabdesktop.painelDoLab=/caminho/do/painel.js` e `node` no PATH; sem isso conta entre os pulados).

**CI** (`CI.md`): `.github/workflows/build.yml` roda `./gradlew build` (compila, testes de interface sem janela, verificação de dependências) em todo push na `main` e em todo pull request, em **Linux, Windows e Mac** (Temurin 21), e no Windows também as provas `provaWindows` (informativo). `.github/workflows/instaladores.yml` gera os instaladores de cada sistema, **só à mão ou em tag `v*`**, e os guarda como artefatos; publicar numa Release é um job à parte que exige marcar "publicar" E aprovar o ambiente `publicacao`. Não há segredos nem deploy.

Ao acrescentar uma dependência: `./gradlew --write-verification-metadata sha256 build` e revise o diff do `verification-metadata.xml`.

## Cofre de teste

`./gradlew run --args="--pasta <dir> --senha-de-teste"` abre um cofre de TESTE sem pedir a senha: lê só `<dir>/SENHA-DE-TESTE.txt`, e apenas se `--pasta` foi dada (nunca na pasta padrão, a do cofre de verdade), se for arquivo comum, do usuário e com permissão 0600 (`util/SenhaDeTeste`). Qualquer dúvida, o app pede a senha como sempre.

`src/test/.../dev/ProvisionarCofreDev` monta esse cofre já ligado a um servidor real, em duas fases, só com as variáveis `LABDEV_FASE`, `LABDEV_PASTA` (e `LABDEV_IMPRESSAO` na fase 2). Fase 1: cria cofre, chave e servidor e grava `linha-authorized-keys.txt`; depois de instalar essa linha no servidor, fase 2: confere a impressão digital do host, grava o pin, conecta e registra o token. A pasta `cofre-dev/` fica fora do git.

## Como conferir na prática

Faça num servidor de TESTE (ou num projeto de teste), nunca em produção: criar o repositório escreve na pasta do projeto.

1. **Criar um repositório.** Crie um projeto de teste e, no terminal dele, faça `printf "SENHA=1\n" > .env`, `printf "ola\n" > a.txt` e `head -c 6000000 /dev/zero > grande.bin`. Na Visão geral, a linha Código diz "Sem Git". Clique em "Criar repositório Git": a janela confere sem escrever nada (confira com `ls -a` que não existe `.git` ainda), mostra o `.env` fora, o `grande.bin` na lista de arquivos grandes e as linhas do `.gitignore`. Clique em criar: `git log --oneline` mostra um commit "Primeiro commit", `git ls-files` lista só `.gitignore` e `a.txt`, e `git config --global user.name` não mudou.
2. **Ver os commits.** A linha Código passa a mostrar o branch e o último commit; "Ver commits" abre a aba Git. Clique num commit: mensagem, arquivos e diff. Edite `a.txt` no terminal e clique em Atualizar: a aba Alterações mostra o arquivo.
3. **Repositório em subpasta.** Num projeto sem `.git` na pasta principal mas com `app/.git`, a aba Git mostra "Repositório: app/" e os commits dele, e "Criar repositório Git" não aparece. Com dois repositórios, a aba mostra a lista e só lê o que você escolher. Fechar e abrir o app mantém a escolha.
4. **Reconexão.** Cadastre um servidor de teste pelo AWS Session Manager com um ID de instância que não existe (invente um) e deixe o app aberto: nos primeiros minutos a faixa diz "Reconectando (tentativa N)"; depois de 12 falhas seguidas ela passa a "Tentando de novo a cada 5 min", com a causa. No `ps` do computador, o `aws ssm start-session` aparece uma vez a cada 5 minutos. "Tentar agora" faz uma tentativa na hora e volta ao ritmo normal.
5. **Encerrar um processo.** Num servidor de teste, deixe um `sleep 600` rodando, abra Servidor > Processos, clique em Encerrar e confira nome, número e hora na confirmação.

Os testes do app gravam as telas em `build/telas/` (tema claro e escuro) e as imagens da galeria em `build/galeria/`.

## Recursos opcionais do Claude

O Claude é opcional e não é um módulo à parte: o app é um só. O sinal de que o servidor tem Claude é `recursos.claude` de `GET /api/versao` (`RecursosDoAgente`); sem ele, tudo o que está abaixo fica escondido e o resto funciona igual. A fronteira, por arquivo:

| Parte | Classes e telas do Claude |
|---|---|
| Modelo e API (`api`, `api.modelo`) | `TelaClaude`, `SessaoAberta`, `SessaoProjeto`, `InfoSessao`, `LoginPendente`, `TokensDoProjeto` (consumo e limite) e os campos de Claude de `Projeto`, `DetalheProjeto`, `Detalhe`, `RecursosDoAgente` e `VersaoAgente`; em `ClienteApi`, as rotas `/api/projetos/:p/claude/*` e `/api/projetos/:p/limite*` |
| Estado e ações (`servidor`) | `ModeloDaSessao`, os tópicos de tela e de limites em `Topico` e `ServidorLab`, e as ações de sessão e limite de `AcoesDoServidor` |
| Telas (`ui.telas`) | a aba **Consumo e limite** (`PaginaConsumo`, `ModeloDeConsumo`, `PainelDeCredito`), o seletor **Shell \| Claude** de `PaginaTerminalDoProjeto`, o alerta de pergunta e de login pendente (`AlertaDaPagina`), os selos e botões de sessão da lista de projetos (`LinhaProjetoVisual`, `LinhaDeProjeto`, `ColunasDaLista`, `ListagemDeProjetos`), a faixa de sessões pausadas (`FaixaAviso`) e `PaginaVisaoGeral` |
| Diálogos (`ui.dialogos`) | `DialogoLimite` e, em `DialogoNovoProjeto`, a caixa "Incluir guia para o Claude" |
| Terminal (`terminal`, `ssh`) | o modo Claude: `LinhaDePty` (anexar à sessão `tmux` do container `lab-claude-<projeto>`), `ComandoDeTerminal.SessaoDoClaude` e a classificação de saída de `ClassificadorDeSaida` |
| Projetos (`projetos`) | `GabaritoDoProjeto`: o esqueleto de projeto criado SEM guia para IA (o guia, quando pedido, é escrito pelo agente) |
| Ajuda | os trechos "Claude" de `agente.txt`, `logs-e-consumo.txt` e `terminal.txt` |

Hoje o Claude não é um módulo à parte: cerca de um em cada oito arquivos de `src/main/java` e de `src/test/java` cita o Claude. Separá-lo num módulo opcional pediria uma interface que as telas do projeto consultam para pedir as peças do Claude; o sinal em tempo de execução já existe (`recursos.claude`) e o teste `TelaServidorSemClaudeTest` prova a tela sem Claude.
