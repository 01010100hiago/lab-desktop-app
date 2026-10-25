package br.com.labdesktop.arquivos;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import org.apache.sshd.common.SshException;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;

/**
 * Tudo o que pode dar errado no gerenciador de arquivos, já classificado e com uma frase em português simples para o usuário
 * (ele não é técnico: "Sem permissão para abrir esta pasta", não "SSH_FX_PERMISSION_DENIED"). O texto técnico do MINA nunca
 * chega à tela; fica só na causa, para o log.
 */
public final class ErroSftp extends IOException {

    private static final long serialVersionUID = 1L;

    /** As classes de erro que a tela trata de jeitos diferentes. */
    public enum Tipo {
        /** O servidor recusou por permissão (pasta que não se lê, arquivo que não se grava). */
        SEM_PERMISSAO,
        /** O arquivo ou a pasta não existe (mais). */
        NAO_EXISTE,
        /** Já existe algo com este nome. */
        JA_EXISTE,
        /** O caminho (ou o link que leva a ele) sai da pasta em que este modo trava o gerenciador. */
        FORA_DA_RAIZ,
        /** Nome ou caminho que não vale (vazio, com barra, com caractere de controle). */
        NOME_INVALIDO,
        /** A conexão SSH não está de pé (caiu, reconectando). */
        SEM_CONEXAO,
        /** A conexão está de pé, mas o servidor não aceita SFTP. */
        SFTP_INDISPONIVEL,
        /** O usuário cancelou. */
        CANCELADO,
        /** A pasta tem coisas dentro e a operação pedia pasta vazia. */
        PASTA_NAO_VAZIA,
        /** O arquivo mudou no servidor depois que foi aberto. */
        ARQUIVO_MUDOU,
        /** O arquivo é de outro usuário: trocá-lo por um novo (salvar atômico) mudaria o usuário. */
        OUTRO_DONO,
        /** A operação é proibida pelo app (apagar a raiz, a home, "/"; mover uma pasta para dentro dela mesma). */
        PROTEGIDO,
        /** O arquivo é grande ou binário demais para abrir como texto. */
        NAO_E_TEXTO,
        /** O tamanho do que chegou não confere com o que foi enviado. */
        TAMANHO_NAO_CONFERE,
        /** Qualquer outra falha. */
        OUTRO
    }

    private final Tipo tipo;
    private final String mensagem;

    public ErroSftp(Tipo tipo, String mensagem) {
        this(tipo, mensagem, null);
    }

    public ErroSftp(Tipo tipo, String mensagem, Throwable causa) {
        super(mensagem, causa);
        this.tipo = tipo;
        this.mensagem = mensagem;
    }

    public Tipo tipo() {
        return tipo;
    }

    /** A frase para o usuário. */
    public String mensagemSimples() {
        return mensagem;
    }

    // ---------------------------------------------------------------------------------------------
    // Frases
    // ---------------------------------------------------------------------------------------------

    public static ErroSftp semPermissao(String oQue) {
        return new ErroSftp(Tipo.SEM_PERMISSAO, "Sem permissão para " + oQue + ".");
    }

    public static ErroSftp semConexao(Throwable causa) {
        return new ErroSftp(Tipo.SEM_CONEXAO, "O servidor desconectou, reconectando…", causa);
    }

    public static ErroSftp cancelado() {
        return new ErroSftp(Tipo.CANCELADO, "Cancelado.");
    }

    public static ErroSftp foraDaRaiz(String caminho) {
        return new ErroSftp(Tipo.FORA_DA_RAIZ, "Este caminho fica fora da pasta liberada nesta tela, por isso não abri.");
    }

    public static ErroSftp nomeInvalido(String motivo) {
        return new ErroSftp(Tipo.NOME_INVALIDO, motivo);
    }

    /** O arquivo que a tela abriu não está mais lá. */
    public static ErroSftp naoExiste(String nome) {
        return new ErroSftp(Tipo.NAO_EXISTE, nome == null ? "Isto não existe mais no servidor."
            : "\"" + nome + "\" não existe mais no servidor.");
    }

