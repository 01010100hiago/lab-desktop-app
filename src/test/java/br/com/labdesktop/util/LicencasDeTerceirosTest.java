package br.com.labdesktop.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.util.LicencasDeTerceiros.Licenca;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A licença do app e a tabela de licenças de terceiros: o texto do jar é o da raiz do repositório, a licença é a MIT do titular, e a tabela tem TODA biblioteca que o
 * build leva (confere com o {@code gradle/verification-metadata.xml}, para uma biblioteca nova sem licença registrada derrubar o build).
 */
class LicencasDeTerceirosTest {

    /** Os componentes do verification-metadata que não são biblioteca do app: pais e listas de versões (POMs), e o que só os testes usam. */
    private static final Set<String> NAO_E_BIBLIOTECA_DO_APP = Set.of(
        "oss-parent", "jackson-base", "jackson-bom", "jackson-parent", "jackson-modules-java8", "zxing-parent", "apache", "sshd", "slf4j-bom", "slf4j-parent",
        "apiguardian-api", "junit-bom", "junit-jupiter", "junit-jupiter-api", "junit-jupiter-engine", "junit-jupiter-params", "junit-platform-commons", "junit-platform-engine",
        "junit-platform-launcher", "opentest4j", "spring-framework-bom", "testcontainers-bom");

    @Test
    void aLicencaDoJarEIgualAoArquivoDaRaizEEMit() throws IOException {
        String raiz = Files.readString(Path.of("LICENSE")).strip();
        assertEquals(raiz, LicencasDeTerceiros.textoDaLicenca(), "o jar leva o LICENSE da raiz (processResources)");
        assertTrue(raiz.startsWith("MIT License"));
        assertTrue(raiz.contains("Copyright (c) 2026 Thiago Bezerra"));
        assertTrue(raiz.contains("THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND"));
    }

    @Test
    void aLicencaDoInstaladorDoWindowsTemOMesmoTextoMitDoLICENSE() throws IOException {
        String licenca = Files.readString(Path.of("LICENSE")).strip();
        String instalador = Files.readString(Path.of("empacotamento", "windows", "licenca.txt"));
        assertTrue(instalador.contains(licenca), "o texto da licença do instalador é o do LICENSE");
        assertFalse(instalador.contains("provisório"), "o texto provisório saiu");
    }

    @Test
    void oResumoEmPortuguesDizLivreGratuitoMitESemGarantia() {
        String r = LicencasDeTerceiros.resumoEmPortugues();
        assertTrue(r.contains("software livre e gratuito"));
        assertTrue(r.contains("licença MIT"));
        assertTrue(r.contains("Sem garantia"));
        assertFalse(r.contains("<html"));
    }

    @Test
    void aTabelaDoJarEIgualAoArquivoDaRaiz() throws IOException {
        assertEquals(Files.readString(Path.of("THIRD-PARTY-NOTICES.md")), LicencasDeTerceiros.avisosDeTerceiros());
        List<Licenca> l = LicencasDeTerceiros.doJar();
        assertTrue(l.size() >= 28, "linhas: " + l.size());
        for (Licenca x : l) {
            assertFalse(x.nome().isBlank() || x.versao().isBlank() || x.licenca().isBlank(), "linha incompleta: " + x);
        }
    }

