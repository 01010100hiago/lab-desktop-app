package br.com.labdesktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Versao.Compilacao;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A versão, o commit e a data lidos do manifesto (simulado e de um jar de teste de verdade): nunca do jar real do build. */
class VersaoTest {

    private static Manifest manifesto(String... pares) {
        Manifest m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        for (int i = 0; i < pares.length; i += 2) {
            m.getMainAttributes().putValue(pares[i], pares[i + 1]);
        }
        return m;
    }

    @Test
    void manifestoCompletoDaVersaoCommitEDataELogoTudoNoTextoCompleto() {
        Compilacao c = Compilacao.de(manifesto("Implementation-Version", "0.1.320", "Build-Commit", "a207ad4", "Build-Date", "2026-10-30"));
        assertEquals("0.1.320", c.versao());
        assertEquals("a207ad4", c.commit());
        assertEquals("2026-10-30", c.data());
        assertEquals("0.1.320 (a207ad4, 2026-10-30)", c.completa());
        assertEquals("0.1.320 (a207ad4)", c.comCommit());
        assertTrue(c.temCommit());
    }

    @Test
    void arvoreAlteradaApareceNaoEscondida() {
        Compilacao c = Compilacao.de(manifesto("Implementation-Version", "0.1.320", "Build-Commit", "a207ad4-alterado", "Build-Date", "2026-10-30"));
        assertEquals("0.1.320 (a207ad4-alterado, 2026-10-30)", c.completa());
        assertEquals("0.1.320 (a207ad4-alterado)", c.comCommit());
    }

    @Test
    void semGitNoBuildOCommitEDesconhecidoEIssoApareceNoTexto() {
        Compilacao c = Compilacao.de(manifesto("Implementation-Version", "0.1.0", "Build-Commit", "desconhecido", "Build-Date", "2026-10-30"));
        assertFalse(c.temCommit());
        assertEquals("desconhecido", c.commitOuDesconhecido());
        assertEquals("0.1.0 (commit desconhecido, 2026-10-30)", c.completa());
        assertEquals("0.1.0 (commit desconhecido)", c.comCommit());
    }

    @Test
    void jarAntigoSoComAVersaoNaoInventaCommitNemData() {
        Compilacao c = Compilacao.de(manifesto("Implementation-Version", "0.1.0"));
        assertEquals("0.1.0", c.versao());
        assertNull(c.commit());
        assertEquals("desconhecido", c.commitOuDesconhecido());
        assertEquals("desconhecida", c.dataOuDesconhecida());
        assertEquals("0.1.0", c.completa());
    }

    @Test
    void semVersaoNoManifestoOuSemManifestoEDev() {
        assertEquals("dev", Compilacao.de(manifesto()).versao());
        assertEquals("dev", Compilacao.de(null).versao());
        assertEquals("dev", Compilacao.de(manifesto("Implementation-Version", "  ")).versao());
        assertEquals("dev", Compilacao.de(manifesto("Build-Commit", "a207ad4", "Build-Date", "2026-10-30")).versao(), "o commit sozinho não faz uma versão");
    }

    @Test
    void valorComCaraDeLixoNaoPassaParaATelaNemParaOUserAgent() {
        Compilacao c = Compilacao.de(manifesto("Implementation-Version", "0.1.320", "Build-Commit", "x; rm -rf /", "Build-Date", "ontem"));
        assertNull(c.commit());
        assertNull(c.data());
        assertEquals("dev", Compilacao.de(manifesto("Implementation-Version", "0.1.320\r\nX-Injetado: 1")).versao());
        assertEquals("dev", Compilacao.de(manifesto("Implementation-Version", "versão nova")).versao());
        assertNull(Compilacao.de(manifesto("Implementation-Version", "0.1.320", "Build-Commit", "A207AD4")).commit(), "o commit é em minúsculas");
        assertNull(Compilacao.de(manifesto("Implementation-Version", "0.1.320", "Build-Commit", "a207ad")).commit(), "sete dígitos");
    }

