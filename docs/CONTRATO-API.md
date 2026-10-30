# lab-desktop: contrato da API do agente

Documento 2 de 2 (ARQUITETURA.md e CONTRATO-API.md). Descreve o que o app nativo pode pedir ao agente Node do Painel do Lab, rota por rota, e o que o usuário precisa saber sobre como o app se identifica a ele. Como o agente confere essa identificação por dentro não faz parte deste contrato.

## 0. Origem e validade

| Item | Valor |
|---|---|
| Fonte | O comportamento do agente (perfis `lab` e `monitor`, 3.12), conferido contra respostas reais capturadas em um servidor de teste (`scripts/capturar-fixtures.sh`). |
| Repositório do agente | Um repositório à parte do app, que o app só consome pela API. |
| O que este documento cobre | O que o app envia e o que ele espera receber: rotas, campos e códigos de erro. Não descreve a implementação interna do agente. |
| Mock do front | Os mocks do front web NÃO são contrato: divergem do agente real (por exemplo, `mem` de container é texto `"186MiB"` no mock e número de bytes no agente). |

Legenda: "App" = o que o lab-desktop faz com a rota. "Web" = o que o front atual faz.

## 1. Convenções gerais

### 1.1 Endereço e transporte

O agente escuta só em `127.0.0.1:7000` dentro do servidor. **"Este computador":** sem túnel nenhum; o app chama `http://127.0.0.1:<porta>` direto, porta que o Docker publica SÓ no loopback do computador (padrão 7070) e que o agente atende dentro do container (3.12). O app chega nele por um forward do MINA SSHD (`127.0.0.1:<porta efêmera>` no Mac para `127.0.0.1:7000` no servidor). Todas as chamadas do app são `http://127.0.0.1:<porta>/...`.

Regras obrigatórias do cliente HTTP do app:

- `HttpClient` do JDK com `version(HTTP_1_1)`. O padrão tenta `Upgrade: h2c`, e o `servidor.on('upgrade')` destrói o socket de todo upgrade que não seja `/terminal/<p>/...`. Reproduzido: a chamada falha com `HTTP/1.1 header parser received no bytes`; em HTTP/1.1 devolve 200.
- Sem proxy do sistema (`ProxySelector.of(null)`), sem seguir redirect.
- Um cabeçalho de identificação do app (seção 6), `Accept: application/json`, `Content-Type: application/json` quando há corpo, `User-Agent: LabDesktop/<versão>`.
- A credencial do app nunca vai em URL, log ou mensagem de erro.
- Se o servidor pedir confirmação em dois passos, o app pergunta o código ao usuário e repete a chamada (seção 6). Qualquer outro `401` é credencial recusada.

### 1.2 Respostas de recusa

Antes de uma rota responder, o agente pode recusar a chamada: credencial ausente ou recusada (`401`, seção 6). Rota que não existe responde `404` em texto puro (`não encontrado`) ou, no perfil `monitor`, o JSON de 3.12. Como o agente decide isso por dentro não faz parte do contrato.

### 1.3 Formatos

- Resposta JSON: `Content-Type: application/json; charset=utf-8`, `Cache-Control: no-store`.
- Texto puro: `GET .../logs` e `GET .../claude/texto` (`text/plain; charset=utf-8`).
- Erro JSON: `{"erro":"mensagem em pt-BR"}`. Rotas desconhecidas e arquivos ausentes respondem texto puro, não JSON.
- Corpo de requisição: JSON, objeto, até 32 KB. Corpo vazio vira `{}`. Array, `null`, número ou JSON inválido caem em erro (`400`, texto varia por rota).
- Nome de projeto: `^[a-z0-9][a-z0-9-]{0,48}$` (`nomeValido`). Nome de container: `^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$`.
- `lab` é a sessão raiz: aceito como `<p>` onde indicado, sem pasta em `/srv/lab/projects`, container `lab-claude-lab`. `infra` e `painel` são nomes reservados.

### 1.4 Unidades e tipos (as pegadinhas dos DTOs)

| Campo | Unidade e tipo real |
|---|---|
| `servidor.cpu.uso`, `usoInstantaneo`, `porNucleo[]`, `historico.cpu[]`, `historico.mem[]` | fração de 0 a 1 (não percentual). Pode ser `null`. |
| `projetos[].cpu`, `containers[].cpu`, `processos[].cpu`, `consumo[].cpu`, `servidor.sessoes[].cpu` | percentual (`12.5` = 12,5 %), soma de containers pode passar de 100. `null` sem leitura. |
| qualquer `mem`, `memLimite`, `total`, `usado`, `livre`, `tamanho` (bytes) | número de bytes, nunca texto. |
| `rx_bps`, `tx_bps` | bytes por segundo. |
| `uptime` | segundos (número com decimais). |
| `cpu.temperatura`, `sensores[].c/max/crit`, `historico.temp[]` | graus Celsius. |
| `mexido` (em `/api/projetos`) | epoch em milissegundos (número). |
| `quando`, `criado`, `desde`, `lido_em`, `zera_em`, `em`, `inicio`, `fim`, `ultima_atividade` | texto ISO 8601 UTC, ou `null`. |
| `usado_pct`, `semana.usado_pct`, `cinco_horas.usado_pct` | percentual de 0 a 100 (da conta). |
| `usado_pp`, `teto_pct`, `pct_conta`, `freio_pct`, `por_projeto.*.usado_pp` | pontos percentuais da semana da conta (100 = semana inteira). Fatia ESTIMADA. |
| `custo_usd` | US$ equivalente de API (assinatura Max, não é cobrança). |
| `containers[].portas` | texto do Docker (`127.0.0.1:5440->5432/tcp, ...`). |
| `containers[].status` | texto do Docker (`Up 3 hours`). |

Regra do app: todo campo é anulável, `FAIL_ON_UNKNOWN_PROPERTIES` desligado, um record por forma de resposta, e resposta que não bate vira `RespostaInvalida` só para aquele tópico (a tela mostra "não consegui ler", não derruba nada).

### 1.5 Status HTTP em uso

| Status | Quando |
|---|---|
| 200 | sucesso (inclusive `logs` com erro do Docker dentro do corpo, ver 3.9) |
| 400 | nome inválido, corpo inválido, container que não é do projeto, tecla não permitida, código inválido |
| 401 | credencial ausente ou recusada (seção 6) |
| 404 | projeto inexistente; `Sessão não está aberta.`; rota desconhecida (texto puro) |
| 409 | sessão pausada pelo limite; Retomar que ainda estoura |
| 429 | `muitas_tentativas`: o agente bloqueia por um tempo as chamadas de um endereço que errou a credencial muitas vezes e não traz um token válido: `{"erro","codigo":"muitas_tentativas","aguarde_s"}` com `Retry-After`. O app mostra "Muitas tentativas; espere um pouco", não repete e o `Sondador` espera o `aguarde_s` |
| 500 | falha do Docker ou do compose; `Projeto não existe.` ao abrir sessão |
| 503 | só `GET /api/tokens` antes do primeiro ciclo de consumo |

## 2. Índice de rotas

Colunas "Web" e "App" resumem a cadência; o detalhe está nas seções 3 e 4. Timeout é o sugerido para o cliente do app (o agente impõe os dele por dentro).

