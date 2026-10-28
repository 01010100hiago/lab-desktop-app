package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.projetos.AmbienteFalso;
import br.com.labdesktop.projetos.BlocoDeServico;
import br.com.labdesktop.projetos.EscritorDeCompose;
import br.com.labdesktop.projetos.FalhaDoProjeto;
import br.com.labdesktop.projetos.GabaritoDoProjeto;
import br.com.labdesktop.projetos.ModelosDeContainer.Modelo;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** "Adicionar container": o que mostra antes, o que grava depois do clique, o que nunca grava e o "Iniciar" que é outro clique. PNGs nos dois temas. */
@ExtendWith(VerificaEdt.class)
class DialogoAdicionarContainerTest {

    private static final Duration PRAZO = Duration.ofSeconds(20);

    @TempDir
    Path tmp;

    private AmbienteFalso a;
    private Path projeto;
    private final AvisosGravados avisos = new AvisosGravados();
    private final AtomicInteger gravacoes = new AtomicInteger();
    private final AtomicInteger inicios = new AtomicInteger();
    private final Clipboard area = new Clipboard("teste");

    @BeforeEach
    void preparar() throws Exception {
        Visual.tema(Tema.CLARO);
        a = new AmbienteFalso(tmp);
        projeto = a.raiz.resolve("demo");
        Files.createDirectories(projeto);
    }

    @AfterEach
    void restaurar() {
        Visual.tema(Tema.CLARO);
    }

    private void compose(String texto) throws Exception {
        Files.writeString(projeto.resolve("docker-compose.yml"), texto, StandardCharsets.UTF_8);
    }

    private DialogoAdicionarContainer abrir() {
        EscritorDeCompose escritor = new EscritorDeCompose(a.local(), Clock.fixed(Instant.parse("2026-10-28T15:30:00Z"), ZoneOffset.UTC), new SecureRandom());
        DialogoAdicionarContainer.Motor motor = new DialogoAdicionarContainer.Motor() {
            @Override
            public EscritorDeCompose.Previa preparar(BlocoDeServico bloco) throws FalhaDoProjeto {
                return escritor.preparar("demo", bloco);
            }

            @Override
            public EscritorDeCompose.Gravado gravar(EscritorDeCompose.Previa previa) throws FalhaDoProjeto {
                return escritor.gravar(previa);
            }
        };
        return Visual.naEdt(() -> new DialogoAdicionarContainer("demo", motor, gravacoes::incrementAndGet, inicios::incrementAndGet, avisos, new CopiadorDeTexto(() -> area)));
    }

    private void esperarPrevia(DialogoAdicionarContainer d) {
        Espera.ate(PRAZO, () -> "prévia: " + Visual.naEdt(d::textoDaPrevia), () -> {
            String t = Visual.naEdt(d::textoDaPrevia);
            return !t.isEmpty() && !t.startsWith("Lendo");
        });
    }

