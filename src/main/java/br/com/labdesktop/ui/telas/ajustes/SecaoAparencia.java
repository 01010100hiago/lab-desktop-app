package br.com.labdesktop.ui.telas.ajustes;

import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Preferencias.Tema;
import java.util.EnumMap;
import java.util.Map;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JRadioButton;

/**
 * Ajustes, Aparência: o tema (Sistema, Claro ou Escuro, padrão Sistema; troca na hora, sem reiniciar, e com "Sistema" diz qual o sistema está usando agora) e o
 * tamanho do texto (Normal, Grande e Maior, os mesmos passos de Visualizar, Aumentar texto).
 */
final class SecaoAparencia extends Painel {

    private static final long serialVersionUID = 1L;

    private final transient ControleDeAjustes controle;
    private final Map<Tema, JRadioButton> temas = new EnumMap<>(Tema.class);
    private final Map<Integer, JRadioButton> tamanhos = new java.util.LinkedHashMap<>();
    private final Rotulo sistemaAgora = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(13f);
    private boolean atualizando;

    SecaoAparencia(ControleDeAjustes controle) {
        super(new java.awt.BorderLayout());
        this.controle = controle;
        ButtonGroup grupoDoTema = new ButtonGroup();
        JComponent opcoesDeTema = Paginas.cartao(
            opcaoDeTema(grupoDoTema, Tema.SISTEMA, "Sistema", "Segue o claro ou escuro do seu computador e muda sozinho quando ele muda."),
            sistemaAgora,
            opcaoDeTema(grupoDoTema, Tema.CLARO, "Claro", "Fundo claro, sempre."),
            opcaoDeTema(grupoDoTema, Tema.ESCURO, "Escuro", "Fundo escuro, sempre."));
        ButtonGroup grupoDoTexto = new ButtonGroup();
        JComponent opcoesDeTexto = Paginas.cartao(
            opcaoDeTamanho(grupoDoTexto, 100, "Normal"),
            opcaoDeTamanho(grupoDoTexto, 115, "Grande"),
            opcaoDeTamanho(grupoDoTexto, 130, "Maior"));
        add(Paginas.coluna(12,
            Paginas.grupo("Tema"),
            Paginas.ajuda("A troca vale na hora, sem reiniciar o app."),
            opcoesDeTema,
            br.com.labdesktop.ui.componentes.Caixa.espacoVertical(8),
            Paginas.grupo("Tamanho do texto"),
            Paginas.ajuda("Também dá para mudar por Visualizar, Aumentar texto e Diminuir texto."),
            opcoesDeTexto), java.awt.BorderLayout.NORTH);
        atualizar();
        controle.aoMudarOTema(this::atualizar);
        getAccessibleContext().setAccessibleName("Aparência");
    }

    private JComponent opcaoDeTema(ButtonGroup grupo, Tema tema, String nome, String explicacao) {
        JRadioButton r = new JRadioButton(nome);
        r.putClientProperty("html.disable", Boolean.TRUE);
        r.setOpaque(false);
        r.setToolTipText(explicacao);
        r.getAccessibleContext().setAccessibleDescription(explicacao);
        grupo.add(r);
        temas.put(tema, r);
        r.addActionListener(e -> {
            if (!atualizando) {
                controle.trocarTema(tema, this::atualizar);
            }
        });
        return br.com.labdesktop.ui.componentes.Caixa.coluna(2, r, new Rotulo(explicacao).corDoToken(Tokens.TEXTO2).tamanho(12.5f));
    }

    private JComponent opcaoDeTamanho(ButtonGroup grupo, int porcento, String nome) {
        JRadioButton r = new JRadioButton(nome + " (" + porcento + " %)");
        r.putClientProperty("html.disable", Boolean.TRUE);
        r.setOpaque(false);
        grupo.add(r);
        tamanhos.put(porcento, r);
        r.addActionListener(e -> {
            if (!atualizando) {
                controle.mudarTamanhoDoTexto(porcento);
            }
        });
        return r;
    }

    /** Põe na tela o que está valendo (tema escolhido, o que o sistema usa agora e o tamanho do texto). */
    void atualizar() {
        atualizando = true;
        try {
            Tema escolha = controle.preferencias().tema();
            temas.forEach((t, r) -> r.setSelected(t == escolha));
            sistemaAgora.setText("Agora o sistema está usando o modo " + (controle.sistemaEstaEscuro() ? "escuro" : "claro") + ".");
            sistemaAgora.setVisible(escolha == Tema.SISTEMA);
            int texto = controle.tamanhoDoTexto();
            tamanhos.forEach((p, r) -> r.setSelected(p == texto));
        } finally {
            atualizando = false;
        }
    }

    // ---- para os testes ----

    JRadioButton opcaoDoTema(Tema t) {
        return temas.get(t);
    }

    JRadioButton opcaoDoTamanho(int porcento) {
        return tamanhos.get(porcento);
    }

    String textoDoSistemaAgora() {
        return sistemaAgora.isVisible() ? sistemaAgora.getText() : "";
    }
}
