package br.com.labdesktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.cofre.TravaDeInstancia;
import br.com.labdesktop.ui.JanelaDeAbertura;
import br.com.labdesktop.util.CaminhosApp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Os caminhos de falha da partida: a janelinha de abertura é fechada ANTES do aviso e não sobrevive a um erro antes da primeira janela. */
class PartidaDoAppTest {

    @AfterEach
    void limpar() {
        JanelaDeAbertura.fecharAAtual();
    }

    /** Um registro único: a ordem entre "fechou" e "aviso" é o que importa. */
    private static JanelaDeAbertura abrirJanelinha(List<String> eventos) {
        return JanelaDeAbertura.mostrar(JanelaDeAbertura.Cores.claro(), new JanelaDeAbertura.Apresentacao() {
            @Override
            public void abrir(JanelaDeAbertura.Cores c) {
                eventos.add("abriu");
            }

            @Override
            public void fechar() {
                eventos.add("fechou");
            }
        }, (e, a) -> { });
    }

    @Test
    void segundaCopiaFechaAJanelinhaAntesDoAviso(@TempDir Path tmp) throws Exception {
        CaminhosApp c = CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
        try (TravaDeInstancia primeira = TravaDeInstancia.adquirir(c)) {
            assertTrue(primeira != null);
            List<String> eventos = new ArrayList<>();
            JanelaDeAbertura j = abrirJanelinha(eventos);
            Optional<TravaDeInstancia> segunda = PartidaDoApp.adquirirOuAvisar(c, texto -> eventos.add("aviso: " + texto), e -> eventos.add("falha"));
            assertTrue(segunda.isEmpty());
            assertFalse(j.aberta());
            assertEquals(3, eventos.size());
            assertEquals("abriu", eventos.get(0));
            assertEquals("fechou", eventos.get(1)); // a janelinha sai ANTES de o aviso aparecer
            assertTrue(eventos.get(2).startsWith("aviso: ") && eventos.get(2).contains("já está aberto"), eventos.get(2));
        }
    }

    @Test
    void pastaDeDadosQueNaoAbreFechaAJanelinhaAntesDoAvisoERegistraAFalha(@TempDir Path tmp) throws Exception {
        Path arquivo = Files.writeString(tmp.resolve("arquivo"), "x"); // um ARQUIVO onde deveria haver a pasta de dados
        CaminhosApp c = CaminhosApp.de(arquivo.resolve("dados"), tmp.resolve("logs"));
        List<String> eventos = new ArrayList<>();
        abrirJanelinha(eventos);
        Optional<TravaDeInstancia> trava = PartidaDoApp.adquirirOuAvisar(c, texto -> eventos.add("aviso"), (IOException e) -> eventos.add("falha"));
        assertTrue(trava.isEmpty());
        assertEquals(List.of("abriu", "fechou", "falha", "aviso"), eventos);
    }

    @Test
    void comATravaNaMaoNadaAcontece(@TempDir Path tmp) throws Exception {
        CaminhosApp c = CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
        List<String> eventos = new ArrayList<>();
        Optional<TravaDeInstancia> trava = PartidaDoApp.adquirirOuAvisar(c, texto -> eventos.add("aviso"), e -> eventos.add("falha"));
        assertTrue(trava.isPresent());
        trava.get().close();
        assertTrue(eventos.isEmpty());
    }

    @Test
    void erroAntesDaPrimeiraJanelaFechaAJanelinhaEPropagaOErro() {
        List<String> eventos = new ArrayList<>();
        JanelaDeAbertura j = abrirJanelinha(eventos);
        IllegalStateException erro = new IllegalStateException("falhou ao montar");
        IllegalStateException pego = assertThrows(IllegalStateException.class, () -> PartidaDoApp.protegendo(() -> {
            throw erro;
        }));
        assertEquals(erro, pego);
        assertFalse(j.aberta());
        assertEquals(List.of("abriu", "fechou"), eventos);
    }

    @Test
    void partidaSemErroDeixaAJanelinhaParaAPrimeiraJanelaFechar() {
        List<String> eventos = new ArrayList<>();
        JanelaDeAbertura j = abrirJanelinha(eventos);
        PartidaDoApp.protegendo(() -> eventos.add("iniciou"));
        assertTrue(j.aberta());
        assertEquals(List.of("abriu", "iniciou"), eventos);
    }
}
