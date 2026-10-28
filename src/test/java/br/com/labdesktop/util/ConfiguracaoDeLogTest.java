package br.com.labdesktop.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ConfiguracaoDeLogTest {

    private String logFileAntes;

    @AfterEach
    void restaurar() {
        // A propriedade é global da JVM: não deixa o teste apontar o slf4j-simple para uma pasta que será apagada.
        if (logFileAntes == null) {
            System.clearProperty("org.slf4j.simpleLogger.logFile");
        } else {
            System.setProperty("org.slf4j.simpleLogger.logFile", logFileAntes);
        }
    }

    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "depende de permissões POSIX ou do shell do Unix; no Windows valem ProtecaoAcl e as provas do Windows")
    @Test
    void criaPastaEArquivoPrivadosEApontaOSlf4j(@TempDir Path tmp) throws Exception {
        logFileAntes = System.getProperty("org.slf4j.simpleLogger.logFile");
        CaminhosApp c = CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
        ConfiguracaoDeLog.configurar(c);
        assertEquals("rwx------", Permissoes.comoTexto(c.pastaLogs()));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(c.arquivoLog())));
        assertEquals(c.arquivoLog().toString(), System.getProperty("org.slf4j.simpleLogger.logFile"));
    }

    @Test
    void comLabdesktopDebugLigaOLoggerDasRequisicoes(@TempDir Path tmp) throws Exception {
        logFileAntes = System.getProperty("org.slf4j.simpleLogger.logFile");
        String nivel = "org.slf4j.simpleLogger.log." + LogDeRequisicoes.NOME_DO_LOGGER;
        String nivelAntes = System.getProperty(nivel);
        String debugAntes = System.getProperty(LogDeRequisicoes.PROPRIEDADE);
        try {
            System.setProperty(LogDeRequisicoes.PROPRIEDADE, "true");
            ConfiguracaoDeLog.configurar(CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs")));
            assertEquals("debug", System.getProperty(nivel), "a partida liga o log de requisições quando o usuário pede");
        } finally {
            for (String[] par : new String[][] {{nivel, nivelAntes}, {LogDeRequisicoes.PROPRIEDADE, debugAntes}}) {
                if (par[1] == null) {
                    System.clearProperty(par[0]);
                } else {
                    System.setProperty(par[0], par[1]);
                }
            }
        }
    }

    @Test
    void rotacionaQuandoPassaDeDoisMiB(@TempDir Path tmp) throws Exception {
        logFileAntes = System.getProperty("org.slf4j.simpleLogger.logFile");
        CaminhosApp c = CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
        c.prepararPastaDeLogs();
        Files.write(c.arquivoLog(), new byte[(int) ConfiguracaoDeLog.TAMANHO_PARA_ROTACIONAR + 1]);
        ConfiguracaoDeLog.configurar(c);
        Path antigo = c.arquivoLog().resolveSibling("lab-desktop.log.1");
        assertTrue(Files.exists(antigo));
        assertEquals(0, Files.size(c.arquivoLog()));
    }

    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "depende de permissões POSIX ou do shell do Unix; no Windows valem ProtecaoAcl e as provas do Windows")
    @Test
    void naoRotacionaArquivoPequenoECorrigePermissao(@TempDir Path tmp) throws Exception {
        logFileAntes = System.getProperty("org.slf4j.simpleLogger.logFile");
        CaminhosApp c = CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
        c.prepararPastaDeLogs();
        Files.writeString(c.arquivoLog(), "linha\n");
        Files.setPosixFilePermissions(c.arquivoLog(), PosixFilePermissions.fromString("rw-r--r--"));
        ConfiguracaoDeLog.configurar(c);
        assertFalse(Files.exists(c.arquivoLog().resolveSibling("lab-desktop.log.1")));
        assertEquals("rw-------", Permissoes.comoTexto(c.arquivoLog()));
        assertEquals("linha\n", Files.readString(c.arquivoLog()));
    }
}
