package br.com.labdesktop.instalador;

import br.com.labdesktop.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * O pacote do agente do perfil monitor que o app envia ao servidor: um {@code tar.gz} montado pela tarefa Gradle
 * {@code empacotarAgente} a partir do repositório do agente num commit FIXADO, e guardado no jar em
 * {@code agente/agente-<versao>.tar.gz} com o descritor {@code agente/agente.json} (versão, commit, nome e SHA-256). O app confere o
 * SHA-256 antes de enviar e o servidor o confere depois do envio, então um pacote adulterado ou truncado nunca é construído.
 *
 * <p>Não é um {@code record} porque carrega os bytes do pacote (a igualdade e o {@code toString} de um record os varreriam).
 */
public final class PacoteDoAgente {

    /** A versão vira tag de imagem do Docker: só o que uma tag aceita. */
    private static final Pattern VERSAO = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,40}$");
    private static final Pattern COMMIT = Pattern.compile("^[0-9a-f]{7,64}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final String DESCRITOR_PADRAO = "/agente/agente.json";
    private static volatile String descritor = DESCRITOR_PADRAO;

    private final String versao;
    private final String commit;
    private final String sha256;
    private final byte[] conteudo;

    public PacoteDoAgente(String versao, String commit, String sha256, byte[] conteudo) {
        if (versao == null || !VERSAO.matcher(versao).matches()) {
            throw new IllegalArgumentException("Versão do pacote do agente inválida.");
        }
        if (commit == null || !COMMIT.matcher(commit).matches()) {
            throw new IllegalArgumentException("Commit do pacote do agente inválido.");
        }
        if (sha256 == null || !SHA256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("SHA-256 do pacote do agente inválido.");
        }
        if (conteudo == null || conteudo.length == 0) {
            throw new IllegalArgumentException("Pacote do agente vazio.");
        }
        this.versao = versao;
        this.commit = commit;
        this.sha256 = sha256;
        this.conteudo = conteudo.clone();
    }

    /**
     * Cria um pacote de dentro dos bytes, calculando o SHA-256 (os testes e a tarefa Gradle).
     */
    public static PacoteDoAgente de(String versao, String commit, byte[] conteudo) {
        return new PacoteDoAgente(versao, commit, sha256De(conteudo), conteudo);
    }

    /**
     * O pacote que vai no jar do app.
     *
     * @throws IOException o descritor ou o arquivo não estão no jar, ou o SHA-256 não confere com o declarado
     */
    public static PacoteDoAgente doClasspath() throws IOException {
        JsonNode d;
        try (InputStream in = PacoteDoAgente.class.getResourceAsStream(descritor)) {
            if (in == null) {
                throw new PacoteDoAgenteAusenteException();
            }
            d = Json.mapper().readTree(in);
        }
        String arquivo = d.path("arquivo").asText("");
        if (!arquivo.matches("^agente-[A-Za-z0-9_.-]{1,60}\\.tar\\.gz$")) {
            throw new IOException("O descritor do pacote do agente tem um nome de arquivo inválido.");
        }
        byte[] bytes;
        try (InputStream in = PacoteDoAgente.class.getResourceAsStream(descritor.substring(0, descritor.lastIndexOf('/') + 1) + arquivo)) {
            if (in == null) {
                throw new PacoteDoAgenteAusenteException();
            }
            bytes = in.readAllBytes();
        }
        PacoteDoAgente p;
        try {
            p = new PacoteDoAgente(d.path("versao").asText(null), d.path("commit").asText(null), d.path("sha256").asText(null), bytes);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
        p.conferir();
        return p;
    }

    /** {@code true} se este app leva o pacote do agente (o descritor está no jar). */
    public static boolean disponivel() {
        return PacoteDoAgente.class.getResource(descritor) != null;
    }

    /** Só para testes: troca o caminho do descritor (por exemplo, um que não existe, para simular o app sem o agente). Devolve o anterior. */
    public static String trocarDescritorParaTeste(String caminho) {
        String anterior = descritor;
        descritor = caminho == null ? DESCRITOR_PADRAO : caminho;
        return anterior;
    }

    /** Confere o SHA-256 declarado contra os bytes. */
    public void conferir() throws IOException {
        if (!sha256De(conteudo).equals(sha256)) {
            throw new IOException("O pacote do agente que vai no app não confere com o SHA-256 declarado: o app foi gerado errado.");
        }
    }

    public static String sha256De(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String versao() {
        return versao;
    }

    public String commit() {
        return commit;
    }

    public String sha256() {
        return sha256;
    }

    public byte[] conteudo() {
        return conteudo.clone();
    }

    public int tamanho() {
        return conteudo.length;
    }

    /** O nome do arquivo do pacote, no app e no servidor. */
    public String nomeDoArquivo() {
        return "agente-" + versao + ".tar.gz";
    }

    @Override
    public String toString() {
        return "PacoteDoAgente[" + versao + ", commit " + commit + ", " + conteudo.length + " bytes]";
    }
}
