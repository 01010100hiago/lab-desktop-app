# Lab Desktop

Criado por Thiago Bezerra. Licença MIT.

Aplicativo de computador (Mac e Linux e, em modo experimental, Windows) para cuidar de servidores e de projetos com Docker numa janela só, com cofre cifrado, SSH, terminal, arquivos e gráficos. Em Java 21, **software livre e gratuito**.

<table>
  <tr>
    <td align="center"><a href="docs/img/resumo-claro.png"><img src="docs/img/mini/resumo-claro.webp" alt="Resumo do servidor (tema claro)" width="420" /></a></td>
    <td align="center"><a href="docs/img/resumo-escuro.png"><img src="docs/img/mini/resumo-escuro.webp" alt="Resumo do servidor (tema escuro)" width="420" /></a></td>
  </tr>
</table>

## Por onde começar

- **Instalar:** [docs/INSTALAR.md](docs/INSTALAR.md), escrito para quem não é técnico.
- **Ver como é:** [docs/GALERIA.md](docs/GALERIA.md), com 82 telas nos temas claro e escuro.
- **Tudo o que o app faz:** [docs/RECURSOS.md](docs/RECURSOS.md). **O que dá para configurar:** [docs/CONFIGURACOES.md](docs/CONFIGURACOES.md).
- **Mexer no código:** [docs/DESENVOLVIMENTO.md](docs/DESENVOLVIMENTO.md).

## A ideia

**O problema.** Quem cuida de servidores e de projetos (uma VPS, um servidor em casa, uma instância na AWS) acaba decorando comandos: `ssh` com a chave certa, `docker compose`, `scp`, onde fica cada pasta, como abrir um túnel. É fácil errar, é fácil deixar uma chave solta e é difícil ver, de relance, se o servidor está bem.

**A ideia.** Uma janela só que junta tudo isso, com cuidado de segurança em cada passo: você cadastra o servidor, o app guarda as chaves num cofre cifrado e mostra o que está acontecendo, sem você decorar nada.

**Para quem é.** Para quem mantém servidores com Docker e projetos pequenos ou médios (uma pessoa ou um time pequeno) e quer um painel claro, em vez de uma pilha de terminais.

## O que o app faz, em resumo

- **Servidores:** vários, por SSH direto, AWS Session Manager ou Cloudflare (sem abrir porta), com reconexão sozinha.
- **Resumo:** saúde, CPU, memória, disco, containers, seis gráficos de tendência (10 minutos a 1 mês) e relatório.
- **Segurança, Containers, Processos e Máquina:** o que está acontecendo no servidor, com Encerrar e Reiniciar depois do seu clique.
- **Projetos:** Visão geral, Logs, Terminal, Arquivos, Git, Backups e Internet; criar, importar e adicionar container.
- **Git do projeto:** commits, diff, alterações e branches, só leitura, mais criar o repositório local.
- **Cloudflare:** publicar um projeto na internet e adicionar domínio, com a prévia do que muda.
- **Este computador:** o laboratório rodando no próprio Docker, sem servidor.
- **Agente de monitoramento:** vai dentro do app e só lê a máquina (token obrigatório, sem inteligência artificial); só é instalado depois do seu clique.
- **Cofre:** chaves e tokens cifrados (Argon2id e AES-256-GCM) que nunca saem do seu computador.

Tudo isso, com o estado de cada recurso, em [docs/RECURSOS.md](docs/RECURSOS.md).

**O que o app NUNCA faz.**

- Não instala nada sem o seu clique: cada instalação mostra o que vai fazer, comando por comando.
- Não guarda a senha do servidor: ela fica só na memória, vai direto ao servidor e é descartada.
- Não envia dados para a internet por conta própria: só fala com os servidores que você cadastra e, se você pedir, com a sua conta da Cloudflare.
- Não instala inteligência artificial.
- Não faz push para o GitHub nem mescla ou desfaz commits: o Git do projeto lê e cria repositório local.

## Um olhar rápido

<table>
  <tr>
    <td align="center" valign="top"><a href="docs/GALERIA.md"><img src="docs/img/mini/projeto-visao-geral-claro.webp" alt="Visão geral de um projeto" width="205" /></a><br /><sub>Projeto: visão geral</sub></td>
    <td align="center" valign="top"><a href="docs/GALERIA.md"><img src="docs/img/mini/projeto-git-commits-claro.webp" alt="Git do projeto" width="205" /></a><br /><sub>Git do projeto</sub></td>
    <td align="center" valign="top"><a href="docs/GALERIA.md"><img src="docs/img/mini/servidor-terminal-claro.webp" alt="Terminal" width="205" /></a><br /><sub>Terminal</sub></td>
  </tr>
  <tr>
    <td align="center" valign="top"><a href="docs/GALERIA.md"><img src="docs/img/mini/novo-projeto-claro.webp" alt="Novo projeto" width="205" /></a><br /><sub>Novo projeto</sub></td>
    <td align="center" valign="top"><a href="docs/GALERIA.md"><img src="docs/img/mini/servidor-cloudflare-claro.webp" alt="Cloudflare" width="205" /></a><br /><sub>Cloudflare</sub></td>
    <td align="center" valign="top"><a href="docs/GALERIA.md"><img src="docs/img/mini/servidor-arquivos-claro.webp" alt="Arquivos" width="205" /></a><br /><sub>Arquivos por SFTP</sub></td>
  </tr>
