package br.com.labdesktop.teste;

import br.com.labdesktop.teste.AgenteFalso.Pedido;
import br.com.labdesktop.teste.AgenteFalso.Resposta;
import br.com.labdesktop.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * O agente do painel inteiro, de mentira: HTTP (as rotas de leitura com os JSONs do mock do front) mais a LINHA
 * DE COMANDO do agente que o app roda por SSH, compartilhando o
 * mesmo estado. Assim os testes de {@code ServidorLab} percorrem o caminho real: registrar o token por SSH faz o
 * HTTP passar a aceitá-lo; confirmar o 2FA por SSH faz o pedido de código sumir.
 */
public final class AgenteDoLab implements AutoCloseable {

    public static final String CODIGO_CERTO = "123456";
    public static final String SEGREDO = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    public final AgenteFalso http;
    /** hash do token -> nome do dispositivo. */
    public final Map<String, String> tokens = new ConcurrentHashMap<>();
    public final Set<String> dispositivosConfirmados = ConcurrentHashMap.newKeySet();
    public final AtomicInteger consultasDeProjetos = new AtomicInteger();
    public final AtomicInteger chamadasDeLimites = new AtomicInteger();
    /** Quantas vezes o app consultou {@code /api/servidor} e {@code /api/servidor/detalhe} (a prova de "só com a tela visível"). */
    public final AtomicInteger consultasDeServidor = new AtomicInteger();
    public final AtomicInteger consultasDeDetalhe = new AtomicInteger();
    /** O agente tem o histórico de longo prazo: diz {@code recursos.historico} e responde {@code /api/servidor/historico}. Desligado por padrão (o agente de antes). */
    public volatile boolean historico;
    /** As janelas pedidas a {@code /api/servidor/historico}, na ordem (a prova de "só com a tela visível" e de "só se o agente declara"). */
    public final List<String> janelasPedidas = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** As primeiras células sem dado, por janela (as janelas grandes de um agente recém-ligado). */
    public volatile int semDadoNoComeco;
    /** Quanto o agente demora para responder o histórico (prova de "carregando" e de troca de janela no meio). */
    public volatile long atrasoDoHistoricoMs;
    /** Quando verdadeiro o histórico responde 500 (o agente com defeito): o app mantém o que tem e diz que não conseguiu. */
    public volatile boolean historicoComDefeito;
    /** O relógio do agente (os testes fixam). */
    public volatile java.time.Instant agoraDoAgente;
    /** O registro do sistema que o servidor SSH devolve a {@code journalctl}; {@code null}: o servidor de teste não tem {@code journalctl}. */
    public volatile String saidaDoJournal = "2026-10-28T11:55:01-0300 lab sshd[1203]: Accepted publickey for labuser from 10.0.0.9 port 51234 ssh2\n"
        + "2026-10-28T11:56:10-0300 lab kernel: usb 1-1: new high-speed USB device number 5\n"
        + "2026-10-28T11:58:00-0300 lab dockerd[900]: level=warning msg=\"Your kernel does not support swap limit capabilities\"\n"
        + "2026-10-28T11:59:30-0300 lab sshd[1250]: Failed password for invalid user admin from 203.0.113.9 port 4000 ssh2\n";
    /** O journalctl não deixa o usuário ver o que é dos outros (não está no grupo systemd-journal): a dica no erro padrão e nada no syslog. */
    public volatile boolean semPermissaoParaORegistro;
    /** Os comandos de registro que chegaram por SSH, na ordem. */
    public final List<String> comandosDeRegistro = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Os comandos de segurança (somente leitura) que o app mandou por SSH. */
    public final List<String> comandosDeSeguranca = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** O usuário não tem sudo sem senha: os comandos de segurança com sudo falham como no servidor real. */
    public volatile boolean segurancaSemSudo;
    /** Tudo o que o servidor SSH de teste recebeu como linha de comando e stdin, na ordem. */
    public final List<String> comandosCli = new java.util.concurrent.CopyOnWriteArrayList<>();
    public final List<String> entradasCli = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * O que o agente sabe de cada projeto (a página do projeto): o detalhe ({@code GET /api/projetos/:p}), o consumo
     * ({@code /api/tokens/:p}) e os logs por container. Os testes trocam o conteúdo e conferem o que chegou. Por padrão só existe
     * o projeto sintético {@code orbita} (nada aqui vem de um servidor real).
     */
    public final Map<String, String> detalhes = new ConcurrentHashMap<>();
    public final Map<String, String> consumos = new ConcurrentHashMap<>();
    /** Chave {@code projeto/container}; o container que não está aqui responde 400 como o agente real. */
    public final Map<String, String> logs = new ConcurrentHashMap<>();
    /** O JSON de {@code claude/tela} do projeto; ausente, o agente diz "Sessão não está aberta." (404). */
    public final Map<String, String> telas = new ConcurrentHashMap<>();
    /** Os corpos que chegaram em {@code claude/teclas}, na ordem. */
    public final List<String> teclasRecebidas = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Os containers que o app mandou reiniciar ({@code projeto/container}), na ordem. */
    public final List<String> containersReiniciados = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Os caminhos de projeto consultados ({@code GET}), com a query, para os testes de "só com a aba visível". */
    public final List<String> leiturasDeProjeto = new java.util.concurrent.CopyOnWriteArrayList<>();

