# Licenças de terceiros

O Lab Desktop foi criado por Thiago Bezerra e é software livre sob a licença MIT (arquivo `LICENSE`). Ele leva as bibliotecas abaixo, cada uma com a sua licença. Esta tabela é a fonte da
seção "Licenças de terceiros" da tela Ajustes > Sobre (o app a lê do jar) e um teste confere que, para cada biblioteca do `gradle/verification-metadata.xml`, existe uma linha com o nome e a versão certos (a licença de cada linha vem da conferência dos POMs, abaixo).

Como foi conferida: a licença de cada linha vem do campo `<licenses>` do POM da própria biblioteca (quando o POM não traz, do POM pai: `org.apache:apache:37` para o
Apache MINA SSHD, `jackson-base` para o `jackson-datatype-jsr310`, `slf4j-bom` para o SLF4J e `zxing-parent` para o ZXing `core` e `javase`). As notas técnicas de cada caso ficam na seção "Notas técnicas" mais abaixo.

A tabela é o que o app MOSTRA ao usuário (colunas que quebram linha, sem texto de desenvolvedor); as notas técnicas ficam só neste arquivo.

| Biblioteca | Versão | Licença | Para que serve no app |
|---|---|---|---|
| FlatLaf | 3.7.2 | Apache-2.0 | tema claro e escuro e aparência dos componentes |
| FlatLaf Extras | 3.7.2 | Apache-2.0 | ícones SVG |
| FlatLaf Fonts Inter | 4.1 | Apache-2.0 (o pacote) e SIL OFL 1.1 (a fonte Inter) | fonte do texto |
| FlatLaf Fonts JetBrains Mono | 2.304 | Apache-2.0 (o pacote) e SIL OFL 1.1 (a fonte JetBrains Mono) | fonte do terminal e dos números |
| JSVG | 2.1.0 | MIT | desenha os ícones SVG |
| Apache MINA SSHD (sshd-core) | 2.20.0 | Apache-2.0 | conexão SSH e túnel |
| Apache MINA SSHD (sshd-common) | 2.20.0 | Apache-2.0 | parte comum do SSH |
| Apache MINA SSHD (sshd-sftp) | 2.20.0 | Apache-2.0 | gerenciador de arquivos (SFTP) |
| Bouncy Castle (bcprov-jdk18on) | 1.86 | Bouncy Castle Licence (tipo MIT) | cofre (Argon2id) e chaves Ed25519 |
| Bouncy Castle (bcpkix-jdk18on) | 1.86 | Bouncy Castle Licence (tipo MIT) | leitura de chaves |
| Bouncy Castle (bcutil-jdk18on) | 1.86 | Bouncy Castle Licence (tipo MIT) | apoio do bcpkix |
| JediTerm (jediterm-core) | 3.76 | LGPL-3.0 | terminal: emulador |
| JediTerm (jediterm-ui) | 3.76 | LGPL-3.0 | terminal: tela |
| pty4j | 0.13.13 | EPL-1.0 | terminal do computador local |
| JNA | 5.14.0 | LGPL-2.1-ou-posterior ou Apache-2.0 (a escolha é de quem usa) | acesso ao sistema (usado pelo pty4j) |
| JNA Platform | 5.14.0 | LGPL-2.1-ou-posterior ou Apache-2.0 (a escolha é de quem usa) | acesso ao sistema (usado pelo pty4j) |
| RSyntaxTextArea | 4.0.1 | BSD-3-Clause | editor de texto com realce de sintaxe |
| ZXing core | 3.5.4 | Apache-2.0 | QR do 2FA |
| ZXing javase | 3.5.4 | Apache-2.0 | desenha o QR |
| Jackson Core | 2.22.3 | Apache-2.0 | leitura e escrita de JSON |
| Jackson Databind | 2.22.3 | Apache-2.0 | leitura e escrita de JSON |
| Jackson Annotations | 2.22 | Apache-2.0 | leitura e escrita de JSON |
| Jackson Datatype JSR310 | 2.22.3 | Apache-2.0 | datas no JSON |
| SLF4J API | 2.0.17 | MIT | registro (log) |
| SLF4J Simple | 2.0.17 | MIT | registro (log) em arquivo |
| JCL over SLF4J | 1.7.36 | Apache-2.0 | registro (log) das bibliotecas |
| Kotlin Stdlib | 2.4.0 | Apache-2.0 | usada pelo JediTerm |
| JetBrains Annotations | 24.0.1 | Apache-2.0 | usada pelo JediTerm e pelo Kotlin |
| FastDoubleParser (dentro do Jackson Core) | o do Jackson Core 2.22.3 | MIT | leitura rápida de números no JSON |
| fast_float, de Daniel Lemire (dentro do FastDoubleParser) | o do Jackson Core 2.22.3 | Boost Software License 1.0 | leitura rápida de números no JSON |
| Schubfach, de Raffaello Giulietti (dentro do Jackson Core) | o do Jackson Core 2.22.3 | MIT | escrita rápida de números no JSON |
| winpty, de Ryan Prichard (dentro do pty4j, para o Windows) | o do pty4j 0.13.13 | MIT | terminal do computador local no Windows |
| OpenConsole e conpty, do Windows Terminal da Microsoft (dentro do pty4j, para o Windows) | o do pty4j 0.13.13 | MIT | terminal do computador local no Windows |
| elt e JPty (o código em que o pty4j se baseia) | o do pty4j 0.13.13 | EPL-1.0 (elt) e Apache-2.0 (JPty) | origem do código do pty4j |
| Lista de senhas comuns (derivada do zxcvbn, Dropbox) | 1.9.0 | MIT | recusa senha de dicionário na senha-mestra; vem das listas do zxcvbn, mais um complemento em português escrito pelo autor |
| OpenJDK (o Java que vai dentro do app) | o do pacote | GPL-2.0 com a exceção Classpath | roda o app |
| Ícones da interface (Feather e Lucide) | o do app | MIT (Feather) e ISC (Lucide) | ícones desenhados pelo autor; parte das formas é muito próxima das desses dois conjuntos |
| Agente de monitoramento (lab-desktop-agente) | 0.1.0-dadf957 | MIT | o programa que o app envia ao servidor para ler CPU, memória, disco, rede e Docker; do mesmo autor, sem dependências de terceiros |

