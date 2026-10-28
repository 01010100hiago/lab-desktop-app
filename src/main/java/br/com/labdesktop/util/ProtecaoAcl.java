package br.com.labdesktop.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.LoggerFactory;

/**
 * A lista de acesso (ACL) do NTFS no lugar das permissões POSIX: a ACL do arquivo ou da pasta passa a ter UMA entrada de permissão, a do DONO, com todos os direitos (nas pastas,
 * marcada para ser herdada pelos arquivos e pastas que nascerem dentro: {@code FILE_INHERIT} e {@code DIRECTORY_INHERIT}). Nada de processo ({@code icacls}): é a API do Java.
 *
 * <p><b>O que NÃO está provado sem um Windows de verdade:</b> o Java grava a ACL sem marcar "não herdar do pai", então entradas HERDADAS (por exemplo o grupo Administradores e o SISTEMA, que
 * o perfil do usuário já dá) podem continuar valendo; outros usuários comuns não estão na pasta do perfil. Depois de gravar, o app relê a ACL e registra no log quem mais tem acesso
 * ({@link #quemMaisTemAcesso}); nunca falha por isso (o cofre é cifrado com a senha-mestra; a ACL é uma segunda trava). A vista da ACL entra por uma função, para o teste dar uma falsa.
 */
public final class ProtecaoAcl implements ProtecaoDeArquivos {

    private final Function<Path, AclFileAttributeView> vista;

    public ProtecaoAcl() {
        this(p -> Files.getFileAttributeView(p, AclFileAttributeView.class));
    }

    /** Com a vista da ACL trocável (testes). */
    public ProtecaoAcl(Function<Path, AclFileAttributeView> vista) {
        this.vista = vista;
    }

    @Override
    public FileAttribute<?>[] atributosDeArquivo() {
        return new FileAttribute<?>[0]; // a criação no NTFS não aceita ACL pelo atributo do Java: restringirAoDono vem logo depois
    }

    @Override
    public FileAttribute<?>[] atributosDePasta() {
        return new FileAttribute<?>[0];
    }

    @Override
    public void restringirAoDono(Path caminho, boolean pasta) throws IOException {
        AclFileAttributeView v = vista.apply(caminho);
        if (v == null) {
            throw new IOException("Este sistema de arquivos não deixa mudar a lista de acesso de " + caminho.getFileName() + ".");
        }
        UserPrincipal proprietario = v.getOwner();
        Set<AclEntryFlag> flags = pasta ? EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT) : EnumSet.noneOf(AclEntryFlag.class);
        AclEntry deDono = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(proprietario).setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(flags).build();
        v.setAcl(List.of(deDono));
        List<String> outros = quemMaisTemAcesso(v.getAcl(), proprietario);
        if (!outros.isEmpty()) {
            LoggerFactory.getLogger(ProtecaoAcl.class).debug("Depois de restringir {}, ainda têm acesso (herdado): {}", caminho.getFileName(), outros);
        }
    }

    /**
     * No NTFS não dá para dizer com segurança o que é "aberto demais" (os nomes dos grupos do sistema mudam com o idioma e as entradas herdadas voltam): refaz a ACL só do dono,
     * sempre, e não alarma o usuário. Só uma falha de verdade (exceção) sobe.
     */
    @Override
    public boolean corrigirSeAberta(Path caminho, boolean pasta) throws IOException {
        restringirAoDono(caminho, pasta);
        return false;
    }

    @Override
    public String descrever(Path caminho) throws IOException {
        AclFileAttributeView v = vista.apply(caminho);
        if (v == null) {
            return "?";
        }
        return quemMaisTemAcesso(v.getAcl(), v.getOwner()).isEmpty() ? "só o dono" : "dono e mais " + quemMaisTemAcesso(v.getAcl(), v.getOwner()).size();
    }

    /** Os nomes de quem, além do dono, tem uma entrada de PERMISSÃO na ACL. */
    static List<String> quemMaisTemAcesso(List<AclEntry> acl, UserPrincipal proprietario) {
        return acl.stream().filter(e -> e.type() == AclEntryType.ALLOW && !e.principal().equals(proprietario)).map(e -> e.principal().getName()).distinct().toList();
    }
}
