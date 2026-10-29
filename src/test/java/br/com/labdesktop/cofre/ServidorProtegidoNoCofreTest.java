package br.com.labdesktop.cofre;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * "Servidor protegido" no cofre: padrão falso, cofre de antes (sem os campos) abre normalmente, ida e volta pelo JSON e pelo cofre de verdade
 * (arquivo temporário), e as regras da marca automática (o usuário que desliga a recusa para sempre).
 */
class ServidorProtegidoNoCofreTest {

    private static final char[] SENHA = "uma-senha-mestra-de-teste".toCharArray();
    private static final String TOKEN = "lab_" + "T".repeat(43);

    @TempDir
    Path tmp;

    private static ConfigServidor base() {
        return new ConfigServidor("s1", "lab", "192.0.2.5", 2222, "labuser", "k1", 7000, List.of(), Transporte.DIRETO, null, Estado2fa.DESLIGADO);
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.US_ASCII));
    }

    private static String chaveJson() {
        return "{\"id\":\"k1\",\"nome\":\"lab\",\"algoritmo\":\"ssh-ed25519\","
            + "\"privadaOpenssh\":\"" + b64("-----BEGIN OPENSSH PRIVATE KEY-----\nxx\n-----END OPENSSH PRIVATE KEY-----\n") + "\","
            + "\"publica\":\"ssh-ed25519 AAAAC3Nza lab-desktop@mac\",\"impressao\":\"SHA256:abcdefghijkl\",\"criadaEm\":\"2026-10-20T13:00:00Z\"}";
    }

    private static String jsonDoServidor(String extras) {
        return "{\"versao\":1,\"chaves\":[" + chaveJson() + "],\"servidores\":[{"
            + "\"id\":\"s1\",\"nome\":\"lab\",\"host\":\"h\",\"porta\":22,\"usuario\":\"u\",\"chaveId\":\"k1\",\"portaAgente\":7000,"
            + "\"tokenAgente\":\"" + b64(TOKEN) + "\",\"pins\":[],\"transporte\":{\"tipo\":\"direto\"},\"estado2fa\":\"desligado\"" + extras + "}]}";
    }

    private static ConfigServidor lerConfig(String json) throws Exception {
        return Json.mapper().readValue(json, ConteudoCofre.class).paraMemoria().servidores.get(0).config;
    }

    // ---------------------------------------------------------------------------------------------
    // O modelo
    // ---------------------------------------------------------------------------------------------

    @Test
    void servidorNovoNaoNascePortegidoENemComAMarcaRecusada() {
        ConfigServidor c = ConfigServidor.novo("lab", "192.0.2.5", 2222, "labuser", "k1");
        assertFalse(c.protegido());
        assertFalse(c.protecaoAutomaticaRecusada());
        assertFalse(base().protegido(), "o construtor sem a marca também é desprotegido");
    }

    @Test
    void comProtegidoLigaEDesligaEUmDesligarAnotaARecusaDaMarcaAutomatica() {
        ConfigServidor ligado = base().comProtegido(true);
        assertTrue(ligado.protegido());
        assertFalse(ligado.protecaoAutomaticaRecusada(), "ligar não recusa nada");

        ConfigServidor desligado = ligado.comProtegido(false);
        assertFalse(desligado.protegido());
        assertTrue(desligado.protecaoAutomaticaRecusada(), "o usuário desligou uma proteção ligada: a marca automática não volta sozinha");

        // desligar o que nunca esteve ligado não é recusa: o app ainda pode proteger sozinho
        assertFalse(base().comProtegido(false).protecaoAutomaticaRecusada());
    }

    @Test
    void aMarcaAutomaticaSoLigaUmaVezEHonraARecusa() {
        ConfigServidor c = base();
        assertTrue(c.podeProtegerSozinho());
        ConfigServidor auto = c.comProtecaoAutomatica();
        assertTrue(auto.protegido());
        assertFalse(auto.podeProtegerSozinho(), "já está ligada");
        assertSame(auto, auto.comProtecaoAutomatica(), "ligar de novo não muda nada");

        ConfigServidor recusada = auto.comProtegido(false);
        assertFalse(recusada.podeProtegerSozinho());
        assertSame(recusada, recusada.comProtecaoAutomatica(), "desmarcada pelousuário, a marca automática não religa");
        assertFalse(recusada.comProtecaoAutomatica().protegido());
    }

    @Test
    void esteComputadorNuncaEProtegido() {
        ConfigServidor local = ConfigServidor.novoLocal("Este computador", 7070);
        assertFalse(local.comProtegido(true).protegido());
        assertFalse(local.podeProtegerSozinho());
        assertFalse(local.comProtecaoAutomatica().protegido());
        assertFalse(new ConfigServidor("s2", "Este computador", ConfigServidor.HOST_LOCAL, 0, "", null, 7070, List.of(), Transporte.LOCAL, null,
            Estado2fa.DESLIGADO, true, true).protegido(), "mesmo se alguém gravar a marca num servidor local");
    }

    @Test
    void asOutrasEdicoesNaoPerdemAsMarcas() {
        ConfigServidor c = base().comProtegido(true).comProtegido(false).comProtegido(true); // protegido e recusada
        assertTrue(c.protegido());
        assertTrue(c.protecaoAutomaticaRecusada());
        for (ConfigServidor depois : List.of(
            c.comPin(new Pin("ssh-ed25519", "SHA256:abc")), c.semPins(), c.comNomeDoDispositivo("mac"), c.comEstado2fa(Estado2fa.ATIVO), c.comChaveId("k2"),
            c.editar("outro", "h2", 2222, "outro", 7001), c.editar("lab", "192.0.2.5", 2222, "labuser", 7000))) {
            assertTrue(depois.protegido(), "perdeu a proteção em " + depois);
            assertTrue(depois.protecaoAutomaticaRecusada(), "perdeu a recusa em " + depois);
        }
    }

    @Test
    void mesmaConexaoQueIgnoraSoAsMarcas() {
        ConfigServidor c = base();
        assertTrue(c.mesmaConexaoQue(c.comProtegido(true)));
        assertTrue(c.comProtegido(true).mesmaConexaoQue(c));
        assertFalse(c.mesmaConexaoQue(c.editar("outro", "192.0.2.5", 2222, "labuser", 7000)));
        assertFalse(c.mesmaConexaoQue(c.comNomeDoDispositivo("mac")));
    }

    // ---------------------------------------------------------------------------------------------
    // O JSON do cofre
    // ---------------------------------------------------------------------------------------------

    @Test
    void cofreDeAntesSemOsCamposAbreComoNaoProtegido() throws Exception {
        ConfigServidor c = lerConfig(jsonDoServidor(""));
        assertFalse(c.protegido());
        assertFalse(c.protecaoAutomaticaRecusada());
        assertNull(c.nomeDoDispositivo());
    }

    @Test
    void cofreComOsCamposNulosOuFalsosAbreComoNaoProtegido() throws Exception {
        assertFalse(lerConfig(jsonDoServidor(",\"protegido\":null")).protegido());
        assertFalse(lerConfig(jsonDoServidor(",\"protegido\":false,\"protecaoAutomaticaRecusada\":false")).protegido());
    }

    @Test
    void leAsMarcasGravadas() throws Exception {
        ConfigServidor c = lerConfig(jsonDoServidor(",\"protegido\":true,\"protecaoAutomaticaRecusada\":true"));
        assertTrue(c.protegido());
        assertTrue(c.protecaoAutomaticaRecusada());
    }

    @Test
    void idaEVoltaPeloJsonMantemAsMarcasEGravaAVersao3() throws Exception {
        String json = jsonDoServidor(",\"protegido\":true").replace("\"versao\":1", "\"versao\":3");
        ConteudoEmMemoria m = Json.mapper().readValue(json, ConteudoCofre.class).paraMemoria();
        var original = Json.mapper().readTree(json);
        var nova = Json.mapper().readTree(Json.mapper().writeValueAsBytes(ConteudoCofre.de(m)));
        assertEquals(original, nova);
        assertTrue(nova.get("servidores").get(0).get("protegido").asBoolean());
        assertEquals(3, nova.get("versao").asInt());
    }

    @Test
    void qualquerMarcaGravaAVersao3EOCofreSemMarcaContinuaComoHoje() throws Exception {
        for (String extras : new String[] {",\"protegido\":true", ",\"protecaoAutomaticaRecusada\":true", ",\"protegido\":true,\"protecaoAutomaticaRecusada\":true"}) {
            ConteudoEmMemoria m = Json.mapper().readValue(jsonDoServidor(extras), ConteudoCofre.class).paraMemoria(); // um arquivo de versão 1 com a marca também abre
            assertEquals(3, ConteudoCofre.de(m).versao, extras);
        }
        ConteudoEmMemoria semMarca = Json.mapper().readValue(jsonDoServidor(""), ConteudoCofre.class).paraMemoria();
        assertEquals(1, ConteudoCofre.de(semMarca).versao, "sem marca nenhuma o arquivo é o de hoje, versão 1");
    }

    @Test
    void aVersao3NaoEUmaDasQueOAppDeAntesAceitaEAVersaoDeAntesContinuaLendo() throws Exception {
        // um app sem a marca de proteção aceita só estas; a 3 o faz recusar com "versão não suportada" em vez de regravar e apagar a marca em silêncio
        assertFalse(java.util.Set.of(ConteudoCofre.VERSAO, ConteudoCofre.VERSAO_COM_SERVIDOR_LOCAL).contains(ConteudoCofre.VERSAO_COM_PROTECAO));
        assertEquals(3, ConteudoCofre.VERSAO_COM_PROTECAO);
        // e o app novo lê as três (e recusa o que não conhece)
        for (int v : new int[] {1, 2, 3, 4}) {
            assertEquals("lab", lerConfig(jsonDoServidor("").replace("\"versao\":1", "\"versao\":" + v)).nome(), "versão " + v);
        }
        assertThrows(CofreCorrompidoException.class, () -> lerConfig(jsonDoServidor("").replace("\"versao\":1", "\"versao\":5")));
    }

    @Test
    void servidorSemProtecaoGravaOMesmoJsonDeHoje() throws Exception {
        // sem as marcas o arquivo é idêntico ao de antes: nenhum campo novo (um app de antes lê sem estranhar, e a idempotência de hoje continua valendo)
        String json = jsonDoServidor("");
        ConteudoEmMemoria m = Json.mapper().readValue(json, ConteudoCofre.class).paraMemoria();
        var nova = Json.mapper().readTree(Json.mapper().writeValueAsBytes(ConteudoCofre.de(m)));
        assertFalse(nova.get("servidores").get(0).has("protegido"));
        assertFalse(nova.get("servidores").get(0).has("protecaoAutomaticaRecusada"));
        assertEquals(Json.mapper().readTree(json), nova);
    }

    @Test
    void servidorComCampoDesconhecidoNoJsonContinuaLegivel() throws Exception {
        // prova só o que diz: o leitor ignora campo que não conhece (ignoreUnknown) e traz o resto do servidor. Que o app de ANTES não regrave e apague a marca é
        // a versão 3 (teste acima), não isto.
        ConfigServidor c = lerConfig(jsonDoServidor(",\"protegido\":true,\"campoQueUmDiaExistira\":1"));
        assertEquals("lab", c.nome());
        assertEquals(22, c.porta());
        assertEquals("u", c.usuario());
    }

    // ---------------------------------------------------------------------------------------------
    // O cofre de verdade, em arquivo temporário
    // ---------------------------------------------------------------------------------------------

    @Test
    void ligarEDesligarPersisteEntreAberturasDoCofre() throws Exception {
        CaminhosApp caminhos = CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
        String id;
        try (CofreDeChaves cofre = CofreDeChaves.criar(caminhos, SENHA.clone(), ParametrosKdf.MINIMO)) {
            ChaveSsh chave = GeradorDeChaves.gerar("k", "mac");
            cofre.adicionarChave(chave);
            ConfigServidor cfg = ConfigServidor.novo("lab", "192.0.2.5", 2222, "labuser", chave.id());
            id = cfg.id();
            cofre.adicionarServidor(cfg, null);
            assertFalse(cofre.servidor(id).orElseThrow().protegido());
            cofre.alterarServidor(id, atual -> atual.comProtegido(true));
        }
        try (CofreDeChaves cofre = CofreDeChaves.abrir(caminhos, SENHA.clone(), ParametrosKdf.MINIMO)) {
            ConfigServidor lido = cofre.servidor(id).orElseThrow();
            assertTrue(lido.protegido(), "a marca sobrevive ao fechar e abrir o cofre");
            assertFalse(lido.protecaoAutomaticaRecusada());
            cofre.alterarServidor(id, atual -> atual.comProtegido(false));
        }
        try (CofreDeChaves cofre = CofreDeChaves.abrir(caminhos, SENHA.clone(), ParametrosKdf.MINIMO)) {
            ConfigServidor lido = cofre.servidor(id).orElseThrow();
            assertFalse(lido.protegido());
            assertTrue(lido.protecaoAutomaticaRecusada(), "o usuário desligou: a recusa também sobrevive");
        }
    }
}