## Endereço oficial de cada componente

Os endereços são os dos projetos de origem (código ou página oficial); as versões são as do `build.gradle.kts` e do `./gradlew dependencies` (classpath de execução), e as licenças são as da tabela acima.

| Componente | Endereço oficial |
|---|---|
| FlatLaf, FlatLaf Extras e os pacotes de fontes do FlatLaf | https://www.formdev.com/flatlaf/ e https://github.com/JFormDesigner/FlatLaf |
| Fonte Inter | https://rsms.me/inter/ |
| Fonte JetBrains Mono | https://www.jetbrains.com/lp/mono/ |
| JSVG | https://github.com/weisj/jsvg |
| Apache MINA SSHD | https://github.com/apache/mina-sshd |
| Bouncy Castle | https://www.bouncycastle.org/ |
| JediTerm | https://github.com/JetBrains/jediterm |
| pty4j | https://github.com/JetBrains/pty4j |
| elt e JPty (base do pty4j) | https://github.com/jawi/JPty (JPty); o elt era o Local Terminal Plug-In for Eclipse |
| winpty | https://github.com/rprichard/winpty |
| OpenConsole e conpty (Windows Terminal) | https://github.com/microsoft/terminal |
| FastDoubleParser | https://github.com/wrandelshofer/FastDoubleParser |
| fast_float | https://github.com/fastfloat/fast_float |
| Schubfach | o artigo e o código de Raffaello Giulietti, citados no jackson-core (`licencas/Jackson-core-embutidos.txt`) |
| JNA e JNA Platform | https://github.com/java-native-access/jna |
| RSyntaxTextArea | https://github.com/bobbylight/RSyntaxTextArea |
| ZXing | https://github.com/zxing/zxing |
| Jackson | https://github.com/FasterXML/jackson |
| SLF4J e JCL over SLF4J | https://www.slf4j.org/ |
| Kotlin | https://kotlinlang.org/ |
| JetBrains Annotations | https://github.com/JetBrains/java-annotations |
| Lista de senhas comuns (zxcvbn) | https://github.com/dropbox/zxcvbn (as listas vieram do jar `com.nulab-inc:zxcvbn:1.9.0`, do Maven Central) |
| Ícones (Feather e Lucide) | https://github.com/feathericons/feather e https://github.com/lucide-icons/lucide |
| OpenJDK (o Java do instalador) | https://openjdk.org/ |

## Ferramentas de construção e de teste (não vão dentro do app)

| Ferramenta | Versão | Licença | Para que serve | Endereço oficial |
|---|---|---|---|---|
| Gradle (wrapper) | 9.8.0 | Apache-2.0 | compila, testa e empacota | https://gradle.org/ |
| JUnit Jupiter e JUnit Platform | 5.14.4 e 1.14.4 | EPL-2.0 | testes automáticos | https://junit.org/ |
| opentest4j | 1.3.0 | Apache-2.0 | usada pelo JUnit | https://github.com/ota4j-team/opentest4j |
| apiguardian-api | 1.1.2 | Apache-2.0 | usada pelo JUnit | https://github.com/apiguardian-team/apiguardian |
| WiX Toolset | 3.x | MS-RL | gera o instalador `.msi` no Windows (`jpackage`) | https://wixtoolset.org/ |
| Temurin (OpenJDK 21) | 21 | GPL-2.0 com a exceção Classpath | Java usado no CI e para compilar | https://adoptium.net/ |

Critério da conferência: entram na primeira tabela tudo o que vai DENTRO do app e do instalador (os jars de `runtimeClasspath`, o Java embutido, as fontes, os ícones, a lista de senhas e o código de terceiros que vem dentro de um desses jars). Esta segunda tabela lista o que só é usado para construir e testar; não vai no app e por isso não leva o texto completo da licença.

## Notas técnicas (só neste arquivo; o app não as mostra)

