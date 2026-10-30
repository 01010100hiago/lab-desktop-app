package br.com.labdesktop.ui.galeria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.git.ConferenciaDoRepositorio;
import br.com.labdesktop.git.ConferenciaDoRepositorio.ArquivoGrande;
import br.com.labdesktop.git.ConferenciaDoRepositorio.Situacao;
import br.com.labdesktop.git.OperacoesDeGit;
import br.com.labdesktop.git.PedidoDeCriacao;
import br.com.labdesktop.git.ResultadoDaCriacao;
import br.com.labdesktop.instalador.GerenciadorDePacotes;
import br.com.labdesktop.instalador.InstaladorDeGit;
import br.com.labdesktop.instalador.InstaladorDeGit.Estado;
import br.com.labdesktop.instalador.InstaladorDeGit.Previa;
import br.com.labdesktop.instalador.InstaladorDeGit.Resultado;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.dialogos.DialogoCriarRepositorio;
import br.com.labdesktop.ui.dialogos.DialogoInstalarGit;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Imagens da galeria do README: "Criar repositório Git" (conferência pronta, pedindo a identidade, com o projeto que já protege os segredos e com repositório dentro de repositório)
 * e "Instalar o Git". Os diálogos são os de verdade, com um motor de mentira e dados inventados (servidor-demo, projeto loja-web, Ana Souza).
 */
@ExtendWith(VerificaEdt.class)
@org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // usam o shell, o SFTP e as permissões de um servidor Linux de mentira
class GaleriaGitDialogosTest {

    private static final Duration PRAZO = Duration.ofSeconds(20);
    private static final String VERSAO = "git version 2.43.0";

    private final AvisosGravados avisos = new AvisosGravados();
    private final Clipboard area = new Clipboard("galeria");

    @AfterEach
    void voltar() {
        Visual.tema(Tema.CLARO);
    }

    /** O motor de mentira do "Criar repositório": devolve a conferência que o teste pôs e nunca cria nada. */
    private static final class Motor implements DialogoCriarRepositorio.Motor {
        final ConferenciaDoRepositorio conferencia;
        final List<String> criacoes = new CopyOnWriteArrayList<>();

        Motor(ConferenciaDoRepositorio conferencia) {
            this.conferencia = conferencia;
        }

        @Override
        public ConferenciaDoRepositorio conferir() {
            return conferencia;
        }

        @Override
        public ResultadoDaCriacao criar(PedidoDeCriacao pedido, OperacoesDeGit.Observador observador, Cancelamento cancelamento) {
            criacoes.add("criou");
            return null;
        }
    }

    private DialogoCriarRepositorio abrir(Motor motor) {
        Visual.tema(Tema.CLARO);
        DialogoCriarRepositorio d = Visual.naEdt(() -> new DialogoCriarRepositorio("servidor-demo", "loja-web", motor, true, false, null, null, null, avisos,
            new CopiadorDeTexto(() -> area)));
        Visual.naEdt(d::conferir);
        Espera.ate(PRAZO, "a conferência na tela", () -> Visual.naEdt(() -> d.conferenciaMostrada() != null));
        return d;
    }

    private static ConferenciaDoRepositorio conferencia(String nome, String email, List<String> arquivos, List<ArquivoGrande> grandes, List<String> aninhados, String gitignore,
                                                        List<String> repositoriosAbaixo) {
        return new ConferenciaDoRepositorio(Situacao.PODE_CRIAR, null, VERSAO, nome, email, arquivos, grandes, aninhados, gitignore, List.of(), true, false, 0, repositoriosAbaixo);
    }

    private static final List<String> ARQUIVOS = List.of(".env.example", "package.json", "src/app.js", "src/rotas.js", "src/pedidos.js", "docs/leia-me.md", "dados/exportacao.sql", "dados/fotos.zip");
    private static final List<ArquivoGrande> GRANDES = List.of(new ArquivoGrande("dados/exportacao.sql", 6L * 1024 * 1024), new ArquivoGrande("dados/fotos.zip", 12L * 1024 * 1024));

    @Test
    void conferenciaProntaParaCriar() {
        Motor m = new Motor(conferencia("Ana Souza", "ana@example.com", ARQUIVOS, GRANDES, List.of("modulos/tema-loja"), null, List.of()));
        DialogoCriarRepositorio d = abrir(m);
        assertEquals("CONFERENCIA", d.faseAtual());
        assertTrue(Visual.naEdt(() -> d.botaoCriar().isEnabled()), "pronta para criar");
        assertEquals("Criar repositório em loja-web (servidor-demo)", Visual.naEdt(() -> d.botaoCriar().getText()));
        ImagemDeDialogo.gerar(d, "git-criar-repositorio", "Conferência (só leitura): nada foi criado ainda.", VERSAO, "O Git já sabe quem você é: Ana Souza <ana@example.com>",
            "O que entra no primeiro commit", "Segredos nunca entram", "Repositório dentro do projeto", "Arquivos grandes (mais de 5 MB)", "dados/exportacao.sql",
            "O app não faz push", "Criar repositório em loja-web (servidor-demo)");
        assertEquals(List.of(), m.criacoes, "a galeria só olha: nada foi criado");
    }

