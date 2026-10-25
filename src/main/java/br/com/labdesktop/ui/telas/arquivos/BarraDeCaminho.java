package br.com.labdesktop.ui.telas.arquivos;

import br.com.labdesktop.arquivos.CaminhoRemoto;
import br.com.labdesktop.arquivos.Escopo;
import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.tema.Tokens;
import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

/**
 * As migalhas do caminho ("orbita › src › componentes"), cada trecho clicável, a partir da RAIZ do modo (o modo projeto nunca
 * mostra o que está acima da pasta do projeto). O último trecho é o lugar onde o usuário está e não é botão. Quando não cabe na
 * largura, os trechos do começo viram "…" com um menu dos que ficaram escondidos; a raiz e o trecho atual nunca somem.
 */
final class BarraDeCaminho extends Painel {

    private static final long serialVersionUID = 1L;
    private static final int LARGURA_DO_SEPARADOR = 18;
    private static final int LARGURA_MAXIMA_DE_UM_TRECHO = 260;

    /** Um trecho: o caminho completo até ele e o texto. */
    record Trecho(String caminho, String texto) { }

    private final Consumer<String> irPara;
    private final List<Trecho> trechos = new ArrayList<>();
    private final List<Component> botoes = new ArrayList<>();
    private final List<Rotulo> separadores = new ArrayList<>();
    private final Botao reticencias = Botoes.fantasma("…", null);
    private int escondidos;

    @SuppressWarnings("this-escape")
    BarraDeCaminho(Consumer<String> irPara) {
        super(null);
        this.irPara = irPara;
        reticencias.setToolTipText("Pastas do caminho que não cabem aqui");
        reticencias.getAccessibleContext().setAccessibleName("Mais pastas do caminho");
        reticencias.addActionListener(e -> menuDosEscondidos());
        reticencias.putClientProperty(FlatClientProperties.STYLE, "margin: 2,6,2,6");
        getAccessibleContext().setAccessibleName("Caminho");
    }

    /** Refaz as migalhas para a pasta atual. */
    void definir(Escopo escopo, String caminhoAtual) {
        trechos.clear();
        trechos.add(new Trecho(escopo.raiz(), escopo.nome()));
        String acumulado = escopo.raiz();
        for (String t : escopo.trechosAPartirDaRaiz(caminhoAtual)) {
            acumulado = acumulado.equals("/") ? "/" + t : acumulado + "/" + t;
            trechos.add(new Trecho(acumulado, t));
        }
        removeAll();
        botoes.clear();
        separadores.clear();
        for (int i = 0; i < trechos.size(); i++) {
            Trecho t = trechos.get(i);
            boolean ultimo = i == trechos.size() - 1;
            String texto = encurtar(TextoSeguro.limparLinha(t.texto()));
            Component c;
            if (ultimo) {
                Rotulo r = new Rotulo(texto).negrito();
                r.setToolTipText(CaminhoRemoto.RAIZ.equals(t.caminho()) ? "/" : t.caminho());
                r.getAccessibleContext().setAccessibleName("Você está em " + texto);
                c = r;
            } else {
                Botao b = Botoes.fantasma(texto, null);
                b.putClientProperty(FlatClientProperties.STYLE, "margin: 2,6,2,6");
                b.setToolTipText(t.caminho());
                b.getAccessibleContext().setAccessibleName("Ir para " + texto);
                b.addActionListener(e -> irPara.accept(t.caminho()));
                c = b;
            }
            botoes.add(c);
            add(c);
        }
        for (int i = 0; i < trechos.size(); i++) { // uma reserva de separadores: o layout usa os que precisar
            Rotulo sep = new Rotulo("›").corDoToken(Tokens.TEXTO3).aoCentro();
            sep.getAccessibleContext().setAccessibleName("");
            separadores.add(sep);
            add(sep);
        }
        add(reticencias);
        escondidos = 0;
        revalidate();
        repaint();
    }

