package br.com.labdesktop.ui.dialogos;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.git.ConferenciaDoRepositorio;
import br.com.labdesktop.git.ConferenciaDoRepositorio.Situacao;
import br.com.labdesktop.git.CriadorDeRepositorio;
import br.com.labdesktop.git.OperacoesDeGit;
import br.com.labdesktop.git.PedidoDeCriacao;
import br.com.labdesktop.git.PlanejadorDoGitignore;
import br.com.labdesktop.git.ResultadoDaCriacao;
import br.com.labdesktop.git.ValidadorDeGit;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.componentes.AreaMonoespacada;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.BotaoCopiar;
import br.com.labdesktop.ui.componentes.Botoes;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Campos;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TextoCorrido;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Formato;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;

/**
 * "Criar repositório Git" num projeto que ainda não tem um. Dois tempos, como "Adicionar container": primeiro a CONFERÊNCIA, que só LÊ o projeto e mostra tudo ANTES de qualquer escrita (o Git existe e é
 * novo o bastante, quem assina os commits, quais arquivos entram, o que fica de fora e por quê, as linhas que o app acrescentaria ao {@code .gitignore}, os segredos que nunca entram, os
 * arquivos grandes e os repositórios que já estão dentro do projeto); depois, SÓ no clique de "Criar repositório em &lt;projeto&gt; (&lt;servidor&gt;)", a criação, com cada passo à vista. Se algo
 * falha no meio, a janela diz o que foi feito e o que não foi, e mostra o comando para desfazer: o app NUNCA apaga o {@code .git} sozinho. Sem push, sem remoto, sem {@code --force}.
 *
 * <p>A criação escreve no projeto e por isso fica atrás da trava das ações ({@code labdesktop.acoes}): com ela desligada o botão aparece desabilitado com a razão. A conferência é só leitura.
 * Nada de rede, disco ou processo na EDT.
 */
public final class DialogoCriarRepositorio extends Dialogo {

    /** O que o diálogo precisa do domínio. Tudo BLOQUEIA: só roda fora da EDT. */
    public interface Motor {
        /** Só lê o projeto (e usa uma área temporária fora dele). */
        ConferenciaDoRepositorio conferir() throws Exception;

        /** Escreve no projeto: só depois do clique. */
        ResultadoDaCriacao criar(PedidoDeCriacao pedido, OperacoesDeGit.Observador observador, Cancelamento cancelamento) throws Exception;
    }

    private static final int LARGURA = 560;
    /** A razão do botão desligado pela trava das ações (a mesma frase fixa dos outros botões). */
    public static final String RAZAO_DA_TRAVA = "As ações de escrita estão desligadas nesta versão do app.";

    private final String servidor;
    private final String projeto;
    private final Motor motor;
    private final boolean liberada;
    private final boolean servidorProtegido;
    private final Runnable instalarGit;
    private final Runnable verCommits;
    private final Runnable aoTerminar;
    private final Notificacoes avisos;
    private final CopiadorDeTexto copiador;

    private final JPanel area = new Painel(new BorderLayout());
    private final Botao criar;
    private final Botao conferirDeNovo = Botoes.secundario("Conferir de novo", "atualizar");
    private final Botao instalar = Botoes.secundario("Instalar o Git…", "baixar");
    private final Botao verOsCommits = Botoes.primario("Ver commits", "lista");
    private final Botao cancelar = Botoes.secundario("Cancelar", null);
    private final TextoCorrido razaoDaTrava = new TextoCorrido(RAZAO_DA_TRAVA, LARGURA).corDoToken(Tokens.TEXTO3).tamanho(12f);
    private final JTextField campoNome = Campos.texto(28, "Seu nome");
    private final JTextField campoEmail = Campos.texto(28, "voce@example.com");
    private final TextoCorrido erroDaIdentidade = erroCorrido();
    private final JCheckBox incluirGrandes = new JCheckBox("Incluir os arquivos grandes no primeiro commit");
    private final TextoCorrido progresso = new TextoCorrido("", LARGURA).corDoToken(Tokens.TEXTO2).tamanho(13f);
    private final AtomicInteger geracao = new AtomicInteger();
    private final Cancelamento cancelamento = new Cancelamento();
    private Botao copiarComando;
    private ConferenciaDoRepositorio conferencia;
    private ResultadoDaCriacao resultado;
    private Fase fase = Fase.LENDO;
    private boolean ocupado;

