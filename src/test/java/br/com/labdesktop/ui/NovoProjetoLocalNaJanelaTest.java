package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.arquivos.ServicosDeArquivos;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.local.ConfigLocal;
import br.com.labdesktop.local.PlataformaLocal;
import br.com.labdesktop.local.ServicosLocais;
import br.com.labdesktop.plataforma.SistemaOperacional;
import br.com.labdesktop.projetos.ModelosDeContainer.Modelo;
import br.com.labdesktop.servidor.AssistenteDeServidor;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.teste.ComputadorSimulado;
import br.com.labdesktop.teste.SondaFalsa;
import br.com.labdesktop.ui.dialogos.DialogoAdicionarContainer;
import br.com.labdesktop.ui.dialogos.DialogoNovoProjeto;
import br.com.labdesktop.ui.telas.FluxosDeProjeto;
import br.com.labdesktop.ui.telas.TelaProjeto;
import br.com.labdesktop.ui.telas.TelaProjetos;
import br.com.labdesktop.ui.telas.TelaServidor;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias.Tema;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * "Novo projeto" em "Este computador", de ponta a ponta pela janela (sem JFrame): o botão e o convite da aba Projetos (que o local ganhou), criar do
 * zero NA PASTA do laboratório local com os arquivos do agente, a rede pelo "Docker" simulado, importar uma pasta do computador, a lista que se atualiza
 * sozinha, a página do projeto aberta na aba Terminal, e "Adicionar container" gravando no compose depois de conferir. Sem Docker de verdade.
 */
@ExtendWith(VerificaEdt.class)
class NovoProjetoLocalNaJanelaTest {

    private static final char[] SENHA = "uma-senha-mestra-de-teste".toCharArray();
    private static final Duration PRAZO = Duration.ofSeconds(25);

    @TempDir
    Path tmp;

    private ComputadorSimulado pc;
    private CofreDeChaves cofre;
    private GerenciadorServidores gerenciador;
    private AssistenteDeServidor assistente;
    private PainelPrincipal painel;
    private ServidorLab lab;
    private final AvisosGravados avisos = new AvisosGravados();
    private final DialogosGravados dialogos = new DialogosGravados();
    private String acoesAntes;

    @BeforeEach
    void montar() throws Exception {
        Visual.tema(Tema.CLARO);
        acoesAntes = System.getProperty(FluxosDeProjeto.PROPRIEDADE_ACOES);
        System.setProperty(FluxosDeProjeto.PROPRIEDADE_ACOES, "true"); // a trava das ações de escrita, ligada (é UMA flag)
        pc = new ComputadorSimulado();
        SondaFalsa sonda = new SondaFalsa();
        PlataformaLocal plataforma = new PlataformaLocal(SistemaOperacional.MAC, tmp.toString(), Map.of());
        cofre = CofreDeChaves.criar(CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs")), SENHA.clone(), ParametrosKdf.MINIMO);
        gerenciador = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida(), new ServicosLocais(pc, plataforma, sonda, ConfigLocal.rapida()));
        assistente = new AssistenteDeServidor(cofre, gerenciador, ConfigSsh.rapida());
        painel = Visual.naEdt(() -> new PainelPrincipal(gerenciador, assistente, avisos, dialogos, new NavegadorGravado(avisos), ServicosDeArquivos.descartaveis()));
        lab = assistente.cadastrarLocal("Este computador", 7070);
        Espera.ate(PRAZO, "o servidor local entrou na lista", () -> gerenciador.servidores().size() == 1);
        Visual.naEdt(() -> painel.mostrarServidor(lab));
    }

    @AfterEach
    void desmontar() throws Exception {
        if (acoesAntes == null) {
            System.clearProperty(FluxosDeProjeto.PROPRIEDADE_ACOES);
        } else {
            System.setProperty(FluxosDeProjeto.PROPRIEDADE_ACOES, acoesAntes);
        }
        Visual.naEdt(() -> painel.encerrar());
        gerenciador.fechar().join();
        cofre.close();
        Visual.tema(Tema.CLARO);
    }

    private Path raiz() {
        return tmp.resolve("LabLocal").resolve("projects");
    }

    private TelaServidor telaDoServidor() {
        return Visual.naEdt(() -> painel.navegacao().telaServidorDe(lab.id()));
    }

