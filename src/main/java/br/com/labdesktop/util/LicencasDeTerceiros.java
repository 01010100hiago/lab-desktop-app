package br.com.labdesktop.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Os textos de licença que a tela "Sobre" mostra, lidos do JAR (nada de rede): o resumo em português, o texto da licença MIT do app e a tabela de
 * licenças de terceiros. Os dois últimos são os arquivos {@code LICENSE} e {@code THIRD-PARTY-NOTICES.md} da raiz do repositório, que o Gradle copia para o jar
 * ({@code processResources}): uma só fonte, e um teste confere que a tabela tem cada biblioteca do {@code gradle/verification-metadata.xml}.
 */
public final class LicencasDeTerceiros {

    private static final String PASTA = "/br/com/labdesktop/sobre/";

    /** Uma linha da tabela de terceiros. */
    public record Licenca(String nome, String versao, String licenca, String uso) { }

    private LicencasDeTerceiros() { }

    private static String ler(String arquivo) {
        try (InputStream in = LicencasDeTerceiros.class.getResourceAsStream(PASTA + arquivo)) {
            if (in == null) {
                throw new IllegalStateException("Faltou o recurso " + arquivo + " no jar.");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Não consegui ler o recurso " + arquivo + ".", e);
        }
    }

    /** O resumo em português: software livre e gratuito, licença MIT, sem garantia. */
    public static String resumoEmPortugues() {
        return ler("sobre.txt").strip();
    }

    /** O texto oficial da licença MIT do app (o arquivo {@code LICENSE}). */
    public static String textoDaLicenca() {
        return ler("LICENSE").strip();
    }

    /** O arquivo {@code THIRD-PARTY-NOTICES.md} inteiro. */
    public static String avisosDeTerceiros() {
        return ler("THIRD-PARTY-NOTICES.md");
    }

    /** A tabela de terceiros do jar. */
    public static List<Licenca> doJar() {
        return interpretar(avisosDeTerceiros());
    }

    /** As linhas da tabela do markdown: {@code | nome | versão | licença | uso |} (o cabeçalho e o traço de separação ficam de fora). */
    static List<Licenca> interpretar(String markdown) {
        List<Licenca> lista = new ArrayList<>();
        boolean naTabela = false;
        for (String linha : markdown.split("\\R")) {
            String t = linha.strip();
            if (!t.startsWith("|")) {
                naTabela = false;
                continue;
            }
            if (t.startsWith("| Biblioteca")) {
                naTabela = true;
                continue;
            }
            if (!naTabela || t.matches("\\|[\\s|:-]+\\|?")) {
                continue;
            }
            String[] c = t.substring(1, t.endsWith("|") ? t.length() - 1 : t.length()).split("\\|", -1);
            if (c.length >= 4) {
                lista.add(new Licenca(c[0].strip(), c[1].strip(), c[2].strip(), c[3].strip()));
            }
        }
        return List.copyOf(lista);
    }
}