    private void fotografar(DialogoAdicionarContainer d, String nome) {
        Dialogo.definirAlturaDaTelaParaTeste(1500);
        try {
            for (Tema t : List.of(Tema.CLARO, Tema.ESCURO)) {
                Visual.tema(t);
                Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(d.raiz()));
                Visual.salvar(Visual.renderizar(d.raiz(), 580, Visual.alturaPreferida(d.raiz(), 580)), nome + "-" + (t == Tema.CLARO ? "claro" : "escuro"));
            }
        } finally {
            Dialogo.definirAlturaDaTelaParaTeste(0);
            Visual.tema(Tema.CLARO);
            Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(d.raiz()));
        }
    }

    private static List<String> nomes(Path pasta) throws Exception {
        try (Stream<Path> s = Files.list(pasta)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void abrirMostraADiferencaDoNodeENaoGravaNada() throws Exception {
        compose(GabaritoDoProjeto.composeVazio("demo"));
        DialogoAdicionarContainer d = abrir();
        esperarPrevia(d);
        assertEquals(Modelo.NODE, d.modeloAtual());
        assertTrue(Visual.naEdt(() -> d.opcao(Modelo.NODE).isSelected()));
        String previa = Visual.naEdt(d::textoDaPrevia);
        assertTrue(previa.contains("- services: {}"), previa);
        assertTrue(previa.contains("+ services:\n+   app:\n+     image: node:22-alpine"), previa);
        assertTrue(previa.contains("\"127.0.0.1:3000:3000\""), "a porta só em 127.0.0.1");
        assertTrue(Visual.naEdt(d::textoDaNota).contains("cópia datada"));
        assertTrue(Visual.naEdt(() -> d.botaoGravar().isEnabled()));
        assertFalse(Visual.naEdt(() -> d.botaoIniciar().isEnabled()), "Iniciar só depois de gravar");
        assertEquals(List.of("docker-compose.yml"), nomes(projeto), "só olhar não grava nada");
        assertEquals(GabaritoDoProjeto.composeVazio("demo"), Files.readString(projeto.resolve("docker-compose.yml")));
        assertEquals(List.of(), a.comandos(), "nem o Docker foi chamado");
        fotografar(d, "dialogo-adicionar-container-node");
    }

    @Test
    void postgresMostraVolumeESenhaNoEnvSemValor() throws Exception {
        compose(GabaritoDoProjeto.composeVazio("demo"));
        DialogoAdicionarContainer d = abrir();
        esperarPrevia(d);
        Visual.naEdt(() -> d.escolherModelo(Modelo.POSTGRES));
        Espera.ate(PRAZO, "prévia do Postgres", () -> Visual.naEdt(d::textoDaPrevia).contains("postgres_dados"));
        String previa = Visual.naEdt(d::textoDaPrevia);
        assertTrue(previa.contains("+ volumes:\n+   postgres_dados:"), previa);
        assertFalse(previa.contains("ports:"), "banco sem porta por padrão");
        assertTrue(Visual.naEdt(d::textoDaNota).contains("POSTGRES_PASSWORD"));
        assertTrue(Visual.naEdt(d::textoDaNota).contains("sem aparecer aqui"));
        assertFalse(Visual.naEdt(() -> d.caixaDaPorta().isSelected()));
        fotografar(d, "dialogo-adicionar-container-postgres");
    }

    @Test
    void imagemPorNomePedeONomeEValidaOFormato() throws Exception {
        compose(GabaritoDoProjeto.composeVazio("demo"));
        DialogoAdicionarContainer d = abrir();
        esperarPrevia(d);
        Visual.naEdt(() -> d.escolherModelo(Modelo.IMAGEM));
        assertTrue(Visual.naEdt(() -> d.campoImagem().isVisible()));
        assertTrue(Visual.naEdt(d::textoDosProblemas).contains("nome da imagem"));
        assertFalse(Visual.naEdt(() -> d.botaoGravar().isEnabled()));
        Visual.naEdt(() -> d.campoImagem().setText("Nginx; rm -rf /"));
        assertTrue(Visual.naEdt(d::textoDosProblemas).contains("não vale"));
        assertFalse(Visual.naEdt(() -> d.botaoGravar().isEnabled()));
        Visual.naEdt(() -> d.campoImagem().setText("nginx:alpine"));
        assertEquals("", Visual.naEdt(d::textoDosProblemas));
        esperarPrevia(d);
        assertTrue(Visual.naEdt(d::textoDaPrevia).contains("image: nginx:alpine"));
        assertTrue(Visual.naEdt(() -> d.botaoGravar().isEnabled()));
        Visual.naEdt(() -> {
            d.caixaDaPorta().doClick();
            d.campoPortaNoComputador().setText("abc");
        });
        assertTrue(Visual.naEdt(d::textoDosProblemas).contains("números"));
        assertFalse(Visual.naEdt(() -> d.botaoGravar().isEnabled()));
        Visual.naEdt(() -> {
            d.campoPortaNoComputador().setText("8080");
            d.campoPortaNoContainer().setText("80");
        });
        Espera.ate(PRAZO, "prévia com porta", () -> Visual.naEdt(d::textoDaPrevia).contains("127.0.0.1:8080:80"));
    }

    @Test
    void composeDoDonoSoMostraOBlocoParaCopiar() throws Exception {
        String composeDoUsuario = "services:\n  web:\n    image: nginx\nvolumes:\n  d:\n";
        compose(composeDoUsuario);
        DialogoAdicionarContainer d = abrir();
        esperarPrevia(d);
        Visual.naEdt(() -> d.escolherModelo(Modelo.POSTGRES));
        Espera.ate(PRAZO, "bloco", () -> Visual.naEdt(d::textoDaPrevia).contains("# 1) Em \"services:\""));
        assertFalse(Visual.naEdt(() -> d.botaoGravar().isEnabled()), "o app não reescreve o compose do usuário");
        assertTrue(Visual.naEdt(d::textoDaNota).contains("não reescreve"));
        assertTrue(Visual.naEdt(() -> d.botaoCopiar().isEnabled()));
        Visual.naEdt(() -> d.botaoCopiar().doClick());
        String copiado = (String) area.getContents(null).getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor);
        assertTrue(copiado.contains("POSTGRES_PASSWORD=escolha-uma-senha-forte"));
        assertTrue(copiado.contains("  postgres:\n    image: postgres:17-alpine"));
        assertEquals(composeDoUsuario, Files.readString(projeto.resolve("docker-compose.yml")));
        assertEquals(List.of("docker-compose.yml"), nomes(projeto));
        fotografar(d, "dialogo-adicionar-container-compose-do-usuario");
    }

    @Test
    void gravarSoDepoisDoCliqueEIniciarEOutroClique() throws Exception {
        compose(GabaritoDoProjeto.composeVazio("demo"));
        DialogoAdicionarContainer d = abrir();
        esperarPrevia(d);
        Visual.naEdt(() -> d.escolherModelo(Modelo.POSTGRES));
        Espera.ate(PRAZO, "prévia do Postgres", () -> Visual.naEdt(d::textoDaPrevia).contains("postgres_dados"));
        Visual.naEdt(() -> d.botaoGravar().doClick());
        Espera.ate(PRAZO, "gravou", () -> Visual.naEdt(d::gravado));
        String sucesso = Visual.naEdt(d::textoDoSucesso);
        assertTrue(sucesso.contains("Gravado em docker-compose.yml"), sucesso);
        assertTrue(sucesso.contains("docker-compose.yml.20261028-153000.bak"), sucesso);
        assertTrue(sucesso.contains("POSTGRES_PASSWORD"), sucesso);
        assertTrue(sucesso.contains("não aparece aqui"), sucesso);
        assertTrue(sucesso.contains("ainda não subiu"), sucesso);
        assertEquals(1, gravacoes.get());
        assertEquals(0, inicios.get(), "gravar NÃO inicia o projeto");
        assertTrue(Files.readString(projeto.resolve("docker-compose.yml")).contains("services:\n  postgres:\n"));
        assertTrue(Files.readString(projeto.resolve(".env")).startsWith("POSTGRES_PASSWORD="));
        assertFalse(Files.readString(projeto.resolve("docker-compose.yml")).contains(Files.readString(projeto.resolve(".env")).substring(18).strip()));
        assertTrue(Visual.naEdt(() -> d.botaoIniciar().isEnabled()));
        assertFalse(Visual.naEdt(() -> d.botaoGravar().isVisible()), "não se grava duas vezes");
        assertEquals("Fechar", Visual.naEdt(() -> d.botaoCancelar().getText()));
        fotografar(d, "dialogo-adicionar-container-gravado");
        Visual.naEdt(() -> d.botaoIniciar().doClick());
        assertEquals(1, inicios.get());
    }

    @Test
    void dockerRecusandoNadaEGravadoEOBotaoVolta() throws Exception {
        String original = GabaritoDoProjeto.composeVazio("demo");
        compose(original);
        DialogoAdicionarContainer d = abrir();
        esperarPrevia(d);
        a.roteiro = c -> AmbienteFalso.erro(1, "service \"app\" refers to undefined volume x");
        Visual.naEdt(() -> d.botaoGravar().doClick());
        Espera.ate(PRAZO, "falhou", () -> !Visual.naEdt(d::textoDaFalha).isEmpty());
        assertTrue(Visual.naEdt(d::textoDaFalha).contains("Nada foi gravado"));
        assertTrue(Visual.naEdt(d::textoDaFalha).contains("undefined volume"));
        assertFalse(Visual.naEdt(d::gravado));
        assertTrue(Visual.naEdt(() -> d.botaoGravar().isEnabled()));
        assertEquals(0, gravacoes.get());
        assertEquals(original, Files.readString(projeto.resolve("docker-compose.yml")));
        assertEquals(List.of("docker-compose.yml"), nomes(projeto));
        fotografar(d, "dialogo-adicionar-container-docker-recusou");
    }

    @Test
    void arquivoQueMudouDepoisDeVerNaoEGravado() throws Exception {
        compose(GabaritoDoProjeto.composeVazio("demo"));
        DialogoAdicionarContainer d = abrir();
        esperarPrevia(d);
        compose("services:\n  web:\n    image: nginx\n"); // o usuário editou enquanto olhava
        Visual.naEdt(() -> d.botaoGravar().doClick());
        Espera.ate(PRAZO, "falhou", () -> !Visual.naEdt(d::textoDaFalha).isEmpty());
        assertTrue(Visual.naEdt(d::textoDaFalha).contains("mudou"));
        assertEquals("services:\n  web:\n    image: nginx\n", Files.readString(projeto.resolve("docker-compose.yml")));
    }

    @Test
    void semAcaoDeIniciarOBotaoNemAparece() throws Exception {
        compose(GabaritoDoProjeto.composeVazio("demo"));
        EscritorDeCompose escritor = new EscritorDeCompose(a.local());
        DialogoAdicionarContainer d = Visual.naEdt(() -> new DialogoAdicionarContainer("demo", new DialogoAdicionarContainer.Motor() {
            @Override
            public EscritorDeCompose.Previa preparar(BlocoDeServico b) throws FalhaDoProjeto {
                return escritor.preparar("demo", b);
            }

            @Override
            public EscritorDeCompose.Gravado gravar(EscritorDeCompose.Previa p) throws FalhaDoProjeto {
                return escritor.gravar(p);
            }
        }, () -> { }, null, avisos, new CopiadorDeTexto(() -> area)));
        assertFalse(Visual.naEdt(() -> d.botaoIniciar().isVisible()));
    }

    @Test
    void projetoSemComposeOferecePorOEsqueleto() throws Exception {
        DialogoAdicionarContainer d = abrir();
        esperarPrevia(d);
        assertTrue(Visual.naEdt(d::textoDaNota).contains("ainda não tem docker-compose.yml"));
        assertTrue(Visual.naEdt(() -> d.botaoGravar().isEnabled()));
    }
}
