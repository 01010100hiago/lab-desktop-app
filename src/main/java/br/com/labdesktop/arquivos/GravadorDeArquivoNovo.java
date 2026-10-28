package br.com.labdesktop.arquivos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Grava um arquivo NOVO escolhido pelo usuário (nunca sobrescreve). Fica fora da {@code ui}: é entrada e saída de disco. */
public final class GravadorDeArquivoNovo {

    private GravadorDeArquivoNovo() {
    }

    /** O destino sem sobrescrever nada: se o nome existe, vira {@code nome-2.ext}, {@code nome-3.ext}... */
    public static Path livre(Path pedido) {
        if (!Files.exists(pedido)) {
            return pedido;
        }
        String nome = pedido.getFileName().toString();
        int ponto = nome.lastIndexOf('.');
        String base = ponto > 0 ? nome.substring(0, ponto) : nome;
        String ext = ponto > 0 ? nome.substring(ponto) : "";
        for (int i = 2; i < 1000; i++) {
            Path p = pedido.resolveSibling(base + "-" + i + ext);
            if (!Files.exists(p)) {
                return p;
            }
        }
        return pedido.resolveSibling(base + "-" + System.currentTimeMillis() + ext);
    }

    /** Arquivo novo (falha se já existe), só para o usuário. */
    public static void gravar(Path destino, byte[] bytes) throws IOException {
        Files.write(destino, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
}
