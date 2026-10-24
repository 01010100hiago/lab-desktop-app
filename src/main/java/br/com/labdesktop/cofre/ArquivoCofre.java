package br.com.labdesktop.cofre;

import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Permissoes;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Set;

/**
 * O formato binário do cofre e a gravação em disco (ARQUITETURA 5.2 e 5.7). Não conhece senha nem JSON.
 *
 * <pre>
 * deslocamento  tamanho  campo
 *  0             8       magic "LABCOFRE"
 *  8             1       versão do formato (1)
 *  9             1       id do KDF (1 = Argon2id v1.3)
 * 10             4       memória em KiB (u32, big-endian)
 * 14             1       iterações (u8)
 * 15             1       paralelismo (u8)
 * 16            16       sal
 * 32            12       nonce (novo a cada gravação)
 * 44           n+16      texto cifrado AES-256-GCM, tag de 16 bytes no fim
 * </pre>
 * O AAD do GCM são os 32 primeiros bytes (cabeçalho mais sal).
 */
final class ArquivoCofre {

    static final byte[] MAGIC = "LABCOFRE".getBytes(StandardCharsets.US_ASCII);
    static final int VERSAO_DO_FORMATO = 1;
    static final int KDF_ARGON2ID = 1;
    static final int TAMANHO_SAL = 16;
    static final int TAMANHO_NONCE = CifraAesGcm.TAMANHO_NONCE;
    /** Cabeçalho completo (até o fim do nonce). */
    static final int TAMANHO_CABECALHO = 44;
    /** Bytes autenticados como AAD: cabeçalho até o sal, sem o nonce. */
    static final int TAMANHO_AAD = 32;
    /** Arquivo maior que isto é recusado sem ser lido (nenhum cofre de verdade chega perto). */
    static final long TAMANHO_MAXIMO = 8L * 1024 * 1024;

    /** O que o cabeçalho diz. Sem segredo (sal e nonce são públicos). */
    record Cabecalho(ParametrosKdf parametros, byte[] sal, byte[] nonce) { }

    /** O que fazer com o {@code .bak} ao gravar. */
    enum ModoDeBackup {
        /** Comportamento normal: o cofre atual vira {@code .bak} e o novo toma o lugar dele. */
        ROTACIONAR,
        /** Deixa o {@code .bak} como está (recuperação: o cofre atual é o que está ruim, não pode virar backup). */
        MANTER,
        /** Depois de gravar, o {@code .bak} passa a ser cópia do novo (reforço de KDF: não deixar cópia fraca para trás). */
        REPLICAR_NOVO
    }

    private ArquivoCofre() { }

    // ------------------------------------------------------------------------------------------
    // Formato
    // ------------------------------------------------------------------------------------------

    /** Monta o arquivo: cabeçalho de 44 bytes mais o texto cifrado. */
    static byte[] montar(Cabecalho cabecalho, byte[] cifrado) {
        ParametrosKdf p = cabecalho.parametros();
        ByteBuffer buf = ByteBuffer.allocate(TAMANHO_CABECALHO + cifrado.length);
        buf.put(MAGIC);
        buf.put((byte) VERSAO_DO_FORMATO);
        buf.put((byte) KDF_ARGON2ID);
        buf.putInt(p.memoriaKib());
        buf.put((byte) p.iteracoes());
        buf.put((byte) p.paralelismo());
        buf.put(cabecalho.sal());
        buf.put(cabecalho.nonce());
        buf.put(cifrado);
        return buf.array();
    }

