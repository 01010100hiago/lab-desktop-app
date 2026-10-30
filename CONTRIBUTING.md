# Como contribuir

Obrigado por querer ajudar. O essencial:

1. **Ambiente**: JDK 21 completo (`JAVA_HOME` apontando para ele) e o `./gradlew` do repositório. Veja o README, seção "Desenvolvimento".
2. **Antes de abrir um pull request**: `./gradlew build` verde (compila, roda a suíte inteira e confere as dependências). Os testes de interface desenham as telas sem abrir janela e gravam PNGs em `build/telas/`.
3. **Convenções** (`docs/ARQUITETURA.md`): identificadores em português sem acento, interface em pt-BR, sem emoji (use os ícones SVG), paleta neutra (sem azul nem ciano), nada de rede, disco pesado ou `sleep` na thread da interface, todo texto vindo do servidor tratado como não confiável (`TextoSeguro`).
4. **Tela ou botão novo** atualiza, no mesmo commit, o tópico correspondente da Ajuda (`src/main/resources/br/com/labdesktop/ajuda/`).
5. **Segredos**: nunca em log, argumento, variável de ambiente nem arquivo temporário; chaves e tokens só no cofre. Não envie fixtures com dados reais de servidores (`src/test/resources/lab-real/` fica fora do git).
6. **Dependência nova**: `./gradlew --write-verification-metadata sha256 build` e revise o diff de `gradle/verification-metadata.xml`.
7. Mensagens de commit em português, curtas, no imperativo ou no passado, dizendo o que mudou.

Ao participar, siga o `CODE_OF_CONDUCT.md`. Ao contribuir você concorda que o seu código segue a licença MIT do projeto (`LICENSE`).
