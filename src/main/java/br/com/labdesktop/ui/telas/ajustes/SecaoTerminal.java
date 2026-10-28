package br.com.labdesktop.ui.telas.ajustes;

import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Preferencias;
import java.awt.BorderLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * Ajustes, Terminal: as preferências do terminal que o app já tinha (o arquivo de preferências as guardava e o terminal as lia, mas não havia onde mudar): tamanho da letra,
 * Option como Meta, copiar ao selecionar e tomar a sessão do Claude dos outros computadores. Cada mudança vale para os terminais que o usuário abrir daqui em diante (o que já
 * está aberto segue como estava).
 */
final class SecaoTerminal extends Painel {

    private static final long serialVersionUID = 1L;

    private final transient ControleDeAjustes controle;
    private final JSpinner tamanhoDaLetra;
    private final JCheckBox opcaoComoMeta = new JCheckBox("Usar a tecla Option como Meta");
    private final JCheckBox copiarAoSelecionar = new JCheckBox("Copiar sozinho o que eu selecionar");
    private final JCheckBox donoExclusivo = new JCheckBox("Ao abrir a sessão do Claude, desconectar quem mais estiver nela");
    private boolean atualizando;

    SecaoTerminal(ControleDeAjustes controle) {
        super(new BorderLayout());
        this.controle = controle;
        Preferencias p = controle.preferencias();
        tamanhoDaLetra = new JSpinner(new SpinnerNumberModel(p.tamanhoFonteTerminal(), Preferencias.FONTE_MIN, Preferencias.FONTE_MAX, 1));
        tamanhoDaLetra.getAccessibleContext().setAccessibleName("Tamanho da letra do terminal, em pontos");
        tamanhoDaLetra.addChangeListener(e -> {
            if (!atualizando) {
                int pontos = (Integer) tamanhoDaLetra.getValue();
                controle.atualizarPreferencias(x -> x.comTamanhoFonteTerminal(pontos));
            }
        });
        ligar(opcaoComoMeta, v -> controle.atualizarPreferencias(x -> x.comOpcaoComoMeta(v)));
        ligar(copiarAoSelecionar, v -> controle.atualizarPreferencias(x -> x.comCopiarAoSelecionar(v)));
        ligar(donoExclusivo, v -> controle.atualizarPreferencias(x -> x.comDonoExclusivo(v)));
        Rotulo tamanho = new Rotulo("Tamanho da letra (pontos)");
        add(Paginas.coluna(12,
            Paginas.grupo("Terminal"),
            Paginas.ajuda("Vale para os terminais que você abrir daqui em diante. O que já está aberto continua como estava."),
            Paginas.cartao(
                Caixa.linha(10, tamanho, tamanhoDaLetra, Caixa.mola()),
                opcao(opcaoComoMeta, "Desligado, o Option digita os caracteres do teclado (acentos e símbolos do ABNT2). Ligado, ele manda ESC antes da letra, como o Meta dos programas de terminal."),
                opcao(copiarAoSelecionar, "Desligado, só copia quando você pede. Ligado, tudo o que você seleciona vai para a área de transferência, e isso apaga o que estava lá."),
                opcao(donoExclusivo, "Desligado, o Claude pode ficar aberto em vários computadores ao mesmo tempo. Ligado, abrir aqui derruba os outros.")),
            Caixa.espacoVertical(4)), BorderLayout.NORTH);
        atualizar();
        getAccessibleContext().setAccessibleName("Terminal");
    }

    private void ligar(JCheckBox caixa, java.util.function.Consumer<Boolean> aoMudar) {
        caixa.putClientProperty("html.disable", Boolean.TRUE);
        caixa.setOpaque(false);
        caixa.addActionListener(e -> {
            if (!atualizando) {
                aoMudar.accept(caixa.isSelected());
            }
        });
    }

    private static JComponent opcao(JCheckBox caixa, String explicacao) {
        return Caixa.coluna(2, caixa, new Rotulo(explicacao).corDoToken(Tokens.TEXTO2).tamanho(12.5f).truncavel());
    }

    /** Põe na tela as preferências de agora. */
    void atualizar() {
        atualizando = true;
        try {
            Preferencias p = controle.preferencias();
            tamanhoDaLetra.setValue(p.tamanhoFonteTerminal());
            opcaoComoMeta.setSelected(p.opcaoComoMeta());
            copiarAoSelecionar.setSelected(p.copiarAoSelecionar());
            donoExclusivo.setSelected(p.donoExclusivo());
        } finally {
            atualizando = false;
        }
    }

    JSpinner tamanhoDaLetra() {
        return tamanhoDaLetra;
    }

    JCheckBox opcaoComoMeta() {
        return opcaoComoMeta;
    }

    JCheckBox copiarAoSelecionar() {
        return copiarAoSelecionar;
    }

    JCheckBox donoExclusivo() {
        return donoExclusivo;
    }
}
