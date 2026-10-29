package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.modelo.InfoServidor;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.servidor.ProtecaoDoServidor;
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
    /** "Protegido": só aparece com o servidor protegido; a linha de baixo explica e diz como mudar. */
    private final Selo protegido = new Selo("Protegido", br.com.labdesktop.ui.Gravidade.NEUTRO, "cadeado");
    private final Rotulo linhaDaProtecao = new Rotulo(ProtecaoDoServidor.EXPLICACAO_DO_SELO).corDoToken(Tokens.TEXTO3).tamanho(12f).truncavel();
    /** Só a frase de "esperando a primeira leitura"; com a leitura, quem fala é a ficha. */
    private final Rotulo linhaDeBaixo = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12.5f).truncavel();
    private final FichaRotulada ficha = new FichaRotulada(4, 24);
    private final Rotulo leitura = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(12f);
    private final Botao atualizar = Botoes.mini("Atualizar", "atualizar");

    CabecalhoDoServidor(Runnable aoAtualizar) {
        super(new BorderLayout(0, 6));
        nome.setIcon(Icones.colorido("servidor", 22, Tokens.DESTAQUE));
        nome.setIconTextGap(10);
        protegido.setName("selo-protegido");
        protegido.setToolTipText(ProtecaoDoServidor.EXPLICACAO_DO_SELO);
        protegido.setVisible(false);
        linhaDaProtecao.setName("linha-da-protecao");
        linhaDaProtecao.setVisible(false);
        add(Caixa.linha(12, nome, new AEsquerda(estado), new AEsquerda(protegido), Caixa.mola(), leitura, atualizar), BorderLayout.NORTH);
        add(Caixa.coluna(0, linhaDaProtecao, linhaDeBaixo, ficha), BorderLayout.CENTER);
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
            protegido.setVisible(e.protegido());
            // Sem o agente o aviso da página já é essa linha (no lugar do botão Instalar agente): não a repete, para o Resumo caber em 1100 x 700.
            linhaDaProtecao.setVisible(e.protegido() && !e.agenteFaltaMasServidorProtegido());
        } else {
            protegido.setVisible(false);
            linhaDaProtecao.setVisible(false);
            estado.atualizar("Lendo…", br.com.labdesktop.ui.Gravidade.NEUTRO, "atualizar");
        }
        if (info == null) {
            boolean semAgente = e != null && e.conexao() == br.com.labdesktop.ssh.EstadoConexao.SSH_OK_SEM_AGENTE
                && e.situacaoDoAgente().oferecerInstalar();
            linhaDeBaixo.setText(semAgente ? (e.local() ? "Sem o laboratório local não há leituras de CPU, memória, containers nem projetos."
                : "Sem o agente não há leituras de CPU, memória, containers nem projetos.")
                : "Esperando a primeira leitura do servidor…");
            // sem o agente o aviso logo abaixo (ou o cartão "sem dados") já diz isto: repetir gastaria uma linha que o Registro precisa (o Resumo cabe em 1100 x 700)
            linhaDeBaixo.setVisible(!semAgente);
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

    /** O selo "Protegido" está à vista? */
    boolean mostraOSeloProtegido() {
        return protegido.isVisible();
    }

    /** A linha que explica a proteção (e como mudar), ou vazio se não aparece. */
    String textoDaLinhaDaProtecao() {
        return linhaDaProtecao.isVisible() ? linhaDaProtecao.getText() : "";
    }

    Selo seloProtegido() {
        return protegido;
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
