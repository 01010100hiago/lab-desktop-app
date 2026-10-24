package br.com.labdesktop.ssh;

import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.seguranca.ValidadorDeNomes;
import br.com.labdesktop.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Os comandos do agente que o app roda por SSH {@code exec} (CONTRATO-API seção 6):
 * registrar e revogar o acesso do app, e configurar,
 * confirmar e desativar o 2FA. É a casca fina entre o app e o programa do agente.
 *
 * <p>Nada sensível vai na linha de comando: o hash do token, o nome do dispositivo e o código do 2FA vão por
 * STDIN. A linha de comando é FIXA (uma constante por comando). Cada comando imprime UMA linha JSON no stdout
 * e sai com 0 (sucesso) ou 1 (falha); a falha é {@code {"ok":false,"codigo":...,"erro":...}}.
 *
 * <p>Tudo aqui bloqueia (SSH): thread de fundo, nunca a EDT. Veja {@code ServidorLab} para o fluxo completo.
 */
public final class ProvisionadorRemoto {

    /** Quem executa uma linha de comando com stdin (o {@link ExecRemoto} da sessão viva; um fake nos testes). */
    @FunctionalInterface
    public interface Executor {
        ResultadoExec executar(String comando, byte[] stdin, Duration prazo) throws IOException, InterruptedException;
    }

    /** Container do agente e programa de linha de comando dele. Fixos (CONTRATO-API seção 6). */
    static final String PREFIXO = "docker exec -i painel-lab node acesso.js ";
    private static final Duration PRAZO = Duration.ofSeconds(30);

    private final Executor executor;

    public ProvisionadorRemoto(Executor executor) {
        this.executor = executor;
    }

    /**
     * Registra o hash do token no agente. Repetir o mesmo registro é idempotente.
     *
     * @param hashSha256Hex hash do texto do token em hexadecimal minúsculo (64)
     * @return {@code true} se já estava registrado (mesmo hash e mesmo nome)
     * @throws ErroProvisionamento {@code token_conflito} (mesmo nome com outro hash, ou o contrário) e afins
     */
    public boolean registrarToken(String hashSha256Hex, String nomeDoDispositivo, Instant criadoEm)
        throws ErroProvisionamento {
        if (!ValidadorDeNomes.hashSha256Valido(hashSha256Hex)) {
            throw new IllegalArgumentException("Hash do token inválido.");
        }
        if (!ValidadorDeNomes.dispositivoValido(nomeDoDispositivo)) {
            throw new IllegalArgumentException("Nome do dispositivo inválido.");
        }
        ObjectNode entrada = Json.mapper().createObjectNode();
        entrada.put("hash", hashSha256Hex);
        entrada.put("nome_do_dispositivo", nomeDoDispositivo);
        entrada.put("criado_em", criadoEm.toString()); // Instant.toString(), como o agente espera
        JsonNode r = rodar("token-registrar", entrada.toString() + "\n");
        return r.path("ja_existia").asBoolean(false);
    }

    /** Remove a entrada do token pelo nome do dispositivo. */
    public void revogarToken(String nomeDoDispositivo) throws ErroProvisionamento {
        if (!ValidadorDeNomes.dispositivoValido(nomeDoDispositivo)) {
            throw new IllegalArgumentException("Nome do dispositivo inválido.");
        }
        rodar("token-revogar", nomeDoDispositivo + "\n");
    }

