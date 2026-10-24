package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.Versao;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Rotulo;

/** "Sobre o Lab Desktop": nome, versão e a JVM. */
public final class DialogoSobre extends Dialogo {

    private final Botao fechar = Botoes.primario("Fechar", null);

    public DialogoSobre() {
        super("Sobre o " + Versao.NOME);
        conteudo(Caixa.coluna(12,
            cabecalho("logo", Versao.NOME),
            Rotulo.secundario("Cliente nativo do Painel do Lab: projetos, sessões do Claude e servidores num só lugar."),
            Rotulo.rotuloDeCampo("Versão " + Versao.atual()),
            Rotulo.dica("Java " + System.getProperty("java.version") + " · " + System.getProperty("os.name") + " "
                + System.getProperty("os.arch")),
            rodape(null, fechar)));
        botaoPadrao(fechar);
        fechar.addActionListener(e -> fechar());
    }
}
