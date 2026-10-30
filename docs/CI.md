# CI e instaladores

Para o usuário. O `build.yml` rodou pela primeira vez em 2026-10-30 (run 41091992893 do repositório público) e falhou nos três sistemas; uma rodada de correções (abaixo) corrigiu o Linux e o Mac e deixou o Windows como **experimental e informativo**. O `instaladores.yml` ainda não foi exercitado no GitHub. Este roteiro diz o que o CI mostra hoje e como corrigir se falhar.

## Os dois workflows

| Arquivo | Quando roda | O que faz |
|---|---|---|
| `.github/workflows/build.yml` | todo push na `main` e todo pull request | `./gradlew build` (compila, roda a suíte inteira, confere as dependências) em **Linux, Windows e Mac**, um job por sistema, sem um bloquear o outro. **Linux e Mac são EXIGIDOS** (se quebrarem, o commit fica reprovado). **O Windows é EXPERIMENTAL e INFORMATIVO**: o job tem `continue-on-error` (`${{ matrix.os == 'windows-latest' }}`), roda e mostra o resultado, mas uma falha dele NÃO reprova o commit (o ícone do job fica vermelho, o status do commit não). No Windows, depois do build, roda `./gradlew provaWindows` (informativo). Sobe os PNGs das telas e o relatório de testes como artefato. |
| `.github/workflows/instaladores.yml` | **só** à mão (Actions > instaladores > Run workflow) ou ao criar uma tag `v*`. **Nunca** em push comum. | Um job por sistema: confere os módulos do Java (`jdeps`), gera o instalador (`jlink` + `jpackage`), roda o app da imagem com `--autoteste` e sobe os arquivos como **artefatos da execução**. |

Permissões: os dois usam `contents: read`. A escrita (`contents: write`) existe em UM lugar, o job `publicar` do `instaladores.yml`, que só roda se você marcar "publicar" no Run workflow E aprovar o ambiente `publicacao`. **Crie esse ambiente ANTES** (Settings > Environments > New environment): ponha você como "Required reviewers" e restrinja a "Deployment branches and tags" a `main` e às tags `v*`. **Atenção:** se o ambiente não existir, o GitHub o cria sozinho, SEM revisores, e o job roda sem pedir aprovação a ninguém; a proteção só existe depois de você criá-lo. A Release sai **em rascunho** e presa ao commit exato da execução (`--target "$GITHUB_SHA"`): você olha e publica à mão.

As actions estão fixadas na versão principal (`actions/checkout@v4`, `setup-java@v4`, `upload-artifact@v4`, `download-artifact@v4`). Para travar mais (por hash do commit), troque cada `@v4` pelo SHA que o repositório da action mostra na página da versão.

## O que o `build.yml` mostrou no primeiro run (2026-10-30) e o que foi corrigido

Três jobs: `build (ubuntu-latest)`, `build (windows-latest)` e `build (macos-latest)`. Resultado do primeiro run, com 3904 testes: **Mac 1 falha, Linux 6, Windows 271** (e 211 pulados). A rodada de correções mexeu só em Linux, Mac e na honestidade do Windows:

