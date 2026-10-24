package br.com.labdesktop.ui;

import br.com.labdesktop.servidor.AssistenteDeServidor;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.dialogos.Apresentador;
import br.com.labdesktop.ui.telas.TelaServidor;
import java.awt.BorderLayout;
import java.util.function.Consumer;

/**
 * O conteúdo da janela principal: a {@link BarraServidores} à esquerda e a {@link Navegacao} no centro. Fica separado
 * do {@code JFrame} ({@link JanelaPrincipal}) para ser montado e testado sem janela. É quem liga as duas: escolher um
 * servidor na barra mostra a lista de projetos dele, e adicionar um servidor pelo assistente o seleciona.
 */
public final class PainelPrincipal extends Painel {

    private static final long serialVersionUID = 1L;

    private final BarraServidores barra;
    private final Navegacao navegacao;
    private final AcoesDaInterface acoes;
    private final Navegador navegador;
    private final GerenciadorServidores gerenciador;

    public PainelPrincipal(GerenciadorServidores gerenciador, AssistenteDeServidor assistente, Notificacoes avisos,
                           Apresentador apresentador, Navegador navegador) {
        super(new BorderLayout());
        this.gerenciador = gerenciador;
        this.navegador = navegador;
        Consumer<ServidorLab> aoSelecionar = this::mostrarServidor;
        this.acoes = new AcoesDaInterface(gerenciador, assistente, avisos, apresentador, aoSelecionar);
        this.navegacao = new Navegacao(gerenciador, acoes, navegador, avisos, apresentador);
        this.barra = new BarraServidores(gerenciador, acoes, new BarraServidores.Ouvinte() {
            @Override
            public void aoEscolherServidor(ServidorLab lab) {
                navegacao.mostrarServidor(lab);
            }

            @Override
            public void aoEscolherGerenciar() {
                navegacao.mostrarServidores();
            }
        });
        navegacao.aoMudar(atual -> {
            ServidorLab lab = navegacao.servidorAtual();
            barra.definirSelecionado(lab == null ? null : lab.id(), lab == null);
        });
        add(barra, BorderLayout.WEST);
        add(navegacao, BorderLayout.CENTER);
        // Já há servidores no cofre ao abrir: mostra o primeiro (a lista de projetos é a tela principal do app).
        if (!gerenciador.servidores().isEmpty()) {
            mostrarServidor(gerenciador.servidores().get(0));
        } else {
            navegacao.mostrarServidores();
        }
    }

    public AcoesDaInterface acoes() {
        return acoes;
    }

    public Navegacao navegacao() {
        return navegacao;
    }

    public BarraServidores barra() {
        return barra;
    }

    public Navegador navegador() {
        return navegador;
    }

    /** Mostra a página de um servidor (na aba em que estava) e marca a barra. */
    public void mostrarServidor(ServidorLab lab) {
        navegacao.mostrarServidor(lab);
    }

    public void mostrarServidores() {
        navegacao.mostrarServidores();
    }

    /** A aba "Projetos" do servidor em foco (ou do primeiro cadastrado, se está na tela de servidores). */
    public void mostrarProjetos() {
        ServidorLab lab = servidorEmFoco();
        if (lab == null && !gerenciador.servidores().isEmpty()) {
            lab = gerenciador.servidores().get(0);
        }
        if (lab != null) {
            navegacao.mostrarServidor(lab, TelaServidor.Aba.PROJETOS);
        }
    }

    /** A visão geral do servidor em foco (ou do primeiro cadastrado). */
    public void mostrarVisaoGeral() {
        ServidorLab lab = servidorEmFoco();
        if (lab == null && !gerenciador.servidores().isEmpty()) {
            lab = gerenciador.servidores().get(0);
        }
        if (lab != null) {
            navegacao.mostrarServidor(lab, TelaServidor.Aba.VISAO_GERAL);
        }
    }

    /** O servidor cuja lista está na tela; se estiver na tela de servidores, o único servidor (se houver um só). */
    public ServidorLab servidorEmFoco() {
        ServidorLab lab = navegacao.servidorAtual();
        if (lab != null) {
            return lab;
        }
        var todos = gerenciador.servidores();
        return todos.size() == 1 ? todos.get(0) : null;
    }

    /** Solta tudo (a janela fechou ou o cofre foi bloqueado). */
    public void encerrar() {
        barra.encerrar();
        navegacao.encerrar();
    }
}
