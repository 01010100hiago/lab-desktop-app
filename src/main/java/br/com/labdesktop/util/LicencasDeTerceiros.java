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

    /** O que a tabela diz onde a licença ainda não foi conferida (a nota técnica de cada caso fica só no arquivo, fora da tabela). */
    public static final String A_CONFIRMAR = "Licença a confirmar";

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

    /** Os arquivos da pasta {@code licencas/} que vão no jar, na ordem em que a tela Sobre os mostra (um teste confere que a lista é a da pasta). */
    public static final List<String> ARQUIVOS_DE_LICENCA = List.of(
        "Apache-2.0.txt", "NOTICE-Apache.txt", "Jackson-core-embutidos.txt", "BSD-3-Clause-RSyntaxTextArea.txt", "Bouncy-Castle-Licence.md", "MIT-JSVG.txt", "MIT-SLF4J.txt",
        "OFL-1.1-Inter.txt", "OFL-1.1-JetBrains-Mono.txt", "LGPL-3.0-JediTerm.txt", "GPL-3.0.txt", "EPL-1.0-pty4j.txt", "NOTICE-pty4j.txt", "MIT-winpty.txt",
        "MIT-Windows-Terminal.txt", "MIT-zxcvbn-Dropbox.txt", "MIT-lab-desktop-agente.txt", "MIT-Feather.txt", "ISC-Lucide.txt");

    /** O texto de UM arquivo da pasta {@code licencas/}, como está no jar. */
    public static String textoDeLicenca(String arquivo) {
        return ler("licencas/" + arquivo);
    }

    /** Todos os textos de licença juntos, cada um com o nome do arquivo num cabeçalho (o que a seção "textos completos" da tela Sobre mostra). */
    public static String textosCompletos() {
        StringBuilder sb = new StringBuilder();
        for (String arquivo : ARQUIVOS_DE_LICENCA) {
            sb.append("==================== ").append(arquivo).append(" ====================\n\n").append(textoDeLicenca(arquivo).strip()).append("\n\n");
        }
        return sb.toString().strip();
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