    /** O nome que cada biblioteca do build tem na tabela (a coluna "Biblioteca"), pelo nome do artefato no {@code verification-metadata.xml}. */
    private static final java.util.Map<String, String> NOME_NA_TABELA = java.util.Map.ofEntries(
        java.util.Map.entry("flatlaf", "FlatLaf"), java.util.Map.entry("flatlaf-extras", "FlatLaf Extras"),
        java.util.Map.entry("flatlaf-fonts-inter", "FlatLaf Fonts Inter"), java.util.Map.entry("flatlaf-fonts-jetbrains-mono", "FlatLaf Fonts JetBrains Mono"),
        java.util.Map.entry("jsvg", "JSVG"),
        java.util.Map.entry("sshd-core", "Apache MINA SSHD (sshd-core)"), java.util.Map.entry("sshd-common", "Apache MINA SSHD (sshd-common)"),
        java.util.Map.entry("sshd-sftp", "Apache MINA SSHD (sshd-sftp)"),
        java.util.Map.entry("bcprov-jdk18on", "Bouncy Castle (bcprov-jdk18on)"), java.util.Map.entry("bcpkix-jdk18on", "Bouncy Castle (bcpkix-jdk18on)"),
        java.util.Map.entry("bcutil-jdk18on", "Bouncy Castle (bcutil-jdk18on)"),
        java.util.Map.entry("jediterm-core", "JediTerm (jediterm-core)"), java.util.Map.entry("jediterm-ui", "JediTerm (jediterm-ui)"),
        java.util.Map.entry("pty4j", "pty4j"), java.util.Map.entry("jna", "JNA"), java.util.Map.entry("jna-platform", "JNA Platform"),
        java.util.Map.entry("rsyntaxtextarea", "RSyntaxTextArea"),
        java.util.Map.entry("core", "ZXing core"), java.util.Map.entry("javase", "ZXing javase"),
        java.util.Map.entry("jackson-core", "Jackson Core"), java.util.Map.entry("jackson-databind", "Jackson Databind"),
        java.util.Map.entry("jackson-annotations", "Jackson Annotations"), java.util.Map.entry("jackson-datatype-jsr310", "Jackson Datatype JSR310"),
        java.util.Map.entry("slf4j-api", "SLF4J API"), java.util.Map.entry("slf4j-simple", "SLF4J Simple"), java.util.Map.entry("jcl-over-slf4j", "JCL over SLF4J"),
        java.util.Map.entry("kotlin-stdlib", "Kotlin Stdlib"), java.util.Map.entry("annotations", "JetBrains Annotations"));

    /**
     * Cada biblioteca do build tem, na tabela, UMA linha com o nome certo E a versão certa NA MESMA linha (não basta a versão aparecer em outro lugar: apagar a linha
     * do Bouncy Castle bcutil ou a do ZXing faz este teste falhar). O que o teste NÃO garante: que a licença da linha seja a certa (isso vem da conferência dos POMs, ver
     * as notas técnicas do arquivo).
     */
    @Test
    void cadaBibliotecaDoBuildTemLinhaNaTabelaComONomeEAVersaoNaMesmaLinha() throws IOException {
        String xml = Files.readString(Path.of("gradle", "verification-metadata.xml"));
        Matcher m = Pattern.compile("<component group=\"([^\"]+)\" name=\"([^\"]+)\" version=\"([^\"]+)\"").matcher(xml);
        List<Licenca> linhas = LicencasDeTerceiros.doJar();
        List<String> faltando = new ArrayList<>();
        int conferidas = 0;
        while (m.find()) {
            String nome = m.group(2);
            String versao = m.group(3);
            if (NAO_E_BIBLIOTECA_DO_APP.contains(nome) || (nome.equals("annotations") && versao.startsWith("13."))) {
                continue; // o annotations 13.0 é só uma versão que o Gradle conheceu e trocou pela 24.0.1
            }
            conferidas++;
            String nomeNaTabela = NOME_NA_TABELA.get(nome);
            if (nomeNaTabela == null) {
                faltando.add(m.group(1) + ":" + nome + ":" + versao + " (o teste não sabe o nome dela na tabela: acrescente em NOME_NA_TABELA)");
                continue;
            }
            boolean tem = linhas.stream().anyMatch(l -> l.nome().equals(nomeNaTabela) && l.versao().equals(versao));
            if (!tem) {
                faltando.add(m.group(1) + ":" + nome + ":" + versao + " (linha \"" + nomeNaTabela + "\" com a versão " + versao + ")");
            }
        }
        assertEquals(28, conferidas, "o app leva 28 bibliotecas (mude aqui se o build ganhar ou perder uma)");
        assertTrue(faltando.isEmpty(), "biblioteca do build sem a linha certa na tabela (THIRD-PARTY-NOTICES.md): " + faltando);
    }

