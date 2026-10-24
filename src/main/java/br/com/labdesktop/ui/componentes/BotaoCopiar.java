package br.com.labdesktop.ui.componentes;

import java.util.function.Supplier;
import javax.swing.Timer;

/**
 * O botão "Copiar" com resposta visível: ao copiar, o texto dele vira "Copiado" por uns 2 s (e volta), e o app avisa por
 * toast. Se a área de transferência não deixar, avisa que não copiou e diz o que fazer (os textos do app que se copiam
 * ficam selecionáveis na tela). O texto copiado é pedido na hora do clique, então acompanha o que a tela mostra.
 */
public final class BotaoCopiar {

    /** O texto do botão durante o feedback. */
    public static final String ROTULO_DEPOIS_DE_COPIAR = "Copiado";
    /** Quanto tempo o botão fica com o texto "Copiado". */
    private static final int MILISSEGUNDOS_DO_FEEDBACK = 2000;

    private BotaoCopiar() { }

    /**
     * @param rotulo   o texto do botão em repouso ("Copiar", "Copiar comando")
     * @param texto    o que copiar, pedido a cada clique
     * @param copiador de onde vem a área de transferência
     * @param avisos   quem mostra o toast
     */
    public static Botao criar(String rotulo, Supplier<String> texto, CopiadorDeTexto copiador, Notificacoes avisos) {
        Botao botao = Botoes.mini(rotulo, "copiar");
        Timer voltar = new Timer(MILISSEGUNDOS_DO_FEEDBACK, e -> {
            botao.setText(rotulo);
            botao.setIcon(Icones.de("copiar", 14));
        });
        voltar.setRepeats(false);
        botao.addActionListener(e -> {
            if (copiador.copiar(texto.get())) {
                botao.setText(ROTULO_DEPOIS_DE_COPIAR);
                botao.setIcon(Icones.de("ok", 14));
                voltar.restart();
                avisos.info("Copiado.");
            } else {
                avisos.atencao("Não consegui copiar. Selecione o texto e use Cmd+C.");
            }
        });
        return botao;
    }
}
