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
    private final Function<Path, UserPrincipal> usuarioAtual;

    /** A vista real da ACL e o usuário que está rodando o app. */
    public ProtecaoAcl() {
        this(p -> Files.getFileAttributeView(p, AclFileAttributeView.class), ProtecaoAcl::usuarioQueRodaOApp);
    }

    /** Com a vista da ACL trocável (testes); o "usuário atual" é o dono que a vista informa. */
    public ProtecaoAcl(Function<Path, AclFileAttributeView> vista) {
        this(vista, p -> {
            try {
                return vista.apply(p).getOwner();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    /** Com a vista e o usuário atual trocáveis (testes). */
    public ProtecaoAcl(Function<Path, AclFileAttributeView> vista, Function<Path, UserPrincipal> usuarioAtual) {
        this.vista = vista;
        this.usuarioAtual = usuarioAtual;
    }

    /**
     * Quem está rodando o app ({@code user.name}), e NÃO o dono do arquivo: numa pasta criada por um administrador (instalador) o dono pode ser o grupo Administradores, e a ACL só
     * dele deixaria o próprio usuário sem acesso. Se o sistema não souber resolver o nome, cai no usuário (com um aviso no log).
     */
    static UserPrincipal usuarioQueRodaOApp(Path caminho) {
        try {
            return caminho.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
        } catch (IOException | RuntimeException e) {
            LoggerFactory.getLogger(ProtecaoAcl.class).warn("Não consegui achar o usuário atual para a ACL de {}; uso o dono do arquivo: {}", caminho.getFileName(), e.toString());
            try {
                return Files.getOwner(caminho);
            } catch (IOException e2) {
                throw new java.io.UncheckedIOException(e2);
            }
        }
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
        UserPrincipal dono;
        try {
            dono = usuarioAtual.apply(caminho);
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
        Set<AclEntryFlag> flags = pasta ? EnumSet.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT) : EnumSet.noneOf(AclEntryFlag.class);
        AclEntry deDono = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(dono).setPermissions(EnumSet.allOf(AclEntryPermission.class)).setFlags(flags).build();
        v.setAcl(List.of(deDono));
        List<String> outros = quemMaisTemAcesso(v.getAcl(), dono);
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
    static List<String> quemMaisTemAcesso(List<AclEntry> acl, UserPrincipal dono) {
        return acl.stream().filter(e -> e.type() == AclEntryType.ALLOW && !e.principal().equals(dono)).map(e -> e.principal().getName()).distinct().toList();
    }
}
