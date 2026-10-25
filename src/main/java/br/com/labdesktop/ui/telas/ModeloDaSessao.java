package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.modelo.TelaClaude;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.TextosDeLimite;
import java.util.Locale;

/**
 * As decisões da aba "Sessão do Claude" (puro, sem Swing): em que estado a sessão está, se o login está pendente e o que o
 * Claude está fazendo agora. O vocabulário é o do painel web: pergunta pendente, executando, esperando você, sessão aberta,
 * sessão fechada, pausada pelo limite ou pelo freio, login pendente.
 */
public final class ModeloDaSessao {

    /** Em que pé a sessão está. */
    public enum Situacao {
        ABERTA("sessão aberta", Gravidade.ACENTO, null),
        FECHADA("sessão fechada", Gravidade.NEUTRO, null),
        PAUSADA_PELO_LIMITE("pausada pelo limite", Gravidade.PERIGO, "pausa"),
        PAUSADA_PELO_FREIO("pausada pelo freio", Gravidade.ATENCAO, "pausa");

        private final String rotulo;
        private final Gravidade gravidade;
        private final String icone;

        Situacao(String rotulo, Gravidade gravidade, String icone) {
            this.rotulo = rotulo;
            this.gravidade = gravidade;
            this.icone = icone;
        }

        public String rotulo() {
            return rotulo;
        }

        public Gravidade gravidade() {
            return gravidade;
        }

        public String icone() {
            return icone;
        }
    }

    /** O que o Claude está fazendo (na tela da sessão aberta). */
    public enum Atividade {
        PERGUNTA_PENDENTE("pergunta pendente", Gravidade.ATENCAO, "alerta"),
        EXECUTANDO("executando", Gravidade.ACENTO, "play"),
        ESPERANDO_VOCE("esperando você", Gravidade.OK, "ok-circulo"),
        OCUPADO("ocupado", Gravidade.NEUTRO, "info");

        private final String rotulo;
        private final Gravidade gravidade;
        private final String icone;

        Atividade(String rotulo, Gravidade gravidade, String icone) {
            this.rotulo = rotulo;
            this.gravidade = gravidade;
            this.icone = icone;
        }

        public String rotulo() {
            return rotulo;
        }

        public Gravidade gravidade() {
            return gravidade;
        }

        public String icone() {
            return icone;
        }
    }

    private ModeloDaSessao() { }

    /** Aberta, fechada ou pausada (pelo limite do projeto ou pelo freio da conta). A pausa vale mesmo sem sessão aberta. */
    public static Situacao situacao(ModeloDoProjeto m) {
        if (m.sessaoAberta()) {
            return Situacao.ABERTA;
        }
        if (m.pausado()) {
            return TextosDeLimite.pausaPorFreio(m.limite()) ? Situacao.PAUSADA_PELO_FREIO : Situacao.PAUSADA_PELO_LIMITE;
        }
        return Situacao.FECHADA;
    }

    /**
     * O login do Claude está pendente: o detalhe do projeto não diz isso (só a lista traz o login), então vale o que a lista já
     * viu ({@code urlDeLogin}) ou a tela da sessão pedindo o código ("Paste code here").
     */
    public static boolean loginPendente(TelaClaude tela, String urlDeLogin) {
        if (urlDeLogin != null && !urlDeLogin.isBlank()) {
            return true;
        }
        return tela != null && tela.tela() != null && tela.tela().toLowerCase(Locale.ROOT).contains("paste code here");
    }

    /** Pergunta pendente &gt; executando &gt; esperando você &gt; ocupado (a mesma ordem do painel web). */
    public static Atividade atividade(TelaClaude tela) {
        if (tela.temPergunta()) {
            return Atividade.PERGUNTA_PENDENTE;
        }
        if (tela.executando() != null && !tela.executando().isBlank()) {
            return Atividade.EXECUTANDO;
        }
        return tela.estaEsperando() ? Atividade.ESPERANDO_VOCE : Atividade.OCUPADO;
    }
}
