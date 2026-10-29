package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.transporte.FerramentaExterna;
import br.com.labdesktop.transporte.RelatorioDeFerramentas;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.BotaoCopiar;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;

/**
 * "Ferramentas deste computador": o que a conferência achou do {@code aws}, do plugin do Session Manager e do {@code cloudflared}. Por programa: "encontrada em
 * &lt;caminho&gt;" ou "não encontrada" e, se não está, o passo de instalação do sistema em que o app roda, como TEXTO que se copia. O app NUNCA instala nem executa a instalação: só mostra.
 */
public final class DialogoDasFerramentas extends Dialogo {

    /** A frase de cima: onde ficam os programas e o que o app faz (e não faz) com eles. */
    public static final String TEXTO_DE_ABERTURA = "Estes programas ficam neste computador, não no servidor. O app só confere se eles estão aqui: não instala nada e não faz login "
        + "por você. Se faltar algum, copie o passo e rode no terminal.";

    private final RelatorioDeFerramentas relatorio;
    private final Botao fechar = Botoes.primario("Fechar", null);
    private final List<TextoCorrido> situacoes = new ArrayList<>();
    private final List<TextoCorrido> passos = new ArrayList<>();
    private final List<Botao> botoesDeCopiar = new ArrayList<>();

    public DialogoDasFerramentas(RelatorioDeFerramentas relatorio, CopiadorDeTexto copiador, Notificacoes avisos) {
        super("Ferramentas deste computador");
        this.relatorio = relatorio;
        List<Component> corpo = new ArrayList<>();
        corpo.add(cabecalho("sistema", "Ferramentas deste computador"));
        corpo.add(paragrafo(TEXTO_DE_ABERTURA));
        for (RelatorioDeFerramentas.Item item : relatorio.itens()) {
            corpo.add(bloco(item, copiador, avisos));
        }
        conteudoComRodape(Caixa.coluna(10, corpo.toArray(new Component[0])), rodape(null, fechar));
        botaoPadrao(fechar);
        fechar.addActionListener(e -> fechar());
    }

    private JComponent bloco(RelatorioDeFerramentas.Item item, CopiadorDeTexto copiador, Notificacoes avisos) {
        boolean achada = item.achada();
        Rotulo nome = new Rotulo(item.ferramenta().nomeBase()).negrito();
        nome.setIcon(Icones.colorido(achada ? "ok-circulo" : "alerta", 16, achada ? Tokens.OK : Tokens.TEXTO3));
        nome.setIconTextGap(8);
        Rotulo uso = Rotulo.dica(usadoPor(item.ferramenta()));
        TextoCorrido situacao = new TextoCorrido(item.situacao(), LARGURA_DO_TEXTO).mono(12f).corDoToken(Tokens.TEXTO2);
        situacao.setName("situacao-" + item.ferramenta().nomeBase());
        situacoes.add(situacao);
        List<Component> linhas = new ArrayList<>(List.of(Caixa.linha(8, nome, uso, Caixa.mola()), situacao));
        if (!achada) {
            TextoCorrido passo = new TextoCorrido(item.textoParaCopiar(), LARGURA_DO_TEXTO - 90).mono(12.5f).corDoToken(Tokens.TEXTO);
            passo.setName("passo-" + item.ferramenta().nomeBase());
            passos.add(passo);
            Botao copiar = BotaoCopiar.criar("Copiar", item::textoParaCopiar, copiador, avisos);
            copiar.setName("copiar-" + item.ferramenta().nomeBase());
            botoesDeCopiar.add(copiar);
            linhas.add(dicaCorrida("Para instalar:"));
            linhas.add(Caixa.linha(10, passo, copiar));
        }
        return Caixa.coluna(3, linhas.toArray(new Component[0]));
    }

    private static String usadoPor(FerramentaExterna f) {
        return switch (f) {
            case AWS, PLUGIN_SSM -> "usado pelo modo AWS Session Manager (SSM)";
            case CLOUDFLARED -> "usado pelo modo Cloudflare";
        };
    }

    // ---------------------------------------------------------------------------------------------
    // Para os testes
    // ---------------------------------------------------------------------------------------------

    public RelatorioDeFerramentas relatorio() {
        return relatorio;
    }

    /** As frases "encontrada em ..." / "não encontrada", na ordem do relatório. */
    public List<String> textosDasSituacoes() {
        return situacoes.stream().map(TextoCorrido::getText).toList();
    }

    /** Os passos de instalação copiáveis (só dos que faltam), na ordem do relatório. */
    public List<String> textosDosPassos() {
        return passos.stream().map(TextoCorrido::getText).toList();
    }

    public List<Botao> botoesDeCopiar() {
        return botoesDeCopiar;
    }

    public Botao botaoFechar() {
        return fechar;
    }
}
