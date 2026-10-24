package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.api.ErroApi.Conflito;
import br.com.labdesktop.api.modelo.Limite;
import br.com.labdesktop.ui.TextosDeLimite;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * "Ainda acima do limite" (o 409 do Retomar, {@code abrirExcedido} do painel web). Quatro números chegam do agente
 * ({@code usado_pp}, {@code teto_pct}, {@code pct_conta}, {@code freio_pct}) e o texto diz qual regra estoura: o
 * teto do projeto, o freio global, ou os dois. Dois caminhos além de cancelar: <b>Aumentar limite</b> (só quando
 * é o teto do projeto que estoura; abre "Definir limite" e, ao salvar, tenta Retomar de novo) e <b>Liberar até a
 * semana virar</b> (repete com {@code liberar: true}).
 */
public final class DialogoLimiteExcedido extends Dialogo {

    /** O que o usuário escolheu. */
    public enum Escolha { CANCELAR, AUMENTAR, LIBERAR }

    private final Consumer<Escolha> aoEscolher;
    private final Botao aumentar = Botoes.primario("Aumentar limite", null);
    private final Botao liberar = Botoes.secundario("Liberar até a semana virar", null);
    private final Botao cancelar = Botoes.secundario("Cancelar", null);
    private final String titulo;
    private final String texto;
    private final boolean porTeto;
    private boolean escolheu;

    /**
     * @param limite o limite de hoje do projeto, só para dizer quando a semana vira (pode ser {@code null})
     */
    public DialogoLimiteExcedido(String projeto, Conflito conflito, Limite limite, Instant agora, ZoneId fuso,
                                 Consumer<Escolha> aoEscolher) {
        super("Ainda acima do limite");
        this.aoEscolher = aoEscolher;

        Double usado = conflito.usadoPp();
        Integer teto = conflito.tetoPct();
        Double pctConta = conflito.pctConta();
        Integer freio = conflito.freioPct();
        this.porTeto = teto != null && usado != null && usado >= teto;
        boolean porFreio = freio != null && pctConta != null && pctConta >= freio;
        String alvo = TextosDeLimite.nomeAmigavel(projeto);

        String tituloDoTexto = "Ainda acima do limite";
        List<String> partes = new ArrayList<>();
        if (porTeto) {
            tituloDoTexto = "Consumiu " + TextosDeLimite.fmtPp(usado) + " de " + TextosDeLimite.fmtPp(teto);
            partes.add(alvo + " já usou " + TextosDeLimite.fmtPp(usado) + " da semana (estimado) e o teto é "
                + TextosDeLimite.fmtPp(teto) + ".");
        }
        if (porFreio) {
            if (!porTeto) {
                tituloDoTexto = "Freio global ativo";
            }
            partes.add("A conta está em " + TextosDeLimite.fmtPp(pctConta) + " da semana e o freio global pausa as sessões em "
                + TextosDeLimite.fmtPp(freio) + ".");
        }
        if (partes.isEmpty()) {
            partes.add(conflito.mensagem() == null || conflito.mensagem().isBlank()
                ? "O projeto ainda estoura o limite." : conflito.mensagem());
        }
        String zera = limite == null ? "" : TextosDeLimite.zeraTexto(limite.zeraEm(), agora, fuso);
        String fim = "Retomando assim, a sessão pausa de novo no próximo ciclo do painel (até 1 min). "
            + (porTeto ? "Aumente o limite, ou libere" : "Libere") + " o projeto: ele fica sem teto e sem freio até a "
            + "semana virar" + (zera.isEmpty() ? "" : " — " + zera) + ".";
        this.titulo = tituloDoTexto;
        this.texto = String.join(" ", partes) + "\n\n" + fim;

        aumentar.setVisible(porTeto);
        conteudo(Caixa.coluna(14,
            cabecalho("pausa", tituloDoTexto),
            new TextoCorrido(texto, LARGURA_DO_TEXTO).corDoToken(br.com.labdesktop.ui.tema.Tokens.TEXTO2),
            rodape(null, cancelar, liberar, aumentar)));
        botaoPadrao(porTeto ? aumentar : liberar);

        aumentar.addActionListener(e -> escolher(Escolha.AUMENTAR));
        liberar.addActionListener(e -> escolher(Escolha.LIBERAR));
        cancelar.addActionListener(e -> aoCancelar());
    }

    public String tituloDoTexto() {
        return titulo;
    }

    public String textoDoCorpo() {
        return texto;
    }

    public boolean ofereceAumentar() {
        return porTeto;
    }

    public Botao botaoAumentar() {
        return aumentar;
    }

    public Botao botaoLiberar() {
        return liberar;
    }

    public Botao botaoCancelar() {
        return cancelar;
    }

    @Override
    protected void aoCancelar() {
        escolher(Escolha.CANCELAR);
    }

    private void escolher(Escolha e) {
        if (escolheu) {
            return;
        }
        escolheu = true;
        fechar();
        aoEscolher.accept(e);
    }

    @Override
    protected void aoFechar() {
        if (!escolheu) {
            escolheu = true;
            aoEscolher.accept(Escolha.CANCELAR);
        }
    }
}
