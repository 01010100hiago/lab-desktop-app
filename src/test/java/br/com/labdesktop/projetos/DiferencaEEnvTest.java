package br.com.labdesktop.projetos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A diferença legível, o {@code .env}, o filtro de envio e a frase dos erros do git. */
class DiferencaEEnvTest {

    @Test
    void diferencaMostraSoOQueMudaComContexto() {
        String antes = "a\nb\nc\nd\ne\nf\ng\nh\n";
        String depois = "a\nb\nc\nd\nX\ne\nf\ng\nh\n";
        assertEquals("  c\n  d\n+ X\n  e\n  f\n", Diferenca.legivel(antes, depois, 2));
        assertEquals("", Diferenca.legivel(antes, antes, 2));
    }

    @Test
    void diferencaDeSubstituicaoEPontinhos() {
        String antes = "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\n12\n";
        String depois = "1\nDOIS\n3\n4\n5\n6\n7\n8\n9\n10\n11\nDOZE\n";
        String d = Diferenca.legivel(antes, depois, 1);
        assertEquals("  1\n- 2\n+ DOIS\n  3\n  ...\n  11\n- 12\n+ DOZE\n", d);
    }

    @Test
    void diferencaDoEsqueletoParaOCompose() {
        String esq = GabaritoDoProjeto.composeVazio("demo");
        String novo = ComposeDoProjeto.acrescentar(esq, "demo", ModelosDeContainer.gerar(ModelosDeContainer.Modelo.REDIS,
            ModelosDeContainer.Parametros.padraoDe(ModelosDeContainer.Modelo.REDIS))).orElseThrow();
        String d = Diferenca.legivel(esq, novo, 1);
        assertTrue(d.contains("- services: {}\n+ services:\n+   redis:\n"), d);
        assertTrue(d.contains("+ volumes:\n+   redis_dados:\n"), d);
    }

    @Test
    void diferencaDeArquivoNovoEATodaComMais() {
        String d = Diferenca.legivel("", "a\nb\n", 2);
        assertEquals("+ a\n+ b\n", d);
    }

    @Test
    void diferencaDeArquivoGrandeDemaisSoConta() {
        String grande = "x\n".repeat(Diferenca.LIMITE_DE_LINHAS + 1);
        assertTrue(Diferenca.legivel(grande, grande + "y\n", 2).startsWith("(arquivo grande demais"));
    }

    @Test
    void envSoAcrescentaEONuncaTrocaOQueJaExiste() {
        Map<String, String> novas = new LinkedHashMap<>();
        novas.put("POSTGRES_PASSWORD", "NOVA");
        novas.put("OUTRA", "X");
        EnvDoProjeto.Mescla m = EnvDoProjeto.acrescentar("A=1\nPOSTGRES_PASSWORD=minha\n", novas);
        assertEquals("A=1\nPOSTGRES_PASSWORD=minha\nOUTRA=X\n", m.texto());
        assertEquals(List.of("OUTRA"), m.acrescentadas());
        assertEquals(List.of("POSTGRES_PASSWORD"), m.jaExistiam());
        assertTrue(m.mudou());
    }

    @Test
    void envArquivoNovoEFimDeLinhaFaltando() {
        assertEquals("K=v\n", EnvDoProjeto.acrescentar(null, Map.of("K", "v")).texto());
        assertEquals("A=1\nK=v\n", EnvDoProjeto.acrescentar("A=1", Map.of("K", "v")).texto());
        assertFalse(EnvDoProjeto.acrescentar("export K = 1\n", Map.of("K", "v")).mudou(), "export e espaços também contam como já existente");
        assertFalse(EnvDoProjeto.acrescentar("  K=1\n", Map.of("K", "v")).mudou());
        assertTrue(EnvDoProjeto.acrescentar("#K=1\n", Map.of("K", "v")).mudou(), "comentário não conta");
        assertTrue(EnvDoProjeto.acrescentar("KK=1\n", Map.of("K", "v")).mudou(), "outra chave com o mesmo começo não conta");
    }

    @Test
    void senhaAleatoriaTem24CaracteresSimples() {
        SecureRandom r = new SecureRandom();
        String a = EnvDoProjeto.novaSenha(r);
        String b = EnvDoProjeto.novaSenha(r);
        assertEquals(24, a.length());
        assertTrue(a.matches("[A-Za-z0-9]{24}"));
        assertNotEquals(a, b);
    }

    @Test
    void filtroDeEnvio() {
        FiltroDeEnvio padrao = FiltroDeEnvio.PADRAO;
        assertFalse(padrao.aceita("node_modules", true));
        assertFalse(padrao.aceita("Node_Modules", true));
        assertFalse(padrao.aceita(".DS_Store", false));
        assertFalse(padrao.aceita(".git", true), ".git não vai por padrão");
        assertFalse(padrao.aceita(".env", false), ".env não vai por padrão");
        assertFalse(padrao.aceita(".env.local", false));
        assertFalse(padrao.aceita(".env.production", false));
        assertTrue(padrao.aceita(".env.example", false));
        assertTrue(padrao.aceita(".env.sample", false));
        assertTrue(padrao.aceita("src", true));
        assertTrue(padrao.aceita("README.md", false));
        assertTrue(padrao.aceita(".gitignore", false));
        assertTrue(padrao.aceita(".environment", true));
        FiltroDeEnvio tudo = new FiltroDeEnvio(true, true);
        assertTrue(tudo.aceita(".git", true));
        assertTrue(tudo.aceita(".env", false));
        assertFalse(tudo.aceita("node_modules", true), "node_modules nunca vai");
        assertTrue(new FiltroDeEnvio(true, false).aceita(".git", true));
        assertFalse(new FiltroDeEnvio(true, false).aceita(".env", false));
    }

    @Test
    void errosDoGitViramFrasesSimples() {
        assertTrue(ExplicadorDeErrosDeGit.explicar("fatal: could not read Username for 'https://github.com': terminal prompts disabled").contains("privado"));
        assertTrue(ExplicadorDeErrosDeGit.explicar("git@github.com: Permission denied (publickey).\nfatal: Could not read from remote repository.").contains("chave de deploy"));
        assertTrue(ExplicadorDeErrosDeGit.explicar("fatal: repository 'https://github.com/x/y/' not found").contains("privado"));
        assertTrue(ExplicadorDeErrosDeGit.explicar("Host key verification failed.").contains("ssh -T"));
        assertTrue(ExplicadorDeErrosDeGit.explicar("fatal: unable to access: Could not resolve host: github.com").contains("sem internet"));
        assertTrue(ExplicadorDeErrosDeGit.explicar("fatal: destination path 'x' already exists and is not an empty directory.").contains("outro nome"));
        assertTrue(ExplicadorDeErrosDeGit.explicar("sh: git: command not found").contains("git não está instalado"));
        assertTrue(ExplicadorDeErrosDeGit.explicar("fatal: could not create work tree dir 'x': Permission denied").contains("Sem permissão"));
        String qualquer = ExplicadorDeErrosDeGit.explicar("<html>erro estranho ghp_abcdefghijklmnopqrstuvwxyz0123456789ABCD\n</html>");
        assertFalse(qualquer.contains("ghp_abcdefghijklmnopqrstuvwxyz"), "nenhum token na frase: " + qualquer);
        assertFalse(qualquer.startsWith("<html"));
        assertTrue(ExplicadorDeErrosDeGit.explicar("").length() > 10);
        assertTrue(ExplicadorDeErrosDeGit.explicar(null).length() > 10);
    }
}