    @Test
    void userAgentTemSoAVersaoSemCommitNemDadoDoComputador() {
        Compilacao c = Compilacao.de(manifesto("Implementation-Version", "0.1.320", "Build-Commit", "a207ad4-alterado", "Build-Date", "2026-10-30"));
        assertEquals("LabDesktop/0.1.320", c.userAgent());
        assertEquals("LabDesktop/dev", Compilacao.de(null).userAgent());
        assertEquals("LabDesktop/" + Versao.atual(), Versao.userAgent());
        assertFalse(Versao.userAgent().contains("("));
        assertFalse(Versao.userAgent().contains(System.getProperty("user.name")) && System.getProperty("user.name").length() > 3);
    }

    @Test
    void foraDoJarOAppDizDevEIssoValeNosTestes() {
        // as classes dos testes vêm de uma pasta, sem manifesto: é o "versão de teste ou de IDE"
        assertEquals("dev", Versao.atual());
        assertEquals("dev", Versao.completa().split(" ")[0]);
    }

    @Test
    void devComCommitETemOCommitQuandoOBuildDoRunOPassou() {
        // o que o `./gradlew run` faz com as duas propriedades do sistema
        Compilacao c = Compilacao.dev("a207ad4-alterado", "2026-10-30");
        assertEquals("dev", c.versao());
        assertEquals("dev (a207ad4-alterado, 2026-10-30)", c.completa());
        assertEquals("dev (a207ad4-alterado)", c.comCommit());
        assertEquals("dev", Compilacao.dev(null, null).completa());
        assertEquals("dev", Compilacao.dev("lixo", "lixo").completa());
    }

    // ---- o jar de verdade (um jar pequeno montado pelo teste) ----

    private static Path jar(Path pasta, String nome, Manifest manifesto) throws IOException {
        Path arquivo = pasta.resolve(nome);
        try (OutputStream out = Files.newOutputStream(arquivo);
             JarOutputStream jar = manifesto == null ? new JarOutputStream(out) : new JarOutputStream(out, manifesto)) {
            jar.putNextEntry(new JarEntry("br/com/labdesktop/Qualquer.class"));
            jar.write(new byte[] {1, 2, 3});
            jar.closeEntry();
        }
        return arquivo;
    }

    private static java.net.URL classeNoJar(Path jar) throws IOException {
        return URI.create("jar:" + jar.toUri() + "!/br/com/labdesktop/Qualquer.class").toURL();
    }

    @Test
    void leOManifestoDeUmJarDeVerdade(@TempDir Path tmp) throws IOException {
        Path j = jar(tmp, "lab-desktop.jar", manifesto("Implementation-Version", "0.1.321", "Build-Commit", "b1c2d3e", "Build-Date", "2026-11-02"));
        Compilacao c = Compilacao.doJarDe(classeNoJar(j));
        assertEquals("0.1.321 (b1c2d3e, 2026-11-02)", c.completa());
    }

    @Test
    void leOManifestoMesmoComEspacoEAcentoNoCaminhoDoJar(@TempDir Path tmp) throws IOException {
        Path pasta = Files.createDirectories(tmp.resolve("Lab Desktop.app").resolve("Conteúdo"));
        Path j = jar(pasta, "lab-desktop.jar", manifesto("Implementation-Version", "0.1.321", "Build-Commit", "b1c2d3e", "Build-Date", "2026-11-02"));
        assertEquals("0.1.321", Compilacao.doJarDe(classeNoJar(j)).versao());
    }

    @Test
    void jarSemOsDadosOuIlegivelEUrlQueNaoEDeJarSaoDev(@TempDir Path tmp) throws IOException {
        assertEquals("dev", Compilacao.doJarDe(classeNoJar(jar(tmp, "sem-versao.jar", manifesto()))).versao());
        assertEquals("dev", Compilacao.doJarDe(classeNoJar(jar(tmp, "sem-manifesto.jar", null))).versao());
        Path quebrado = tmp.resolve("quebrado.jar");
        Files.write(quebrado, "isto nao e um zip".getBytes(StandardCharsets.UTF_8));
        assertEquals("dev", Compilacao.doJarDe(classeNoJar(quebrado)).versao());
        assertEquals("dev", Compilacao.doJarDe(classeNoJar(tmp.resolve("nao-existe.jar"))).versao());
        assertEquals("dev", Compilacao.doJarDe(tmp.toUri().toURL()).versao(), "uma pasta de classes");
        assertEquals("dev", Compilacao.doJarDe(null).versao());
    }
}