    public volatile boolean fora;
    public volatile boolean exigeToken;
    public volatile boolean totpPendente;
    public volatile boolean totpAtivo;
    /** Se verdadeiro, o agente não reconhece nenhum token (para o teste de "token_nao_validou"). */
    public volatile boolean recusaTodosOsTokens;
    /** Agente de ANTES do app nativo: não tem {@code /api/versao} (cai no 404 em texto puro das rotas desconhecidas). */
    /** O painel de antes do app nativo: sem {@code GET /api/versao} (404) e sem o acesso do app (o node falha). */
    public volatile boolean agenteAntigo;
    /**
     * {@code null}: o agente responde {@code /api/versao} como o de antes dos recursos (sem {@code perfil} nem {@code recursos}).
     * {@code "lab"} ou {@code "monitor"}: responde com os campos novos; o monitor só responde as rotas de leitura da máquina e dá 404
     * (JSON do perfil) em todo o resto, como o agente de verdade.
     */
    public volatile String perfil;
    /** O que a leitura "o agente está instalado?" (por SSH) responde: {@code ausente}, {@code sem-docker}, {@code sem-permissao}, {@code existe:running}... */
    public volatile String diagnostico = "existe:running";
    /** As leituras de diagnóstico que chegaram por SSH (fora de {@link #comandosCli}, que são só os comandos do agente). */
    public final List<String> comandosDeDiagnostico = new java.util.concurrent.CopyOnWriteArrayList<>();
    /**
     * Respostas sob medida de uma rota (depois da autenticação e do 2FA, antes das rotas padrão): devolve {@code null}
     * para deixar a rota padrão responder. Os testes de tela programam aqui o 409 de abrir sessão, o Retomar que passa
     * com {@code liberar:true} e afins.
     */
    public volatile java.util.function.Function<Pedido, Resposta> excecoes = p -> null;

    public AgenteDoLab() throws IOException {
        http = new AgenteFalso();
        http.responder(this::atender);
        detalhes.put("orbita", br.com.labdesktop.api.FixturesDeApi.lerRecursoDoProjeto("detalhe-sintetico.json"));
        consumos.put("orbita", br.com.labdesktop.api.FixturesDeApi.lerRecursoDoProjeto("consumo-sintetico.json"));
        String registro = br.com.labdesktop.api.FixturesDeApi.lerRecursoDoProjeto("logs-sintetico.txt");
        for (String c : List.of("orbita-web-1", "orbita-fila-1", "orbita-banco-1")) {
            logs.put("orbita/" + c, registro);
        }
        logs.put("orbita/orbita-cache-1", ""); // container que nunca escreveu nada
    }

    public int porta() {
        return http.porta();
    }