    // ---------------------------------------------------------------------------------------------
    // Tradução do que vem do MINA
    // ---------------------------------------------------------------------------------------------

    /**
     * Traduz uma exceção qualquer do cliente SFTP. {@code oQue} completa "Sem permissão para ..." ("abrir esta pasta",
     * "gravar este arquivo"). Se já é um {@link ErroSftp}, devolve ele mesmo.
     */
    public static ErroSftp de(Throwable erro, String oQue) {
        if (erro instanceof ErroSftp e) {
            return e;
        }
        Throwable t = erro;
        // o iterador de diretório do MINA embrulha o status em UncheckedIOException
        while ((t instanceof UncheckedIOException || t instanceof java.util.concurrent.ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof ErroSftp e) {
            return e;
        }
        if (t instanceof SftpException s) {
            return porStatus(s, oQue);
        }
        if (t instanceof InterruptedIOException) {
            return cancelado();
        }
        if (t instanceof ClosedChannelException || t instanceof EOFException || t instanceof SocketException
            || t instanceof SshException || t instanceof java.util.concurrent.TimeoutException) {
            return semConexao(t);
        }
        if (t instanceof java.nio.file.AccessDeniedException) {
            return new ErroSftp(Tipo.SEM_PERMISSAO, "Sem permissão para " + oQue + ".", t);
        }
        if (t instanceof java.nio.file.NoSuchFileException) {
            return new ErroSftp(Tipo.NAO_EXISTE, "Isto não existe.", t);
        }
        if (t instanceof java.nio.file.FileAlreadyExistsException) {
            return new ErroSftp(Tipo.JA_EXISTE, "Já existe um item com este nome.", t);
        }
        if (t instanceof IOException io) {
            String m = io.getMessage() == null ? "" : io.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (m.contains("closed") || m.contains("connection") || m.contains("session")) {
                return semConexao(t);
            }
            return new ErroSftp(Tipo.OUTRO, "Não consegui concluir: " + frase(io), t);
        }
        return new ErroSftp(Tipo.OUTRO, "Não consegui concluir: " + frase(t), t);
    }

    private static String frase(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isBlank()) {
            return "erro inesperado.";
        }
        // só a primeira linha, sem controles: a mensagem pode trazer o que o servidor escreveu
        String linha = m.lines().findFirst().orElse("").replaceAll("\\p{Cntrl}", " ").strip();
        return linha.length() > 160 ? linha.substring(0, 160) + "…" : linha;
    }

    private static ErroSftp porStatus(SftpException s, String oQue) {
        return switch (s.getStatus()) {
            case SftpConstants.SSH_FX_PERMISSION_DENIED, SftpConstants.SSH_FX_WRITE_PROTECT,
                 SftpConstants.SSH_FX_CANNOT_DELETE ->
                new ErroSftp(Tipo.SEM_PERMISSAO, "Sem permissão para " + oQue + ".", s);
            case SftpConstants.SSH_FX_NO_SUCH_FILE, SftpConstants.SSH_FX_NO_SUCH_PATH, SftpConstants.SSH_FX_INVALID_HANDLE ->
                new ErroSftp(Tipo.NAO_EXISTE, "Isto não existe (mais) no servidor.", s);
            case SftpConstants.SSH_FX_FILE_ALREADY_EXISTS ->
                new ErroSftp(Tipo.JA_EXISTE, "Já existe um item com este nome.", s);
            case SftpConstants.SSH_FX_DIR_NOT_EMPTY ->
                new ErroSftp(Tipo.PASTA_NAO_VAZIA, "A pasta não está vazia.", s);
            case SftpConstants.SSH_FX_NO_CONNECTION, SftpConstants.SSH_FX_CONNECTION_LOST ->
                semConexao(s);
            case SftpConstants.SSH_FX_NO_SPACE_ON_FILESYSTEM, SftpConstants.SSH_FX_QUOTA_EXCEEDED ->
                new ErroSftp(Tipo.OUTRO, "O servidor está sem espaço (ou a cota acabou).", s);
            default -> new ErroSftp(Tipo.OUTRO, "O servidor recusou a operação.", s);
        };
    }
}
