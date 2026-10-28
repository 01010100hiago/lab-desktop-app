package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.projetos.FalhaDoProjeto;
import br.com.labdesktop.projetos.FiltroDeEnvio;
import br.com.labdesktop.projetos.FluxoDeCriacao;
import br.com.labdesktop.projetos.ImportadorDeProjeto;
import br.com.labdesktop.projetos.UrlDeGit;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.MolduraDeJanela;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * "Novo projeto": a escolha, criar do zero (vazio, válido, inválido, erro do agente, enviando, clique duplo, prazo incerto) e importar (Git, pasta do
 * computador, pasta do servidor), contra um motor de mentira. Grava os PNGs dos momentos nos dois temas em {@code build/telas/}.
 */
@ExtendWith(VerificaEdt.class)
class DialogoNovoProjetoTest {

    private static final Duration PRAZO = Duration.ofSeconds(20);

    private final AvisosGravados avisos = new AvisosGravados();
    private final List<DialogoNovoProjeto.Concluido> concluidos = new CopyOnWriteArrayList<>();
    private final AtomicInteger criacoes = new AtomicInteger();
    private final List<String> chamadas = new CopyOnWriteArrayList<>();
    private final List<Boolean> guias = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch segurar = new CountDownLatch(0);
    private volatile FluxoDeCriacao.Desfecho desfecho;
    private volatile FalhaDoProjeto falhaNoImportar;
    private volatile List<ImportadorDeProjeto.Candidata> candidatas = List.of();
    private volatile Optional<Path> pastaEscolhida = Optional.empty();
    private volatile boolean local;
    private volatile boolean guiaIa;
    private volatile FiltroDeEnvio filtroRecebido;
    private volatile Cancelamento cancelamentoRecebido;
    private volatile boolean teimoso;

