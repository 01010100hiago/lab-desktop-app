package br.com.labdesktop.util;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Onde as bibliotecas NATIVAS que o app extrai de dentro dos jars (o PTY do terminal local, com o {@code OpenConsole.exe} e o {@code conpty.dll} do Windows; o {@code jnidispatch} do JNA)
 * são gravadas: uma pasta do app ({@code <dados>/nativos} no Mac e no Linux, {@code %LOCALAPPDATA%\\LabDesktop\\nativos} no Windows, só do usuário) e não a pasta temporária do sistema, que no Linux é compartilhada (um programa extraído em
 * {@code /tmp} pode ser trocado por outro usuário) e no Windows é limpa por antivírus e pelo "Limpeza de disco" no meio de uma sessão. Só vale se a propriedade ainda não foi definida
 * (quem roda o app pode apontar outra pasta).
 */
public final class NativosDoApp {

    public static final String PROPRIEDADE_DO_PTY = "pty4j.tmpdir";
    public static final String PROPRIEDADE_DO_JNA = "jna.tmpdir";

    private NativosDoApp() { }

    /** A pasta dos nativos dentro da pasta de dados. */
    public static Path pasta(CaminhosApp caminhos) {
        return caminhos.pastaNativos();
    }

    /** Cria a pasta (só do usuário) e aponta o pty4j e o JNA para ela; antes de qualquer uso dos dois. */
    public static void apontar(CaminhosApp caminhos) throws IOException {
        Path pasta = pasta(caminhos);
        Permissoes.criarPastaPrivada(pasta);
        if (System.getProperty(PROPRIEDADE_DO_PTY) == null) {
            System.setProperty(PROPRIEDADE_DO_PTY, pasta.toString());
        }
        if (System.getProperty(PROPRIEDADE_DO_JNA) == null) {
            System.setProperty(PROPRIEDADE_DO_JNA, pasta.toString());
        }
    }
}
