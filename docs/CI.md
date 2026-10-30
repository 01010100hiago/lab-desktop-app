# CI e instaladores

Este roteiro diz o que esperar do CI e como corrigir se falhar. Os workflows ainda não rodaram no GitHub: o que está abaixo é o que mais provavelmente aparece no primeiro run.

## Os dois workflows

| Arquivo | Quando roda | O que faz |
|---|---|---|
| `.github/workflows/build.yml` | todo push na `main` e todo pull request | `./gradlew build` (compila, roda a suíte inteira, confere as dependências) em **Linux, Windows e Mac**, um job por sistema, sem um bloquear o outro. No Windows, depois do build, roda `./gradlew provaWindows` (informativo). Sobe os PNGs das telas e o relatório de testes como artefato. |
| `.github/workflows/instaladores.yml` | **só** à mão (Actions > instaladores > Run workflow) ou ao criar uma tag `v*`. **Nunca** em push comum. | Um job por sistema: confere os módulos do Java (`jdeps`), gera o instalador (`jlink` + `jpackage`), roda o app da imagem com `--autoteste` e sobe os arquivos como **artefatos da execução**. |

Permissões: os dois usam `contents: read`. A escrita (`contents: write`) existe em UM lugar, o job `publicar` do `instaladores.yml`, que só roda se você marcar "publicar" no Run workflow E aprovar o ambiente `publicacao`. **Crie esse ambiente ANTES** (Settings > Environments > New environment): ponha você como "Required reviewers" e restrinja a "Deployment branches and tags" a `main` e às tags `v*`. **Atenção:** se o ambiente não existir, o GitHub o cria sozinho, SEM revisores, e o job roda sem pedir aprovação a ninguém; a proteção só existe depois de você criá-lo. A Release sai **em rascunho** e presa ao commit exato da execução (`--target "$GITHUB_SHA"`): você olha e publica à mão.

As actions estão fixadas na versão principal (`actions/checkout@v4`, `setup-java@v4`, `upload-artifact@v4`, `download-artifact@v4`). Para travar mais (por hash do commit), troque cada `@v4` pelo SHA que o repositório da action mostra na página da versão.

## O que você vai ver no primeiro run do `build.yml`

Três jobs: `build (ubuntu-latest)`, `build (windows-latest)` e `build (macos-latest)`. O do Mac é o sistema em que a suíte foi desenvolvida (inteira verde, sem avisos de compilação). Os outros dois são **a primeira vez** que a suíte roda em Windows e em Linux; o que mais provavelmente aparece:

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
