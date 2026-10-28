package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.telas.PainelDoRegistro;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.time.ZoneId;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * "Ver tudo" do registro do servidor: a MESMA lista da caixa do Resumo (mais novas em cima, filtro, pausa, seleção e cópia), numa janela maior que o usuário pode redimensionar.
 * Assina o registro só enquanto a janela está aberta e a lista não está em pausa.
 */
public final class DialogoRegistro extends Dialogo {

    private final PainelDoRegistro painel;
    private final Botao fechar = Botoes.secundario("Fechar", "fechar");

    public DialogoRegistro(String nomeDoServidor, PainelDoRegistro.Fonte fonte, CopiadorDeTexto copiador, Notificacoes avisos, Supplier<ZoneId> fuso) {
        super("Registro do servidor");
        this.painel = new PainelDoRegistro(false, fonte, copiador, avisos, fuso);
        Rotulo dica = new Rotulo("As linhas mais novas aparecem em cima. A gravidade (Info, Aviso, Erro) é uma estimativa feita pelo texto de cada linha.").corDoToken(Tokens.TEXTO3).tamanho(12f)
            .truncavel();
        JComponent topo = Caixa.coluna(6, cabecalho("log", "Registro" + (nomeDoServidor == null || nomeDoServidor.isBlank() ? "" : " de " + nomeDoServidor)), dica);
        JPanel conteudo = new Painel(new BorderLayout(0, 8));
        conteudo.add(topo, BorderLayout.NORTH);
        conteudo.add(painel, BorderLayout.CENTER); // estica com a janela: a lista ganha as linhas que o usuário der de altura
        conteudo.add(rodape(null, fechar), BorderLayout.SOUTH);
        conteudo.setPreferredSize(new Dimension(880, 520));
        fechar.addActionListener(e -> fechar());
        conteudo(conteudo);
        botaoPadrao(fechar);
    }

    @Override
    protected boolean redimensionavel() {
        return true;
    }

    public PainelDoRegistro painel() {
        return painel;
    }

    public Botao botaoFechar() {
        return fechar;
    }
}
