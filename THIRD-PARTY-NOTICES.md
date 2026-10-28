# Licenças de terceiros

O Lab Desktop foi criado por Thiago Bezerra e é software livre sob a licença MIT (arquivo `LICENSE`). Ele leva as bibliotecas abaixo, cada uma com a sua licença. Esta tabela é a fonte da
seção "Licenças de terceiros" da tela Ajustes > Sobre (o app a lê do jar) e um teste confere que, para cada biblioteca do `gradle/verification-metadata.xml`, existe uma linha com o nome e a versão certos (a licença de cada linha vem da conferência dos POMs, abaixo).

Como foi conferida: a licença de cada linha vem do campo `<licenses>` do POM da própria biblioteca (quando o POM não traz, do POM pai: `org.apache:apache:37` para o
Apache MINA SSHD, `jackson-base` para o `jackson-datatype-jsr310`, `slf4j-bom` para o SLF4J e `zxing-parent` para o ZXing `core` e `javase`). As notas veio de memória; o que não deu
para conferir nesta máquina diz "verificar".

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
| Lista de senhas comuns (dados) | zxcvbn do Dropbox, jar 1.9.0 | MIT (declarada no cabeçalho de `senhas-comuns.txt`; o jar de origem não está nesta máquina: verificar) | recusa senha de dicionário na senha-mestra; vem das listas do zxcvbn, mais um complemento em português escrito pelo autor |
| OpenJDK (o Java que vai dentro do app empacotado) | o do `jpackage` (verificar o arquivo `release` do runtime) | GPL-2.0 com a exceção Classpath (verificar na distribuição usada) | roda o app |

Os textos das licenças, de forma honesta:

- **Estão neste repositório**, na pasta `licencas/`: Apache-2.0, SIL OFL 1.1 (Inter e JetBrains Mono), MIT (SLF4J, JSVG e Feather), BSD-3-Clause (RSyntaxTextArea), a licença do Bouncy Castle, LGPL-3.0 (JediTerm), EPL-1.0 (pty4j) e ISC (Lucide). Os cinco primeiros grupos foram copiados dos próprios `.jar`, sem alteração; os de LGPL-3.0, EPL-1.0, MIT do Feather e ISC do Lucide vêm dos endereços oficiais (a EPL-1.0 foi convertida de HTML para texto puro, sem mudar as palavras). Veja `licencas/LEIAME.md`.
- **Vêm também dentro do `.jar`** (arquivos `META-INF/LICENSE*`): FlatLaf, FlatLaf Extras, os pacotes de fontes do FlatLaf, JSVG, Apache MINA SSHD, Bouncy Castle, JNA, JNA Platform, RSyntaxTextArea, Jackson e SLF4J (API e Simple).
- **NÃO vêm dentro do `.jar`**: JediTerm (LGPL-3.0), pty4j (EPL-1.0), ZXing (`core` e `javase`), Kotlin Stdlib, JetBrains Annotations e JCL over SLF4J (as quatro últimas são Apache-2.0, cujo texto está em `licencas/Apache-2.0.txt`).
- **Oferta do código-fonte (LGPL-3.0, JediTerm):** o JediTerm é usado sem alteração, como um `.jar` separado dentro da pasta do app, e o código-fonte da versão usada (3.76) é público em https://github.com/JetBrains/jediterm; quem quiser pode trocar o `.jar` por outra versão compatível.

As fontes Inter e JetBrains Mono seguem a SIL Open Font License 1.1 (permite usar e redistribuir, inclusive embutidas em programa, sem vender a fonte sozinha).

Os ícones SVG (`src/main/resources/.../ui/icones`) foram desenhados pelo autor no estilo de traço fino (viewBox 24, traço 2, sem preenchimento). Parte das formas é muito próxima das do Feather (MIT) e do Lucide (ISC); as licenças dos dois acompanham este repositório em `licencas/MIT-Feather.txt` e `licencas/ISC-Lucide.txt`. Não se afirma mais do que isso sobre a origem de cada forma.
