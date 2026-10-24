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
    /** Os comandos de segurança (somente leitura) que o app mandou por SSH. */
    public final List<String> comandosDeSeguranca = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** O usuário não tem sudo sem senha: os comandos de segurança com sudo falham como no servidor real. */
    public volatile boolean segurancaSemSudo;
    /** Tudo o que o servidor SSH de teste recebeu como linha de comando e stdin, na ordem. */
    public final List<String> comandosCli = new java.util.concurrent.CopyOnWriteArrayList<>();
    public final List<String> entradasCli = new java.util.concurrent.CopyOnWriteArrayList<>();

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
     * Respostas sob medida de uma rota (depois da autenticação e do 2FA, antes das rotas padrão): devolve {@code null}
     * para deixar a rota padrão responder. Os testes de tela programam aqui o 409 de abrir sessão, o Retomar que passa
     * com {@code liberar:true} e afins.
     */
    public volatile java.util.function.Function<Pedido, Resposta> excecoes = p -> null;

    public AgenteDoLab() throws IOException {
        http = new AgenteFalso();
        http.responder(this::atender);
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

    private Resposta atender(Pedido p) {
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
        if (agenteAntigo && "/api/versao".equals(caminho)) {
            return Resposta.texto(404, "não encontrado");
        }
        if ("/api/versao".equals(caminho)) {
            String totp = totpAtivo ? "ativo" : totpPendente ? "pendente" : "desligado";
            return Resposta.json(200, "{\"api\":1,\"painel\":\"v22.5.0\",\"exigeToken\":" + exigeToken + ",\"cliente\":"
                + (dispositivo == null ? "null" : "\"" + dispositivo + "\"") + ",\"totp\":\"" + totp + "\"}");
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
        if ("/api/servidor/detalhe".equals(caminho)) {
            consultasDeDetalhe.incrementAndGet();
            return Resposta.json(200, br.com.labdesktop.api.FixturesDeApi.lerRecurso("servidor-detalhe-sintetico.json"));
        }
        if (caminho.endsWith("/limite/retomar")) {
            return Resposta.json(409, MocksDoFront.ler("projetos/app-loja-retomar.POST.json"));
        }
        if ("POST".equals(p.metodo()) || "PUT".equals(p.metodo())) {
            return Resposta.json(200, "{\"ok\":true,\"saida\":\"[docker-compose.yml] feito\",\"url\":\"/sessao/x\"}");
        }
        return Resposta.json(404, "{\"erro\":\"Projeto não existe.\"}");
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
            if (ehComandoDeSeguranca(linha)) {
                comandosDeSeguranca.add(linha);
                return responderSeguranca(linha, saida);
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

    private static boolean ehComandoDeSeguranca(String linha) {
        return linha.startsWith("sudo -n ") || linha.startsWith("ss ") || linha.startsWith("systemctl ");
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