    /**
     * Lê e VALIDA o cabeçalho antes de qualquer custo: um arquivo hostil não pode provocar
     * {@code OutOfMemoryError} com parâmetros absurdos de KDF.
     */
    static Cabecalho lerCabecalho(byte[] arquivo) throws CofreCorrompidoException {
        if (arquivo.length > TAMANHO_MAXIMO) {
            throw new CofreCorrompidoException("O arquivo do cofre é grande demais.");
        }
        if (arquivo.length < TAMANHO_CABECALHO + CifraAesGcm.TAMANHO_TAG) {
            throw new CofreCorrompidoException("O arquivo do cofre está truncado.");
        }
        ByteBuffer buf = ByteBuffer.wrap(arquivo);
        byte[] magic = new byte[MAGIC.length];
        buf.get(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new CofreCorrompidoException("Este arquivo não é um cofre do Lab Desktop.");
        }
        int versao = buf.get() & 0xFF;
        if (versao != VERSAO_DO_FORMATO) {
            throw new CofreCorrompidoException("Versão do formato do cofre não suportada: " + versao + ".");
        }
        int kdf = buf.get() & 0xFF;
        if (kdf != KDF_ARGON2ID) {
            throw new CofreCorrompidoException("Função de derivação de chave desconhecida: " + kdf + ".");
        }
        long memoria = buf.getInt() & 0xFFFFFFFFL;
        int iteracoes = buf.get() & 0xFF;
        int paralelismo = buf.get() & 0xFF;
        if (!ParametrosKdf.dentroDaFaixa(memoria, iteracoes, paralelismo)) {
            throw new CofreCorrompidoException("Parâmetros do Argon2id fora da faixa aceita (m=" + memoria
                + " KiB, t=" + iteracoes + ", p=" + paralelismo + ").");
        }
        byte[] sal = new byte[TAMANHO_SAL];
        buf.get(sal);
        byte[] nonce = new byte[TAMANHO_NONCE];
        buf.get(nonce);
        return new Cabecalho(new ParametrosKdf((int) memoria, iteracoes, paralelismo), sal, nonce);
    }

    /** Os 32 primeiros bytes (cabeçalho até o sal). */
    static byte[] aad(byte[] arquivo) {
        return Arrays.copyOfRange(arquivo, 0, TAMANHO_AAD);
    }

    // ------------------------------------------------------------------------------------------
    // Disco
    // ------------------------------------------------------------------------------------------

    /**
     * Lê o arquivo inteiro, recusando acima de {@link #TAMANHO_MAXIMO} ANTES de alocar.
     */
    static byte[] ler(Path arquivo) throws IOException, CofreCorrompidoException {
        long tamanho = Files.size(arquivo);
        if (tamanho > TAMANHO_MAXIMO) {
            throw new CofreCorrompidoException("O arquivo do cofre é grande demais (" + tamanho + " bytes).");
        }
        return Files.readAllBytes(arquivo);
    }

    /**
     * Grava o cofre de forma atômica (ARQUITETURA 5.7): temporário já com {@code rw-------}, {@code fsync},
     * troca por {@code rename} atômico. Uma queda em qualquer ponto deixa ou o cofre antigo inteiro ou o novo
     * inteiro, e o {@code .bak} cobre o resto.
     */
    static void gravar(CaminhosApp caminhos, byte[] conteudo, ModoDeBackup modo) throws IOException {
        Path principal = caminhos.arquivoCofre();
        Path bak = caminhos.arquivoCofreBak();
        Path tmp = caminhos.arquivoCofreTmp();

        escreverPrivado(tmp, conteudo);
        if (modo == ModoDeBackup.ROTACIONAR && Files.exists(principal)) {
            // Cópia, não rename: o principal fica no lugar até o rename atômico do novo.
            escreverPrivado(bak, Files.readAllBytes(principal));
        }
        try {
            Files.move(tmp, principal, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Mesma pasta, mesmo volume: não deveria acontecer. Se acontecer, melhor um move comum que perder a gravação.
            Files.move(tmp, principal, StandardCopyOption.REPLACE_EXISTING);
        }
        sincronizarPasta(caminhos.pastaDados());
        if (modo == ModoDeBackup.REPLICAR_NOVO) {
            escreverPrivado(bak, conteudo);
        }
    }

    /** Cria o arquivo novo com {@code rw-------} (apagando um antigo), grava, {@code fsync}. */
    private static void escreverPrivado(Path destino, byte[] conteudo) throws IOException {
        Files.deleteIfExists(destino);
        try (FileChannel canal = abrirParaCriar(destino)) {
            ByteBuffer buf = ByteBuffer.wrap(conteudo);
            while (buf.hasRemaining()) {
                canal.write(buf);
            }
            canal.force(true);
        }
    }

    private static FileChannel abrirParaCriar(Path destino) throws IOException {
        Set<StandardOpenOption> opcoes = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            return FileChannel.open(destino, opcoes, Permissoes.atributoDeArquivoPrivado());
        } catch (UnsupportedOperationException e) {
            return FileChannel.open(destino, opcoes);
        }
    }

    /** {@code fsync} da pasta, para o rename sobreviver a uma queda de energia. Falhar aqui não invalida a gravação. */
    private static void sincronizarPasta(Path pasta) {
        try (FileChannel canal = FileChannel.open(pasta, StandardOpenOption.READ)) {
            canal.force(true);
        } catch (IOException | RuntimeException e) {
            // Melhor esforço.
        }
    }
}
