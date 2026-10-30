package br.com.labdesktop.ui.telas;

import br.com.labdesktop.git.LeitorDeGit;
import br.com.labdesktop.git.RepositorioAbaixo;
import br.com.labdesktop.git.ResultadoDoGit;
import br.com.labdesktop.git.ResumoDoRepositorio;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.BotaoCopiar;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.BoxLayout;
import javax.swing.JPanel;

/**
 * A linha "Código" da Visão geral: o que o Git do projeto diz, em palavras simples, e o botão que leva ao próximo passo. Seis estados: <b>Sem Git</b> (a frase diz que é a pasta NO SERVIDOR que não tem
 * Git, e o botão "Criar repositório Git"), <b>repositório em subpasta</b> (a pasta principal não tem {@code .git}, mas {@code app/} tem: a linha diz onde, leva à aba Git para escolher e NÃO oferece criar),
 * <b>com Git</b> (branch, último commit, quando, alterações não salvas e "Ver commits"; se o Git lido é o de uma subpasta, a frase começa por "Repositório app/"), <b>Git não instalado</b> (leva ao "Instalar o Git…" que já existe), <b>dono diferente</b> (a frase e o
 * comando do {@code safe.directory} como TEXTO para copiar: o app nunca o executa) e <b>erro</b> (a mensagem do Git, com "Tentar de novo"). Lê em segundo plano, só quando a Visão geral aparece ou
 * muda o que o agente conhece do git; nunca de tempos em tempos. Sem leitor (testes de tela sem servidor), mostra o texto do agente, como antes.
 */
final class LinhaDoCodigo extends Painel {

    private static final long serialVersionUID = 1L;

    private final transient ContextoDoProjeto ctx;
    private final TextoCorrido texto = new TextoCorrido("", 640).tamanho(13f);
    private final Botao criar = Botoes.mini("Criar repositório Git", "mais");
    private final Botao verCommits = Botoes.mini("Ver commits", "lista");
    private final Botao verRepositorios = Botoes.mini("Ver repositórios", "lista");
    private final Botao instalar = Botoes.mini("Instalar o Git…", "baixar");
    private final Botao tentarDeNovo = Botoes.mini("Tentar de novo", "atualizar");
    private final Rotulo comando = new Rotulo().mono(12f).corDoToken(Tokens.TEXTO).truncavel();
    private final JPanel linhaDoComando = new Painel(new BorderLayout(8, 0));
    private final Botao copiar;
    private final AtomicInteger geracao = new AtomicInteger();
    private ResumoDoRepositorio atual;
    private String textoDoAgente = "";
    private boolean lendo;

