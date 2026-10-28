package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.modelo.DetalheServidor;
import br.com.labdesktop.api.modelo.InfoServidor;
import br.com.labdesktop.api.modelo.InfoServidor.Processo;
import br.com.labdesktop.processos.Acao;
import br.com.labdesktop.processos.AvaliadorDeProcessos;
import br.com.labdesktop.processos.LeitorDeProcessosLocais;
import br.com.labdesktop.processos.Oferta;
import br.com.labdesktop.processos.ProcessoAlvo;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TabelaPaginada;
import br.com.labdesktop.ui.componentes.TabelaPaginada.Coluna;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;

/**
 * "Processos que mais gastam": os 20 programas que mais usam o processador, com o dono (usuário), CPU, memória e o
 * projeto ou container a que pertencem. O projeto vem do detalhe do servidor quando ele já chegou; antes disso, deduz-se
 * do nome do container da sessão ({@code lab-claude-<projeto>}) e, se não der, mostra só o container. CPU em branco
 * (traço) é normal na primeira varredura do agente.
 *
 * <p>Cada linha leva os botões <b>Encerrar</b> e, só para container, <b>Reiniciar</b>. Protegido (PID 1, sshd, o agente do painel, o Docker, o próprio app, o sistema)
 * aparece como "Protegido", desligado, com o motivo na dica; de outro usuário aparece "Sem permissão", que abre o comando para o usuário rodar. Sem as ações de escrita ligadas, os botões
 * aparecem DESLIGADOS com a explicação (em vez de sumir). Serve à aba Processos do servidor (a lista inteira) e à do projeto (só os do projeto).
 */
final class BlocoDeProcessos extends CaixaDoServidor {

    private static final long serialVersionUID = 1L;
    private static final String PREFIXO_DA_SESSAO = "lab-claude-";

    /** Uma linha, com os números crus (a ordenação usa eles, não o texto). */
    record Linha(String nome, String usuario, Double cpu, Long mem, String origem, ProcessoAlvo alvo) { }

    private static final Comparator<Double> CPU = Comparator.nullsFirst(Comparator.<Double>naturalOrder());
    private static final Comparator<Long> MEM = Comparator.nullsFirst(Comparator.<Long>naturalOrder());

    private final TabelaPaginada<Linha> tabela = new TabelaPaginada<>(20, List.of(
        new Coluna<>("Programa", 1.0, false, true, l -> l.nome(), null),
        new Coluna<>("Usuário", 0.7, false, false, l -> l.usuario(), null),
        new Coluna<>("CPU", 0.4, true, false, l -> FormatosDoServidor.percentual(l.cpu()), Comparator.comparing(Linha::cpu, CPU)),
        new Coluna<>("Memória", 0.5, true, false, l -> FormatosDoServidor.bytes(l.mem()), Comparator.comparing(Linha::mem, MEM)),
        new Coluna<>("Projeto / container", 1.6, false, false, l -> l.origem(), null)));

    private final AcoesDeProcessos acoes;
    private final String projeto;
    private final TextoCorrido nota = new TextoCorrido("", 900).corDoToken(Tokens.TEXTO3).tamanho(12f);
    private final Rotulo dica;
    private final String semDados;
    private final List<Botao> botoes = new ArrayList<>();
    private Runnable aoMudar = () -> { };

    BlocoDeProcessos() {
        this(null, null);
    }

    /**
     * @param acoes   o que os botões fazem ({@code null}: a lista sem botões, só leitura)
     * @param projeto só os processos deste projeto ({@code null}: todos)
     */
    BlocoDeProcessos(AcoesDeProcessos acoes, String projeto) {
        super(projeto == null ? "Processos que mais gastam" : "Processos do projeto", "processo");
        this.acoes = acoes;
        this.projeto = projeto;
        this.dica = Rotulo.dica(projeto == null ? "Os 20 que mais usam o processador. Clique em CPU ou Memória para ordenar."
            : "Só aparecem os do projeto que estão entre os 20 que mais usam o processador do servidor.");
        this.semDados = projeto == null ? "Sem dados de processos ainda." : "Nenhum processo deste projeto entre os 20 que mais gastam agora.";
        apoio(dica);
        linha(tabela, 0);
        if (acoes != null) {
            tabela.acaoPorLinha("Ação", this::celulaDeAcao);
            linha(nota, 8);
        }
        atualizarNota();
        atualizar(null, null);
    }

