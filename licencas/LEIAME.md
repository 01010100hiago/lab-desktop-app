# Textos das licenças de terceiros

Esta pasta guarda o texto completo das licenças das bibliotecas, fontes e ícones que o Lab Desktop leva. Parte dos textos foi copiada, sem alteração, de arquivos que vêm dentro dos próprios `.jar` (o do Apache MINA SSHD para a Apache-2.0, o das fontes Inter e JetBrains Mono para a SIL OFL 1.1, o do SLF4J e o do JSVG para a MIT, o do RSyntaxTextArea para a BSD-3-Clause e o do Bouncy Castle). O resto veio dos endereços oficiais: LGPL-3.0 (site da GNU), EPL-1.0 (site da Eclipse Foundation, convertida de HTML para texto puro sem mudar as palavras), MIT do Feather e ISC do Lucide (os `LICENSE` dos repositórios no GitHub). A tabela de bibliotecas, com versão, licença e endereço oficial, está em `THIRD-PARTY-NOTICES.md`.

| Arquivo | Licença | De quem |
|---|---|---|
| `Apache-2.0.txt` | Apache License 2.0 | Apache MINA SSHD, FlatLaf, Jackson, ZXing, Kotlin, JetBrains Annotations, JCL over SLF4J (e a opção Apache-2.0 do JNA) |
| `OFL-1.1-Inter.txt` | SIL Open Font License 1.1 | fonte Inter |
| `OFL-1.1-JetBrains-Mono.txt` | SIL Open Font License 1.1 | fonte JetBrains Mono |
| `MIT-SLF4J.txt` | MIT | SLF4J (API, Simple) |
| `MIT-JSVG.txt` | MIT | JSVG |
| `BSD-3-Clause-RSyntaxTextArea.txt` | BSD-3-Clause | RSyntaxTextArea |
| `Bouncy-Castle-Licence.md` | Bouncy Castle Licence (tipo MIT) | Bouncy Castle |
| `LGPL-3.0-JediTerm.txt` | GNU LGPL 3.0 | JediTerm (`jediterm-core` e `jediterm-ui` 3.76) |
| `EPL-1.0-pty4j.txt` | Eclipse Public License 1.0 | pty4j 0.13.13 |
| `MIT-Feather.txt` | MIT | Feather (ícones; formas próximas das de alguns ícones do app) |
| `ISC-Lucide.txt` | ISC (e MIT para os ícones herdados do Feather) | Lucide (ícones; formas próximas das de alguns ícones do app) |

A LGPL-3.0 incorpora os termos da GPL-3.0; o texto da GPL-3.0 está em https://www.gnu.org/licenses/gpl-3.0.txt.

### Código-fonte do JediTerm (LGPL-3.0)

O Lab Desktop usa o JediTerm como biblioteca, sem alteração, num arquivo `.jar` separado dentro da pasta do app (não está misturado ao código do Lab Desktop). O código-fonte da versão usada, a 3.76, é público em https://github.com/JetBrains/jediterm; quem quiser pode trocar o `.jar` por outra versão compatível. A mesma oferta vale para o pty4j (EPL-1.0): código-fonte em https://github.com/JetBrains/pty4j.
