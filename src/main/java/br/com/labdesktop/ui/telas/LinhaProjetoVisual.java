package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.modelo.Limite;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.Navegador;
import br.com.labdesktop.ui.Severidade;
import br.com.labdesktop.ui.componentes.BarraSeveridade;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.Selo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.telas.ColunasDaLista.Coluna;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Formato;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.Set;
import java.util.function.BiConsumer;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * UMA linha da lista de projetos (ARQUITETURA 8.3, tradução do {@code .lp-linha} do {@code index.html}): estado,
 * projeto, containers, CPU, memória, consumo de 7 dias com o limite, git, URL pública, Claude e ações, mais a faixa de
 * login pendente embaixo. É criada UMA vez por projeto e atualizada no lugar ({@link #atualizar}): o menu aberto, o
 * foco e o código de login digitado sobrevivem ao polling de 10 s (o {@code capturarEstadoLista} do front).
 *
 * <p>Só desenha o retrato imutável ({@link LinhaDeProjeto}); toda ação sai por {@link FluxosDeProjeto}, que roda a
 * rede fora da EDT. Texto do servidor entra por {@code Rotulo} e {@code Botao}, que desligam o HTML.
 */
final class LinhaProjetoVisual extends Painel {

    private static final long serialVersionUID = 1L;

    private final FluxosDeProjeto fluxos;
    private final Navegador navegador;
    private final BiConsumer<String, String> enviarCodigoDeLogin;

    private LinhaDeProjeto modelo;
    private Limite limite;
    private boolean sobMouse;

    // estado
    private final PontoDeEstado ponto = new PontoDeEstado();
    private final Rotulo estadoTexto = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12.5f);
    // projeto
    private final Rotulo nome = new Rotulo().negrito().tamanho(14f).truncavel();
    private final Rotulo semDocker = new Rotulo();
    private final Rotulo descricao = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(12f).truncavel();
    // métricas
    private final Rotulo containers = Rotulo.secundario("").tamanho(12.5f).aDireita();
    private final Rotulo cpu = Rotulo.secundario("").tamanho(12.5f).aDireita();
    private final Rotulo memoria = Rotulo.secundario("").tamanho(12.5f).aDireita();
    // consumo
    private final Rotulo custo = new Rotulo().negrito().tamanho(12.5f).aDireita().truncavel();
    private final Rotulo tokens = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(11.5f);
    private final Rotulo semConsumo = Rotulo.dica("—");
    private final Rotulo limiteTexto = new Rotulo().tamanho(11f).aDireita().truncavel();
    private final BarraSeveridade limiteBarra = new BarraSeveridade(BarraSeveridade.Tamanho.FINA);
    // git
    private final Rotulo git = new Rotulo().mono(12f).corDoToken(Tokens.TEXTO2).truncavel();
    private final Rotulo gitSujo = new Rotulo("não commitado").corDoToken(Tokens.TEXTO3).tamanho(10.5f);
    // url
    private final Botao url = Botoes.link("");
    private final Rotulo urlMais = Rotulo.dica("");
    private final Rotulo semUrl = Rotulo.dica("—");
    // claude
    private final Selo seloClaude = new Selo("", Gravidade.NEUTRO);
    private final Selo seloDesatualizada = new Selo("desatualizada", Gravidade.ATENCAO);
    private final Botao reabrir = Botoes.mini("Reabrir", "atualizar");
    private final Botao retomar = Botoes.mini("Retomar", "play");
    private final Rotulo semClaude = Rotulo.dica("—");
    // ações
    private final Botao botaoCompose = Botoes.icone("play", "Iniciar");
    private final Botao botaoAbrirClaude = Botoes.iconePrimario("terminal", "Abrir Claude");
    private final Botao botaoMenu = Botoes.icone("mais-acoes", "Mais ações");
    // login pendente
    private final JPanel faixaDeLogin = new Painel(new BorderLayout(10, 0)).fundo(Tokens.ATENCAO_SUAVE).borda(Tokens.ATENCAO).raio(10);
    private final Rotulo tituloDoLogin = new Rotulo().negrito();
    private final Botao abrirLogin = Botoes.mini("Abrir página de login", "externo");
    private final JTextField campoDoCodigo = Campos.mono(18, "cole o código aqui");
    private final Botao enviarCodigo = Botoes.miniPrimario("Enviar", null);
    private final TextoCorrido ajudaDoLogin = new TextoCorrido("", 560).corDoToken(Tokens.TEXTO2).tamanho(12f);

    // as células que mudam de conteúdo
    private final JComponent celulaDoConsumo;
    private final JComponent celulaDaUrl;
    private final JComponent celulaDoClaude;
    private final JPanel grade = new Painel(new GridBagLayout());

    LinhaProjetoVisual(FluxosDeProjeto fluxos, Navegador navegador, BiConsumer<String, String> enviarCodigoDeLogin) {
        super(new BorderLayout());
        this.fluxos = fluxos;
        this.navegador = navegador;
        this.enviarCodigoDeLogin = enviarCodigoDeLogin;

        semDocker.setIcon(Icones.de("fechar", 12));
        semDocker.corDoToken(Tokens.TEXTO3);
        semDocker.setToolTipText("Sem Docker do projeto (.lab-sem-docker)");
        url.setMinimumSize(new Dimension(0, 22));
        url.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);

        celulaDoConsumo = colunaADireita(2, linhaADireita(custo, tokens, semConsumo), limiteTexto, limiteBarra);
        celulaDaUrl = Caixa.linha(6, url, urlMais, semUrl);
        celulaDoClaude = Caixa.coluna(5, seloClaude, seloDesatualizada, reabrir, retomar, semClaude);

        grade.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, ColunasDaLista.RECUO, 10, ColunasDaLista.RECUO));
        grade.add(ColunasDaLista.celula(Coluna.ESTADO, Caixa.linha(7, ponto, estadoTexto)), ColunasDaLista.restricoes(Coluna.ESTADO));
        grade.add(ColunasDaLista.celula(Coluna.PROJETO, Caixa.coluna(2, Caixa.linha(6, nome, semDocker), descricao)),
            ColunasDaLista.restricoes(Coluna.PROJETO));
        grade.add(ColunasDaLista.celula(Coluna.CONTAINERS, containers), ColunasDaLista.restricoes(Coluna.CONTAINERS));
        grade.add(ColunasDaLista.celula(Coluna.CPU, cpu), ColunasDaLista.restricoes(Coluna.CPU));
        grade.add(ColunasDaLista.celula(Coluna.MEMORIA, memoria), ColunasDaLista.restricoes(Coluna.MEMORIA));
        grade.add(ColunasDaLista.celula(Coluna.CONSUMO, celulaDoConsumo), ColunasDaLista.restricoes(Coluna.CONSUMO));
        grade.add(ColunasDaLista.celula(Coluna.GIT, Caixa.coluna(3, git, gitSujo)), ColunasDaLista.restricoes(Coluna.GIT));
        grade.add(ColunasDaLista.celula(Coluna.URL, celulaDaUrl), ColunasDaLista.restricoes(Coluna.URL));
        grade.add(ColunasDaLista.celula(Coluna.CLAUDE, celulaDoClaude), ColunasDaLista.restricoes(Coluna.CLAUDE));
        grade.add(ColunasDaLista.celula(Coluna.ACOES, Caixa.linha(6, botaoCompose, botaoAbrirClaude, botaoMenu)),
            ColunasDaLista.restricoes(Coluna.ACOES));
        add(grade, BorderLayout.CENTER);

        montarFaixaDeLogin();
        faixaDeLogin.setVisible(false);
        JPanel embaixo = new Painel(new BorderLayout());
        embaixo.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, ColunasDaLista.RECUO, 14, ColunasDaLista.RECUO));
        embaixo.add(faixaDeLogin, BorderLayout.CENTER);
        add(embaixo, BorderLayout.SOUTH);

        ligarEventos();
        instalarHover(this);
        getAccessibleContext().setAccessibleName("Projeto");
    }

    /** O x de cada célula da grade, na ordem das colunas (o teste compara com o do cabeçalho). */
    java.util.List<Integer> xsDasColunas() {
        return java.util.Arrays.stream(grade.getComponents()).map(Component::getX).toList();
    }

    int larguraDaColuna(Coluna c) {
        return grade.getComponent(c.ordinal()).getWidth();
    }

    private void montarFaixaDeLogin() {
        faixaDeLogin.setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 12, 10, 12));
        Rotulo marca = new Rotulo().icone(Icones.colorido("alerta", 16, Tokens.ATENCAO));
        marca.setVerticalAlignment(javax.swing.SwingConstants.TOP);
        faixaDeLogin.add(marca, BorderLayout.WEST);
        if (fluxos.liberadas()) {
            ajudaDoLogin.setText("Abra a página, entre na conta e copie o código; depois cole aqui e o painel "
                + "envia para o terminal da sessão.");
            JPanel acoes = Caixa.linha(8, abrirLogin, campoDoCodigo, enviarCodigo);
            faixaDeLogin.add(Caixa.coluna(6, tituloDoLogin, ajudaDoLogin, acoes), BorderLayout.CENTER);
        } else {
            // Somente leitura: o alerta informa, mas enviar o código é ação de escrita e fica desligada.
            ajudaDoLogin.setText("Conclua o login pelo painel web: com as ações de escrita desligadas, o app não envia o código "
                + "à sessão.");
            faixaDeLogin.add(Caixa.coluna(6, tituloDoLogin, ajudaDoLogin), BorderLayout.CENTER);
        }
        enviarCodigo.setEnabled(false);
    }

    private static JComponent colunaADireita(int espaco, Component... filhos) {
        JPanel p = new Painel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        boolean primeiro = true;
        for (Component c : filhos) {
            if (!primeiro && espaco > 0) {
                p.add(Caixa.espacoVertical(espaco));
            }
            ((JComponent) c).setAlignmentX(Component.RIGHT_ALIGNMENT);
            p.add(c);
            primeiro = false;
        }
        return p;
    }

    private static JComponent linhaADireita(Component... filhos) {
        JPanel p = new Painel();
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        p.add(Caixa.mola());
        for (Component c : filhos) {
            p.add(c);
        }
        return p;
    }

    private void ligarEventos() {
        botaoCompose.addActionListener(e -> {
            if (modelo == null) {
                return;
            }
            if (modelo.mostrarParar()) {
                fluxos.parar(modelo.nome());
            } else {
                fluxos.iniciar(modelo.nome());
            }
        });
        botaoAbrirClaude.addActionListener(e -> {
            if (modelo != null) {
                fluxos.abrirSessao(modelo.nome());
            }
        });
        botaoMenu.addActionListener(e -> mostrarMenu());
        reabrir.addActionListener(e -> {
            if (modelo != null) {
                fluxos.pedirReabrir(modelo.nome());
            }
        });
        retomar.addActionListener(e -> {
            if (modelo != null) {
                fluxos.retomar(modelo.nome(), limite);
            }
        });
        url.addActionListener(e -> {
            if (modelo != null && modelo.urlPrincipal() != null) {
                navegador.abrirHostDeProjeto(modelo.urlPrincipal());
            }
        });
        abrirLogin.addActionListener(e -> {
            if (modelo != null && modelo.urlDeLogin() != null) {
                navegador.abrirLogin(modelo.urlDeLogin());
            }
        });
        campoDoCodigo.getDocument().addDocumentListener(
            new br.com.labdesktop.ui.dialogos.SimplesOuvinteDeDocumento(
                () -> enviarCodigo.setEnabled(!campoDoCodigo.getText().isBlank())));
        Runnable enviar = () -> {
            String codigo = campoDoCodigo.getText().strip();
            if (modelo != null && !codigo.isEmpty() && enviarCodigo.isEnabled()) {
                enviarCodigoDeLogin.accept(modelo.nome(), codigo);
            }
        };
        enviarCodigo.addActionListener(e -> enviar.run());
        campoDoCodigo.addActionListener(e -> enviar.run());
    }

    // ---------------------------------------------------------------------------------------------
    // Estado visível
    // ---------------------------------------------------------------------------------------------

    /** O projeto que esta linha mostra, ou {@code null} antes da primeira atualização. */
    String projeto() {
        return modelo == null ? null : modelo.nome();
    }

    LinhaDeProjeto modelo() {
        return modelo;
    }

    /**
     * Mostra o retrato. Se for igual ao que já está na tela, nada é tocado (só a ocupação, que depende de outra fonte).
     *
     * @param limiteAtual o {@code Limite} do DTO, para Definir limite e para o diálogo "ainda acima do limite"
     */
    void atualizar(LinhaDeProjeto novo, Limite limiteAtual) {
        this.limite = limiteAtual;
        if (!novo.equals(modelo)) {
            this.modelo = novo;
            preencher(novo);
        }
        aplicarOcupacao();
    }

    /** Reaplica o "ocupado" dos botões a partir de {@link FluxosDeProjeto}. */
    void aplicarOcupacao() {
        if (modelo == null) {
            return;
        }
        Set<String> o = fluxos.ocupadas(modelo.nome());
        botaoCompose.definirOcupado(o.contains(FluxosDeProjeto.INICIAR) || o.contains(FluxosDeProjeto.PARAR)
            || o.contains(FluxosDeProjeto.REINICIAR));
        botaoAbrirClaude.definirOcupado(o.contains(FluxosDeProjeto.ABRIR));
        botaoMenu.definirOcupado(o.contains(FluxosDeProjeto.FECHAR));
        reabrir.definirOcupado(o.contains(FluxosDeProjeto.REABRIR));
        retomar.definirOcupado(o.contains(FluxosDeProjeto.RETOMAR));
    }

    private void preencher(LinhaDeProjeto m) {
        ponto.definir(m.gravidadeDoEstado());
        estadoTexto.setText(m.estado().rotulo());

        nome.setText(m.nome());
        nome.setToolTipText(m.nome());
        semDocker.setVisible(m.semDocker());
        descricao.setText(m.descricao());
        descricao.setToolTipText(m.descricao());
        descricao.setVisible(!m.descricao().isEmpty());

        containers.setText(m.containers() == null ? Formato.SEM_DADO : m.containers());
        cpu.setText(m.cpu() == null ? Formato.SEM_DADO : m.cpu());
        memoria.setText(m.memoria() == null ? Formato.SEM_DADO : m.memoria());

        preencherConsumo(m);
        preencherGit(m);

        url.setText(m.urlPrincipal() == null ? "" : m.urlPrincipal());
        url.setToolTipText(m.urlPrincipal());
        url.setVisible(m.urlPrincipal() != null);
        semUrl.setVisible(m.urlPrincipal() == null);
        urlMais.setText(m.urlsExtras() > 0 ? "+" + m.urlsExtras() : "");
        urlMais.setVisible(m.urlsExtras() > 0);

        preencherClaude(m);

        boolean parar = m.mostrarParar();
        boolean acoes = fluxos.liberadas(); // no modo somente leitura: nenhum botão que mude o servidor
        botaoCompose.setVisible(acoes && (m.mostrarParar() || m.mostrarIniciar()));
        botaoCompose.setIcon(Icones.de(parar ? "parar" : "play", 14));
        botaoCompose.setToolTipText((parar ? "Parar " : "Iniciar ") + m.nome());
        botaoCompose.getAccessibleContext().setAccessibleName((parar ? "Parar " : "Iniciar ") + m.nome());
        botaoAbrirClaude.setVisible(acoes && m.oferecerAbrirClaude());
        botaoAbrirClaude.setToolTipText("Abrir Claude em " + m.nome());
        botaoAbrirClaude.getAccessibleContext().setAccessibleName("Abrir Claude em " + m.nome());
        botaoMenu.setVisible(acoes && m.temMenu());
        botaoMenu.getAccessibleContext().setAccessibleName("Mais ações em " + m.nome());

        faixaDeLogin.setVisible(m.temAlerta());
        friso(m.temAlerta() ? 3 : 0, Tokens.ATENCAO);
        tituloDoLogin.setText("Login do Claude pendente — " + m.nome());
        getAccessibleContext().setAccessibleName("Projeto " + m.nome());
        revalidate();
        repaint();
    }

    private void preencherConsumo(LinhaDeProjeto m) {
        boolean tem = m.consumo() != null;
        custo.setVisible(tem);
        tokens.setVisible(tem);
        semConsumo.setVisible(!tem);
        if (tem) {
            custo.setText(m.consumo().custo());
            tokens.setText(m.consumo().tokens());
            custo.setToolTipText("7 dias — US$ equivalente de API (a conta é assinatura, não cobra por token)");
        }
        LinhaDeProjeto.LimiteDaLinha l = m.limite();
        limiteTexto.setVisible(l != null);
        limiteBarra.setVisible(l != null && l.temBarra());
        if (l != null) {
            limiteTexto.setText(l.texto());
            limiteTexto.setToolTipText(l.texto() + ". Fatia estimada da semana do plano contra o limite do projeto."
                + (l.liberado() ? " Liberado: sem teto e sem freio até a semana virar." : ""));
            String token = l.pausado() ? Tokens.PERIGO : l.severidade() == Severidade.ALTA ? Tokens.ATENCAO : Tokens.TEXTO3;
            limiteTexto.corDoToken(token);
            limiteBarra.definir(l.fracao(), l.severidade(), l.descricao());
            limiteBarra.getAccessibleContext().setAccessibleName(l.descricao());
        }
    }

    private void preencherGit(LinhaDeProjeto m) {
        git.setText(m.git() == null ? Formato.SEM_DADO : m.git());
        git.setToolTipText(m.git());
        gitSujo.setVisible(m.git() != null && m.gitSujo());
    }

    private void preencherClaude(LinhaDeProjeto m) {
        LinhaDeProjeto.Claude c = m.claude();
        seloClaude.setVisible(false);
        seloDesatualizada.setVisible(false);
        reabrir.setVisible(false);
        retomar.setVisible(false);
        semClaude.setVisible(false);
        switch (c.tipo()) {
            case LOGIN_PENDENTE -> {
                seloClaude.atualizar("login pendente", Gravidade.ATENCAO, "chave");
                seloClaude.setVisible(true);
            }
            case SESSAO_ABERTA -> {
                seloClaude.atualizar("sessão aberta", Gravidade.ACENTO, null);
                seloClaude.setVisible(true);
                seloClaude.setToolTipText(null);
                if (c.desatualizada()) {
                    seloDesatualizada.setToolTipText("Falta: " + c.motivoDesatualizada());
                    seloDesatualizada.setVisible(true);
                    reabrir.setToolTipText("Reabrir (continua a conversa)");
                    reabrir.setVisible(fluxos.liberadas());
                }
            }
            case PAUSADA -> {
                seloClaude.atualizar(c.rotuloDaPausa(), c.pausadaPorFreio() ? Gravidade.ATENCAO : Gravidade.PERIGO, "pausa");
                seloClaude.setToolTipText(dicaDaPausa(c));
                seloClaude.setVisible(true);
                retomar.setToolTipText("Tira a pausa e reabre o Claude continuando a conversa");
                retomar.setVisible(fluxos.liberadas());
            }
            case NENHUMA -> semClaude.setVisible(true);
        }
    }

    private static String dicaDaPausa(LinhaDeProjeto.Claude c) {
        String quando = c.pausadoEm() == null ? "" : " " + Formato.fmtHa(c.pausadoEm(), Instant.now());
        return c.dicaDaPausa() + quando;
    }

    // ---------------------------------------------------------------------------------------------
    // Menu "mais ações"
    // ---------------------------------------------------------------------------------------------

    private void mostrarMenu() {
        JPopupMenu menu = montarMenu();
        if (menu != null) {
            menu.show(botaoMenu, 0, botaoMenu.getHeight() + 2);
        }
    }

    /** O menu "mais ações" com o que vale para o retrato de agora (montado na hora, nunca guardado). */
    JPopupMenu montarMenu() {
        if (modelo == null || !fluxos.liberadas()) {
            return null;
        }
        JPopupMenu menu = new JPopupMenu();
        String p = modelo.nome();
        if (modelo.temCompose()) {
            menu.add(item("Reiniciar", "atualizar", false, () -> fluxos.reiniciar(p)));
        }
        if (modelo.oferecerReiniciarSessao()) {
            menu.add(item("Reiniciar sessão (continua a conversa)", "atualizar", false, () -> fluxos.pedirReabrir(p)));
        }
        if (modelo.oferecerDefinirLimite()) {
            menu.add(item("Definir limite", "moeda", false, () -> fluxos.definirLimite(p, limite, null)));
        }
        if (modelo.oferecerFecharSessao()) {
            menu.add(item("Fechar sessão", "fechar", true, () -> fluxos.fecharSessao(p)));
        }
        return menu;
    }

    private static JMenuItem item(String texto, String icone, boolean perigo, Runnable acao) {
        JMenuItem i = new JMenuItem(texto);
        i.putClientProperty("html.disable", Boolean.TRUE);
        i.setIcon(Icones.de(icone, 14));
        if (perigo) {
            i.setForeground(Tokens.cor(Tokens.PERIGO));
        }
        i.addActionListener(e -> acao.run());
        return i;
    }

    // ---------------------------------------------------------------------------------------------
    // Hover
    // ---------------------------------------------------------------------------------------------

    private void instalarHover(Component raiz) {
        MouseAdapter ouvinte = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                mudarHover(true);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                java.awt.Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), LinhaProjetoVisual.this);
                mudarHover(contains(p));
            }
        };
        raiz.addMouseListener(ouvinte);
        if (raiz instanceof java.awt.Container k) {
            k.addContainerListener(new java.awt.event.ContainerAdapter() {
                @Override
                public void componentAdded(java.awt.event.ContainerEvent e) {
                    instalarHover(e.getChild());
                }
            });
            for (Component filho : k.getComponents()) {
                instalarHover(filho);
            }
        }
    }

    private void mudarHover(boolean sim) {
        if (sim != sobMouse) {
            sobMouse = sim;
            fundo(sim ? Tokens.SUPERFICIE2 : null);
        }
    }

    /** Só para os testes: simula o mouse em cima da linha. */
    void simularHover(boolean sim) {
        mudarHover(sim);
    }

    // ---------------------------------------------------------------------------------------------
    // Para os testes
    // ---------------------------------------------------------------------------------------------

    Botao botaoCompose() {
        return botaoCompose;
    }

    Botao botaoAbrirClaude() {
        return botaoAbrirClaude;
    }

    Botao botaoMenu() {
        return botaoMenu;
    }

    Botao botaoReabrir() {
        return reabrir;
    }

    Botao botaoRetomar() {
        return retomar;
    }

    Botao botaoUrl() {
        return url;
    }

    Botao botaoAbrirLogin() {
        return abrirLogin;
    }

    JTextField campoDoCodigo() {
        return campoDoCodigo;
    }

    Botao botaoEnviarCodigo() {
        return enviarCodigo;
    }

    Selo seloClaude() {
        return seloClaude;
    }

    Selo seloDesatualizada() {
        return seloDesatualizada;
    }

    Rotulo rotuloDoNome() {
        return nome;
    }

    Rotulo rotuloDaDescricao() {
        return descricao;
    }

    Rotulo rotuloDoEstado() {
        return estadoTexto;
    }

    Rotulo rotuloDoLimite() {
        return limiteTexto;
    }

    BarraSeveridade barraDoLimite() {
        return limiteBarra;
    }

    JComponent faixaDeLogin() {
        return faixaDeLogin;
    }

    boolean estaDeHover() {
        return sobMouse;
    }

    /** O ponto colorido do estado. */
    static final class PontoDeEstado extends JComponent {
        private static final long serialVersionUID = 1L;

        private Gravidade gravidade = Gravidade.NEUTRO;

        void definir(Gravidade g) {
            this.gravidade = g;
            repaint();
        }

        Gravidade gravidade() {
            return gravidade;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(8, 8);
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Tokens.cor(gravidade.tokenDoTexto()));
                g2.fillOval(0, (getHeight() - 8) / 2, 8, 8);
            } finally {
                g2.dispose();
            }
        }
    }
}
