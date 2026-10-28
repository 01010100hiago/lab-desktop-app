package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.Versao;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.telas.ajustes.PainelSobre;
import br.com.labdesktop.util.InformacoesDoApp;

/**
 * "Sobre o Lab Desktop" numa janelinha, para quando não há janela principal (o cofre está bloqueado): o mesmo {@link PainelSobre} que a tela Ajustes mostra na seção
 * Sobre. Com a janela principal aberta, "Sobre" leva aos Ajustes.
 */
public final class DialogoSobre extends Dialogo {

    private final Botao fechar = Botoes.primario("Fechar", null);
    private final PainelSobre painel;

    public DialogoSobre(InformacoesDoApp info) {
        super("Sobre o " + Versao.NOME);
        // sem a janela principal não há onde mostrar o "Copiado": o botão já troca o próprio texto
        Notificacoes semAvisos = new Notificacoes() {
            @Override
            public void ok(String texto) { }

            @Override
            public void info(String texto) { }

            @Override
            public void atencao(String texto) { }

            @Override
            public void erro(String texto) { }

            @Override
            public void comAcao(String texto, String rotuloDaAcao, Runnable acao) { }
        };
        painel = new PainelSobre(info, CopiadorDeTexto.doSistema(), semAvisos);
        conteudoComRodape(painel, rodape(null, fechar));
        botaoPadrao(fechar);
        fechar.addActionListener(e -> fechar());
    }

    public PainelSobre painel() {
        return painel;
    }

    @Override
    protected boolean redimensionavel() {
        return true;
    }
}
