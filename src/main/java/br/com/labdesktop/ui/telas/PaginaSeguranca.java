package br.com.labdesktop.ui.telas;

import br.com.labdesktop.servidor.ParsersDeSeguranca;
import br.com.labdesktop.servidor.ResumoDeSeguranca;
import br.com.labdesktop.servidor.ResumoDeSeguranca.EventoDeTentativa;
import br.com.labdesktop.servidor.ResumoDeSeguranca.Leitura;
import br.com.labdesktop.servidor.ResumoDeSeguranca.LoginAceito;
import br.com.labdesktop.servidor.ResumoDeSeguranca.Origem;
import br.com.labdesktop.servidor.ResumoDeSeguranca.Tentativas;
import br.com.labdesktop.servidor.ResumoDeSeguranca.TipoDeTentativa;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.Severidade;
import br.com.labdesktop.ui.componentes.Abas;
import br.com.labdesktop.ui.componentes.BarraSeveridade;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TabelaPaginada;
import br.com.labdesktop.ui.componentes.TabelaPaginada.Coluna;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Formato;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JToggleButton;

/**
 * A aba "Segurança", sempre do tamanho de uma tela: uma faixa de resumo no topo (tentativas em 24 h e em 7 dias, IPs
 * bloqueados, estado do SSH, fail2ban) e, embaixo, SUB-ABAS para o que cresce: Tentativas (tabela paginada com filtro de
 * período e de tipo), Origens (os 10 IPs que mais tentaram, com barra), Logins aceitos (tabela paginada, os de fora da rede
 * local ou por senha no topo), Firewall e portas, e SSH e bloqueio. SOMENTE LEITURA: nada aqui bloqueia IP nem muda firewall.
 *
 * <p>Só assina a leitura de segurança (SSH exec, 60 s) e só enquanto esta aba está visível.
 */
public final class PaginaSeguranca extends PaginaDoServidor {

    private static final long serialVersionUID = 1L;

    /** Linhas por página das tabelas. */
    public static final int POR_PAGINA = 15;

    /** Os ids das sub-abas. */
    public static final String SUB_TENTATIVAS = "tentativas";
    public static final String SUB_ORIGENS = "origens";
    public static final String SUB_LOGINS = "logins";
    public static final String SUB_FIREWALL = "firewall";
    public static final String SUB_SSH = "ssh";

    private final Resumo resumo = new Resumo();
    private final Abas subAbas = new Abas(true);
    private final CardLayout cartoes = new br.com.labdesktop.ui.componentes.CartoesAjustaveis();
    private final JPanel cartas = new Painel(cartoes);

    // Tentativas
    private final TabelaPaginada<EventoDeTentativa> tabelaDeTentativas = new TabelaPaginada<>(POR_PAGINA, List.of(
        new Coluna<>("Quando", 0.8, false, false, e -> FormatosDoServidor.diaEHora(e.quando(), java.time.ZoneId.systemDefault()), null),
        new Coluna<>("Tipo", 1.4, false, false, e -> rotuloDoTipo(e.tipo()), null),
        new Coluna<>("IP", 1.0, false, true, e -> e.ip(), null),
        new Coluna<>("Usuário tentado", 1.0, false, false, e -> e.usuario(), null)));
    private final ButtonGroup grupoDePeriodo = new ButtonGroup();
    private final ButtonGroup grupoDeTipo = new ButtonGroup();
    private final Map<String, JToggleButton> botoesDePeriodo = new java.util.LinkedHashMap<>();
    private final Map<String, JToggleButton> botoesDeTipo = new java.util.LinkedHashMap<>();
    private String periodo = "7d";
    private TipoDeTentativa tipoEscolhido;

