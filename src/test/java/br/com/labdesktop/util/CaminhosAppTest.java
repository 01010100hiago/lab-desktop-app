package br.com.labdesktop.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaminhosAppTest {

    @Test
    void padraoSegueAsPastasDoSistemaDesteComputador() {
        CaminhosApp c = CaminhosApp.padrao();
        var pastas = br.com.labdesktop.plataforma.PastasDoApp.de(br.com.labdesktop.plataforma.AmbienteDoSistema.detectar());
        assertEquals(Path.of(pastas.dados()), c.pastaDados());
        assertEquals(Path.of(pastas.logs()), c.pastaLogs());
        assertEquals(c.pastaDados().resolve("cofre.labcofre"), c.arquivoCofre());
        assertEquals(c.pastaDados().resolve("cofre.labcofre.bak"), c.arquivoCofreBak());
        assertEquals(c.pastaDados().resolve("cofre.labcofre.tmp"), c.arquivoCofreTmp());
        assertEquals(c.pastaDados().resolve("cofre.lock"), c.arquivoTrava());
        assertEquals(c.pastaLogs().resolve("lab-desktop.log"), c.arquivoLog());
    }

    @Test
    void pastaPorArgumentoComEspacoEComIgual() {
        assertEquals(Path.of("cofre-dev").toAbsolutePath().normalize(),
            CaminhosApp.deArgumentos(new String[] {"--pasta", "cofre-dev"}).pastaDados());
        Path absoluta = Path.of("algum", "lugar").toAbsolutePath().normalize();
        assertEquals(absoluta, CaminhosApp.deArgumentos(new String[] {"--x", "--pasta=" + absoluta}).pastaDados());
    }

    @Test
    void pastaPorArgumentoNaoMudaOsLogs() {
        assertEquals(CaminhosApp.padrao().pastaLogs(),
            CaminhosApp.deArgumentos(new String[] {"--pasta", "cofre-dev"}).pastaLogs());
    }

    @Test
    void semArgumentoVaiParaOPadrao() {
        assertEquals(CaminhosApp.padrao().pastaDados(), CaminhosApp.deArgumentos(new String[0]).pastaDados());
        assertEquals(CaminhosApp.padrao().pastaDados(), CaminhosApp.deArgumentos(null).pastaDados());
    }

    @Test
    void pastaSemValorEErro() {
        assertThrows(IllegalArgumentException.class, () -> CaminhosApp.deArgumentos(new String[] {"--pasta"}));
        assertThrows(IllegalArgumentException.class, () -> CaminhosApp.deArgumentos(new String[] {"--pasta="}));
    }

    @Test
    void prepararPastasCriaComZeroSeteZeroZero(@TempDir Path tmp) throws Exception {
        CaminhosApp c = CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
        c.prepararPastas();
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(c.pastaDados())));
    }

    @Test
    void prepararPastasCorrigePastaAberta(@TempDir Path tmp) throws Exception {
        Path dados = tmp.resolve("dados");
        Files.createDirectory(dados);
        Files.setPosixFilePermissions(dados, PosixFilePermissions.fromString("rwxr-xr-x"));
        CaminhosApp.de(dados, tmp.resolve("logs")).prepararPastas();
        assertEquals("rwx------", Permissoes.comoTexto(dados));
        assertFalse(Permissoes.corrigirPastaSeAberta(dados));
        assertTrue(Files.isDirectory(dados));
    }
}
