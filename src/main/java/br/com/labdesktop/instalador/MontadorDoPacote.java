package br.com.labdesktop.instalador;

import br.com.labdesktop.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Monta o pacote do agente ({@link PacoteDoAgente}) a partir do repositório do agente público ({@code lab-desktop-agente}) num commit
 * FIXADO: lê o {@code package.json} do commit (o campo {@code files} é a lista do que o agente precisa para subir) e cada arquivo dela com
 * {@code git show <commit>:<arquivo>} (nada depende do que está na pasta de trabalho do repositório), gera um {@code tar.gz}
 * DETERMINÍSTICO (entradas em ordem, data zero, dono zero) e grava {@code agente-<versao>.tar.gz} mais o descritor {@code agente.json}
 * (versão, commit, SHA-256) em {@code src/main/resources/agente/}. A versão é {@code <base>-<7 primeiros dígitos do commit>}. O arquivo
 * {@code VERSAO} NÃO vem do repositório: o montador o escreve com a versão do pacote (a do repositório é só a base), e é ele que o agente
 * devolve em {@code /api/versao} para o app oferecer "Atualizar agente".
 *
 * <p>É Java puro de propósito (o app roda também no Windows, e a CI de lá não tem bash): a tarefa Gradle
 * {@code empacotarAgente} só chama esta classe. A montagem é manual, quando se muda o commit fixado; o build normal usa o que está
 * guardado e o confere ({@code PacoteDoAgenteTest}).
 */
public final class MontadorDoPacote {

    /**
     * O mínimo sem o qual o pacote não faz sentido: o Dockerfile que o app usa no {@code docker build} e o ponto de entrada do agente. O resto
     * da lista vem do {@code package.json} do agente ({@link #arquivosDoCommit}), para o app nunca ficar com uma lista própria que se afasta da
     * dele.
     */
    public static final List<String> OBRIGATORIOS = List.of("Dockerfile.monitor", "painel.js");

    /** O arquivo que o montador escreve sozinho (ver a classe). */
    public static final String ARQUIVO_DE_VERSAO = "VERSAO";

