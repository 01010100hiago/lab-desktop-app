package br.com.labdesktop.ui.galeria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.acesso.DesligadorDeSenha;
import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.instalador.GanchosFalsos;
import br.com.labdesktop.instalador.InstaladorDeAgente;
import br.com.labdesktop.instalador.PacoteDoAgente;
import br.com.labdesktop.instalador.ParametrosDaInstalacao;
import br.com.labdesktop.projetos.AmbienteFalso;
import br.com.labdesktop.projetos.BlocoDeServico;
import br.com.labdesktop.projetos.EscritorDeCompose;
import br.com.labdesktop.projetos.FalhaDoProjeto;
import br.com.labdesktop.projetos.FiltroDeEnvio;
import br.com.labdesktop.projetos.FluxoDeCriacao;
import br.com.labdesktop.projetos.ImportadorDeProjeto;
import br.com.labdesktop.projetos.ModelosDeContainer.Modelo;
import br.com.labdesktop.projetos.UrlDeGit;
import br.com.labdesktop.teste.ServidorSimulado;
import br.com.labdesktop.teste.SshdFalso;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.dialogos.DialogoAdicionarContainer;
import br.com.labdesktop.ui.dialogos.DialogoDesligarSenha;
import br.com.labdesktop.ui.dialogos.DialogoDoAgente;
import br.com.labdesktop.ui.dialogos.DialogoNovoProjeto;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Imagens da galeria do README dos diálogos de projeto e de servidor: "Novo projeto" (criar, importar por Git e por pasta), "Adicionar container" (com a diferença do compose antes
 * de gravar), a confirmação de instalar o agente e "Desligar o login por senha". São os diálogos de verdade com um motor de mentira (servidor simulado, ambiente falso, sshd falso) e
 * dados inventados (servidor-demo, loja-web, ana). Nada é criado, gravado nem instalado.
 */
@ExtendWith(VerificaEdt.class)
@org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // usam o shell, o SFTP e as permissões de um servidor Linux de mentira
class GaleriaProjetoDialogosTest {

    private static final Duration PRAZO = Duration.ofSeconds(25);
    private static final Clock RELOGIO = Clock.fixed(Instant.parse("2026-10-28T15:30:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tmp;

    private final AvisosGravados avisos = new AvisosGravados();
    private final Clipboard area = new Clipboard("galeria");
    private final List<String> chamadas = new CopyOnWriteArrayList<>();

    @BeforeEach
    void preparar() {
        Visual.tema(Tema.CLARO);
    }

    @AfterEach
    void restaurar() {
        Visual.tema(Tema.CLARO);
        DialogoDoAgente.definirPrazoDaTravaDaConfirmacaoParaTeste(600);
    }

    private static <T> T ed(java.util.function.Supplier<T> s) {
        return Visual.naEdt(s);
    }

    // ---------------------------------------------------------------------------------------------
    // Novo projeto
    // ---------------------------------------------------------------------------------------------

    private final DialogoNovoProjeto.Motor motorDoNovoProjeto = new DialogoNovoProjeto.Motor() {
        @Override
        public boolean local() {
            return false;
        }

        @Override
        public boolean guiaIaDisponivel() {
            return false;
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
            chamadas.add("criar " + nome);
            return new FluxoDeCriacao.Desfecho(FluxoDeCriacao.Tipo.CRIADO, nome, "Projeto " + nome + " criado.", List.of());
        }

        @Override
        public ImportadorDeProjeto.Resultado clonar(UrlDeGit url, String nome, ImportadorDeProjeto.Observador obs, Cancelamento cancelamento) {
            chamadas.add("clonar " + url.texto());
            return new ImportadorDeProjeto.Resultado(nome, false, List.of());
        }

        @Override
        public ImportadorDeProjeto.Resultado importarPasta(Path pasta, String nome, FiltroDeEnvio filtro, ImportadorDeProjeto.Observador obs, Cancelamento cancelamento) {
            chamadas.add("pasta " + pasta);
            return new ImportadorDeProjeto.Resultado(nome, false, List.of());
        }

        @Override
        public List<ImportadorDeProjeto.Candidata> candidatas() {
            return List.of();
        }

        @Override
        public ImportadorDeProjeto.Resultado adotar(String origem, String nome, ImportadorDeProjeto.Modo modo, ImportadorDeProjeto.Observador obs, Cancelamento cancelamento) {
            chamadas.add("adotar " + origem);
            return new ImportadorDeProjeto.Resultado(nome, false, List.of());
        }

        @Override
        public Optional<Path> escolherPasta() {
            return Optional.empty();
        }
    };

    private DialogoNovoProjeto abrirONovoProjeto() {
        Visual.tema(Tema.CLARO);
        return Visual.naEdt(() -> new DialogoNovoProjeto(motorDoNovoProjeto, avisos, new CopiadorDeTexto(() -> area), c -> chamadas.add("concluiu")));
    }

    @Test
    void novoProjetoCriarDoZero() {
        DialogoNovoProjeto d = abrirONovoProjeto();
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.CRIAR));
        Visual.naEdt(() -> d.campoNome().setText("loja-web"));
        assertTrue(ed(() -> d.botaoPrincipal().isEnabled()));
        assertEquals("Será criado em: /srv/lab/projects/loja-web", ed(d::textoDoCaminho));
        ImagemDeDialogo.gerar(d, "novo-projeto-criar", 580, "Será criado em: /srv/lab/projects/loja-web", "docker-compose.yml vazio", "loja-web_default", "Criar projeto");
        assertEquals(List.of(), chamadas, "a galeria nunca cria o projeto");
    }

