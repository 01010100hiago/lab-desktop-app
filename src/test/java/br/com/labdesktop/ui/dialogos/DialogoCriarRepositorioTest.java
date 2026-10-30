package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.git.ClassificadorDeErrosDoGit;
import br.com.labdesktop.git.ConferenciaDoRepositorio;
import br.com.labdesktop.git.ConferenciaDoRepositorio.ArquivoGrande;
import br.com.labdesktop.git.ConferenciaDoRepositorio.Situacao;
import br.com.labdesktop.git.FalhaDoGit;
import br.com.labdesktop.git.OperacoesDeGit;
import br.com.labdesktop.git.PedidoDeCriacao;
import br.com.labdesktop.git.ResultadoDaCriacao;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.TextosDeTela;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A janela "Criar repositório Git" com um motor de mentira: a conferência (só leitura) com TODOS os avisos, a identidade pedida na janela, a trava das ações, o Git ausente e o servidor protegido,
 * o que vai no pedido, a criação com cada passo à vista, o resultado (inteiro e pela metade, com o comando para desfazer) e o fechar no meio.
 */
@ExtendWith(VerificaEdt.class)
class DialogoCriarRepositorioTest {

    private final AvisosGravados avisos = new AvisosGravados();
    private final Clipboard area = new Clipboard("teste");
    private final List<String> eventos = new CopyOnWriteArrayList<>();

    @AfterEach
    void restaurar() {
        Visual.tema(Tema.CLARO);
        Dialogo.definirAlturaDaTelaParaTeste(0);
    }

    private static <T> T ed(Supplier<T> s) {
        return Visual.naEdt(s);
    }

    static ConferenciaDoRepositorio completa(String nome, String email) {
        return new ConferenciaDoRepositorio(Situacao.PODE_CRIAR, null, "git version 2.43.0", nome, email,
            List.of(".env.example", "src/app.js", "src/rotas.js", "docs/leia.md", "dados/grande.sql", "dados/outro.bin"),
            List.of(new ArquivoGrande("dados/grande.sql", 6L * 1024 * 1024), new ArquivoGrande("dados/outro.bin", 12L * 1024 * 1024)),
            List.of("clone-do-claude"), null, List.of(), true, false, 0);
    }

    static ConferenciaDoRepositorio parou(Situacao s, FalhaDoGit f) {
        return ConferenciaDoRepositorio.parou(s, f, "git version 2.43.0");
    }

    /** O motor de mentira: devolve o que o teste pôs e anota tudo. */
    final class Motor implements DialogoCriarRepositorio.Motor {
        volatile ConferenciaDoRepositorio conferencia = completa("", "");
        volatile ResultadoDaCriacao resultado = new ResultadoDaCriacao(true, true, "Repositório criado com o primeiro commit: 5 arquivo(s), branch main.", List.of("Criei o repositório com o branch main (git init)."),
            List.of(), null, 5, "abc1234", null);
        volatile CountDownLatch segurar;
        final AtomicReference<PedidoDeCriacao> pedido = new AtomicReference<>();
        final AtomicReference<Cancelamento> cancelamento = new AtomicReference<>();
        final AtomicInteger conferencias = new AtomicInteger();
        final AtomicInteger criacoes = new AtomicInteger();

        @Override
        public ConferenciaDoRepositorio conferir() {
            conferencias.incrementAndGet();
            return conferencia;
        }

        @Override
        public ResultadoDaCriacao criar(PedidoDeCriacao p, OperacoesDeGit.Observador observador, Cancelamento c) throws Exception {
            criacoes.incrementAndGet();
            pedido.set(p);
            cancelamento.set(c);
            observador.passo("Criando o repositório (git init)…");
            observador.passo("Ajustando o .gitignore…");
            if (segurar != null) {
                segurar.await();
            }
            return resultado;
        }
    }

    private DialogoCriarRepositorio abrir(Motor m, boolean liberada, boolean protegido) {
        DialogoCriarRepositorio d = Visual.naEdt(() -> new DialogoCriarRepositorio("lab", "loja-web", m, liberada, protegido, () -> eventos.add("instalar"), () -> eventos.add("ver-commits"),
            () -> eventos.add("terminou"), avisos, new CopiadorDeTexto(() -> area)));
        Visual.naEdt(d::conferir);
        Espera.ate(Duration.ofSeconds(10), "conferência", () -> ed(() -> d.conferenciaMostrada() != null));
        return d;
    }