    private static final Pattern COMMIT_COMPLETO = Pattern.compile("^[0-9a-f]{40}$");
    /** Um arquivo da raiz: letras, dígitos, ponto, hífen e sublinhado, sem começar por ponto nem hífen, até 99 caracteres (o limite do nome no tar). */
    private static final Pattern NOME_SIMPLES = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,98}$");

    private MontadorDoPacote() { }

    /**
     * Uso: {@code MontadorDoPacote <pasta do repositório do agente> <commit de 40 dígitos> <versão base> <pasta de destino>}.
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 4) {
            throw new IllegalArgumentException("Uso: MontadorDoPacote <repositorio-do-agente> <commit> <versaoBase> <destino>");
        }
        PacoteDoAgente p = montar(Path.of(args[0]), args[1], args[2], Path.of(args[3]));
        System.out.println("Pacote do agente: " + p + " sha256 " + p.sha256());
    }

    /** Monta e grava; devolve o pacote. */
    public static PacoteDoAgente montar(Path painel, String commit, String versaoBase, Path destino) throws IOException, InterruptedException {
        if (!COMMIT_COMPLETO.matcher(commit).matches()) {
            throw new IllegalArgumentException("O commit fixado precisa ter os 40 dígitos hexadecimais (não vale nome de branch).");
        }
        String versao = versaoBase + "-" + commit.substring(0, 7);
        git(painel, "cat-file", "-e", commit + "^{commit}");
        List<String> nomes = arquivosDoCommit(painel, commit);
        Map<String, byte[]> arquivos = new TreeMap<>();
        for (String nome : nomes) {
            arquivos.put(nome, git(painel, "show", commit + ":" + nome));
        }
        PacoteDoAgente pacote = de(arquivos, versao, commit);

        Files.createDirectories(destino);
        try (Stream<Path> antigos = Files.list(destino)) {
            for (Path a : antigos.filter(f -> f.getFileName().toString().matches("^agente-.*\\.tar\\.gz$")).toList()) {
                Files.delete(a);
            }
        }
        Files.write(destino.resolve(pacote.nomeDoArquivo()), pacote.conteudo());
        ObjectNode d = Json.mapper().createObjectNode();
        d.put("versao", pacote.versao());
        d.put("commit", pacote.commit());
        d.put("arquivo", pacote.nomeDoArquivo());
        d.put("sha256", pacote.sha256());
        ArrayNode lista = d.putArray("arquivos");
        nomes.forEach(lista::add);
        lista.add(ARQUIVO_DE_VERSAO);
        Files.writeString(destino.resolve("agente.json"), Json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(d) + "\n",
            StandardCharsets.UTF_8);
        return pacote;
    }

    /**
     * A lista de arquivos do agente naquele commit: o campo {@code files} do {@code package.json} dele, menos o {@code VERSAO} (que o
     * montador escreve). Cada nome é conferido ANTES de ir a um comando do git ou a um tar: só um nome simples, sem pasta, sem {@code ..}, sem
     * barra e sem começar por hífen; e os {@link #OBRIGATORIOS} têm de estar lá.
     */
    public static List<String> arquivosDoCommit(Path painel, String commit) throws IOException, InterruptedException {
        byte[] bruto = git(painel, "show", commit + ":package.json");
        JsonNode pacoteJson;
        try {
            pacoteJson = Json.mapper().readTree(bruto);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IOException("O package.json do agente não é um JSON válido.", e);
        }
        JsonNode files = pacoteJson.path("files");
        if (!files.isArray() || files.isEmpty()) {
            throw new IOException("O package.json do agente não tem o campo \"files\" (a lista do que vai no pacote).");
        }
        java.util.Set<String> nomes = new java.util.TreeSet<>();
        for (JsonNode f : files) {
            String nome = f.asText("");
            if (!NOME_SIMPLES.matcher(nome).matches()) {
                throw new IOException("O campo \"files\" do package.json tem um nome que o pacote não aceita (só arquivos na raiz, sem pastas): " + nome);
            }
            if (!nome.equals(ARQUIVO_DE_VERSAO)) {
                nomes.add(nome);
            }
        }
        for (String o : OBRIGATORIOS) {
            if (!nomes.contains(o)) {
                throw new IOException("O campo \"files\" do package.json do agente não lista " + o + ".");
            }
        }
        return List.copyOf(nomes);
    }

    /** O pacote a partir dos arquivos (o conteúdo de cada um), sem git: é o que os testes usam. */
    public static PacoteDoAgente de(Map<String, byte[]> arquivos, String versao, String commit) throws IOException {
        Map<String, byte[]> todos = new TreeMap<>(arquivos);
        todos.put("VERSAO", (versao + "\n").getBytes(StandardCharsets.UTF_8));
        return PacoteDoAgente.de(versao, commit.length() >= 7 ? commit : "0000000", tarGz(todos));
    }

    /** Um {@code tar.gz} que só depende do que se põe nele: entradas em ordem de nome, data zero, dono zero, modo 0644. */
    public static byte[] tarGz(Map<String, byte[]> arquivos) throws IOException {
        ByteArrayOutputStream bruto = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bruto)) {
            for (Map.Entry<String, byte[]> e : new TreeMap<>(arquivos).entrySet()) {
                escreverEntrada(gz, e.getKey(), e.getValue());
            }
            gz.write(new byte[1024]); // o fim do tar: dois blocos de zeros
        }
        return bruto.toByteArray();
    }

    private static void escreverEntrada(OutputStream saida, String nome, byte[] conteudo) throws IOException {
        byte[] nomeBytes = nome.getBytes(StandardCharsets.UTF_8);
        if (nomeBytes.length == 0 || nomeBytes.length > 99 || nome.contains("/") || nome.contains("..")) {
            throw new IllegalArgumentException("Nome de arquivo fora do que o pacote aceita: " + nome);
        }
        byte[] c = new byte[512];
        System.arraycopy(nomeBytes, 0, c, 0, nomeBytes.length);
        octal(c, 100, 8, 0644);   // modo
        octal(c, 108, 8, 0);      // uid
        octal(c, 116, 8, 0);      // gid
        octal(c, 124, 12, conteudo.length);
        octal(c, 136, 12, 0);     // data zero: o pacote é o mesmo em qualquer máquina
        for (int i = 148; i < 156; i++) {
            c[i] = ' ';           // soma de controle: oito espaços enquanto se calcula
        }
        c[156] = '0';             // arquivo comum
        byte[] magia = "ustar\0".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magia, 0, c, 257, magia.length);
        c[263] = '0';
        c[264] = '0';
        long soma = 0;
        for (byte b : c) {
            soma += b & 0xFF;
        }
        octal(c, 148, 7, soma);
        c[155] = ' ';
        saida.write(c);
        saida.write(conteudo);
        int resto = conteudo.length % 512;
        if (resto != 0) {
            saida.write(new byte[512 - resto]);
        }
    }

    private static void octal(byte[] bloco, int de, int tamanho, long valor) {
        String o = Long.toOctalString(valor);
        if (o.length() > tamanho - 1) {
            throw new IllegalArgumentException("Valor grande demais para o campo do tar.");
        }
        String completo = "0".repeat(tamanho - 1 - o.length()) + o;
        byte[] b = completo.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, bloco, de, b.length);
        bloco[de + tamanho - 1] = 0;
    }

    private static byte[] git(Path pasta, String... argumentos) throws IOException, InterruptedException {
        List<String> comando = new ArrayList<>();
        comando.add("git");
        comando.add("-C");
        comando.add(pasta.toAbsolutePath().toString());
        comando.addAll(List.of(argumentos));
        Process p = new ProcessBuilder(comando).redirectErrorStream(false).start();
        // o stderr é lido ao mesmo tempo: se ele enche o buffer do pipe enquanto só o stdout é lido, o git trava e nós com ele
        java.util.concurrent.CompletableFuture<byte[]> lendoOErro = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return p.getErrorStream().readAllBytes();
            } catch (IOException e) {
                return new byte[0];
            }
        });
        byte[] saida = p.getInputStream().readAllBytes();
        byte[] erro;
        try {
            erro = lendoOErro.get();
        } catch (java.util.concurrent.ExecutionException e) {
            erro = new byte[0];
        }
        if (p.waitFor() != 0) {
            throw new IOException("git " + String.join(" ", argumentos) + " falhou: " + new String(erro, StandardCharsets.UTF_8).strip());
        }
        return saida;
    }
}
