package br.com.labdesktop.api;

import br.com.labdesktop.Versao;
import br.com.labdesktop.api.ErroApi.Conflito;
import br.com.labdesktop.api.ErroApi.ErroRede;
import br.com.labdesktop.api.ErroApi.NaoAutorizado;
import br.com.labdesktop.api.ErroApi.NaoEncontrado;
import br.com.labdesktop.api.ErroApi.RespostaInvalida;
import br.com.labdesktop.api.ErroApi.ServidorErro;
import br.com.labdesktop.api.modelo.Ativacao2fa;
import br.com.labdesktop.api.modelo.Detalhe;
import br.com.labdesktop.api.modelo.DetalheProjeto;
import br.com.labdesktop.api.modelo.DetalheRaiz;
import br.com.labdesktop.api.modelo.DetalheServidor;
import br.com.labdesktop.api.modelo.InfoServidor;
import br.com.labdesktop.api.modelo.LimitesConta;
import br.com.labdesktop.api.modelo.ListaProjetos;
import br.com.labdesktop.api.modelo.RespostaLimite;
import br.com.labdesktop.api.modelo.RespostaNovoProjeto;
import br.com.labdesktop.api.modelo.RespostaRetomar;
import br.com.labdesktop.api.modelo.ResultadoAcao;
import br.com.labdesktop.api.modelo.SessaoAberta;
import br.com.labdesktop.api.modelo.TelaClaude;
import br.com.labdesktop.api.modelo.TokensDoProjeto;
import br.com.labdesktop.api.modelo.VersaoAgente;
import br.com.labdesktop.cofre.TokenAgente;
import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.seguranca.ValidadorDeNomes;
import br.com.labdesktop.util.Agendador;
import br.com.labdesktop.util.Fundo;
import br.com.labdesktop.util.LogDeRequisicoes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * O cliente HTTP do agente do painel (ARQUITETURA 6.5 e CONTRATO-API): um método por rota, BLOQUEANTE, sempre
 * fora da EDT (chame de {@code Fundo.ioVirtual()}).
 *
 * <h2>Por geração de túnel</h2>
 * Um {@code ClienteApi} nasce para UMA geração do túnel ({@code http://127.0.0.1:<porta>}) e é descartado com
 * {@link #close()} ao reconectar, para nunca reaproveitar keep-alive de uma porta morta. O token é montado uma
 * vez, no construtor.
 *
 * <h2>Regras do cliente HTTP</h2>
 * <ul>
 *   <li>HTTP/1.1 EXPLÍCITO. O padrão do JDK tenta {@code Upgrade: h2c} e o handler de upgrade do agente destrói
 *       o socket de qualquer upgrade que não seja {@code /terminal/...}: a chamada falha com "header parser
 *       received no bytes". Um teste prova que nenhum {@code Upgrade} sai.</li>
 *   <li>Sem proxy do sistema, sem seguir redirect, conexão em 3 s. {@code Host} sai sozinho da URI;
 *       nenhum {@code Origin}.</li>
 *   <li>{@code Authorization}, {@code Accept}, {@code User-Agent: LabDesktop/<versão>}. O token
 *       nunca vai em URL, log ou mensagem de erro.</li>
 *   <li>Resposta lida por fluxo com TETO (JSON 2 MiB) e com prazo que cobre também o corpo.</li>
 *   <li>POST e PUT nunca são reenviados sozinhos (o resultado de uma falha de rede é incerto).</li>
 * </ul>
 *
 * <h2>2FA</h2>
 * O pedido de código de confirmação é o ÚNICO caso em que a chamada é repetida: ele vem antes de
 * qualquer rota, então repetir é seguro até para POST e PUT. O {@link PedidoDeCodigo2fa} pergunta o código, o
 * cliente envia o código e refaz a chamada UMA vez; uma segunda 401 vira
 * {@link NaoAutorizado} comum (sem laço). Vários tópicos que levem 401 ao mesmo tempo esperam o MESMO pedido
 * (um único diálogo) e todos repetem depois dele.
 */
public final class ClienteApi implements AutoCloseable {

    /** O formato do código de login que o agente aceita (CONTRATO-API 4.8). */
    private static final java.util.regex.Pattern CODIGO_DE_LOGIN = java.util.regex.Pattern.compile("^[A-Za-z0-9_#.-]{4,600}$");

    /** O agente limita {@code linhas} dos logs a 50..2000 (CONTRATO-API 3.9). */
    private static final int LINHAS_MINIMAS_DE_LOG = 50;
    private static final int LINHAS_MAXIMAS_DE_LOG = 2000;

    private static final Logger LOG = LoggerFactory.getLogger(ClienteApi.class);
    private static final String CODIGO_TOTP_INCORRETO = "totp_incorreto";
    private static final String CODIGO_TOTP_BLOQUEADO = "totp_bloqueado";
    private static final String CODIGO_TOTP_NAO_CONFIGURADO = "totp_nao_configurado";
    private static final String CODIGO_TOTP_FORMATO = "totp_formato";
    /** Tentativas de código num mesmo pedido, para um usuário teimoso não prender uma thread para sempre. */
    private static final int MAXIMO_DE_PEDIDOS_DE_CODIGO = 20;
    private static final int ESPERA_PADRAO_DO_BLOQUEIO_S = 60;

    private final String nomeDoServidor;
    private final String autorizacao;
    private final PedidoDeCodigo2fa pedidoDeCodigo;
    private final TimeoutsPorRota timeouts;
    private final Agendador agendador;
    private final Consumer<String> registroDeRequisicoes;
    private final ObjectMapper json = ContratoJson.mapper();
    private final String base;
    private final HttpClient http;

    // Um pedido de código por vez (ARQUITETURA 10).
    private final Object trava = new Object();
    private CompletableFuture<Boolean> confirmacaoEmCurso;
    private final AtomicLong confirmacoes = new AtomicLong();

    /**
     * @param nomeDoServidor  nome mostrado no diálogo do 2FA
     * @param portaLocal      porta do túnel em 127.0.0.1
     * @param token           token do cliente nativo; {@code null} enquanto não foi registrado
     * @param pedidoDeCodigo  quem pergunta o código do autenticador
     */
    public ClienteApi(String nomeDoServidor, int portaLocal, TokenAgente token, PedidoDeCodigo2fa pedidoDeCodigo) {
        this(nomeDoServidor, portaLocal, token, pedidoDeCodigo, TimeoutsPorRota.padrao(), Agendador.doFundo());
    }

    public ClienteApi(String nomeDoServidor, int portaLocal, TokenAgente token, PedidoDeCodigo2fa pedidoDeCodigo,
                      TimeoutsPorRota timeouts, Agendador agendador) {
        this(nomeDoServidor, portaLocal, token, pedidoDeCodigo, timeouts, agendador, LogDeRequisicoes::registrar);
    }

    /**
     * @param registroDeRequisicoes recebe UMA linha por chamada ao agente (método, rota sem query, status, tempo; veja
     *                              {@link LogDeRequisicoes}); em produção escreve no log em nível debug
     */
    public ClienteApi(String nomeDoServidor, int portaLocal, TokenAgente token, PedidoDeCodigo2fa pedidoDeCodigo,
                      TimeoutsPorRota timeouts, Agendador agendador, Consumer<String> registroDeRequisicoes) {
        this.nomeDoServidor = nomeDoServidor;
        this.registroDeRequisicoes = registroDeRequisicoes;
        this.autorizacao = token == null ? null : token.cabecalhoAuthorization();
        this.pedidoDeCodigo = pedidoDeCodigo == null ? PedidoDeCodigo2fa.NUNCA : pedidoDeCodigo;
        this.timeouts = timeouts;
        this.agendador = agendador;
        this.base = "http://127.0.0.1:" + portaLocal;
        this.http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(ProxySelector.of(null))
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(3))
            .executor(Fundo.ioVirtual())
            .build();
    }

    @Override
    public void close() {
        http.shutdownNow();
    }

    // =====================================================================================================
    // Leitura
    // =====================================================================================================

    /** {@code GET /api/versao}: o teste de saúde da conexão. */
    public VersaoAgente versao() throws ErroApi {
        return get("/api/versao", Rota.VERSAO, VersaoAgente.class);
    }

    /** {@code GET /api/servidor}. */
    public InfoServidor servidor() throws ErroApi {
        return get("/api/servidor", Rota.SERVIDOR, InfoServidor.class);
    }

    /** {@code GET /api/servidor/detalhe}: sensores, energia e hardware (mais pesado que {@code /api/servidor}). */
    public DetalheServidor servidorDetalhe() throws ErroApi {
        return get("/api/servidor/detalhe", Rota.SERVIDOR_DETALHE, DetalheServidor.class);
    }

    /** {@code GET /api/limites}. */
    public LimitesConta limites() throws ErroApi {
        return get("/api/limites", Rota.LIMITES, LimitesConta.class);
    }

    /** {@code GET /api/projetos}. */
    public ListaProjetos projetos() throws ErroApi {
        return get("/api/projetos", Rota.PROJETOS, ListaProjetos.class);
    }

    /**
     * {@code GET /api/projetos/:p}. O {@code lab} (sessão raiz) devolve {@link DetalheRaiz}; os demais,
     * {@link DetalheProjeto}.
     */
    public Detalhe detalhe(String projeto) throws ErroApi {
        String p = ValidadorDeNomes.exigirProjeto(projeto);
        Resposta r = executar("GET", "/api/projetos/" + p, null, Rota.DETALHE_DO_PROJETO);
        exigirSucesso(r);
        JsonNode raiz = lerJson(r);
        boolean ehRaiz = raiz.path("raiz").asBoolean(false);
        return ehRaiz ? converter(raiz, r.status, DetalheRaiz.class) : converter(raiz, r.status, DetalheProjeto.class);
    }

    /** Atalho para o projeto comum; {@link RespostaInvalida} se o agente devolver a forma da raiz. */
    public DetalheProjeto projeto(String projeto) throws ErroApi {
        if (detalhe(projeto) instanceof DetalheProjeto d) {
            return d;
        }
        throw new RespostaInvalida(200, "O agente devolveu a sessão raiz onde se esperava um projeto.", null);
    }

    /** Atalho para a sessão raiz ({@code lab}). */
    public DetalheRaiz sessaoRaiz() throws ErroApi {
        if (detalhe("lab") instanceof DetalheRaiz d) {
            return d;
        }
        throw new RespostaInvalida(200, "O agente devolveu um projeto comum onde se esperava a sessão raiz.", null);
    }

    /** {@code GET /api/tokens/:p}: o consumo de um projeto (nunca 503: sem cache o agente devolve zeros). */
    public TokensDoProjeto tokensDoProjeto(String projeto) throws ErroApi {
        String p = ValidadorDeNomes.exigirProjeto(projeto);
        return get("/api/tokens/" + p, Rota.TOKENS_DO_PROJETO, TokensDoProjeto.class);
    }

    /**
     * {@code GET /api/projetos/:p/logs?container=&linhas=}: texto puro (até 8 MiB). O agente limita {@code linhas} a 50..2000; o
     * app limita igual antes de pedir. Se o Docker falhar o agente ainda responde 200 e o corpo é o texto do erro: o app o
     * mostra como veio (o texto passa por {@code TextoSeguro} na tela).
     */
    public String logs(String projeto, String container, int linhas) throws ErroApi {
        String p = ValidadorDeNomes.exigirProjeto(projeto);
        String c = ValidadorDeNomes.exigirContainer(container);
        int n = Math.max(LINHAS_MINIMAS_DE_LOG, Math.min(LINHAS_MAXIMAS_DE_LOG, linhas));
        Resposta r = executar("GET", "/api/projetos/" + p + "/logs?container=" + c + "&linhas=" + n, null, Rota.LOGS,
            LeituraLimitada.TETO_TEXTO);
        exigirSucesso(r);
        return new String(r.corpo, StandardCharsets.UTF_8);
    }

    /**
     * {@code GET /api/projetos/:p/claude/tela}: a tela do Claude já analisada. {@code NaoEncontrado} ("Sessão não está aberta.")
     * é fluxo normal: quem consulta decide o que mostrar.
     */
    public TelaClaude telaDoClaude(String projeto) throws ErroApi {
        String p = ValidadorDeNomes.exigirProjeto(projeto);
        return get("/api/projetos/" + p + "/claude/tela", Rota.TELA, TelaClaude.class);
    }

    // =====================================================================================================
    // Ações (POST e PUT: nunca reenviadas sozinhas)
    // =====================================================================================================

    /**
     * {@code POST /api/projetos/novo}: cria a pasta, o esqueleto e a rede {@code <nome>_default} (até 60 s). O {@code nome} passa pela regra do
     * agente antes de sair; {@code guiaIa} {@code null} omite o campo (o agente entende como "com guia", o comportamento de sempre), e
     * {@code false} pede um projeto sem {@code CLAUDE.md}. 400 traz a frase do agente (nome já existe, reservado). Nunca reenviada sozinha.
     */
    public RespostaNovoProjeto criarProjeto(String nome, Boolean guiaIa) throws ErroApi {
        ValidadorDeNomes.exigirProjeto(nome);
        ObjectNode corpo = json.createObjectNode();
        corpo.put("nome", nome);
        if (guiaIa != null) {
            corpo.put("guia_ia", guiaIa.booleanValue());
        }
        Resposta r = executar("POST", "/api/projetos/novo", corpo.toString(), Rota.NOVO_PROJETO);
        exigirSucesso(r);
        return converter(lerJson(r), r.status, RespostaNovoProjeto.class);
    }

    /** {@code POST /api/projetos/:p/iniciar}: compose up em cada arquivo (até 12 min). */
    public ResultadoAcao iniciar(String projeto) throws ErroApi {
        return acaoDeProjeto("POST", projeto, "/iniciar", Rota.COMPOSE, ResultadoAcao.class);
    }

    /** {@code POST /api/projetos/:p/parar}: compose down, sem {@code -v}. */
    public ResultadoAcao parar(String projeto) throws ErroApi {
        return acaoDeProjeto("POST", projeto, "/parar", Rota.COMPOSE, ResultadoAcao.class);
    }

    /** {@code POST /api/projetos/:p/reiniciar}. */
    public ResultadoAcao reiniciar(String projeto) throws ErroApi {
        return acaoDeProjeto("POST", projeto, "/reiniciar", Rota.COMPOSE, ResultadoAcao.class);
    }

    /** {@code POST /api/projetos/:p/claude/abrir}: idempotente; faz backup do Postgres antes (até 6 min). 409 = pausada. */
    public SessaoAberta abrirSessao(String projeto) throws ErroApi {
        return acaoDeProjeto("POST", projeto, "/claude/abrir", Rota.ABRIR_SESSAO, SessaoAberta.class);
    }

    /** {@code POST /api/projetos/:p/claude/fechar}: sempre 200, mesmo sem sessão. */
    public void fecharSessao(String projeto) throws ErroApi {
        acaoDeProjeto("POST", projeto, "/claude/fechar", Rota.FECHAR_SESSAO, JsonNode.class);
    }

    /** {@code POST /api/projetos/:p/claude/reabrir}: fecha e abre continuando a conversa. 409 é checado ANTES de fechar. */
    public SessaoAberta reabrirSessao(String projeto) throws ErroApi {
        return acaoDeProjeto("POST", projeto, "/claude/reabrir", Rota.REABRIR_SESSAO, SessaoAberta.class);
    }

    /**
     * {@code PUT /api/projetos/:p/limite}.
     *
     * @param tetoPct de 1 a 100 (pontos percentuais da semana), ou {@code null} para remover o teto
     */
    public RespostaLimite definirLimite(String projeto, Integer tetoPct) throws ErroApi {
        if (tetoPct != null && (tetoPct < 1 || tetoPct > 100)) {
            throw new IllegalArgumentException("O teto vai de 1 a 100 pontos percentuais da semana.");
        }
        ObjectNode corpo = json.createObjectNode();
        if (tetoPct == null) {
            corpo.putNull("teto_pct");
        } else {
            corpo.put("teto_pct", tetoPct.intValue());
        }
        return acaoDeProjeto("PUT", projeto, "/limite", Rota.DEFINIR_LIMITE, corpo, RespostaLimite.class);
    }

    /**
     * {@code POST /api/projetos/:p/limite/retomar}. Um 409 com {@code usado_pp} vira {@link Conflito} com os
     * números do diálogo "ainda acima do limite" ({@link Conflito#aindaEstoura()}).
     *
     * @param liberar isenta o projeto de teto e freio até a semana virar
     * @param reabrir reabre a sessão depois de tirar a pausa
     */
    public RespostaRetomar retomar(String projeto, boolean liberar, boolean reabrir) throws ErroApi {
        ObjectNode corpo = json.createObjectNode();
        corpo.put("liberar", liberar);
        corpo.put("reabrir", reabrir);
        return acaoDeProjeto("POST", projeto, "/limite/retomar", Rota.RETOMAR, corpo, RespostaRetomar.class);
    }

    /**
     * {@code POST /api/projetos/:p/claude/codigo}: digita o código de login do Claude no terminal da sessão (o alerta "Login do
     * Claude pendente"). O agente exige {@code ^[A-Za-z0-9_#.-]{4,600}$} depois do {@code trim}; o app confere antes de enviar.
     * O código NUNCA vai para log.
     */
    public void enviarCodigoDeLogin(String projeto, String codigo) throws ErroApi {
        if (codigo == null || !CODIGO_DE_LOGIN.matcher(codigo.strip()).matches()) {
            throw new IllegalArgumentException("Cole exatamente o código que a página de login mostrou.");
        }
        ObjectNode corpo = json.createObjectNode();
        corpo.put("codigo", codigo.strip());
        acaoDeProjeto("POST", projeto, "/claude/codigo", Rota.CODIGO_DE_LOGIN, corpo, JsonNode.class);
    }

    /** {@code POST /api/projetos/:p/containers/:c/reiniciar}: {@code docker restart} (prazo de 120 s no agente). */
    public void reiniciarContainer(String projeto, String container) throws ErroApi {
        String c = ValidadorDeNomes.exigirContainer(container);
        acaoDeProjeto("POST", projeto, "/containers/" + c + "/reiniciar", Rota.REINICIAR_CONTAINER, JsonNode.class);
    }

    /** As teclas que o agente aceita em {@code claude/teclas} (CONTRATO-API 4.7). */
    public static final java.util.Set<String> TECLAS_PERMITIDAS = java.util.Set.of("Enter", "Escape", "Up", "Down", "Left",
        "Right", "Tab", "BTab", "C-c", "Space", "BSpace");

    /** O maior texto que o agente aceita em uma mensagem para o Claude. */
    public static final int TEXTO_MAXIMO_DA_MENSAGEM = 4000;

    /** {@code POST /api/projetos/:p/claude/teclas} com {@code {"tecla": ...}}: Enter, Escape... 404 = sessão fechada. */
    public void enviarTecla(String projeto, String tecla) throws ErroApi {
        if (tecla == null || !TECLAS_PERMITIDAS.contains(tecla)) {
            throw new IllegalArgumentException("Tecla não permitida.");
        }
        ObjectNode corpo = json.createObjectNode();
        corpo.put("tecla", tecla);
        acaoDeProjeto("POST", projeto, "/claude/teclas", Rota.TECLAS, corpo, JsonNode.class);
    }

    /**
     * {@code POST /api/projetos/:p/claude/teclas} com {@code {"texto": ..., "enter": true}}: escreve a mensagem na sessão e dá
     * Enter. O agente troca quebras de linha por espaço e recusa texto vazio ou acima de 4000 caracteres; o app confere antes.
     * O texto da mensagem NUNCA vai para log.
     */
    public void enviarMensagem(String projeto, String texto) throws ErroApi {
        String t = texto == null ? "" : texto.strip();
        if (t.isEmpty() || t.length() > TEXTO_MAXIMO_DA_MENSAGEM) {
            throw new IllegalArgumentException("Escreva uma mensagem de até " + TEXTO_MAXIMO_DA_MENSAGEM + " caracteres.");
        }
        ObjectNode corpo = json.createObjectNode();
        corpo.put("texto", t);
        corpo.put("enter", true);
        acaoDeProjeto("POST", projeto, "/claude/teclas", Rota.TECLAS, corpo, JsonNode.class);
    }

    /**
     * A confirmação do código: UMA tentativa, sem diálogo e sem repetir. Quem usa o
     * fluxo normal não chama isto: o {@code ClienteApi} o faz sozinho quando o agente pede o código.
     * Código incorreto e afins chegam como {@link NaoAutorizado}, {@link ServidorErro} ou {@link Conflito}.
     */
    public Ativacao2fa confirmar2fa(String codigo) throws ErroApi {
        String limpo = ValidadorDeNomes.normalizarCodigoTotp(codigo);
        if (limpo == null) {
            throw new IllegalArgumentException("O código do autenticador tem 6 dígitos.");
        }
        Resposta r = enviar("POST", "/api/acesso/2fa", corpoDoCodigo(limpo), Rota.CONFIRMAR_2FA);
        exigirSucesso(r);
        return converter(lerJson(r), r.status, Ativacao2fa.class);
    }

    // =====================================================================================================
    // Núcleo
    // =====================================================================================================

    private <T> T get(String caminho, Rota rota, Class<T> tipo) throws ErroApi {
        Resposta r = executar("GET", caminho, null, rota);
        exigirSucesso(r);
        return converter(lerJson(r), r.status, tipo);
    }

    private <T> T acaoDeProjeto(String metodo, String projeto, String sufixo, Rota rota, Class<T> tipo) throws ErroApi {
        return acaoDeProjeto(metodo, projeto, sufixo, rota, null, tipo);
    }

    private <T> T acaoDeProjeto(String metodo, String projeto, String sufixo, Rota rota, ObjectNode corpo, Class<T> tipo)
        throws ErroApi {
        String p = ValidadorDeNomes.exigirProjeto(projeto);
        String texto = corpo == null ? "{}" : corpo.toString();
        Resposta r = executar(metodo, "/api/projetos/" + p + sufixo, texto, rota);
        exigirSucesso(r);
        return converter(lerJson(r), r.status, tipo);
    }

    /** Resposta crua: status e corpo (já com teto). */
    private record Resposta(int status, byte[] corpo) { }

    /**
     * Envia e, se o agente pedir o código do autenticador, resolve o pedido (uma vez para todos) e REPETE a
     * chamada UMA vez.
     */
    private Resposta executar(String metodo, String caminho, String corpo, Rota rota) throws ErroApi {
        return executar(metodo, caminho, corpo, rota, LeituraLimitada.TETO_JSON);
    }

    private Resposta executar(String metodo, String caminho, String corpo, Rota rota, int teto) throws ErroApi {
        long confirmacoesAntes = confirmacoes.get();
        Resposta r = enviar(metodo, caminho, corpo, rota, teto);
        if (ehTotpRequerido(r)) {
            garantirConfirmacao(confirmacoesAntes);
            r = enviar(metodo, caminho, corpo, rota, teto); // a ÚNICA repetição; uma segunda 401 vira erro comum
        }
        return r;
    }

    private Resposta enviar(String metodo, String caminho, String corpo, Rota rota) throws ErroApi {
        return enviar(metodo, caminho, corpo, rota, LeituraLimitada.TETO_JSON);
    }

    /** O {@code teto} é do corpo: JSON até 2 MiB, texto (logs) até 8 MiB, o {@code maxBuffer} do agente. */
    private Resposta enviar(String metodo, String caminho, String corpo, Rota rota, int teto) throws ErroApi {
        Duration prazo = timeouts.de(rota);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + caminho))
            .timeout(prazo)
            .header("Accept", teto == LeituraLimitada.TETO_TEXTO ? "text/plain, application/json" : "application/json")
            .header("User-Agent", Versao.userAgent());
        if (autorizacao != null) {
            b.header("Authorization", autorizacao);
        }
        if (corpo == null) {
            b.method(metodo, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json");
            b.method(metodo, HttpRequest.BodyPublishers.ofString(corpo, StandardCharsets.UTF_8));
        }
        long inicio = System.nanoTime();
        String desfecho = "interrompida";
        try {
            HttpResponse<InputStream> resposta = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            // O prazo do HttpRequest cobre só até os cabeçalhos; um vigia fecha o fluxo se o corpo emperrar.
            Duration restante = prazo.minus(Duration.ofNanos(System.nanoTime() - inicio));
            Agendador.Cancelavel vigia = agendador.agendar(restante, () -> fecharQuieto(resposta.body()));
            try (InputStream corpoDaResposta = resposta.body()) {
                desfecho = String.valueOf(resposta.statusCode());
                return new Resposta(resposta.statusCode(), LeituraLimitada.ler(corpoDaResposta, teto));
            } finally {
                vigia.cancelar();
            }
        } catch (LeituraLimitada.AcimaDoTeto e) {
            desfecho += ", resposta grande demais";
            throw new RespostaInvalida(0, "O agente mandou uma resposta grande demais.", e);
        } catch (HttpTimeoutException e) {
            desfecho = "sem resposta (prazo de " + prazo.toSeconds() + " s)";
            throw new ErroRede("O agente não respondeu em " + prazo.toSeconds() + " s.", e);
        } catch (IOException e) {
            desfecho = "sem resposta (" + e.getClass().getSimpleName() + ")";
            throw new ErroRede("Sem resposta do agente (" + descrever(e) + ").", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ErroRede("Chamada interrompida.", e);
        } finally {
            registrar(metodo, caminho, desfecho, inicio);
        }
    }

    /** Uma linha no log de requisições (modo debug). Nunca derruba a chamada. */
    private void registrar(String metodo, String caminho, String desfecho, long inicioEmNanos) {
        try {
            long ms = (System.nanoTime() - inicioEmNanos) / 1_000_000;
            registroDeRequisicoes.accept(LogDeRequisicoes.linha(nomeDoServidor, metodo, caminho, desfecho, ms));
        } catch (RuntimeException e) {
            LOG.debug("Falha ao registrar a requisição: {}", e.toString());
        }
    }

    private static void fecharQuieto(InputStream fluxo) {
        try {
            fluxo.close();
        } catch (IOException e) {
            LOG.debug("Falha ao fechar o fluxo da resposta: {}", e.toString());
        }
    }

    private static String descrever(IOException e) {
        String m = e.getMessage();
        return Redator.redigir(m == null || m.isBlank() ? e.getClass().getSimpleName() : m);
    }

    // ----- 2FA -----

    private boolean ehTotpRequerido(Resposta r) {
        if (r.status != 401) {
            return false;
        }
        JsonNode n = tentarLerJson(r);
        return n != null && NaoAutorizado.TOTP_REQUERIDO.equals(n.path("codigo").asText(null));
    }

    /**
     * Garante que o código foi confirmado DEPOIS do envio que tomou a 401. Se outra thread já confirmou nesse
     * meio tempo, não pergunta de novo; se há um pedido em curso, espera por ele; senão, é esta thread que
     * pergunta.
     */
    private void garantirConfirmacao(long confirmacoesAntes) throws ErroApi {
        CompletableFuture<Boolean> esperar;
        boolean souOLider = false;
        synchronized (trava) {
            if (confirmacoes.get() > confirmacoesAntes) {
                return;
            }
            if (confirmacaoEmCurso == null) {
                confirmacaoEmCurso = new CompletableFuture<>();
                souOLider = true;
            }
            esperar = confirmacaoEmCurso;
        }
        if (souOLider) {
            boolean confirmou = false;
            Throwable falha = null;
            try {
                confirmou = perguntarEConfirmar();
            } catch (ErroApi | RuntimeException e) {
                falha = e;
            }
            synchronized (trava) {
                if (confirmou) {
                    confirmacoes.incrementAndGet();
                }
                confirmacaoEmCurso = null;
            }
            if (falha != null) {
                esperar.completeExceptionally(falha);
            } else {
                esperar.complete(confirmou);
            }
        }
        try {
            if (!esperar.get()) {
                throw new NaoAutorizado(jsonDeTotpRequerido(), "Confirme o código do autenticador.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ErroRede("Chamada interrompida.", e);
        } catch (ExecutionException e) {
            Throwable causa = e.getCause();
            if (causa instanceof ErroApi erro) {
                throw erro;
            }
            throw new ErroRede("Falha ao confirmar o código do autenticador.", causa);
        }
    }

    private JsonNode jsonDeTotpRequerido() {
        ObjectNode n = json.createObjectNode();
        n.put("erro", "Confirme o código do autenticador.");
        n.put("codigo", NaoAutorizado.TOTP_REQUERIDO);
        return n;
    }

    /**
     * Pergunta o código (quantas vezes for preciso) e o confirma no agente.
     *
     * @return {@code true} se confirmou (ou se o 2FA já nem está ativo), {@code false} se o usuário cancelou
     */
    private boolean perguntarEConfirmar() throws ErroApi {
        String aviso = null;
        int aguardeS = 0;
        for (int i = 0; i < MAXIMO_DE_PEDIDOS_DE_CODIGO; i++) {
            Optional<String> digitado = aguardeS > 0
                ? pedidoDeCodigo.pedir(nomeDoServidor, aviso, aguardeS)
                : pedidoDeCodigo.pedir(nomeDoServidor, aviso);
            if (digitado == null || digitado.isEmpty()) {
                return false;
            }
            String codigo = ValidadorDeNomes.normalizarCodigoTotp(digitado.get());
            aguardeS = 0;
            if (codigo == null) {
                aviso = "Informe o código de 6 dígitos.";
                continue;
            }
            Resposta r = enviar("POST", "/api/acesso/2fa", corpoDoCodigo(codigo), Rota.CONFIRMAR_2FA);
            JsonNode corpo = tentarLerJson(r);
            String codigoDoErro = corpo == null ? null : corpo.path("codigo").asText(null);
            if (r.status >= 200 && r.status < 300) {
                return true;
            }
            if (r.status == 401 && CODIGO_TOTP_INCORRETO.equals(codigoDoErro)) {
                aviso = "Código incorreto ou já usado. Confira também a hora do celular.";
            } else if (r.status == 429 && CODIGO_TOTP_BLOQUEADO.equals(codigoDoErro)) {
                aguardeS = Math.max(1, corpo.path("aguarde_s").asInt(ESPERA_PADRAO_DO_BLOQUEIO_S));
                aviso = "Muitas tentativas. Aguarde " + aguardeS + " s.";
            } else if (r.status == 400 && CODIGO_TOTP_FORMATO.equals(codigoDoErro)) {
                aviso = "Informe o código de 6 dígitos.";
            } else if (r.status == 409 && CODIGO_TOTP_NAO_CONFIGURADO.equals(codigoDoErro)) {
                return true; // alguém desativou o 2FA no meio: a chamada original passa sem código
            } else {
                throw erroDe(r); // 401 sem código (token revogado), 500, ...
            }
        }
        return false;
    }

    private String corpoDoCodigo(String codigo) {
        ObjectNode n = json.createObjectNode();
        n.put("codigo", codigo);
        return n.toString();
    }

    // ----- interpretação da resposta -----

    private void exigirSucesso(Resposta r) throws ErroApi {
        if (r.status < 200 || r.status >= 300) {
            throw erroDe(r);
        }
    }

    private JsonNode tentarLerJson(Resposta r) {
        if (r.corpo.length == 0) {
            return null;
        }
        try {
            return json.readTree(r.corpo);
        } catch (IOException e) {
            return null;
        }
    }

    private JsonNode lerJson(Resposta r) throws ErroApi {
        if (r.corpo.length == 0) {
            throw new RespostaInvalida(r.status, "O agente respondeu sem corpo.", null);
        }
        try {
            JsonNode n = json.readTree(r.corpo);
            if (n == null || n.isMissingNode()) {
                throw new RespostaInvalida(r.status, "O agente respondeu sem corpo.", null);
            }
            return n;
        } catch (JsonProcessingException e) {
            throw new RespostaInvalida(r.status, "Não consegui ler a resposta do agente.", e);
        } catch (IOException e) {
            throw new RespostaInvalida(r.status, "Não consegui ler a resposta do agente.", e);
        }
    }

    private <T> T converter(JsonNode no, int status, Class<T> tipo) throws ErroApi {
        if (tipo == JsonNode.class) {
            return tipo.cast(no);
        }
        try {
            return json.treeToValue(no, tipo);
        } catch (JsonProcessingException e) {
            // A mensagem do Jackson cita pedaços do JSON; fica só no log de depuração, nunca na tela.
            LOG.debug("Resposta fora do contrato ({}): {}", tipo.getSimpleName(), Redator.redigir(e.getOriginalMessage()));
            throw new RespostaInvalida(status, "A resposta do agente não bate com o contrato (" + tipo.getSimpleName() + ").", e);
        }
    }

    private ErroApi erroDe(Resposta r) {
        JsonNode corpo = tentarLerJson(r);
        boolean objeto = corpo != null && corpo.isObject();
        String mensagem = objeto && corpo.hasNonNull("erro") ? corpo.get("erro").asText()
            : textoPuro(r);
        JsonNode corpoDoErro = objeto ? corpo : null;
        return switch (r.status) {
            case 401 -> new NaoAutorizado(corpoDoErro, mensagem.isBlank() ? "Token ausente ou inválido." : mensagem);
            case 404 -> new NaoEncontrado(corpoDoErro, mensagem);
            case 409 -> new Conflito(corpoDoErro, mensagem);
            default -> new ServidorErro(r.status, corpoDoErro, mensagem);
        };
    }

    /** Erros fora do formato JSON (421, 404 "não encontrado") são texto puro. */
    private static String textoPuro(Resposta r) {
        String t = new String(r.corpo, StandardCharsets.UTF_8);
        return TextoSeguro.truncar(TextoSeguro.limparLinha(t), 200);
    }
}
