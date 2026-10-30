package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import java.util.function.Consumer;
import javax.swing.JCheckBox;
import javax.swing.JComponent;

/**
 * Pergunta de sim ou não antes de uma ação que interrompe algo (reabrir a sessão do Claude). O texto é do app (nunca
 * do servidor); o botão de confirmar leva o verbo da ação, não "OK".
 *
 * <p>OPCIONAL: uma caixa "Não perguntar de novo" ({@link #comNaoPerguntarDeNovo}), desmarcada de partida. Ela só vale quando o usuário CONFIRMA; cancelar
 * (o botão, Esc, fechar a janela) ignora a marca. O diálogo só ENTREGA a escolha: quem pergunta decide o que fazer com ela (gravar a preferência
 * e dar um jeito de desfazer). Quem usa a caixa é decidido caso a caso: só perguntas que o usuário pode razoavelmente querer silenciar e que não
 * destroem dado no servidor; as de ações destrutivas, de apagar, remover, instalar e de sair do app com pendências continuam SEMPRE perguntando.
 */
public final class DialogoConfirmar extends Dialogo {

    /**
     * O que o usuário decidiu. {@code naoPerguntarDeNovo} só é {@code true} quando ele CONFIRMOU com a caixa marcada; ao cancelar é sempre {@code false}.
     */
    public record Decisao(boolean confirmou, boolean naoPerguntarDeNovo) { }

    private final Consumer<Decisao> aoDecidir;
    private final Botao confirmar;
    private final Botao cancelar;
    private final JCheckBox naoPerguntar;
    private boolean decidiu;

    /**
     * @param icone     nome do ícone do cabeçalho ({@code "atualizar"}, {@code "alerta"}...)
     * @param verbo     rótulo do botão de confirmar ("Reabrir")
     * @param perigoso  o botão de confirmar sai em terracota
     * @param aoDecidir {@code true} = confirmou; {@code false} = cancelou; chamado uma vez
     */
    public DialogoConfirmar(String titulo, String icone, String texto, String verbo, boolean perigoso,
                            Consumer<Boolean> aoDecidir) {
        this(titulo, icone, texto, verbo, perigoso, false, aoDecidir);
    }

    /**
     * @param cancelarEPadrao {@code true}: o botão em destaque (o do Enter) é Cancelar, para ações que não se desfazem ou que
     *                        mexem no que o usuário não pediu de forma explícita (salvar com sudo, abrir o servidor inteiro)
     */
    public DialogoConfirmar(String titulo, String icone, String texto, String verbo, boolean perigoso, boolean cancelarEPadrao,
                            Consumer<Boolean> aoDecidir) {
        this(titulo, icone, texto, verbo, perigoso, cancelarEPadrao, null, null, d -> aoDecidir.accept(d.confirmou()));
    }

    /**
     * Como a pergunta com Cancelar no Enter, mais a caixa "Não perguntar de novo" (desmarcada, abaixo do texto e acima dos botões; Tab chega nela e Espaço marca).
     *
     * @param rotuloDaCaixa o texto da caixa ("Não perguntar de novo")
     * @param dicaDaCaixa   uma linha pequena sob a caixa dizendo onde desfazer ({@code null}: sem dica)
     * @param aoDecidir     chamado uma vez com a decisão; cancelar entrega {@code (false, false)}
     */
    public static DialogoConfirmar comNaoPerguntarDeNovo(String titulo, String icone, String texto, String verbo, boolean perigoso,
                                                         String rotuloDaCaixa, String dicaDaCaixa, Consumer<Decisao> aoDecidir) {
        if (rotuloDaCaixa == null || rotuloDaCaixa.isBlank()) {
            throw new IllegalArgumentException("A caixa precisa de um texto.");
        }
        return new DialogoConfirmar(titulo, icone, texto, verbo, perigoso, true, rotuloDaCaixa, dicaDaCaixa, aoDecidir);
    }

    private DialogoConfirmar(String titulo, String icone, String texto, String verbo, boolean perigoso, boolean cancelarEPadrao,
                             String rotuloDaCaixa, String dicaDaCaixa, Consumer<Decisao> aoDecidir) {
        super(titulo);
        this.aoDecidir = aoDecidir;
        this.cancelar = cancelarEPadrao ? Botoes.primario("Cancelar", null) : Botoes.secundario("Cancelar", null);
        this.confirmar = perigoso ? Botoes.perigo(verbo, null) : Botoes.primario(verbo, null);
        JComponent caixa = null;
        if (rotuloDaCaixa == null) {
            naoPerguntar = null;
        } else {
            naoPerguntar = new JCheckBox(rotuloDaCaixa);
            naoPerguntar.putClientProperty("html.disable", Boolean.TRUE);
            naoPerguntar.setOpaque(false);
            naoPerguntar.getAccessibleContext().setAccessibleName(rotuloDaCaixa);
            if (dicaDaCaixa != null && !dicaDaCaixa.isBlank()) {
                naoPerguntar.getAccessibleContext().setAccessibleDescription(dicaDaCaixa);
                var dica = dicaCorrida(dicaDaCaixa);
                dica.setFocusable(false); // a explicação já vai na descrição da caixa para leitores de tela; Tab não precisa parar nela
                caixa = Caixa.coluna(2, naoPerguntar, dica);
            } else {
                caixa = naoPerguntar;
            }
        }
        conteudo(Caixa.coluna(14, cabecalho(icone, titulo), paragrafo(texto), caixa, rodape(null, cancelar, confirmar)));
        botaoPadrao(cancelarEPadrao ? cancelar : confirmar);
        confirmar.addActionListener(e -> decidir(true));
        cancelar.addActionListener(e -> aoCancelar());
    }

    /** A caixa "Não perguntar de novo", ou {@code null} se este diálogo não tem. */
    public JCheckBox caixaNaoPerguntarDeNovo() {
        return naoPerguntar;
    }

    public Botao botaoConfirmar() {
        return confirmar;
    }

    public Botao botaoCancelar() {
        return cancelar;
    }

    @Override
    protected void aoCancelar() {
        decidir(false);
    }

    private void decidir(boolean sim) {
        if (decidiu) {
            return;
        }
        decidiu = true;
        boolean marcada = sim && naoPerguntar != null && naoPerguntar.isSelected(); // a marca só vale ao confirmar
        fechar();
        aoDecidir.accept(new Decisao(sim, marcada));
    }

    @Override
    protected void aoFechar() {
        if (!decidiu) {
            decidiu = true;
            aoDecidir.accept(new Decisao(false, false));
        }
    }
}
