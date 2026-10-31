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
| teste do Windows falha com `O caminho precisa começar na raiz do servidor (/)`, `O caminho tem caracteres que não posso usar` ou `A pasta dos projetos deve ser um caminho completo` | o teste entregou `Path.toString()` (`D:\...`) onde o app espera o caminho do app (`/D:/...`), ou forçou o Mac com uma pasta do Windows | usar `SistemaDoTeste` (`app(...)`, `doSistema(...)`, `plataforma(...)`; ARQUITETURA 11.7) |
| teste do Linux/Windows de terminal local falha | `TransporteLocalTest` usa `sh`, `stty` e `echo` do Unix; no Windows o shell é o PowerShell | já marcado para só rodar em Mac e Linux; se um novo falhar, mesma saída |
| `Could not resolve` ou erro de checksum de dependência | `gradle/verification-metadata.xml` sem um artefato que só um sistema baixa | rodar `./gradlew --write-verification-metadata sha256 build` NAQUELE sistema e revisar o diff (conferido: nenhum jar extra por sistema é preciso, os nativos vêm dentro dos mesmos jars) |
| fontes ou tamanho de texto diferentes nos testes do Linux | o runner não tem as mesmas fontes de sistema | os testes usam as fontes embutidas (Inter e JetBrains Mono); se um teste de medida de texto falhar, tolerar a diferença ou usar a fonte embutida |
| `provaWindows` mostra falha | uma suposição do Windows estava errada | é **informativo** (não bloqueia): leia a saída no log do job; ela diz o que o Windows respondeu de verdade |


## Corridas de tempo que o CI viu depois (Linux e Mac)

Com os caminhos do Windows corrigidos, o CI ainda mostrou, uma vez cada, quebras de tempo no Linux e no Mac. Cada uma foi reproduzida ou explicada pelo código, e a correção tira o tempo da conta (espera por uma condição que dá para observar, nunca por um número de segundos):

| Teste | Causa | Correção |
|---|---|---|
| `VerificadorDeChaveDoServidorTest > oVereditoFicaNaSessaoQuePerguntou...` (Linux, "a sessão não recebeu nenhum veredito") | **App (corrida real).** O MINA APAGA os atributos de uma sessão quando ela fecha, e recusar a chave do host fecha a sessão na hora. Quem lia o veredito do atributo depois da recusa podia ver nada: a tela diria só "não consegui conectar" em vez de "a chave do servidor mudou". Reproduzido: 5 falhas em 40 execuções com a máquina carregada | o verificador guarda o veredito também numa tabela fraca por sessão (`vereditoDa`) e a conexão lê por ela; teste novo que lê o veredito depois de fechar a sessão. 0 falhas em 90 execuções com a mesma carga |
| `ConexaoPeloTunelTest > seOProgramaDoTunelMorrer...` (Mac, esperava `TRANSPORTE_LOGIN_AWS`, veio `REDE`) | **Teste e app.** O programa falso do túnel morria 1,5 s depois de abrir a porta (tempo fixo): num runner lento a conexão ainda nem estava pronta. E, no app, o socket do túnel que morre fecha ANTES de o programa ser dado como morto: a espera pela causa existia só para a sessão já de pé, e não para a que ainda estava conectando | o programa falso só morre quando o teste manda (arquivo-gatilho); o teste lê o retrato do RECONECTANDO no histórico e dá folga à espera pela causa (a espera acaba assim que o programa sai, folga não custa tempo); a conexão que ainda está conectando também espera pela causa, só quando a falha é de IO e nunca em recusa de chave ou de autenticação. Teste novo, sem tempo: o destino só sabe a causa depois da falha da conexão |
| `PrimeiroAcessoPeloTunelTest > editarOModoMantemAConfianca...` (Mac, esperava `/ferramentas/aws`, veio `/ferramentas/cloudflared`) | **Teste.** Depois de trocar o modo, o estado "conectado" da conexão ANTIGA ainda valia por um instante: a espera passava e a leitura pegava o último túnel, ainda o do cloudflared | espera primeiro pelo observável (um túnel novo lançado) e só então pelo estado |
| `BaixadorDeBackupsTest > cancelarNoMeioNaoDeixaArquivoNemParcial` (Mac e Linux, esperava `CANCELADA`, veio `CONCLUIDA`) | **Teste.** Os 30 MB locais terminavam de baixar enquanto o teste fotografava a tela, antes do clique em Cancelar | o servidor SFTP de teste segura as leituras depois de 1 MB até o teste liberar (`segurarLeiturasAPartirDe`); o clique em Cancelar acontece com a transferência parada no meio |

