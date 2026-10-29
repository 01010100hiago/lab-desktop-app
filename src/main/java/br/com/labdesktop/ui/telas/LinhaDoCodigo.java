package br.com.labdesktop.ui.telas;

import br.com.labdesktop.git.LeitorDeGit;
import br.com.labdesktop.git.ResultadoDoGit;
import br.com.labdesktop.git.ResumoDoRepositorio;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.BotaoCopiar;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.tema.Tokens;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.BoxLayout;
import javax.swing.JPanel;

/**
 * A linha "Código" da Visão geral: o que o Git do projeto diz, em palavras simples, e o botão que leva ao próximo passo. Cinco estados: <b>Sem Git</b> (botão "Criar repositório Git"),
 * <b>com Git</b> (branch, último commit, quando, alterações não salvas e "Ver commits"), <b>Git não instalado</b> (leva ao "Instalar o Git…" que já existe), <b>proprietário diferente</b> (a frase e o
 * comando do {@code safe.directory} como TEXTO para copiar: o app nunca o executa) e <b>erro</b> (a mensagem do Git, com "Tentar de novo"). Lê em segundo plano, só quando a Visão geral aparece ou
 * muda o que o agente conhece do git; nunca de tempos em tempos. Sem leitor (testes de tela sem servidor), mostra o texto do agente, como antes.
 */
final class LinhaDoCodigo extends Painel {

    private static final long serialVersionUID = 1L;

    private final transient ContextoDoProjeto ctx;
    private final Rotulo texto = new Rotulo().tamanho(13f).truncavel();
    private final Botao criar = Botoes.mini("Criar repositório Git", "mais");
    private final Botao verCommits = Botoes.mini("Ver commits", "lista");
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
        instalar.setName("instalar-git-da-linha");
        tentarDeNovo.setName("tentar-git-de-novo");
        comando.setName("comando-safe-directory");
        comando.putClientProperty("html.disable", Boolean.TRUE);
        copiar = BotaoCopiar.criar("Copiar comando", () -> comando.getText(), ctx.copiador, ctx.avisos);
        criar.addActionListener(e -> ctx.criarRepositorio.accept(ctx.recarregarGit, () -> ctx.navegar.accept(TelaProjeto.Aba.GIT)));
        verCommits.addActionListener(e -> ctx.navegar.accept(TelaProjeto.Aba.GIT));
        instalar.addActionListener(e -> ctx.instalarGit.accept(ctx.recarregarGit));
        tentarDeNovo.addActionListener(e -> atualizar());
        verCommits.setToolTipText("Abre a aba Git, com os últimos commits e o que mudou em cada um");
        tentarDeNovo.setToolTipText("Lê o Git do projeto de novo");

        JPanel botoes = Caixa.fluxo(java.awt.FlowLayout.RIGHT, 8, criar, verCommits, instalar, tentarDeNovo);
        JPanel linha1 = new Painel(new BorderLayout(12, 0));
        linha1.add(texto, BorderLayout.CENTER);
        linha1.add(botoes, BorderLayout.EAST);
        linhaDoComando.add(comando, BorderLayout.CENTER);
        linhaDoComando.add(copiar, BorderLayout.EAST);
        linhaDoComando.setVisible(false);
        JPanel coluna = new Painel();
        coluna.setLayout(new BoxLayout(coluna, BoxLayout.Y_AXIS));
        linha1.setAlignmentX(LEFT_ALIGNMENT);
        linhaDoComando.setAlignmentX(LEFT_ALIGNMENT);
        coluna.add(linha1);
        coluna.add(Caixa.espacoVertical(4));
        coluna.add(linhaDoComando);
        add(coluna, BorderLayout.CENTER);
        for (Botao b : new Botao[] {criar, verCommits, instalar, tentarDeNovo}) {
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
        instalar.setVisible(false);
        tentarDeNovo.setVisible(false);
        linhaDoComando.setVisible(false);
        if (atual == null) {
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
                texto.setText("Sem Git");
                texto.setToolTipText("Esta pasta ainda não é um repositório Git: o app pode criar o repositório, mostrando antes o que vai entrar");
                criar.setVisible(true);
                criar.setEnabled(acoes);
                criar.setToolTipText(acoes ? "Confere a pasta (só leitura) e mostra o que entraria no primeiro commit, antes de criar qualquer coisa" : TextosDeAcao.TRAVA);
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
                texto.setText("Não consegui ler o Git: " + (r.falha() == null ? "erro desconhecido." : r.falha().frase()));
                texto.setToolTipText(texto.getText());
                tentarDeNovo.setVisible(true);
            }
        }
        revalidate();
        repaint();
    }

    /** {@code Branch main · a1b2c3d Corrige o login · há 2 horas · 3 alterações ainda não salvas}. */
    private String frase(ResumoDoRepositorio r) {
        StringBuilder sb = new StringBuilder();
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
