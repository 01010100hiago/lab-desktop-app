package br.com.labdesktop.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Os nativos de cada sistema e arquitetura que o app leva DENTRO dos jars que já usa (ARQUITETURA 11.4): nenhum jar extra por sistema, então o {@code verification-metadata.xml} é o mesmo
 * nos três e o instalador de cada sistema já traz o que precisa. Se uma atualização de biblioteca tirar um nativo (o ConPTY do Windows no arm64, por exemplo), este teste reprova.
 */
class NativosEmpacotadosTest {

    private static void existe(String recurso) {
        assertNotNull(NativosEmpacotadosTest.class.getResource("/" + recurso), "falta no classpath: " + recurso);
    }

    @Test
    void pty4jTrazOConPtyDoWindowsParaX64EArm64EOForkptyDeMacELinux() {
        for (String arq : List.of("x86-64", "aarch64")) {
            for (String f : List.of("conpty.dll", "OpenConsole.exe", "win-helper.dll")) {
                existe("resources/com/pty4j/native/win/" + arq + "/" + f);
            }
        }
        existe("resources/com/pty4j/native/linux/x86-64/libpty.so");
        existe("resources/com/pty4j/native/linux/aarch64/libpty.so");
        existe("resources/com/pty4j/native/darwin/libpty.dylib");
        existe("resources/com/pty4j/native/darwin/pty4j-unix-spawn-helper");
    }

    @Test
    void jnaTrazOJnidispatchDeCadaSistemaEArquitetura() {
        for (String r : List.of("win32-x86-64/jnidispatch.dll", "win32-aarch64/jnidispatch.dll", "linux-x86-64/libjnidispatch.so", "linux-aarch64/libjnidispatch.so",
            "darwin-x86-64/libjnidispatch.jnilib", "darwin-aarch64/libjnidispatch.jnilib")) {
            existe("com/sun/jna/" + r);
        }
    }

    @Test
    void flatLafTrazOsNativosDeWindowsLinuxEMac() {
        for (String r : List.of("flatlaf-windows-x86_64.dll", "flatlaf-windows-arm64.dll", "libflatlaf-linux-x86_64.so", "libflatlaf-linux-arm64.so", "libflatlaf-macos-arm64.dylib",
            "libflatlaf-macos-x86_64.dylib")) {
            existe("com/formdev/flatlaf/natives/" + r);
        }
    }

    @Test
    void osNativosSaemNaPastaDeDadosDoAppEnaoNaTemporariaDoSistema(@TempDir Path tmp) throws Exception {
        String pty = System.getProperty(NativosDoApp.PROPRIEDADE_DO_PTY);
        String jna = System.getProperty(NativosDoApp.PROPRIEDADE_DO_JNA);
        try {
            System.clearProperty(NativosDoApp.PROPRIEDADE_DO_PTY);
            System.clearProperty(NativosDoApp.PROPRIEDADE_DO_JNA);
            CaminhosApp c = CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
            NativosDoApp.apontar(c);
            assertEquals(tmp.resolve("dados").resolve("nativos").toAbsolutePath().normalize().toString(), System.getProperty(NativosDoApp.PROPRIEDADE_DO_PTY));
            assertEquals(System.getProperty(NativosDoApp.PROPRIEDADE_DO_PTY), System.getProperty(NativosDoApp.PROPRIEDADE_DO_JNA));
            // quem já apontou outra pasta (quem roda o app) não é desfeito
            System.setProperty(NativosDoApp.PROPRIEDADE_DO_PTY, "/outra");
            NativosDoApp.apontar(c);
            assertEquals("/outra", System.getProperty(NativosDoApp.PROPRIEDADE_DO_PTY));
        } finally {
            restaurar(NativosDoApp.PROPRIEDADE_DO_PTY, pty);
            restaurar(NativosDoApp.PROPRIEDADE_DO_JNA, jna);
        }
    }

    private static void restaurar(String nome, String valor) {
        if (valor == null) {
            System.clearProperty(nome);
        } else {
            System.setProperty(nome, valor);
        }
    }
}
