package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.api.modelo.Detalhe;
import br.com.labdesktop.arquivos.CaminhoRemoto;
import br.com.labdesktop.arquivos.ErroSftp;
import br.com.labdesktop.arquivos.ServicosDeArquivos;
import br.com.labdesktop.api.modelo.DetalheProjeto;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.servidor.Inscricao;
import br.com.labdesktop.servidor.OuvinteDeTopico;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.servidor.Topico;
import br.com.labdesktop.ui.Navegador;
import br.com.labdesktop.ui.componentes.Abas;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.CartoesAjustaveis;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.Selo;
import br.com.labdesktop.ui.terminal.ContextoDeTerminal;
import br.com.labdesktop.ui.telas.arquivos.BaixadorDeBackups;
import br.com.labdesktop.ui.telas.arquivos.ContextoDeArquivos;
import br.com.labdesktop.ui.telas.arquivos.EscolhedorLocal;
import br.com.labdesktop.ui.telas.arquivos.GerenciadorDeArquivos;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Formato;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.text.JTextComponent;

/**
 * A página de UM projeto (ARQUITETURA 8.1, tradução de {@code projeto.html}): o cabeçalho com "‹ Projetos" (volta à lista; Esc
 * também), o nome e o estado, e as abas por assunto: Visão geral, Logs, Terminal (o shell do projeto e, se o servidor tem Claude, a sessão dele), Arquivos, Backups,
 * Consumo e limite, Internet e Processos.
 * Abre no lugar da página do servidor (quem a abre e fecha é a {@link TelaServidor}); a aba escolhida fica lembrada por projeto
 * enquanto o app estiver aberto ({@link Saida#abaMudou}).
 *
 * <p>Esta tela assina SÓ o detalhe do projeto ({@code DETALHE(p)}, 10 s) e o estado da conexão, e só enquanto está visível; cada
 * aba assina o que mais usa (logs, tela, consumo) e só enquanto ELA está visível. Se o agente disser que o projeto não existe
 * mais (404), a página avisa a {@link Saida} e a lista volta. Só desenha snapshots; nada de rede na EDT.
 */
public final class TelaProjeto extends Painel {

    private static final long serialVersionUID = 1L;

    /** As abas, na ordem da tela. */
    public enum Aba {
        VISAO_GERAL("visao-geral", "Visão geral"),
        LOGS("logs", "Logs"),
        TERMINAL("terminal", "Terminal"),
        ARQUIVOS("arquivos", "Arquivos"),
        BACKUPS("backups", "Backups"),
        CONSUMO("consumo", "Consumo e limite"),
        INTERNET("internet", "Internet"),
        PROCESSOS("processos", "Processos");

        private final String id;
        private final String rotulo;

        Aba(String id, String rotulo) {
            this.id = id;
            this.rotulo = rotulo;
        }

        public String id() {
            return id;
        }

        public String rotulo() {
            return rotulo;
        }

        static Aba de(String id) {
            return Arrays.stream(values()).filter(a -> a.id.equals(id)).findFirst().orElse(VISAO_GERAL);
        }
    }

    /** O que a página pede a quem a abriu. */
    public interface Saida {

        /** O usuário quer voltar para a lista de projetos. */
        void voltar();

        /** O servidor diz que o projeto não existe mais. */
        void projetoSumiu();

        /** O usuário trocou de aba (para a página lembrar). */
        void abaMudou(Aba aba);

        /** O usuário pediu "Adicionar container" (a janela abre o diálogo; o app só escreve depois do clique de gravar). */
        default void adicionarContainer() { }

        /** A janela sabe adicionar container neste servidor? (o agente do lab ou este computador, com as ações liberadas) */
        default boolean podeAdicionarContainer() {
            return false;
        }

        /** O usuário pediu "Publicar na internet" (a janela abre o diálogo; o app só escreve depois do clique de confirmar). {@code depois} roda quando o diálogo termina. */
        default void publicarNaInternet(Runnable depois) { }

        /** O usuário pediu "Conectar a Cloudflare" (abre o diálogo do token). */
        default void conectarCloudflare(Runnable depois) { }

