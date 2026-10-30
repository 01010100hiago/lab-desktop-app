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
        assertTrue(l.stream().noneMatch(x -> x.licenca().equals(LicencasDeTerceiros.A_CONFIRMAR)), "nenhuma licença ficou \"a confirmar\": " + l);
    }

    /** O que a tabela MOSTRA é para o usuário: nada de nota de desenvolvedor, crase de markdown nem reticências. */
    @Test
    void aTabelaMostradaNaoTemNotaDeDesenvolvedorNemMarcaDeMarkdown() {
        for (Licenca x : LicencasDeTerceiros.doJar()) {
            for (String campo : new String[] {x.nome(), x.versao(), x.licenca(), x.uso()}) {
                String minusculo = campo.toLowerCase(java.util.Locale.ROOT);
                assertFalse(minusculo.contains("verificar") || minusculo.contains("nesta máquina") || minusculo.contains("jpackage") || campo.contains("`") || campo.contains("…"),
                    "texto de desenvolvedor na tabela mostrada ao usuário: " + x);
            }
        }
        assertTrue(LicencasDeTerceiros.avisosDeTerceiros().contains("## Notas técnicas"), "a nota técnica fica no arquivo, fora da tabela");
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

    // ---------------------------------------------------------------------------------------------
    // Conferência mecânica de créditos: jars de execução, avisos NOTICE, código embutido e programas nativos
    // ---------------------------------------------------------------------------------------------

    /** Os arquivos da pasta licencas/ (menos o LEIAME) são exatamente os que o app leva no jar e mostra na tela Sobre, e todos estão descritos no LEIAME. */
    @Test
    void cadaArquivoDaPastaLicencasVaiNoJarEEstaDescritoNoLeiame() throws IOException {
        List<String> naPasta;
        try (var f = Files.list(Path.of("licencas"))) {
            naPasta = f.map(x -> x.getFileName().toString()).filter(n -> !n.equals("LEIAME.md")).sorted().toList();
        }
        assertEquals(naPasta, LicencasDeTerceiros.ARQUIVOS_DE_LICENCA.stream().sorted().toList(), "a lista do app tem de ser a da pasta licencas/");
        String leiame = Files.readString(Path.of("licencas", "LEIAME.md"));
        for (String arquivo : naPasta) {
            assertTrue(leiame.contains("`" + arquivo + "`"), arquivo + " não está descrito em licencas/LEIAME.md");
            assertEquals(Files.readString(Path.of("licencas", arquivo)).strip(), LicencasDeTerceiros.textoDeLicenca(arquivo).strip(), arquivo + ": o jar leva o texto da pasta");
        }
        assertTrue(LicencasDeTerceiros.textosCompletos().contains("GNU GENERAL PUBLIC LICENSE") && LicencasDeTerceiros.textosCompletos().contains("Eclipse Public License"));
    }

    /** Cada família de licença que a tabela cita tem o texto na pasta licencas/ (ou, para o Java embutido, na pasta legal/ do próprio runtime). */
    @Test
    void cadaLicencaDaTabelaTemOTextoNaPasta() throws IOException {
        java.util.Map<String, String> texto = java.util.Map.ofEntries(
            java.util.Map.entry("Apache-2.0", "Apache-2.0.txt"), java.util.Map.entry("MIT", "MIT-SLF4J.txt"), java.util.Map.entry("BSD-3-Clause", "BSD-3-Clause-RSyntaxTextArea.txt"),
            java.util.Map.entry("EPL-1.0", "EPL-1.0-pty4j.txt"), java.util.Map.entry("LGPL-3.0", "LGPL-3.0-JediTerm.txt"), java.util.Map.entry("SIL OFL 1.1", "OFL-1.1-Inter.txt"),
            java.util.Map.entry("Bouncy Castle Licence", "Bouncy-Castle-Licence.md"), java.util.Map.entry("ISC", "ISC-Lucide.txt"), java.util.Map.entry("Boost Software License", "Jackson-core-embutidos.txt"));
        for (Licenca x : LicencasDeTerceiros.doJar()) {
            if (x.licenca().contains("Classpath")) {
                continue; // o OpenJDK leva a pasta legal/ com os textos dentro do runtime
            }
            boolean coberta = texto.entrySet().stream().anyMatch(e -> x.licenca().contains(e.getKey()) && Files.exists(Path.of("licencas", e.getValue())));
            assertTrue(coberta, "sem texto em licencas/ para a licença \"" + x.licenca() + "\" de " + x.nome());
        }
    }

    /** Os componentes que só servem para construir e testar estão listados como tais (sem texto completo: não vão no app). */
    @Test
    void asFerramentasSoDeDesenvolvimentoEstaoListadas() throws IOException {
        String avisos = Files.readString(Path.of("THIRD-PARTY-NOTICES.md"));
        for (String ferramenta : new String[] {"Gradle", "JUnit", "opentest4j", "apiguardian-api", "WiX Toolset"}) {
            assertTrue(avisos.contains("| " + ferramenta), "ferramenta de desenvolvimento sem linha: " + ferramenta);
        }
    }

    /** Os jars de execução que estão no classpath do teste (os 28 do app). */
    private static List<Path> jarsDeExecucao() {
        List<Path> jars = new ArrayList<>();
        for (String entrada : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            Path p = Path.of(entrada);
            String nome = p.getFileName().toString();
            if (nome.endsWith(".jar") && NOME_NA_TABELA.keySet().stream().anyMatch(a -> nome.startsWith(a + "-") && nome.substring(a.length() + 1).matches("\\d.*"))) {
                jars.add(p);
            }
        }
        return jars;
    }

    /** O texto de cada META-INF/NOTICE dos jars de execução está em licencas/NOTICE-Apache.txt (a Apache-2.0 pede a reprodução). */
    @Test
    void osAvisosNoticeDosJarsDeExecucaoEstaoReproduzidos() throws IOException {
        String reproduzido = Files.readString(Path.of("licencas", "NOTICE-Apache.txt"));
        int conferidos = 0;
        for (Path jar : jarsDeExecucao()) {
            try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(jar.toFile())) {
                var notice = z.getEntry("META-INF/NOTICE");
                if (notice != null) {
                    String texto = new String(z.getInputStream(notice).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
                    assertTrue(reproduzido.contains(texto), "o NOTICE de " + jar.getFileName() + " não está em licencas/NOTICE-Apache.txt");
                    conferidos++;
                }
            }
        }
        assertTrue(conferidos >= 7, "esperava ao menos 7 NOTICE (sshd x3, jackson x4); achei " + conferidos);
    }

    /** Qualquer arquivo de licença que um jar de execução traga em META-INF além dos já tratados faz o teste falhar: é um crédito novo a registrar. */
    @Test
    void jarDeExecucaoComArquivoDeLicencaNovoPedeCredito() throws IOException {
        Set<String> tratados = Set.of("META-INF/LICENSE", "META-INF/LICENSE.md", "META-INF/LICENSE.txt", "META-INF/NOTICE", "META-INF/AL2.0", "META-INF/LGPL2.1",
            "META-INF/FastDoubleParser-LICENSE", "META-INF/FastDoubleParser-ThirdParty-LICENSE", "META-INF/Schubfach-LICENSE", "com/formdev/flatlaf/fonts/inter/LICENSE.txt");
        List<String> novos = new ArrayList<>();
        for (Path jar : jarsDeExecucao()) {
            try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(jar.toFile())) {
                z.stream().map(java.util.zip.ZipEntry::getName).filter(n -> !n.endsWith("/") && !n.endsWith(".class"))
                    .filter(n -> n.toLowerCase(java.util.Locale.ROOT).matches(".*(licen[cs]e|notice|copying|third.?party).*")).filter(n -> !tratados.contains(n))
                    .forEach(n -> novos.add(jar.getFileName() + ":" + n));
            }
        }
        assertEquals(List.of(), novos, "arquivo de licença novo dentro de um jar: registre o crédito em THIRD-PARTY-NOTICES.md e licencas/");
    }

    /** O Jackson Core leva código de terceiros com licença própria: cada arquivo de licença dele está reproduzido, e os três projetos têm linha na tabela. */
    @Test
    void oCodigoDeTerceirosDentroDoJacksonCoreTemCredito() throws IOException {
        String reproduzido = Files.readString(Path.of("licencas", "Jackson-core-embutidos.txt"));
        Path jar = jarsDeExecucao().stream().filter(j -> j.getFileName().toString().startsWith("jackson-core-")).findFirst().orElseThrow();
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(jar.toFile())) {
            for (String nome : new String[] {"META-INF/FastDoubleParser-LICENSE", "META-INF/FastDoubleParser-ThirdParty-LICENSE", "META-INF/Schubfach-LICENSE"}) {
                String texto = new String(z.getInputStream(z.getEntry(nome)).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
                assertTrue(reproduzido.contains(texto), nome + " não está em licencas/Jackson-core-embutidos.txt");
            }
        }
        String avisos = Files.readString(Path.of("THIRD-PARTY-NOTICES.md"));
        for (String nome : new String[] {"FastDoubleParser", "fast_float", "Schubfach"}) {
            assertTrue(avisos.contains("| " + nome), "sem linha na tabela: " + nome);
        }
    }

    /** O pty4j leva programas e bibliotecas nativas: cada família está creditada; um arquivo nativo novo faz o teste falhar. */
    @Test
    void osProgramasNativosDentroDoPty4jTemCredito() throws IOException {
        Path jar = jarsDeExecucao().stream().filter(j -> j.getFileName().toString().startsWith("pty4j-")).findFirst().orElseThrow();
        Set<String> conhecidos = Set.of("winpty-agent.exe", "winpty.dll", "conpty.dll", "OpenConsole.exe", "win-helper.dll", "cyglaunch.exe", "libpty.so", "libpty.dylib", "pty4j-unix-spawn-helper");
        List<String> desconhecidos = new ArrayList<>();
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(jar.toFile())) {
            z.stream().map(java.util.zip.ZipEntry::getName).filter(n -> n.startsWith("resources/com/pty4j/native/") && !n.endsWith("/"))
                .map(n -> n.substring(n.lastIndexOf('/') + 1)).filter(n -> !conhecidos.contains(n)).forEach(desconhecidos::add);
        }
        assertEquals(List.of(), desconhecidos, "programa nativo novo dentro do pty4j: confira a origem e registre o crédito");
        String avisos = Files.readString(Path.of("THIRD-PARTY-NOTICES.md"));
        assertTrue(avisos.contains("| winpty, de Ryan Prichard") && avisos.contains("| OpenConsole e conpty, do Windows Terminal") && avisos.contains("| elt e JPty (o código"), "créditos dos componentes do pty4j");
        assertTrue(Files.readString(Path.of("licencas", "NOTICE-pty4j.txt")).contains("JPty"));
    }

    /** A origem da lista de senhas, os ícones e o Java embutido têm linha de crédito (nenhum "a confirmar"). */
    @Test
    void dadosEmbutidosTemCredito() throws IOException {
        List<Licenca> l = LicencasDeTerceiros.doJar();
        for (String nome : new String[] {"Lista de senhas comuns (derivada do zxcvbn, Dropbox)", "OpenJDK (o Java que vai dentro do app)", "Ícones da interface (Feather e Lucide)", "FlatLaf Fonts Inter", "FlatLaf Fonts JetBrains Mono"}) {
            assertTrue(l.stream().anyMatch(x -> x.nome().equals(nome)), "sem linha de crédito: " + nome);
        }
        assertTrue(Files.readString(Path.of("licencas", "MIT-zxcvbn-Dropbox.txt")).contains("Dropbox"));
        assertTrue(Files.readString(Path.of("scripts", "GerarIcones.java")).contains("Gera os ícones do Lab Desktop"), "o ícone do app é gerado por um script do repositório (obra do autor)");
    }
}