    /** O que roda quando algo termina na confirmação (a página relê a lista). */
    void aoMudar(Runnable r) {
        this.aoMudar = r == null ? () -> { } : r;
    }

    void atualizar(InfoServidor info, DetalheServidor detalhe) {
        Map<Integer, String> projetoPorPid = new HashMap<>();
        if (detalhe != null) {
            for (Processo p : detalhe.processos()) {
                if (p.pid() != null) {
                    projetoPorPid.put(p.pid(), p.projeto() == null ? "Sem dono" : p.projeto());
                }
            }
        }
        List<Linha> linhas = new ArrayList<>();
        for (Processo p : info == null ? List.<Processo>of() : info.processos()) {
            String projetoDoDetalhe = projetoPorPid.get(p.pid());
            String deQuem = projetoDe(p, projetoDoDetalhe);
            if (projeto != null && !projeto.equals(deQuem)) {
                continue;
            }
            String nome = p.nome() == null ? FormatosDoServidor.SEM_DADO : p.nome();
            String usuario = p.usuario() == null ? FormatosDoServidor.SEM_DADO : p.usuario();
            linhas.add(new Linha(nome, usuario, p.cpu(), p.mem(), origem(p, projetoDoDetalhe), new ProcessoAlvo(p.pid(), p.nome(), p.usuario(), p.container(), deQuem)));
        }
        tabela.definir(linhas, semDados);
        terminar();
    }

    /** Este computador: a lista vem do próprio sistema (o número é o deste computador). */
    void atualizarLocal(List<LeitorDeProcessosLocais.Linha> lidas) {
        List<Linha> linhas = new ArrayList<>();
        for (LeitorDeProcessosLocais.Linha l : lidas) {
            ProcessoAlvo a = l.alvo();
            linhas.add(new Linha(a.nome().isBlank() ? FormatosDoServidor.SEM_DADO : a.nome(), a.usuario().isBlank() ? FormatosDoServidor.SEM_DADO : a.usuario(), l.cpu(), l.mem(),
                "este computador", a));
        }
        tabela.definir(linhas, "Sem dados de processos ainda.");
        terminar();
    }

    /** Os rótulos de "Este computador": o aviso muda (não são os 20 do agente) e a coluna de memória não existe. */
    void modoLocal() {
        trocarTitulo("Processos deste computador");
        dica.setText("Os mais ocupados primeiro, lidos do próprio sistema (até " + LeitorDeProcessosLocais.MAXIMO + "). Clique em CPU ou Memória para ordenar.");
    }

    /** O projeto de um processo: o do detalhe, o do agente ou o deduzido do nome do container da sessão; {@code null} se é do servidor. */
    static String projetoDe(Processo p, String projetoDoDetalhe) {
        String container = p.container();
        String projeto = projetoDoDetalhe != null ? projetoDoDetalhe : p.projeto();
        if (projeto == null && container != null && container.startsWith(PREFIXO_DA_SESSAO)) {
            projeto = container.substring(PREFIXO_DA_SESSAO.length());
        }
        if ("(servidor)".equals(projeto) || "(lab)".equals(projeto) || "Sem dono".equals(projeto)) {
            return null;
        }
        return projeto;
    }

    /** O texto da coluna "Projeto / container". */
    static String origem(Processo p, String projetoDoDetalhe) {
        String container = p.container();
        String projeto = projetoDoDetalhe != null ? projetoDoDetalhe : p.projeto();
        if (projeto == null && container != null && container.startsWith(PREFIXO_DA_SESSAO)) {
            projeto = container.substring(PREFIXO_DA_SESSAO.length());
        }
        if ("(servidor)".equals(projeto)) {
            return "o próprio servidor (fora de container)";
        }
        if ("(lab)".equals(projeto)) {
            projeto = "infraestrutura do lab";
        }
        if (container == null) {
            return projeto == null ? "o próprio servidor (fora de container)" : projeto;
        }
        return projeto == null ? container : projeto + " (" + container + ")";
    }

    // =====================================================================================================
    // Os botões de cada linha
    // =====================================================================================================

