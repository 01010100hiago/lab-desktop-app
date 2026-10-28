package br.com.labdesktop.ui.telas;

import br.com.labdesktop.arquivos.ServicosDeArquivos;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ui.AcoesDaInterface;
import br.com.labdesktop.ui.componentes.Abas;
import br.com.labdesktop.ui.componentes.CartoesAjustaveis;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.telas.arquivos.ContextoDeArquivos;
import br.com.labdesktop.ui.telas.arquivos.EscolhedorLocal;
import br.com.labdesktop.ui.telas.arquivos.GerenciadorDeArquivos;
import br.com.labdesktop.ui.terminal.ContextoDeTerminal;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.OrdemDeAbas;
import br.com.labdesktop.util.Preferencias;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.JPanel;

/**
 * A página de UM servidor (ARQUITETURA 8.1): abas no topo, Resumo (a aberta de partida), Segurança, Containers, Processos,
 * Máquina, Projetos (a {@link TelaProjetos}, sem mudar em nada), Arquivos e Terminal (o shell do servidor, com várias abas). Clicar no servidor na barra lateral abre esta página; a aba
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

    /** Os ids de todas as abas desta versão, na ordem padrão (o que {@link OrdemDeAbas} usa para não perder nenhuma). */
    public static final java.util.List<String> IDS_DAS_ABAS = Arrays.stream(Aba.values()).map(Aba::id).toList();

    /** As abas, na ordem padrão. */
    public enum Aba {
        RESUMO("resumo", "Resumo"),
        SEGURANCA("seguranca", "Segurança"),
        CONTAINERS("containers", "Containers"),
        PROCESSOS("processos", "Processos"),
        MAQUINA("maquina", "Máquina"),
        PROJETOS("projetos", "Projetos"),
        ARQUIVOS("arquivos", "Arquivos"),
        TERMINAL("terminal", "Terminal"),
        CLOUDFLARE("cloudflare", "Cloudflare");

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
    private final GerenciadorDeArquivos arquivos;
    private final ServicosDeArquivos servicosDeArquivos;
    private final EscolhedorLocal escolhedor;
    private final PaginaTerminal terminais;
    private final PaginaCloudflare cloudflare;
    private final ContextoDeTerminal contextoDeTerminal;
    private final Abas abas = new Abas();
    private final CardLayout cartoes = new CartoesAjustaveis();
    private final JPanel corpo = new Painel(cartoes);
    private Aba atual = Aba.RESUMO;
    /** A ordem e as abas escondidas das páginas de projeto (Ajustes, Navegação): vale para toda página de projeto aberta aqui. */
    private OrdemDeAbas ordemDoProjeto = OrdemDeAbas.PADRAO;
    /** O servidor tem projetos, segundo o agente (desconhecido vale sim): sem projetos, a aba Projetos some. */
    private boolean temProjetos = true;
    private transient br.com.labdesktop.servidor.Inscricao inscricaoDoEstado;

    private static final String CARTAO_PAGINA = "pagina";
    private static final String CARTAO_PROJETO = "projeto";
    /** O primeiro nível: a página do servidor (abas) ou a página de um projeto, uma de cada vez. */
    private final CardLayout niveis = new CartoesAjustaveis();
    private final Painel paginaDoServidor = new Painel(new BorderLayout());
    private TelaProjeto projetoAberto;
    /** Páginas de projeto fechadas com alterações não salvas num editor: ficam escondidas, para voltar ao projeto as devolver. */
    private final Map<String, TelaProjeto> projetosGuardados = new HashMap<>();
    private String nomeDoProjetoAberto;
    private String ultimoProjeto;
    private final Map<String, TelaProjeto.Aba> abasDosProjetos = new HashMap<>();

    /**
     * @param servidor    o servidor vivo ({@code null} nos testes com dados fixos)
     * @param projetos    a lista de projetos do servidor (a aba "Projetos")
     * @param aoAtualizar o que o botão Atualizar do Resumo faz
     */
    public TelaServidor(ServidorLab servidor, TelaProjetos projetos, Runnable aoAtualizar) {
        this(servidor, projetos, aoAtualizar, ServicosDeArquivos.descartaveis(), EscolhedorLocal.doSistema(() -> null));
    }

    /**
     * @param servicos  a memória dos últimos caminhos e a pasta dos backups do gerenciador de arquivos (aba Arquivos e a dos projetos)
     * @param escolhedor quem pergunta ao usuário o que enviar e onde guardar o que baixar
     */
    public TelaServidor(ServidorLab servidor, TelaProjetos projetos, Runnable aoAtualizar, ServicosDeArquivos servicos,
                        EscolhedorLocal escolhedor) {
        this(servidor, projetos, aoAtualizar, servicos, escolhedor, Preferencias::padrao);
    }

    /**
     * @param preferencias as preferências em uso (Option como Meta, copiar ao selecionar, dono exclusivo, fonte): o terminal as lê a cada
     *                     anexação
     */
    public TelaServidor(ServidorLab servidor, TelaProjetos projetos, Runnable aoAtualizar, ServicosDeArquivos servicos,
                        EscolhedorLocal escolhedor, Supplier<Preferencias> preferencias) {
        super();
        setLayout(niveis);
        paginaDoServidor.fundo(Tokens.BG);
        fundo(Tokens.BG);
        this.servidor = servidor;
        this.aoAtualizarProjeto = aoAtualizar == null ? () -> { } : aoAtualizar;
        this.projetos = projetos;
        this.resumo = new PaginaResumo(servidor, aoAtualizar, this::mostrarAba,
            ApoioDoResumo.de(projetos.fluxos().apresentador(), projetos.fluxos().avisos(), escolhedor, servicos.memoria()));
        this.seguranca = new PaginaSeguranca(servidor, aoAtualizar,
            servidor == null || projetos.acoes() == null || servidor.ehLocal() ? null : () -> projetos.acoes().desligarSenha(servidor));
        this.containers = new PaginaContainers(servidor, aoAtualizar);
        this.processos = new PaginaProcessos(servidor, aoAtualizar,
            AcoesDeProcessos.de(servidor, projetos.acoes(), projetos.fluxos()::liberadas));
        this.maquina = new PaginaMaquina(servidor, aoAtualizar, projetos.acoes());
        this.servicosDeArquivos = servicos;
        this.escolhedor = escolhedor;
        this.arquivos = new GerenciadorDeArquivos(ContextoDeArquivos.de(servidor, null, servicos, projetos.fluxos().liberadas(),
            projetos.fluxos().avisos(), projetos.fluxos().apresentador(), escolhedor));

        this.contextoDeTerminal = servidor == null ? null : ContextoDeTerminal.de(servidor, preferencias, projetos.navegador());
        this.terminais = new PaginaTerminal(contextoDeTerminal, projetos.fluxos().avisos());
        this.cloudflare = new PaginaCloudflare(servidor, projetos.acoes(), projetos.fluxos().liberadas(), projetos.fluxos().avisos(), projetos::adicionarDominioEscolhendoOProjeto);

        for (Aba a : Aba.values()) {
            abas.adicionar(a.id, a.rotulo);
        }
        abas.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 12, 0, 12));
        Preferencias prefs = preferencias == null ? Preferencias.padrao() : preferencias.get();
        this.ordemDoProjeto = prefs.abasDoProjeto();
        abas.aplicarPreferencia(prefs.abasDoServidor(), IDS_DAS_ABAS);
        if (servidor != null && servidor.ehLocal()) {
            abas.definirVisivel(Aba.SEGURANCA.id, false); // a leitura de segurança é do SSH (registro de entradas, firewall) de um servidor remoto
        }
        abas.aoSelecionar(id -> mostrarAba(Aba.de(id)));
        corpo.add(resumo, Aba.RESUMO.id);
        corpo.add(seguranca, Aba.SEGURANCA.id);
        corpo.add(containers, Aba.CONTAINERS.id);
        corpo.add(processos, Aba.PROCESSOS.id);
        corpo.add(maquina, Aba.MAQUINA.id);
        corpo.add(projetos, Aba.PROJETOS.id);
        corpo.add(arquivos, Aba.ARQUIVOS.id);
        corpo.add(terminais, Aba.TERMINAL.id);
        corpo.add(cloudflare, Aba.CLOUDFLARE.id);
        paginaDoServidor.add(abas, BorderLayout.NORTH);
        paginaDoServidor.add(corpo, BorderLayout.CENTER);
        add(paginaDoServidor, CARTAO_PAGINA);
        niveis.show(this, CARTAO_PAGINA);
        cartoes.show(corpo, Aba.RESUMO.id);
        projetos.aoAbrirProjeto(this::abrirProjeto);
        projetos.aoCriarProjeto(new AcoesDaInterface.DepoisDeCriar() {
            @Override
            public void abrirNoTerminal(String projeto) {
                abrirProjetoNoTerminal(projeto);
            }

            @Override
            public void abrirNoClaude(String projeto) {
                abrirProjetoNoTerminal(projeto);
                if (projetoAberto != null && projeto.equals(nomeDoProjetoAberto)) {
                    projetoAberto.mostrarOClaude();
                }
            }
        });
        getAccessibleContext().setAccessibleName("Servidor");
        if (servidor != null) {
            inscricaoDoEstado = servidor.observarEstado(e -> aplicarRecursos(e.temProjetos()));
        }
    }

    /**
     * O que o agente diz que o servidor tem ({@code recursos} de {@code /api/versao}): sem projetos (o agente do perfil monitor), a aba
     * Projetos não aparece e, se estava aberta, o Resumo a substitui. Desconhecido nunca esconde nada.
     */
    public void aplicarRecursos(boolean comProjetos) {
        this.temProjetos = comProjetos;
        abas.definirVisivel(Aba.PROJETOS.id, comProjetos);
        if (!comProjetos && (atual == Aba.PROJETOS || projetoAberto != null)) {
            mostrarAbaSemPerguntar(Aba.RESUMO); // o servidor mudou, não foi o usuário: não há o que perguntar
        }
    }

    /**
     * O usuário mexeu em Ajustes, Navegação: aplica a ordem e as abas escondidas das duas barras (a do servidor e a das páginas de projeto, a aberta e as guardadas). Na hora,
     * sem reiniciar. A aba que está aberta nunca some.
     */
    public void aplicarPreferenciasDeAbas(Preferencias prefs) {
        abas.aplicarPreferencia(prefs.abasDoServidor(), IDS_DAS_ABAS);
        this.ordemDoProjeto = prefs.abasDoProjeto();
        if (projetoAberto != null) {
            projetoAberto.aplicarPreferenciaDeAbas(ordemDoProjeto);
        }
        projetosGuardados.values().forEach(t -> t.aplicarPreferenciaDeAbas(ordemDoProjeto));
    }

    /** As abas que aparecem agora, da esquerda para a direita (⌘1 abre a primeira daqui, ⌘2 a segunda...). */
    public java.util.List<Aba> abasVisiveis() {
        return abas.idsVisiveis().stream().map(Aba::de).toList();
    }

    /** A aba Projetos está disponível neste servidor? */
    public boolean temProjetos() {
        return temProjetos;
    }

    /**
     * O que o botão "Instalar agente" dos avisos faz (as abas de dados mostram o aviso fixo "agente não instalado" com ele). O app só liga
     * isto ao diálogo de confirmação: nunca instala sozinho.
     */
    public void aoInstalarAgente(Runnable acao) {
        for (PaginaDoServidor p : new PaginaDoServidor[] {resumo, seguranca, containers, processos, maquina}) {
            p.aoInstalarAgente(acao);
        }
    }

    /**
     * Mostra uma aba (e marca o rótulo). Se havia uma página de projeto aberta, ela fecha: o usuário pediu outro assunto. Se essa página tem um terminal vivo (um shell que o usuário
     * deixou rodando), sair o fecharia sem aviso: antes disso o app PERGUNTA (vale para a barra de abas, os atalhos Cmd/Ctrl+1 a 9, os links da Ajuda e o "‹ Projetos").
     */
    public void mostrarAba(Aba aba) {
        if (projetoAberto != null && projetoAberto.temTerminalVivo()) {
            perguntarAntesDeSairDoProjeto(() -> mostrarAbaSemPerguntar(aba));
            return;
        }
        mostrarAbaSemPerguntar(aba);
    }

    /** A pergunta de sair do projeto com terminal aberto: o texto é do app; Enter cancela (o terminal não volta depois de fechado). */
    private void perguntarAntesDeSairDoProjeto(Runnable aoConfirmar) {
        String nome = br.com.labdesktop.seguranca.TextoSeguro.limparLinha(nomeDoProjetoAberto == null ? "" : nomeDoProjetoAberto);
        projetos.fluxos().apresentador().mostrar(new br.com.labdesktop.ui.dialogos.DialogoConfirmar(
            "Sair de " + (nome.isEmpty() ? "este projeto" : nome) + "?", "alerta",
            "Este projeto tem um terminal aberto. Se sair da página dele agora, o terminal fecha e o que estiver rodando nele para. A conversa do Claude continua no servidor.",
            "Sair do projeto", true, true, sim -> {
                if (sim) {
                    aoConfirmar.run();
                }
            }));
    }

    private void mostrarAbaSemPerguntar(Aba aba) {
        if (aba == Aba.PROJETOS && !temProjetos) {
            aba = Aba.RESUMO; // atalho (Cmd+1) ou voltar da lista: sem projetos, a aba não existe
        }
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
        if (projetoAberto != null && !nome.equals(nomeDoProjetoAberto) && projetoAberto.temTerminalVivo()) {
            perguntarAntesDeSairDoProjeto(() -> abrirProjetoSemPerguntar(nome)); // "Último projeto aberto" com outro projeto aberto
            return;
        }
        abrirProjetoSemPerguntar(nome);
    }

    private void abrirProjetoSemPerguntar(String nome) {
        fecharProjetoSemMostrar();
        FluxosDeProjeto fluxos = projetos.fluxos();
        TelaProjeto.Aba aba = abasDosProjetos.getOrDefault(nome, TelaProjeto.Aba.VISAO_GERAL);
        nomeDoProjetoAberto = nome;
        ultimoProjeto = nome;
        TelaProjeto guardada = projetosGuardados.remove(nome);
        if (guardada != null) {
            projetoAberto = guardada; // com os editores e as alterações de quando o usuário saiu
            niveis.show(this, CARTAO_PROJETO + nome);
            revalidate();
            repaint();
            return;
        }
        projetoAberto = new TelaProjeto(servidor, nome, fluxos, projetos.navegador(), fluxos.avisos(), aba, new TelaProjeto.Saida() {
            @Override
            public void voltar() {
                voltarParaALista();
            }

            @Override
            public void projetoSumiu() {
                String sumiu = nomeDoProjetoAberto;
                abasDosProjetos.remove(sumiu);
                mostrarAbaSemPerguntar(Aba.PROJETOS); // o projeto não existe mais: o terminal já morreu junto
                fluxos.avisos().atencao("O projeto " + sumiu + " não existe mais no servidor. Voltei para a lista de projetos.");
            }

            @Override
            public void abaMudou(TelaProjeto.Aba nova) {
                abasDosProjetos.put(nome, nova);
            }

            @Override
            public void adicionarContainer() {
                projetos.adicionarContainerDe(nome);
            }

            @Override
            public boolean podeAdicionarContainer() {
                return projetos.podeAdicionarContainer();
            }

            @Override
            public void publicarNaInternet(Runnable depois) {
                projetos.publicarDe(nome, depois);
            }

            @Override
            public void adicionarDominio(Runnable depois) {
                projetos.adicionarDominioDe(nome, depois);
            }

            @Override
            public void conectarCloudflare(Runnable depois) {
                projetos.conectarCloudflareDe(depois);
            }

            @Override
            public void removerEndereco(String hostname, Runnable depois) {
                projetos.removerEnderecoDe(hostname, depois);
            }

            @Override
            public void abrirEndereco(String hostname) {
                projetos.abrirEnderecoDe(hostname);
            }

            @Override
            public br.com.labdesktop.processos.Oferta avaliarProcesso(br.com.labdesktop.processos.ProcessoAlvo alvo) {
                return servidor == null ? null : servidor.controleDeProcessos().avaliar(alvo);
            }

            @Override
            public void abrirProcesso(br.com.labdesktop.processos.ProcessoAlvo alvo, String origem, br.com.labdesktop.processos.Acao acao, Runnable depois) {
                projetos.abrirProcessoDe(alvo, origem, acao, depois);
            }
        }, () -> projetos.urlDeLoginDe(nome), aoAtualizarProjeto, CopiadorDeTexto.doSistema(), servicosDeArquivos, escolhedor,
            contextoDeTerminal);
        projetoAberto.aplicarPreferenciaDeAbas(ordemDoProjeto);
        add(projetoAberto, CARTAO_PROJETO + nome);
        niveis.show(this, CARTAO_PROJETO + nome);
        revalidate();
        repaint();
    }

    /** Abre a página de um projeto recém-criado ou importado direto na aba Terminal (o shell já na pasta). */
    public void abrirProjetoNoTerminal(String nome) {
        abasDosProjetos.put(nome, TelaProjeto.Aba.TERMINAL);
        abrirProjeto(nome);
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
        String nome = nomeDoProjetoAberto;
        projetoAberto = null;
        nomeDoProjetoAberto = null;
        niveis.show(this, CARTAO_PAGINA);
        fechando.soltar();
        fechando.fecharTerminais(); // sair do projeto solta o cliente do tmux (a conversa do Claude continua) e devolve a vaga
        if (nome != null && fechando.temAlteracoesNaoSalvas()) {
            // Sair do projeto não pode jogar fora o que o usuário digitou: a página fica guardada, escondida, e voltar a mostra.
            projetosGuardados.put(nome, fechando);
            projetos.fluxos().avisos().atencao("Guardei as alterações não salvas em " + nome + ": ao voltar ao projeto, os editores continuam abertos.");
        } else {
            fechando.encerrar();
            remove(fechando);
        }
        revalidate();
        repaint();
    }

    /** O que sair do app agora perderia neste servidor: a aba Arquivos e as páginas de projeto (a aberta e as guardadas). */
    public br.com.labdesktop.ui.Pendencias pendencias() {
        br.com.labdesktop.ui.Pendencias p = new br.com.labdesktop.ui.Pendencias(arquivos.transferenciasEmAberto(), arquivos.editoresComAlteracoes());
        if (projetoAberto != null) {
            p = p.somar(projetoAberto.pendencias());
        }
        for (TelaProjeto guardada : projetosGuardados.values()) {
            p = p.somar(guardada.pendencias());
        }
        return p;
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

    /** Os nomes dos projetos que a lista mostra agora (o menu "Adicionar domínio…" pergunta entre eles). */
    public java.util.List<String> nomesDosProjetos() {
        return projetos.nomesDosProjetos();
    }

    public PaginaProcessos processos() {
        return processos;
    }

    public PaginaCloudflare cloudflare() {
        return cloudflare;
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
            case PROJETOS, ARQUIVOS, TERMINAL, CLOUDFLARE -> null;
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
        if (inscricaoDoEstado != null) {
            inscricaoDoEstado.close();
            inscricaoDoEstado = null;
        }
        resumo.soltar();
        seguranca.soltar();
        containers.soltar();
        processos.soltar();
        maquina.soltar();
        projetos.soltar();
        arquivos.encerrar();
        terminais.encerrar();
        for (TelaProjeto guardada : projetosGuardados.values()) {
            guardada.encerrar();
        }
        projetosGuardados.clear();
        if (projetoAberto != null) {
            projetoAberto.soltar();
            projetoAberto.encerrar();
        }
    }

    /** A aba Terminal: o shell do servidor, com várias abas de terminal. */
    public PaginaTerminal terminais() {
        return terminais;
    }

    /** O contexto dos terminais deste servidor ({@code null} sem servidor vivo): o limite de abas é dele. */
    public ContextoDeTerminal contextoDeTerminal() {
        return contextoDeTerminal;
    }

    /** O gerenciador de arquivos do servidor inteiro (a aba Arquivos). */
    public GerenciadorDeArquivos arquivos() {
        return arquivos;
    }
}