    @Test
    void novoProjetoImportarPorGitEPorPasta() {
        DialogoNovoProjeto d = abrirONovoProjeto();
        Visual.naEdt(() -> d.escolherAPagina(DialogoNovoProjeto.Pagina.IMPORTAR));
        assertEquals(DialogoNovoProjeto.Origem.GIT, d.origemAtual());
        Visual.naEdt(() -> d.campoUrl().setText("https://git.example.com/ana/loja-web.git"));
        assertEquals("", ed(d::textoDoErroDaUrl));
        assertEquals("loja-web", ed(() -> d.campoNomeImportar().getText()), "o nome sai do repositório");
        assertTrue(ed(() -> d.botaoPrincipal().isEnabled()));
        assertEquals("Será criado em: /srv/lab/projects/loja-web", ed(d::textoDoCaminhoDaImportacao));
        ImagemDeDialogo.gerar(d, "novo-projeto-importar", 580, "https://git.example.com/ana/loja-web.git", "git clone https://git.example.com/ana/loja-web.git",
            "Será criado em: /srv/lab/projects/loja-web", "Importar projeto");

        DialogoNovoProjeto pasta = abrirONovoProjeto();
        Visual.naEdt(() -> pasta.escolherAPagina(DialogoNovoProjeto.Pagina.IMPORTAR));
        Visual.naEdt(() -> pasta.escolherAOrigem(DialogoNovoProjeto.Origem.PASTA_DO_COMPUTADOR));
        Visual.naEdt(() -> pasta.usarPastaDoComputador(Path.of("/home/ana/projetos/loja-web")));
        assertEquals("loja-web", ed(() -> pasta.campoNomeImportar().getText()));
        assertTrue(ed(() -> pasta.botaoPrincipal().isEnabled()));
        assertFalse(ed(() -> pasta.caixaLevarEnv().isSelected()), ".env NÃO vai por padrão");
        ImagemDeDialogo.gerar(pasta, "novo-projeto-importar-pasta", 580, "/home/ana/projetos/loja-web", "sem node_modules", "sem os arquivos de segredos", "Importar projeto");
        assertEquals(List.of(), chamadas, "a galeria nunca importa nada");
    }

    // ---------------------------------------------------------------------------------------------
    // Adicionar container
    // ---------------------------------------------------------------------------------------------