    /**
     * O comando que tira UMA linha do {@code ~/.ssh/authorized_keys} do usuário do servidor. FIXO, como os outros: o texto da
     * chave (só a parte em base64, que identifica a linha) vai por STDIN e o {@code grep} o lê com {@code -f -}, então nada
     * que o usuário ou o servidor escrevam entra na linha de comando. {@code sh -c '...'} para valer em qualquer shell de login.
     *
     * <p>Sai com 0 (tirou, ou a linha nem estava lá), 2 (erro de leitura ou de escrita) ou 3 (a chave era a ÚNICA linha do
     * arquivo: não se apaga, para não trancar o usuário fora do próprio servidor). O arquivo é reescrito no mesmo lugar
     * ({@code cat > arquivo}), o que preserva dono e permissões.
     */
    static final String COMANDO_REMOVER_CHAVE_AUTORIZADA = "sh -c '"
        + "f=\"$HOME/.ssh/authorized_keys\"; [ -f \"$f\" ] || exit 0; "
        + "t=$(mktemp) || exit 2; "
        + "grep -vFf - \"$f\" > \"$t\"; r=$?; "
        + "if [ \"$r\" -gt 1 ]; then rm -f \"$t\"; exit 2; fi; "
        + "if [ ! -s \"$t\" ]; then rm -f \"$t\"; exit 3; fi; "
        + "cat \"$t\" > \"$f\"; r=$?; rm -f \"$t\"; exit \"$r\"'";

    /** O que aconteceu com a chave deste app no {@code authorized_keys} do servidor. */
    public enum ResultadoDaChaveAutorizada {
        /** A linha foi tirada (ou já não estava lá). */
        REMOVIDA,
        /** Era a única chave do arquivo: ficou, para o usuário não perder o acesso ao servidor. */
        MANTIDA_POR_SER_A_UNICA
    }

    /**
     * Tira a chave pública deste app do {@code authorized_keys} do usuário no servidor (é o que de fato revoga o acesso por
     * SSH). Só a chave Ed25519 do app é reconhecida; o texto em base64 é validado antes de ir por stdin.
     *
     * @param chavePublica a linha {@code ssh-ed25519 AAAA... comentário}
     * @throws IllegalArgumentException se não parece uma chave pública Ed25519
     * @throws ErroProvisionamento      sem conexão, ou o servidor não conseguiu mexer no arquivo
     */
    public ResultadoDaChaveAutorizada removerChaveAutorizada(String chavePublica) throws ErroProvisionamento {
        String[] partes = chavePublica == null ? new String[0] : chavePublica.strip().split("\\s+");
        if (partes.length < 2 || !"ssh-ed25519".equals(partes[0]) || !partes[1].matches("[A-Za-z0-9+/]{20,100}={0,2}")) {
            throw new IllegalArgumentException("A chave pública não parece uma chave Ed25519.");
        }
        ResultadoExec exec;
        try {
            exec = executor.executar(COMANDO_REMOVER_CHAVE_AUTORIZADA, (partes[1] + "\n").getBytes(StandardCharsets.UTF_8), PRAZO);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ErroProvisionamento(ErroProvisionamento.SEM_RESPOSTA, "Interrompido.", e);
        } catch (IOException e) {
            throw new ErroProvisionamento(ErroProvisionamento.SEM_SESSAO,
                "Sem conexão SSH para tirar a chave do servidor: " + Redator.redigir(e), e);
        }
        return switch (exec.status()) {
            case 0 -> ResultadoDaChaveAutorizada.REMOVIDA;
            case 3 -> ResultadoDaChaveAutorizada.MANTIDA_POR_SER_A_UNICA;
            default -> {
                String dica = TextoSeguro.truncar(TextoSeguro.limparLinha(Redator.redigir(exec.stderr())), 200);
                throw new ErroProvisionamento(ErroProvisionamento.SEM_RESPOSTA,
                    "O servidor não conseguiu tirar a chave do arquivo de acessos (status " + exec.status() + ")."
                        + (dica.isEmpty() ? "" : " " + dica));
            }
        };
    }

    /**
     * Gera o segredo do 2FA no servidor (fica {@code pendente}: não exige nada até a confirmação). O segredo
     * volta UMA vez, aqui; quem recebe o {@link SegredoTotp} o zera ao fechar o diálogo.
     */
    public SegredoTotp totpIniciar() throws ErroProvisionamento {
        JsonNode r = rodar("totp-iniciar", "");
        String segredo = r.path("segredo").asText(null);
        String uri = r.path("uri").asText(null);
        try {
            return SegredoTotp.de(segredo, uri);
        } catch (IllegalArgumentException e) {
            throw new ErroProvisionamento(ErroProvisionamento.SEM_RESPOSTA, "O agente devolveu um segredo de 2FA ilegível.", e);
        }
    }