| Teste que falhou (Mac e Linux) | Era do app ou do teste? | O que foi feito |
|---|---|---|
| `ConexaoPeloTunelTest > seOProgramaDoTunelMorrer...` (Mac e Linux: esperava `TRANSPORTE_LOGIN_AWS`, veio `REDE`) | **App** (corrida, bug real). O teste já era hermético (ferramenta falsa, nada de `aws` nem de `PATH`); a causa não era o `aws` ausente. Quando o programa do túnel morre, o sistema fecha os sockets dele um instante ANTES de o Java dá-lo como morto e de a saída ser lida: a sessão SSH caía, o app olhava a causa na hora, não achava e dizia só "conexão caiu" | `ConexaoSsh` espera, fora do ator, até `ConfigDoTunel.esperaPelaCausa` (1 s; 300 ms nos testes) pela causa do túnel (`DestinoSsh.falhaDoTunelAposPerderASessao`). Reproduzido num container Linux (falhava sempre rodando a classe inteira) e provado: sem a espera, falha; com ela, passa. Dois testes novos em `TunelDeTransporteTest` |
| `AjudaNaJanelaTest > ajudaTemF1NoWindowsELinuxECmdInterrogacaoNoMac` (Linux: `Ctrl+Shift+/` no lugar de `Cmd+Shift+/`) | **App** (pequeno): o `MenuApp` escolhia a tecla de comando pelo sistema ONDE RODA, e não pelo sistema do `ComportamentoDaJanela` que recebe. No app real é o mesmo; num teste que monta o menu "de outro sistema" saía misturado (texto Mac, tecla do Linux) | `MenuApp.atalho()` usa `Teclas.comando(comportamento.sistema())` |
| `GitPeloSshEmbutidoTest > donoDiferentePeloCanalExecDaOComandoSemExecutalo` (veio `COM_GIT`) | **Teste**: o `git` do shell de teste lia o gitconfig do SISTEMA, e uma máquina com `safe.directory=*` lá (o runner, provavelmente) apaga o "dono diferente" que o teste provoca | `GIT_CONFIG_NOSYSTEM=1` no shell dos testes de git por SSH (`GitPeloSshEmbutidoTest`, `GitPeloTunelTest` e `GitPeloServidorLabTest`). Reproduzido no container com `safe.directory=*` no sistema e provado |
| `GitEProcessosPeloServidorLabTest > gitAusenteComSudoSemSenha...` (comandos extras no começo e no fim da lista) | **Teste**: o monitor da conexão manda, em segundo plano e a qualquer hora, a leitura fixa "situação do agente" (`command -v docker`...`docker inspect <container-do-agente>`); numa máquina lenta ela cai no meio do que o teste mede | O teste tira só essa leitura da lista (`comandosDoFluxo()`); todo o resto continua exigido exatamente. O mesmo vale para o teste vizinho "não envia nada" |
| `ControleRemotoComShellTest > processoDeOutroUsuarioDaSemPermissao...` (comando para o dono vazio) | **Teste**: ele escolhia o primeiro processo de root acima do 100, que num Linux é uma thread do kernel (`kworker/R-charg`, com barra no nome); para um nome assim o app RECUSA montar o comando (`ComandoParaODono.NOME`, certo) | O teste escolhe um processo de root DE VERDADE (uid e ruid 0; o `sudo` tem uid efetivo 0 mas é do usuário) e de nome comum; se não houver, pula (já fazia) |
| `RepositoriosGitLembradosTest > lembraPorServidorEProjeto...` (`Failed to delete temp directory`) | **Teste**: as preferências gravam numa thread de fundo e a pasta temporária era apagada no meio de uma gravação | o teste chama `descarregar()` no fim (o app faz o mesmo ao sair) |

**Nenhum teste foi enfraquecido nem pulado nos sistemas onde o cenário existe.** Os que dependem do sistema continuam com `assume` no que já tinham.

Tabela de sintomas (útil para o próximo run):

| Sintoma | Causa provável | Como corrigir |
|---|---|---|
| teste do Windows falha com `UnsupportedOperationException` ou `AccessDeniedException` em arquivo | teste que ainda assume permissão POSIX ou apagar arquivo aberto (o Windows não deixa) | pôr `@DisabledOnOs(OS.WINDOWS)` com o motivo, ou `assumeTrue(posix)`; o relatório está no artefato `telas-e-testes-windows-latest` |
| teste do Windows falha comparando caminho (`C:\...` contra `/...`) | teste que monta `Path.of("/algo")` | usar `@TempDir`/`Path.of(...)` relativo, ou comparar com `toString()` do `Path` que o próprio teste criou |
| teste do Linux/Windows de terminal local falha | `TransporteLocalTest` usa `sh`, `stty` e `echo` do Unix; no Windows o shell é o PowerShell | já marcado para só rodar em Mac e Linux; se um novo falhar, mesma saída |
| `Could not resolve` ou erro de checksum de dependência | `gradle/verification-metadata.xml` sem um artefato que só um sistema baixa | rodar `./gradlew --write-verification-metadata sha256 build` NAQUELE sistema e revisar o diff (conferido: nenhum jar extra por sistema é preciso, os nativos vêm dentro dos mesmos jars) |
| fontes ou tamanho de texto diferentes nos testes do Linux | o runner não tem as mesmas fontes de sistema | os testes usam as fontes embutidas (Inter e JetBrains Mono); se um teste de medida de texto falhar, tolerar a diferença ou usar a fonte embutida |
| `provaWindows` mostra falha | uma suposição do Windows estava errada | é **informativo** (não bloqueia): leia a saída no log do job; ela diz o que o Windows respondeu de verdade |