    private static String encurtar(String texto) {
        return texto.length() <= 40 ? texto : texto.substring(0, 18) + "…" + texto.substring(texto.length() - 18);
    }

    /** Os textos dos trechos, da raiz ao atual (para os testes). */
    List<String> textos() {
        List<String> l = new ArrayList<>();
        for (Component c : botoes) {
            l.add(c instanceof Botao b ? b.getText() : ((Rotulo) c).getText());
        }
        return l;
    }

    /** Os trechos que aparecem como botão e os que estão escondidos atrás de "…" (para os testes). */
    int escondidos() {
        return escondidos;
    }

    Botao botaoDoTrecho(int i) {
        return botoes.get(i) instanceof Botao b ? b : null;
    }

    Botao botaoDeReticencias() {
        return reticencias;
    }

    private void menuDosEscondidos() {
        JPopupMenu menu = new JPopupMenu();
        for (int i = 1; i <= escondidos && i < trechos.size() - 1; i++) {
            Trecho t = trechos.get(i);
            JMenuItem item = new JMenuItem(encurtar(TextoSeguro.limparLinha(t.texto())));
            item.putClientProperty("html.disable", Boolean.TRUE);
            item.addActionListener(e -> irPara.accept(t.caminho()));
            menu.add(item);
        }
        menu.show(reticencias, 0, reticencias.getHeight());
    }

    // ---------------------------------------------------------------------------------------------
    // Layout próprio: decide quantos trechos do começo escondem
    // ---------------------------------------------------------------------------------------------

    private int larguraDe(Component c) {
        return Math.min(c.getPreferredSize().width, LARGURA_MAXIMA_DE_UM_TRECHO);
    }

    @Override
    public Dimension getPreferredSize() {
        int altura = 34;
        for (Component c : botoes) {
            altura = Math.max(altura, c.getPreferredSize().height);
        }
        return new Dimension(360, altura);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(120, getPreferredSize().height);
    }

    @Override
    public void doLayout() {
        int n = botoes.size();
        if (n == 0) {
            reticencias.setVisible(false);
            return;
        }
        Insets in = getInsets();
        int disponivel = getWidth() - in.left - in.right;
        int escondido = 0;
        for (; escondido <= Math.max(0, n - 2); escondido++) {
            int total = larguraDe(botoes.get(0));
            if (n > 1) {
                total += LARGURA_DO_SEPARADOR;
            }
            if (escondido > 0) {
                total += larguraDe(reticencias) + LARGURA_DO_SEPARADOR;
            }
            for (int i = 1 + escondido; i < n; i++) {
                total += larguraDe(botoes.get(i)) + (i < n - 1 ? LARGURA_DO_SEPARADOR : 0);
            }
            if (total <= disponivel) {
                break;
            }
        }
        escondido = Math.min(escondido, Math.max(0, n - 2));
        this.escondidos = escondido;

        List<Component> ordem = new ArrayList<>();
        int usado = 0;
        ordem.add(botoes.get(0));
        if (n > 1) {
            if (escondido > 0) {
                ordem.add(separadores.get(usado++));
                ordem.add(reticencias);
            }
            for (int i = 1 + escondido; i < n; i++) {
                ordem.add(separadores.get(usado++));
                ordem.add(botoes.get(i));
            }
        }
        for (Component c : botoes) {
            c.setVisible(false);
        }
        separadores.forEach(sp -> sp.setVisible(false));
        reticencias.setVisible(false);
        int altura = getHeight() - in.top - in.bottom;
        int x = in.left;
        for (Component c : ordem) {
            c.setVisible(true);
            int w = separadores.contains(c) ? LARGURA_DO_SEPARADOR : larguraDe(c);
            int h = separadores.contains(c) ? altura : Math.min(c.getPreferredSize().height, altura);
            c.setBounds(x, in.top + (altura - h) / 2, w, h);
            x += w;
        }
    }
}