    /**
     * Confirma o primeiro código e ativa o 2FA.
     *
     * @param codigo             seis dígitos (aceita o espaço do meio)
     * @param nomeDoDispositivo  token DESTE app já registrado: fica confirmado por 30 dias, sem pedir um segundo
     *                           código logo em seguida (o mesmo código não valeria de novo); {@code null} para não ativar nenhum
     * @return até quando este dispositivo ficou confirmado ({@code null} se não foi pedido)
     */
    public Optional<Instant> totpConfirmar(String codigo, String nomeDoDispositivo) throws ErroProvisionamento {
        String limpo = exigirCodigo(codigo);
        if (nomeDoDispositivo != null && !ValidadorDeNomes.dispositivoValido(nomeDoDispositivo)) {
            throw new IllegalArgumentException("Nome do dispositivo inválido.");
        }
        String stdin = limpo + "\n" + (nomeDoDispositivo == null ? "" : nomeDoDispositivo + "\n");
        JsonNode r = rodar("totp-confirmar", stdin);
        String ate = r.path("ativado_ate").asText(null);
        try {
            return ate == null ? Optional.empty() : Optional.of(Instant.parse(ate));
        } catch (RuntimeException e) {
            return Optional.empty(); // ativou; só a data veio ilegível
        }
    }

    /** Desativa o 2FA (exige um código válido). */
    public void totpDesativar(String codigo) throws ErroProvisionamento {
        rodar("totp-desativar", exigirCodigo(codigo) + "\n");
    }

    private static String exigirCodigo(String digitado) {
        String limpo = ValidadorDeNomes.normalizarCodigoTotp(digitado);
        if (limpo == null) {
            throw new IllegalArgumentException("O código do autenticador tem 6 dígitos.");
        }
        return limpo;
    }

    /** Roda o comando e devolve o objeto JSON de sucesso, ou lança {@link ErroProvisionamento}. */
    private JsonNode rodar(String comando, String stdin) throws ErroProvisionamento {
        ResultadoExec exec;
        try {
            exec = executor.executar(PREFIXO + comando, stdin.getBytes(StandardCharsets.UTF_8), PRAZO);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ErroProvisionamento(ErroProvisionamento.SEM_RESPOSTA, "Interrompido.", e);
        } catch (IOException e) {
            throw new ErroProvisionamento(ErroProvisionamento.SEM_SESSAO,
                "Sem conexão SSH para rodar o comando no servidor: " + Redator.redigir(e), e);
        }
        JsonNode json = ultimaLinhaJson(exec.stdout());
        if (json == null) {
            String dica = TextoSeguro.truncar(TextoSeguro.limparLinha(Redator.redigir(exec.stderr())), 200);
            throw new ErroProvisionamento(ErroProvisionamento.SEM_RESPOSTA,
                "O comando '" + comando + "' não respondeu (status " + exec.status() + ")."
                    + (dica.isEmpty() ? "" : " " + dica));
        }
        if (!json.path("ok").asBoolean(false)) {
            String codigo = json.path("codigo").asText(ErroProvisionamento.SEM_RESPOSTA);
            String erro = json.path("erro").asText("O agente recusou o comando.");
            throw new ErroProvisionamento(codigo, TextoSeguro.truncar(erro, 300));
        }
        return json;
    }

    /**
     * Procura a última linha que é um objeto JSON. O stdout pode ter ruído antes (banner, aviso do docker); o
     * contrato do agente é "uma linha JSON", e é a última.
     */
    static JsonNode ultimaLinhaJson(String stdout) {
        String[] linhas = stdout.split("\\R");
        for (int i = linhas.length - 1; i >= 0; i--) {
            String l = linhas[i].strip();
            if (l.startsWith("{") && l.endsWith("}")) {
                try {
                    JsonNode n = Json.mapper().readTree(l);
                    if (n != null && n.isObject()) {
                        return n;
                    }
                } catch (IOException e) {
                    // linha que parece JSON mas não é: continua procurando
                }
            }
        }
        return null;
    }
}