        /** O usuário pediu "Adicionar domínio" (a janela abre o assistente; o app só escreve no último passo, depois do clique de confirmar). {@code depois} roda quando termina. */
        default void adicionarDominio(Runnable depois) { }

        /** O usuário pediu para remover um endereço publicado (abre o diálogo de confirmação). */
        default void removerEndereco(String hostname, Runnable depois) { }

        /** O usuário pediu para abrir um endereço público no navegador. */
        default void abrirEndereco(String hostname) { }

        /** O que se oferece para um processo da lista (só a regra, sem rede); {@code null}: a aba Processos sem botões. */
        default br.com.labdesktop.processos.Oferta avaliarProcesso(br.com.labdesktop.processos.ProcessoAlvo alvo) {
            return null;
        }

        /** O usuário pediu Encerrar ou Reiniciar um processo (a janela abre a confirmação; o app só age depois do clique). */
        default void abrirProcesso(br.com.labdesktop.processos.ProcessoAlvo alvo, String origem, br.com.labdesktop.processos.Acao acao, Runnable depois) { }
    }

    private final transient ServidorLab servidor;
    private final String projeto;
    private final transient Saida saida;
    private final transient ContextoDoProjeto ctx;
    private final Botao voltar = Botoes.fantasma("‹ Projetos", null);
    private final Rotulo nome;
    private final Selo estado = new Selo("", br.com.labdesktop.ui.Gravidade.NEUTRO);
    private final Rotulo leitura = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(12f);
    private final Botao atualizar = Botoes.mini("Atualizar", "atualizar");
    /** "Adicionar domínio…" na barra da página do projeto, em toda aba: o usuário não achava onde dar um endereço novo ao projeto. Desligado, com a explicação, quando não vale. */
    private final Botao adicionarDominio = Botoes.mini("Adicionar domínio…", "externo");
    private final Abas abas = new Abas();
    private final CardLayout cartoes = new CartoesAjustaveis();
    private final JPanel corpo = new Painel(cartoes);
    private final Map<Aba, PaginaDoProjeto> paginas = new EnumMap<>(Aba.class);
    /** A aba Arquivos é o gerenciador de arquivos (SFTP), o mesmo componente da aba do servidor, com a raiz travada neste projeto. */
    private final GerenciadorDeArquivos arquivos;
    /** A aba Backups baixa pela fila de transferências, com o mesmo contexto do gerenciador de arquivos. */
    private final BaixadorDeBackups baixador;
    private Aba atual;
    private transient Inscricao inscricaoDoDetalhe;
    private transient Inscricao inscricaoDoEstado;

    /**
     * @param servidor    o servidor vivo ({@code null} nos testes que alimentam a página com dados fixos)
     * @param abaInicial  a aba em que o projeto estava (a Visão geral, na primeira vez)
     * @param urlDeLogin  o endereço de login do Claude que a lista de projetos já viu, ou {@code null}
     * @param aoAtualizar o que o botão Atualizar faz (consulta já tudo o que está assinado)
     */
    public TelaProjeto(ServidorLab servidor, String projeto, FluxosDeProjeto fluxos, Navegador navegador, Notificacoes avisos,
                       Aba abaInicial, Saida saida, Supplier<String> urlDeLogin, Runnable aoAtualizar) {
        this(servidor, projeto, fluxos, navegador, avisos, abaInicial, saida, urlDeLogin, aoAtualizar, CopiadorDeTexto.doSistema(),
            ServicosDeArquivos.descartaveis(), EscolhedorLocal.doSistema(() -> null));
    }

    /** Com a memória e os backups do gerenciador de arquivos, e quem escolhe o que enviar e onde baixar. */
    public TelaProjeto(ServidorLab servidor, String projeto, FluxosDeProjeto fluxos, Navegador navegador, Notificacoes avisos,
                       Aba abaInicial, Saida saida, Supplier<String> urlDeLogin, Runnable aoAtualizar, ServicosDeArquivos servicos,
                       EscolhedorLocal escolhedor) {
        this(servidor, projeto, fluxos, navegador, avisos, abaInicial, saida, urlDeLogin, aoAtualizar, CopiadorDeTexto.doSistema(), servicos,
            escolhedor);
    }