    // Origens, logins, firewall, ssh
    private final JPanel origens = new Painel(new GridBagLayout());
    private List<List<String>> linhasDeOrigens = List.of();
    private final TabelaPaginada<LoginAceito> tabelaDeLogins = new TabelaPaginada<>(POR_PAGINA, List.of(
        new Coluna<>("Quem", 0.7, false, false, l -> l.usuario(), null),
        new Coluna<>("De onde", 1.3, false, true, l -> l.ip() + ":" + l.porta(), null),
        new Coluna<>("Como entrou", 1.7, false, false, PaginaSeguranca::comoEntrou, null),
        new Coluna<>("Quando", 0.8, false, false, l -> FormatosDoServidor.diaEHora(l.quando(), java.time.ZoneId.systemDefault()), null),
        new Coluna<>("Atenção", 1.5, false, false, PaginaSeguranca::atencaoDoLogin, null)));
    private final JPanel firewallEPortas = new Painel(new GridLayout(1, 2, 28, 0));
    private final JPanel sshEBloqueio = new Painel(new GridLayout(1, 2, 28, 0));

    public PaginaSeguranca(ServidorLab servidor, Runnable aoAtualizar) {
        super(servidor, aoAtualizar);
        iniciar();
    }

    @Override
    boolean usaServidor() {
        return false;
    }

    @Override
    boolean usaDetalhe() {
        return false;
    }

    @Override
    boolean usaSeguranca() {
        return true;
    }

