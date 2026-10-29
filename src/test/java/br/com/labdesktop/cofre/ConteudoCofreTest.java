package br.com.labdesktop.cofre;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.cofre.ConteudoEmMemoria.ServidorGuardado;
import br.com.labdesktop.seguranca.Zeragem;
import br.com.labdesktop.util.Json;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** O JSON de dentro do cofre (ARQUITETURA 5.3). */
class ConteudoCofreTest {

    private static final String TOKEN = "lab_" + "T".repeat(43);

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.US_ASCII));
    }

    private static String chaveJson(String id) {
        return "{\"id\":\"" + id + "\",\"nome\":\"lab\",\"algoritmo\":\"ssh-ed25519\","
            + "\"privadaOpenssh\":\"" + b64("-----BEGIN OPENSSH PRIVATE KEY-----\nxx\n-----END OPENSSH PRIVATE KEY-----\n") + "\","
            + "\"publica\":\"ssh-ed25519 AAAAC3Nza lab-desktop@mac\",\"impressao\":\"SHA256:abcdefghijkl\","
            + "\"criadaEm\":\"2026-10-20T13:00:00Z\"}";
    }

    private static ConteudoCofre ler(String json) throws Exception {
        return Json.mapper().readValue(json, ConteudoCofre.class);
    }

    @Test
    void leOExemploExatoDoDocumento() throws Exception {
        // O JSON de ARQUITETURA 5.3, sem os campos acrescentados depois (nomeDoDispositivo, estado2fa).
        String json = "{\"versao\":1,\"chaves\":[" + chaveJson("k1") + "],\"servidores\":[{"
            + "\"id\":\"s1\",\"nome\":\"lab\",\"host\":\"192.0.2.5\",\"porta\":2222,\"usuario\":\"labuser\","
            + "\"chaveId\":\"k1\",\"portaAgente\":7000,\"tokenAgente\":\"" + b64(TOKEN) + "\","
            + "\"pins\":[{\"algoritmo\":\"ssh-ed25519\",\"impressao\":\"SHA256:abcdefghijkl\"}],"
            + "\"transporte\":{\"tipo\":\"direto\"}}]}";
        ConteudoEmMemoria m = ler(json).paraMemoria();
        assertEquals(1, m.chaves.size());
        assertEquals("k1", m.chaves.get(0).id());
        ServidorGuardado s = m.servidores.get(0);
        assertEquals("lab", s.config.nome());
        assertEquals(2222, s.config.porta());
        assertEquals(Estado2fa.DESLIGADO, s.config.estado2fa());
        assertNull(s.config.nomeDoDispositivo());
        assertEquals(Transporte.DIRETO, s.config.transporte());
        assertEquals("Bearer " + TOKEN, s.token.cabecalhoAuthorization());
    }

    @Test
    void transporteCloudflaredEPortaAgenteAusenteTemPadrao() throws Exception {
        String json = "{\"versao\":1,\"chaves\":[" + chaveJson("k1") + "],\"servidores\":[{"
            + "\"id\":\"s1\",\"nome\":\"lab\",\"host\":\"h\",\"porta\":22,\"usuario\":\"u\",\"chaveId\":\"k1\","
            + "\"transporte\":{\"tipo\":\"cloudflared\",\"hostname\":\"ssh.example.com\"},\"campoDoFuturo\":123}],"
            + "\"outroCampoDoFuturo\":true}";
        ServidorGuardado s = ler(json).paraMemoria().servidores.get(0);
        assertEquals(7000, s.config.portaAgente());
        assertEquals(Transporte.cloudflared("ssh.example.com"), s.config.transporte());
        assertNull(s.token);
    }

    @Test
    void ondeFalhaMaisCedoNaoVazaSegredo() throws Exception {
        // Servidor que aponta para chave inexistente: Corrompido, e o token que já tinha sido adotado é zerado.
        ConteudoCofre c = ler("{\"versao\":1,\"chaves\":[" + chaveJson("k1") + "],\"servidores\":[{"
            + "\"id\":\"s1\",\"nome\":\"lab\",\"host\":\"h\",\"porta\":22,\"usuario\":\"u\",\"chaveId\":\"k9\","
            + "\"tokenAgente\":\"" + b64(TOKEN) + "\"}]}");
        byte[] privada = c.chaves.get(0).privadaOpenssh;
        byte[] token = c.servidores.get(0).tokenAgente;
        assertThrows(CofreCorrompidoException.class, c::paraMemoria);
        assertTrue(Zeragem.todoZero(privada), "a chave privada adotada foi zerada");
        assertTrue(Zeragem.todoZero(token));
    }

    @Test
    void conteudoInvalidoViraCorrompido() throws Exception {
        String ok = chaveJson("k1");
        String[] ruins = {
            "{\"versao\":4,\"chaves\":[],\"servidores\":[]}",
            "{\"versao\":1,\"chaves\":[" + ok + "," + ok + "],\"servidores\":[]}",                         // id de chave repetido
            "{\"versao\":1,\"chaves\":[" + chaveJson("K1") + "],\"servidores\":[]}",                       // id inválido
            "{\"versao\":1,\"chaves\":[" + ok.replace("ssh-ed25519\",\"priv", "ssh-rsa\",\"priv") + "],\"servidores\":[]}", // algoritmo
            "{\"versao\":1,\"chaves\":[" + ok.replaceAll("\"privadaOpenssh\":\"[^\"]*\",", "") + "],\"servidores\":[]}",   // sem privada
            "{\"versao\":1,\"chaves\":[null],\"servidores\":[]}",
            "{\"versao\":1,\"chaves\":[" + ok + "],\"servidores\":[null]}",
            "{\"versao\":1,\"chaves\":[" + ok + "],\"servidores\":[{\"id\":\"s1\",\"nome\":\"x\",\"host\":\"-h\",\"porta\":22,\"usuario\":\"u\",\"chaveId\":\"k1\"}]}",
            "{\"versao\":1,\"chaves\":[" + ok + "],\"servidores\":[{\"id\":\"s1\",\"nome\":\"x\",\"host\":\"h\",\"porta\":0,\"usuario\":\"u\",\"chaveId\":\"k1\"}]}",
            "{\"versao\":1,\"chaves\":[" + ok + "],\"servidores\":[{\"id\":\"s1\",\"nome\":\"x\",\"host\":\"h\",\"porta\":22,\"usuario\":\"u\",\"chaveId\":\"k1\",\"tokenAgente\":\"" + b64("lab_curto") + "\"}]}",
            "{\"versao\":1,\"chaves\":[" + ok + "],\"servidores\":[{\"id\":\"s1\",\"nome\":\"x\",\"host\":\"h\",\"porta\":22,\"usuario\":\"u\",\"chaveId\":\"k1\",\"transporte\":{\"tipo\":\"voador\"}}]}",
            "{\"versao\":1,\"chaves\":[" + ok + "],\"servidores\":[{\"id\":\"s1\",\"nome\":\"x\",\"host\":\"h\",\"porta\":22,\"usuario\":\"u\",\"chaveId\":\"k1\"},{\"id\":\"s1\",\"nome\":\"y\",\"host\":\"h\",\"porta\":22,\"usuario\":\"u\",\"chaveId\":\"k1\"}]}",
            "{\"versao\":1,\"chaves\":[" + ok + "],\"servidores\":[{\"id\":\"s1\",\"nome\":\"x\",\"host\":\"h\",\"porta\":22,\"usuario\":\"u\",\"chaveId\":\"k1\",\"pins\":[{\"algoritmo\":\"ssh-ed25519\",\"impressao\":\"MD5:xx\"}]}]}",
        };
        for (String json : ruins) {
            ConteudoCofre c = ler(json);
            assertThrows(CofreCorrompidoException.class, c::paraMemoria, json);
        }
    }

    @Test
    void toStringDoJsonNaoMostraSegredo() throws Exception {
        ConteudoCofre c = ler("{\"versao\":1,\"chaves\":[" + chaveJson("k1") + "],\"servidores\":[]}");
        assertFalse(c.toString().contains("OPENSSH"));
        assertFalse(c.chaves.get(0).toString().contains("OPENSSH"));
        assertEquals("ConteudoCofre[redigido]", c.toString());
    }

    @Test
    void idaEVoltaPelaMemoriaGeraOMesmoJson() throws Exception {
        String json = "{\"versao\":1,\"chaves\":[" + chaveJson("k1") + "],\"servidores\":[{"
            + "\"id\":\"s1\",\"nome\":\"lab\",\"host\":\"h\",\"porta\":22,\"usuario\":\"u\",\"chaveId\":\"k1\",\"portaAgente\":7000,"
            + "\"tokenAgente\":\"" + b64(TOKEN) + "\",\"pins\":[],\"transporte\":{\"tipo\":\"direto\"},"
            + "\"nomeDoDispositivo\":\"mac\",\"estado2fa\":\"pendente\"}]}";
        ConteudoEmMemoria m = ler(json).paraMemoria();
        var arvoreOriginal = Json.mapper().readTree(json);
        var arvoreNova = Json.mapper().readTree(Json.mapper().writeValueAsBytes(ConteudoCofre.de(m)));
        assertEquals(arvoreOriginal, arvoreNova);
    }
}