    @Test
    void adicionarContainerComADiferencaDoComposeAntesDeGravar() throws Exception {
        AmbienteFalso a = new AmbienteFalso(tmp);
        Path projeto = a.raiz.resolve("loja-web");
        Files.createDirectories(projeto);
        // o esqueleto de um projeto novo (só name:, um comentário e services: {}): é o que o app sabe completar sozinho
        String esqueleto = "name: \"loja-web\"\n# Serviços da loja.\nservices: {}\n";
        Files.writeString(projeto.resolve("docker-compose.yml"), esqueleto, StandardCharsets.UTF_8);
        EscritorDeCompose escritor = new EscritorDeCompose(a.local(), RELOGIO, new SecureRandom());
        AtomicInteger gravacoes = new AtomicInteger();
        DialogoAdicionarContainer.Motor motor = new DialogoAdicionarContainer.Motor() {
            @Override
            public EscritorDeCompose.Previa preparar(BlocoDeServico bloco) throws FalhaDoProjeto {
                return escritor.preparar("loja-web", bloco);
            }

            @Override
            public EscritorDeCompose.Gravado gravar(EscritorDeCompose.Previa previa) throws FalhaDoProjeto {
                gravacoes.incrementAndGet();
                return escritor.gravar(previa);
            }
        };
        Visual.tema(Tema.CLARO);
        DialogoAdicionarContainer d = Visual.naEdt(() -> new DialogoAdicionarContainer("loja-web", motor, () -> { }, () -> { }, avisos, new CopiadorDeTexto(() -> area)));
        Espera.ate(PRAZO, () -> "prévia: " + ed(d::textoDaPrevia), () -> {
            String t = ed(d::textoDaPrevia);
            return !t.isEmpty() && !t.startsWith("Lendo");
        });
        Visual.naEdt(() -> d.escolherModelo(Modelo.REDIS));
        Espera.ate(PRAZO, "prévia do Redis", () -> ed(d::textoDaPrevia).contains("redis_dados"));
        String previa = ed(d::textoDaPrevia);
        assertTrue(previa.contains("+ volumes:\n+   redis_dados:"), previa);
        assertFalse(previa.contains("ports:"), "o cache não abre porta");
        assertTrue(ed(() -> d.botaoGravar().isEnabled()));
        assertFalse(ed(() -> d.botaoIniciar().isEnabled()), "Iniciar só depois de gravar");
        ImagemDeDialogo.gerar(d, "novo-projeto-container", "Adicionar container", "loja-web", "+ volumes:", "redis_dados", "image: redis:7-alpine", "cópia datada");
        assertEquals(0, gravacoes.get(), "a galeria só olha a diferença: nada foi gravado");
        assertEquals(esqueleto, Files.readString(projeto.resolve("docker-compose.yml")));
    }

    // ---------------------------------------------------------------------------------------------
    // Instalar o agente: a confirmação
    // ---------------------------------------------------------------------------------------------

    @Test
    void instalarOAgenteNaConfirmacaoComandoPorComando() throws Exception {
        PacoteDoAgente pacote = PacoteDoAgente.doClasspath(); // o agente de verdade que o app leva
        ParametrosDaInstalacao padrao = ParametrosDaInstalacao.padrao(7000);
        ServidorSimulado servidor = new ServidorSimulado(padrao, pacote);
        servidor.noGrupoDocker = true;
        GanchosFalsos ganchos = new GanchosFalsos();
        DialogoDoAgente.definirPrazoDaTravaDaConfirmacaoParaTeste(0); // a trava de segurança tem os testes dela
        Visual.tema(Tema.CLARO);
        DialogoDoAgente d = Visual.naEdt(() -> new DialogoDoAgente(DialogoDoAgente.Acao.INSTALAR, new DialogoDoAgente.Alvo("servidor-demo", 7000, null), pacote,
            p -> new InstaladorDeAgente(servidor, p, pacote, ganchos).comSonda(Duration.ofMillis(300), Duration.ofMillis(10)), () -> { }, () -> { }, avisos,
            new CopiadorDeTexto(() -> area)));
        Visual.naEdt(d::iniciarConferencia);
        Espera.ate(PRAZO, () -> "página=" + ed(d::paginaAtual), () -> "escolher".equals(ed(d::paginaAtual)));
        Visual.naEdt(() -> d.botaoContinuar().doClick()); // o Completo, marcado de saída: com o Docker no servidor é só o agente
        assertEquals("confirmar", ed(d::paginaAtual));
        String passos = String.join("\n", ed(d::frasesDoQueVaiAcontecer));
        assertTrue(passos.contains("1. Enviar o pacote do agente"), passos);
        assertTrue(passos.contains("4. Conferir que o agente responde e registrar o token"), passos);
        ImagemDeDialogo.gerarComLoopbackDoTexto(d, "instalar-agente-confirmacao", "1. Enviar o pacote do agente", "4. Conferir que o agente responde e registrar o token", "equivale a ser administrador (root)");
        assertTrue(servidor.escritas().isEmpty(), "sem o clique de confirmar nada roda: " + servidor.escritas());
        assertTrue(ganchos.chamadas.isEmpty(), "o instalador não registrou nenhum token");
    }