- Lista de senhas comuns: derivada das listas de dados do zxcvbn (Dropbox) do jar `com.nulab-inc:zxcvbn:1.9.0` (sha256 `38efaebab09144eb1f4d4c9ff650e79df875a8d6c4539c105b079a606bb7db34`; o jar não traz arquivo de licença, e o POM dele declara MIT), mais um complemento em português escrito pelo autor (`scripts/senhas-ptbr.txt`). O texto da licença MIT do zxcvbn, com o copyright de Dan Wheeler e da Dropbox, é o do repositório oficial dropbox/zxcvbn e está em `licencas/MIT-zxcvbn-Dropbox.txt`. Do jar só entram as listas de dados, não o código Java (da Nulab).
- OpenJDK: o runtime que o `jpackage` leva é o do JDK usado na compilação e vem com a pasta `legal/` de cada módulo (os textos da GPL-2.0, da exceção Classpath e das licenças de componentes como ICU, ASM e CLDR); a licença é GPL-2.0 com a exceção Classpath.
- Ícones SVG: ver o último parágrafo deste arquivo.
- O agente de monitoramento que o app leva dentro do jar (`agente/agente-*.tar.gz`, enviado ao servidor pelo instalador): licença MIT, Copyright (c) 2026 Thiago Bezerra. Código-fonte: repositório `lab-desktop-agente` do mesmo autor, a ser publicado. O pacote só tem os arquivos do campo `files` do `package.json` dele e não carrega o arquivo de licença; o aviso e o texto da MIT estão registrados aqui e em `licencas/MIT-lab-desktop-agente.txt`. Ele não tem dependências de terceiros (Node puro).

Os textos das licenças, de forma honesta:

- **Estão neste repositório**, na pasta `licencas/` (e, por isso, também dentro do jar do app, que a tela Ajustes > Sobre mostra em "Ver os textos completos das licenças"): Apache-2.0 e os avisos NOTICE das bibliotecas Apache (`NOTICE-Apache.txt`: Apache MINA SSHD e Jackson), as licenças do código que o Jackson Core leva dentro (`Jackson-core-embutidos.txt`: FastDoubleParser, fast_float e Schubfach), SIL OFL 1.1 (Inter e JetBrains Mono), MIT (SLF4J, JSVG, Feather, zxcvbn, winpty, Windows Terminal e o agente de monitoramento do próprio app), BSD-3-Clause (RSyntaxTextArea), a licença do Bouncy Castle, LGPL-3.0 e GPL-3.0 (JediTerm), EPL-1.0 e o aviso do pty4j (`NOTICE-pty4j.txt`) e ISC (Lucide). Os textos de Apache-2.0, OFL, SLF4J, JSVG, RSyntaxTextArea, Bouncy Castle, NOTICE e Jackson embutido foram copiados dos próprios `.jar`, sem alteração; os de LGPL-3.0, GPL-3.0, EPL-1.0 (convertida de HTML para texto puro, sem mudar as palavras), Feather, Lucide, zxcvbn, winpty, Windows Terminal e o aviso do pty4j vêm dos endereços oficiais. Veja `licencas/LEIAME.md`.
- **Vêm também dentro do `.jar`** (arquivos `META-INF/LICENSE*`): FlatLaf, FlatLaf Extras, os pacotes de fontes do FlatLaf, JSVG, Apache MINA SSHD, Bouncy Castle, JNA, JNA Platform, RSyntaxTextArea, Jackson e SLF4J (API e Simple).
- **NÃO vêm dentro do `.jar`**: JediTerm (LGPL-3.0), pty4j (EPL-1.0), ZXing (`core` e `javase`), Kotlin Stdlib, JetBrains Annotations e JCL over SLF4J (as quatro últimas são Apache-2.0, cujo texto está em `licencas/Apache-2.0.txt`).
- **JNA** é licenciada em duas opções, LGPL-2.1 ou Apache-2.0; o app usa a opção Apache-2.0.
- **Dentro do pty4j** vão programas e bibliotecas nativas (winpty, OpenConsole e conpty para o Windows, e a `libpty` para Mac, Linux e FreeBSD); as de terceiros estão na primeira tabela e em `licencas/`.
- **Oferta do código-fonte (LGPL-3.0, JediTerm):** o JediTerm é usado sem alteração, como um `.jar` separado dentro da pasta do app, e o código-fonte da versão usada (3.76) é público em https://github.com/JetBrains/jediterm; quem quiser pode trocar o `.jar` por outra versão compatível.

As fontes Inter e JetBrains Mono seguem a SIL Open Font License 1.1 (permite usar e redistribuir, inclusive embutidas em programa, sem vender a fonte sozinha).

Os ícones SVG (`src/main/resources/.../ui/icones`) foram desenhados pelo autor no estilo de traço fino (viewBox 24, traço 2, sem preenchimento). Parte das formas é muito próxima das do Feather (MIT) e do Lucide (ISC); as licenças dos dois acompanham este repositório em `licencas/MIT-Feather.txt` e `licencas/ISC-Lucide.txt`. Não se afirma mais do que isso sobre a origem de cada forma.