| # | Método e caminho | Para quê | Web (cadência) | App (tópico) | Timeout |
|---|---|---|---|---|---|
| 1 | `GET /api/versao` (NOVA) | validar a credencial e a compatibilidade; marca CONECTADO | não existe | conexão | 5 s |
| 2 | `GET /api/servidor` | métricas, histórico de 10 min, processos | 10 s, só com a faixa Servidor aberta | SERVIDOR | 20 s |
| 26 | `GET /api/servidor/historico?janela=10m\|1h\|1d\|7d\|30d` (NOVA) | histórico de métricas de 10 min a 1 mês (3.2.1) | não existe | HISTORICO(janela) | 20 s |
| 3 | `GET /api/servidor/detalhe` | tela Servidor completa | 10 s na página do servidor | SERVIDOR_DETALHE | 20 s |
| 4 | `GET /api/limites` | limites do plano, freio, pausadas | 60 s em toda página | LIMITES (sempre ligado) | 15 s |
| 5 | `GET /api/tokens` | consumo global | 1 vez na página de consumo | TOKENS | 15 s |
| 6 | `GET /api/tokens/:p` | consumo de um projeto | ao abrir a aba Consumo | CONSUMO(p) | 15 s |
| 7 | `GET /api/projetos` | lista de projetos + resumo do servidor | 10 s na página inicial do front web | PROJETOS | 20 s |
| 8 | `GET /api/projetos/:p` (inclui `lab`) | detalhe do projeto ou da sessão raiz | 10 s na página do projeto e a página da sessão | DETALHE(p) | 20 s |
| 9 | `GET /api/projetos/:p/logs` | logs de um container | ao abrir/trocar; 5 s se "atualizar" | LOGS(p,c,n) | 25 s |
| 10 | `GET /api/projetos/:p/claude/tela` | tela do Claude já analisada | 2 s na página da sessão | TELA(p) | 10 s |
| 11 | `GET /api/projetos/:p/claude/texto` | tela em texto puro | sob demanda | ação | 15 s |
| 12 | `POST /api/projetos/novo` | criar projeto (corpo `{"nome", "guia_ia"?}`, 4.1) | modal Novo projeto | ação | 60 s |
| 13 | `POST /api/projetos/:p/iniciar` ou `parar` ou `reiniciar` | compose up, down, restart | botões | ação | 12 min |
| 14 | `POST /api/projetos/:p/containers/:c/reiniciar` | `docker restart` de um container | tabela de containers | ação | 3 min |
| 15 | `POST /api/projetos/:p/claude/abrir` | abre a sessão do Claude | botões | ação | 6 min |
| 16 | `POST /api/projetos/:p/claude/fechar` | fecha a sessão | menu | ação | 60 s |
| 17 | `POST /api/projetos/:p/claude/reabrir` | fecha e abre continuando a conversa | modal | ação | 6 min |
| 18 | `POST /api/projetos/:p/claude/teclas` | envia tecla ou texto ao tmux | lateral da sessão | ação | 15 s |
| 19 | `POST /api/projetos/:p/claude/codigo` | envia o código de login | alerta de login | ação | 30 s |
| 20 | `PUT /api/projetos/:p/limite` | define ou remove o teto | modal | ação | 30 s |
| 21 | `POST /api/projetos/:p/limite/retomar` | tira a pausa e reabre | botão Retomar | ação | 6 min |
| 22 | `PUT /api/limites/freio` | liga, ajusta ou desliga o freio global | modal na página de consumo | ação | 30 s |
| 24 | ~~`GET /api/containers`~~ (REMOVIDA: o agente público de leitura não a tem) | | não existe | o app nunca a usou; ver 3.12 | |
| 25 | ~~`GET /api/containers/:nome/logs?linhas=<n>`~~ (REMOVIDA, como a 24) | | não existe | o app nunca a usou; ver 3.12 | |

Fora do app: `POST /api/diag`, `GET /terminal/<p>/*` (HTTP e WebSocket do ttyd) e as páginas estáticas (`/`, `/projeto/:p`, `/sessao/:p`, `/consumo`, `/servidor`, arquivos de `publico/`). O terminal do app é um canal SSH com PTY, não o ttyd.

## 3. Rotas de leitura

### 3.1 `GET /api/versao` (nova, proposta)

Resposta `200`: `{ "api": 1, "painel": "v22.x", "cliente": "mac-de-teste", "perfil": "lab", "recursos": { "claude": true, "projetos": true, "monitor": true, "guia_ia": true, "historico": true }, "agente": null }`.

- `api`: versão do contrato; o app avisa "agente incompatível" se for diferente de 1.
- `painel`: `process.version` do Node do agente.
- `cliente`: o nome do dispositivo desta conexão, ou `null`.
- `perfil`: `"lab"` (o painel completo do lab, o padrão) ou `"monitor"` (o agente que o app instala em qualquer servidor: só leitura da máquina). Aditivo: o contrato continua `api: 1`.
- `recursos`: o que o servidor sabe fazer, `{ "claude": bool, "projetos": bool, "monitor": bool, "guia_ia": bool }`. Perfil `lab` = tudo `true`; perfil `monitor` = só `monitor`. **`guia_ia`**: o `POST /api/projetos/novo` entende `"guia_ia": false` (4.1); o app só mostra a caixa "Incluir guia para o Claude" quando `claude` E `guia_ia` são `true` (um agente mais antigo ignoraria o `false` e criaria o `CLAUDE.md` do mesmo jeito); ausente vale "não". **`historico`**: o agente grava o histórico de métricas e responde `GET /api/servidor/historico` (3.2.1); o app SÓ chama a rota nova quando é `true` (ausente vale "não": um agente de antes não a tem, e o app usa os 10 minutos de `/api/servidor`, sem erro). **É o sinal "o servidor tem Claude"**: o app esconde o Claude (aba Terminal do projeto, Visão geral, lista de projetos, Consumo, faixa de crédito, tópico de limites) quando `claude` é falso, e a aba Projetos quando `projetos` é falso. Cada campo é anulável no app: **um agente que não manda `recursos` (agente antigo) mantém o comportamento de antes** (o Claude vale pela presença de `sessao` no detalhe do projeto, a aba Projetos fica), e desconhecido nunca esconde nada.
- `agente`: a versão do pacote do agente que o app instalou (`"0.1.0-abc1234"`, o texto do arquivo `VERSAO` do pacote), ou `null` (o servidor não tem o arquivo). Serve para "Atualizar agente" mostrar de qual versão para qual.

Erros: `401` com o campo `erro` em JSON (credencial recusada, seção 6).

Uso no app: é o teste de saúde. A conexão só vira CONECTADO depois de um 200 aqui. `401` significa credencial recusada (oferece registrar de novo). Falha de conexão significa túnel ou agente fora (SSH_OK_SEM_AGENTE se o SSH está de pé).

### 3.2 `GET /api/servidor`

`montarInfoServidor({ comProcessos: true, comHistorico: true })`. Sempre `200`.