## O que você vai ver no primeiro run do `instaladores.yml`

1. **Windows:** o passo "WiX Toolset 3" confere se o `candle.exe` existe e, se não, instala o `wixtoolset` pelo Chocolatey. Depois `verificarModulos`, `empacotar` (gera `.msi` e `.zip` portátil), `conferirImagem` (roda o autoteste com o Java do instalador) e, informativo, instala o `.msi` de verdade (por usuário) e roda o autoteste da pasta instalada.
2. **Mac:** `empacotar` gera o `.app` (com `NSLocalNetworkUsageDescription` e assinatura ad-hoc) e o `.dmg`; `conferirImagem`; informativo: monta o `.dmg` e roda o autoteste do app dentro dele.
3. **Linux:** gera o `.tar.gz` portátil (não há instalador de Linux, só o `.tar.gz`).
4. Cada job sobe `build/instaladores/*` como artefato `instalador-<sistema>`: o instalador e o `SHA256SUMS-<sistema>-<arq>.txt`. Baixe na página da execução.

| Sintoma | Causa provável | Como corrigir |
|---|---|---|
| Windows: `Cannot find WiX tool (light.exe, candle.exe)` | o WiX 3 não ficou no PATH | ver o passo do WiX no log; o caminho padrão é `C:\Program Files (x86)\WiX Toolset v3.14\bin`; ajustar o passo. Enquanto isso o `.zip` portátil continua sendo gerado pela tarefa `jpackageImagem`/`empacotar` (rode `./gradlew jpackageImagem` e arquive a pasta) |
| `jdeps` acha um módulo que o runtime não leva (`verificarModulos` falha) | uma biblioteca nova pediu um módulo do Java | acrescentar o módulo em `modulosDoRuntime` no `build.gradle.kts` |
| `conferirImagem` falha com `FALHA ...` | o runtime enxuto não tem algo (módulo ou nativo) | a linha `FALHA nome: motivo` diz qual verificação; módulo faltando = acima; PTY (aviso) = nativo do pty4j |
| Mac: `codesign` ou `hdiutil` falha | runner sem sessão gráfica para o estilo do `.dmg` | repetir o run; se persistir, o `jpackage` pode ser chamado com `--type pkg`, ou o app-image zipado com `ditto -c -k --keepParent` |
| a execução da tag `v*` não aparece | o workflow só dispara em tag que começa com `v` | `git tag v0.1.0 && git push origin v0.1.0` (a tag é só um nome; a versão do app vem da contagem de commits) |

Versão do app: `<base>.<n>` (a base `labdesktop.versaoBase` em `gradle.properties`, `n` = número de commits do git; ARQUITETURA 11.5), por isso os workflows fazem o checkout COMPLETO (`fetch-depth: 0`): num clone raso a versão cai em `<base>.0`. O `.msi` exige três números (`x.y.z`, os dois primeiros até 255 e o terceiro até 65535). O **UUID de atualização** do Windows é fixo (`--win-upgrade-uuid` em `build.gradle.kts`): **nunca o troque**, ou a versão nova não substitui a antiga.

## Windows: o que falta

**Estado honesto (2026-10-30): o Windows é EXPERIMENTAL.** No primeiro run do CI, **271 dos 3904 testes falharam no Windows** (211 pularam). O instalador `.msi` e o app abrem e a parte por **SSH** (servidores, terminal, arquivos e Git do servidor) é a esperada, mas **não está provada** em Windows de verdade. Os recursos que mexem no **próprio computador** (Este computador, importar pasta local, Git local, gravação do compose, arquivos e terminal locais) **ainda não passam nos testes automáticos**: as falhas vêm de suposições de Mac e Linux nos testes e também no código (caminhos `/...` contra `C:\...`, permissões POSIX, `sh`, nomes de arquivo). Por isso o job do Windows roda mas **não reprova o commit** (`continue-on-error`), e não dizemos que o Windows tem prova de ponta a ponta. Isto é um **roteiro de trabalho futuro, sem data**; nada aqui foi corrigido além do item 1.

