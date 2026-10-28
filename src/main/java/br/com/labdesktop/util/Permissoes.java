package br.com.labdesktop.util;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.util.Set;
import org.slf4j.LoggerFactory;

/**
 * "Só o dono" para o que o app grava (pasta, arquivos, trava, log, backups): a porta única para {@link ProtecaoDeArquivos}, que escolhe pelo sistema de arquivos POSIX (0700 e 0600), ACL
 * do NTFS (a entrada do dono) ou nada. Ninguém mais chama {@code PosixFilePermissions} direto: no Windows isso lança {@code UnsupportedOperationException}.
 */
public final class Permissoes {

    private static volatile ProtecaoDeArquivos protecao = ProtecaoDeArquivos.doSistema();

    private Permissoes() { }

    /** Troca a proteção (testes: a do Windows num Mac). Devolve a anterior. */
    public static ProtecaoDeArquivos trocar(ProtecaoDeArquivos nova) {
        ProtecaoDeArquivos antes = protecao;
        protecao = nova;
        return antes;
    }

    public static ProtecaoDeArquivos atual() {
        return protecao;
    }

    /** Atributos para criar um arquivo já só do dono (vazio onde a criação não aceita permissão). */
    public static FileAttribute<?>[] atributosDeArquivoPrivado() {
        return protecao.atributosDeArquivo();
    }

    /** Atributos para criar pastas já só do usuário. */
    public static FileAttribute<?>[] atributosDePastaPrivada() {
        return protecao.atributosDePasta();
    }

    /** Cria o arquivo (falha se existe) já só do usuário. */
    public static void criarArquivoPrivado(Path arquivo) throws IOException {
        Files.createFile(arquivo, protecao.atributosDeArquivo());
        restringirSemFalhar(arquivo, false);
    }

    /**
     * Abre (criando, se for o caso) um canal já só do dono. Num arquivo NOVO em sistema sem permissões na criação (NTFS) a restrição vem logo depois da criação; se ela falhar, o app
     * segue (log de aviso): o conteúdo sensível é cifrado, e a trava do usuário é uma segunda camada.
     */
    public static FileChannel abrirCanalPrivado(Path arquivo, Set<? extends OpenOption> opcoes) throws IOException {
        boolean existia = Files.exists(arquivo);
        FileChannel canal = FileChannel.open(arquivo, opcoes, protecao.atributosDeArquivo());
        if (!existia) {
            restringirSemFalhar(arquivo, false);
        }
        return canal;
    }

    /** Cria a pasta (e as que faltarem) só do usuário. Se já existe, só confere e corrige. */
    public static void criarPastaPrivada(Path pasta) throws IOException {
        if (!Files.exists(pasta)) {
            Files.createDirectories(pasta, protecao.atributosDePasta());
            restringirSemFalhar(pasta, true);
        }
        corrigirPastaSeAberta(pasta);
    }

    /** @return {@code true} se estava aberta e foi corrigida (o app avisa o usuário) */
    public static boolean corrigirPastaSeAberta(Path pasta) throws IOException {
        return protecao.corrigirSeAberta(pasta, true);
    }

    /** @return {@code true} se estava aberto e foi corrigido (o app avisa o usuário) */
    public static boolean corrigirArquivoSeAberto(Path arquivo) throws IOException {
        return protecao.corrigirSeAberta(arquivo, false);
    }

    /** As permissões atuais em texto ({@code rw-------}), ou {@code "?"} se o sistema de arquivos não souber dizer. */
    public static String comoTexto(Path caminho) throws IOException {
        return protecao.descrever(caminho);
    }

    private static void restringirSemFalhar(Path caminho, boolean pasta) {
        try {
            protecao.restringirAoDono(caminho, pasta);
        } catch (IOException | RuntimeException e) {
            // Logger só aqui, preguiçoso: criar um logger na carga da classe fixava o destino do log antes do ConfiguracaoDeLog.
            LoggerFactory.getLogger(Permissoes.class).warn("Não consegui deixar {} só para o usuário: {}", caminho.getFileName(), e.toString());
        }
    }
}