```jsonc
{
  "quando": "2026-10-20T13:00:00.000Z",
  "hostname": "lab", "sistema": "Linux de exemplo 1.0", "kernel": "6.1.0-..",
  "uptime": 123456.7,                       // segundos
  "carga": [1.2, 0.9, 0.8],                 // 1, 5 e 15 min
  "cpu": { "uso": 0.17,                     // 0..1, média das últimas 5 amostras de 2 s; pode ser null
           "usoInstantaneo": 0.2,
           "porNucleo": [0.1, 0.3, null],   // 0..1 por thread
           "nucleos": 8, "modelo": "Intel(R) ...", "temperatura": 62.0 },   // temperatura: °C da zona mais quente, ou null
  "memoria": { "total": 0, "usada": 0, "disponivel": 0, "livre": 0, "cache": 0, "buffers": 0,
               "swap": { "total": 0, "usada": 0 } },                          // bytes
  "rede": { "interface": "enp3s0", "rx_bps": 0, "tx_bps": 0 },               // interface mais ativa
  "node": { "painel": "v22.x", "imagemSessao": { "node": "..", "claude": "..", "docker": "..", "compose": "..", "pnpm": ".." } },  // imagemSessao: null até a primeira leitura (cache de 1 h)
  "discos": [ { "montagem": "/", "total": 0, "usado": 0, "livre": 0 } ],     // /srv/lab só aparece se for outro dispositivo
  "docker": { "versao": "..", "containers": 0, "rodando": 0, "pausados": 0, "parados": 0, "imagens": 0, "volumes": 0,
              "tamanhos": { "imagens": 0, "containers": 0, "volumes": 0, "cache": 0 } },   // objeto inteiro null se o docker info falhar
  "sessoes": [ { "projeto": "app-loja", "container": "lab-claude-app-loja", "desde": "ISO|null", "cpu": 0.4, "mem": 123, "redes": ["app-loja_default"] } ],
  "historico": { "passo_s": 10, "cpu": [], "mem": [], "rx_bps": [], "tx_bps": [], "temp": [] },   // até 60 pontos (10 min), valores podem ser null
  "processos": [ { "pid": 1, "nome": "node", "usuario": "labuser", "cpu": 1.5, "mem": 1234, "container": "painel-agente" } ]   // top 20; cpu null na primeira varredura; container null = processo do servidor
}
```

Web: a página inicial do front web (`carregarServidorDetalhe`), 10 s, só enquanto a faixa Servidor está aberta (padrão aberta) ou o modal Processos está aberto. Compara o JSON com o anterior e só então redesenha sparklines e processos. Erro é silencioso (os números básicos já vêm de `/api/projetos`).

App: tópico SERVIDOR, assinado pela faixa Servidor da lista de projetos e pelo Monitor. `sessoes` pode não conter a sessão raiz; o app soma `lab` a partir de 3.8, como o front faz.

#### 3.2.1 `GET /api/servidor/historico?janela=10m|1h|1d|7d|30d`

Só leitura, nos perfis `lab` e `monitor`, com as mesmas regras de acesso dos outros `GET`. O agente grava sozinho (com o app fechado) o histórico de CPU, memória, uso do disco principal, escrita no disco e rede (ARQUITETURA 8.4) e responde uma janela por chamada.

```jsonc
{ "janela": "1h", "passo_s": 20,
  "inicio": "2026-10-28T14:00:00.000Z", "fim": "2026-10-28T15:00:00.000Z", "quando": "2026-10-28T15:00:07.000Z",   // início da 1ª célula, fim da última, agora no agente
  "cpu": [0.12, null, ...],                    // média de cada célula, fração de 0 a 1
  "cpu_max": [0.4, null, ...],                 // o MAIOR valor visto na célula (a média esconde o pico)
  "mem": [...], "disco_uso": [...],            // frações de 0 a 1 (disco: usado / total do disco principal, a raiz do servidor)
  "disco_escrita_bps": [...], "disco_escrita_max_bps": [...],   // BYTES por segundo (soma dos discos inteiros de /proc/diskstats)
  "rx_bps": [...], "tx_bps": [...],            // BYTES por segundo (a interface mais ativa, como em /api/servidor)
  "agora": { "t": "2026-10-28T15:00:05.000Z", "cpu": 0.083, "mem": 0.34, "disco_uso": 0.6,
             "disco_escrita_bps": 4200, "rx_bps": 125000, "tx_bps": 12000 } }   // a ÚLTIMA leitura real gravada; null antes da primeira amostra
```

- A resposta é uma **grade regular**: um valor por célula de `passo_s` segundos a partir de `inicio`; `null` onde não há dado (o agente estava parado, ou a métrica não tinha leitura). **Nunca mais de 300 células**: `10m` 60 de 10 s; `1h` 180 de 20 s; `1d` 288 de 5 min; `7d` 252 de 40 min; `30d` 240 de 3 h. A célula maior é a média PONDERADA pelas amostras de cada balde (um balde fechado pesa `passo / 10 s` amostras; o balde ABERTO pesa só as que já tem: um balde de 1 h com uma amostra não vale o mesmo que os cheios) e o maior dos máximos. **`agora`** : o último valor real, não a média da última célula (num período de 1 mês ela cobre 3 horas); é o "agora" dos gráficos, para o número nunca divergir do indicador. Agente sem o campo: o app usa o último valor da série e o chama de "último".
- **Relógio errado nunca apaga histórico** : uma amostra até 1 h antes da última só é ignorada; um salto maior, para trás ou para frente, vai para QUARENTENA (nada gravado, podado nem descartado) até haver amostras seguidas e coerentes no relógio novo (para frente, 6 amostras; para trás, 1 hora seguida, e só então o que "está no futuro" sai); se a hora volta ao normal no meio, o histórico fica intacto. Não há poda por idade, só pela capacidade da faixa. O arquivo vai ao disco a cada 5 minutos (e no `SIGTERM`).
- Fonte por janela: `10m` e `1h` saem da faixa de 10 s (guardada por 1 h), `1d` da de 1 min (1 dia), `7d` da de 10 min (1 semana), `30d` da de 1 h (30 dias). O gráfico de 1 h só enche depois de uma hora com o agente no ar, e o de 1 mês depois de um mês.
- Erros: `janela` ausente, repetida (`janela=1h&janela=1d`) ou desconhecida (`2h`, `1H`, `constructor`) é `400` com `{"erro":"Informe uma janela: janela=10m, 1h, 1d, 7d ou 30d.","validas":["10m","1h","1d","7d","30d"]}`; `401` como em toda rota (e a janela só é julgada DEPOIS do token); qualquer método que não seja `GET` cai no `404` de sempre (no perfil `monitor`, o `404` com a frase do perfil). A rota não escreve nada.
- Agente de antes (sem `recursos.historico`): esta rota não existe (`404` em texto puro); o app nem a chama e usa o `historico` de 10 min de `/api/servidor`, que NÃO mudou (3.2).
- Uso no app: tópico `HISTORICO(janela)` (ARQUITETURA 6.7), um por período, só o que está na tela; o `ClienteApi` recusa antes de ir à rede um código de janela fora dos cinco.

### 3.3 `GET /api/servidor/detalhe`

Tudo de 3.2 (com `historico` e `processos`) mais:

```jsonc
{
  "sensores": [ { "chip": "coretemp", "rotulo": "Package id 0", "c": 62.0, "max": 87.0, "crit": 105.0 } ],   // max e crit podem ser null
  "energia": { "tomada": true, "baterias": [ { "nome": "BAT0", "carga": 100, "estado": "Full", "saude": 0.91 } ] },   // tomada: true, false ou null
  "maquina": { "fabricante": "..", "modelo": "..", "versao": "..", "placa": "..", "bios": "..", "biosData": "..", "cpuModelo": "..", "nucleos": 8, "memoriaTotal": 0, "arquitetura": "x64" },
  "consumo": [ { "projeto": "app-loja", "cpu": 12.5, "mem": 0,
                 "containers": [ { "nome": "..", "tipo": "sessao|app|infra|avulso|sem-dono", "imagem": "..", "ha": "3 hours", "estado": "Up 3 hours", "cpu": 1.0, "mem": 0 } ] } ],
  "processos": [ { "pid": 1, "nome": "..", "usuario": "..", "cpu": 1.5, "mem": 0, "container": "..|null", "projeto": "app-loja|(servidor)|null" } ]
}
```

`consumo[].projeto` pode ser `null` (containers sem dono), `"(lab)"` (infra) ou o nome do projeto. Em `processos[].projeto`, `null` = container sem dono e `"(servidor)"` = processo fora de container.

Web: a página do servidor, 10 s (`UI.aCada`), timeout de 15 s, seis blocos renderizados em `try` isolados. App: SERVIDOR_DETALHE, visível só na tela Servidor.

### 3.4 `GET /api/limites`

`Object.assign({}, tokens.obterLimites() || { disponivel: false }, guarda.resumoParaConta())`. Barato: lê só memória, sem Docker e sem disco. Sempre `200`.

```jsonc
{
  "disponivel": true,                       // false quando nenhuma sessão gravou leitura válida
  "antiga": false,                          // leitura com mais de 30 min (o app NÃO recalcula, usa este campo)
  "lido_em": "ISO",
  "fonte": { "projeto": "app-loja", "sessao": "a1b2c3d4", "modelo": "claude-..." },
  "cinco_horas": { "usado_pct": 42.0, "zera_em": "ISO|null" },
  "semana":      { "usado_pct": 61.0, "zera_em": "ISO|null" },
  "leituras": [ { "projeto": "app-loja", "lido_em": "ISO" } ],        // até 20
  "freio": { "pct": 90, "ativo": true },                              // desligado: pct null e ativo false
  "pausados": [ { "projeto": "vendas", "motivo": "teto|freio", "em": "ISO" } ],
  "por_projeto": { "vendas": { "usado_pp": 22.1, "teto_pct": 20, "estado": "pausado" },
                   "_fora_do_lab": { "usado_pp": 1.5 } }              // estado: ok, atencao ou pausado
}
```

Sem leitura: `{ "disponivel": false, "freio": {...}, "pausados": [...], "por_projeto": {...} }` (os três últimos sempre existem).

Web: `UI.avisoCredito` (montado por `UI.montarTopo` em toda página), 60 s e imediatamente após Retomar, definir limite ou freio. Só roda com a aba visível (`UI.aCada`). Mostra a faixa quando a pior janela está em 75 % ou mais (`perigo` em 90 % ou mais) e `disponivel && !antiga`, e a lista de pausadas (até 3 nomes) independentemente da leitura. a página de consumo lê uma vez, junto com `/api/tokens`, para a coluna Limite, o freio e a nota "fora do lab".

App: tópico LIMITES, a cada 60 s SEMPRE (inclusive com a janela escondida), porque alimenta a faixa de crédito, o ícone da barra de menus e as notificações. As regras de severidade e limiar são as do front: `>= 75 %` atenção, `>= 90 %` perigo; barra de projeto: `> 0,75` alto e `> 0,9` crítico da fração do teto.

### 3.5 `GET /api/tokens`

Lê o cache do agregador (ciclo de 60 s; primeiro ciclo 5 s depois de subir). Nunca varre arquivo na requisição.

Erros: `503 {"erro":"Ainda calculando o consumo de tokens — tenta de novo em alguns segundos."}` até o primeiro ciclo.

```jsonc
{
  "atualizado": "ISO",
  "plano": { "tipo": "max", "nivel": "Max 20x", "observacao": "Assinatura Claude Max 20x ..." },
  "precos": { "fonte": "..", "data": "2026-10-19", "modelos": { "claude-opus-5": { "entrada": 5, "saida": 25, "leitura_cache": 0.5 } } },   // US$ por milhão de tokens; modelo sem preço pode vir com sem_preco
  "totais": { "hoje": ACC, "semana": ACC, "mes": ACC, "tudo": ACC },             // semana = 7 dias, mes = 30 dias
  "por_dia": [ { "dia": "2026-10-20", "custo_usd": 0, "saida": 0, "por_projeto": { "app-loja": 1.2, "_sem_projeto": 0.3 } } ],   // 30 dias, mais antigo primeiro
  "projetos": [ { "nome": "app-loja", "atribuicao": "direta|estimada", "hoje": ACC, "semana": ACC, "mes": ACC, "tudo": ACC,
                  "modelos": [ MODELO ], "ultima_atividade": "ISO|null" } ],     // ordenado por custo do mês, o mais caro primeiro
  "modelos": [ MODELO ],                                                         // global, mais usado primeiro
  "historico_antigo": { "explicacao": "..", "tudo": ACC, "sessoes": 0, "atribuidas": 0 },
  "limites_plano": { /* o objeto de tokens.obterLimites(): disponivel, antiga, lido_em, fonte, cinco_horas, semana, leituras */ }
}
// ACC    = { "respostas": 0, "entrada": 0, "saida": 0, "cache_escrita": 0, "cache_leitura": 0, "custo_usd": 0 }
// MODELO = { "modelo": "claude-..", "respostas": 0, "entrada": 0, "saida": 0, "cache_escrita": 0, "cache_leitura": 0, "custo_usd": 0|null, "sem_preco": true? }
```

Web: a página de consumo carrega UMA vez (não há polling de `/api/tokens`); em `503` mostra "ainda calculando…" e tenta de novo em 6 s. Cores do gráfico: `--serie-1..8` pela ordem de `projetos` (a chave `_sem_projeto` é cinza neutro, sempre por último na pilha).

App: tópico TOKENS, buscado ao abrir a tela Consumo e a cada 60 s enquanto ela está visível (o cache do agente só muda a cada 60 s). Mesmo tratamento de 503.

### 3.6 `GET /api/tokens/:p`

`:p` aceita `lab`.

Erros: `400 {"erro":"Nome inválido."}`, `404 {"erro":"Projeto não existe."}`. Nunca 503: sem cache usa `obterProjetoVazio` (zeros e listas vazias).

```jsonc
{
  "nome": "app-loja",
  "totais": { "hoje": ACC, "semana": ACC, "mes": ACC, "tudo": ACC },
  "por_dia": [ { "dia": "2026-10-20", "custo_usd": 0, "saida": 0, "entrada": 0, "cache_escrita": 0, "cache_leitura": 0, "respostas": 0 } ],   // 30 dias
  "modelos": [ MODELO ],
  "sessoes": [ { "id": "a1b2c3d4", "inicio": "ISO", "fim": "ISO", "modelos": ["claude-.."], "respostas": 0, "subagentes": 0, "saida": 0, "custo_usd": 0, "atribuicao": "direta|estimada" } ],   // até 30, mais recente primeiro
  "harness": { "entregas": [ { "quando": "ISO", "tarefa": "..", "trilha": "..", "pr": 12, "tokens": 0, "merge": true } ],   // pr, tokens e merge podem ser null; merge é boolean ou texto curto
               "iniciados": 0, "tokens_total": 0, "repo": "owner/nome|null" }
}
```

