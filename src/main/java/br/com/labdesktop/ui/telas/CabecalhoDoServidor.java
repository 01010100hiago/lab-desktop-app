package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.modelo.InfoServidor;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.ui.ApresentacaoDoEstado;
import br.com.labdesktop.ui.ApresentacaoDoEstado.Apresentacao;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.FichaRotulada;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.Selo;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Formato;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * O cabeçalho da Visão geral: o nome do servidor, o estado da conexão (texto e ícone) e, embaixo, em palavras simples,
 * a máquina (hostname), o sistema, o kernel e há quanto tempo está ligada, mais a hora da última leitura e o botão
 * Atualizar. Sem leitura ainda, a linha de baixo diz que está esperando.
 */
final class CabecalhoDoServidor extends Painel {

    private static final long serialVersionUID = 1L;

    private final Rotulo nome = Rotulo.titulo("Servidor");
    private final Selo estado = new Selo("", br.com.labdesktop.ui.Gravidade.NEUTRO, "info");
    /** Só a frase de "esperando a primeira leitura"; com a leitura, quem fala é a ficha. */
    private final Rotulo linhaDeBaixo = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12.5f).truncavel();
    private final FichaRotulada ficha = new FichaRotulada(4, 24);
    private final Rotulo leitura = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(12f);
    private final Botao atualizar = Botoes.mini("Atualizar", "atualizar");

    CabecalhoDoServidor(Runnable aoAtualizar) {
        super(new BorderLayout(0, 6));
        nome.setIcon(Icones.colorido("servidor", 22, Tokens.ACENTO));
        nome.setIconTextGap(10);
        add(Caixa.linha(12, nome, new AEsquerda(estado), Caixa.mola(), leitura, atualizar), BorderLayout.NORTH);
        add(Caixa.coluna(0, linhaDeBaixo, ficha), BorderLayout.CENTER);
        atualizar.addActionListener(e -> aoAtualizar.run());
        atualizar.setToolTipText("Consulta o servidor agora");
        getAccessibleContext().setAccessibleName("Cabeçalho do servidor");
        setName("cabecalho-do-servidor");
        atualizar(null, null, null, ZoneId.systemDefault());
    }

    void atualizar(EstadoServidor e, InfoServidor info, Instant ultimaLeitura, ZoneId fuso) {
        if (e != null) {
            nome.setText(e.nome());
            Apresentacao a = ApresentacaoDoEstado.de(e);
            estado.atualizar(a.texto(), a.gravidade(), a.icone());
        } else {
            estado.atualizar("Lendo…", br.com.labdesktop.ui.Gravidade.NEUTRO, "atualizar");
        }
        if (info == null) {
            linhaDeBaixo.setText("Esperando a primeira leitura do servidor…");
            linhaDeBaixo.setVisible(true);
            ficha.definir(List.of());
            leitura.setText("");
        } else {
            linhaDeBaixo.setText("");
            linhaDeBaixo.setVisible(false);
            ficha.definir(List.of(
                FichaRotulada.Item.de("Máquina", info.hostname() == null ? FormatosDoServidor.SEM_DADO : info.hostname()),
                FichaRotulada.Item.de("Sistema", info.sistema() == null ? FormatosDoServidor.SEM_DADO : info.sistema()),
                FichaRotulada.Item.de("Kernel", info.kernel() == null ? FormatosDoServidor.SEM_DADO : info.kernel()),
                FichaRotulada.Item.de("Ligada há", FormatosDoServidor.tempoLigadoPorExtenso(info.uptime()))));
            leitura.setText(ultimaLeitura == null ? "" : "Leitura das " + Formato.fmtHora(ultimaLeitura, fuso));
        }
        revalidate();
        repaint();
    }

    Botao botaoAtualizar() {
        return atualizar;
    }

    String textoDoNome() {
        return nome.getText();
    }

    String textoDoEstado() {
        return estado.texto();
    }

    String textoDaLinhaDeBaixo() {
        return linhaDeBaixo.getText();
    }

    /** Os pares (rótulo, valor) da ficha da máquina. */
    java.util.List<java.util.List<String>> paresDaFicha() {
        return ficha.pares();
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
