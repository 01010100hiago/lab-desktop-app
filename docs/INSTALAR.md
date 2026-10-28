# Instalar o Lab Desktop

Guia para quem NÃO é técnico. O instalador leva o Java junto: você não instala Java, nem Gradle, nem nada para o app em si. Só o que o app USA (o Docker, no caso de "Este computador") é separado, e o próprio app confere e explica.

O app nunca instala nada sozinho: cada instalação mostra o que vai fazer e só acontece depois do seu clique.

O app público não traz o pacote do agente de monitoramento. Por isso, "Instalar agente…" e "Criar meu laboratório local" avisam que esta versão foi compilada sem o agente, em vez de instalar. O terminal, os arquivos e a segurança continuam funcionando; sem o agente você não vê CPU, memória, containers nem projetos.

## O que você precisa antes (e o que não precisa)

| Quero usar... | Preciso de Docker neste computador? |
|---|---|
| **Outro servidor (SSH)**: um servidor seu na rede ou na internet | **Não.** Só o endereço, o usuário e a chave (o app gera a chave). |
| **Este computador**: criar e rodar projetos aqui mesmo | **Sim**: Docker Desktop (Windows e Mac). O app confere, mostra o que falta e abre a página oficial; no Windows ele também pode rodar o `winget` por você, com a sua confirmação. |

---

## Instalar no Windows

Funciona no Windows 10 (versão 22H2 ou mais nova) e no Windows 11, 64 bits. Em computadores com processador ARM o programa roda pela emulação do Windows (mais lento, mas funciona).

1. **Baixe** o arquivo `LabDesktop-<versão>-windows-x64.msi` (ou, se preferir não instalar, o `...-portatil.zip`: descompacte numa pasta sua e abra `Lab Desktop.exe`).
2. **Dê dois cliques** no `.msi`. Ele instala só para o seu usuário, **sem pedir senha de administrador**, e põe um atalho no menu Iniciar e na área de trabalho.
3. **Aviso do Windows.** Como o instalador ainda não tem assinatura digital paga, o Windows pode mostrar uma tela azul "O Windows protegeu o computador" (SmartScreen).
   - Clique em **Mais informações**.
   - Clique em **Executar assim mesmo**.
   Isso é esperado enquanto não houver assinatura (veja "Assinatura" em `docs/CI.md`). Se o seu antivírus reclamar, o arquivo é o mesmo que o CI gerou: confira a soma no arquivo `SHA256SUMS-windows-x64.txt` (no PowerShell: `Get-FileHash arquivo.msi`).
4. Abra o **Lab Desktop** pelo menu Iniciar. Na primeira vez ele pede para você **criar a senha-mestra** do cofre (guarde-a: sem ela os dados do cofre não abrem). Os dados ficam em `%APPDATA%\LabDesktop` (e o log em `%LOCALAPPDATA%\LabDesktop\Logs`), **fora** da pasta do programa: desinstalar ou atualizar o programa não apaga o seu cofre.
5. **Atualizar:** instale o `.msi` da versão nova por cima; o Windows reconhece como atualização.
6. **Desinstalar:** Configurações > Aplicativos > Lab Desktop > Desinstalar. O cofre continua em `%APPDATA%\LabDesktop` (apague a pasta só se não quiser mais os dados).

### Para usar "Este computador" no Windows

O Docker Desktop precisa de duas coisas do Windows. O app confere ("Preparar este computador") e diz o que falta:

- **Virtualização ligada na BIOS** (Intel VT-x ou AMD-V/SVM). Na maioria dos computadores já vem ligada. Se o Docker reclamar, reinicie, entre na BIOS (a tecla aparece ao ligar: Del, F2 ou F10) e ligue "Virtualization" / "SVM Mode".
- **WSL 2.** Se faltar, o app mostra o comando `wsl --install` para você rodar no **PowerShell como administrador** (o app não pede nem guarda sua senha de administrador, e não reinicia o computador). Depois **reinicie** e clique em "Verificar de novo".

Depois o app oferece o Docker Desktop (`winget install Docker.DockerDesktop`, com a sua confirmação) ou a página oficial de download. Abra o Docker Desktop uma vez e aceite os termos dele. Então volte ao app e clique em **Criar meu laboratório local**.

---

## Instalar no Mac

Feito para o macOS atual (o Java 21 do app pede o macOS 11 ou mais novo; foi testado só no macOS mais recente). O arquivo é para o chip do Mac em que foi gerado (`...-mac-arm64.dmg` serve aos Macs com chip Apple; para um Mac Intel é preciso gerar o instalador num runner Intel).

1. **Baixe** `LabDesktop-<versão>-mac-arm64.dmg` e dê dois cliques.
2. **Arraste** o Lab Desktop para a pasta **Aplicativos**.
3. **Aviso do macOS (Gatekeeper).** Como o app ainda não tem a assinatura paga da Apple (nem foi "notarizado"), na primeira abertura o macOS diz que não pôde verificar o app.
   - Tente abrir o app (ele não abre, e o aviso aparece). Feche o aviso.
   - Abra **Ajustes do Sistema > Privacidade e Segurança**, role até o fim: aparece "Lab Desktop foi bloqueado". Clique em **Abrir Mesmo Assim** e confirme com a sua senha do Mac.
   - (Alternativa para quem usa o Terminal: `xattr -dr com.apple.quarantine "/Applications/Lab Desktop.app"` e abra de novo.)
   Isso só acontece na primeira vez. Veja "Assinatura" em `docs/CI.md` para o caminho de remover o aviso.
4. **Rede local.** Na primeira conexão a um servidor da sua rede local (casa ou escritório), o macOS pergunta se o Lab Desktop pode encontrar dispositivos na **rede local**. Responda **Permitir**; sem isso o app não alcança o servidor.
5. Abra o app e **crie a senha-mestra** do cofre. Os dados ficam em `~/Library/Application Support/LabDesktop`, fora do app.
6. **Atualizar:** arraste a versão nova para Aplicativos e substitua. **Desinstalar:** arraste o app para o Lixo (o cofre continua na pasta acima).

### Para usar "Este computador" no Mac

Precisa do **Docker Desktop**. O app confere e, se houver Homebrew, oferece `brew install --cask docker-desktop` (com a sua confirmação); sem Homebrew, abre a página oficial. Depois de instalar, abra o Docker uma vez (aceite os termos) e volte ao app: **Criar meu laboratório local**.

---

## Primeiro uso

1. Abra o app e crie a **senha-mestra**.
2. Escolha **Este computador** (laboratório local, precisa do Docker) ou **Outro servidor (SSH)**.
3. Se algo faltar, o app diz em português o que é e o que fazer. Nada é instalado ou mudado sem o seu clique.
4. Para "Este computador" e para instalar o agente, use uma versão do app que traga o pacote do agente; no app público, o app avisa que ele não está incluído.

## Se algo der errado

- O log do app está na pasta de logs acima (`lab-desktop.log`); ele não guarda senha, chave nem token.
- "O Lab Desktop já está aberto": só pode haver uma janela do app por usuário. No Windows e no Linux, fechar a janela **sai** do app; no Mac, fechar a janela só a esconde (clique no ícone do Dock para trazê-la de volta, ou Cmd+Q para sair).