    private TelaProjetos listaDeProjetos() {
        TelaProjetos t = Visual.naEdt(() -> telaDoServidor().projetos());
        Visual.naEdt(t::assinar); // a lista só consulta com a tela à vista; sem janela, o teste faz o papel dela
        return t;
    }

    private static <T> T ed(java.util.function.Supplier<T> s) {
        return Visual.naEdt(s);
    }

    private void fotografarJanela(String nome) {
        for (Tema t : List.of(Tema.CLARO, Tema.ESCURO)) {
            Visual.tema(t);
            Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(painel));
            Visual.salvar(Visual.renderizar(painel, 1280, 700), nome + "-" + (t == Tema.CLARO ? "claro" : "escuro"));
        }
        Visual.tema(Tema.CLARO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(painel));
    }

    private DialogoNovoProjeto abrirNovoProjetoPeloConvite(TelaProjetos lista) {
        int antes = dialogos.mostrados.size();
        Visual.naEdt(() -> lista.botaoDoConvite().doClick());
        Espera.ate(PRAZO, "abriu o diálogo", () -> dialogos.mostrados.size() > antes);
        return dialogos.ultimoComo(DialogoNovoProjeto.class);
    }

    @Test
    void oLocalGanhaAAbaProjetosComOConviteQuandoVazio() throws Exception {
        TelaServidor tela = telaDoServidor();
        assertTrue(ed(tela::temProjetos), "o local tem projetos (as pastas do laboratório), mesmo com o agente de monitor");
        TelaProjetos lista = listaDeProjetos();
        Espera.ate(PRAZO, () -> "cartão=" + lista.cartaoAtual(), () -> "vazio".equals(lista.cartaoAtual()));
        Espera.ate(PRAZO, "o botão apareceu", () -> ed(() -> lista.botaoNovoProjeto().isVisible()));
        assertTrue(ed(() -> lista.botaoDoConvite().isVisible()));
        assertTrue(lista.textoDoVazio().contains(raiz().toString()), lista.textoDoVazio());
        assertEquals(List.of(), pc.escritas(), "nada foi criado só de olhar");
        Visual.naEdt(() -> tela.mostrarAba(TelaServidor.Aba.PROJETOS)); // a foto é da aba Projetos, com o convite (e não do Resumo)
        assertEquals(TelaServidor.Aba.PROJETOS, ed(tela::abaMarcada));
        fotografarJanela("janela-este-computador-projetos-vazio");
    }

    @Test
    void criarDoZeroNoLocalFazAPastaARedeSelecionaEAbreOTerminal() throws Exception {
        TelaProjetos lista = listaDeProjetos();
        Espera.ate(PRAZO, "convite", () -> ed(() -> lista.botaoDoConvite().isVisible()));
        DialogoNovoProjeto d = abrirNovoProjetoPeloConvite(lista);
        assertFalse(ed(() -> d.caixaGuiaIa().isVisible()), "o Claude não existe no laboratório local");
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.CRIAR));
        Visual.naEdt(() -> d.campoNome().setText("meu-app"));
        assertEquals("Será criado em: " + raiz().resolve("meu-app"), ed(d::textoDoCaminho));
        Visual.naEdt(() -> d.botaoPrincipal().doClick());

        TelaServidor tela = telaDoServidor();
        Espera.ate(PRAZO, () -> "página: " + ed(tela::projetoAberto), () -> ed(tela::projetoAberto) != null);
        TelaProjeto pagina = ed(tela::projetoAberto);
        assertEquals("meu-app", pagina.projeto());
        assertEquals(TelaProjeto.Aba.TERMINAL, ed(pagina::abaAtual), "vai direto para a aba Terminal (o shell já na pasta)");
        Path pasta = raiz().resolve("meu-app");
        try (Stream<Path> s = Files.list(pasta)) {
            assertEquals(List.of(".env.example", ".gitignore", "README.md", "STATUS.md", "docker-compose.yml"), s.map(p -> p.getFileName().toString()).sorted().toList());
        }
        assertTrue(Files.readString(pasta.resolve("docker-compose.yml"), StandardCharsets.UTF_8).contains("services: {}"));
        assertTrue(pc.comandos.stream().anyMatch(c -> c.equals(List.of("docker", "network", "create", "--label", "com.docker.compose.network=default", "--label",
            "com.docker.compose.project=meu-app", "meu-app_default"))), pc.comandos.toString());
        assertTrue(avisos.disse("ok", "Projeto meu-app criado."), avisos.todos.toString());
        assertFalse(avisos.todos.stream().anyMatch(a -> a.tipo().equals("acao")), "sem Claude, sem o botão de abrir o Claude");
        // a lista leu a pasta sozinha, sem esperar o ciclo de 10 s
        Espera.ate(PRAZO, () -> "lista: " + lista.nomesExibidos(), () -> ed(lista::nomesExibidos).contains("meu-app"));
        Espera.ate(PRAZO, "o usuário vê a página", () -> ed(() -> tela.abaAtual()) != null);
        fotografarJanela("janela-este-computador-projeto-criado");
    }

    @Test
    void nomeQueJaExisteNoLocalEUmaFraseENadaMudaNaPasta() throws Exception {
        Files.createDirectories(raiz().resolve("meu-app"));
        Files.writeString(raiz().resolve("meu-app").resolve("meu.txt"), "do usuário");
        TelaProjetos lista = listaDeProjetos();
        Espera.ate(PRAZO, "o botão apareceu", () -> ed(() -> lista.botaoNovoProjeto().isVisible()));
        int antes = dialogos.mostrados.size();
        Visual.naEdt(() -> lista.botaoNovoProjeto().doClick());
        Espera.ate(PRAZO, "abriu", () -> dialogos.mostrados.size() > antes);
        DialogoNovoProjeto d = dialogos.ultimoComo(DialogoNovoProjeto.class);
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.CRIAR));
        Visual.naEdt(() -> d.campoNome().setText("meu-app"));
        Visual.naEdt(() -> d.botaoPrincipal().doClick());
        Espera.ate(PRAZO, "falhou", () -> !ed(d::textoDaFalhaDeCriar).isEmpty());
        assertEquals("Já existe um projeto com esse nome.", ed(d::textoDaFalhaDeCriar));
        assertEquals("do usuário", Files.readString(raiz().resolve("meu-app").resolve("meu.txt")));
        try (Stream<Path> s = Files.list(raiz().resolve("meu-app"))) {
            assertEquals(1, s.count());
        }
        assertEquals(List.of(), pc.escritas());
    }

    @Test
    void importarUmaPastaDoComputadorNoLocalSemNodeModulesNemEnv() throws Exception {
        Path origem = tmp.resolve("Meu Codigo");
        Files.createDirectories(origem.resolve("src"));
        Files.createDirectories(origem.resolve("node_modules/x"));
        Files.writeString(origem.resolve("src/index.js"), "codigo");
        Files.writeString(origem.resolve("node_modules/x/y.js"), "pesado");
        Files.writeString(origem.resolve(".env"), "SENHA=1");
        TelaProjetos lista = listaDeProjetos();
        Espera.ate(PRAZO, "convite", () -> ed(() -> lista.botaoDoConvite().isVisible()));
        DialogoNovoProjeto d = abrirNovoProjetoPeloConvite(lista);
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.IMPORTAR));
        assertFalse(ed(() -> d.abasDeOrigem().tem(DialogoNovoProjeto.Origem.PASTA_DO_SERVIDOR.name())), "no local não há pasta do servidor");
        Visual.naEdt(() -> d.escolherAOrigem(DialogoNovoProjeto.Origem.PASTA_DO_COMPUTADOR));
        Visual.naEdt(() -> d.usarPastaDoComputador(origem));
        assertEquals("meu-codigo", ed(() -> d.campoNomeImportar().getText()));
        Visual.naEdt(() -> d.botaoPrincipal().doClick());
        Espera.ate(PRAZO, () -> "avisos: " + avisos.todos, () -> avisos.disse("ok", "Projeto meu-codigo importado."));
        Path destino = raiz().resolve("meu-codigo");
        assertEquals("codigo", Files.readString(destino.resolve("src/index.js")));
        assertFalse(Files.exists(destino.resolve("node_modules")));
        assertFalse(Files.exists(destino.resolve(".env")), ".env não vai por padrão");
        assertTrue(Files.exists(destino.resolve("docker-compose.yml")), "o compose vazio nasce porque a pasta não tinha");
        assertTrue(Files.exists(origem.resolve(".env")), "a pasta do usuário não foi tocada");
        try (Stream<Path> s = Files.list(raiz())) {
            assertEquals(List.of("meu-codigo"), s.map(p -> p.getFileName().toString()).toList(), "nenhuma pasta provisória ficou");
        }
        TelaServidor tela = telaDoServidor();
        Espera.ate(PRAZO, "abriu a página", () -> ed(tela::projetoAberto) != null && "meu-codigo".equals(ed(() -> tela.projetoAberto().projeto())));
    }

    @Test
    void adicionarContainerNoLocalMostraGravaConfereEIniciarEOutroClique() throws Exception {
        Files.createDirectories(raiz().resolve("meu-app"));
        Files.writeString(raiz().resolve("meu-app/docker-compose.yml"), br.com.labdesktop.projetos.GabaritoDoProjeto.composeVazio("meu-app"));
        AtomicInteger inicios = new AtomicInteger();
        Visual.naEdt(() -> painel.acoes().adicionarContainer(lab, "meu-app", inicios::incrementAndGet));
        DialogoAdicionarContainer d = dialogos.ultimoComo(DialogoAdicionarContainer.class);
        Espera.ate(PRAZO, () -> "prévia: " + ed(d::textoDaPrevia), () -> ed(d::textoDaPrevia).contains("+ services:"));
        Visual.naEdt(() -> d.escolherModelo(Modelo.REDIS));
        Espera.ate(PRAZO, "prévia do Redis", () -> ed(d::textoDaPrevia).contains("redis:7-alpine"));
        assertTrue(Files.readString(raiz().resolve("meu-app/docker-compose.yml")).contains("services: {}"), "ainda não gravou");
        Visual.naEdt(() -> d.botaoGravar().doClick());
        Espera.ate(PRAZO, () -> "falha=" + ed(d::textoDaFalha), () -> ed(d::gravado));
        String compose = Files.readString(raiz().resolve("meu-app/docker-compose.yml"));
        assertTrue(compose.contains("services:\n  redis:\n    image: redis:7-alpine"), compose);
        assertTrue(pc.comandos.stream().anyMatch(c -> c.size() > 4 && c.subList(0, 4).equals(List.of("docker", "compose", "--project-directory", raiz().resolve("meu-app").toString()))
            && c.contains("config")), "conferiu com o docker compose config: " + pc.comandos);
        assertEquals(0, inicios.get(), "gravar não inicia");
        Visual.naEdt(() -> d.botaoIniciar().doClick());
        assertEquals(1, inicios.get());
        try (Stream<Path> s = Files.list(raiz().resolve("meu-app"))) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().startsWith("docker-compose.yml.") && p.getFileName().toString().endsWith(".bak")), "cópia datada");
        }
        assertNotNull(compose);
    }

    @Test
    void iniciarEPararOProjetoLocalRodamOComposeNoComputadorSemOAgente() throws Exception {
        Files.createDirectories(raiz().resolve("meu-app"));
        Files.writeString(raiz().resolve("meu-app/docker-compose.yml"), "name: meu-app\nservices: {}\n");
        FluxosDeProjeto fluxos = Visual.naEdt(() -> new FluxosDeProjeto(lab, avisos, dialogos, true));
        Visual.naEdt(() -> fluxos.iniciar("meu-app"));
        Espera.ate(PRAZO, () -> "avisos: " + avisos.todos, () -> avisos.disse("ok", "meu-app: no ar."));
        assertTrue(pc.comandos.contains(List.of("docker", "compose", "-p", "meu-app", "-f", raiz().resolve("meu-app/docker-compose.yml").toString(), "up", "-d")), pc.comandos.toString());
        Visual.naEdt(() -> fluxos.parar("meu-app"));
        Espera.ate(PRAZO, () -> "avisos: " + avisos.todos, () -> avisos.disse("ok", "meu-app: parado."));
        assertTrue(pc.comandos.contains(List.of("docker", "compose", "-p", "meu-app", "-f", raiz().resolve("meu-app/docker-compose.yml").toString(), "down")));
    }

    private MenuApp menu() {
        return Visual.naEdt(() -> new MenuApp(null, painel, new GerenciadorTema(Tema.CLARO, false),
            new PreferenciasAtuais(CaminhosApp.comPasta(tmp.resolve("prefs")), br.com.labdesktop.util.Preferencias.padrao()), () -> { }, () -> { }));
    }

    @Test
    void oItemArquivoNovoProjetoUsaOModificadorDaPlataformaEAbreODialogoDoServidorEmFoco() throws Exception {
        MenuApp menu = menu();
        javax.swing.JMenuItem item = menu.itemNovoProjeto();
        assertEquals("Novo projeto…", Visual.naEdt(item::getText));
        assertEquals(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_N, br.com.labdesktop.ui.componentes.Teclas.comando() | java.awt.event.InputEvent.SHIFT_DOWN_MASK),
            Visual.naEdt(item::getAccelerator), "o menu usa a tecla de comando do SISTEMA (Cmd no Mac, Ctrl no Windows e no Linux), com ou sem tela");
        assertTrue(Visual.naEdt(item::isEnabled), "o servidor em foco (Este computador) cria projeto e as ações estão ligadas");
        int antes = dialogos.mostrados.size();
        Visual.naEdt(() -> item.doClick());
        Espera.ate(PRAZO, "abriu o diálogo", () -> dialogos.mostrados.size() > antes);
        assertTrue(dialogos.ultimo() instanceof DialogoNovoProjeto);
    }

    @Test
    void oItemAdicionarDominioFicaDesligadoEmEsteComputadorComAExplicacao() throws Exception {
        MenuApp menu = menu();
        javax.swing.JMenuItem item = menu.itemAdicionarDominio();
        Visual.naEdt(menu::atualizarHabilitacaoDoNovoProjeto);
        assertEquals("Adicionar domínio…", Visual.naEdt(item::getText));
        assertFalse(Visual.naEdt(item::isEnabled), "Este computador não tem túnel da Cloudflare configurado pelo app");
        assertTrue(Visual.naEdt(() -> item.getToolTipText()).contains("Este computador não tem um túnel"), Visual.naEdt(() -> item.getToolTipText()));
    }

    @Test
    void oItemDoMenuAcompanhaAMudancaDeTelaSemPrecisarAbrirOMenu() throws Exception {
        // o atalho de um item desabilitado não faz nada: a habilitação não pode esperar o menu ser aberto
        System.clearProperty(FluxosDeProjeto.PROPRIEDADE_ACOES);
        MenuApp menu = menu();
        assertFalse(Visual.naEdt(menu.itemNovoProjeto()::isEnabled));
        System.setProperty(FluxosDeProjeto.PROPRIEDADE_ACOES, "true");
        Visual.naEdt(() -> painel.mostrarServidores());
        Visual.naEdt(() -> painel.mostrarServidor(lab));
        assertTrue(Visual.naEdt(menu.itemNovoProjeto()::isEnabled), "trocou o servidor em foco: o item (e o atalho) passa a valer sem abrir o menu");
    }

    @Test
    void oItemDoMenuFicaDesabilitadoSemAsAcoes() throws Exception {
        System.clearProperty(FluxosDeProjeto.PROPRIEDADE_ACOES);
        MenuApp menu = menu();
        assertFalse(Visual.naEdt(menu.itemNovoProjeto()::isEnabled));
    }

    @Test
    void semAsAcoesLigadasNemBotaoNemConvite() throws Exception {
        System.clearProperty(FluxosDeProjeto.PROPRIEDADE_ACOES);
        painel = Visual.naEdt(() -> new PainelPrincipal(gerenciador, assistente, avisos, dialogos, new NavegadorGravado(avisos), ServicosDeArquivos.descartaveis()));
        Visual.naEdt(() -> painel.mostrarServidor(lab));
        TelaProjetos lista = listaDeProjetos();
        Espera.ate(PRAZO, () -> "cartão=" + lista.cartaoAtual(), () -> "vazio".equals(lista.cartaoAtual()));
        assertFalse(ed(() -> lista.botaoNovoProjeto().isVisible()));
        assertFalse(ed(() -> lista.botaoDoConvite().isVisible()));
        assertFalse(ed(painel::podeCriarProjetoNoServidorEmFoco), "nem o item de menu");
    }
}
