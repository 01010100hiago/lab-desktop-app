package br.com.labdesktop.util;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;

/**
 * Como deixar uma pasta ou um arquivo do app LEGÍVEL SÓ PELO DONO, em cada tipo de sistema de arquivos (ARQUITETURA 5.1 e 11.4). A escolha é pela CAPACIDADE do sistema de arquivos e não
 * pelo nome do sistema operacional:
 *
 * <ul>
 *   <li>{@link ProtecaoPosix} (macOS, Linux): {@code rwx------} nas pastas e {@code rw-------} nos arquivos, já na criação (sem janela em que o arquivo existe mais aberto);</li>
 *   <li>{@link ProtecaoAcl} (NTFS no Windows, que não tem POSIX): a lista de acesso (ACL) do arquivo passa a ter UMA entrada, a do dono, com acesso total (e, nas pastas, herdada pelo
 *       que nascer dentro dela);</li>
 *   <li>{@link ProtecaoNenhuma}: um sistema de arquivos sem nenhum dos dois (FAT de um pen drive): nada a fazer e o app segue (o cofre é cifrado de qualquer jeito).</li>
 * </ul>
 */
public interface ProtecaoDeArquivos {

    /** Atributos para passar na CRIAÇÃO de um arquivo (vazio onde a criação não aceita permissão). */
    FileAttribute<?>[] atributosDeArquivo();

    /** Atributos para passar na criação de uma pasta. */
    FileAttribute<?>[] atributosDePasta();

    /** Depois de criado, deixa só o usuário com acesso (idempotente). */
    void restringirAoDono(Path caminho, boolean pasta) throws IOException;

    /**
     * Confere e, se estiver aberto demais, corrige.
     *
     * @return {@code true} se estava aberto e foi corrigido (o app avisa o usuário), {@code false} se já estava certo, ou se este tipo de proteção não consegue dizer ("aberto" não existe)
     */
    boolean corrigirSeAberta(Path caminho, boolean pasta) throws IOException;

    /** As permissões em texto para mostrar ({@code rw-------}), ou {@code "?"}. */
    String descrever(Path caminho) throws IOException;

    /** A proteção deste computador: POSIX se o sistema de arquivos tem {@code posix}, senão ACL se tem {@code acl}, senão nenhuma. */
    static ProtecaoDeArquivos doSistema() {
        var vistas = FileSystems.getDefault().supportedFileAttributeViews();
        if (vistas.contains("posix")) {
            return new ProtecaoPosix();
        }
        if (vistas.contains("acl")) {
            return new ProtecaoAcl();
        }
        return new ProtecaoNenhuma();
    }
}