    private enum Fase { LENDO, CONFERENCIA, CRIANDO, RESULTADO }

    /**
     * @param servidor          o nome do servidor (vai no botão de criar)
     * @param projeto           o nome do projeto
     * @param liberada          a trava das ações está ligada (criar escreve no projeto)
     * @param servidorProtegido o servidor é protegido (o app nunca instala nada nele: o "Instalar o Git…" fica desligado; criar o repositório vale, como Novo projeto)
     * @param instalarGit       leva ao "Instalar o Git…" que já existe (nada de instalador novo); roda a conferência de novo ao voltar
     * @param verCommits        abre a aba Git (depois de criado)
     * @param aoTerminar        roda quando a criação terminou (com sucesso ou pela metade): a página se atualiza
     */
    public DialogoCriarRepositorio(String servidor, String projeto, Motor motor, boolean liberada, boolean servidorProtegido, Runnable instalarGit, Runnable verCommits, Runnable aoTerminar,
                                   Notificacoes avisos, CopiadorDeTexto copiador) {
        super("Criar repositório Git");
        this.servidor = servidor;
        this.projeto = projeto;
        this.motor = motor;
        this.liberada = liberada;
        this.servidorProtegido = servidorProtegido;
        this.instalarGit = instalarGit;
        this.verCommits = verCommits;
        this.aoTerminar = aoTerminar;
        this.avisos = avisos;
        this.copiador = copiador;
        this.criar = Botoes.primario("Criar repositório em " + projeto + " (" + servidor + ")", "mais");
        criar.setName("criar-repositorio");
        conferirDeNovo.setName("conferir-de-novo");
        instalar.setName("instalar-git");
        verOsCommits.setName("ver-commits-depois-de-criar");
        cancelar.setName("cancelar");
        campoNome.setName("nome-de-quem-assina");
        campoEmail.setName("email-de-quem-assina");
        incluirGrandes.setName("incluir-grandes");
        incluirGrandes.putClientProperty("html.disable", Boolean.TRUE);
        incluirGrandes.setOpaque(false);
        campoNome.getAccessibleContext().setAccessibleName("Seu nome, para assinar os commits");
        campoEmail.getAccessibleContext().setAccessibleName("Seu e-mail, para assinar os commits");

        conteudoComRodape(Caixa.coluna(10, cabecalho("terminal", "Criar repositório Git"), area), rodape(razaoDaTrava, cancelar, conferirDeNovo, instalar, verOsCommits, criar));
        botaoPadrao(criar);
        razaoDaTrava.setVisible(false);
        instalar.setVisible(false);
        verOsCommits.setVisible(false);
        conferirDeNovo.setVisible(false);
        criar.setVisible(false);
        cancelar.addActionListener(e -> aoCancelar());
        conferirDeNovo.addActionListener(e -> conferir());
        instalar.addActionListener(e -> {
            if (instalarGit != null) {
                instalarGit.run();
            }
        });
        verOsCommits.addActionListener(e -> {
            fechar();
            if (verCommits != null) {
                verCommits.run();
            }
        });
        criar.addActionListener(e -> criar());
        SimplesOuvinteDeDocumento mudou = new SimplesOuvinteDeDocumento(this::conferirAIdentidade);
        campoNome.getDocument().addDocumentListener(mudou);
        campoEmail.getDocument().addDocumentListener(mudou);
        incluirGrandes.addActionListener(e -> {
            if (conferencia != null && fase == Fase.CONFERENCIA) {
                mostrarConferencia(conferencia); // as linhas do .gitignore e a contagem mudam com a caixa
            }
        });
        mostrarLendo();
    }

    @Override
    protected void antesDeMostrar() {
        if (fase == Fase.LENDO && conferencia == null) {
            conferir();
        }
    }