    /** A confirmação de "Atualizar agente": diz o que o agente recebe e avisa que o histórico dos gráficos do agente anterior não é levado. */
    @Test
    void atualizarOAgenteNaConfirmacaoAvisaQueOsGraficosRecomecam() throws Exception {
        PacoteDoAgente pacote = PacoteDoAgente.doClasspath();
        ParametrosDaInstalacao padrao = ParametrosDaInstalacao.padrao(7000);
        String anterior = "0.1.0-3b7e9d1";
        ServidorSimulado servidor = new ServidorSimulado(padrao, pacote);
        servidor.noGrupoDocker = true;
        servidor.containerNome = padrao.container();
        servidor.rotuloDoContainer = anterior;
        servidor.volumeExiste = true;
        servidor.pastaExiste = true;
        GanchosFalsos ganchos = new GanchosFalsos();
        ganchos.tokenJaValia = true;
        DialogoDoAgente.definirPrazoDaTravaDaConfirmacaoParaTeste(0);
        Visual.tema(Tema.CLARO);
        DialogoDoAgente d = Visual.naEdt(() -> new DialogoDoAgente(DialogoDoAgente.Acao.ATUALIZAR, new DialogoDoAgente.Alvo("servidor-demo", 7000, anterior), pacote,
            p -> new InstaladorDeAgente(servidor, p, pacote, ganchos).comSonda(Duration.ofMillis(300), Duration.ofMillis(10)), () -> { }, () -> { }, avisos,
            new CopiadorDeTexto(() -> area)));
        Visual.naEdt(d::iniciarConferencia);
        Espera.ate(PRAZO, () -> "página=" + ed(d::paginaAtual), () -> "confirmar".equals(ed(d::paginaAtual)));
        ImagemDeDialogo.gerarComLoopbackDoTexto(d, "atualizar-agente-confirmacao", "Versão instalada: " + anterior, "Os dados e o token deste computador ficam como estão",
            "O histórico dos gráficos que o agente anterior guardou não é levado", "O agente só lê", "Não recebe o disco");
        assertTrue(servidor.escritas().isEmpty(), "sem o clique de confirmar nada roda: " + servidor.escritas());
    }

    // ---------------------------------------------------------------------------------------------
    // Desligar o login por senha
    // ---------------------------------------------------------------------------------------------

    @Test
    void desligarOLoginPorSenhaMostraOQueFaraOsRiscosEPedeONomeDoServidor() {
        SshdFalso sshd = new SshdFalso("20261028-153005");
        DesligadorDeSenha desligador = new DesligadorDeSenha(sshd, () -> { }, () -> LocalDateTime.of(2026, 10, 28, 15, 30, 5), () -> List.of("publickey"), SshdFalso.USUARIO, Duration.ZERO);
        DialogoDesligarSenha.Motor motor = new DialogoDesligarSenha.Motor() {
            @Override
            public DesligadorDeSenha.Avaliacao avaliar() throws Exception {
                return desligador.avaliar();
            }

            @Override
            public DesligadorDeSenha.Resultado desligar(DesligadorDeSenha.Avaliacao avaliacao, DesligadorDeSenha.Observador observador) {
                throw new IllegalStateException("a galeria nunca desliga a senha");
            }
        };
        Visual.tema(Tema.CLARO);
        DialogoDesligarSenha d = Visual.naEdt(() -> new DialogoDesligarSenha("servidor-demo", motor, new CopiadorDeTexto(() -> area), avisos, () -> { }));
        Espera.ate(PRAZO, "a leitura terminou", () -> ed(() -> d.avaliacao() != null || !d.textoDoBloqueio().isEmpty()));
        assertTrue(d.avaliacao().podeDesligar());
        assertFalse(ed(() -> d.botaoDesligar().isEnabled()), "sem o nome digitado o botão fica desligado");
        ImagemDeDialogo.gerar(d, "seguranca-desligar-senha", "Desligar a senha em servidor-demo", "PasswordAuthentication no",
            "Para confirmar, digite o nome do servidor exatamente como está escrito (maiúsculas e minúsculas contam): servidor-demo", "A chave existe só neste cofre");
        assertEquals(List.of(), sshd.escritas, "abrir a janela só lê: nada foi escrito no servidor");
    }
}
