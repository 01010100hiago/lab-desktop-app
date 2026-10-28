package br.com.labdesktop.util;

import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;

/** Sistema de arquivos sem permissões POSIX nem ACL: não há o que restringir, e nada falha (o cofre é cifrado de qualquer jeito). */
public final class ProtecaoNenhuma implements ProtecaoDeArquivos {

    @Override
    public FileAttribute<?>[] atributosDeArquivo() {
        return new FileAttribute<?>[0];
    }

    @Override
    public FileAttribute<?>[] atributosDePasta() {
        return new FileAttribute<?>[0];
    }

    @Override
    public void restringirAoDono(Path caminho, boolean pasta) { }

    @Override
    public boolean corrigirSeAberta(Path caminho, boolean pasta) {
        return false;
    }

    @Override
    public String descrever(Path caminho) {
        return "?";
    }
}