Agrupamento das 271 falhas por causa provável (lido do log do run 41091992893; aproximado: uma falha cai no primeiro grupo que casa):

| # | Falhas | Causa provável | Exemplo da mensagem | Classes |
|---|---|---|---|---|
| 1 | 19 | **Teste:** o preparo é pulado pela suposição "precisa de `/bin/sh`" e o `@AfterEach` quebra com NPE, então o teste conta como falha em vez de pulado. **Corrigido na rodada de correções** (agora pulam: 252 falhas devem restar) | `NullPointerException: ... "this.conexao" is null` | `TransporteLocalTest` 13, `ChaveRecusadaNaJanelaTest` 6 |
| 2 | 139 | **Teste e app:** caminho do Windows (`D:\a\...\junit-123`) onde se espera caminho POSIX. Os testes forçam a plataforma Mac ou Linux e passam o `@TempDir` do Windows; `ValidadorDaPastaDosProjetos` (regra Unix: "começando por /") e `CaminhoRemoto.normalizar` (a regra de caminho do SFTP: raiz `/`, e o `:` de `C:` vira "caracteres que não posso usar") recusam. O gerenciador de arquivos local e o fluxo de projetos de "Este computador" falam caminho como texto POSIX: no Windows real precisam de uma tradução (ou de caminhos no formato do sistema) | `O caminho precisa começar na raiz do servidor (/)`; `A pasta dos projetos deve ser um caminho completo (começando por /)`; `O caminho tem caracteres que não posso usar` | `ImportadorDeProjetoTest` 31, `LaboratorioLocalTest` 25, `EscritorDeComposeTest` 18, `RepositoriosEmSubpastaComGitDeVerdadeTest` 13, `ClienteSftpLocalTest` 12, `DialogoAdicionarContainerTest` 11, `CriadorDeRepositorioComGitDeVerdadeTest` 10, `CriadorNoAmbienteTest` 8, `CriadorDeRepositorioCasosDificeisTest` 4 e mais 5 classes (1 a 2 cada) |
| 3 | 56 | **Mistas, a investigar uma a uma.** `DialogoPrepararComputadorTest` (17) é provavelmente do grupo 2 (o laboratório local com a pasta de teste do Windows: `expected: <true> but was: <false>`, botão não achado), mas não foi confirmado. Também: `expected: <420> but was: <438>` (tamanho de arquivo com CRLF), `PlataformaLocalTest` e `VerificacoesDoComputadorTest` (o localizador de programas acha `git` no PATH do Mac), `ProcessosLocaisTest` (quebra de linha), `InformacoesDoAppTest` (`~\Library` contra `~/Library`) | `NoSuchElementException: No value present` | `DialogoPrepararComputadorTest` 17, `CriadorDeRepositorioComGitDeVerdadeTest` 6, `RepositoriosEmSubpastaComGitDeVerdadeTest` 4, `LaboratorioLocalTest` 4, `EscritorDeComposeTest` 4 e outras |
| 4 | 25 | **Teste de tela:** tamanho e contraste medidos com as fontes e o LaF do runner Windows (o desenho dos PNGs é calibrado no Mac com o LaF genérico) | `controles da Ajuda abaixo do limite de contraste: ... contraste 1.72 (minimo 3.0)`; `pede 679 px` | `TelaServidorTest` 5, `AjudaNaJanelaTest` 3, `Cores*Test` (10 classes), `AjustesNaJanelaTest` 2, `LinhaDoCodigoTest`, `AgenteNaoInstaladoNaJanelaTest`... |
| 5 | 11 | **App:** `ProcessosLocais` RECUSA no Windows um argumento com quebra de linha (o Windows entregaria quebrado); o leitor de Git local manda `sh -c` de várias linhas | `O argumento 6 do comando local tem caractere de controle: no Windows ele chegaria quebrado ao programa.` | `LeitorDeGitComGitDeVerdadeTest` 11 |
| 6 | 10 | **Teste:** cria arquivo com nome que o NTFS não aceita (`.env ` com espaço no fim, `*`, `<`, `>`) | `InvalidPathException: Trailing char < > at index 4: .env` | `CriadorDeRepositorioCasosDificeisTest` 4, `SegurancaDoGerenciadorTest` 3, `CriadorDeRepositorioComGitDeVerdadeTest`, `PlanejadorDoGitignoreTest`, `RepositoriosEmSubpastaComGitDeVerdadeTest` |
| 7 | 9 | **Teste:** assume permissão POSIX (`rw-------`, `PosixFilePermissions`) | `UnsupportedOperationException`; `expected: <rw-------> but was: <só o dono>` | `ClienteSshFactoryTest` 2, `ConexaoSshTest` 2, `TravaDeInstanciaTest`, `ArquivoPrivadoTest`, `PreferenciasTest`, `ProvisionadorRemotoTest`, `CriadorDeRepositorioCasosDificeisTest` |
| 8 | 2 | **Teste:** o `.gitconfig` de teste leva caminho com `\` e o git lê como escape | `git init -q: fatal: bad config line 2 in file D:\...\.gitconfig` | `CriadorDeRepositorioCasosDificeisTest` 2 |

Ordem sugerida do trabalho futuro: (1) o item 2 é o que mais vale (cerca de metade das falhas e o que decide se "Este computador" funciona no Windows: decidir se o caminho local vira texto no formato do sistema e adaptar `ValidadorDaPastaDosProjetos`, `CaminhoRemoto` e o `ClienteSftpLocal`); (2) os itens 6, 7 e 8 são só de teste (`assumeTrue` no sistema onde o cenário não existe, ou nomes e permissões portáteis); (3) o item 5 pede um caminho de leitura do Git local sem `sh -c` de várias linhas; (4) o item 4 pede limites de tela por sistema ou fontes embutidas; (5) o grupo 3, um a um. Rodar com `gh run view <id> --repo 01010100hiago/lab-desktop-app --log-failed` depois de cada rodada. Quando o job do Windows passar inteiro, tirar o `continue-on-error` do `build.yml` e a palavra "experimental" dos documentos.

## Como gerar o instalador na sua máquina

```sh
./gradlew verificarModulos   # os módulos do jlink cobrem o que o jdeps acha
./gradlew empacotar          # build/instaladores/: .dmg (Mac), .msi + .zip (Windows), .tar.gz (Linux)
./gradlew conferirImagem     # roda o app da imagem com --autoteste (sem janela)
```

No Mac o resultado é `build/instaladores/LabDesktop-<versão>-mac-arm64.dmg`. O `.app` solto fica em `build/empacotamento/imagem/Lab Desktop.app`. Esses diretórios (`build/`) não vão para o git.

## Assinatura de código (ainda não feita)

Hoje os instaladores **não são assinados** (no Mac só há a assinatura "ad-hoc", que não tem identidade e MUDA a cada build: por isso o macOS pode pedir de novo a permissão de Rede Local e o aviso do Gatekeeper a cada versão instalada). Consequência, que o usuário vê:

- **Windows (SmartScreen):** "O Windows protegeu o computador" na primeira execução. **Mais informações > Executar assim mesmo.** A reputação melhora com o tempo e com a assinatura.
- **macOS (Gatekeeper):** "não pôde verificar o app". **Ajustes do Sistema > Privacidade e Segurança > Abrir Mesmo Assim** (ou `xattr -dr com.apple.quarantine "/Applications/Lab Desktop.app"`).

Para remover os avisos (tem custo: contas e certificados pagos):

1. **Apple:** conta no Apple Developer Program (paga por ano); certificado "Developer ID Application"; assinar com `jpackage --mac-sign --mac-signing-key-user-name "<nome do certificado>"` (ou `codesign` na mão) e **notarizar** (`xcrun notarytool submit ... --wait`, depois `xcrun stapler staple`). No CI isso pede segredos (certificado em base64, senha, ID da conta) guardados em Settings > Secrets, nunca no repositório.
2. **Windows:** certificado de assinatura de código (padrão ou EV, de uma autoridade certificadora); assinar o `.exe` do launcher e o `.msi` com `signtool sign /fd SHA256 /tr <servidor de carimbo> /td SHA256 ...`. Também via segredos do CI (o EV costuma exigir token de hardware ou um serviço de assinatura na nuvem).

Nada disso está feito nem configurado: não há segredo nenhum no repositório.
