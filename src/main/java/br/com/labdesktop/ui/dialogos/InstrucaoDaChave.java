package br.com.labdesktop.ui.dialogos;

import static br.com.labdesktop.ui.dialogos.Dialogo.LARGURA_DO_TEXTO;

import br.com.labdesktop.cofre.InfoChave;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.BotaoCopiar;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.SecaoRecolhivel;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * O quadro "falta instalar esta chave no servidor", usado no passo da chave e, se o servidor recusar a chave, no passo da
 * conexão (cada passo tem o seu, porque um componente Swing só cabe num pai). Mostra a chave PÚBLICA (a linha que começa
 * com {@code ssh-ed25519}) com o botão Copiar, diz em uma frase o que fazer com ela e deixa o comando técnico num "Ver
 * comando" recolhido, para quem tem acesso ao servidor por SSH.
 */
final class InstrucaoDaChave {

    private final Painel quadro = new Painel(new BorderLayout(0, 8)).fundo(Tokens.SUPERFICIE).borda(Tokens.BORDA).raio(10);
    private final TextoCorrido linhaPublica = new TextoCorrido("", LARGURA_DO_TEXTO - 150).mono(12f);
    private final TextoCorrido proximoPasso = new TextoCorrido("", LARGURA_DO_TEXTO - 24).corDoToken(Tokens.TEXTO);
    private final TextoCorrido comando = new TextoCorrido("", LARGURA_DO_TEXTO - 24).mono(12f);
    private final Botao copiarChave;
    private final Botao copiarComando;
    private final SecaoRecolhivel verComando;
    private String publica = "";
    private String comandoAtual = "";

    InstrucaoDaChave(CopiadorDeTexto copiador, Notificacoes avisos, Runnable aoMudarTamanho) {
        copiarChave = BotaoCopiar.criar("Copiar", () -> publica, copiador, avisos);
        copiarComando = BotaoCopiar.criar("Copiar comando", () -> comandoAtual, copiador, avisos);
        JComponent detalhes = Caixa.coluna(8,
            new TextoCorrido("Se você já tem acesso ao servidor por SSH, pode colar este comando nele:", LARGURA_DO_TEXTO - 24)
                .corDoToken(Tokens.TEXTO2).tamanho(12.5f),
            comando,
            Caixa.linha(0, copiarComando, Caixa.mola()),
            new TextoCorrido(InfoChave.RESTRICOES_DA_LINHA, LARGURA_DO_TEXTO - 24).corDoToken(Tokens.TEXTO3).tamanho(12f));
        verComando = new SecaoRecolhivel("Ver comando", "Esconder o comando", detalhes, aoMudarTamanho);

        JPanel linhaDaChave = new Painel(new BorderLayout(10, 0));
        linhaDaChave.add(linhaPublica, BorderLayout.CENTER);
        JPanel lado = new Painel(new BorderLayout());
        lado.add(copiarChave, BorderLayout.NORTH);
        linhaDaChave.add(lado, BorderLayout.EAST);

        quadro.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 12, 10, 12));
        quadro.add(Caixa.coluna(8,
            Rotulo.rotuloDeCampo("Chave pública deste servidor"),
            linhaDaChave,
            proximoPasso,
            verComando), BorderLayout.CENTER);
        quadro.setVisible(false);
    }

    JComponent componente() {
        return quadro;
    }

    /**
     * Mostra a chave e o próximo passo para o usuário e a porta do agente de agora.
     *
     * @param usuario o usuário do servidor que vai receber a chave
     */
    void atualizar(InfoChave chave, String usuario, int portaDoAgente) {
        publica = chave.publica();
        linhaPublica.setText(publica);
        proximoPasso.setText(textoDoProximoPasso(usuario));
        try {
            comandoAtual = chave.comandoDeInstalacao(portaDoAgente);
        } catch (IllegalArgumentException e) {
            comandoAtual = "";
        }
        comando.setText(comandoAtual.isEmpty() ? "(informe uma porta do agente válida para ver o comando)" : comandoAtual);
        quadro.setVisible(true);
    }

    void esconder() {
        quadro.setVisible(false);
    }

    /** A frase que diz o que fazer com a chave, sem termo técnico além do que o provedor mostra na tela dele. */
    static String textoDoProximoPasso(String usuario) {
        return "Falta instalar esta chave no servidor: peça a quem administra o servidor para adicioná-la ao acesso do "
            + "usuário " + usuario + " (no painel do provedor, é o campo 'chaves SSH').";
    }

    boolean visivel() {
        return quadro.isVisible();
    }

    String chavePublicaMostrada() {
        return linhaPublica.getText();
    }

    String proximoPassoMostrado() {
        return proximoPasso.getText();
    }

    String comandoMostrado() {
        return comando.getText();
    }

    Botao botaoCopiarChave() {
        return copiarChave;
    }

    Botao botaoCopiarComando() {
        return copiarComando;
    }

    SecaoRecolhivel secaoDoComando() {
        return verComando;
    }
}