    LinhaDoCodigo(ContextoDoProjeto ctx) {
        super(new BorderLayout());
        this.ctx = ctx;
        setName("linha-do-codigo");
        criar.setName("criar-repositorio-git");
        verCommits.setName("ver-commits");
        verRepositorios.setName("ver-repositorios");
        instalar.setName("instalar-git-da-linha");
        tentarDeNovo.setName("tentar-git-de-novo");
        comando.setName("comando-safe-directory");
        comando.putClientProperty("html.disable", Boolean.TRUE);
        copiar = BotaoCopiar.criar("Copiar comando", () -> comando.getText(), ctx.copiador, ctx.avisos);
        criar.addActionListener(e -> ctx.criarRepositorio.accept(ctx.recarregarGit, () -> ctx.navegar.accept(TelaProjeto.Aba.GIT)));
        verCommits.addActionListener(e -> ctx.navegar.accept(TelaProjeto.Aba.GIT));
        verRepositorios.addActionListener(e -> ctx.navegar.accept(TelaProjeto.Aba.GIT));
        verRepositorios.setToolTipText("Abre a aba Git, onde você escolhe o repositório que o app lê");
        instalar.addActionListener(e -> ctx.instalarGit.accept(ctx.recarregarGit));
        tentarDeNovo.addActionListener(e -> {
            LeitorDeGit leitor = ctx.leitorDeGit.get();
            if (leitor != null) {
                leitor.esquecerABusca(); // "Tentar de novo" procura as subpastas de verdade, sem o cache de 60 s
            }
            atualizar();
        });
        verCommits.setToolTipText("Abre a aba Git, com os últimos commits e o que mudou em cada um");
        tentarDeNovo.setToolTipText("Lê o Git do projeto de novo");

        JPanel botoes = Caixa.fluxo(java.awt.FlowLayout.RIGHT, 8, criar, verCommits, verRepositorios, instalar, tentarDeNovo);
        JPanel linha1 = new Painel(new BorderLayout(12, 0));
        // o texto quebra em duas linhas quando é comprido (a frase de "sem Git" e a dos repositórios em subpasta precisam aparecer INTEIRAS), e fica centrado na altura dos botões
        JPanel centroDoTexto = new Painel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints gc = new java.awt.GridBagConstraints();
        gc.weightx = 1;
        gc.fill = java.awt.GridBagConstraints.HORIZONTAL;
        centroDoTexto.add(texto, gc);
        linha1.add(centroDoTexto, BorderLayout.CENTER);
        linha1.add(botoes, BorderLayout.EAST);
        linhaDoComando.add(comando, BorderLayout.CENTER);
        linhaDoComando.add(copiar, BorderLayout.EAST);
        linhaDoComando.setVisible(false);
        linhaDoComando.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 0, 0, 0));
        JPanel coluna = new Painel();
        coluna.setLayout(new BoxLayout(coluna, BoxLayout.Y_AXIS));
        linha1.setAlignmentX(LEFT_ALIGNMENT);
        linhaDoComando.setAlignmentX(LEFT_ALIGNMENT);
        coluna.add(linha1);
        coluna.add(linhaDoComando);
        add(coluna, BorderLayout.CENTER);
        for (Botao b : new Botao[] {criar, verCommits, verRepositorios, instalar, tentarDeNovo}) {
            b.setVisible(false);
        }
        redesenhar();
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /** O que o agente sabe do git (a frase que vinha só do agente), mostrada até a leitura própria chegar e quando não há leitor. */
    void textoDoAgente(String t) {
        this.textoDoAgente = t == null ? "" : t;
        if (atual == null) {
            redesenhar();
        }
    }

    /** Lê o git do projeto agora, em segundo plano (a Visão geral apareceu, o git mudou ou o usuário pediu). */
    void atualizar() {
        LeitorDeGit leitor = ctx.leitorDeGit.get();
        if (leitor == null) {
            return;
        }
        int minha = geracao.incrementAndGet();
        lendo = true;
        redesenhar();
        Tarefas.rodar(leitor::resumo, (ResultadoDoGit<ResumoDoRepositorio> r) -> {
            if (minha != geracao.get()) {
                return;
            }
            lendo = false;
            atual = r.ok() ? r.valorOuNulo() : ResumoDoRepositorio.de(ResumoDoRepositorio.Estado.ERRO, r.falhaOuNula());
            redesenhar();
        }, falha -> {
            if (minha != geracao.get()) {
                return;
            }
            lendo = false;
            atual = ResumoDoRepositorio.de(ResumoDoRepositorio.Estado.ERRO,
                new br.com.labdesktop.git.FalhaDoGit(br.com.labdesktop.git.FalhaDoGit.Tipo.OUTRO, br.com.labdesktop.ui.MensagensDeErro.de(falha)));
            redesenhar();
        });
    }

    /** A Visão geral saiu da tela: o que estava em voo é descartado. */
    void soltar() {
        geracao.incrementAndGet();
        lendo = false;
    }

    /** Põe a linha num resumo (o que a leitura faria); público no pacote para os testes alimentarem a linha sem servidor. */
    void mostrar(ResumoDoRepositorio r) {
        geracao.incrementAndGet();
        lendo = false;
        atual = r;
        redesenhar();
    }

    ResumoDoRepositorio resumoAtual() {
        return atual;
    }

    // ---------------------------------------------------------------------------------------------

    private void redesenhar() {
        boolean acoes = ctx.acoesLiberadas();
        criar.setVisible(false);
        verCommits.setVisible(false);
        verRepositorios.setVisible(false);
        instalar.setVisible(false);
        tentarDeNovo.setVisible(false);
        linhaDoComando.setVisible(false);
        if (atual == null || lendo && atual.estado() == ResumoDoRepositorio.Estado.ERRO) {
            texto.setText(lendo ? "Lendo o Git do projeto…" : textoDoAgente);
            texto.setToolTipText(null);
            revalidate();
            repaint();
            return;
        }
        ResumoDoRepositorio r = atual;
        switch (r.estado()) {
            case COM_GIT -> {
                texto.setText(frase(r));
                texto.setToolTipText(frase(r));
                verCommits.setVisible(true);
            }
            case SEM_GIT -> {
                boolean local = ctx.servidor != null && ctx.servidor.ehLocal();
                texto.setText(local ? SEM_GIT_AQUI : SEM_GIT_NO_SERVIDOR);
                texto.setToolTipText((local ? SEM_GIT_AQUI : SEM_GIT_NO_SERVIDOR) + " O app pode criar um repositório novo nesta pasta, mostrando antes o que vai entrar.");
                criar.setVisible(true);
                criar.setEnabled(acoes);
                criar.setToolTipText(acoes ? "Confere a pasta (só leitura) e mostra o que entraria no primeiro commit, antes de criar qualquer coisa" : TextosDeAcao.TRAVA);
            }
            case REPOSITORIOS_ABAIXO -> {
                texto.setText(fraseDosRepositoriosAbaixo(r));
                texto.setToolTipText(fraseDosRepositoriosAbaixo(r) + " Criar um repositório na pasta principal esconderia o histórico dele: a aba Git abre o repositório que já existe.");
                verRepositorios.setVisible(true);
            }
            case GIT_NAO_INSTALADO -> {
                boolean protegido = ctx.estado != null && ctx.estado.protegido();
                texto.setText(protegido ? "Git não instalado (servidor protegido: o app não instala nada aqui)" : "Git não instalado");
                texto.setToolTipText(texto.getText());
                instalar.setVisible(true);
                instalar.setEnabled(!protegido);
                instalar.setToolTipText(protegido ? "O servidor é protegido: o app não instala nada nele. Instale o Git você mesmo ou abra Editar servidor."
                    : "Abre a confirmação de instalar o Git, com os comandos exatos (nada roda antes do clique)");
            }
            case PROPRIEDADE_DUVIDOSA -> {
                texto.setText("O Git recusou a pasta porque ela é de outro usuário.");
                texto.setToolTipText(r.falha().frase());
                if (r.falha().comandoParaCopiar() != null) {
                    comando.setText(r.falha().comandoParaCopiar());
                    comando.setToolTipText("Rode no Terminal do servidor, com o usuário que o app usa. O app não executa este comando.");
                    linhaDoComando.setVisible(true);
                }
                tentarDeNovo.setVisible(true);
            }
            case DENTRO_DE_OUTRO -> {
                texto.setText("Sem Git próprio: a pasta está dentro de um repositório de uma pasta de cima.");
                texto.setToolTipText("O app não cria um repositório dentro de outro. O Git que vale aqui é o da pasta de cima.");
            }
            case ERRO -> {
                texto.setText((r.emSubpasta() ? "Repositório " + r.subpasta() + "/ · " : "") + "Não consegui ler o Git: " + (r.falha() == null ? "erro desconhecido." : r.falha().frase()));
                texto.setToolTipText(texto.getText());
                tentarDeNovo.setVisible(true);
            }
        }
        revalidate();
        repaint();
    }

    /** O texto de "sem Git" num servidor: a pasta DO SERVIDOR não tem Git; o que está no computador do usuário ou no GitHub não aparece aqui até ser clonado ou enviado. */
    static final String SEM_GIT_NO_SERVIDOR = "Esta pasta, no servidor, não tem Git. Se o repositório está no seu computador ou no GitHub, ele só aparece aqui depois de ser clonado ou enviado.";
    static final String SEM_GIT_AQUI = "Esta pasta, neste computador, não tem Git. Se o repositório está em outro lugar ou no GitHub, ele só aparece aqui depois de ser clonado ou copiado para cá.";

    /** {@code Este projeto não tem Git na pasta principal, mas achei repositório em: app/ (e outros)}. */
    static String fraseDosRepositoriosAbaixo(ResumoDoRepositorio r) {
        List<RepositorioAbaixo> l = r.abaixo();
        if (l.isEmpty()) {
            return "Este projeto não tem Git na pasta principal, mas achei repositório em uma subpasta.";
        }
        String primeiro = l.get(0).comBarra();
        int mais = l.size() - 1 + (r.abaixoCortado() ? 1 : 0);
        mais += r.ilegiveis();
        String outros = mais == 0 ? "" : r.abaixoCortado() ? " (e outros)" : mais == 1 ? " (e outro)" : " (e " + mais + " outros)";
        return "Este projeto não tem Git na pasta principal, mas achei repositório em: " + primeiro + outros + ".";
    }

    /** {@code Branch main · a1b2c3d Corrige o login · há 2 horas · 3 alterações ainda não salvas}; de uma subpasta, começa por {@code Repositório app/ · }. */
    private String frase(ResumoDoRepositorio r) {
        StringBuilder sb = new StringBuilder();
        if (r.emSubpasta()) {
            sb.append("Repositório ").append(r.subpasta()).append("/ · ");
        }
        sb.append(r.cabecaSolta() ? "Commit solto (sem branch)" : "Branch " + (r.branch() == null ? "sem nome" : r.branch()));
        if (r.ultimo() == null) {
            sb.append(" · ainda sem commits");
        } else {
            sb.append(" · ").append(r.ultimo().curto()).append(' ').append(r.ultimo().assunto().isEmpty() ? "(sem mensagem)" : r.ultimo().assunto())
                .append(" · ").append(FormatosDoProjeto.haQuanto(r.ultimo().quando(), ctx.agora()));
        }
        sb.append(" · ").append(r.alteracoes() == 0 ? "tudo salvo" : r.alteracoes() == 1 ? "1 alteração ainda não salva" : r.alteracoes() + " alterações ainda não salvas");
        return sb.toString();
    }

    // ---- para os testes ----

    String textoMostrado() {
        return texto.getText();
    }

    Botao botaoCriar() {
        return criar;
    }

    Botao botaoVerCommits() {
        return verCommits;
    }

    Botao botaoVerRepositorios() {
        return verRepositorios;
    }

    Botao botaoInstalar() {
        return instalar;
    }

    Botao botaoTentarDeNovo() {
        return tentarDeNovo;
    }

    String comandoMostrado() {
        return linhaDoComando.isVisible() ? comando.getText() : "";
    }

    boolean lendo() {
        return lendo;
    }
}