    /** Lê o projeto (só leitura) e mostra a conferência. */
    public void conferir() {
        int minha = geracao.incrementAndGet();
        mostrarLendo();
        Tarefas.rodar(motor::conferir, c -> {
            if (minha != geracao.get() || jaFechou()) {
                return;
            }
            conferencia = c;
            mostrarConferencia(c);
        }, f -> {
            if (minha != geracao.get() || jaFechou()) {
                return;
            }
            conferencia = ConferenciaDoRepositorio.parou(Situacao.NAO_CONFERI, new br.com.labdesktop.git.FalhaDoGit(br.com.labdesktop.git.FalhaDoGit.Tipo.OUTRO, MensagensDeErro.de(f)), "");
            mostrarConferencia(conferencia);
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Os tempos da janela
    // ---------------------------------------------------------------------------------------------

    private void trocar(JComponent novo) {
        area.removeAll();
        area.add(novo, BorderLayout.NORTH);
        area.revalidate();
        area.repaint();
        reajustar();
    }

    private void mostrarLendo() {
        fase = Fase.LENDO;
        conferirDeNovo.setVisible(false);
        instalar.setVisible(false);
        criar.setVisible(false);
        verOsCommits.setVisible(false);
        razaoDaTrava.setVisible(false);
        cancelar.setText("Cancelar");
        trocar(Caixa.coluna(8, new Rotulo("Conferindo a pasta de " + projeto + "…").negrito(),
            paragrafo("Só leitura: o app olha os arquivos e o Git do projeto e mostra o que faria. Nada é criado nem escrito no projeto antes do seu clique.")));
    }

    private void mostrarConferencia(ConferenciaDoRepositorio c) {
        fase = Fase.CONFERENCIA;
        cancelar.setText("Cancelar");
        conferirDeNovo.setVisible(true);
        verOsCommits.setVisible(false);
        criar.setVisible(c.situacao() == Situacao.PODE_CRIAR);
        instalar.setVisible(c.situacao() == Situacao.GIT_AUSENTE);
        instalar.setEnabled(!servidorProtegido);
        instalar.setToolTipText(servidorProtegido ? "O servidor é protegido: o app não instala nada nele. Instale o Git você mesmo ou abra Editar servidor."
            : "Abre a confirmação de instalar o Git, com os comandos exatos (nada roda antes do clique)");
        List<JComponent> itens = new ArrayList<>();
        itens.add(new Rotulo("Conferência (só leitura): nada foi criado ainda.").negrito());
        if (c.situacao() != Situacao.PODE_CRIAR) {
            itens.add(avisoDeBloqueio(c));
            trocar(Caixa.coluna(8, itens.toArray(JComponent[]::new)));
            razaoDaTrava.setVisible(false);
            return;
        }
        boolean grandes = incluirGrandes.isSelected();
        PlanejadorDoGitignore.Plano plano = c.planoDoGitignore(grandes);
        itens.add(dicaCorrida("Git: " + c.versaoDoGit() + ". Projeto " + projeto + " no servidor " + servidor + "."));

        // quem assina
        itens.add(secao("Quem assina os commits"));
        if (c.temIdentidade()) {
            itens.add(paragrafo("O Git já sabe quem você é: " + c.nomeAtual() + " <" + c.emailAtual() + ">. O app não grava nenhum dado seu na configuração do Git."));
        } else {
            itens.add(paragrafo("O Git ainda não sabe quem assina os commits. Diga seu nome e e-mail: o app grava SÓ neste repositório (git config --local), nunca na configuração de toda a máquina."));
            itens.add(Caixa.linha(8, new Rotulo("Nome").corDoToken(Tokens.TEXTO2).tamanho(12.5f), campoNome, Caixa.mola()));
            itens.add(Caixa.linha(8, new Rotulo("E-mail").corDoToken(Tokens.TEXTO2).tamanho(12.5f), campoEmail, Caixa.mola()));
            itens.add(erroDaIdentidade);
        }

        // o que entra
        itens.add(secao("O que entra no primeiro commit"));
        int entram = c.quantosEntram(grandes);
        String pastas = c.primeirasPastas(6).isEmpty() ? "" : " Pastas com mais arquivos: " + String.join(", ", c.primeirasPastas(6)) + ".";
        itens.add(paragrafo(entram + (entram == 1 ? " arquivo" : " arquivos") + ", mais o .gitignore (" + (c.gitignoreAtual() == null ? "que o app cria" : "que já existe e o app só completa") + ")." + pastas
            + " O .gitignore do projeto vale: o que ele ignora não entra."));
        if (c.ignoradosPorNome() > 0) {
            itens.add(dicaCorrida(c.ignoradosPorNome() + " arquivo(s) têm um nome com caractere estranho e ficam de fora."));
        }

        // segredos
        itens.add(secao("Segredos nunca entram"));
        itens.add(paragrafo("Arquivos como .env, .env.local, chaves (.pem, .key, id_rsa) e credenciais ficam de fora, e o app confere com o próprio Git (git check-ignore) antes de adicionar qualquer arquivo. "
            + "O .env.example, que é só o modelo sem valores, pode entrar."));
        if (plano.mudaAlgo()) {
            itens.add(dicaCorrida("O app acrescentaria estas linhas ao final do .gitignore (nada do que você já escreveu muda):"));
            AreaMonoespacada linhas = new AreaMonoespacada("Linhas que serão acrescentadas ao .gitignore", false);
            linhas.setName("linhas-do-gitignore");
            linhas.setText(String.join("\n", plano.linhasNovas()));
            linhas.setCaretPosition(0);
            JScrollPane r = AreaMonoespacada.emRolagem(linhas);
            r.setPreferredSize(new Dimension(LARGURA, Math.min(150, 26 + 17 * plano.linhasNovas().size())));
            itens.add(r);
        } else {
            itens.add(dicaCorrida("O .gitignore do projeto já tem todas essas linhas: o app não precisa mudá-lo."));
        }
        if (!c.protecaoConfirmada()) {
            itens.add(erroComTexto("O Git não confirmou que estas linhas protegem os segredos neste projeto. Por segurança o app não cria o repositório."));
        }
        if (!c.segredosQueEntrariam().isEmpty()) {
            itens.add(erroComTexto("Estes arquivos têm cara de segredo e um .gitignore de dentro do projeto os deixaria entrar: " + String.join(", ", c.segredosQueEntrariam().stream().limit(5).toList())
                + (c.segredosQueEntrariam().size() > 5 ? " e mais " + (c.segredosQueEntrariam().size() - 5) : "")
                + ". Tire a linha que os libera (uma linha que começa com !) ou o arquivo, e confira de novo. O app não cria o repositório assim."));
        }

        // repositórios de dentro
        if (!c.aninhados().isEmpty()) {
            itens.add(secao("Repositório dentro do projeto"));
            itens.add(paragrafo((c.aninhados().size() == 1 ? "Esta pasta já tem o próprio Git: " : "Estas pastas já têm o próprio Git: ") + String.join(", ", c.aninhados().stream().map(a -> a + "/").toList())
                + ". Elas ficam FORA do repositório do projeto (uma linha no .gitignore, mostrada acima): o Git guardaria só um ponteiro, sem o código."));
            itens.add(dicaCorrida("Para guardar o código delas AQUI, a alternativa é apagar a pasta .git de dentro (você perde o histórico dela) antes de criar, ou tratá-las como submódulo (git submodule add). O app não faz nenhuma das duas sozinho."));
        }

        // arquivos grandes
        if (!c.grandes().isEmpty()) {
            itens.add(secao("Arquivos grandes (mais de " + Formato.fmtBytes(CriadorDeRepositorio.LIMITE_DE_TAMANHO) + ")"));
            StringBuilder sb = new StringBuilder();
            c.grandes().stream().limit(6).forEach(g -> sb.append(g.caminho()).append("  (").append(Formato.fmtBytes(g.tamanho())).append(")\n"));
            if (c.grandes().size() > 6) {
                sb.append("e mais ").append(c.grandes().size() - 6).append('\n');
            }
            itens.add(paragrafo("Ficam de fora por padrão, porque o Git guarda cada versão deles para sempre:"));
            AreaMonoespacada lista = new AreaMonoespacada("Arquivos grandes", false);
            lista.setName("arquivos-grandes");
            lista.setText(sb.toString().strip());
            lista.setCaretPosition(0);
            JScrollPane r = AreaMonoespacada.emRolagem(lista);
            r.setPreferredSize(new Dimension(LARGURA, Math.min(120, 24 + 17 * Math.min(7, c.grandes().size() + 1))));
            itens.add(r);
            itens.add(incluirGrandes);
        }
        if (c.listaIncompleta()) {
            itens.add(erroComTexto("Este projeto tem arquivos demais para o app conferir um por um. Por segurança ele não cria o repositório: use o Terminal."));
        }
        itens.add(dicaCorrida("O app não faz push, não liga a nenhum remoto e nunca usa --force. Só cria o repositório local, no branch main, com o commit \"Primeiro commit\"."));
        trocar(Caixa.coluna(8, itens.toArray(JComponent[]::new)));
        conferirAIdentidade();
    }

    /** O que impede de criar, em palavras simples (e o botão que ajuda, quando existe). */
    private JComponent avisoDeBloqueio(ConferenciaDoRepositorio c) {
        String frase = switch (c.situacao()) {
            case GIT_AUSENTE -> "O Git não está instalado nesta máquina. Sem ele o app não cria o repositório. "
                + (servidorProtegido ? "O servidor é protegido: o app não instala nada nele, então instale o Git você mesmo e confira de novo."
                : "\"Instalar o Git…\" leva à confirmação que já existe, com os comandos exatos.");
            case GIT_ANTIGO -> c.falha().frase();
            case JA_E_REPOSITORIO -> "Esta pasta já é um repositório Git. O app não cria por cima de um que existe.";
            case DENTRO_DE_OUTRO -> "Esta pasta está dentro de outro repositório Git (de uma pasta de cima). O app não cria um repositório dentro de outro.";
            default -> c.falha() == null ? "Não consegui conferir a pasta." : c.falha().frase();
        };
        TextoCorrido t = erroCorrido();
        dizerErro(t, frase);
        t.setName("bloqueio");
        return t;
    }

    private static Rotulo secao(String texto) {
        return new Rotulo(texto).negrito().tamanho(13f);
    }

    private TextoCorrido erroComTexto(String texto) {
        TextoCorrido t = erroCorrido();
        dizerErro(t, texto);
        return t;
    }

    // ---------------------------------------------------------------------------------------------
    // Criar
    // ---------------------------------------------------------------------------------------------

    /** Liga ou desliga o "Criar" conforme a trava, a conferência e a identidade. */
    private void conferirAIdentidade() {
        if (fase != Fase.CONFERENCIA || conferencia == null || conferencia.situacao() != Situacao.PODE_CRIAR) {
            return;
        }
        boolean precisa = !conferencia.temIdentidade();
        String nome = campoNome.getText();
        String email = campoEmail.getText().strip();
        boolean identidadeOk = !precisa || (ValidadorDeGit.nomeDeAutorValido(nome) && ValidadorDeGit.emailValido(email));
        if (precisa) {
            String aviso = "";
            if (!nome.isEmpty() && !ValidadorDeGit.nomeDeAutorValido(nome)) {
                aviso = "O nome não pode começar nem terminar com espaço, ter mais de 100 letras nem usar < > ou \\.";
            } else if (!email.isEmpty() && !ValidadorDeGit.emailValido(email)) {
                aviso = "O e-mail precisa ter a forma nome@example.com.";
            }
            dizerErro(erroDaIdentidade, aviso);
        }
        boolean pode = liberada && identidadeOk && conferencia.liberaACriacao() && !ocupado;
        criar.setEnabled(pode);
        criar.setToolTipText(!liberada ? RAZAO_DA_TRAVA : !conferencia.liberaACriacao() ? "A conferência não libera criar: veja o aviso em vermelho."
            : !identidadeOk ? "Diga seu nome e e-mail para assinar os commits." : "Cria o repositório depois do seu clique: git init, .gitignore, git add dos arquivos conferidos e o primeiro commit.");
        razaoDaTrava.setVisible(!liberada);
    }

    private void criar() {
        if (ocupado || conferencia == null || !liberada || !conferencia.liberaACriacao()) {
            return;
        }
        PedidoDeCriacao pedido = new PedidoDeCriacao(conferencia, conferencia.temIdentidade() ? null : campoNome.getText(),
            conferencia.temIdentidade() ? null : campoEmail.getText().strip(), incluirGrandes.isSelected());
        ocupado = true;
        fase = Fase.CRIANDO;
        criar.setVisible(false);
        conferirDeNovo.setVisible(false);
        instalar.setVisible(false);
        razaoDaTrava.setVisible(false);
        cancelar.setText("Cancelar");
        progresso.setText("Começando…");
        trocar(Caixa.coluna(8, new Rotulo("Criando o repositório em " + projeto + "…").negrito(), progresso,
            dicaCorrida("Cada passo aparece aqui. Cancelar para depois do passo em curso e diz o que ficou feito; o app nunca apaga o .git sozinho.")));
        int minha = geracao.incrementAndGet();
        Tarefas.rodar(() -> motor.criar(pedido, frase -> br.com.labdesktop.util.Fundo.naEdt(() -> {
            if (minha == geracao.get() && !jaFechou()) {
                progresso.setText(frase);
            }
        }), cancelamento), r -> {
            ocupado = false;
            resultado = r;
            if (r.repositorioCriado() && aoTerminar != null) {
                aoTerminar.run();
            }
            if (jaFechou()) {
                if (r.criou()) {
                    avisos.ok(r.mensagem());
                } else {
                    avisos.atencao(r.mensagem());
                }
                return;
            }
            mostrarResultado(r);
        }, f -> {
            ocupado = false;
            if (jaFechou()) {
                avisos.erro("Criar o repositório em " + projeto + ": " + MensagensDeErro.de(f));
                return;
            }
            mostrarResultado(new ResultadoDaCriacao(false, true, "Não terminei de criar o repositório: " + MensagensDeErro.de(f)
                + " Confira a pasta: o app não apaga nada sozinho.", List.of(), List.of(), null, 0, "", null));
        });
    }

    private void mostrarResultado(ResultadoDaCriacao r) {
        fase = Fase.RESULTADO;
        cancelar.setText("Fechar");
        conferirDeNovo.setVisible(false);
        criar.setVisible(false);
        instalar.setVisible(false);
        razaoDaTrava.setVisible(false);
        verOsCommits.setVisible(r.criou() && r.arquivosNoCommit() > 0);
        List<JComponent> itens = new ArrayList<>();
        Rotulo titulo = new Rotulo(r.criou() ? "Repositório criado" : r.repositorioCriado() ? "Ficou pela metade" : "Nada foi criado").negrito().tamanho(14f);
        titulo.setName("titulo-do-resultado");
        itens.add(titulo);
        TextoCorrido msg = paragrafo(r.mensagem());
        msg.setName("mensagem-do-resultado");
        itens.add(msg);
        if (!r.feito().isEmpty()) {
            itens.add(secao("O que o app fez"));
            r.feito().forEach(f -> itens.add(paragrafo("• " + f)));
        }
        if (!r.naoFeito().isEmpty()) {
            itens.add(secao("O que o app NÃO fez"));
            r.naoFeito().forEach(f -> itens.add(paragrafo("• " + f)));
        }
        if (r.comandoParaDesfazer() != null) {
            itens.add(dicaCorrida("Para voltar a como estava (o app não faz isso sozinho), rode no Terminal do servidor:"));
            Rotulo comando = new Rotulo(r.comandoParaDesfazer()).mono(12f).truncavel();
            comando.setName("comando-para-desfazer");
            comando.putClientProperty("html.disable", Boolean.TRUE);
            copiarComando = BotaoCopiar.criar("Copiar comando", () -> r.comandoParaDesfazer(), copiador, avisos);
            itens.add(Caixa.linha(8, comando, Caixa.mola(), copiarComando));
        }
        trocar(Caixa.coluna(8, itens.toArray(JComponent[]::new)));
    }

    // ---------------------------------------------------------------------------------------------
    // Fechar
    // ---------------------------------------------------------------------------------------------

    /** Esc, o X e o botão. Criando: pede para parar depois do passo em curso (a janela nunca fica presa). */
    @Override
    protected void aoCancelar() {
        if (ocupado) {
            cancelamento.cancelar();
            avisos.info("O app para depois do passo em curso e diz o que ficou feito.");
        }
        fechar();
    }

    @Override
    protected void aoFechar() {
        if (fase != Fase.CRIANDO) {
            geracao.incrementAndGet();
        }
    }

    // ---- para os testes ----

    public Botao botaoCriar() {
        return criar;
    }

    public Botao botaoConferirDeNovo() {
        return conferirDeNovo;
    }

    public Botao botaoInstalar() {
        return instalar;
    }

    public Botao botaoVerCommits() {
        return verOsCommits;
    }

    public Botao botaoCancelar() {
        return cancelar;
    }

    public JTextField campoNome() {
        return campoNome;
    }

    public JTextField campoEmail() {
        return campoEmail;
    }

    public JCheckBox caixaDosGrandes() {
        return incluirGrandes;
    }

    public Botao botaoCopiarComando() {
        return copiarComando;
    }

    public ConferenciaDoRepositorio conferenciaMostrada() {
        return conferencia;
    }

    public ResultadoDaCriacao resultadoMostrado() {
        return resultado;
    }

    public boolean mostraRazaoDaTrava() {
        return razaoDaTrava.isVisible();
    }

    public boolean fechado() {
        return jaFechou();
    }

    public String faseAtual() {
        return fase.name();
    }

    public String textoDoProgresso() {
        return progresso.getText();
    }
}
