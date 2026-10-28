package br.com.labdesktop.ui;

import br.com.labdesktop.arquivos.ServicosDeArquivos;
import br.com.labdesktop.servidor.AssistenteDeServidor;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.dialogos.Apresentador;
import br.com.labdesktop.ui.telas.TelaServidor;
import java.awt.BorderLayout;
import br.com.labdesktop.util.Preferencias;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
        this(gerenciador, assistente, avisos, apresentador, navegador, ServicosDeArquivos.descartaveis());
    }

    /** @param servicosDeArquivos o que o gerenciador de arquivos guarda no Mac (últimos caminhos, backups) */
    public PainelPrincipal(GerenciadorServidores gerenciador, AssistenteDeServidor assistente, Notificacoes avisos,
                           Apresentador apresentador, Navegador navegador, ServicosDeArquivos servicosDeArquivos) {
        this(gerenciador, assistente, avisos, apresentador, navegador, servicosDeArquivos, Preferencias::padrao);
    }

    /** @param preferencias as preferências em uso (o terminal as lê a cada anexação) */
    public PainelPrincipal(GerenciadorServidores gerenciador, AssistenteDeServidor assistente, Notificacoes avisos,
                           Apresentador apresentador, Navegador navegador, ServicosDeArquivos servicosDeArquivos,
                           Supplier<Preferencias> preferencias) {
        super(new BorderLayout());
        this.gerenciador = gerenciador;
        this.navegador = navegador;
        Consumer<ServidorLab> aoSelecionar = this::mostrarServidor;
        this.acoes = new AcoesDaInterface(gerenciador, assistente, avisos, apresentador, aoSelecionar, navegador, servicosDeArquivos);
        this.navegacao = new Navegacao(gerenciador, acoes, navegador, avisos, apresentador, servicosDeArquivos, preferencias);
        acoes.usarEscolhedor(navegacao.escolhedor());
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

    /**
     * "Novo projeto" do menu: o servidor em foco, se ele sabe criar projeto e as ações estão liberadas; depois de criar, seleciona o projeto e leva à
     * aba Terminal dele.
     */
    public void novoProjetoNoServidorEmFoco() {
        ServidorLab lab = servidorEmFoco();
        if (lab == null) {
            return;
        }
        acoes.novoProjeto(lab, new AcoesDaInterface.DepoisDeCriar() {
            @Override
            public void abrirNoTerminal(String projeto) {
                navegacao.mostrarServidor(lab);
                navegacao.telaServidorDe(lab.id()).abrirProjetoNoTerminal(projeto);
            }

            @Override
            public void abrirNoClaude(String projeto) {
                abrirNoTerminal(projeto);
                var aberto = navegacao.telaServidorDe(lab.id()).projetoAberto();
                if (aberto != null) {
                    aberto.mostrarOClaude();
                }
            }
        });
    }

    /** O item "Novo projeto…" do menu vale agora? (o servidor em foco cria projeto e as ações estão liberadas) */
    public boolean podeCriarProjetoNoServidorEmFoco() {
        return br.com.labdesktop.ui.telas.FluxosDeProjeto.liberadasPorPropriedade() && AcoesDaInterface.podeCriarProjeto(servidorEmFoco());
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

    /** A aba "Arquivos" do servidor em foco (ou do primeiro cadastrado). */
    public void mostrarArquivos() {
        ServidorLab lab = servidorOuPrimeiro();
        if (lab != null) {
            navegacao.mostrarServidor(lab, TelaServidor.Aba.ARQUIVOS);
        }
    }

    /** A aba "Terminal" do servidor em foco (ou do primeiro cadastrado). */
    public void mostrarTerminal() {
        ServidorLab lab = servidorOuPrimeiro();
        if (lab != null) {
            navegacao.mostrarServidor(lab, TelaServidor.Aba.TERMINAL);
        }
    }

    /** O servidor em foco (ou o primeiro cadastrado, se está na tela de servidores). */
    private ServidorLab servidorOuPrimeiro() {
        ServidorLab lab = servidorEmFoco();
        return lab == null && !gerenciador.servidores().isEmpty() ? gerenciador.servidores().get(0) : lab;
    }

    /** Reabre a página do último projeto aberto no servidor em foco, se houver. */
    public void mostrarUltimoProjeto() {
        ServidorLab lab = servidorOuPrimeiro();
        TelaServidor t = lab == null ? null : navegacao.telaServidorDe(lab.id());
        if (t != null && t.ultimoProjeto() != null) {
            navegacao.abrirProjeto(lab, t.ultimoProjeto());
        }
    }

    /** Há um projeto aberto no servidor em foco (o menu só habilita "Voltar à lista de projetos" assim). */
    public boolean temProjetoAberto() {
        ServidorLab lab = servidorEmFoco();
        TelaServidor t = lab == null ? null : navegacao.telaServidorDe(lab.id());
        return t != null && t.projetoAberto() != null;
    }

    /** Há um último projeto para reabrir no servidor em foco. */
    public boolean temUltimoProjeto() {
        ServidorLab lab = servidorOuPrimeiro();
        TelaServidor t = lab == null ? null : navegacao.telaServidorDe(lab.id());
        return t != null && t.ultimoProjeto() != null;
    }

    /** O resumo do servidor em foco (ou do primeiro cadastrado). */
    public void mostrarResumo() {
        ServidorLab lab = servidorEmFoco();
        if (lab == null && !gerenciador.servidores().isEmpty()) {
            lab = gerenciador.servidores().get(0);
        }
        if (lab != null) {
            navegacao.mostrarServidor(lab, TelaServidor.Aba.RESUMO);
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

    /** O que sair do app agora perderia (transferências andando, edições não salvas), somado de todos os servidores abertos. */
    public Pendencias pendencias() {
        return navegacao.pendencias();
    }

    /** Solta tudo (a janela fechou ou o cofre foi bloqueado). */
    public void encerrar() {
        barra.encerrar();
        navegacao.encerrar();
    }
}
