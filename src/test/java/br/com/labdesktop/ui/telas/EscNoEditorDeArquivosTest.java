package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.CenarioDeSftp;
import br.com.labdesktop.arquivos.MemoriaDeCaminhos;
import br.com.labdesktop.arquivos.ServicosDeArquivos;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.CenarioDeUi;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.NavegadorGravado;
import br.com.labdesktop.ui.PainelPrincipal;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.telas.arquivos.GerenciadorDeArquivos;
import br.com.labdesktop.util.Preferencias.Tema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * Na página do projeto Esc volta para a lista e, num campo com texto, só limpa o campo. No editor de arquivos NENHUM dos dois:
 * Esc ali nunca apaga o texto e nunca joga fora a página (e as alterações) de quem está editando.
 */
@ExtendWith(VerificaEdt.class)
class EscNoEditorDeArquivosTest {

    private static final String O = CenarioDeSftp.ORBITA;

    @Test
    void escNoEditorNaoLimpaOTextoNemSaiDaPagina(@TempDir Path tmp) throws Exception {
        Visual.tema(Tema.CLARO);
        String antes = System.getProperty(FluxosDeProjeto.PROPRIEDADE_ACOES);
        System.setProperty(FluxosDeProjeto.PROPRIEDADE_ACOES, "true");
        CenarioDeUi cenario = new CenarioDeUi();
        PainelPrincipal painel = null;
        try {
            Path raiz = Files.createDirectories(tmp.resolve("servidor")).toRealPath();
            cenario.ssh.comSftp(raiz);
            Files.createDirectories(raiz.resolve("srv/lab/projects/orbita"));
            Files.writeString(raiz.resolve("srv/lab/projects/orbita/notas.txt"), "texto importante\n");
            AvisosGravados avisos = new AvisosGravados();
            ServicosDeArquivos servicos = new ServicosDeArquivos(MemoriaDeCaminhos.emMemoria(), tmp.resolve("backups"));
            painel = Visual.naEdt(() -> new PainelPrincipal(cenario.gerenciador, null, avisos, new DialogosGravados(), new NavegadorGravado(avisos), servicos));
            PainelPrincipal p = painel;
            Visual.naEdt(() -> p.navegacao().abrirProjeto(cenario.lab, "orbita"));
            TelaServidor servidor = Visual.naEdt(() -> p.navegacao().telaServidorDe(CofreFalso.ID));
            TelaProjeto pagina = Visual.naEdt(servidor::projetoAberto);
            Visual.naEdt(() -> pagina.mostrarAba(TelaProjeto.Aba.ARQUIVOS));
            GerenciadorDeArquivos g = Visual.naEdt(pagina::arquivos);
            Visual.naEdt(g::iniciar);
            Espera.ate(Duration.ofSeconds(30), "lista", () -> Visual.naEdt(() -> g.entrada("notas.txt").isPresent()));
            Visual.naEdt(() -> g.abrirEntrada(g.entrada("notas.txt").orElseThrow()));
            Espera.ate(Duration.ofSeconds(30), "editor", () -> Visual.naEdt(() -> g.areaDoEditor(O + "/notas.txt")) != null);
            var area = Visual.naEdt(() -> g.areaDoEditor(O + "/notas.txt"));
            Visual.naEdt(() -> area.setText(area.getText() + "mais uma linha\n"));

            Visual.naEdt(() -> pagina.aoApertarEsc(area)); // Esc com o foco no editor
            assertEquals("texto importante\nmais uma linha\n", Visual.naEdt(() -> area.getText()), "o texto continua");
            assertEquals(pagina, Visual.naEdt(servidor::projetoAberto), "e a página também");

            // o campo de filtro da mesma tela segue a regra de sempre: com texto, Esc limpa o campo (e não sai)
            var filtro = Visual.naEdt(() -> new javax.swing.JTextField("abc"));
            Visual.naEdt(() -> pagina.aoApertarEsc(filtro));
            assertEquals("", Visual.naEdt(() -> filtro.getText()));
            assertEquals(pagina, Visual.naEdt(servidor::projetoAberto));

            // fora do editor, Esc volta para a lista; as alterações não somem: a página fica guardada
            Visual.naEdt(() -> pagina.aoApertarEsc(null));
            assertNull(Visual.naEdt(servidor::projetoAberto));
            assertTrue(avisos.disse("atencao", "Guardei as alterações não salvas em orbita"));
            Visual.naEdt(() -> p.navegacao().abrirProjeto(cenario.lab, "orbita"));
            assertEquals(pagina, Visual.naEdt(servidor::projetoAberto));
            assertTrue(Visual.naEdt(() -> g.areaDoEditor(O + "/notas.txt").getText()).endsWith("mais uma linha\n"));
            assertFalse(Visual.naEdt(g::encerrado));
            assertNotNull(area);
        } finally {
            if (painel != null) {
                PainelPrincipal p = painel;
                Visual.naEdt(p::encerrar);
            }
            cenario.close();
            if (antes == null) {
                System.clearProperty(FluxosDeProjeto.PROPRIEDADE_ACOES);
            } else {
                System.setProperty(FluxosDeProjeto.PROPRIEDADE_ACOES, antes);
            }
        }
    }
}
