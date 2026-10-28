package br.com.labdesktop.cofre;

import static br.com.labdesktop.cofre.Fixtures.abrir;
import static br.com.labdesktop.cofre.Fixtures.caminhos;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.seguranca.SegredoZeradoException;
import br.com.labdesktop.util.CaminhosApp;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** O token da Cloudflare e a escolha do usuário no cofre: cifrado, por servidor, zerado, e o cofre antigo abre igual. */
class CofreCloudflareTest {

    static final String CONTA = "a".repeat(32);
    static final String ZONA = "b".repeat(32);
    static final String TUNEL = "11111111-2222-3333-4444-555555555555";
    /** Um token de mentira com a cara de um de verdade (40 caracteres). */
    static final String TOKEN = "SeGrEdOfAlSoDeTeStE0123456789_-abcdEFGH";

    static ConfiguracaoCloudflare porArquivo() {
        return new ConfiguracaoCloudflare(CONTA, "Conta de Teste", ZONA, "example.com", TUNEL, "lab", ConfiguracaoCloudflare.Tipo.ARQUIVO, "lab",
            "/srv/lab/infra/cloudflared/config.yml", "cloudflared", null);
    }

    static ConfiguracaoCloudflare pelaApi() {
        return new ConfiguracaoCloudflare(CONTA, "Conta", ZONA, "example.com", TUNEL, "lab", ConfiguracaoCloudflare.Tipo.API, "lab", null, null, null);
    }

    static TokenCloudflare token() {
        return TokenCloudflare.deChars(TOKEN.toCharArray());
    }

    @Test
    void tokenEEscolhaVoltamIguaisDoArquivoEOTokenNaoApareceEmClaroNoArquivo(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        ConfigServidor cfg = Fixtures.popular(c);
        try (CofreDeChaves cofre = abrir(c)) {
            assertTrue(cofre.tokenCloudflare(cfg.id()).isEmpty(), "ninguém conectou ainda");
            assertTrue(cofre.configuracaoCloudflare(cfg.id()).isEmpty());
            cofre.gravarCloudflare(cfg.id(), token(), porArquivo());
        }
        byte[] arquivo = Files.readAllBytes(c.arquivoCofre());
        assertEquals(-1, indice(arquivo, TOKEN.getBytes(StandardCharsets.US_ASCII)), "o cofre é cifrado: o token nunca fica em claro no disco");
        try (CofreDeChaves cofre = abrir(c)) {
            TokenCloudflare lido = cofre.tokenCloudflare(cfg.id()).orElseThrow();
            assertEquals("Bearer " + TOKEN, lido.cabecalhoAuthorization());
            assertEquals(porArquivo(), cofre.configuracaoCloudflare(cfg.id()).orElseThrow());
            assertEquals(cfg, cofre.servidor(cfg.id()).orElseThrow(), "a configuração do servidor não mudou");
        }
    }

    @Test
    void oTokenEZeradoAoBloquearAoTrocarEAoDesconectar(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        ConfigServidor cfg = Fixtures.popular(c);
        CofreDeChaves cofre = abrir(c);
        cofre.gravarCloudflare(cfg.id(), token(), pelaApi());
        TokenCloudflare primeiro = cofre.tokenCloudflare(cfg.id()).orElseThrow();
        cofre.gravarCloudflare(cfg.id(), TokenCloudflare.deChars("OutroTokenDeTeste0123456789abcdefghijkl".toCharArray()), pelaApi());
        assertTrue(primeiro.estaZerado(), "o token trocado é zerado na memória");
        TokenCloudflare segundo = cofre.tokenCloudflare(cfg.id()).orElseThrow();
        assertFalse(segundo.estaZerado());
        cofre.removerCloudflare(cfg.id());
        assertTrue(segundo.estaZerado(), "desconectar zera o token");
        assertTrue(cofre.tokenCloudflare(cfg.id()).isEmpty());
        assertTrue(cofre.configuracaoCloudflare(cfg.id()).isEmpty());
        cofre.gravarCloudflare(cfg.id(), token(), pelaApi());
        TokenCloudflare terceiro = cofre.tokenCloudflare(cfg.id()).orElseThrow();
        cofre.bloquear();
        assertTrue(terceiro.estaZerado(), "bloquear o cofre zera o token");
        assertThrows(SegredoZeradoException.class, terceiro::cabecalhoAuthorization);
        cofre.close();
        try (CofreDeChaves de_novo = abrir(c)) {
            assertTrue(de_novo.tokenCloudflare(cfg.id()).isPresent(), "o que foi gravado continua para a próxima abertura");
        }
    }