    private JComponent celulaDeAcao(Linha l) {
        Oferta o = acoes.avaliar(l.alvo());
        List<JComponent> pecas = new ArrayList<>();
        if (o.protegido()) {
            Botao b = Botoes.miniBaixo("Protegido", null);
            b.setEnabled(false);
            b.setToolTipText(o.protecao());
            b.getAccessibleContext().setAccessibleName("Protegido: " + o.protecao());
            b.setName("protegido");
            pecas.add(b);
        } else if (o.situacao() == Oferta.Situacao.SEM_PERMISSAO) {
            Botao b = Botoes.miniBaixo("Sem permissão", "info");
            b.setToolTipText("Este processo é de outro usuário. Clique para ver o comando que você mesmo roda no Terminal.");
            b.getAccessibleContext().setAccessibleName("Sem permissão para encerrar " + l.nome() + ": ver o comando");
            b.setName("sem-permissao");
            b.addActionListener(e -> acoes.abrir(l.alvo(), l.origem(), Acao.ENCERRAR, aoMudar));
            botoes.add(b); // só mostra o comando que o usuário roda: a trava das ações de escrita não o desliga
            pecas.add(b);
        } else {
            boolean container = o.podeReiniciar();
            Botao e = Botoes.miniBaixo(container ? "Parar" : "Encerrar", "parar");
            e.setToolTipText(container ? "Para o container " + l.alvo().container() + " (docker stop)" : "Pede ao programa para fechar; você confirma antes");
            e.getAccessibleContext().setAccessibleName((container ? "Parar o container de " : "Encerrar ") + l.nome());
            e.setName("encerrar");
            e.addActionListener(ev -> acoes.abrir(l.alvo(), l.origem(), Acao.ENCERRAR, aoMudar));
            ligarSeLiberadas(e);
            pecas.add(e);
            if (container) {
                Botao r = Botoes.miniBaixo("Reiniciar", "atualizar");
                r.setToolTipText("Reinicia o container " + l.alvo().container() + " (docker restart)");
                r.getAccessibleContext().setAccessibleName("Reiniciar o container de " + l.nome());
                r.setName("reiniciar");
                r.addActionListener(ev -> acoes.abrir(l.alvo(), l.origem(), Acao.REINICIAR, aoMudar));
                ligarSeLiberadas(r);
                pecas.add(r);
            }
        }
        JComponent linha = Caixa.linha(6, pecas.toArray(JComponent[]::new));
        return linha;
    }

    /** Sem as ações de escrita ligadas o botão aparece desligado, com a explicação, e não some. */
    private void ligarSeLiberadas(Botao b) {
        if (!acoes.liberadas()) {
            b.setEnabled(false);
            b.setToolTipText(TextosDeAcao.TRAVA);
        }
        botoes.add(b);
    }

    private void atualizarNota() {
        if (acoes == null) {
            return;
        }
        nota.setText((acoes.liberadas() ? "" : TextosDeAcao.TRAVA_COMPLETA + "\n")
            + "Encerrar pede ao programa para fechar e nunca força sozinho: se ele não fechar, você decide se quer forçar. "
            + "Reiniciar só aparece para containers e serviços; de um processo comum o app não sabe como religá-lo.");
    }

    /** As ações foram ligadas ou desligadas (os testes e a página refazem os botões). */
    void refazerBotoes() {
        botoes.clear();
        atualizarNota();
        tabela.redesenhar();
    }

    // =====================================================================================================
    // Para os testes
    // =====================================================================================================

    /** O texto das linhas da tela, na ordem. */
    List<List<String>> linhas() {
        return tabela.linhasDaPagina();
    }

    TabelaPaginada<Linha> tabela() {
        return tabela;
    }

    /** Os botões criados no último desenho (Encerrar, Reiniciar, Protegido, Sem permissão), na ordem das linhas. */
    List<Botao> botoes() {
        List<Botao> todos = new ArrayList<>();
        coletar(this, todos);
        return todos;
    }

    private static void coletar(java.awt.Component c, List<Botao> l) {
        if (c instanceof Botao b && b.getName() != null && List.of("encerrar", "reiniciar", "protegido", "sem-permissao").contains(b.getName())) {
            l.add(b);
        }
        if (c instanceof java.awt.Container k) {
            for (java.awt.Component f : k.getComponents()) {
                coletar(f, l);
            }
        }
    }

    String textoDaNota() {
        return acoes == null ? "" : nota.getText();
    }

    String semDadosEsperado() {
        return semDados;
    }

    static String semReiniciar() {
        return AvaliadorDeProcessos.SEM_REINICIAR;
    }
}