`harness.repo` só existe quando o `.git/config` do projeto aponta para o GitHub; o link de PR é `https://github.com/<repo>/pull/<pr>` e o app só o monta se `repo` casar `^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$` e `pr` for inteiro positivo.

Web: a página do projeto, ao abrir a aba Consumo (ou abrir com `#consumo`). O front tem um retry de 6 s em `503` que na prática nunca dispara nesta rota. App: tópico CONSUMO(p) com a tela visível.

### 3.7 `GET /api/projetos`

Faz `recuperarSessoes` (um `docker ps`) e, por sessão aberta, um `docker exec tmux capture-pane` (login pendente). Por isso o app só chama com a tela visível. `500 {"erro":"..."}` se não conseguir ler `/srv/lab/projects`.

```jsonc
{
  "projetos": [ {
    "nome": "app-loja",
    "descricao": "primeira linha útil do README, até 170 caracteres",
    "composes": ["docker-compose.yml"],                 // arquivos compose aceitos
    "containers": [ { "nome": "..", "estado": "running|exited|..", "status": "Up 3 hours", "portas": "..", "cpu": 1.5, "mem": 12345678 } ],   // cpu e mem null sem leitura
    "rodando": true,                                    // pelo menos um container running
    "urls": ["lab-app-loja.example.com"],              // hostnames do cloudflared (sem https://)
    "tamanho": 123456789,                               // bytes da pasta, null até o primeiro du
    "mexido": 1760900000000,                            // epoch em ms
    "sessaoClaude": true,                               // booleano; o detalhe da sessão vem em sessaoInfo
    "sessaoInfo": { "atualizada": false, "faltando": ["imagem antiga (Claude desatualizado)"] },   // null se não há sessão ou ainda não calculou
    "login": { "url": "https://claude.ai/oauth/authorize?..." },   // null se não há login pendente
    "cpu": 3.0, "mem": 123,                             // soma dos containers running, null se nenhum leu
    "acessoDocker": true,                               // false com .lab-sem-docker
    "backups": 3,                                       // quantidade de arquivos em backups/
    "tokens": { "semana_custo_usd": 1.2, "semana_saida": 12345, "hoje_custo_usd": 0.4 },   // null até o agregador ter algo do projeto
    "limite": LIMITE,
    "git": { "branch": "main", "commit": "a1b2c3d", "quando": "ISO", "sujo": false }   // null se não é repositório
  } ],
  "servidor": { /* 3.2 SEM historico e SEM processos, MAIS */ "limites_plano": { /* igual ao de 3.5 */ } }
}
// LIMITE = { "teto_pct": 20|null, "usado_pp": 14.2, "estado": "ok|atencao|pausado", "liberado": false,
//            "pausa": { "motivo": "teto|freio", "em": "ISO" } | null, "zera_em": "ISO|null", "desde": "ISO|null" }
```

A sessão raiz NÃO aparece em `projetos` (não tem pasta). O app a busca em 3.8 com `p = lab`.

Estado exibido por projeto (regra do front, a repetir): sem `composes` = "sem compose"; sem containers = "nunca subiu"; todos running = "no ar"; alguns = "parcial"; nenhum = "parado". Ordenação: "no ar" primeiro, depois por nome (`localeCompare` pt-BR). Filtros rápidos: Todos, No ar, Com Claude (`sessaoClaude`), Pausados (`limite.estado === 'pausado'`).

Web: a página inicial do front web, 10 s via `UI.aCada` (para quando a aba está oculta), junto com 3.8 para `lab` (falha dessa vira `null` sem erro de tela), e logo após cada ação. A lista só é redesenhada se o JSON mudou, e o front preserva menu aberto, foco e código de login digitado (`capturarEstadoLista`). O app faz o mesmo: atualiza linhas por nome, sem recriar componentes.

### 3.8 `GET /api/projetos/:p`

`400 {"erro":"Nome inválido."}`; `404 {"erro":"Projeto não existe."}` para nome sem pasta.

Projeto comum:

```jsonc
{
  "nome": "app-loja", "descricao": "..",
  "readme": "até 6000 caracteres", "status": "STATUS.md até 6000 caracteres",
  "composes": [ { "arquivo": "docker-compose.yml", "projetoCompose": "app-loja" } ],
  "containers": [ { "nome": "..", "estado": "running", "status": "Up 3 hours", "portas": "..", "imagem": "..", "criado": "ISO|null",
                    "cpu": 1.5, "mem": 123, "memLimite": 456, "net": "1.2kB / 3kB", "bloco": "0B / 0B", "pids": 12 } ],   // cpu...pids null sem leitura
  "urls": [".."],
  "sessao": { "aberta": true, "container": "lab-claude-app-loja|null", "desde": "ISO|null", "url": "/sessao/app-loja", "info": { "atualizada": true, "faltando": [] } | null },
  "git": { "branch": "..", "commit": "..", "quando": "ISO", "sujo": false } | null,
  "backups": [ { "arquivo": "..", "tamanho": 0, "quando": "ISO" } ],             // mais recente primeiro
  // o ARQUIVO do backup fica em <pasta do projeto>/backups/<arquivo> no servidor; o app o baixa por SFTP (a API não tem rota de download)
  "tamanho": 0, "mexido": 1760900000000, "acessoDocker": true,
  "redes": ["app-loja_default"],
  "arquivos": [ { "nome": "src", "tipo": "pasta|arquivo" } ],                    // raiz, pastas primeiro; sem ocultos, exceto .env.example e os .lab-*
  "limite": LIMITE
}
```

Sessão raiz (`:p = lab`), sempre `200`:

```jsonc
{ "nome": "lab", "raiz": true, "titulo": "Sessão do lab",
  "sessaoClaude": { "aberta": true, "container": "lab-claude-lab", "desde": "ISO|null", "url": "/sessao/lab", "info": {..}|null } | null,
  "loginPendente": false,                                                         // só booleano; a URL de login da raiz não vem aqui
  "limite": LIMITE }
```

**Servidor sem Claude**: o sinal é `recursos.claude` de `GET /api/versao` (3.1). Com ele o app esconde tudo do Claude (a aba Terminal do projeto fica só com o shell; ARQUITETURA 7.8). Um agente que não manda `recursos` (mais antigo) mantém a regra antiga: `sessao: null` no detalhe comum quer dizer "este servidor não tem sessões do Claude". O agente do lab sempre devolve o objeto `sessao`, aberto ou não (um teste de contrato confere), então para ele nada muda.

Diferenças que o app precisa respeitar: o detalhe comum não traz `login` (só a lista traz); o da raiz traz `loginPendente` (booleano) e `sessaoClaude` no lugar de `sessao`.

Web: a página do projeto e a página da sessão, 10 s. O item de `arquivos[]` de hoje só traz `nome` e `tipo`; o app já lê um `tamanho` opcional e mostra a coluna quando ele vier. Em a página da sessão serve também de PROVA para o cartão "pausada" ou "fechada" (3.10 e `ARQUITETURA.md`, seção Terminal). O front exige "forma esperada" (`sessao` objeto, ou `sessaoClaude` na raiz) antes de tratar a resposta como prova.

App: DETALHE(p).

### 3.9 `GET /api/projetos/:p/logs?container=<nome>&linhas=<n>`

`container` é obrigatório. `linhas` é limitado a 50..2000 (padrão 200).