    @Test
    void removerOServidorZeraOTokenDaCloudflareEGirarOTokenDoAgenteNaoPerdeACloudflare(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        ConfigServidor cfg = Fixtures.popular(c);
        try (CofreDeChaves cofre = abrir(c)) {
            cofre.gravarCloudflare(cfg.id(), token(), porArquivo());
            TokenCloudflare t = cofre.tokenCloudflare(cfg.id()).orElseThrow();
            cofre.gravarToken(cfg.id(), TokenAgente.gerar(), "outro-mac");
            assertFalse(t.estaZerado(), "trocar o token do agente não mexe na Cloudflare");
            assertEquals(porArquivo(), cofre.configuracaoCloudflare(cfg.id()).orElseThrow());
            cofre.gravarAgenteInstalado(cfg.id(), new AgenteInstalado("painel-teste", true));
            assertTrue(cofre.tokenCloudflare(cfg.id()).isPresent(), "nem registrar o agente instalado");
            cofre.removerServidor(cfg.id(), false);
            assertTrue(t.estaZerado(), "sem o servidor, o token da Cloudflare dele não fica na memória");
        }
    }

    @Test
    void trocarSoAEscolhaMantemOTokenEExigeQueEleExista(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        ConfigServidor cfg = Fixtures.popular(c);
        try (CofreDeChaves cofre = abrir(c)) {
            assertThrows(IllegalArgumentException.class, () -> cofre.gravarConfiguracaoCloudflare(cfg.id(), porArquivo()), "sem token não há o que ajustar");
            cofre.gravarCloudflare(cfg.id(), token(), porArquivo());
            TokenCloudflare t = cofre.tokenCloudflare(cfg.id()).orElseThrow();
            cofre.gravarConfiguracaoCloudflare(cfg.id(), new ConfiguracaoCloudflare(CONTA, "Conta de Teste", ZONA, "example.com", TUNEL, "lab", ConfiguracaoCloudflare.Tipo.ARQUIVO, "lab",
                "/srv/lab/infra/cloudflared/config.yml", "cloudflared", "/etc/cloudflared/config.yml"));
            assertSame(t, cofre.tokenCloudflare(cfg.id()).orElseThrow());
            assertEquals("/etc/cloudflared/config.yml", cofre.configuracaoCloudflare(cfg.id()).orElseThrow().caminhoNoContainer());
        }
    }

    private static void assertSame(Object a, Object b) {
        org.junit.jupiter.api.Assertions.assertSame(a, b);
    }

    @Test
    void cofreDeAntesDaCloudflareAbreIgual(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        ConfigServidor cfg = Fixtures.popular(c);
        try (CofreDeChaves cofre = abrir(c)) {
            assertEquals(cfg, cofre.servidor(cfg.id()).orElseThrow());
            assertTrue(cofre.tokenCloudflare(cfg.id()).isEmpty());
            ConteudoCofre json = ConteudoCofre.de(new ConteudoEmMemoria(java.util.List.of(), java.util.List.of()));
            assertEquals(1, json.versao, "a versão do arquivo continua 1: os campos novos são opcionais");
        }
    }

    @Test
    void oJsonDeUmServidorSemCloudflareNaoTemOsCamposNovos() throws Exception {
        ConfigServidor local = ConfigServidor.novoLocal("Este computador", 7070);
        ConteudoEmMemoria m = ConteudoEmMemoria.vazio().comServidor(new ConteudoEmMemoria.ServidorGuardado(local, null));
        String json = br.com.labdesktop.util.Json.mapper().writeValueAsString(ConteudoCofre.de(m));
        assertFalse(json.contains("cloudflare"), json);
    }

