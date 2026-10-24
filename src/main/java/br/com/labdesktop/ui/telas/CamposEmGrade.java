package br.com.labdesktop.ui.telas;

import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;

/**
 * Pares "rótulo: valor" em duas colunas (o {@code dl.kv} do painel web). O valor pode vir com gravidade: então ganha o
 * ícone de alerta e a cor, sempre junto do texto, nunca só a cor.
 */
final class CamposEmGrade extends Painel {

    private static final long serialVersionUID = 1L;

    /** Um par. {@code gravidade} {@code null} ou {@code NEUTRO} é o texto comum. */
    record Campo(String rotulo, String valor, Gravidade gravidade) {
        Campo(String rotulo, String valor) {
            this(rotulo, valor, null);
        }
    }

    private List<Campo> campos = List.of();

    CamposEmGrade() {
        super(new GridBagLayout());
    }

    void definir(List<Campo> novos) {
        this.campos = List.copyOf(novos);
        removeAll();
        int y = 0;
        for (Campo c : campos) {
            Rotulo r = new Rotulo(c.rotulo()).corDoToken(Tokens.TEXTO2).tamanho(12.5f);
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 0;
            g.gridy = y;
            g.anchor = GridBagConstraints.NORTHWEST;
            g.insets = new Insets(2, 0, 2, 14);
            add(r, g);

            String valor = c.valor() == null || c.valor().isBlank() ? FormatosDoServidor.SEM_DADO : c.valor();
            Rotulo v = new Rotulo(valor).tamanho(12.5f).truncavel();
            Gravidade grav = c.gravidade();
            if (grav != null && grav != Gravidade.NEUTRO) {
                v.corDoToken(grav.tokenDoTexto());
                v.setIcon(Icones.colorido(grav == Gravidade.OK ? "ok-circulo" : "alerta", 14, grav.tokenDoTexto()));
                v.setIconTextGap(6);
            }
            v.setToolTipText(valor);
            GridBagConstraints h = new GridBagConstraints();
            h.gridx = 1;
            h.gridy = y;
            h.weightx = 1;
            h.fill = GridBagConstraints.HORIZONTAL;
            h.anchor = GridBagConstraints.NORTHWEST;
            h.insets = new Insets(2, 0, 2, 0);
            add(v, h);
            y++;
        }
        revalidate();
        repaint();
    }

    /** Os pares atuais (para os testes). */
    List<Campo> campos() {
        return new ArrayList<>(campos);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
