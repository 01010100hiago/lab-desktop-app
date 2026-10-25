package br.com.labdesktop.ui.telas;

import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ui.componentes.Abas;
import br.com.labdesktop.ui.componentes.CartoesAjustaveis;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import javax.swing.JPanel;

/**
 * A página de UM servidor (ARQUITETURA 8.1): abas no topo, Resumo (a aberta de partida), Segurança, Containers, Processos,
 * Máquina e Projetos (a {@link TelaProjetos}, sem mudar em nada). Clicar no servidor na barra lateral abre esta página; a aba
 * escolhida fica lembrada enquanto o app estiver aberto: a tela de cada servidor é criada uma vez e só escondida quando o
 * usuário vai para outro lugar, então voltar ao servidor mostra a aba em que ele estava.
 *
 * <p>Quem consulta o servidor é cada aba, ao ficar visível ({@code HierarchyListener}), e só os dados que ela usa: a aba que
 * não está na tela não consulta nada. Uma aba escondida dentro de um {@code CardLayout} não está {@code showing}, então a
 * regra vale sozinha.
 *
 * <p>Clicar numa linha da lista de projetos (a aba "Projetos") abre a {@link TelaProjeto} desse projeto NO LUGAR das abas do
 * servidor, no mesmo painel; "‹ Projetos" (ou Esc) volta para a lista. A aba em que cada projeto estava fica lembrada por
 * projeto enquanto o app estiver aberto, e se o servidor diz que o projeto aberto não existe mais, a lista volta com um aviso.
 */
public final class TelaServidor extends Painel {

    private static final long serialVersionUID = 1L;

    /** As abas, na ordem da tela. */
    public enum Aba {
        RESUMO("resumo", "Resumo"),
        SEGURANCA("seguranca", "Segurança"),
        CONTAINERS("containers", "Containers"),
        PROCESSOS("processos", "Processos"),
        MAQUINA("maquina", "Máquina"),
        PROJETOS("projetos", "Projetos");

        private final String id;
        private final String rotulo;

        Aba(String id, String rotulo) {
            this.id = id;
            this.rotulo = rotulo;
        }

        public String rotulo() {
            return rotulo;
        }

        public String id() {
            return id;
        }

        static Aba de(String id) {
            return Arrays.stream(values()).filter(a -> a.id.equals(id)).findFirst().orElse(RESUMO);
        }
    }

    private final ServidorLab servidor;
    private final Runnable aoAtualizarProjeto;
    private final TelaProjetos projetos;
    private final PaginaResumo resumo;
    private final PaginaSeguranca seguranca;
    private final PaginaContainers containers;
    private final PaginaProcessos processos;
    private final PaginaMaquina maquina;
    private final Abas abas = new Abas();
    private final CardLayout cartoes = new CartoesAjustaveis();
    private final JPanel corpo = new Painel(cartoes);
    private Aba atual = Aba.RESUMO;

    private static final String CARTAO_PAGINA = "pagina";
    private static final String CARTAO_PROJETO = "projeto";
    /** O primeiro nível: a página do servidor (abas) ou a página de um projeto, uma de cada vez. */
    private final CardLayout niveis = new CartoesAjustaveis();
    private final Painel paginaDoServidor = new Painel(new BorderLayout());
    private TelaProjeto projetoAberto;
    private String nomeDoProjetoAberto;
    private String ultimoProjeto;
    private final Map<String, TelaProjeto.Aba> abasDosProjetos = new HashMap<>();