    @Test
    void asBibliotecasPrincipaisEstaoComALicencaConferidaNosPoms() {
        List<Licenca> l = LicencasDeTerceiros.doJar();
        assertEquals("Apache-2.0", licencaDe(l, "FlatLaf"));
        assertEquals("Apache-2.0", licencaDe(l, "Apache MINA SSHD (sshd-core)"));
        assertEquals("LGPL-3.0", licencaDe(l, "JediTerm (jediterm-core)"));
        assertEquals("EPL-1.0", licencaDe(l, "pty4j"));
        assertEquals("BSD-3-Clause", licencaDe(l, "RSyntaxTextArea"));
        assertEquals("MIT", licencaDe(l, "SLF4J API"));
        assertEquals("MIT", licencaDe(l, "JSVG"));
        assertTrue(licencaDe(l, "Bouncy Castle (bcprov-jdk18on)").startsWith("Bouncy Castle Licence"));
        assertEquals("MIT", licencaDe(l, "Lista de senhas comuns (derivada do zxcvbn, Dropbox)"));
        assertEquals("GPL-2.0 com a exceção Classpath", licencaDe(l, "OpenJDK (o Java que vai dentro do app)"));
        assertEquals("MIT (Feather) e ISC (Lucide)", licencaDe(l, "Ícones da interface (Feather e Lucide)"));
        assertTrue(l.stream().noneMatch(x -> x.licenca().contains("verificar")), "nenhuma licença não deu para conferir nesta máquina diz \"verificar\": " + l);
    }

    private static String licencaDe(List<Licenca> l, String nome) {
        return l.stream().filter(x -> x.nome().equals(nome)).map(Licenca::licenca).findFirst().orElseThrow(() -> new AssertionError("sem linha para " + nome));
    }

    @Test
    void oInterpretadorLeSoALinhasDaTabelaEIgnoraOResto() {
        String md = "# Título\n\ntexto | com barra\n\n| Biblioteca | Versão | Licença | Uso |\n|---|---|---|---|\n| A | 1.0 | MIT | serve a |\n| B | 2.0 | Apache-2.0 | serve b |\n\nfim\n| solta | 1 | 2 | 3 |\n";
        List<Licenca> l = LicencasDeTerceiros.interpretar(md);
        assertEquals(List.of(new Licenca("A", "1.0", "MIT", "serve a"), new Licenca("B", "2.0", "Apache-2.0", "serve b")), l);
    }

    /** Os textos oficiais que a tabela cita estão na pasta licencas/ do repositório, inteiros (nada de página de site, nada vazio). */
    @Test
    void osTextosDasLicencasCitadasEstaoNaPastaLicencas() throws Exception {
        java.nio.file.Path pasta = java.nio.file.Path.of("licencas");
        java.util.Map<String, String> esperado = java.util.Map.of(
            "LGPL-3.0-JediTerm.txt", "GNU LESSER GENERAL PUBLIC LICENSE",
            "EPL-1.0-pty4j.txt", "Eclipse Public License",
            "MIT-Feather.txt", "The MIT License (MIT)",
            "ISC-Lucide.txt", "ISC License");
        for (var e : esperado.entrySet()) {
            String texto = java.nio.file.Files.readString(pasta.resolve(e.getKey()), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(texto.contains(e.getValue()), e.getKey() + " sem o título da licença");
            assertTrue(texto.length() > 900, e.getKey() + " curto demais");
            assertFalse(texto.toLowerCase(java.util.Locale.ROOT).contains("<script") || texto.contains("<html"), e.getKey() + " parece página de site");
        }
        assertTrue(java.nio.file.Files.readString(pasta.resolve("LGPL-3.0-JediTerm.txt")).length() > 7000, "a LGPL-3.0 inteira");
        assertTrue(java.nio.file.Files.readString(pasta.resolve("EPL-1.0-pty4j.txt")).contains("7. GENERAL"), "a EPL-1.0 inteira, até a seção 7");
    }
}
