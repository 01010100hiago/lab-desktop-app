package br.com.labdesktop.ui;

import br.com.labdesktop.arquivos.ServicosDeArquivos;
import br.com.labdesktop.servidor.AssistenteDeServidor;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.dialogos.Apresentador;
import br.com.labdesktop.ui.telas.TelaServidor;
import br.com.labdesktop.ui.telas.ajustes.TelaAjustes;
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

            @Override
            public void aoEscolherAjustes() {
                if (navegacao.emAjustes()) {
                    navegacao.voltarDosAjustes(); // a engrenagem de novo fecha os Ajustes
                } else {
                    navegacao.mostrarAjustes(null);
                }
            }
        });
        navegacao.aoMudar(atual -> {
            ServidorLab lab = navegacao.servidorAtual();
            boolean ajustesNaFrente = navegacao.emAjustes();
            barra.definirSelecionado(ajustesNaFrente || lab == null ? null : lab.id(), lab == null && !ajustesNaFrente);
            barra.definirAjustesSelecionado(ajustesNaFrente);
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

    /** Liga a engrenagem da barra e os atalhos à tela Ajustes (montada só na primeira vez que for aberta). */
    public void instalarAjustes(Supplier<TelaAjustes> fabrica) {
        navegacao.instalarAjustes(fabrica);
        barra.definirAjustesDisponivel(true);
    }

    /** Abre os Ajustes na seção dada ({@code null}: a que estava). */
    public void mostrarAjustes(TelaAjustes.Secao secao) {
        navegacao.mostrarAjustes(secao);
    }

    /**
     * Cmd+1 a Cmd+9: a aba de número {@code n} (a partir de 1) do servidor em foco, contando as abas que APARECEM na barra, na ordem que o usuário deixou. Sem aba nesse número
     * não faz nada.
     */
    public void mostrarAbaNumero(int n) {
        ServidorLab lab = servidorOuPrimeiro();
        TelaServidor t = lab == null ? null : navegacao.telaServidorDe(lab.id());
        if (t == null) {
            return;
        }
        java.util.List<TelaServidor.Aba> visiveis = t.abasVisiveis();
        if (n >= 1 && n <= visiveis.size()) {
            navegacao.mostrarServidor(lab, visiveis.get(n - 1));
        }
    }

    /**
     * Abre uma aba do servidor em foco (ou do primeiro cadastrado). {@code false} se não há nenhum servidor. Um link da Ajuda usa isto; como sai da tela Ajustes, o
     * "Voltar" de Ajustes não volta para cá: o usuário já está onde queria ir.
     */
    public boolean irParaAba(TelaServidor.Aba aba) {
        ServidorLab lab = servidorOuPrimeiro();
        if (lab == null) {
            return false;
        }
        navegacao.mostrarServidor(lab, aba);
        return true;
    }

    /** Os nomes das abas que aparecem agora no servidor em foco, na ordem (os itens Cmd+1 a Cmd+9 do menu mostram estes). */
    public java.util.List<String> nomesDasAbasVisiveis() {
        ServidorLab lab = servidorOuPrimeiro();
        TelaServidor t = lab == null ? null : navegacao.telaServidorDe(lab.id());
        return t == null ? java.util.List.of() : t.abasVisiveis().stream().map(TelaServidor.Aba::rotulo).toList();
    }

    /** A aba aberta agora na página do servidor em foco (ela não pode ser escondida em Ajustes). */
    public String abaAbertaDoServidor() {
        ServidorLab lab = servidorOuPrimeiro();
        TelaServidor t = lab == null ? null : navegacao.telaServidorDe(lab.id());
        return t == null ? null : t.abaAtual().id();
    }

    /** A aba aberta agora na página do projeto em foco, ou {@code null} se não há página de projeto aberta. */
    public String abaAbertaDoProjeto() {
        ServidorLab lab = servidorOuPrimeiro();
        TelaServidor t = lab == null ? null : navegacao.telaServidorDe(lab.id());
        return t == null || t.projetoAberto() == null ? null : t.projetoAberto().abaAtual().id();
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

    /**
     * "Adicionar domínio…" do menu: no projeto que está aberto no servidor em foco, ou, se nenhum está aberto, pergunta para qual projeto. Leva ao "Conectar a Cloudflare" primeiro
     * se faltar. Só com as ações de escrita liberadas.
     */
    public void adicionarDominioNoServidorEmFoco() {
        ServidorLab lab = servidorEmFoco();
        if (lab == null) {
            return;
        }
        TelaServidor t = navegacao.telaServidorDe(lab.id());
        if (t != null && t.projetoAberto() != null) {
            acoes.adicionarDominio(lab, t.projetoAberto().projeto(), null);
        } else if (t != null) {
            acoes.adicionarDominioEscolhendoOProjeto(lab, t.nomesDosProjetos(), null);
        }
    }

    /** O item "Adicionar domínio…" vale agora? (um servidor por SSH em foco e as ações liberadas) */
    public boolean podeAdicionarDominioNoServidorEmFoco() {
        ServidorLab lab = servidorEmFoco();
        return lab != null && !lab.ehLocal() && br.com.labdesktop.ui.telas.FluxosDeProjeto.liberadasPorPropriedade();
    }

    /** Por que o item "Adicionar domínio…" está desligado (a dica dele), ou {@code null} se está ligado. */
    public String motivoDeNaoAdicionarDominio() {
        ServidorLab lab = servidorEmFoco();
        if (lab == null) {
            return "Abra um servidor para dar um endereço novo a um projeto dele.";
        }
        if (lab.ehLocal()) {
            return "Este computador não tem um túnel da Cloudflare configurado pelo app.";
        }
        return br.com.labdesktop.ui.telas.FluxosDeProjeto.liberadasPorPropriedade() ? null : "As ações de escrita estão desligadas nesta versão do app.";
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