Nenhum teste foi enfraquecido nem pulado. Cada correção foi provada capaz de falhar: a quebra feita à mão (desfeita em seguida) derruba o teste.

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

**Estado honesto (2026-10-30, depois da correção dos caminhos): o Windows continua EXPERIMENTAL, mas a maior causa saiu.** No primeiro run do CI, **271 dos 3904 testes falharam no Windows** (211 pularam). Depois da correção dos caminhos (ARQUITETURA 11.7), o mesmo CI mediu **68 falhas** na primeira rodada e **64** na segunda, de 3973 testes (238 pulados): o grupo 2 da tabela de antes (139 falhas de caminho `D:\...` contra `/...`) **zerou**. O que o CI do Windows passou a provar: o laboratório local (`LaboratorioLocalTest`), o servidor local e as janelas de "Este computador" (`ServidorLocalTest`, `EsteComputadorNaJanelaTest`, `NovoProjetoLocalNaJanelaTest`), importar projeto (clone e pasta, `ImportadorDeProjetoTest`), a gravação do compose (`EscritorDeComposeTest`), criar projeto (`CriadorNoAmbienteTest`, `DialogoAdicionarContainerTest`), criar repositório Git e achar repositórios em subpasta (`CriadorDeRepositorioComGitDeVerdadeTest`, `RepositoriosEmSubpastaComGitDeVerdadeTest`, com o git de verdade) e o gerenciador de arquivos local e a cerca dele (`ClienteSftpLocalTest`, com um link de verdade do NTFS). **O que isso NÃO prova:** o Docker de verdade no Windows (`docker.exe`, Docker Desktop e WSL 2: os testes usam o computador simulado e os de Docker de verdade pulam), o terminal local (ConPTY, os testes de `TransporteLocalTest` pulam) e as permissões do cofre por ACL do NTFS (os testes de POSIX pulam ou falham, ver abaixo). Por isso o job do Windows continua só informativo (`continue-on-error`), o `.msi` continua experimental e não se diz que o Windows tem prova de ponta a ponta. Isto é um **roteiro de trabalho futuro, sem data**.

Nos caminhos houve UM defeito real do app e uma inconsistência (gerenciador de arquivos local; ARQUITETURA 11.7): um nome com barra invertida (`a\..\..\fora` num "Renomear") pulava a cerca no Windows e levava o item para fora da pasta do projeto (agora recusado); e o alvo de um link vinha em texto do sistema (`..\fora`), em vez do caminho do app (agora traduzido; a cerca já recusava todo link do computador local). O resto, cerca de 135 falhas, era dos TESTES: o computador local de mentira (`AmbienteFalso`, `GitDeTeste`) e os testes de "Este computador" misturavam o caminho do app (`/D:/a/x`) com o texto do sistema (`D:\a\x`) e forçavam o Mac com uma pasta do Windows.

As 64 que restam (última rodada), agrupadas por causa (lidas do log; cada falha cai no primeiro grupo que casa):