- `404 {"erro":"Projeto não existe."}` (inclui `lab`, que não tem pasta).
- `400 {"erro":"Container não pertence a este projeto."}` (container fora dos composes do projeto, ou `lab-claude-<p>` com a sessão fechada).
- `200` texto puro: `docker logs --tail N --timestamps`, até 8 MiB, prazo de 20 s no agente.
- Pegadinha: se o Docker falhar, ainda responde `200` e o corpo é o texto do erro (`stderr`). O app não consegue distinguir; mostra o texto como veio.

Web: aba Logs da página do projeto: carrega ao abrir a aba e ao trocar container ou número de linhas; com "atualizar" marcado, a cada 5 s só se a aba Logs está visível. Cada linha ganha classe de cor: `erro|error|fail|exception` = erro, `warn|atenção|aviso` = aviso. Rolagem presa ao fim só se já estava no fim. App: LOGS(p,c,n).

### 3.10 `GET /api/projetos/:p/claude/tela`

`404 {"erro":"Sessão não está aberta."}` quando o agente não conhece sessão para `:p` (é fluxo normal, não erro). `500 {"erro":...}` se o `docker exec` falhar. Custa um `docker exec tmux capture-pane`.

```jsonc
{
  "pergunta": { "titulo": "Do you want to proceed?", "contexto": "linhas anteriores",
                "opcoes": [ { "numero": 1, "texto": "Yes", "descricao": "", "selecionada": true } ] } | null,   // só com 2 ou mais opções numeradas 1..k
  "executando": "Pondering…" | null,        // linha com "esc to interrupt" nas últimas 15 linhas
  "ultimaFerramenta": "Bash(ls)" | null,
  "esperandoVoce": false,                   // prompt vazio no fim e sem pergunta nem execução
  "tarefas": [ { "feito": false, "texto": "..." } ],   // até 25
  "atividade": ["..."],                     // até 12 linhas iniciadas por marcador
  "tela": "últimas 45 linhas já limpas de moldura"
}
```

Web: a página da sessão, 2 s (`UI.aCada`, só aba visível); renderiza só o que mudou (`renderSe`). Estado da pílula: `pergunta pendente` > `executando` > `esperando você` > `ocupado`. A aba Sessão da página do projeto chama uma vez ao abrir. Falhas: o front só toma "não está aberta" ou 3 falhas seguidas como sinal para conferir o detalhe (3.8).

App: TELA(p), só com a aba Terminal do servidor ou a aba Terminal do projeto, no modo Claude e com a tela em texto à vista (3 s, 18 s sem foco), selecionada, a sessão aberta e a janela ativa. O 404 "Sessão não está aberta." vira o valor "sessão fechada" do tópico, não uma falha.

### 3.11 `GET /api/projetos/:p/claude/texto`

`404 {"erro":"Sessão não está aberta."}`, `500`. `200` texto puro: últimas 300 linhas do tmux sem espaços no fim e sem 3 ou mais quebras seguidas.

Web: botão "Tela em texto" da página da sessão (usa `fetch` direto). App: ação "Copiar tela inteira" da barra lateral, vai para a área de transferência.

### 3.12 O perfil `monitor` e as rotas só dele

O agente de leitura que o app instala (`lab-desktop-agente`, público, de perfil único `monitor`) responde uma lista FECHADA de rotas: `GET /api/versao`, `GET /api/servidor`, `GET /api/servidor/detalhe` (sem `consumo` por projeto: `[]`; processo de container fica sem dono de projeto), `GET /api/servidor/historico` (3.2.1) e a confirmação do 2FA, a única que escreve, e só no estado de acesso do próprio agente. **Todo o resto responde `404`** em JSON, depois da checagem do token: `{"erro":"Esta rota não existe no perfil monitor do agente (só leitura do servidor).","perfil":"monitor"}`. Isso inclui projetos, sessões do Claude, consumo, limites, terminal web, páginas e TODA outra rota de escrita. O token é SEMPRE exigido. `GET /api/containers` e `GET /api/containers/:nome/logs` NÃO existem nele: o app nunca as consumiu (a lista de containers do Resumo vem de `/api/servidor`). `GET /api/versao` diz `recursos: { claude: false, projetos: false, monitor: true, guia_ia: false, historico: true }`, e é isso que faz o app esconder a aba Projetos e o Claude. O histórico fica na pasta de dados do agente (`serie-historica.json`): trocar por um agente que usava outro arquivo perde o histórico antigo. A documentação do agente (`README.md`, `SECURITY.md` e `docs/API.md` do repositório dele) é a fonte da verdade da parte pública; o resto desta seção é o que o app espera de um agente com mais recursos.

No container do laboratório local o agente atende em todas as interfaces DO CONTAINER, e o Docker publica a porta só no loopback do computador (`-p 127.0.0.1:<porta>:<porta>`).

## 4. Rotas de ação

O app NUNCA reenvia POST ou PUT sozinho depois de uma falha de rede: o resultado é incerto, o botão fica ocupado e a tela consulta o estado de novo.

### 4.1 `POST /api/projetos/novo`

Corpo `{ "nome": "meu-projeto", "guia_ia": true }`. O agente aplica `trim` e minúsculas ao `nome` (o app já manda um nome válido: a regra é `^[a-z0-9][a-z0-9-]{0,48}$`, de 1 a **49** caracteres, e o app ainda recusa hífen no fim). **`guia_ia`** (aditivo; a rota continua `api: 1`): booleano opcional. **Ausente (ou `null`) vale `true`, o comportamento de sempre**: o esqueleto leva o `CLAUDE.md`, e o compose e o README falam do Claude. `false` cria o projeto SEM `CLAUDE.md` e sem nada sobre o Claude, com os mesmos cinco arquivos que o app escreve ao criar um projeto no computador local (`docker-compose.yml` esqueleto com `services: {}`, `.gitignore`, `.env.example`, `STATUS.md`, `README.md`; o `GabaritoDoProjeto` do app e o `arquivosSemGuiaIa` do o agente têm o MESMO texto, conferido por `projetos.test.js` e pelo `GabaritoIgualAoDoAgenteTest`). Qualquer valor que não seja booleano: `400 {"erro":"guia_ia deve ser true ou false."}` e nada é criado.

Erros: `400 {"erro":"Informe um nome."}` (corpo inválido ou sem `nome`); `400 {"erro":"Nome inválido: use letras minúsculas, números e hífen (ex.: meu-projeto)."}`; `400 {"erro":"Esse nome é reservado pela infraestrutura do lab."}` (`infra`, `painel`, `lab`); `400 {"erro":"Já existe um projeto com esse nome."}`; `400 {"erro":"Não consegui criar a pasta: ..."}`. **`200 {"ok":true,"guia_ia":<o que valeu>}`** (o campo `guia_ia` da resposta é novo: um agente de antes devolve só `{"ok":true}`, e o app usa isso para avisar o usuário que pediu "sem guia" e o agente criou o `CLAUDE.md` mesmo assim). Cria a pasta, o esqueleto e a rede `<nome>_default` (com os rótulos do compose, falha de rede só vai para o log).

Web: modal Novo projeto na página inicial do front web; erro aparece no campo, sucesso recarrega a lista. App: `DialogoNovoProjeto` valida o nome a cada letra com a mesma regra, mostra a frase do 400 como TEXTO (nunca HTML) e, num prazo estourado (ou 5xx), NÃO repete o pedido: relê `GET /api/projetos` e diz "talvez já tenha sido criado, confira a lista" (ou "criado", se o projeto já aparece).