    @Override
    JComponent montarConteudo() {
        subAbas.adicionar(SUB_TENTATIVAS, "Tentativas");
        subAbas.adicionar(SUB_ORIGENS, "Origens");
        subAbas.adicionar(SUB_LOGINS, "Logins aceitos");
        subAbas.adicionar(SUB_FIREWALL, "Firewall e portas");
        subAbas.adicionar(SUB_SSH, "SSH e bloqueio");
        subAbas.aoSelecionar(this::mostrarSubAba);

        cartas.add(caixa(painelDeTentativas()), SUB_TENTATIVAS);
        cartas.add(caixa(origens), SUB_ORIGENS);
        cartas.add(caixa(tabelaDeLogins), SUB_LOGINS);
        cartas.add(caixa(firewallEPortas), SUB_FIREWALL);
        cartas.add(caixa(sshEBloqueio), SUB_SSH);
        cartoes.show(cartas, SUB_TENTATIVAS);
        tabelaDeLogins.destacar(LoginAceito::pedeAtencao);

        JPanel embaixo = new Painel(new BorderLayout(0, 8));
        embaixo.add(subAbas, BorderLayout.NORTH);
        embaixo.add(cartas, BorderLayout.CENTER);
        JPanel conteudo = new Painel(new BorderLayout(0, 12)) {
            private static final long serialVersionUID = 1L;

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        conteudo.add(resumo, BorderLayout.NORTH);
        conteudo.add(embaixo, BorderLayout.CENTER);
        return conteudo;
    }

    private static JPanel caixa(JComponent miolo) {
        JPanel c = new Painel(new BorderLayout());
        ((Painel) c).fundo(Tokens.SUPERFICIE).borda(Tokens.BORDA).raio(14);
        c.setBorder(javax.swing.BorderFactory.createEmptyBorder(14, 16, 14, 16));
        c.add(miolo, BorderLayout.CENTER);
        return c;
    }

    /** Mostra uma sub-aba pelo id. */
    public void mostrarSubAba(String id) {
        subAbas.selecionar(id);
        cartoes.show(cartas, id);
        cartas.revalidate();
        cartas.repaint();
        refazerLayout();
    }

    // ---------------------------------------------------------------------------------------------
    // Tentativas
    // ---------------------------------------------------------------------------------------------

    private JComponent painelDeTentativas() {
        JPanel filtros = Caixa.linha(6, new Rotulo("Período").corDoToken(Tokens.TEXTO3).tamanho(12f), Caixa.espaco(2));
        for (String[] p : new String[][] {{"24h", "24 h"}, {"7d", "7 dias"}}) {
            JToggleButton b = Botoes.filtro(p[1], "Tentativas dos últimos " + p[1]);
            b.setSelected(p[0].equals(periodo));
            b.addActionListener(e -> {
                periodo = p[0];
                refiltrar();
            });
            grupoDePeriodo.add(b);
            botoesDePeriodo.put(p[0], b);
            filtros.add(b);
        }
        filtros.add(Caixa.espaco(14));
        filtros.add(new Rotulo("Tipo").corDoToken(Tokens.TEXTO3).tamanho(12f));
        filtros.add(Caixa.espaco(2));
        botaoDeTipo("todos", "Todos", null, filtros);
        for (TipoDeTentativa t : TipoDeTentativa.values()) {
            botaoDeTipo(t.name(), rotuloDoTipo(t), t, filtros);
        }
        filtros.add(Caixa.mola());
        tabelaDeTentativas.mensagemDoFiltroVazio("Nenhuma tentativa com este período e este tipo.");
        JPanel p = new Painel(new BorderLayout(0, 10));
        p.add(filtros, BorderLayout.NORTH);
        p.add(tabelaDeTentativas, BorderLayout.CENTER);
        return p;
    }

    private void botaoDeTipo(String id, String texto, TipoDeTentativa tipo, JPanel linha) {
        JToggleButton b = Botoes.filtro(texto, tipo == null ? "Todos os tipos" : tipo.descricao());
        b.setSelected(tipo == tipoEscolhido);
        b.addActionListener(e -> {
            tipoEscolhido = tipo;
            refiltrar();
        });
        grupoDeTipo.add(b);
        botoesDeTipo.put(id, b);
        linha.add(b);
    }

    static String rotuloDoTipo(TipoDeTentativa t) {
        return switch (t) {
            case ADIVINHAR_SENHA -> "Adivinhar senha";
            case USUARIO_INVENTADO -> "Usuário inventado";
            case VARREDURA -> "Varredura";
            case OUTROS -> "Outros";
        };
    }

    private Predicate<EventoDeTentativa> filtroAtual() {
        Instant referencia = seguranca == null ? Instant.now() : seguranca.lidoEm();
        Instant corte = referencia.minus(ParsersDeSeguranca.VINTE_E_QUATRO_HORAS);
        return e -> (!"24h".equals(periodo) || !e.quando().isBefore(corte)) && (tipoEscolhido == null || e.tipo() == tipoEscolhido);
    }

    /** O usuário mudou o período ou o tipo: refiltra e volta à página 1. */
    private void refiltrar() {
        tabelaDeTentativas.filtrar(filtroAtual());
    }

    /** Escolhe o período pelo código ({@code "24h"} ou {@code "7d"}), como o clique no botão. */
    public void escolherPeriodo(String codigo) {
        botoesDePeriodo.get(codigo).doClick();
    }

    /** Escolhe o tipo (ou {@code null} para todos), como o clique no botão. */
    public void escolherTipo(TipoDeTentativa tipo) {
        botoesDeTipo.get(tipo == null ? "todos" : tipo.name()).doClick();
    }

    // ---------------------------------------------------------------------------------------------
    // Atualização
    // ---------------------------------------------------------------------------------------------

    @Override
    void atualizarConteudo() {
        ResumoDeSeguranca r = seguranca;
        resumo.mostrar(r);
        Leitura<Tentativas> lt = r.tentativas();
        String semLeitura = lt.situacao() == Leitura.Situacao.SEM_PERMISSAO ? PecasDeSeguranca.SEM_PERMISSAO : PecasDeSeguranca.INDISPONIVEL;
        if (lt.lida()) {
            Tentativas t = lt.dados();
            tabelaDeTentativas.atualizar(t.eventos(), filtroAtual(), "Nenhuma tentativa de entrada nos últimos 7 dias.");
            List<String> notas = new ArrayList<>();
            if (t.maisQueOLimite()) {
                notas.add("mostrando os " + ParsersDeSeguranca.MAX_EVENTOS + " mais recentes (houve " + Formato.fmtNum(t.em7d(), 0) + " nos 7 dias)");
            }
            if (t.parcial()) {
                notas.add("o registro passou do tamanho lido: são pelo menos estes");
            }
            tabelaDeTentativas.nota(String.join(" · ", notas));
            atualizarOrigens(t);
            List<LoginAceito> ordem = new ArrayList<>(t.logins());
            ordem.sort(Comparator.comparing((LoginAceito l) -> !l.pedeAtencao()).thenComparing(Comparator.comparing(LoginAceito::quando).reversed()));
            tabelaDeLogins.atualizar(ordem, null, "Nenhum login nos últimos 7 dias.");
        } else {
            tabelaDeTentativas.atualizar(List.of(), null, semLeitura);
            tabelaDeTentativas.nota("");
            atualizarOrigensVazio(semLeitura);
            tabelaDeLogins.atualizar(List.of(), null, semLeitura);
        }
        preencher(firewallEPortas, PecasDeSeguranca.secao("Firewall", PecasDeSeguranca.firewall(r.firewall())),
            PecasDeSeguranca.secao("Portas abertas para fora da máquina", PecasDeSeguranca.portas(r.portas())));
        preencher(sshEBloqueio, PecasDeSeguranca.secao("Configuração do SSH", PecasDeSeguranca.ssh(r.ssh())),
            PecasDeSeguranca.secao("Bloqueio automático de IPs (fail2ban)", PecasDeSeguranca.fail2ban(r.fail2ban())));
    }

    private static void preencher(JPanel colunas, JComponent esquerda, JComponent direita) {
        colunas.removeAll();
        colunas.add(esquerda);
        colunas.add(direita);
        colunas.revalidate();
        colunas.repaint();
    }

    private void atualizarOrigens(Tentativas t) {
        origens.removeAll();
        List<List<String>> linhas = new ArrayList<>();
        if (t.origens().isEmpty()) {
            atualizarOrigensVazio("Nenhuma origem: não houve tentativas de entrada nos últimos 7 dias.");
            return;
        }
        int max = t.origens().stream().mapToInt(Origem::tentativas).max().orElse(1);
        int y = 0;
        String[] titulos = {"IP", "Proporção", "Tentativas", "Tipo mais usado", "Última tentativa"};
        for (int i = 0; i < titulos.length; i++) {
            Rotulo r = new Rotulo(titulos[i].toUpperCase(Locale.forLanguageTag("pt-BR"))).corDoToken(Tokens.TEXTO3).negrito().tamanho(10.5f);
            r.setHorizontalAlignment(i == 2 ? javax.swing.SwingConstants.RIGHT : javax.swing.SwingConstants.LEFT);
            origens.add(r, celula(i, y, 0, i == 0 ? 0 : 12, 6));
        }
        y++;
        for (Origem o : t.origens()) {
            Rotulo ip = new Rotulo(o.ip() + (o.redeLocal() ? " (rede local)" : "")).mono(12f).truncavel();
            BarraSeveridade barra = new BarraSeveridade(BarraSeveridade.Tamanho.PADRAO);
            barra.definir((double) o.tentativas() / max, Severidade.NORMAL, o.tentativas() + " tentativas");
            Rotulo n = new Rotulo(Formato.fmtNum(o.tentativas(), 0)).negrito().tamanho(12.5f).aDireita();
            String tipo = rotuloDoTipo(o.tipoPredominante());
            String ultima = FormatosDoServidor.desde(o.ultima(), agora(), fuso());
            origens.add(ip, celula(0, y, 0, 0, 4));
            origens.add(barra, celula(1, y, 1.0, 12, 4));
            origens.add(n, celula(2, y, 0, 12, 4));
            origens.add(new Rotulo(tipo).tamanho(12.5f).truncavel(), celula(3, y, 0.8, 12, 4));
            origens.add(new Rotulo(ultima).tamanho(12.5f).truncavel(), celula(4, y, 0.8, 12, 4));
            linhas.add(List.of(o.ip(), Formato.fmtNum(o.tentativas(), 0), tipo, ultima));
            y++;
        }
        String nota = t.totalDeOrigens() > t.origens().size()
            ? "As " + t.origens().size() + " que mais tentaram, de " + t.totalDeOrigens() + " endereços nos 7 dias. Sem geolocalização."
            : "Todos os " + t.totalDeOrigens() + " endereços dos últimos 7 dias. Sem geolocalização.";
        GridBagConstraints g = celula(0, y, 0, 0, 10);
        g.gridwidth = 5;
        origens.add(new Rotulo(nota).corDoToken(Tokens.TEXTO3).tamanho(12f), g);
        this.linhasDeOrigens = List.copyOf(linhas);
        origens.revalidate();
        origens.repaint();
    }

    private void atualizarOrigensVazio(String texto) {
        origens.removeAll();
        GridBagConstraints g = celula(0, 0, 1, 0, 0);
        origens.add(new Rotulo(texto).corDoToken(Tokens.TEXTO3).tamanho(12.5f), g);
        this.linhasDeOrigens = List.of();
        origens.revalidate();
        origens.repaint();
    }

    private static GridBagConstraints celula(int x, int y, double peso, int esquerda, int vertical) {
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = x;
        g.gridy = y;
        g.weightx = peso;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(vertical, esquerda, vertical, 0);
        return g;
    }

    static String comoEntrou(LoginAceito l) {
        if ("senha".equals(l.metodo())) {
            return "senha";
        }
        return "chave" + (l.algoritmo().isEmpty() ? "" : " " + l.algoritmo()) + (l.impressao().isEmpty() ? "" : " " + l.impressao());
    }

    static String atencaoDoLogin(LoginAceito l) {
        if (l.foraDaRedeLocal() && "senha".equals(l.metodo())) {
            return "fora da rede local, com senha";
        }
        return l.foraDaRedeLocal() ? "fora da rede local" : "senha".equals(l.metodo()) ? "entrou com senha" : "";
    }

    // ---------------------------------------------------------------------------------------------
    // A faixa de resumo
    // ---------------------------------------------------------------------------------------------

    /** Cinco números no topo da aba: tentativas em 24 h e 7 dias, IPs bloqueados, SSH e fail2ban. */
    private static final class Resumo extends Painel {
        private static final long serialVersionUID = 1L;
        private final Celula h24 = new Celula("TENTATIVAS · 24 H");
        private final Celula d7 = new Celula("TENTATIVAS · 7 DIAS");
        private final Celula bloqueados = new Celula("IPS BLOQUEADOS");
        private final Celula ssh = new Celula("SSH");
        private final Celula fail2ban = new Celula("BLOQUEIO AUTOMÁTICO");
        private final List<Celula> celulas = List.of(h24, d7, bloqueados, ssh, fail2ban);

        Resumo() {
            super(new GridLayout(1, 5));
            fundo(Tokens.SUPERFICIE).borda(Tokens.BORDA).raio(14);
            for (Celula c : celulas) {
                add(c);
            }
            setName("resumo-de-seguranca");
            getAccessibleContext().setAccessibleName("Resumo da segurança");
            mostrar(null);
        }

        void mostrar(ResumoDeSeguranca r) {
            if (r == null || !r.tentativas().lida()) {
                String motivo = r == null ? "lendo…" : r.tentativas().situacao() == Leitura.Situacao.SEM_PERMISSAO ? "sem permissão" : "sem leitura";
                h24.mostrar("—", motivo, Gravidade.NEUTRO, "info");
                d7.mostrar("—", motivo, Gravidade.NEUTRO, "info");
            } else {
                Tentativas t = r.tentativas().dados();
                String mais = t.parcial() ? "+" : "";
                if (t.em24h() == 0) {
                    h24.mostrar("0", "nenhuma tentativa", Gravidade.OK, "ok-circulo");
                } else {
                    h24.mostrar(Formato.fmtNum(t.em24h(), 0) + mais, t.em24h() == 1 ? "tentativa de entrada" : "tentativas de entrada",
                        Gravidade.ATENCAO, "alerta");
                }
                d7.mostrar(Formato.fmtNum(t.em7d(), 0) + mais, t.totalDeOrigens() == 0 ? "nenhuma origem"
                    : "de " + t.totalDeOrigens() + (t.totalDeOrigens() == 1 ? " endereço" : " endereços"),
                    t.em7d() == 0 ? Gravidade.OK : Gravidade.NEUTRO, t.em7d() == 0 ? "ok-circulo" : "info");
            }
            if (r != null && r.fail2ban().lida()) {
                var f = r.fail2ban().dados();
                bloqueados.mostrar(f.ativo() && f.banidosAgora() != null ? String.valueOf(f.banidosAgora()) : "—",
                    f.ativo() && f.totalBanidos() != null ? Formato.fmtNum(f.totalBanidos(), 0) + " desde o início" : "agora",
                    Gravidade.NEUTRO, "info");
                fail2ban.mostrar(f.ativo() ? "Ativo" : "Inativo", f.ativo() ? "bloqueia IPs sozinho" : "sem bloqueio automático",
                    f.ativo() ? Gravidade.OK : Gravidade.ATENCAO, f.ativo() ? "ok-circulo" : "alerta");
            } else {
                bloqueados.mostrar("—", "sem leitura", Gravidade.NEUTRO, "info");
                fail2ban.mostrar("—", r == null ? "lendo…" : "sem leitura", Gravidade.NEUTRO, "info");
            }
            if (r != null && r.ssh().lida()) {
                var s = r.ssh().dados();
                boolean senha = Boolean.TRUE.equals(s.senhaLigada());
                String root = s.rootDesligado() ? "root desligado" : s.loginDoRoot() == null ? "root: ?" : "root permitido";
                ssh.mostrar(senha ? "Aceita senha" : s.soChave() ? "Só chave" : "—", root,
                    senha || (!s.rootDesligado() && s.loginDoRoot() != null) ? (senha ? Gravidade.PERIGO : Gravidade.ATENCAO) : Gravidade.OK,
                    senha || (!s.rootDesligado() && s.loginDoRoot() != null) ? "alerta" : "ok-circulo");
            } else {
                ssh.mostrar("—", r == null ? "lendo…" : "sem leitura", Gravidade.NEUTRO, "info");
            }
        }

        List<Celula> celulas() {
            return celulas;
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /** Um número da faixa: nome pequeno, valor grande e uma linha com ícone e texto. */
    private static final class Celula extends Painel {
        private static final long serialVersionUID = 1L;
        private final Rotulo nome;
        private final Rotulo valor = new Rotulo().negrito().truncavel();
        private final Rotulo nota = new Rotulo().tamanho(11.5f).truncavel();

        Celula(String titulo) {
            super();
            setLayout(new javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS));
            setBorder(javax.swing.BorderFactory.createEmptyBorder(10, 14, 10, 12));
            nome = new Rotulo(titulo).corDoToken(Tokens.TEXTO3).negrito().tamanho(10.5f).truncavel();
            valor.mono(17f).negrito();
            for (JComponent c : new JComponent[] {nome, valor, nota}) {
                c.setAlignmentX(LEFT_ALIGNMENT);
                add(c);
            }
        }

        void mostrar(String texto, String legenda, Gravidade g, String icone) {
            valor.setText(texto);
            nota.setText(legenda);
            nota.corDoToken(g == Gravidade.NEUTRO ? Tokens.TEXTO2 : g.tokenDoTexto());
            nota.setIcon(Icones.colorido(icone, 13, g == Gravidade.NEUTRO ? Tokens.TEXTO3 : g.tokenDoTexto()));
            nota.setIconTextGap(5);
            nota.setToolTipText(legenda);
            getAccessibleContext().setAccessibleName(nome.getText() + ": " + texto + ", " + legenda);
        }

        String textoCompleto() {
            return nome.getText() + " " + valor.getText() + " " + nota.getText();
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, getPreferredSize().height);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Para os testes
    // ---------------------------------------------------------------------------------------------

    public Abas subAbas() {
        return subAbas;
    }

    public String subAbaAtual() {
        return subAbas.selecionada();
    }

    TabelaPaginada<EventoDeTentativa> tabelaDeTentativas() {
        return tabelaDeTentativas;
    }

    TabelaPaginada<LoginAceito> tabelaDeLogins() {
        return tabelaDeLogins;
    }

    List<List<String>> linhasDeOrigens() {
        return linhasDeOrigens;
    }

    /** O texto da faixa de resumo, uma célula por linha. */
    List<String> textosDoResumo() {
        return resumo.celulas().stream().map(Celula::textoCompleto).toList();
    }

    String textoDoPainel(String subAba) {
        return Textos.de(switch (subAba) {
            case SUB_ORIGENS -> origens;
            case SUB_FIREWALL -> firewallEPortas;
            case SUB_SSH -> sshEBloqueio;
            case SUB_LOGINS -> tabelaDeLogins;
            default -> tabelaDeTentativas;
        });
    }

    /** Quantos tipos de tentativa o filtro conhece (o mapa dos botões, para os testes). */
    int quantidadeDeBotoesDeTipo() {
        return botoesDeTipo.size();
    }
}