    private final DialogoNovoProjeto.Motor motor = new DialogoNovoProjeto.Motor() {
        @Override
        public boolean local() {
            return local;
        }

        @Override
        public boolean guiaIaDisponivel() {
            return guiaIa;
        }

        @Override
        public String pastaDosProjetos() {
            return "/srv/lab/projects";
        }

        @Override
        public String caminhoDoProjeto(String nome) {
            return "/srv/lab/projects/" + nome;
        }

        @Override
        public FluxoDeCriacao.Desfecho criar(String nome, Boolean guia) {
            criacoes.incrementAndGet();
            guias.add(guia);
            aguardar();
            return desfecho != null ? desfecho : new FluxoDeCriacao.Desfecho(FluxoDeCriacao.Tipo.CRIADO, nome, "Projeto " + nome + " criado.", List.of());
        }

        @Override
        public ImportadorDeProjeto.Resultado clonar(UrlDeGit url, String nome, ImportadorDeProjeto.Observador obs, Cancelamento cancelamento) throws FalhaDoProjeto {
            cancelamentoRecebido = cancelamento;
            chamadas.add("clonar " + url.texto() + " " + nome);
            obs.andamento("Clonando…", -1);
            aguardar();
            if (falhaNoImportar != null) {
                throw falhaNoImportar;
            }
            return new ImportadorDeProjeto.Resultado(nome, true, List.of("O projeto não tinha docker-compose.yml: criei um vazio."));
        }

        @Override
        public ImportadorDeProjeto.Resultado importarPasta(Path pasta, String nome, FiltroDeEnvio filtro, ImportadorDeProjeto.Observador obs,
                                                           Cancelamento cancelamento) throws FalhaDoProjeto {
            chamadas.add("pasta " + pasta + " " + nome);
            filtroRecebido = filtro;
            obs.andamento("Copiando…", 0.4);
            aguardar();
            if (falhaNoImportar != null) {
                throw falhaNoImportar;
            }
            return new ImportadorDeProjeto.Resultado(nome, false, List.of());
        }

        @Override
        public List<ImportadorDeProjeto.Candidata> candidatas() {
            return candidatas;
        }

        @Override
        public ImportadorDeProjeto.Resultado adotar(String origem, String nome, ImportadorDeProjeto.Modo modo, ImportadorDeProjeto.Observador obs,
                                                    Cancelamento cancelamento) throws FalhaDoProjeto {
            chamadas.add("adotar " + modo + " " + origem + " " + nome);
            aguardar();
            if (falhaNoImportar != null) {
                throw falhaNoImportar;
            }
            return new ImportadorDeProjeto.Resultado(nome, false, List.of());
        }

        @Override
        public Optional<Path> escolherPasta() {
            return pastaEscolhida;
        }

        private void aguardar() {
            long limite = System.nanoTime() + PRAZO.toNanos();
            while (segurar.getCount() > 0 && System.nanoTime() < limite) {
                try {
                    segurar.await(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    if (!teimoso) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    // uma tarefa que não solta (um comando remoto que demora a obedecer): o diálogo não pode ficar preso por causa dela
                }
            }
        }
    };

    @BeforeEach
    void preparar() {
        Visual.tema(Tema.CLARO);
    }

    @AfterEach
    void restaurar() {
        segurar.countDown();
        Visual.tema(Tema.CLARO);
    }

    private DialogoNovoProjeto abrir() {
        return Visual.naEdt(() -> new DialogoNovoProjeto(motor, avisos, new CopiadorDeTexto(() -> new Clipboard("teste")), concluidos::add));
    }

    private static void digitar(javax.swing.JTextField campo, String texto) {
        Visual.naEdt(() -> campo.setText(texto));
    }

    private void fotografar(DialogoNovoProjeto d, String nome) {
        Dialogo.definirAlturaDaTelaParaTeste(1500); // a foto mostra o diálogo inteiro, sem a rolagem de uma tela de notebook
        try {
            for (Tema t : List.of(Tema.CLARO, Tema.ESCURO)) {
                Visual.tema(t);
                Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(d.raiz()));
                Visual.salvar(Visual.renderizar(d.raiz(), 580, Visual.alturaPreferida(d.raiz(), 580)), nome + "-" + (t == Tema.CLARO ? "claro" : "escuro"));
                if (nome.equals("dialogo-novo-projeto-escolha")) { // galeria do README
                    Visual.salvarNaGaleria(Visual.renderizar(d.raiz(), 580, Visual.alturaPreferida(d.raiz(), 580)), "novo-projeto-" + (t == Tema.CLARO ? "claro" : "escuro"),
                        d.titulo(), t == Tema.ESCURO, MolduraDeJanela.Tipo.DIALOGO);
                }
            }
        } finally {
            Dialogo.definirAlturaDaTelaParaTeste(0);
            Visual.tema(Tema.CLARO);
            Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(d.raiz()));
        }
    }

    private static <T> T ed(java.util.function.Supplier<T> s) {
        return Visual.naEdt(s);
    }

    // ---------------------------------------------------------------------------------------------
    // a escolha
    // ---------------------------------------------------------------------------------------------

    @Test
    void aEscolhaTemTresCartoesESoCancelarNoRodape() {
        DialogoNovoProjeto d = abrir();
        assertEquals(DialogoNovoProjeto.Pagina.ESCOLHER, d.paginaAtual());
        assertEquals(List.of("Cancelar"), ed(d::botoesVisiveis));
        assertTrue(textosDaTela(d).contains("Criar do zero"));
        assertTrue(textosDaTela(d).contains("Importar um projeto que já existe"));
        assertTrue(textosDaTela(d).contains("Adicionar um container a um projeto"));
        assertTrue(textosDaTela(d).contains("git clone no servidor"), "no servidor o texto fala do servidor");
        assertTrue(concluidos.isEmpty());
        assertEquals(0, criacoes.get(), "só abrir não cria nada");
        fotografar(d, "dialogo-novo-projeto-escolha");
    }

    @Test
    void noLocalOTextoFalaDoComputador() {
        local = true;
        DialogoNovoProjeto d = abrir();
        assertFalse(textosDaTela(d).contains("no servidor"), textosDaTela(d));
    }

    private static String textosDaTela(DialogoNovoProjeto d) {
        StringBuilder sb = new StringBuilder();
        Visual.naEdt(() -> coletar(d.raiz(), sb));
        return sb.toString();
    }

    private static void coletar(java.awt.Component c, StringBuilder sb) {
        if (!c.isVisible()) {
            return; // só o que o usuário vê (os cartões das outras páginas ficam escondidos)
        }
        if (c instanceof javax.swing.text.JTextComponent t) {
            sb.append(t.getText()).append('\n');
        } else if (c instanceof javax.swing.JLabel l) {
            sb.append(l.getText()).append('\n');
        } else if (c instanceof javax.swing.AbstractButton b) {
            sb.append(b.getText()).append('\n');
        }
        if (c instanceof java.awt.Container k) {
            for (java.awt.Component f : k.getComponents()) {
                coletar(f, sb);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // criar do zero
    // ---------------------------------------------------------------------------------------------

    @Test
    void criarVazioEMostraOndeEOQueVemPronto() {
        DialogoNovoProjeto d = abrir();
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.CRIAR));
        assertEquals(List.of("Voltar", "Cancelar", "Criar projeto"), ed(d::botoesVisiveis));
        assertFalse(ed(() -> d.botaoPrincipal().isEnabled()), "campo vazio: botão desabilitado");
        assertEquals("", ed(d::textoDoErroDoNome), "vazio não é erro");
        assertEquals("Será criado em: /srv/lab/projects/<nome>", ed(d::textoDoCaminho));
        assertTrue(ed(d::textoDoQueVemPronto).contains("docker-compose.yml vazio"));
        assertTrue(ed(d::textoDoQueVemPronto).contains("a rede do projeto (<nome>_default)"));
        assertFalse(ed(d::textoDoQueVemPronto).contains("CLAUDE.md"));
        assertFalse(ed(() -> d.caixaGuiaIa().isVisible()), "sem Claude no servidor, nada de guia");
        fotografar(d, "dialogo-novo-projeto-criar-vazio");
    }

    @Test
    void nomeValidoHabilitaEMostraOCaminho() {
        DialogoNovoProjeto d = abrir();
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.CRIAR));
        digitar(d.campoNome(), "meu-app");
        assertTrue(ed(() -> d.botaoPrincipal().isEnabled()));
        assertEquals("", ed(d::textoDoErroDoNome));
        assertEquals("Será criado em: /srv/lab/projects/meu-app", ed(d::textoDoCaminho));
        assertTrue(ed(d::textoDoQueVemPronto).contains("meu-app_default"));
        fotografar(d, "dialogo-novo-projeto-criar-valido");
    }

    @Test
    void nomeInvalidoDizOQueEstaErradoEDesabilita() {
        DialogoNovoProjeto d = abrir();
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.CRIAR));
        digitar(d.campoNome(), "Meu App");
        assertFalse(ed(() -> d.botaoPrincipal().isEnabled()));
        assertTrue(ed(d::textoDoErroDoNome).startsWith("Sem espaços"), ed(d::textoDoErroDoNome));
        digitar(d.campoNome(), "Meu-App");
        assertEquals("Use só letras minúsculas (sem maiúsculas).", ed(d::textoDoErroDoNome));
        digitar(d.campoNome(), "abc-");
        assertEquals("O nome não pode terminar com hífen.", ed(d::textoDoErroDoNome));
        digitar(d.campoNome(), "a".repeat(50));
        assertTrue(ed(d::textoDoErroDoNome).startsWith("No máximo 49"));
        digitar(d.campoNome(), "../x");
        assertFalse(ed(() -> d.botaoPrincipal().isEnabled()));
        digitar(d.campoNome(), "Meu App");
        fotografar(d, "dialogo-novo-projeto-criar-invalido");
        digitar(d.campoNome(), "ok");
        assertTrue(ed(() -> d.botaoPrincipal().isEnabled()));
        assertEquals("", ed(d::textoDoErroDoNome));
        assertEquals(0, criacoes.get());
    }

    @Test
    void reservadoNoLocalEBarradoNaTelaENoServidorFicaComOAgente() {
        local = true;
        DialogoNovoProjeto d = abrir();
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.CRIAR));
        digitar(d.campoNome(), "painel");
        assertFalse(ed(() -> d.botaoPrincipal().isEnabled()));
        assertEquals("Esse nome é reservado pela infraestrutura do lab.", ed(d::textoDoErroDoNome));
        local = false;
        DialogoNovoProjeto d2 = abrir();
        Visual.naEdt(() -> d2.escolherAPagina(DialogoNovoProjeto.Pagina.CRIAR));
        digitar(d2.campoNome(), "painel");
        assertTrue(ed(() -> d2.botaoPrincipal().isEnabled()), "no servidor o app não inventa a lista de reservados: quem diz é o agente");
    }

    @Test
    void aCaixaDoGuiaSoApareceQuandoOServidorEntendeEVaiComoMarcada() {
        guiaIa = true;
        DialogoNovoProjeto d = abrir();
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.CRIAR));
        assertTrue(ed(() -> d.caixaGuiaIa().isVisible()));
        assertTrue(ed(() -> d.caixaGuiaIa().isSelected()), "marcada por padrão");
        assertTrue(ed(d::textoDoQueVemPronto).contains("CLAUDE.md"));
        digitar(d.campoNome(), "meu-app");
        fotografar(d, "dialogo-novo-projeto-criar-com-guia");
        Visual.naEdt(() -> d.caixaGuiaIa().doClick());
        assertFalse(ed(d::textoDoQueVemPronto).contains("CLAUDE.md"));
        Visual.naEdt(() -> d.botaoPrincipal().doClick());
        Espera.ate(PRAZO, "criou", () -> criacoes.get() == 1);
        assertEquals(List.of(false), guias, "a escolha do usuário vai ao agente");
    }

    @Test
    void semAcaixaOGuiaNemVai() {
        DialogoNovoProjeto d = abrir();
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.CRIAR));
        digitar(d.campoNome(), "meu-app");
        Visual.naEdt(() -> d.botaoPrincipal().doClick());
        Espera.ate(PRAZO, "criou", () -> criacoes.get() == 1);
        assertNull(guias.get(0));
    }

    @Test
    void criarFechaEEntregaOProjeto() {
        DialogoNovoProjeto d = abrir();
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.CRIAR));
        digitar(d.campoNome(), "meu-app");
        Visual.naEdt(() -> d.botaoPrincipal().doClick());
        Espera.ate(PRAZO, "terminou", () -> !concluidos.isEmpty());
        assertEquals("meu-app", concluidos.get(0).nome());
        assertEquals("Projeto meu-app criado.", concluidos.get(0).mensagem());
        assertFalse(concluidos.get(0).talvez());
    }

    @Test
    void enviandoOBotaoFicaOcupadoEOCliqueDuploNaoCriaDuasVezes() throws Exception {
        segurar = new CountDownLatch(1);
        DialogoNovoProjeto d = abrir();
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.CRIAR));
        digitar(d.campoNome(), "meu-app");
        Visual.naEdt(() -> {
            d.botaoPrincipal().doClick();
            d.botaoPrincipal().doClick(); // o segundo clique, rápido
        });
        Espera.ate(PRAZO, "o pedido saiu", () -> criacoes.get() >= 1);
        assertTrue(ed(d::ocupado));
        assertFalse(ed(() -> d.botaoPrincipal().isEnabled()), "desabilitado enquanto envia");
        assertTrue(ed(() -> d.botaoPrincipal().ocupado()));
        assertFalse(ed(() -> d.campoNome().isEnabled()));
        assertFalse(ed(() -> d.botaoVoltar().isEnabled()));
        assertTrue(ed(() -> d.botaoCancelar().isEnabled()), "a janela nunca fica presa: o botão fecha mesmo com o pedido em voo");
        assertEquals("Fechar mesmo assim", ed(() -> d.botaoCancelar().getText()), "o pedido já saiu e não se desfaz: só se fecha a janela");
        fotografar(d, "dialogo-novo-projeto-criar-enviando");
        Espera.naoAcontece(Duration.ofMillis(300), "um segundo pedido", () -> criacoes.get() > 1);
        segurar.countDown();
        Espera.ate(PRAZO, "terminou", () -> !concluidos.isEmpty());
        assertEquals(1, criacoes.get(), "uma criação só");
        assertEquals(1, concluidos.size());
    }

    @Test
    void erroDoAgenteApareceComoTextoEOBotaoVolta() {
        desfecho = new FluxoDeCriacao.Desfecho(FluxoDeCriacao.Tipo.FALHOU, "meu-app", "Já existe um projeto com esse nome.", List.of());
        DialogoNovoProjeto d = abrir();
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.CRIAR));
        digitar(d.campoNome(), "meu-app");
        Visual.naEdt(() -> d.botaoPrincipal().doClick());
        Espera.ate(PRAZO, "falhou", () -> !ed(d::textoDaFalhaDeCriar).isEmpty());
        assertEquals("Já existe um projeto com esse nome.", ed(d::textoDaFalhaDeCriar));
        assertFalse(ed(d::ocupado));
        assertTrue(ed(() -> d.botaoPrincipal().isEnabled()), "o usuário corrige e tenta de novo");
        assertTrue(concluidos.isEmpty());
        fotografar(d, "dialogo-novo-projeto-criar-erro-do-agente");
        // trocar o nome limpa o erro de antes
        digitar(d.campoNome(), "meu-app-2");
        assertEquals("", ed(d::textoDaFalhaDeCriar));
    }

    @Test
    void erroComHtmlNuncaViraHtml() {
        desfecho = new FluxoDeCriacao.Desfecho(FluxoDeCriacao.Tipo.FALHOU, "x", "<html><b>oi</b>", List.of());
        DialogoNovoProjeto d = abrir();
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.CRIAR));
        digitar(d.campoNome(), "x");
        Visual.naEdt(() -> d.botaoPrincipal().doClick());
        Espera.ate(PRAZO, "falhou", () -> !ed(d::textoDaFalhaDeCriar).isEmpty());
        assertFalse(ed(d::textoDaFalhaDeCriar).startsWith("<html"), ed(d::textoDaFalhaDeCriar));
    }

    @Test
    void prazoIncertoMandaConferirAListaENaoOferecePepetir() {
        desfecho = new FluxoDeCriacao.Desfecho(FluxoDeCriacao.Tipo.TALVEZ, "meu-app",
            "O servidor não respondeu a tempo e o projeto meu-app ainda não aparece na lista. Talvez já tenha sido criado: confira a lista antes de tentar de novo.", List.of());
        DialogoNovoProjeto d = abrir();
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.CRIAR));
        digitar(d.campoNome(), "meu-app");
        Visual.naEdt(() -> d.botaoPrincipal().doClick());
        Espera.ate(PRAZO, "incerto", () -> !ed(d::textoDoAvisoIncerto).isEmpty());
        assertTrue(ed(d::textoDoAvisoIncerto).contains("Talvez já tenha sido criado"));
        assertEquals("Ver a lista", ed(() -> d.botaoPrincipal().getText()));
        assertFalse(ed(() -> d.campoNome().isEnabled()), "não deixa recriar com o mesmo nome");
        fotografar(d, "dialogo-novo-projeto-criar-incerto");
        Visual.naEdt(() -> d.botaoPrincipal().doClick());
        assertEquals(1, concluidos.size());
        assertTrue(concluidos.get(0).talvez());
        assertEquals(1, criacoes.get(), "NÃO repetiu o pedido");
    }

    @Test
    void voltarEEscVoltamParaAEscolha() {
        DialogoNovoProjeto d = abrir();
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.CRIAR));
        Visual.naEdt(() -> d.botaoVoltar().doClick());
        assertEquals(DialogoNovoProjeto.Pagina.ESCOLHER, d.paginaAtual());
        assertEquals(List.of("Cancelar"), ed(d::botoesVisiveis));
    }

    // ---------------------------------------------------------------------------------------------
    // importar
    // ---------------------------------------------------------------------------------------------

    @Test
    void importarDoGitValidaOEnderecoESugereONome() {
        DialogoNovoProjeto d = abrir();
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.IMPORTAR));
        assertEquals(DialogoNovoProjeto.Origem.GIT, d.origemAtual());
        assertFalse(ed(() -> d.botaoPrincipal().isEnabled()));
        assertEquals("Importar projeto", ed(() -> d.botaoPrincipal().getText()));
        fotografar(d, "dialogo-novo-projeto-importar-git-vazio");
        for (String ruim : List.of("file:///etc/passwd", "ext::sh -c x", "https://u:s@github.com/a/b.git", "http://github.com/a/b.git", "https://github.com/a/b; rm -rf /",
            "-oProxyCommand=x", "texto qualquer")) {
            digitar(d.campoUrl(), ruim);
            assertFalse(ed(() -> d.botaoPrincipal().isEnabled()), ruim);
            assertFalse(ed(d::textoDoErroDaUrl).isEmpty(), ruim);
        }
        digitar(d.campoUrl(), "https://github.com/usuario/Meu_Repo.git");
        assertEquals("", ed(d::textoDoErroDaUrl));
        assertEquals("meu-repo", ed(() -> d.campoNomeImportar().getText()), "o nome sai do repositório");
        assertTrue(ed(() -> d.botaoPrincipal().isEnabled()));
        assertTrue(ed(d::textoDoQueVaiAcontecer).contains("git clone https://github.com/usuario/Meu_Repo.git"));
        assertTrue(ed(d::textoDoQueVaiAcontecer).contains("SÓ se o projeto não tiver nenhum"));
        assertEquals("Será criado em: /srv/lab/projects/meu-repo", ed(d::textoDoCaminhoDaImportacao));
        fotografar(d, "dialogo-novo-projeto-importar-git");
        // o nome que o usuário escreveu não é trocado pela sugestão
        digitar(d.campoNomeImportar(), "meu-nome");
        digitar(d.campoUrl(), "https://github.com/usuario/outro.git");
        assertEquals("meu-nome", ed(() -> d.campoNomeImportar().getText()));
        digitar(d.campoNomeImportar(), "Nome Ruim");
        assertFalse(ed(() -> d.botaoPrincipal().isEnabled()));
        assertTrue(ed(d::textoDoErroDoNomeImportar).startsWith("Sem espaços"));
    }

    @Test
    void importarDoGitClonaEFecha() {
        DialogoNovoProjeto d = abrir();
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.IMPORTAR));
        digitar(d.campoUrl(), "git@github.com:usuario/repo.git");
        Visual.naEdt(() -> d.botaoPrincipal().doClick());
        Espera.ate(PRAZO, "importou", () -> !concluidos.isEmpty());
        assertEquals(List.of("clonar git@github.com:usuario/repo.git repo"), chamadas);
        assertEquals("repo", concluidos.get(0).nome());
        assertEquals("Projeto repo importado.", concluidos.get(0).mensagem());
        assertEquals(1, concluidos.get(0).avisos().size());
    }

    @Test
    void importarEmAndamentoMostraProgressoEPararDesfaz() {
        segurar = new CountDownLatch(1);
        DialogoNovoProjeto d = abrir();
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.IMPORTAR));
        digitar(d.campoUrl(), "https://github.com/usuario/repo.git");
        Visual.naEdt(() -> {
            d.botaoPrincipal().doClick();
            d.botaoPrincipal().doClick();
        });
        Espera.ate(PRAZO, "começou", () -> chamadas.size() == 1);
        Espera.ate(PRAZO, "progresso na tela", () -> !ed(d::textoDoProgresso).isEmpty());
        assertTrue(ed(d::textoDoProgresso).startsWith("Clonando"));
        assertEquals("Parar", ed(() -> d.botaoCancelar().getText()));
        assertTrue(ed(() -> d.botaoCancelar().isEnabled()));
        assertFalse(ed(() -> d.botaoPrincipal().isEnabled()));
        fotografar(d, "dialogo-novo-projeto-importar-andando");
        assertEquals(1, chamadas.size(), "clique duplo: um clone só");
        segurar.countDown();
        Espera.ate(PRAZO, "terminou", () -> !concluidos.isEmpty());
        assertEquals(1, chamadas.size());
    }

    @Test
    void pararPedeCancelamentoDesdeOPrimeiroInstanteEOSegundoCliqueFechaMesmoAssim() {
        teimoso = true;
        segurar = new CountDownLatch(1);
        DialogoNovoProjeto d = abrir();
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.IMPORTAR));
        digitar(d.campoUrl(), "https://github.com/usuario/repo.git");
        Visual.naEdt(() -> d.botaoPrincipal().doClick());
        Espera.ate(PRAZO, "começou", () -> chamadas.size() == 1);
        assertFalse(cancelamentoRecebido.cancelado());
        Visual.naEdt(() -> d.botaoCancelar().doClick()); // Parar
        assertTrue(cancelamentoRecebido.cancelado(), "o pedido de parar chega a quem clona (vale para o git também, não só para a cópia)");
        assertEquals("Fechar mesmo assim", ed(() -> d.botaoCancelar().getText()));
        assertTrue(ed(() -> d.botaoCancelar().isEnabled()));
        assertFalse(ed(d::fechou), "o primeiro clique só pede para parar");
        Visual.naEdt(() -> d.botaoCancelar().doClick()); // o segundo fecha
        assertTrue(ed(d::fechou), "a janela nunca fica presa");
        assertTrue(avisos.textos().stream().anyMatch(t -> t.contains("segundo plano")), avisos.textos().toString());
        falhaNoImportar = new FalhaDoProjeto(FalhaDoProjeto.Tipo.CANCELADO, "Cancelado. O que estava pela metade foi apagado.");
        segurar.countDown();
        Espera.ate(PRAZO, "o aviso do fim, já com a janela fechada", () -> avisos.textos().stream().anyMatch(t -> t.contains("Importar repo")));
        assertTrue(concluidos.isEmpty(), "cancelado não conclui nada");
    }

    @Test
    void escETambemFechamUmaCriacaoEmVooEOResultadoChegaDepois() {
        segurar = new CountDownLatch(1);
        DialogoNovoProjeto d = abrir();
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.CRIAR));
        digitar(d.campoNome(), "meu-app");
        Visual.naEdt(() -> d.botaoPrincipal().doClick());
        Espera.ate(PRAZO, "o pedido saiu", () -> criacoes.get() >= 1);
        Visual.naEdt(() -> d.botaoCancelar().doClick());
        assertTrue(ed(d::fechou), "criando: o pedido não se desfaz, mas a janela fecha");
        assertTrue(avisos.textos().stream().anyMatch(t -> t.contains("pode ser criado")), avisos.textos().toString());
        segurar.countDown();
        Espera.ate(PRAZO, "o resultado chegou pela janela principal", () -> !concluidos.isEmpty());
        assertEquals("meu-app", concluidos.get(0).nome());
        assertEquals(1, criacoes.get());
    }

    @Test
    void importarComFalhaMostraAFraseDoGit() {
        falhaNoImportar = new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, "Não consegui acessar o repositório. Se ele é privado, deixe o servidor logado nele.");
        DialogoNovoProjeto d = abrir();
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.IMPORTAR));
        digitar(d.campoUrl(), "https://github.com/usuario/privado.git");
        Visual.naEdt(() -> d.botaoPrincipal().doClick());
        Espera.ate(PRAZO, "falhou", () -> !ed(d::textoDaFalhaDeImportar).isEmpty());
        assertTrue(ed(d::textoDaFalhaDeImportar).contains("privado"));
        assertFalse(ed(d::ocupado));
        assertTrue(ed(() -> d.botaoPrincipal().isEnabled()), "pode tentar de novo");
        assertEquals("", ed(d::comandoMostrado));
        assertTrue(concluidos.isEmpty());
        fotografar(d, "dialogo-novo-projeto-importar-erro");
    }

    @Test
    void importarPastaDoComputadorPadraoNaoLevaGitNemEnv() {
        pastaEscolhida = Optional.of(Path.of("/tmp/Meu Codigo"));
        DialogoNovoProjeto d = abrir();
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.IMPORTAR));
        Visual.naEdt(() -> d.escolherAOrigem(DialogoNovoProjeto.Origem.PASTA_DO_COMPUTADOR));
        assertFalse(ed(() -> d.caixaLevarGit().isSelected()));
        assertFalse(ed(() -> d.caixaLevarEnv().isSelected()), ".env NÃO vai por padrão");
        assertFalse(ed(() -> d.botaoPrincipal().isEnabled()), "sem pasta escolhida");
        Visual.naEdt(() -> d.botaoEscolherPasta().doClick());
        assertEquals("meu-codigo", ed(() -> d.campoNomeImportar().getText()));
        assertTrue(ed(() -> d.botaoPrincipal().isEnabled()));
        String resumo = ed(d::textoDoQueVaiAcontecer);
        assertTrue(resumo.contains("sem node_modules"), resumo);
        assertTrue(resumo.contains("sem .git"), resumo);
        assertTrue(resumo.contains("sem os arquivos de segredos"), resumo);
        fotografar(d, "dialogo-novo-projeto-importar-pasta");
        Visual.naEdt(() -> d.botaoPrincipal().doClick());
        Espera.ate(PRAZO, "importou", () -> !concluidos.isEmpty());
        assertEquals(new FiltroDeEnvio(false, false), filtroRecebido);
        assertEquals(List.of("pasta /tmp/Meu Codigo meu-codigo"), chamadas);
    }

    @Test
    void importarPastaComGitEEnvMarcadosAvisaNoResumoEVaiNoFiltro() {
        pastaEscolhida = Optional.of(Path.of("/tmp/app"));
        DialogoNovoProjeto d = abrir();
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.IMPORTAR));
        Visual.naEdt(() -> d.escolherAOrigem(DialogoNovoProjeto.Origem.PASTA_DO_COMPUTADOR));
        Visual.naEdt(() -> d.botaoEscolherPasta().doClick());
        Visual.naEdt(() -> {
            d.caixaLevarGit().doClick();
            d.caixaLevarEnv().doClick();
        });
        String resumo = ed(d::textoDoQueVaiAcontecer);
        assertFalse(resumo.contains("sem .git"), resumo);
        assertTrue(resumo.contains("COM os arquivos de segredos"), resumo);
        Visual.naEdt(() -> d.botaoPrincipal().doClick());
        Espera.ate(PRAZO, "importou", () -> !concluidos.isEmpty());
        assertEquals(new FiltroDeEnvio(true, true), filtroRecebido);
    }

    @Test
    void escolherPastaCancelandoNaoMudaNada() {
        pastaEscolhida = Optional.empty();
        DialogoNovoProjeto d = abrir();
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.IMPORTAR));
        Visual.naEdt(() -> d.escolherAOrigem(DialogoNovoProjeto.Origem.PASTA_DO_COMPUTADOR));
        Visual.naEdt(() -> d.botaoEscolherPasta().doClick());
        assertFalse(ed(() -> d.botaoPrincipal().isEnabled()));
        assertEquals("", ed(() -> d.campoNomeImportar().getText()));
    }

    @Test
    void importarPastaDoServidorListaMostraOComandoEOfereceMoverOuCopiar() {
        candidatas = List.of(new ImportadorDeProjeto.Candidata("meuapp", "/home/ana/meuapp"), new ImportadorDeProjeto.Candidata("outro", "/home/ana/outro"));
        DialogoNovoProjeto d = abrir();
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.IMPORTAR));
        Visual.naEdt(() -> d.escolherAOrigem(DialogoNovoProjeto.Origem.PASTA_DO_SERVIDOR));
        Espera.ate(PRAZO, "carregou as pastas", () -> ed(() -> d.listaDeCandidatas().getItemCount()) == 2);
        assertEquals("meuapp", ed(() -> d.campoNomeImportar().getText()));
        assertTrue(ed(() -> d.opcaoCopiar().isSelected()), "o padrão é copiar: a pasta de antes não some");
        String resumo = ed(d::textoDoQueVaiAcontecer);
        assertTrue(resumo.contains("Copia /home/ana/meuapp para /srv/lab/projects/meuapp"), resumo);
        assertTrue(resumo.contains("Comando: cp -aT -- /home/ana/meuapp /srv/lab/projects/meuapp"), resumo);
        Visual.naEdt(() -> d.opcaoMover().doClick());
        assertTrue(ed(d::textoDoQueVaiAcontecer).contains("Comando: mv -nT -- /home/ana/meuapp /srv/lab/projects/meuapp"));
        fotografar(d, "dialogo-novo-projeto-importar-servidor");
        Visual.naEdt(() -> d.botaoPrincipal().doClick());
        Espera.ate(PRAZO, "adotou", () -> !concluidos.isEmpty());
        assertEquals(List.of("adotar MOVER /home/ana/meuapp meuapp"), chamadas);
    }

    @Test
    void caminhoEscritoVenceALista() {
        candidatas = List.of(new ImportadorDeProjeto.Candidata("meuapp", "/home/ana/meuapp"));
        DialogoNovoProjeto d = abrir();
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.IMPORTAR));
        Visual.naEdt(() -> d.escolherAOrigem(DialogoNovoProjeto.Origem.PASTA_DO_SERVIDOR));
        Espera.ate(PRAZO, "carregou", () -> ed(() -> d.listaDeCandidatas().getItemCount()) == 1);
        digitar(d.campoOutraPasta(), "/srv/outra/pasta");
        assertTrue(ed(d::textoDoQueVaiAcontecer).contains("/srv/outra/pasta"));
        assertEquals("pasta", ed(() -> d.campoNomeImportar().getText()), "a sugestão acompanha a pasta, enquanto o usuário não escreveu o nome dele");
    }

    @Test
    void semPermissaoMostraOComandoExatoParaCopiar() {
        candidatas = List.of(new ImportadorDeProjeto.Candidata("meuapp", "/home/ana/meuapp"));
        falhaNoImportar = new FalhaDoProjeto(FalhaDoProjeto.Tipo.SEM_PERMISSAO, "O servidor não deixou o seu usuário mover essa pasta. Quem tem a permissão pode rodar, no servidor, o comando abaixo.",
            "mv -n -- /home/ana/meuapp /srv/lab/projects/meuapp");
        DialogoNovoProjeto d = abrir();
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.IMPORTAR));
        Visual.naEdt(() -> d.escolherAOrigem(DialogoNovoProjeto.Origem.PASTA_DO_SERVIDOR));
        Espera.ate(PRAZO, "carregou", () -> ed(() -> d.listaDeCandidatas().getItemCount()) == 1);
        Visual.naEdt(() -> d.botaoPrincipal().doClick());
        Espera.ate(PRAZO, "falhou", () -> !ed(d::textoDaFalhaDeImportar).isEmpty());
        assertEquals("mv -n -- /home/ana/meuapp /srv/lab/projects/meuapp", ed(d::comandoMostrado));
        fotografar(d, "dialogo-novo-projeto-importar-sem-permissao");
    }

    @Test
    void noLocalNaoHaPastaDoServidor() {
        local = true;
        DialogoNovoProjeto d = abrir();
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.IMPORTAR));
        assertTrue(ed(() -> d.abasDeOrigem().tem(DialogoNovoProjeto.Origem.GIT.name())));
        assertTrue(ed(() -> d.abasDeOrigem().tem(DialogoNovoProjeto.Origem.PASTA_DO_COMPUTADOR.name())));
        assertFalse(ed(() -> d.abasDeOrigem().tem(DialogoNovoProjeto.Origem.PASTA_DO_SERVIDOR.name())));
        assertTrue(textosDaTela(d).contains("este computador precisa estar logado"));
        assertNotNull(d);
    }
}