    /**
     * @param servidor    o servidor vivo ({@code null} nos testes com dados fixos)
     * @param projetos    a lista de projetos do servidor (a aba "Projetos")
     * @param aoAtualizar o que o botão Atualizar do Resumo faz
     */
    public TelaServidor(ServidorLab servidor, TelaProjetos projetos, Runnable aoAtualizar) {
        super();
        setLayout(niveis);
        paginaDoServidor.fundo(Tokens.BG);
        fundo(Tokens.BG);
        this.servidor = servidor;
        this.aoAtualizarProjeto = aoAtualizar == null ? () -> { } : aoAtualizar;
        this.projetos = projetos;
        this.resumo = new PaginaResumo(servidor, aoAtualizar, this::mostrarAba);
        this.seguranca = new PaginaSeguranca(servidor, aoAtualizar);
        this.containers = new PaginaContainers(servidor, aoAtualizar);
        this.processos = new PaginaProcessos(servidor, aoAtualizar);
        this.maquina = new PaginaMaquina(servidor, aoAtualizar);

        for (Aba a : Aba.values()) {
            abas.adicionar(a.id, a.rotulo);
        }
        abas.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 12, 0, 12));
        abas.aoSelecionar(id -> mostrarAba(Aba.de(id)));
        corpo.add(resumo, Aba.RESUMO.id);
        corpo.add(seguranca, Aba.SEGURANCA.id);
        corpo.add(containers, Aba.CONTAINERS.id);
        corpo.add(processos, Aba.PROCESSOS.id);
        corpo.add(maquina, Aba.MAQUINA.id);
        corpo.add(projetos, Aba.PROJETOS.id);
        paginaDoServidor.add(abas, BorderLayout.NORTH);
        paginaDoServidor.add(corpo, BorderLayout.CENTER);
        add(paginaDoServidor, CARTAO_PAGINA);
        niveis.show(this, CARTAO_PAGINA);
        cartoes.show(corpo, Aba.RESUMO.id);
        projetos.aoAbrirProjeto(this::abrirProjeto);
        getAccessibleContext().setAccessibleName("Servidor");
    }

    /** Mostra uma aba (e marca o rótulo). Se havia uma página de projeto aberta, ela fecha: o usuário pediu outro assunto. */
    public void mostrarAba(Aba aba) {
        fecharProjetoSemMostrar();
        atual = aba;
        abas.selecionar(aba.id);
        cartoes.show(corpo, aba.id);
        niveis.show(this, CARTAO_PAGINA);
        corpo.revalidate();
        corpo.repaint();
    }

    // ---------------------------------------------------------------------------------------------
    // A página de um projeto
    // ---------------------------------------------------------------------------------------------

    /**
     * Abre a página de um projeto no lugar das abas do servidor, na aba em que ele estava (a Visão geral, na primeira vez). O
     * nome vem da lista (que só traz nomes válidos); um nome fora da regra do agente é recusado antes de virar tópico.
     */
    public void abrirProjeto(String nome) {
        if (!br.com.labdesktop.seguranca.ValidadorDeNomes.projetoValido(nome)) {
            return;
        }
        fecharProjetoSemMostrar();
        FluxosDeProjeto fluxos = projetos.fluxos();
        TelaProjeto.Aba aba = abasDosProjetos.getOrDefault(nome, TelaProjeto.Aba.VISAO_GERAL);
        nomeDoProjetoAberto = nome;
        ultimoProjeto = nome;
        projetoAberto = new TelaProjeto(servidor, nome, fluxos, projetos.navegador(), fluxos.avisos(), aba, new TelaProjeto.Saida() {
            @Override
            public void voltar() {
                voltarParaALista();
            }

            @Override
            public void projetoSumiu() {
                String sumiu = nomeDoProjetoAberto;
                abasDosProjetos.remove(sumiu);
                voltarParaALista();
                fluxos.avisos().atencao("O projeto " + sumiu + " não existe mais no servidor. Voltei para a lista de projetos.");
            }

            @Override
            public void abaMudou(TelaProjeto.Aba nova) {
                abasDosProjetos.put(nome, nova);
            }
        }, () -> projetos.urlDeLoginDe(nome), aoAtualizarProjeto);
        add(projetoAberto, CARTAO_PROJETO);
        niveis.show(this, CARTAO_PROJETO);
        revalidate();
        repaint();
    }

    /** "‹ Projetos" e Esc: fecha a página do projeto e mostra a aba Projetos do servidor. */
    public void voltarParaALista() {
        mostrarAba(Aba.PROJETOS);
    }

    private void fecharProjetoSemMostrar() {
        if (projetoAberto == null) {
            return;
        }
        TelaProjeto fechando = projetoAberto;
        projetoAberto = null;
        nomeDoProjetoAberto = null;
        niveis.show(this, CARTAO_PAGINA);
        fechando.soltar();
        remove(fechando);
        revalidate();
        repaint();
    }

    /** A página do projeto aberta agora, ou {@code null}. */
    public TelaProjeto projetoAberto() {
        return projetoAberto;
    }

    /** O último projeto que se abriu neste servidor (para o atalho "Último projeto aberto"), ou {@code null}. */
    public String ultimoProjeto() {
        return ultimoProjeto;
    }

    /** A aba em que o projeto estava da última vez que foi aberto (a Visão geral se nunca foi). */
    public TelaProjeto.Aba abaLembradaDe(String projeto) {
        return abasDosProjetos.getOrDefault(projeto, TelaProjeto.Aba.VISAO_GERAL);
    }

    public Aba abaAtual() {
        return atual;
    }

    public TelaProjetos projetos() {
        return projetos;
    }

    public PaginaResumo resumo() {
        return resumo;
    }

    public PaginaSeguranca seguranca() {
        return seguranca;
    }

    public PaginaContainers containers() {
        return containers;
    }

    public PaginaProcessos processos() {
        return processos;
    }

    public PaginaMaquina maquina() {
        return maquina;
    }

    /** A página de dados de uma aba ({@code null} para Projetos). */
    public PaginaDoServidor pagina(Aba aba) {
        return switch (aba) {
            case RESUMO -> resumo;
            case SEGURANCA -> seguranca;
            case CONTAINERS -> containers;
            case PROCESSOS -> processos;
            case MAQUINA -> maquina;
            case PROJETOS -> null;
        };
    }

    public ServidorLab servidor() {
        return servidor;
    }

    /** A aba escolhida no rótulo, para os testes conferirem que rótulo e conteúdo andam juntos. */
    public Aba abaMarcada() {
        return Aba.de(abas.selecionada());
    }

    public Abas abas() {
        return abas;
    }

    /** Solta as assinaturas de todas as abas (o servidor saiu da lista ou o cofre foi bloqueado). */
    public void soltar() {
        resumo.soltar();
        seguranca.soltar();
        containers.soltar();
        processos.soltar();
        maquina.soltar();
        projetos.soltar();
        if (projetoAberto != null) {
            projetoAberto.soltar();
        }
    }
}