</table>

As telas são do app, com dados de exemplo inventados, dentro de uma moldura de janela desenhada por código. Veja todas em [docs/GALERIA.md](docs/GALERIA.md).

## Como instalar

Baixe o instalador do seu sistema (veja [docs/INSTALAR.md](docs/INSTALAR.md)) ou rode `./gradlew run` (veja [docs/DESENVOLVIMENTO.md](docs/DESENVOLVIMENTO.md)). Na primeira abertura o app pede a senha-mestra do cofre; ela não tem recuperação, então guarde-a bem. Depois escolha "Adicionar servidor", informe como conectar, confira a impressão digital e, se quiser, deixe o app instalar a chave e o agente depois de mostrar o que vai fazer. Quem não tem servidor pode usar "Este computador".

## Testes e qualidade

[![build](https://github.com/01010100hiago/lab-desktop-app/actions/workflows/build.yml/badge.svg)](https://github.com/01010100hiago/lab-desktop-app/actions/workflows/build.yml) [![instaladores](https://github.com/01010100hiago/lab-desktop-app/actions/workflows/instaladores.yml/badge.svg)](https://github.com/01010100hiago/lab-desktop-app/actions/workflows/instaladores.yml)

O `./gradlew build` roda 3.962 testes automáticos (13 ficam pulados quando falta o Docker de verdade, o `node` ou dados reais de um servidor; telas desenhadas sem abrir janela, SSH e agente de mentira, cofre, Git) e confere as dependências. No GitHub o `build` roda em Linux, Mac e Windows: Linux e Mac são exigidos (se falharem, o commit fica reprovado); o Windows é experimental e informativo (o resultado aparece, mas não reprova). Os selos acima mostram o resultado do momento, que pode estar verde ou vermelho. Para rodar tudo no seu computador: `./gradlew build`. O que falta no Windows está em [docs/CI.md](docs/CI.md), na seção "Windows: o que falta".

## Limites de hoje

- O agente que o app leva dentro do programa só monitora: lê a máquina (CPU, memória, disco, rede, processos e Docker), exige o token e não usa IA. Projetos e Claude no servidor pedem um agente que os ofereça; sem agente o app segue com terminal, arquivos e segurança.
- O agente recebe o acesso ao Docker do servidor (o app não monta o disco nem a pasta dos projetos nele). O filtro do agente só deixa três leituras, mas o poder desse acesso é o do Docker inteiro, e é o risco que resta.
- AWS Session Manager foi provado com uma instância EC2 real (Amazon Linux 2023): o túnel subiu, o SSH entrou por dentro e o app conectou. O agente de monitoramento não foi instalado nela porque a imagem não tinha Docker (o instalador só instala o Docker sozinho em Debian e Ubuntu).
- Cloudflare: o caminho (`cloudflared access tcp` e o SSH por dentro) foi provado de ponta a ponta pela linha de comando contra um servidor real com túnel da Cloudflare; falta conferir o fluxo completo pela tela do app.
- O Git do projeto lê e cria o repositório local. Aprovar e mesclar, desfazer e publicar não existem.
- O app procura o repositório Git da pasta do projeto NO SERVIDOR; um repositório que está só no seu computador ou no GitHub só aparece depois de ser clonado ou enviado para lá.
- Os instaladores não têm assinatura de código paga.
- O Windows é experimental (no primeiro CI, 271 de 3904 testes falharam nele; veja `docs/CI.md`), e Linux e Windows não têm prova de ponta a ponta: o CI do Linux passa a suíte, mas a janela, o Docker e os servidores foram provados à mão só no Mac.

## Documentação

| Arquivo | O que tem |
|---|---|
| [docs/INSTALAR.md](docs/INSTALAR.md) | Instalar no Mac e no Windows (experimental), para quem não é técnico |
| [docs/RECURSOS.md](docs/RECURSOS.md) | O que o app faz e o estado de cada recurso |
| [docs/CONFIGURACOES.md](docs/CONFIGURACOES.md) | O que dá para mudar, onde fica e o que o app NÃO faz sozinho |
| [docs/GALERIA.md](docs/GALERIA.md) | Telas do app nos temas claro e escuro |
| [docs/DESENVOLVIMENTO.md](docs/DESENVOLVIMENTO.md) | Compilar, testar, empacotar, conferir na prática e o Claude opcional |
| [docs/ARQUITETURA.md](docs/ARQUITETURA.md) | O desenho do app |
| [docs/CONTRATO-API.md](docs/CONTRATO-API.md) | As rotas do agente |
| [docs/CI.md](docs/CI.md) | O CI, os instaladores e a assinatura de código |
| [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) e `licencas/` | Créditos e textos das licenças de terceiros |

## Licença

O Lab Desktop é **software livre e gratuito**, sob a licença **MIT**, **sem garantia**.

Licença MIT, Copyright (c) 2026 Thiago Bezerra (arquivo `LICENSE`).

As bibliotecas de terceiros e a licença de cada uma estão em `THIRD-PARTY-NOTICES.md`, na pasta `licencas/` e dentro do app, em Ajustes > Sobre.

## Contribuir e segurança

Veja [CONTRIBUTING.md](CONTRIBUTING.md), [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) e [SECURITY.md](SECURITY.md). Issues e pull requests têm modelos em `.github/`.
