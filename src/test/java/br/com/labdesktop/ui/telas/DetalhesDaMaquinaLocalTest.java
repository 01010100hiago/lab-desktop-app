package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.util.Preferencias.Tema;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Em "Este computador" a aba Máquina diz de quem são os números: da máquina virtual do Docker, e a rede é só a do agente. */
@ExtendWith(VerificaEdt.class)
class DetalhesDaMaquinaLocalTest {

    private static List<String> rotulosDeRede(DetalhesDaMaquina d) {
        return Visual.naEdt(() -> d.camposDeRede().stream().map(c -> c.rotulo()).toList());
    }

    @Test
    void servidorComumFalaDeDownloadEUpload() {
        Visual.tema(Tema.CLARO);
        DetalhesDaMaquina d = Visual.naEdt(DetalhesDaMaquina::new);
        Visual.naEdt(() -> {
            d.definirLocal(false);
            d.atualizar(FixturesDeApi.servidorSintetico(), FixturesDeApi.detalheSintetico(), false);
        });
        assertFalse(Visual.naEdt(d::notaDoLocalVisivel));
        assertEquals(List.of("Placa de rede", "Descida (download)", "Subida (upload)"), rotulosDeRede(d));
    }

    @Test
    void esteComputadorDizQueARedeESoDoAgenteEQueOrestoEDaMaquinaVirtualDoDocker() {
        Visual.tema(Tema.CLARO);
        DetalhesDaMaquina d = Visual.naEdt(DetalhesDaMaquina::new);
        Visual.naEdt(() -> {
            d.definirLocal(true);
            d.atualizar(FixturesDeApi.servidorSintetico(), FixturesDeApi.detalheSintetico(), false);
        });
        assertTrue(Visual.naEdt(d::notaDoLocalVisivel));
        assertEquals(List.of("Placa de rede (do container do agente)", "Descida (só do agente)", "Subida (só do agente)"), rotulosDeRede(d));
    }
}