    @Test
    void escolhaInvalidaECofreCorrompidoNaoPassa() {
        assertThrows(IllegalArgumentException.class, () -> new ConfiguracaoCloudflare("curto", "x", ZONA, "example.com", TUNEL, "lab", ConfiguracaoCloudflare.Tipo.API, "lab",
            null, null, null), "id de conta fora do formato");
        assertThrows(IllegalArgumentException.class, () -> new ConfiguracaoCloudflare(CONTA, "x", ZONA, "example.com", "lab", "lab", ConfiguracaoCloudflare.Tipo.API, "lab",
            null, null, null), "o túnel é sempre o UUID, nunca o nome");
        assertThrows(IllegalArgumentException.class, () -> new ConfiguracaoCloudflare(CONTA, "x", ZONA, "zona", TUNEL, "lab", ConfiguracaoCloudflare.Tipo.API, "lab",
            null, null, null), "domínio com um rótulo só");
        assertThrows(IllegalArgumentException.class, () -> new ConfiguracaoCloudflare(CONTA, "x", ZONA, "example.com", TUNEL, "lab", ConfiguracaoCloudflare.Tipo.ARQUIVO, "lab",
            "/srv/lab/infra/config.yml; rm -rf /", "cloudflared", null), "caminho com símbolo de shell");
        assertThrows(IllegalArgumentException.class, () -> new ConfiguracaoCloudflare(CONTA, "x", ZONA, "example.com", TUNEL, "lab", ConfiguracaoCloudflare.Tipo.ARQUIVO, "lab",
            "/srv/lab/../etc/passwd", "cloudflared", null), "caminho com ..");
        assertThrows(IllegalArgumentException.class, () -> new ConfiguracaoCloudflare(CONTA, "x", ZONA, "example.com", TUNEL, "lab", ConfiguracaoCloudflare.Tipo.ARQUIVO, "lab",
            "/srv/lab/infra/config.yml", "-v /:/host", null), "nome de container que vira opção");
        assertThrows(IllegalArgumentException.class, () -> new ConfiguracaoCloudflare(CONTA, "x", ZONA, "example.com", TUNEL, "lab", ConfiguracaoCloudflare.Tipo.API, "Lab Ruim",
            null, null, null), "prefixo com espaço e maiúscula");
        // o tipo API ignora o que é de arquivo
        ConfiguracaoCloudflare api = new ConfiguracaoCloudflare(CONTA, "x", ZONA, "example.com", TUNEL, "lab", ConfiguracaoCloudflare.Tipo.API, null, "/qualquer", "c", "/d");
        assertEquals(null, api.caminhoDoConfig());
        assertEquals("lab", api.prefixo(), "sem prefixo vale o padrão");
    }

    @Test
    void tokenEntradaAceitaPontasEmBrancoRecusaEspacoNoMeioNaoZeraOQueRecebeESemRepetirOTextoNoErro() {
        char[] colado = ("  \n" + TOKEN + "\r\n").toCharArray();
        char[] copiaDoColado = colado.clone();
        TokenCloudflare t = TokenCloudflare.deChars(colado);
        assertEquals("Bearer " + TOKEN, t.cabecalhoAuthorization());
        assertTrue(Arrays.equals(copiaDoColado, colado), "quem digitou é quem zera: o deChars só copia");
        assertEquals(TOKEN.length(), t.tamanho());

        String comEspaco = TOKEN.substring(0, 10) + " " + TOKEN.substring(10);
        RuntimeException e = assertThrows(TokenCloudflare.FormatoInvalidoException.class, () -> TokenCloudflare.deChars(comEspaco.toCharArray()));
        assertFalse(e.getMessage().contains(TOKEN.substring(0, 10)), "o erro não repete o que foi colado");
        assertThrows(TokenCloudflare.FormatoInvalidoException.class, () -> TokenCloudflare.deChars("curto".toCharArray()));
        assertThrows(TokenCloudflare.FormatoInvalidoException.class, () -> TokenCloudflare.deChars("   ".toCharArray()));
        assertThrows(TokenCloudflare.FormatoInvalidoException.class, () -> TokenCloudflare.deChars((TOKEN + "é").toCharArray()));
        assertThrows(TokenCloudflare.FormatoInvalidoException.class, () -> TokenCloudflare.deChars(null));
    }

    @Test
    void oTokenNuncaAparecePorToStringNemPelaCopiaEAZeragemValeNasDuas() {
        TokenCloudflare t = token();
        assertFalse(t.toString().contains(TOKEN));
        assertEquals("TokenCloudflare[redigido]", t.toString());
        TokenCloudflare copia = t.copia();
        assertNotSame(t, copia);
        t.zerar();
        assertTrue(t.estaZerado());
        assertFalse(copia.estaZerado(), "a cópia é independente");
        copia.zerar();
        assertTrue(copia.estaZerado());
        assertThrows(SegredoZeradoException.class, t::copia);
        assertThrows(SegredoZeradoException.class, t::valorParaFiltro);
    }

    private static int indice(byte[] palheiro, byte[] agulha) {
        outer:
        for (int i = 0; i + agulha.length <= palheiro.length; i++) {
            for (int j = 0; j < agulha.length; j++) {
                if (palheiro[i + j] != agulha[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