    public static String hashDoCabecalho(String authorization) {
        try {
            String texto = authorization.substring("Bearer ".length());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // HTTP
    // ---------------------------------------------------------------------------------------------------

    /** Quanto o agente demora para responder as LEITURAS de projetos e do servidor (a conexão e os limites respondem na hora): prova que o "carregando" aparece antes da resposta. */
    public volatile long atrasoDasLeiturasMs;

    private Resposta atender(Pedido p) {
        Resposta r = atenderSemAtraso(p);
        String caminho = p.caminho();
        if (atrasoDasLeiturasMs > 0 && "GET".equals(p.metodo()) && r.status() == 200
            && (caminho.startsWith("/api/projetos") || caminho.startsWith("/api/servidor"))) {
            return Resposta.comAtraso(r, atrasoDasLeiturasMs);
        }
        return r;
    }

    private Resposta atenderSemAtraso(Pedido p) {
        if (fora) {
            return Resposta.semResposta();
        }
        String auth = p.cabecalho("Authorization");
        String dispositivo = null;
        if (auth != null) {
            dispositivo = recusaTodosOsTokens ? null : tokens.get(hashDoCabecalho(auth));
            if (dispositivo == null) {
                return Resposta.json(401, "{\"erro\":\"Token ausente ou inválido.\"}");
            }
        } else if (exigeToken) {
            return Resposta.json(401, "{\"erro\":\"Token ausente ou inválido.\"}");
        }
        if (dispositivo != null && totpAtivo && !dispositivosConfirmados.contains(dispositivo)) {
            if ("POST".equals(p.metodo()) && "/api/acesso/2fa".equals(p.caminho())) {
                return confirmar2fa(p, dispositivo);
            }
            return Resposta.json(401, "{\"erro\":\"Confirme o código do autenticador.\",\"codigo\":\"totp_requerido\"}");
        }
        if ("POST".equals(p.metodo()) && "/api/acesso/2fa".equals(p.caminho())) {
            return Resposta.json(409, "{\"erro\":\"O 2FA não está ativo neste servidor.\",\"codigo\":\"totp_nao_configurado\"}");
        }
        Resposta sob = excecoes.apply(p);
        if (sob != null) {
            return sob;
        }
        String caminho = p.caminho();
        if ("monitor".equals(perfil) && !List.of("/api/versao", "/api/servidor", "/api/servidor/detalhe", "/api/servidor/historico").contains(caminho)) {
            return Resposta.json(404, "{\"erro\":\"Esta rota não existe no perfil monitor do agente (só leitura do servidor).\",\"perfil\":\"monitor\"}");
        }
        if (agenteAntigo && "/api/versao".equals(caminho)) {
            return Resposta.texto(404, "não encontrado");
        }
        if ("/api/versao".equals(caminho)) {
            String totp = totpAtivo ? "ativo" : totpPendente ? "pendente" : "desligado";
            String perfilEfetivo = perfil == null && historico ? "lab" : perfil;
            String comHistorico = historico ? ",\"historico\":true" : "";
            String perfilJson = perfilEfetivo == null ? "" : ",\"perfil\":\"" + perfilEfetivo + "\",\"recursos\":"
                + ("monitor".equals(perfilEfetivo) ? "{\"claude\":false,\"projetos\":false,\"monitor\":true" + comHistorico + "}"
                    : "{\"claude\":true,\"projetos\":true,\"monitor\":true" + comHistorico + "}");
            return Resposta.json(200, "{\"api\":1,\"painel\":\"v22.5.0\",\"exigeToken\":" + exigeToken + ",\"cliente\":"
                + (dispositivo == null ? "null" : "\"" + dispositivo + "\"") + ",\"totp\":\"" + totp + "\"" + perfilJson + "}");
        }
        if ("/api/limites".equals(caminho)) {
            chamadasDeLimites.incrementAndGet();
            return Resposta.json(200, MocksDoFront.ler("limites.json"));
        }
        if ("/api/projetos".equals(caminho)) {
            consultasDeProjetos.incrementAndGet();
            return Resposta.json(200, MocksDoFront.ler("projetos.json"));
        }
        if ("/api/projetos/lab".equals(caminho)) {
            return Resposta.json(200, MocksDoFront.ler("projetos/lab.json"));
        }
        if ("/api/projetos/app-loja".equals(caminho)) {
            return Resposta.json(200, MocksDoFront.ler("projetos/app-loja.json"));
        }
        if ("/api/servidor".equals(caminho)) {
            consultasDeServidor.incrementAndGet();
            return Resposta.json(200, MocksDoFront.ler("servidor.json"));
        }
        if ("/api/servidor/historico".equals(caminho) && historico) {
            return responderHistorico(p);
        }
        if ("/api/servidor/detalhe".equals(caminho)) {
            consultasDeDetalhe.incrementAndGet();
            return Resposta.json(200, br.com.labdesktop.api.FixturesDeApi.lerRecurso("servidor-detalhe-sintetico.json"));
        }
        Resposta deProjeto = rotasDoProjeto(p, caminho);
        if (deProjeto != null) {
            return deProjeto;
        }
        if (caminho.endsWith("/limite/retomar")) {
            return Resposta.json(409, MocksDoFront.ler("projetos/app-loja-retomar.POST.json"));
        }
        if ("POST".equals(p.metodo()) || "PUT".equals(p.metodo())) {
            return Resposta.json(200, "{\"ok\":true,\"saida\":\"[docker-compose.yml] feito\",\"url\":\"/sessao/x\"}");
        }
        return Resposta.json(404, "{\"erro\":\"Projeto não existe.\"}");
    }

    private Resposta responderHistorico(Pedido p) {
        String codigo = parametro(p.caminhoComQuery(), "janela");
        java.util.Optional<br.com.labdesktop.tendencias.JanelaDeTempo> j = br.com.labdesktop.tendencias.JanelaDeTempo.doCodigo(codigo);
        if (j.isEmpty()) {
            return Resposta.json(400, "{\"erro\":\"Informe uma janela: janela=10m, 1h, 1d, 7d ou 30d.\",\"validas\":[\"10m\",\"1h\",\"1d\",\"7d\",\"30d\"]}");
        }
        janelasPedidas.add(codigo);
        if (historicoComDefeito) {
            return Resposta.json(500, "{\"erro\":\"Defeito do agente de teste.\"}");
        }
        java.time.Instant agora = agoraDoAgente != null ? agoraDoAgente : java.time.Instant.now();
        int vazio = j.get().duracao().compareTo(java.time.Duration.ofHours(1)) > 0 ? semDadoNoComeco : 0;
        Resposta r = Resposta.json(200, HistoricoSintetico.json(j.get(), agora, vazio));
        return atrasoDoHistoricoMs > 0 ? Resposta.comAtraso(r, atrasoDoHistoricoMs) : r;
    }

    private static final java.util.regex.Pattern ROTA_DE_PROJETO = java.util.regex.Pattern.compile(
        "^/api/projetos/([a-z0-9-]+)(/logs|/claude/tela|/claude/teclas|/containers/([A-Za-z0-9_.-]+)/reiniciar)?$");
    private static final java.util.regex.Pattern ROTA_DE_CONSUMO = java.util.regex.Pattern.compile("^/api/tokens/([a-z0-9-]+)$");

    /** As rotas da página do projeto (detalhe, consumo, logs, tela, teclas, reiniciar container); {@code null} se não é uma. */
    private Resposta rotasDoProjeto(Pedido p, String caminho) {
        var c = ROTA_DE_CONSUMO.matcher(caminho);
        if (c.matches() && "GET".equals(p.metodo())) {
            leiturasDeProjeto.add(p.caminhoComQuery());
            String corpo = consumos.get(c.group(1));
            return corpo == null ? Resposta.json(404, "{\"erro\":\"Projeto não existe.\"}") : Resposta.json(200, corpo);
        }
        var m = ROTA_DE_PROJETO.matcher(caminho);
        if (!m.matches() || !detalhes.containsKey(m.group(1))) {
            return null;
        }
        String projeto = m.group(1);
        String sufixo = m.group(2);
        if (sufixo == null) {
            if (!"GET".equals(p.metodo())) {
                return null;
            }
            leiturasDeProjeto.add(p.caminhoComQuery());
            return Resposta.json(200, detalhes.get(projeto));
        }
        switch (sufixo) {
            case "/logs" -> {
                leiturasDeProjeto.add(p.caminhoComQuery());
                String container = parametro(p.caminhoComQuery(), "container");
                String texto = logs.get(projeto + "/" + container);
                return texto == null ? Resposta.json(400, "{\"erro\":\"Container não pertence a este projeto.\"}")
                    : Resposta.texto(200, texto);
            }
            case "/claude/tela" -> {
                leiturasDeProjeto.add(p.caminhoComQuery());
                String tela = telas.get(projeto);
                return tela == null ? Resposta.json(404, "{\"erro\":\"Sessão não está aberta.\"}") : Resposta.json(200, tela);
            }
            case "/claude/teclas" -> {
                if (!telas.containsKey(projeto)) {
                    return Resposta.json(404, "{\"erro\":\"Sessão não está aberta.\"}");
                }
                teclasRecebidas.add(p.corpo());
                return Resposta.json(200, "{\"ok\":true}");
            }
            default -> {
                String container = m.group(3);
                if (!logs.containsKey(projeto + "/" + container)) {
                    return Resposta.json(400, "{\"erro\":\"Container não pertence a este projeto.\"}");
                }
                containersReiniciados.add(projeto + "/" + container);
                return Resposta.json(200, "{\"ok\":true}");
            }
        }
    }

    private static String parametro(String caminhoComQuery, String nome) {
        int q = caminhoComQuery.indexOf('?');
        if (q < 0) {
            return null;
        }
        for (String par : caminhoComQuery.substring(q + 1).split("&")) {
            int i = par.indexOf('=');
            if (i > 0 && par.substring(0, i).equals(nome)) {
                return par.substring(i + 1);
            }
        }
        return null;
    }

    private Resposta confirmar2fa(Pedido p, String dispositivo) {
        try {
            JsonNode corpo = Json.mapper().readTree(p.corpo());
            if (CODIGO_CERTO.equals(corpo.path("codigo").asText())) {
                dispositivosConfirmados.add(dispositivo);
                return Resposta.json(200, "{\"ok\":true,\"ativado_ate\":\"2026-11-19T12:00:00.000Z\"}");
            }
        } catch (IOException e) {
            return Resposta.json(400, "{\"erro\":\"Corpo inválido.\"}");
        }
        return Resposta.json(401, "{\"erro\":\"Código incorreto ou já usado.\",\"codigo\":\"totp_incorreto\"}");
    }

    // ---------------------------------------------------------------------------------------------------
    // Linha de comando do agente, por SSH exec
    // ---------------------------------------------------------------------------------------------------

    /** O manipulador que o {@link ServidorSshDeTeste} usa para os comandos do agente. */
    public ServidorSshDeTeste.Manipulador comandosPorSsh() {
        return (linha, stdin, saida) -> {
            if (linha.startsWith("journalctl -r -n 200 ") || linha.startsWith("tail -c 60000 /var/log/")) {
                comandosDeRegistro.add(linha);
                return responderRegistro(linha, saida);
            }
            if (ehComandoDeSeguranca(linha)) {
                comandosDeSeguranca.add(linha);
                return responderSeguranca(linha, saida);
            }
            if (linha.startsWith("sh -c 'command -v docker")) { // "o agente está instalado?": só leitura
                comandosDeDiagnostico.add(linha);
                saida.append(diagnostico).append('\n');
                return 0;
            }
            comandosCli.add(linha);
            entradasCli.add(stdin);
            if (agenteAntigo) {
                // O painel de antes do app nativo não tem o acesso do app: o node falha com a pilha crua (o que o usuário veria).
                saida.append("node:internal/modules/cjs/loader:1228\n  throw err;\n  ^\n\nError: Cannot find module "
                    + "'/srv/painel/acesso.js'\nRequire stack:\n- /srv/painel/[eval]\n");
                return 1;
            }
            String comando = linha.substring(linha.lastIndexOf(' ') + 1);
            String[] linhas = stdin.split("\\R");
            try {
                switch (comando) {
                    case "token-registrar" -> {
                        JsonNode e = Json.mapper().readTree(linhas[0]);
                        String hash = e.path("hash").asText();
                        String nome = e.path("nome_do_dispositivo").asText();
                        if (tokens.containsValue(nome) && !nome.equals(tokens.get(hash))) {
                            return falha(saida, "token_conflito", "Já existe um token com esse nome.");
                        }
                        boolean ja = nome.equals(tokens.get(hash));
                        tokens.put(hash, nome);
                        saida.append("{\"ok\":true,\"ja_existia\":").append(ja).append("}\n");
                        return 0;
                    }
                    case "token-revogar" -> {
                        // Como o agente real: nome que não está registrado é falha com codigo, não sucesso.
                        if (!tokens.values().removeIf(linhas[0]::equals)) {
                            return falha(saida, "token_inexistente", "Não há token com esse nome.");
                        }
                        saida.append("{\"ok\":true}\n");
                        return 0;
                    }
                    case "totp-iniciar" -> {
                        if (totpAtivo) {
                            return falha(saida, "totp_ja_ativo", "O 2FA já está ativo neste servidor.");
                        }
                        totpPendente = true;
                        saida.append("{\"ok\":true,\"segredo\":\"").append(SEGREDO)
                            .append("\",\"uri\":\"otpauth://totp/Painel%20do%20Lab:lab?secret=").append(SEGREDO)
                            .append("&issuer=Painel%20do%20Lab\"}\n");
                        return 0;
                    }
                    case "totp-confirmar" -> {
                        if (!totpPendente) {
                            return falha(saida, "totp_nao_pendente", "Não há 2FA pendente.");
                        }
                        if (!CODIGO_CERTO.equals(linhas[0])) {
                            return falha(saida, "totp_incorreto", "Código incorreto ou já usado.");
                        }
                        dispositivosConfirmados.clear();
                        String nome = linhas.length > 1 ? linhas[1] : "";
                        totpAtivo = true;
                        totpPendente = false;
                        if (!nome.isEmpty()) {
                            dispositivosConfirmados.add(nome);
                            saida.append("{\"ok\":true,\"ativado_ate\":\"2026-11-19T12:00:00.000Z\"}\n");
                        } else {
                            saida.append("{\"ok\":true,\"ativado_ate\":null}\n");
                        }
                        return 0;
                    }
                    case "totp-desativar" -> {
                        if (!totpAtivo) {
                            return falha(saida, "totp_nao_ativo", "O 2FA não está ativo neste servidor.");
                        }
                        if (!CODIGO_CERTO.equals(linhas[0])) {
                            return falha(saida, "totp_incorreto", "Código incorreto ou já usado.");
                        }
                        totpAtivo = false;
                        dispositivosConfirmados.clear();
                        saida.append("{\"ok\":true}\n");
                        return 0;
                    }
                    default -> {
                        return falha(saida, "comando_desconhecido", "Comando desconhecido.");
                    }
                }
            } catch (IOException e) {
                return falha(saida, "entrada_invalida", "Entrada inválida.");
            }
        };
    }

    /** As saídas do registro geral do servidor (leitura: nunca sudo). */
    private int responderRegistro(String linha, StringBuilder saida) {
        if (linha.startsWith("journalctl")) {
            if (saidaDoJournal == null) {
                saida.append(ServidorSshDeTeste.PREFIXO_STDERR).append("sh: 1: journalctl: not found\n");
                return 127;
            }
            if (semPermissaoParaORegistro) {
                saida.append("-- No entries --\n").append(ServidorSshDeTeste.PREFIXO_STDERR)
                    .append("Hint: You are currently not seeing messages from other users and the system.\n      Users in groups 'adm', 'systemd-journal' can see all messages.\n");
                return 0;
            }
            saida.append(saidaDoJournal);
            return 0;
        }
        if (semPermissaoParaORegistro) {
            saida.append(ServidorSshDeTeste.PREFIXO_STDERR).append("tail: cannot open '").append(linha.substring(linha.lastIndexOf(' ') + 1)).append("' for reading: Permission denied\n");
            return 1;
        }
        saida.append(ServidorSshDeTeste.PREFIXO_STDERR).append("tail: cannot open '").append(linha.substring(linha.lastIndexOf(' ') + 1)).append("' for reading: No such file or directory\n");
        return 1;
    }

    private static boolean ehComandoDeSeguranca(String linha) {
        return (linha.startsWith("sudo -n ") && !linha.startsWith("sudo -n docker ")) || linha.startsWith("ss ") || linha.startsWith("systemctl ");
    }

    /** As saídas SINTÉTICAS dos comandos de segurança (o registro tem um ataque simulado). */
    private int responderSeguranca(String linha, StringBuilder saida) {
        if (segurancaSemSudo && linha.startsWith("sudo -n ")) {
            saida.append(ServidorSshDeTeste.PREFIXO_STDERR).append("sudo: a password is required\n");
            return 1;
        }
        java.time.Instant agora = java.time.Instant.now();
        if (linha.contains("journalctl")) {
            saida.append(SaidasDeSeguranca.registroComAtaque(agora));
            return 0;
        }
        if (linha.startsWith("ss ")) {
            saida.append(SaidasDeSeguranca.SS);
            return 0;
        }
        if (linha.contains("nft list")) {
            saida.append(SaidasDeSeguranca.NFT);
            return 0;
        }
        if (linha.contains("sshd -T")) {
            saida.append(SaidasDeSeguranca.SSHD_SEGURO);
            return 0;
        }
        if (linha.startsWith("systemctl is-active")) {
            saida.append("inactive\n");
            return 3;
        }
        return 127;
    }

    private static int falha(StringBuilder saida, String codigo, String erro) {
        saida.append("{\"ok\":false,\"codigo\":\"").append(codigo).append("\",\"erro\":\"").append(erro).append("\"}\n");
        return 1;
    }

    @Override
    public void close() {
        http.close();
    }
}