    private static String texto(DialogoCriarRepositorio d) {
        return Visual.naEdt(() -> TextosDeTela.de(d.raiz()));
    }

    private String clipboard() throws Exception {
        return (String) area.getData(DataFlavor.stringFlavor);
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void antesDeLerMostraConferindoESoLeitura() {
        Visual.tema(Tema.CLARO);
        Motor m = new Motor();
        DialogoCriarRepositorio d = Visual.naEdt(() -> new DialogoCriarRepositorio("lab", "loja-web", m, true, false, null, null, null, avisos, new CopiadorDeTexto(() -> area)));
        assertEquals("LENDO", d.faseAtual());
        assertTrue(texto(d).contains("Conferindo a pasta de loja-web"));
        assertTrue(texto(d).contains("Só leitura"));
        assertFalse(ed(() -> d.botaoCriar().isVisible()), "nada para criar antes de conferir");
        assertEquals(0, m.conferencias.get(), "montar a janela não confere nada: só ao abrir");
    }

    @Test
    void aConferenciaMostraTudoAntesDeQualquerEscrita() {
        Visual.tema(Tema.CLARO);
        Motor m = new Motor();
        DialogoCriarRepositorio d = abrir(m, true, false);
        String t = texto(d);
        assertEquals("CONFERENCIA", d.faseAtual());
        assertTrue(t.contains("Conferência (só leitura): nada foi criado ainda."), t);
        assertTrue(t.contains("git version 2.43.0"));
        assertTrue(t.contains("loja-web"));
        assertTrue(t.contains("O que entra no primeiro commit"));
        assertTrue(t.contains("4 arquivos, mais o .gitignore (que o app cria)"), "6 arquivos, 2 grandes de fora por padrão: " + t);
        assertTrue(t.contains("Pastas com mais arquivos: dados/ (2), src/ (2)") || t.contains("src/ (2)"), t);
        assertTrue(t.contains("Segredos nunca entram"));
        assertTrue(t.contains("git check-ignore"));
        assertTrue(t.contains(".env.example"), "o modelo sem valores pode entrar");
        assertTrue(t.contains("Repositório dentro do projeto"));
        assertTrue(t.contains("clone-do-claude/"));
        assertTrue(t.contains("submódulo"), "a alternativa vem explicada");
        assertTrue(t.contains("Arquivos grandes (mais de 5 MB)"), t);
        assertTrue(t.contains("O app não faz push"));
        assertTrue(t.contains("Quem assina os commits"));
        assertEquals("Criar repositório em loja-web (lab)", ed(() -> d.botaoCriar().getText()), "o servidor e o projeto vão no botão");
        assertEquals(0, m.criacoes.get(), "conferir nunca cria");
    }

    @Test
    void asLinhasDoGitignoreSaoMostradasAntesEMudamComACaixaDosGrandes() {
        Visual.tema(Tema.CLARO);
        Motor m = new Motor();
        DialogoCriarRepositorio d = abrir(m, true, false);
        String linhas = Visual.naEdt(() -> primeiraAreaComNome(d, "linhas-do-gitignore"));
        for (String esperada : List.of(".[eE][nN][vV]*", br.com.labdesktop.git.SegredosDoProjeto.LINHA_DA_EXCECAO, "*.[pP][eE][mM]", "[iI][dD]_[rR][sS][aA]*", "[cC][rR][eE][dD][eE][nN][tT][iI][aA][lL][sS]*", "[sS][eE][cC][rR][eE][tT][sS]*", ".[aA][wW][sS]/", ".[sS][sS][hH]/", "node_modules/", "/clone-do-claude/", "/dados/grande.sql", "/dados/outro.bin")) {
            assertTrue(linhas.lines().anyMatch(esperada::equals), esperada + " em:\n" + linhas);
        }
        Visual.naEdt(() -> d.caixaDosGrandes().doClick());
        String sem = Visual.naEdt(() -> primeiraAreaComNome(d, "linhas-do-gitignore"));
        assertFalse(sem.contains("/dados/grande.sql"), "com a caixa marcada os grandes entram: nada de linha para eles");
        assertTrue(sem.contains("/clone-do-claude/"), "o repositório de dentro fica de fora de qualquer jeito");
        assertTrue(texto(d).contains("6 arquivos, mais o .gitignore"), texto(d));
    }

    static String primeiraAreaComNome(DialogoCriarRepositorio d, String nome) {
        for (java.awt.Component c : todos(d.raiz())) {
            if (nome.equals(c.getName()) && c instanceof javax.swing.text.JTextComponent t) {
                return t.getText();
            }
        }
        return null;
    }

    static List<java.awt.Component> todos(java.awt.Component c) {
        List<java.awt.Component> l = new ArrayList<>();
        l.add(c);
        if (c instanceof java.awt.Container k) {
            for (java.awt.Component f : k.getComponents()) {
                l.addAll(todos(f));
            }
        }
        return l;
    }

    @Test
    void semIdentidadePedeNomeEEmailNaJanelaESoLiberaComOsDoisValidos() {
        Visual.tema(Tema.CLARO);
        Motor m = new Motor();
        DialogoCriarRepositorio d = abrir(m, true, false);
        assertTrue(texto(d).contains("SÓ neste repositório (git config --local)"), texto(d));
        assertTrue(texto(d).contains("nunca na configuração de toda a máquina"));
        assertFalse(ed(() -> d.botaoCriar().isEnabled()), "faltam nome e e-mail");
        Visual.naEdt(() -> d.campoNome().setText("Ana Silva"));
        assertFalse(ed(() -> d.botaoCriar().isEnabled()));
        Visual.naEdt(() -> d.campoEmail().setText("isso-nao-e-email"));
        assertFalse(ed(() -> d.botaoCriar().isEnabled()));
        assertTrue(texto(d).contains("O e-mail precisa ter a forma nome@example.com."));
        Visual.naEdt(() -> d.campoEmail().setText("ana@example.com"));
        assertTrue(ed(() -> d.botaoCriar().isEnabled()));
        Visual.naEdt(() -> d.campoNome().setText(" Ana"));
        assertFalse(ed(() -> d.botaoCriar().isEnabled()), "espaço no começo do nome não vale");
        Visual.naEdt(() -> d.campoNome().setText("Ana <ana@x>"));
        assertFalse(ed(() -> d.botaoCriar().isEnabled()));
    }

    @Test
    void comIdentidadeJaConfiguradaNaoPedeNadaENaoManda() throws Exception {
        Visual.tema(Tema.CLARO);
        Motor m = new Motor();
        m.conferencia = completa("Dona Global", "global@example.com");
        DialogoCriarRepositorio d = abrir(m, true, false);
        assertTrue(texto(d).contains("O Git já sabe quem você é: Dona Global <global@example.com>"), texto(d));
        assertFalse(ed(() -> d.campoNome().isShowing() || d.campoNome().getParent() != null && d.campoNome().isVisible() && d.campoNome().getParent().isShowing()) && texto(d).contains("Nome"), "sem campos de identidade");
        assertTrue(ed(() -> d.botaoCriar().isEnabled()));
        Visual.naEdt(() -> d.botaoCriar().doClick(0));
        Espera.ate(Duration.ofSeconds(10), "resultado", () -> "RESULTADO".equals(ed(d::faseAtual)));
        assertNull(m.pedido.get().nome(), "o app não manda nome nenhum quando o git já sabe");
        assertNull(m.pedido.get().email());
    }

    @Test
    void aTravaDesligadaDeixaOBotaoDesligadoComARazaoENuncaCria() {
        Visual.tema(Tema.CLARO);
        Motor m = new Motor();
        m.conferencia = completa("Ana", "ana@example.com");
        DialogoCriarRepositorio d = abrir(m, false, false);
        assertTrue(ed(() -> d.botaoCriar().isVisible()), "não some");
        assertFalse(ed(() -> d.botaoCriar().isEnabled()));
        assertEquals("As ações de escrita estão desligadas nesta versão do app.", ed(() -> d.botaoCriar().getToolTipText()));
        assertTrue(ed(d::mostraRazaoDaTrava));
        assertTrue(texto(d).contains("As ações de escrita estão desligadas nesta versão do app."));
        Visual.naEdt(() -> d.botaoCriar().doClick(0));
        assertEquals(0, m.criacoes.get());
        assertEquals("CONFERENCIA", d.faseAtual());
    }

    @Test
    void gitAusenteLevaAoInstalarQueJaExisteEServidorProtegidoDesligaOBotao() {
        Visual.tema(Tema.CLARO);
        Motor m = new Motor();
        m.conferencia = parou(Situacao.GIT_AUSENTE, ClassificadorDeErrosDoGit.naoInstalado());
        DialogoCriarRepositorio d = abrir(m, true, false);
        assertTrue(texto(d).contains("O Git não está instalado nesta máquina"));
        assertTrue(texto(d).contains("Instalar o Git…"));
        assertFalse(ed(() -> d.botaoCriar().isVisible()));
        assertTrue(ed(() -> d.botaoInstalar().isVisible() && d.botaoInstalar().isEnabled()));
        Visual.naEdt(() -> d.botaoInstalar().doClick(0));
        assertEquals(List.of("instalar"), eventos, "é o fluxo que já existe: este botão só chama");

        Motor mp = new Motor();
        mp.conferencia = m.conferencia;
        DialogoCriarRepositorio protegido = abrir(mp, true, true);
        assertTrue(ed(() -> protegido.botaoInstalar().isVisible()));
        assertFalse(ed(() -> protegido.botaoInstalar().isEnabled()), "servidor protegido: nunca instala");
        assertTrue(ed(() -> protegido.botaoInstalar().getToolTipText()).contains("protegido"));
        assertTrue(texto(protegido).contains("O servidor é protegido"));
        Visual.naEdt(() -> protegido.botaoInstalar().doClick(0));
        assertEquals(List.of("instalar"), eventos, "o clique no botão desligado não chama nada");
    }

    @Test
    void osOutrosBloqueiosDizemOMotivoESemBotaoDeCriar() {
        Visual.tema(Tema.CLARO);
        for (Object[] caso : new Object[][] {
            {Situacao.GIT_ANTIGO, ClassificadorDeErrosDoGit.antigo("git version 2.20.1"), "2.28"},
            {Situacao.JA_E_REPOSITORIO, new FalhaDoGit(FalhaDoGit.Tipo.OUTRO, "x"), "já é um repositório Git"},
            {Situacao.DENTRO_DE_OUTRO, new FalhaDoGit(FalhaDoGit.Tipo.OUTRO, "x"), "dentro de outro repositório"},
            {Situacao.NAO_CONFERI, new FalhaDoGit(FalhaDoGit.Tipo.SEM_PERMISSAO, "O usuário do app não tem permissão para ler esta pasta."), "não tem permissão"}}) {
            Motor m = new Motor();
            m.conferencia = parou((Situacao) caso[0], (FalhaDoGit) caso[1]);
            DialogoCriarRepositorio d = abrir(m, true, false);
            assertTrue(texto(d).contains((String) caso[2]), caso[0] + ": " + texto(d));
            assertFalse(ed(() -> d.botaoCriar().isVisible()), String.valueOf(caso[0]));
            assertTrue(ed(() -> d.botaoConferirDeNovo().isVisible()), "dá para conferir de novo");
        }
    }

    @Test
    void segredoQueEntrariaProtecaoNaoConfirmadaEListaIncompletaImpedemACriacao() {
        Visual.tema(Tema.CLARO);
        ConferenciaDoRepositorio base = completa("Ana", "ana@example.com");
        ConferenciaDoRepositorio comSegredo = new ConferenciaDoRepositorio(Situacao.PODE_CRIAR, null, "git version 2.43.0", "Ana", "ana@example.com", base.arquivos(), base.grandes(), base.aninhados(), null,
            List.of("sub/.env"), true, false, 0);
        ConferenciaDoRepositorio semProtecao = new ConferenciaDoRepositorio(Situacao.PODE_CRIAR, null, "git version 2.43.0", "Ana", "ana@example.com", base.arquivos(), base.grandes(), base.aninhados(), null,
            List.of(), false, false, 0);
        ConferenciaDoRepositorio incompleta = new ConferenciaDoRepositorio(Situacao.PODE_CRIAR, null, "git version 2.43.0", "Ana", "ana@example.com", base.arquivos(), base.grandes(), base.aninhados(), null,
            List.of(), true, true, 0);
        for (ConferenciaDoRepositorio c : List.of(comSegredo, semProtecao, incompleta)) {
            Motor m = new Motor();
            m.conferencia = c;
            DialogoCriarRepositorio d = abrir(m, true, false);
            assertFalse(ed(() -> d.botaoCriar().isEnabled()), "não libera");
            Visual.naEdt(() -> d.botaoCriar().doClick(0));
            assertEquals(0, m.criacoes.get());
        }
        Motor m = new Motor();
        m.conferencia = comSegredo;
        assertTrue(texto(abrir(m, true, false)).contains("sub/.env"), "diz qual arquivo");
    }

    @Test
    void criarMandaOPedidoCertoMostraOsPassosEOResultado() {
        Visual.tema(Tema.CLARO);
        Motor m = new Motor();
        m.segurar = new CountDownLatch(1);
        DialogoCriarRepositorio d = abrir(m, true, false);
        Visual.naEdt(() -> {
            d.campoNome().setText("Joana D'Ávila");
            d.campoEmail().setText("joana@example.com");
            d.caixaDosGrandes().doClick();
        });
        Visual.naEdt(() -> d.botaoCriar().doClick(0));
        assertEquals("CRIANDO", d.faseAtual());
        Espera.ate(Duration.ofSeconds(10), "passos", () -> ed(d::textoDoProgresso).startsWith("Ajustando"));
        assertTrue(texto(d).contains("Criando o repositório em loja-web"));
        assertFalse(ed(() -> d.botaoCriar().isVisible()), "clique duplo não cria duas vezes");
        PedidoDeCriacao p = m.pedido.get();
        assertEquals("Joana D'Ávila", p.nome());
        assertEquals("joana@example.com", p.email());
        assertTrue(p.incluirGrandes());
        m.segurar.countDown();
        Espera.ate(Duration.ofSeconds(10), "resultado", () -> "RESULTADO".equals(ed(d::faseAtual)));
        assertEquals("Repositório criado", Visual.naEdt(() -> TextosDeTela.de(d.raiz()).lines().filter(l -> l.startsWith("Repositório criado")).findFirst().orElse("")));
        assertTrue(texto(d).contains("5 arquivo(s), branch main"));
        assertTrue(ed(() -> d.botaoVerCommits().isVisible()));
        assertEquals(1, m.criacoes.get());
        assertEquals(List.of("terminou"), eventos);
        assertEquals("Fechar", ed(() -> d.botaoCancelar().getText()));
        Visual.naEdt(() -> d.botaoVerCommits().doClick(0));
        assertTrue(ed(d::fechado));
        assertEquals(List.of("terminou", "ver-commits"), eventos);
    }

    @Test
    void quandoFalhaNoMeioDizOQueFezOQueNaoFezEOComandoParaDesfazer() throws Exception {
        Visual.tema(Tema.CLARO);
        Motor m = new Motor();
        m.conferencia = completa("Ana", "ana@example.com");
        m.resultado = new ResultadoDaCriacao(false, true, "Não terminei de criar o repositório. Não consegui ajustar o .gitignore: Sem permissão para gravar aqui. O app NÃO apaga nada sozinho.",
            List.of("Criei o repositório com o branch main (git init)."), List.of("Ajustar o .gitignore", "Preparar os arquivos (git add)", "Fazer o primeiro commit"),
            "rm -rf /srv/lab/projects/loja-web/.git", 0, "", new FalhaDoGit(FalhaDoGit.Tipo.OUTRO, "x"));
        DialogoCriarRepositorio d = abrir(m, true, false);
        Visual.naEdt(() -> d.botaoCriar().doClick(0));
        Espera.ate(Duration.ofSeconds(10), "resultado", () -> "RESULTADO".equals(ed(d::faseAtual)));
        String t = texto(d);
        assertTrue(t.contains("Ficou pela metade"), t);
        assertTrue(t.contains("O que o app fez") && t.contains("Criei o repositório com o branch main"));
        assertTrue(t.contains("O que o app NÃO fez") && t.contains("Fazer o primeiro commit"));
        assertTrue(t.contains("rm -rf /srv/lab/projects/loja-web/.git"));
        assertTrue(t.contains("o app não faz isso sozinho"));
        assertFalse(ed(() -> d.botaoVerCommits().isVisible()));
        assertEquals(List.of("terminou"), eventos, "a página relê: o .git existe");
        Visual.naEdt(() -> d.botaoCopiarComando().doClick(0));
        assertEquals("rm -rf /srv/lab/projects/loja-web/.git", clipboard());
    }

    @Test
    void aTravaAindaValeDentroDoMotorEResultadoSemRepositorioNaoAtualizaAPagina() {
        Visual.tema(Tema.CLARO);
        Motor m = new Motor();
        m.conferencia = completa("Ana", "ana@example.com");
        m.resultado = new ResultadoDaCriacao(false, false, "A conferência não libera criar o repositório agora. Nada foi feito.", List.of(), List.of("Criar o repositório"), null, 0, "", null);
        DialogoCriarRepositorio d = abrir(m, true, false);
        Visual.naEdt(() -> d.botaoCriar().doClick(0));
        Espera.ate(Duration.ofSeconds(10), "resultado", () -> "RESULTADO".equals(ed(d::faseAtual)));
        assertTrue(texto(d).contains("Nada foi criado"));
        assertTrue(eventos.isEmpty(), "nada criado: a página não precisa reler");
    }

    @Test
    void fecharNoMeioDaCriacaoPedeParaParaEOResultadoChegaPelosAvisos() {
        Visual.tema(Tema.CLARO);
        Motor m = new Motor();
        m.conferencia = completa("Ana", "ana@example.com");
        m.segurar = new CountDownLatch(1);
        DialogoCriarRepositorio d = abrir(m, true, false);
        Visual.naEdt(() -> d.botaoCriar().doClick(0));
        Espera.ate(Duration.ofSeconds(10), "criando", () -> m.criacoes.get() == 1);
        Visual.naEdt(() -> d.botaoCancelar().doClick(0));
        assertTrue(ed(d::fechado), "a janela nunca fica presa");
        assertTrue(m.cancelamento.get().cancelado(), "o motor foi avisado para parar depois do passo em curso");
        m.segurar.countDown();
        Espera.ate(Duration.ofSeconds(10), "aviso final", () -> avisos.textos().stream().anyMatch(a -> a.contains("Repositório criado")));
        assertTrue(avisos.textos().stream().anyMatch(a -> a.contains("Repositório criado")), avisos.textos().toString());
    }

    @Test
    void conferirDeNovoLeOutraVezEQueTemNoMaximoUmaEscrita() {
        Visual.tema(Tema.CLARO);
        Motor m = new Motor();
        DialogoCriarRepositorio d = abrir(m, true, false);
        assertEquals(1, m.conferencias.get());
        Visual.naEdt(() -> d.botaoConferirDeNovo().doClick(0));
        Espera.ate(Duration.ofSeconds(10), "segunda conferência", () -> m.conferencias.get() == 2 && "CONFERENCIA".equals(ed(d::faseAtual)));
        assertEquals(0, m.criacoes.get());
    }

    @Test
    void semEmojiENosDoisTemasComPngs() {
        Dialogo.definirAlturaDaTelaParaTeste(1700);
        for (Tema t : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(t);
            Motor m = new Motor();
            DialogoCriarRepositorio d = abrir(m, true, false);
            Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(d.raiz()));
            assertFalse(java.util.regex.Pattern.compile("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}]").matcher(texto(d)).find());
            foto(d, "git-criar-repositorio-conferencia-" + (t == Tema.CLARO ? "claro" : "escuro"));

            Motor sem = new Motor();
            sem.conferencia = completa("Ana", "ana@example.com");
            DialogoCriarRepositorio semTrava = abrir(sem, false, false);
            Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(semTrava.raiz()));
            foto(semTrava, "git-criar-repositorio-sem-trava-" + (t == Tema.CLARO ? "claro" : "escuro"));

            Motor falha = new Motor();
            falha.conferencia = completa("Ana", "ana@example.com");
            falha.resultado = new ResultadoDaCriacao(false, true, "Não terminei de criar o repositório. Não consegui ajustar o .gitignore: Sem permissão para gravar aqui. O app NÃO apaga nada sozinho: a pasta .git que ele criou continua lá.",
                List.of("Criei o repositório com o branch main (git init)."), List.of("Ajustar o .gitignore", "Preparar os arquivos (git add)", "Fazer o primeiro commit"), "rm -rf /srv/lab/projects/loja-web/.git", 0, "", null);
            DialogoCriarRepositorio meio = abrir(falha, true, false);
            Visual.naEdt(() -> meio.botaoCriar().doClick(0));
            Espera.ate(Duration.ofSeconds(10), "resultado", () -> "RESULTADO".equals(ed(meio::faseAtual)));
            Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(meio.raiz()));
            foto(meio, "git-criar-repositorio-pela-metade-" + (t == Tema.CLARO ? "claro" : "escuro"));

            Motor ausente = new Motor();
            ausente.conferencia = parou(Situacao.GIT_AUSENTE, ClassificadorDeErrosDoGit.naoInstalado());
            DialogoCriarRepositorio sg = abrir(ausente, true, false);
            Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(sg.raiz()));
            foto(sg, "git-criar-repositorio-sem-git-" + (t == Tema.CLARO ? "claro" : "escuro"));
        }
    }

    private static void foto(DialogoCriarRepositorio d, String nome) {
        int largura = Visual.naEdt(() -> d.raiz().getPreferredSize().width);
        Visual.salvar(Visual.renderizar(d.raiz(), largura, Visual.alturaPreferida(d.raiz(), largura)), nome);
    }
}
