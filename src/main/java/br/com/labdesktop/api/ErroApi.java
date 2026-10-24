package br.com.labdesktop.api;

import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.seguranca.TextoSeguro;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Tudo que pode dar errado numa chamada ao agente (ARQUITETURA 6.5). Selada: quem trata erro faz
 * {@code switch} exaustivo e o compilador avisa se aparecer um caso novo. É verificada (de propósito): a tela
 * precisa decidir o que mostrar em cada caso.
 *
 * <ul>
 *   <li>{@link NaoAutorizado} (401): token errado/revogado (sem {@code codigo}) ou 2FA (com {@code codigo});</li>
 *   <li>{@link Conflito} (409): sessão pausada, "ainda estoura" (com os números do diálogo);</li>
 *   <li>{@link NaoEncontrado} (404): inclusive "Sessão não está aberta.", que é fluxo normal;</li>
 *   <li>{@link ServidorErro}: qualquer outro status de erro (400, 403, 421, 429, 500, 503...);</li>
 *   <li>{@link ErroRede}: sem resposta (túnel caiu, prazo estourou). NÃO é erro de tela: o Sondador pergunta à
 *       conexão o que houve;</li>
 *   <li>{@link RespostaInvalida}: o agente respondeu, mas o JSON não bate com o contrato.</li>
 * </ul>
 *
 * <p>A mensagem vem do campo {@code erro} do agente e é tratada como NÃO confiável: sai limpa (controles, bidi,
 * {@code <html}) e redigida (nenhum token, mesmo que o servidor o devolva). O token do app nunca entra aqui.
 */
public abstract sealed class ErroApi extends Exception {

    private static final long serialVersionUID = 1L;
    private static final int TETO_DA_MENSAGEM = 300;

    private final int status;
    private final String codigo;
    private final transient JsonNode corpo;

    private ErroApi(int status, JsonNode corpo, String mensagem, Throwable causa) {
        super(limpar(mensagem), causa);
        this.status = status;
        this.corpo = corpo;
        String c = corpo == null || !corpo.hasNonNull("codigo") ? null : corpo.get("codigo").asText(null);
        this.codigo = c == null ? null : TextoSeguro.limparLinha(c);
    }

    private static String limpar(String mensagem) {
        String m = mensagem == null || mensagem.isBlank() ? "Erro na chamada ao agente." : mensagem;
        return TextoSeguro.truncar(TextoSeguro.limparLinha(Redator.redigir(m)), TETO_DA_MENSAGEM);
    }

    /** O status HTTP, ou 0 quando não houve resposta (rede). */
    public int status() {
        return status;
    }

    /** O campo {@code codigo} do corpo (o motivo da recusa), ou {@code null}. */
    public String codigo() {
        return codigo;
    }

    /** O corpo JSON do erro, se havia um; pode ser {@code null}. */
    public JsonNode corpo() {
        return corpo;
    }

    /** A mensagem em pt-BR, já limpa. */
    public String mensagem() {
        return getMessage();
    }

    // ------------------------------------------------------------------------------------------------

    /** 401. Sem {@code codigo}: token errado ou revogado. Com pedido de código: falta o código do autenticador. */
    public static final class NaoAutorizado extends ErroApi {
        private static final long serialVersionUID = 1L;

        public static final String TOTP_REQUERIDO = "totp_requerido";
        public static final String TOTP_INCORRETO = "totp_incorreto";

        public NaoAutorizado(JsonNode corpo, String mensagem) {
            super(401, corpo, mensagem, null);
        }

        /** O agente pede o código do autenticador (e o usuário não deu, ou não acertou). */
        public boolean totpRequerido() {
            return TOTP_REQUERIDO.equals(codigo());
        }

        /** Token errado ou revogado (401 sem {@code codigo}): oferece reprovisionar. */
        public boolean tokenRecusado() {
            return codigo() == null;
        }
    }

    /** 409. Carrega os números do diálogo "ainda acima do limite" quando o corpo os traz. */
    public static final class Conflito extends ErroApi {
        private static final long serialVersionUID = 1L;

        public Conflito(JsonNode corpo, String mensagem) {
            super(409, corpo, mensagem, null);
        }

        private Double numero(String campo) {
            JsonNode n = corpo() == null ? null : corpo().get(campo);
            return n == null || !n.isNumber() ? null : n.asDouble();
        }

        /** Pontos percentuais da semana que o projeto já usou. */
        public Double usadoPp() {
            return numero("usado_pp");
        }

        /** Teto do projeto (pontos percentuais da semana); {@code null} sem teto. */
        public Integer tetoPct() {
            Double d = numero("teto_pct");
            return d == null ? null : (int) Math.round(d);
        }

        /** Percentual da semana da conta já usado. */
        public Double pctConta() {
            return numero("pct_conta");
        }

        /** Percentual em que o freio global atua; {@code null} com o freio desligado. */
        public Integer freioPct() {
            Double d = numero("freio_pct");
            return d == null ? null : (int) Math.round(d);
        }

        /** {@code true} quando o corpo traz os números do "ainda acima do limite" (Retomar que estoura de novo). */
        public boolean aindaEstoura() {
            return usadoPp() != null;
        }
    }

    /** 404. {@code Sessão não está aberta.} é fluxo normal, não erro de tela. */
    public static final class NaoEncontrado extends ErroApi {
        private static final long serialVersionUID = 1L;

        public NaoEncontrado(JsonNode corpo, String mensagem) {
            super(404, corpo, mensagem, null);
        }

        public boolean sessaoNaoAberta() {
            return getMessage() != null && getMessage().startsWith("Sessão não está aberta");
        }
    }

    /** Outros status de erro. {@code 503} em {@code /api/tokens} significa "ainda calculando". */
    public static final class ServidorErro extends ErroApi {
        private static final long serialVersionUID = 1L;

        public ServidorErro(int status, JsonNode corpo, String mensagem) {
            super(status, corpo, mensagem, null);
        }

        /** 503: o agregador de consumo ainda não fez o primeiro ciclo; repete em 6 s. */
        public boolean calculando() {
            return status() == 503;
        }

        /** 429 do 2FA: segundos de espera pedidos pelo agente (0 se não vieram). */
        public int aguardeSegundos() {
            JsonNode n = corpo() == null ? null : corpo().get("aguarde_s");
            return n == null || !n.canConvertToInt() ? 0 : Math.max(0, n.asInt());
        }
    }

    /** Não houve resposta: conexão recusada, túnel caído, prazo estourado, thread interrompida. */
    public static final class ErroRede extends ErroApi {
        private static final long serialVersionUID = 1L;

        public ErroRede(String mensagem, Throwable causa) {
            super(0, null, mensagem, causa);
        }
    }

    /** O agente respondeu, mas a resposta não bate com o contrato (JSON ilegível, grande demais). */
    public static final class RespostaInvalida extends ErroApi {
        private static final long serialVersionUID = 1L;

        public RespostaInvalida(int status, String mensagem, Throwable causa) {
            super(status, null, mensagem, causa);
        }
    }
}