    TelaProjeto(ServidorLab servidor, String projeto, FluxosDeProjeto fluxos, Navegador navegador, Notificacoes avisos,
                Aba abaInicial, Saida saida, Supplier<String> urlDeLogin, Runnable aoAtualizar, CopiadorDeTexto copiador) {
        this(servidor, projeto, fluxos, navegador, avisos, abaInicial, saida, urlDeLogin, aoAtualizar, copiador,
            ServicosDeArquivos.descartaveis(), EscolhedorLocal.doSistema(() -> null));
    }

    TelaProjeto(ServidorLab servidor, String projeto, FluxosDeProjeto fluxos, Navegador navegador, Notificacoes avisos,
                Aba abaInicial, Saida saida, Supplier<String> urlDeLogin, Runnable aoAtualizar, CopiadorDeTexto copiador,
                ServicosDeArquivos servicos, EscolhedorLocal escolhedor) {
        this(servidor, projeto, fluxos, navegador, avisos, abaInicial, saida, urlDeLogin, aoAtualizar, copiador, servicos, escolhedor, null);
    }

    /**
     * @param terminal por onde abrir os terminais da aba Terminal e quantas vagas o servidor ainda tem; {@code null} (testes de
     *                 tela, sem servidor vivo): o terminal fica indisponível
     */
    TelaProjeto(ServidorLab servidor, String projeto, FluxosDeProjeto fluxos, Navegador navegador, Notificacoes avisos,
                Aba abaInicial, Saida saida, Supplier<String> urlDeLogin, Runnable aoAtualizar, CopiadorDeTexto copiador,
                ServicosDeArquivos servicos, EscolhedorLocal escolhedor, ContextoDeTerminal terminal) {
        super(new BorderLayout());
        fundo(Tokens.BG);
        this.servidor = servidor;
        this.projeto = projeto;
        this.saida = saida;
        this.nome = Rotulo.titulo(projeto);
        this.ctx = new ContextoDoProjeto(servidor, projeto, fluxos, navegador, avisos, copiador,
            urlDeLogin == null ? () -> null : urlDeLogin, this::mostrarAba, aoAtualizar == null ? () -> { } : aoAtualizar);
        this.ctx.terminal = terminal;
        this.ctx.pastaDoProjeto = () -> pastaDoProjeto(servicos, servidor, projeto);
        this.ctx.irParaOClaude = this::mostrarOClaude;
        this.ctx.adicionarContainer = saida::adicionarContainer;
        this.ctx.podeAdicionarContainer = saida::podeAdicionarContainer;
        this.ctx.publicarNaInternet = saida::publicarNaInternet;
        this.ctx.adicionarDominio = saida::adicionarDominio;
        this.ctx.conectarCloudflare = saida::conectarCloudflare;
        this.ctx.removerEndereco = saida::removerEndereco;
        this.ctx.abrirEndereco = saida::abrirEndereco;
        this.ctx.acoesDeProcessos = new AcoesDeProcessos() {
            @Override
            public boolean liberadas() {
                return fluxos != null && fluxos.liberadas();
            }

            @Override
            public br.com.labdesktop.processos.Oferta avaliar(br.com.labdesktop.processos.ProcessoAlvo alvo) {
                br.com.labdesktop.processos.Oferta o = saida.avaliarProcesso(alvo);
                return o != null ? o : br.com.labdesktop.processos.AvaliadorDeProcessos.avaliar(alvo,
                    br.com.labdesktop.processos.AmbienteDeProcessos.servidor("", java.util.Set.of()));
            }

            @Override
            public void abrir(br.com.labdesktop.processos.ProcessoAlvo alvo, String origem, br.com.labdesktop.processos.Acao acao, Runnable depois) {
                saida.abrirProcesso(alvo, origem, acao, depois);
            }
        };

        paginas.put(Aba.VISAO_GERAL, new PaginaVisaoGeral(ctx));
        paginas.put(Aba.LOGS, new PaginaLogs(ctx));
        paginas.put(Aba.TERMINAL, new PaginaTerminalDoProjeto(ctx));
        ContextoDeArquivos contextoDeArquivos = ContextoDeArquivos.de(servidor, projeto, servicos, fluxos != null && fluxos.liberadas(),
            avisos, fluxos == null ? dialogo -> { } : fluxos.apresentador(), escolhedor).comCopiador(copiador);
        this.arquivos = new GerenciadorDeArquivos(contextoDeArquivos);
        this.baixador = new BaixadorDeBackups(contextoDeArquivos, ctx.pastaDoProjeto);
        this.ctx.baixarBackups = baixador::baixar;
        this.ctx.baixadorDeBackups = baixador;
        paginas.put(Aba.BACKUPS, new PaginaBackups(ctx));
        paginas.put(Aba.CONSUMO, new PaginaConsumo(ctx));
        paginas.put(Aba.INTERNET, new PaginaInternet(ctx));
        paginas.put(Aba.PROCESSOS, new PaginaProcessosDoProjeto(ctx));

        voltar.setToolTipText("Volta para a lista de projetos (Esc)");
        voltar.getAccessibleContext().setAccessibleName("Voltar para a lista de projetos");
        br.com.labdesktop.ui.tema.Fontes.aplicar(voltar, br.com.labdesktop.ui.tema.Fontes.TEXTO, java.awt.Font.PLAIN, 13f);
        voltar.addActionListener(e -> saida.voltar());
        atualizar.setToolTipText("Consulta o servidor agora");
        atualizar.addActionListener(e -> ctx.aoAtualizar.run());
        nome.setToolTipText(projeto);
        adicionarDominio.setName("adicionar-dominio");
        adicionarDominio.getAccessibleContext().setAccessibleName("Adicionar domínio a este projeto");
        adicionarDominio.addActionListener(e -> ctx.adicionarDominio.accept(this::recarregarInternet));
        atualizarBotaoDoDominio();
        JPanel linha = Caixa.linha(12, voltar, nome, new AEsquerda(estado), Caixa.mola(), leitura, adicionarDominio, atualizar);
        linha.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 10, 4, 18));
        abas.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 12, 0, 12));
        for (Aba a : Aba.values()) {
            abas.adicionar(a.id, a.rotulo);
            corpo.add(a == Aba.ARQUIVOS ? arquivos : paginas.get(a), a.id);
        }
        if (servidor != null && servidor.ehLocal()) {
            // "Este computador": logs e consumo são do agente do lab (os backups são da pasta backups/ do projeto, lidos pelo app); a página nasce sem eles (e não os pisca até o primeiro detalhe)
            abas.definirVisivel(Aba.LOGS.id, false);
            abas.definirVisivel(Aba.CONSUMO.id, false);
            abas.definirVisivel(Aba.PROCESSOS.id, false); // os programas de um projeto local rodam no Docker: Reiniciar e Parar na Visão geral
        }
        abas.aoSelecionar(id -> mostrarAba(Aba.de(id)));
        JPanel topo = new Painel(new BorderLayout());
        topo.add(linha, BorderLayout.NORTH);
        topo.add(abas, BorderLayout.SOUTH);
        add(topo, BorderLayout.NORTH);
        add(corpo, BorderLayout.CENTER);
        atual = abaInicial == null ? Aba.VISAO_GERAL : abaInicial;
        abas.selecionar(atual.id);
        cartoes.show(corpo, atual.id);
        atualizarCabecalho();

        // Esc volta à lista; num campo de texto com algo escrito, Esc só limpa o campo (não perde a mensagem por engano).
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "voltar-aos-projetos");
        getActionMap().put("voltar-aos-projetos", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                aoApertarEsc(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
            }
        });
        getAccessibleContext().setAccessibleName("Projeto " + projeto);
        setName("tela-do-projeto-" + projeto);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (isShowing()) {
                    assinar();
                } else {
                    soltar();
                }
            }
        });
    }

    /**
     * O que Esc faz: num campo de texto com algo escrito, só limpa o campo (a mensagem que o usuário digitava não se perde por
     * engano); em qualquer outro lugar, volta para a lista de projetos.
     *
     * @param foco quem tem o foco agora, ou {@code null}
     */
    void aoApertarEsc(Component foco) {
        // o editor de arquivos cuida do próprio Esc (fecha a busca); aqui Esc nunca limpa o texto dele nem sai da página com alterações
        for (Component c = foco; c != null; c = c.getParent()) {
            if (c instanceof JComponent j && Boolean.TRUE.equals(j.getClientProperty(GerenciadorDeArquivos.PROPRIEDADE_ESC))) {
                return;
            }
        }
        if (foco instanceof JTextComponent t && t.isEditable() && !t.getText().isEmpty()) {
            t.setText("");
            return;
        }
        voltar.doClick(0);
    }

    // ---------------------------------------------------------------------------------------------
    // Abas
    // ---------------------------------------------------------------------------------------------

    /** Mostra uma aba (e marca o rótulo). */
    public void mostrarAba(Aba aba) {
        boolean mudou = aba != atual;
        atual = aba;
        abas.selecionar(aba.id);
        cartoes.show(corpo, aba.id);
        corpo.revalidate();
        corpo.repaint();
        if (mudou) {
            saida.abaMudou(aba);
        }
    }

    public Aba abaAtual() {
        return atual;
    }

    /** A aba marcada no rótulo (os testes conferem que rótulo e conteúdo andam juntos). */
    public Aba abaMarcada() {
        return Aba.de(abas.selecionada());
    }

    /** Os ids de todas as abas da página do projeto, na ordem padrão. */
    public static final java.util.List<String> IDS_DAS_ABAS = java.util.Arrays.stream(Aba.values()).map(Aba::id).toList();

    /** A ordem e as abas escondidas que o usuário escolheu em Ajustes, Navegação (a aba aberta nunca some). */
    public void aplicarPreferenciaDeAbas(br.com.labdesktop.util.OrdemDeAbas ordem) {
        abas.aplicarPreferencia(ordem, IDS_DAS_ABAS);
    }

    public Abas abas() {
        return abas;
    }

    public String projeto() {
        return projeto;
    }

    // ---------------------------------------------------------------------------------------------
    // Assinatura: só o detalhe do projeto, só enquanto esta página está na tela
    // ---------------------------------------------------------------------------------------------

    /** Os tópicos que ESTA página assina (as abas assinam os próprios, só quando visíveis): só o detalhe do projeto. */
    public Set<String> topicos() {
        return Set.of(Topico.detalhe(projeto).chave());
    }

    /** Passa a receber o detalhe do projeto (e a consultar o servidor). Chamado ao ficar visível; idempotente. */
    public void assinar() {
        if (servidor == null || inscricaoDoEstado != null) {
            return;
        }
        inscricaoDoDetalhe = servidor.assinar(servidor.topicoDeDetalhe(projeto), new OuvinteDeTopico<Detalhe>() {
            @Override
            public void aoAtualizar(Detalhe valor) {
                aoReceberDetalhe(valor);
            }

            @Override
            public void aoConfirmar() {
                aoConfirmarDetalhe();
            }

            @Override
            public void aoFalhar(ErroApi erro) {
                aoFalharDetalhe(erro);
            }
        });
        inscricaoDoEstado = servidor.observarEstado(this::aoMudarEstado);
    }

    /** Para de consultar. Chamado ao esconder; idempotente. */
    public void soltar() {
        for (Inscricao i : new Inscricao[] {inscricaoDoDetalhe, inscricaoDoEstado}) {
            if (i != null) {
                i.close();
            }
        }
        inscricaoDoDetalhe = null;
        inscricaoDoEstado = null;
        paginas.values().forEach(PaginaDoProjeto::soltar);
    }

    public boolean assinada() {
        return inscricaoDoEstado != null;
    }

    /** Há alterações num editor da aba Arquivos que ainda não foram salvas. */
    public boolean temAlteracoesNaoSalvas() {
        return arquivos.temAlteracoesNaoSalvas();
    }

    /** Há um terminal aberto nesta página (o shell ou o do Claude)? Sair da página o fecharia. */
    public boolean temTerminalVivo() {
        return terminal().temTerminalVivo();
    }

    /** Editores com alterações não salvas e transferências ainda abertas desta página (para quem vai sair do app). */
    public br.com.labdesktop.ui.Pendencias pendencias() {
        return new br.com.labdesktop.ui.Pendencias(arquivos.transferenciasEmAberto() + baixador.emAberto(), arquivos.editoresComAlteracoes());
    }

    /** A página saiu de vez: fecha o canal SFTP, os editores e os terminais. */
    public void encerrar() {
        soltar();
        arquivos.encerrar();
        baixador.encerrar();
        fecharTerminais();
    }

    /**
     * Fecha os terminais da aba Terminal (shell e Claude: o canal e o widget) e devolve as vagas ao servidor: sair da página do projeto
     * ou bloquear o cofre. Trocar de aba DENTRO do projeto não passa por aqui: os terminais continuam vivos por trás.
     */
    public void fecharTerminais() {
        terminal().encerrarTodos();
    }

    /** A pasta do projeto no servidor (raiz dos projetos do servidor mais o nome), ou {@code null} se não der para montar. */
    private static String pastaDoProjeto(ServicosDeArquivos servicos, ServidorLab servidor, String projeto) {
        try {
            return CaminhoRemoto.juntar(servicos.memoria().raizDosProjetos(servidor == null ? "sem-servidor" : servidor.id()), projeto);
        } catch (ErroSftp e) {
            return null;
        }
    }

    /** Mostra a aba Terminal já na sessão do Claude (o botão "Ver sessão do Claude" da Visão geral). */
    public void mostrarOClaude() {
        mostrarAba(Aba.TERMINAL);
        terminal().escolherOClaude();
    }

    // ---------------------------------------------------------------------------------------------
    // Snapshots (na EDT). Públicos para os testes alimentarem a página com dados fixos.
    // ---------------------------------------------------------------------------------------------

    public void aoReceberDetalhe(Detalhe novo) {
        if (!(novo instanceof DetalheProjeto d)) {
            return; // a sessão raiz não tem página de projeto
        }
        ctx.detalhe = d;
        ctx.erroSemDados = null;
        ctx.ultimaLeitura = ctx.agora();
        redesenhar();
    }

    /** O agente respondeu e nada mudou: o detalhe de agora é o que está na tela, então a hora da leitura anda. */
    public void aoConfirmarDetalhe() {
        if (ctx.detalhe != null) {
            ctx.ultimaLeitura = ctx.agora();
            atualizarCabecalho();
        }
    }

    public void aoMudarEstado(EstadoServidor novo) {
        ctx.estado = novo;
        redesenhar();
    }

    /** A consulta do detalhe falhou: 404 = o projeto sumiu; rede = a faixa de conexão explica; o resto só aparece sem dados. */
    public void aoFalharDetalhe(ErroApi erro) {
        if (erro instanceof ErroApi.NaoEncontrado) {
            saida.projetoSumiu();
            return;
        }
        if (ctx.detalhe != null || erro instanceof ErroApi.ErroRede) {
            return;
        }
        ctx.erroSemDados = erro;
        redesenhar();
    }

    /** Troca o relógio e o fuso (os testes fixam a hora para o "há 4 d" não variar). */
    void relogio(Supplier<Instant> novo, ZoneId novoFuso) {
        ctx.relogio = novo;
        ctx.fuso = novoFuso;
        redesenhar();
    }

    /** "Adicionar domínio…": ligado quando as ações estão liberadas e o servidor tem túnel; senão desligado e com a razão na dica (nunca some). */
    private void atualizarBotaoDoDominio() {
        boolean local = servidor != null && servidor.ehLocal();
        boolean liberadas = ctx.acoesLiberadas();
        adicionarDominio.setEnabled(liberadas && !local);
        adicionarDominio.setToolTipText(local ? "Este computador não tem um túnel da Cloudflare configurado pelo app: o endereço público é de um servidor por SSH."
            : !liberadas ? TextosDeAcao.TRAVA : "Dá a este projeto um endereço novo na internet, em passos curtos (nada é escrito antes da sua confirmação)");
    }

    private void recarregarInternet() {
        internet().recarregar();
    }

    private void redesenhar() {
        atualizarBotaoDoDominio();
        atualizarCabecalho();
        // Servidor sem Claude: não há consumo nem limite do plano a mostrar, então a aba some (e, se estava aberta, a Visão geral a substitui).
        boolean semClaude = (ctx.estado != null && Boolean.FALSE.equals(ctx.estado.temClaude())) || (ctx.detalhe != null && !ctx.temClaude());
        // "Este computador": os projetos vêm da pasta e do Docker, sem o agente de projetos; logs e consumo são do agente do lab, e os backups vêm da pasta backups/
        boolean local = servidor != null && servidor.ehLocal();
        abas.definirVisivel(Aba.LOGS.id, !local);
        abas.definirVisivel(Aba.CONSUMO.id, !semClaude && !local);
        abas.definirVisivel(Aba.PROCESSOS.id, !local);
        if ((semClaude || local) && atual == Aba.CONSUMO || local && (atual == Aba.LOGS || atual == Aba.PROCESSOS)) {
            mostrarAba(Aba.VISAO_GERAL);
        }
        paginas.values().forEach(PaginaDoProjeto::redesenhar);
        // o contador de backups mora no rótulo da aba, como na web
        abas.rotulo(Aba.BACKUPS.id, ctx.detalhe == null ? Aba.BACKUPS.rotulo
            : Aba.BACKUPS.rotulo + " (" + ctx.detalhe.backups().size() + ")");
    }

    private void atualizarCabecalho() {
        ModeloDoProjeto m = ctx.modelo();
        if (m == null) {
            estado.setVisible(false);
        } else {
            estado.setVisible(true);
            estado.atualizar(m.estado().rotulo(), m.gravidade(), null);
        }
        leitura.setText(ctx.ultimaLeitura == null ? "" : "Leitura das " + Formato.fmtHora(ctx.ultimaLeitura, ctx.fuso));
    }

    // ---------------------------------------------------------------------------------------------
    // Para os testes
    // ---------------------------------------------------------------------------------------------

    PaginaDoProjeto pagina(Aba aba) {
        return paginas.get(aba);
    }

    PaginaVisaoGeral visaoGeral() {
        return (PaginaVisaoGeral) paginas.get(Aba.VISAO_GERAL);
    }

    PaginaLogs logs() {
        return (PaginaLogs) paginas.get(Aba.LOGS);
    }

    PaginaTerminalDoProjeto terminal() {
        return (PaginaTerminalDoProjeto) paginas.get(Aba.TERMINAL);
    }

    /** O gerenciador de arquivos deste projeto. */
    public GerenciadorDeArquivos arquivos() {
        return arquivos;
    }

    /** O contexto da página (os testes trocam o que "Baixar" e as ações de processos fazem). */
    ContextoDoProjeto contexto() {
        return ctx;
    }

    BaixadorDeBackups baixador() {
        return baixador;
    }

    PaginaProcessosDoProjeto processos() {
        return (PaginaProcessosDoProjeto) paginas.get(Aba.PROCESSOS);
    }

    PaginaBackups backups() {
        return (PaginaBackups) paginas.get(Aba.BACKUPS);
    }

    PaginaInternet internet() {
        return (PaginaInternet) paginas.get(Aba.INTERNET);
    }

    PaginaConsumo consumo() {
        return (PaginaConsumo) paginas.get(Aba.CONSUMO);
    }

    Botao botaoVoltar() {
        return voltar;
    }

    Botao botaoAdicionarDominio() {
        return adicionarDominio;
    }

    Botao botaoAtualizar() {
        return atualizar;
    }

    String textoDoNome() {
        return nome.getText();
    }

    Selo seloDoEstado() {
        return estado;
    }

    String textoDaLeitura() {
        return leitura.getText();
    }

    JComponent topoDaPagina() {
        return (JComponent) getComponent(0);
    }
}
