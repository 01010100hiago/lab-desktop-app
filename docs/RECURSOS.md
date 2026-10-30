# O que o app faz

A lista completa dos recursos do Lab Desktop e em que estado cada um está. Para o resumo, veja o [README](../README.md).

| O que | Como funciona | Estado |
|---|---|---|
| **Cofre de chaves SSH** | Cifrado por uma senha-mestra (Argon2id e AES-256-GCM); as chaves e os tokens nunca saem do computador. | Pronto. |
| **Vários servidores** | SSH pelo Apache MINA SSHD, impressão digital conferida na primeira conexão, reconexão sozinha. Se o servidor não volta (12 falhas seguidas), o app passa a tentar a cada 5 minutos e a faixa diz desde quando e por quê; uma mudança de rede adianta a próxima tentativa. | Pronto. Testado com um servidor SSH embutido nos testes; falta prova num servidor real. |
| **Primeira conexão com senha e chave recusada** | A senha vale uma vez: o app instala a chave, prova que ela entra e passa a usar só a chave. "Chave recusada" tem o botão "Instalar a chave com a senha…". Desligar o login por senha é opcional e nunca é automático. | Pronto. |
| **Resumo do servidor** | Saúde, CPU, memória, disco, containers, seis gráficos de tendência (10 min a 1 mês), relatório (mínimo, média, máximo, P95, pico), registro do servidor (só leitura) e o que pede atenção. | Pronto; precisa do agente de monitoramento. |
| **Segurança, Containers, Processos e Máquina** | Tentativas de entrada, logins, firewall, SSH e bloqueio; containers; os processos que mais gastam; hardware, versões e disco. Em Processos, "Encerrar" (SIGTERM, depois "Forçar" por outro clique) e Parar e Reiniciar para containers. | Pronto. |
| **Projetos** | Lista e página de cada projeto: Visão geral, Logs, Terminal, Arquivos, Git, Backups (baixar), Consumo e limite (só com Claude), Internet e Processos. Iniciar, parar e reiniciar. | Pronto. |
| **Novo projeto** | Criar do zero, importar (por Git, de uma pasta do seu computador ou de uma pasta do servidor) e adicionar um container (Node, Postgres, Redis ou imagem), com a diferença do `docker-compose.yml` à vista antes de gravar. | Pronto. |
| **Git do projeto** | Linha "Código" na Visão geral e aba Git: commits, o que cada um mudou (diff), alterações não commitadas e branches, só leitura. Acha o repositório que mora numa subpasta (`app/`) e deixa você escolher entre vários. Cria o repositório local (primeiro commit, `.gitignore` com segredos de fora, identidade só no repositório) e instala o Git no servidor, depois de mostrar os comandos. | Pronto (ler e criar). **Não existe:** aprovar e mesclar, desfazer, push e remoto. |
| **Cloudflare** | Conectar a conta com um token (que fica só no cofre), ver conta, domínio e túnel, Adicionar domínio, Publicar na internet e Remover um endereço, com a prévia de tudo o que muda; túnel gerido por arquivo no servidor ou pela API. | Pronto; falta prova contra a Cloudflare real. |
| **Conexão sem porta aberta** | O app sobe sozinho o `aws ssm start-session` ou o `cloudflared access tcp` numa porta aleatória de 127.0.0.1 e o SSH conecta por dentro; o programa encerra junto com a conexão. | Pronto; testado com programas de mentira e um processo de verdade; falta prova contra a AWS e a Cloudflare reais. |
| **Servidor protegido** | Uma marca que faz o app só ler e conectar: ele não instala, atualiza nem remove nada, nem muda chaves ou a senha de acesso. | Pronto. |
| **Agente de monitoramento** | Instalar ("O que instalar": completo, escolher ou só SSH), atualizar e remover, sempre depois do seu clique. | Pronto; o pacote do agente não vem neste repositório. |
| **Este computador** | O laboratório no próprio computador, no Docker, sem SSH. "Preparar este computador" confere o que falta e só instala depois da confirmação. | Pronto; provado no Mac com o Docker de verdade; no Windows e no Linux falta prova. |
| **Arquivos e Terminal** | Gerenciador por SFTP (abrir, editar com cópia de segurança, enviar, baixar, apagar, mover) e terminal nativo com PTY, no servidor e em cada projeto. Ao sair de um projeto com terminal aberto o app pergunta, e a caixa "Não perguntar de novo" (desfeita em Ajustes > Terminal) silencia a pergunta. | Pronto. |
| **Ajustes, Sobre e Ajuda** | Tema, tamanho do texto, ordem das abas, terminal, containers protegidos, versão e licenças, e uma Ajuda em categorias (F1). | Pronto. |
| **Claude opcional** | Se o servidor tiver o Claude, o app mostra as sessões, o consumo e o limite; sem ele, tudo o mais funciona igual. | Pronto. |

**O que ainda não existe:** trocar a chave ou o token de um servidor e "redefinir pin"; a segunda parte do Git (aprovar, mesclar, desfazer, publicar); notificações do sistema e ícone na barra de menus; a tela de consumo da conta inteira; retomar um download interrompido; assinatura de código paga nos instaladores.