| # | Falhas | Causa | Exemplo da mensagem | Classes |
|---|---|---|---|---|
| 1 | 25 | **Teste de tela:** tamanho e contraste medidos com as fontes e o LaF do runner Windows (o desenho dos PNGs é calibrado no Mac com o LaF genérico) | `controles da Ajuda abaixo do limite de contraste: ... contraste 1.72 (minimo 3.0)`; `pede 679 px` | `TelaServidorTest` 5, `AjudaNaJanelaTest` 3, `CoresDosDialogosCompletoTest` 2, `AjustesNaJanelaTest` 2 e 13 classes de 1 (`Cores*Test`, `PaginaConsumoTest`, `LinhaDoCodigoTest`, `DialogoDoAgenteEscolhaTest`, `ComoConectarNosDialogosTest`, `AgenteNaoInstaladoNaJanelaTest`, `ArquivosNaJanelaTest`) |
| 2 | 11 | **Teste (não era do app) - CORRIGIDO, a confirmar no CI:** o auxiliar `GitDeTeste.commitar` mandava a mensagem de várias linhas em `-m`, e o `ProcessosLocais` RECUSA no Windows um argumento com quebra de linha (certo: o Windows o entregaria quebrado). Agora a mensagem vai pela entrada (`-F -`), e o teste dos separadores antigos manda o nome do autor pelo ambiente (`GIT_AUTHOR_NAME`) | `O argumento 6 do comando local tem caractere de controle: no Windows ele chegaria quebrado ao programa.` | `LeitorDeGitComGitDeVerdadeTest` 11 |
| 3 | 9 | **Teste:** cria arquivo com nome que o NTFS não aceita (`.env ` com espaço no fim, `*`, `<`, quebra de linha) | `InvalidPathException: Trailing char < > at index 4: .env` | `CriadorDeRepositorioCasosDificeisTest` 4, `SegurancaDoGerenciadorTest` 3, `CriadorDeRepositorioComGitDeVerdadeTest` 1, `PlanejadorDoGitignoreTest` 1 |
| 4 | 10 | **Teste:** assume permissão POSIX (`PosixFilePermissions`, `rw-------`) ou um servidor Unix com `/tmp` e `mkdir -m` | `UnsupportedOperationException`; `expected: <rw-------> but was: <só o proprietário>`; `mkdir: cannot change permissions of '/tmp/...'` | `ClienteSshFactoryTest` 2, `ConexaoSshTest` 2, `ProvisionadorRemotoTest` 1, `TravaDeInstanciaTest` 1, `ArquivoPrivadoTest` 1, `PreferenciasTest` 1, `CriadorDeRepositorioCasosDificeisTest` 2 |
| 5 | 4 | **App (defeito real) - CORRIGIDO, a confirmar no CI:** a recusa de conexão era reconhecida pelo TEXTO em inglês da exceção. No Windows o Java entrega um `IOException` comum com a frase do próprio Windows (em qualquer idioma). Agora `CausaDeRede` classifica pelo TIPO (`ConnectException`, `NoRouteToHostException`, `SocketTimeoutException`, `UnknownHostException`, `TimeoutException`) e pelas causas encadeadas; e, ainda na fase de conectar (sem sessão), uma `IOException` comum sem outra causa conhecida vale "recusou" (depois de a sessão existir, não). Limite honesto: no Windows não há tipo para olhar, então um `IOException` comum de outra origem, na fase de conectar, também diria "recusou" | `Não consegui obter a chave do servidor em 127.0.0.1:55292. Confira o endereço ...` onde o teste esperava a frase de "recusou"; `Não consegui conectar: The remote computer refused the network connection.` | `ConexaoSshTest` 1, `SessaoComSenhaTest` 1, `SondagemDeHostTest` 1, `DialogoAdicionarServidorTest` 1 |
| 6 | 5 | **Mistas:** tamanho de arquivo com CRLF (`expected: <420> but was: <438>`); `ProcessosLocaisTest` (usa `printf` e `sh` do Unix); o alias que "não é executável" (`setExecutable` não existe no Windows); `TravaDeInstanciaTest > travaSoltaQuandoOProcessoMorreSemFechar` (a trava de um processo que morreu: o Windows ainda a segura) | | `EditorRemotoTest` 1, `ProcessosLocaisTest` 2, `VerificacoesDoComputadorTest` 1, `TravaDeInstanciaTest` 1 |

Os grupos 2 e 5 (15 das 64 falhas) foram tratados e esperam a próxima rodada do CI do Windows para serem confirmados (esta correção não foi medida num Windows). Ordem sugerida do trabalho futuro: (1) os grupos 3 e 4 são só de teste (`assumeTrue` no sistema onde o cenário não existe, ou nomes e permissões portáteis); (2) o grupo 1 pede limites de tela por sistema ou fontes embutidas; (3) o grupo 6, um a um. Rodar com `gh run view <id> --repo 01010100hiago/lab-desktop-app --log-failed` depois de cada rodada. Quando o job do Windows passar inteiro, tirar o `continue-on-error` do `build.yml` e a palavra "experimental" dos documentos. **Ficaram de fora, de propósito:** os grupos 1, 3, 4 e 6 (outras causas); o Docker de verdade e o terminal local no Windows (só um Windows de verdade responde).

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