**Não há `POST /api/projetos/importar`**: importar (git clone, pasta do computador, pasta do servidor) e "Adicionar container" não passam pelo agente (ARQUITETURA 7.10 explica a escolha: o clone roda por SSH com a identidade do usuário, que o container do agente não tem).

### 4.2 `POST /api/projetos/:p/iniciar` | `parar` | `reiniciar`

`404 {"erro":"Projeto não existe."}`; `500 {"erro":"<saída de cada compose>"}` (inclui `Projeto sem docker-compose.yml.`); `200 {"ok":true,"saida":"[docker-compose.yml] ..."}`.

Roda `docker compose -p <name> -f <arquivo> up -d | restart | down` (sem `-v`) para CADA arquivo compose, em sequência, cada um com prazo de 10 min. Um projeto com 2 arquivos pode levar até 20 min no pior caso. Timeout do app: 12 min, e estourar o prazo NÃO marca falha: a tela passa a "em andamento" e consulta o estado até estabilizar.

Web: botões na página inicial do front web e a página do projeto; botão fica `aria-busy`; depois recarrega.

### 4.3 `POST /api/projetos/:p/containers/:c/reiniciar`

`400 {"erro":"Nome inválido."}`, `400 {"erro":"Container não pertence a este projeto."}` (não há 404 nesta rota), `500 {"erro":"<stderr>"}`, `200 {"ok":true}`. `docker restart` com prazo de 120 s. Web: botão por linha da tabela de containers na página do projeto.

### 4.4 `POST /api/projetos/:p/claude/abrir`

Idempotente: se já existe sessão, devolve `200` na hora.

- `400 {"erro":"Nome inválido."}`
- `409 {"erro":"Sessão pausada pelo limite (...). Use Retomar."}` (pausa gravada, ou o projeto já estoura teto ou freio: o agente grava a pausa na hora)
- `500 {"erro":"..."}` (inclui `Projeto não existe.`: aqui é 500, não 404)
- `200 {"ok":true,"url":"/sessao/<p>"}`

Antes do `docker run` o agente faz backup do Postgres do projeto, se houver (`pg_dump`, até 5 min), e depois do `docker run` espera cerca de 1,2 s o ttyd subir. Timeout do app: 6 min. Em `lab` abre a sessão raiz.

Web: botões na página inicial do front web, a página do projeto e o cartão "Abrir sessão" da página da sessão; abre `d.url` em nova aba. App: abre o canal PTY em vez de navegar, e atualiza DETALHE(p).

### 4.5 `POST /api/projetos/:p/claude/fechar`

`400` só para nome inválido. Sempre `200 {"ok":true}`, mesmo sem sessão. `docker stop` no container e fecha o proxy de Docker do projeto. Web: menu "Fechar sessão". App: fecha a aba de terminal depois do 200.

### 4.6 `POST /api/projetos/:p/claude/reabrir`

`404 {"erro":"Projeto não existe."}` (aceita `lab`), `409 {"erro":"Sessão pausada..."}` (checado ANTES de fechar, para não perder a sessão), `500`, `200 {"ok":true,"url":"/sessao/<p>"}`. Fecha e abre de novo com `claude --continue || claude`. Mesmo tempo da abertura (6 min). Web: modal de confirmação na página inicial do front web e a página do projeto.

### 4.7 `POST /api/projetos/:p/claude/teclas`

Checa a sessão ANTES de ler o corpo: `404 {"erro":"Sessão não está aberta."}`. Corpo, uma das formas:

- `{ "tecla": "Enter" }` com tecla em `Enter, Escape, Up, Down, Left, Right, Tab, BTab, C-c, Space, BSpace`. Se `tecla` vem, `texto` e `enter` são ignorados.
- `{ "texto": "mensagem", "enter": true }`. O agente troca `\r` e `\n` por espaço, remove caracteres de controle, apara; vazio ou acima de 4000 caracteres é recusado. Com `enter`, espera 250 ms e manda `Enter` num segundo `docker exec`.

Erros: `400 {"erro":"Corpo inválido."}`, `400 {"erro":"Tecla não permitida."}`, `400 {"erro":"Texto vazio ou longo demais."}`, `400 {"erro":"<stderr do docker>"}` (falha de Docker também é 400 aqui). `200 {"ok":true}`.

Web (a página da sessão): clicar numa opção da pergunta envia `{ "texto": "<numero>" }` (sem `enter`; se a opção só ficar marcada, o usuário confirma com o botão "Confirmar (Enter)"); botões Esc, Enter e Modo (`BTab`) enviam `{ "tecla": ... }`; o campo "Enviar mensagem" envia `{ "texto", "enter": true }` (Cmd ou Ctrl+Enter). Depois de cada envio, atualiza a tela em 400 ms. App: o `PortaoDeEntrada` serializa estes envios com a digitação no terminal.

### 4.8 `POST /api/projetos/:p/claude/codigo`

Corpo `{ "codigo": "..." }`. `404` sem sessão; `400 {"erro":"Código inválido — cole exatamente o que a página de login mostrou."}` (regex `^[A-Za-z0-9_#.-]{4,600}$` depois de `trim`); `500`; `200 {"ok":true}`. Digita o código e dá Enter no tmux. Web: formulário do alerta "Login do Claude pendente" na página inicial do front web, que também oferece abrir `login.url` (a lista só fornece a URL de login de projetos comuns). O app só abre a URL se for `https://claude.ai/...` ou `https://claude.com/...` (lista de permitidos).

### 4.9 `PUT /api/projetos/:p/limite`

Corpo `{ "teto_pct": 20 }` (1 a 100) ou `{ "teto_pct": null }` para remover. Aceita `lab`. Erros: `400 {"erro":"Nome inválido."}`, `404 {"erro":"Projeto não existe."}`, `400 {"erro":"Corpo inválido."}`, `400 {"erro":"Informe teto_pct (1 a 100) ou null para remover o teto."}` (campo ausente), `400 {"erro":"Informe teto_pct entre 1 e 100 (pontos percentuais da semana) ou null para remover o teto."}`. `200 {"ok":true,"limite":LIMITE}`. O agente reavalia na hora (`setImmediate`): um teto abaixo do consumo pausa a sessão sem esperar o ciclo de 60 s. O front só aceita inteiro de 1 a 100.

Web: modal "Definir limite", aberto no menu da página inicial do front web, no bloco da página do projeto e na lateral e no cartão da página da sessão. Depois do sucesso pede refresh da faixa de crédito (`avisoAtualizar`). App: DialogoLimite + refresh imediato de LIMITES e DETALHE(p).

### 4.10 `POST /api/projetos/:p/limite/retomar`

Corpo opcional `{ "liberar": false, "reabrir": true }` (padrões; ambos precisam ser booleanos, senão `400 {"erro":"\"liberar\" e \"reabrir\" precisam ser verdadeiro ou falso."}`).

Respostas:

- `200 {"ok":true,"limite":LIMITE,"sessao":{"aberta":true,"container":"lab-claude-<p>","url":"/sessao/<p>"}}` (`sessao` é `null` com `reabrir:false`). Se já havia sessão aberta, não reabre e devolve a existente.
- `409 {"erro":"Ainda acima do limite (...). Para retomar assim mesmo, use \"liberar\": ...","usado_pp":22.1,"teto_pct":20,"pct_conta":93.0,"freio_pct":90}` quando o projeto estouraria de novo. Os quatro números alimentam o diálogo "Ainda acima do limite" (`teto_pct` e `freio_pct` podem ser `null`).
- `409 {"erro":"..."}` só com `erro`: o projeto NÃO estava pausado mas já estoura teto ou freio (a checagem de Retomar só cobre quem tem pausa gravada), então a reabertura é recusada e o agente grava a pausa na hora. Não é falha de abertura.
- `500 {"erro":"Limite retomado, mas não consegui reabrir a sessão: ..."}` (a pausa já foi removida; o botão Abrir funciona).
- `400`/`404` de nome e corpo, como em 4.9.

Com `liberar:true` o projeto fica isento de teto e freio até a semana do plano virar.

Web: `UI.limite.retomar`: manda `{ liberar: <bool>, reabrir: true }`. Em `409`, abre o modal com dois caminhos: "Aumentar limite" (abre Definir limite e, ao salvar, tenta Retomar de novo) e "Liberar até a semana virar" (repete com `liberar:true`). O app reproduz exatamente esse fluxo. Timeout 6 min por causa da reabertura.

### 4.11 `PUT /api/limites/freio`

Corpo `{ "pct": 90 }` (50 a 100) ou `{ "pct": null }` para desligar. `400 {"erro":"Corpo inválido."}`, `400 {"erro":"Informe pct (50 a 100) ou null para desligar o freio."}`, `400 {"erro":"Informe pct entre 50 e 100 (% da semana da conta) ou null para desligar o freio."}`. `200 {"ok":true,"freio":{"pct":90,"ativo":true}}`. Reavalia na hora. Web: modal Freio global na página de consumo (ligar, ajustar, desligar). App: DialogoFreio.

## 5. Rotas que o app não usa

| Rota | Motivo |
|---|---|
| `POST /api/diag` | só grava no log do agente o diagnóstico de cópia do navegador. |
| `GET /terminal/:p/*` e WebSocket (`2322`) | proxy do ttyd. O terminal do app é `ssh exec` com PTY rodando `docker exec -it lab-claude-<p> tmux -u attach -t work`. |
| `GET /`, `/projeto/:p`, `/sessao/:p`, `/consumo`, `/servidor`, estáticos | o front web. Ficam intactos. |

## 6. Como o app se identifica ao agente

### 6.1 O que o usuário precisa saber

- O agente é opcional. Sem ele o app funciona (terminal, arquivos e segurança) e nada desta seção se aplica.
- Quando o app instala o agente, ele gera uma credencial só para este computador, a registra no servidor por SSH (você não digita nada) e a guarda no cofre cifrado do app. Ela nunca vai em endereço (URL), em log nem em mensagem de erro.
- O app guarda o acesso ao agente no cofre cifrado do computador. Se o servidor pedir confirmação em dois passos, o app pergunta o código a você.
- "Remover servidor" pode também revogar essa credencial no servidor (é uma opção da janela).
- Como o agente confere a credencial por dentro é assunto do agente e não faz parte deste contrato.

### 6.2 O que o app faz com as respostas

- `401`: credencial recusada. A conexão mostra "Token recusado" e o app oferece registrar de novo.
- Se o servidor pedir confirmação em dois passos, o app pergunta o código ao usuário e repete a chamada. Ligar ou desligar isso é pelo menu Servidor.

### 6.3 A Cloudflare não muda este contrato

A Cloudflare dentro do app (ARQUITETURA 7.11) **não acrescenta rota, campo nem comportamento ao agente**: o app fala com a API da Cloudflare DIRETO do computador do usuário (`https://api.cloudflare.com/client/v4`, com o token de API dele, guardado no cofre e enviado só nesse cabeçalho `Authorization`, nunca ao servidor nem ao agente), e com o servidor só por SSH (SFTP e linhas de comando FIXAS: `docker inspect`, `docker exec ... cloudflared tunnel ... ingress validate`, `docker restart` do container do cloudflared, `docker compose config`). A única chamada ao agente continua sendo a de antes (`POST /api/projetos/<nome>/iniciar`, o mesmo botão Iniciar, para recriar o projeto depois de publicar). A API externa que o app usa, e só ela:

| Chamada | Para quê | Leitura ou escrita |
|---|---|---|
| `GET /user/tokens/verify` | conferir o token ao conectar e no botão "Conferir o token" | leitura |
| `GET /accounts`, `GET /zones` | listar contas e domínios na tela Conectar | leitura |
| `GET /accounts/{conta}/cfd_tunnel` | listar os túneis (e o `config_src`: arquivo ou Cloudflare) | leitura |
| `GET /accounts/{conta}/cfd_tunnel/{túnel}/configurations` | ler as regras do túnel gerido pela API | leitura |
| `GET /zones/{zona}/dns_records` (filtro `comment.startswith=lab-desktop:`) | achar o que o app criou e conferir se o nome já existe | leitura |
| `POST /zones/{zona}/dns_records` | criar o CNAME do endereço (só depois do clique em Publicar) | **escrita** |
| `DELETE /zones/{zona}/dns_records/{id}` | apagar o CNAME que o app criou (Remover, ou desfazer de uma publicação) | **escrita** |
| `PUT /accounts/{conta}/cfd_tunnel/{túnel}/configurations` | gravar a regra no túnel gerido pela API (só depois do clique) | **escrita** |

Permissões mínimas do token: Zona > Zona > Ler, Zona > DNS > Editar (só na zona) e, só para o túnel pela API, Conta > Cloudflare Tunnel > Editar. Erros 429 esperam o `Retry-After`; 5xx e prazo estourado numa escrita NÃO são repetidos (o resultado é incerto e o app relê antes de concluir). Os testes usam uma Cloudflare FALSA em `HttpServer` do JDK (a URL base é a propriedade `labdesktop.cloudflare.url`); nenhuma chamada foi feita à Cloudflare real.

## 7. Notas para quem implementa o cliente

- Fixtures: `scripts/capturar-fixtures.sh` grava em `src/test/resources/lab-real/` uma resposta real de cada rota de leitura (3.2 a 3.11, com um projeto comum, o `lab` e um projeto sem sessão). Contêm nomes e descrições reais de projetos do lab: por isso `src/test/resources/lab-real/` está no `.gitignore` e não deve ser publicado.
- 404 `Sessão não está aberta.` e 409 de pausa são fluxo normal, não erro de tela.
- Qualquer `ErroRede` não é erro de tela: o Sondador pergunta ao estado da conexão (reconectando, agente fora) e mantém o último snapshot com a faixa "sem conexão desde HH:MM".
- Dado vindo do servidor é não confiável (descrição de projeto, README, branch, nome de container, texto da tela, `login.url`): texto sem HTML, controles e bidi removidos, URLs só por lista de permitidos.
- Não existe `GET /api/projetos/:p/login`: a URL de login só vem em `projetos[].login.url`.
- Se o servidor pedir confirmação em dois passos, o `ClienteApi` pede o código e repete a chamada uma única vez; uma segunda recusa é normal, sem laço. Um `401` sem esse pedido continua levando a `TOKEN_RECUSADO`.