    @Test
    void conferenciaPedindoNomeEEmailParaAIdentidadeSoLocal() {
        Motor m = new Motor(conferencia("", "", ARQUIVOS, GRANDES, List.of("modulos/tema-loja"), null, List.of()));
        DialogoCriarRepositorio d = abrir(m);
        Visual.naEdt(() -> {
            d.campoNome().setText("Ana Souza");
            d.campoEmail().setText("ana@example.com");
        });
        ImagemDeDialogo.gerar(d, "git-criar-identidade", "Quem assina os commits", "SÓ neste repositório (git config --local)", "nunca na configuração de toda a máquina",
            "Ana Souza", "ana@example.com", "Criar repositório em loja-web (servidor-demo)");
        assertTrue(Visual.naEdt(() -> d.botaoCriar().isEnabled()), "com nome e e-mail válidos o botão libera");
    }

    @Test
    void conferenciaComOsSegredosFicandoDeFora() {
        // um projeto que já tem .gitignore (só com node_modules): o app completa com as regras de .env e de chaves, e mostra as linhas
        Motor m = new Motor(conferencia("Ana Souza", "ana@example.com", List.of(".env.example", "package.json", "src/app.js", "src/rotas.js", "docs/leia-me.md"), List.of(), List.of(),
            "node_modules/\n", List.of()));
        DialogoCriarRepositorio d = abrir(m);
        String linhas = Visual.naEdt(() -> ImagemDeDialogo.textoDoCampoComNome(d.raiz(), "linhas-do-gitignore"));
        assertTrue(linhas.lines().anyMatch(".[eE][nN][vV]*"::equals), linhas);
        assertTrue(linhas.lines().anyMatch("*.[pP][eE][mM]"::equals), linhas);
        assertTrue(linhas.lines().anyMatch("[iI][dD]_[rR][sS][aA]*"::equals), linhas);
        ImagemDeDialogo.gerar(d, "git-criar-segredo", "Segredos nunca entram", ".env, .env.local, chaves (.pem, .key, id_rsa)", "git check-ignore", ".env.example",
            "O app acrescentaria estas linhas ao final do .gitignore", ".[eE][nN][vV]*", "*.[pP][eE][mM]", "[iI][dD]_[rR][sS][aA]*");
    }

    @Test
    void avisoDeRepositorioDentroDeRepositorioComACaixaDeConfirmacao() {
        Motor m = new Motor(conferencia("Ana Souza", "ana@example.com", List.of("package.json", "docker-compose.yml", "README.md"), List.of(), List.of(), null, List.of("app")));
        DialogoCriarRepositorio d = abrir(m);
        assertTrue(Visual.naEdt(() -> d.caixaDoRepositorioAbaixo().isVisible()));
        ImagemDeDialogo.gerar(d, "git-criar-mesmo-assim", "Já existe repositório Git dentro deste projeto", "Há repositório Git em app/",
            "Entendi o problema e quero criar o repositório na pasta principal mesmo assim");
    }

    // ---------------------------------------------------------------------------------------------
    // Instalar o Git
    // ---------------------------------------------------------------------------------------------

    private static final Estado SEM_GIT = new Estado(false, "");
    private static final String ATUALIZAR = "env DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=60 update";
    private static final String INSTALAR = "env DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=60 install -y git";

    @Test
    void instalarOGitComOsComandosExatosAntesDoClique() {
        Visual.tema(Tema.CLARO);
        Previa previa = new Previa(SEM_GIT, InstaladorDeGit.Situacao.PODE_INSTALAR, GerenciadorDePacotes.APT_GET, "Debian GNU/Linux 13 (trixie)", false, true,
            List.of("sudo -n " + ATUALIZAR, "sudo -n " + INSTALAR), List.of("sudo " + ATUALIZAR, "sudo " + INSTALAR), java.util.Set.of("sudo -n " + ATUALIZAR),
            GerenciadorDePacotes.APT_GET.passosDeInstalar().stream().map(GerenciadorDePacotes.Passo::porQue).filter(n -> !n.isEmpty()).toList());
        List<String> chamadas = new CopyOnWriteArrayList<>();
        DialogoInstalarGit.Motor motor = new DialogoInstalarGit.Motor() {
            @Override
            public Previa conferir() {
                chamadas.add("conferir");
                return previa;
            }

            @Override
            public Resultado rodar(Previa pv, InstaladorDeGit.Observador obs, Cancelamento cancelamento) {
                chamadas.add("instalar");
                return null;
            }

            @Override
            public Estado conferirDeNovo() {
                chamadas.add("conferir-de-novo");
                return SEM_GIT;
            }
        };
        DialogoInstalarGit d = Visual.naEdt(() -> new DialogoInstalarGit("servidor-demo", true, motor, new CopiadorDeTexto(() -> area), avisos, () -> { }));
        Espera.ate(PRAZO, () -> "lido: " + chamadas, () -> chamadas.contains("conferir"));
        Espera.ate(PRAZO, "a explicação na tela", () -> !Visual.naEdt(d::textoDaExplicacao).isEmpty());
        assertTrue(Visual.naEdt(() -> d.botaoInstalar().isVisible()), "o botão de instalar espera o clique");
        ImagemDeDialogo.gerar(d, "git-instalar", "Instalar o Git em servidor-demo", "apt-get", "install -y git");
        assertEquals(List.of("conferir"), chamadas, "nada foi instalado: a galeria só olha");
    }
}
