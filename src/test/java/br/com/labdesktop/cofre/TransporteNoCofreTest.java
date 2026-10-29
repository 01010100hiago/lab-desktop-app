package br.com.labdesktop.cofre;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.teste.EntradasHostis;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * "Como conectar" no cofre: o modelo do transporte (SSM e Cloudflare, dados validados), a compatibilidade (cofre de antes abre como direto; servidor direto grava o MESMO
 * arquivo de hoje; a versão do arquivo só sobe com transporte diferente de direto) e as regras do pin ao editar (trocar o modo mantém, mudar o destino apaga).
 */
class TransporteNoCofreTest {

    private static final char[] SENHA = "uma-senha-mestra-de-teste".toCharArray();
    private static final String TOKEN = "lab_" + "T".repeat(43);
    private static final String INSTANCIA = "i-0123456789abcdef0";
    private static final Pin PIN = new Pin("ssh-ed25519", "SHA256:abcdefghijkl");

    @TempDir
    Path tmp;

    private static ConfigServidor direto() {
        return new ConfigServidor("s1", "lab", "192.0.2.5", 2222, "labuser", "k1", 7000, List.of(PIN), Transporte.DIRETO, null, Estado2fa.DESLIGADO);
    }

    private static ConfigServidor ssm() {
        return new ConfigServidor("s1", "lab", "ignorado", 99, "ubuntu", "k1", 7000, List.of(PIN), Transporte.ssm(INSTANCIA, "us-east-1", "meu-perfil", 22), "mac", Estado2fa.ATIVO);
    }

    private static ConfigServidor cloudflare() {
        return new ConfigServidor("s1", "lab", "ignorado", 99, "ubuntu", "k1", 7000, List.of(PIN), Transporte.cloudflared("ssh.example.com"), null, Estado2fa.DESLIGADO);
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.US_ASCII));
    }

    private static String chaveJson() {
        return "{\"id\":\"k1\",\"nome\":\"lab\",\"algoritmo\":\"ssh-ed25519\","
            + "\"privadaOpenssh\":\"" + b64("-----BEGIN OPENSSH PRIVATE KEY-----\nxx\n-----END OPENSSH PRIVATE KEY-----\n") + "\","
            + "\"publica\":\"ssh-ed25519 AAAAC3Nza lab-desktop@mac\",\"impressao\":\"SHA256:abcdefghijkl\",\"criadaEm\":\"2026-10-20T13:00:00Z\"}";
    }

    private static String json(int versao, String transporte, String extras) {
        return "{\"versao\":" + versao + ",\"chaves\":[" + chaveJson() + "],\"servidores\":[{"
            + "\"id\":\"s1\",\"nome\":\"lab\",\"host\":\"h\",\"porta\":22,\"usuario\":\"u\",\"chaveId\":\"k1\",\"portaAgente\":7000,"
            + "\"tokenAgente\":\"" + b64(TOKEN) + "\",\"pins\":[],\"transporte\":" + transporte + ",\"estado2fa\":\"desligado\"" + extras + "}]}";
    }

    private static ConfigServidor ler(String json) throws Exception {
        return Json.mapper().readValue(json, ConteudoCofre.class).paraMemoria().servidores.get(0).config;
    }

    private static int versaoGravada(String json) throws Exception {
        return ConteudoCofre.de(Json.mapper().readValue(json, ConteudoCofre.class).paraMemoria()).versao;
    }

    // ---------------------------------------------------------------------------------------------
    // O modelo
    // ---------------------------------------------------------------------------------------------

    @Test
    void oPadraoEDiretoEUmServidorNovoNaoTemTunel() {
        assertEquals(Transporte.DIRETO, ConfigServidor.novo("lab", "192.0.2.5", 22, "u", "k1").transporte());
        assertFalse(Transporte.DIRETO.ehTunel());
        assertFalse(Transporte.LOCAL.ehTunel());
        assertTrue(Transporte.ssm(INSTANCIA, "us-east-1", null, 22).ehTunel());
        assertTrue(Transporte.cloudflared("a.b").ehTunel());
    }

    @Test
    void ssmGuardaOsQuatroDadosEORestoFicaVazio() {
        Transporte t = Transporte.ssm(INSTANCIA, "us-east-1", "meu-perfil", 2222);
        assertEquals(Transporte.Tipo.SSM, t.tipo());
        assertEquals(INSTANCIA, t.instanciaId());
        assertEquals("us-east-1", t.regiao());
        assertEquals("meu-perfil", t.perfilAws());
        assertEquals(2222, t.portaSshNaInstancia());
        assertNull(t.hostname());
        assertEquals("Sem porta aberta: SSM", t.selo().orElseThrow());
        assertEquals(INSTANCIA + " (us-east-1, perfil meu-perfil)", t.destinoEmTexto());
        // perfil e região são opcionais
        Transporte minimo = Transporte.ssm(INSTANCIA, "", "", 22);
        assertNull(minimo.regiao());
        assertNull(minimo.perfilAws());
        assertEquals(INSTANCIA, minimo.destinoEmTexto());
    }

    @Test
    void cloudflareGuardaOHostnameEmMinusculasESoIsso() {
        Transporte t = Transporte.cloudflared("SSH.Example.com");
        assertEquals("ssh.example.com", t.hostname());
        assertNull(t.instanciaId());
        assertEquals(0, t.portaSshNaInstancia());
        assertEquals("Sem porta aberta: Cloudflare", t.selo().orElseThrow());
        assertTrue(Transporte.DIRETO.selo().isEmpty());
        assertEquals(new Transporte(Transporte.Tipo.DIRETO, "ignorado"), Transporte.DIRETO, "o direto não guarda hostname");
    }

    @Test
    void oConstrutorRecusaDadoHostilEmQualquerCampo() {
        for (String h : EntradasHostis.TODAS) {
            assertThrows(IllegalArgumentException.class, () -> Transporte.ssm(h, "us-east-1", null, 22), "instância " + h);
            assertThrows(IllegalArgumentException.class, () -> Transporte.cloudflared(h), "hostname " + h);
            if (!h.isBlank()) {
                assertThrows(IllegalArgumentException.class, () -> Transporte.ssm(INSTANCIA, h, null, 22), "região " + h);
                assertThrows(IllegalArgumentException.class, () -> Transporte.ssm(INSTANCIA, "us-east-1", h, 22), "perfil " + h);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> Transporte.ssm(INSTANCIA, "us-east-1", null, 0));
        assertThrows(IllegalArgumentException.class, () -> Transporte.ssm(INSTANCIA, "us-east-1", null, 65_536));
        assertThrows(IllegalArgumentException.class, () -> new Transporte(Transporte.Tipo.SSM, null), "SSM sem dados não existe");
        assertThrows(IllegalArgumentException.class, () -> Transporte.cloudflared(" "));
        assertThrows(IllegalArgumentException.class, () -> new Transporte(null, null));
    }

    @Test
    void servidorSemPortaAbertaNaoTemEnderecoNemPortaDeSsh() {
        ConfigServidor c = ssm();
        assertEquals(ConfigServidor.HOST_DO_TRANSPORTE, c.host(), "o host guardado é um nome que nunca resolve");
        assertTrue(c.host().endsWith(".invalid"));
        assertEquals(0, c.porta());
        assertEquals("ubuntu", c.usuario());
        assertEquals("k1", c.chaveId());
        assertEquals(INSTANCIA + " (us-east-1, perfil meu-perfil)", c.enderecoParaMostrar());
        assertEquals("ssh.example.com", cloudflare().enderecoParaMostrar());
        assertEquals("192.0.2.5:2222", direto().enderecoParaMostrar());
        // usuário e chave continuam valendo
        assertThrows(IllegalArgumentException.class, () -> new ConfigServidor("s1", "lab", "x", 0, "-u", "k1", 7000, null, ssm().transporte(), null, null));
        assertThrows(IllegalArgumentException.class, () -> new ConfigServidor("s1", "lab", "x", 0, "u", "K1", 7000, null, ssm().transporte(), null, null));
    }

    @Test
    void todosOsMetodosComEEditarPreservamOTransporteEOsCampos() {
        for (ConfigServidor c : List.of(ssm().comProtegido(true).comProtegido(false).comProtegido(true), cloudflare().comProtegido(true))) {
            Transporte t = c.transporte();
            for (ConfigServidor depois : List.of(
                c.comPin(new Pin("ecdsa-sha2-nistp256", "SHA256:zzzzzzzzzzzz")), c.comPins(List.of(PIN)), c.semPins(), c.comNomeDoDispositivo("novo"), c.comEstado2fa(Estado2fa.DESLIGADO),
                c.comChaveId("k2"), c.comProtegido(true), c.comProtecaoAutomatica(), c.editar("outro", "h2", 2222, "outro", 7001), c.editar("outro", null, "h2", 2222, "outro", 7001),
                c.comTransporte(t))) {
                assertEquals(t, depois.transporte(), "perdeu o transporte em " + depois);
            }
            assertEquals(c.protegido(), c.comPin(PIN).protegido());
            assertEquals(c.protecaoAutomaticaRecusada(), c.comChaveId("k2").protecaoAutomaticaRecusada());
            assertEquals(c.nomeDoDispositivo(), c.semPins().nomeDoDispositivo());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // O pin e o modo
    // ---------------------------------------------------------------------------------------------

    @Test
    void trocarOModoMantemOsPinsEmTodasAsDirecoes() {
        ConfigServidor d = direto();
        ConfigServidor viraSsm = d.editar("lab", Transporte.ssm(INSTANCIA, "us-east-1", null, 22), "x", 0, "labuser", 7000);
        assertEquals(List.of(PIN), viraSsm.pins(), "direto -> SSM mantém o pin");
        ConfigServidor viraCloud = viraSsm.editar("lab", Transporte.cloudflared("ssh.example.com"), "x", 0, "labuser", 7000);
        assertEquals(List.of(PIN), viraCloud.pins(), "SSM -> Cloudflare mantém o pin");
        ConfigServidor voltaDireto = viraCloud.editar("lab", Transporte.DIRETO, "192.0.2.5", 2222, "labuser", 7000);
        assertEquals(List.of(PIN), voltaDireto.pins(), "Cloudflare -> direto mantém o pin");
        assertEquals("192.0.2.5", voltaDireto.host());
        assertEquals(2222, voltaDireto.porta());
        assertEquals(Transporte.DIRETO, voltaDireto.transporte());
        assertEquals(d.id(), voltaDireto.id(), "o pin é do servidor (o id), não do endereço");
    }

    @Test
    void mudarODestinoDentroDoMesmoModoApagaOsPins() {
        // direto: o endereço ou a porta (a regra de sempre)
        assertTrue(direto().editar("lab", "192.0.2.6", 2222, "labuser", 7000).pins().isEmpty());
        assertTrue(direto().editar("lab", "192.0.2.5", 22, "labuser", 7000).pins().isEmpty());
        assertEquals(List.of(PIN), direto().editar("outro", "192.0.2.5", 2222, "outro", 7001).pins());
        assertEquals(List.of(PIN), direto().editar("lab", "192.0.2.5", 2222, "labuser", 7000).pins());
        assertEquals(List.of(PIN), direto().editar("lab", "192.0.2.5".toUpperCase(), 2222, "labuser", 7000).pins());
        // SSM: outra instância, outra região ou outra porta SSH apagam; o perfil não
        ConfigServidor s = ssm();
        assertTrue(s.editar("lab", Transporte.ssm("i-0fff1234def567890", "us-east-1", "meu-perfil", 22), "x", 0, "ubuntu", 7000).pins().isEmpty());
        assertTrue(s.editar("lab", Transporte.ssm(INSTANCIA, "eu-west-1", "meu-perfil", 22), "x", 0, "ubuntu", 7000).pins().isEmpty());
        assertTrue(s.editar("lab", Transporte.ssm(INSTANCIA, "us-east-1", "meu-perfil", 2222), "x", 0, "ubuntu", 7000).pins().isEmpty());
        assertEquals(List.of(PIN), s.editar("lab", Transporte.ssm(INSTANCIA, "us-east-1", "outro-perfil", 22), "x", 0, "ubuntu", 7000).pins());
        assertEquals(List.of(PIN), s.editar("novo nome", null, "x", 0, "outro", 7001).pins(), "só nome, usuário e porta do agente");
        // Cloudflare: outro hostname apaga; o mesmo (mesmo em maiúsculas) não
        ConfigServidor c = cloudflare();
        assertTrue(c.editar("lab", Transporte.cloudflared("outro.example.com"), "x", 0, "ubuntu", 7000).pins().isEmpty());
        assertEquals(List.of(PIN), c.editar("lab", Transporte.cloudflared("SSH.EXAMPLE.COM"), "x", 0, "ubuntu", 7000).pins());
    }

    @Test
    void numServidorSemPortaAbertaOEnderecoDigitadoNaoValeENaoApagaPins() {
        ConfigServidor depois = ssm().editar("lab", "qualquer.coisa.example.com", 4242, "ubuntu", 7000);
        assertEquals(List.of(PIN), depois.pins());
        assertEquals(ConfigServidor.HOST_DO_TRANSPORTE, depois.host());
        assertTrue(ssm().mesmoEnderecoQue(ssm().editar("outro", "x", 1, "u", 7000)));
        assertFalse(ssm().mesmoEnderecoQue(direto()));
        assertFalse(ssm().mesmoEnderecoQue(cloudflare()));
    }

    @Test
    void daParaVoltarAoDiretoSemEnderecoValidoRecusa() {
        assertThrows(IllegalArgumentException.class, () -> ssm().editar("lab", Transporte.DIRETO, "", 22, "ubuntu", 7000));
        assertThrows(IllegalArgumentException.class, () -> ssm().editar("lab", Transporte.DIRETO, "-oProxyCommand=x", 22, "ubuntu", 7000));
    }

    // ---------------------------------------------------------------------------------------------
    // O JSON e a versão do arquivo
    // ---------------------------------------------------------------------------------------------

    @Test
    void cofreDeAntesAbreComoDireto() throws Exception {
        ConfigServidor c = ler(json(1, "{\"tipo\":\"direto\"}", ""));
        assertEquals(Transporte.DIRETO, c.transporte());
        ConfigServidor semCampo = Json.mapper().readValue(
            json(1, "{\"tipo\":\"direto\"}", "").replace(",\"transporte\":{\"tipo\":\"direto\"}", ""), ConteudoCofre.class).paraMemoria().servidores.get(0).config;
        assertEquals(Transporte.DIRETO, semCampo.transporte(), "um cofre sem o campo transporte também");
    }

    @Test
    void servidorDiretoGravaExatamenteOMesmoArquivoDeHoje() throws Exception {
        String original = json(1, "{\"tipo\":\"direto\"}", "");
        var nova = Json.mapper().readTree(Json.mapper().writeValueAsBytes(ConteudoCofre.de(Json.mapper().readValue(original, ConteudoCofre.class).paraMemoria())));
        assertEquals(Json.mapper().readTree(original), nova, "nenhum campo novo");
        assertEquals(1, nova.get("versao").asInt());
        var transporte = nova.get("servidores").get(0).get("transporte");
        assertEquals(List.of("tipo"), java.util.stream.StreamSupport.stream(java.util.Spliterators.spliteratorUnknownSize(transporte.fieldNames(), 0), false).toList());
    }

    @Test
    void idaEVoltaDoSsmEDaCloudflarePeloJson() throws Exception {
        String ssmJson = "{\"tipo\":\"ssm\",\"instanciaId\":\"" + INSTANCIA + "\",\"regiao\":\"us-east-1\",\"perfilAws\":\"meu-perfil\",\"portaSshNaInstancia\":2222}";
        String doSsm = json(4, ssmJson, "").replace("\"host\":\"h\",\"porta\":22,", "\"porta\":0,");
        ConfigServidor c = ler(doSsm);
        assertEquals(Transporte.ssm(INSTANCIA, "us-east-1", "meu-perfil", 2222), c.transporte());
        var original = Json.mapper().readTree(doSsm);
        var nova = Json.mapper().readTree(Json.mapper().writeValueAsBytes(ConteudoCofre.de(Json.mapper().readValue(doSsm, ConteudoCofre.class).paraMemoria())));
        assertEquals(original, nova, "ida e volta idêntica (sem host: o sem porta aberta não guarda endereço)");
        assertFalse(nova.get("servidores").get(0).has("host"));

        String cloudJson = "{\"tipo\":\"cloudflared\",\"hostname\":\"ssh.example.com\"}";
        String daCloud = json(4, cloudJson, "").replace("\"host\":\"h\",\"porta\":22,", "\"porta\":0,");
        assertEquals(Transporte.cloudflared("ssh.example.com"), ler(daCloud).transporte());
        assertEquals(Json.mapper().readTree(daCloud),
            Json.mapper().readTree(Json.mapper().writeValueAsBytes(ConteudoCofre.de(Json.mapper().readValue(daCloud, ConteudoCofre.class).paraMemoria()))));
    }

    @Test
    void asPortaSshAusenteNoJsonDoSsmValeVinteEDois() throws Exception {
        ConfigServidor c = ler(json(4, "{\"tipo\":\"ssm\",\"instanciaId\":\"" + INSTANCIA + "\"}", ""));
        assertEquals(22, c.transporte().portaSshNaInstancia());
        assertNull(c.transporte().regiao());
        assertNull(c.transporte().perfilAws());
    }

    @Test
    void aVersaoSoSobeQuandoAlgumServidorUsaTransporteDiferenteDeDireto() throws Exception {
        assertEquals(1, versaoGravada(json(1, "{\"tipo\":\"direto\"}", "")));
        assertEquals(3, versaoGravada(json(1, "{\"tipo\":\"direto\"}", ",\"protegido\":true")), "a proteção continua na 3");
        assertEquals(4, versaoGravada(json(1, "{\"tipo\":\"ssm\",\"instanciaId\":\"" + INSTANCIA + "\"}", "")));
        assertEquals(4, versaoGravada(json(1, "{\"tipo\":\"cloudflared\",\"hostname\":\"a.b\"}", "")));
        assertEquals(4, versaoGravada(json(1, "{\"tipo\":\"ssm\",\"instanciaId\":\"" + INSTANCIA + "\"}", ",\"protegido\":true")), "SSM e proteção juntos: a 4, que vale para os dois");
    }

    @Test
    void aVersao4NaoEUmaDasQueOAppDeAntesAceitaEAsDeAntesContinuamLendo() throws Exception {
        // um app sem os modos de conexão sem porta aberta aceita 1, 2 e 3; a 4 o faz recusar com "versão não suportada" em vez de ler o SSM como direto e apagá-lo ao regravar
        Set<Integer> aceitasDeAntes = Set.of(ConteudoCofre.VERSAO, ConteudoCofre.VERSAO_COM_SERVIDOR_LOCAL, ConteudoCofre.VERSAO_COM_PROTECAO);
        assertFalse(aceitasDeAntes.contains(ConteudoCofre.VERSAO_COM_TRANSPORTE));
        assertEquals(4, ConteudoCofre.VERSAO_COM_TRANSPORTE);
        assertTrue(ConteudoCofre.VERSAO_COM_TRANSPORTE > ConteudoCofre.VERSAO_COM_PROTECAO);
        for (int v : new int[] {1, 2, 3, 4}) {
            assertEquals("lab", ler(json(v, "{\"tipo\":\"direto\"}", "")).nome(), "versão " + v);
        }
        assertThrows(CofreCorrompidoException.class, () -> ler(json(5, "{\"tipo\":\"direto\"}", "")));
    }

    @Test
    void jsonDeSsmComDadoRuimViraCofreCorrompido() throws Exception {
        for (String ruim : List.of(
            "{\"tipo\":\"ssm\"}",
            "{\"tipo\":\"ssm\",\"instanciaId\":\"i-0abc;rm -rf\"}",
            "{\"tipo\":\"ssm\",\"instanciaId\":\"-i-0abc1234\"}",
            "{\"tipo\":\"ssm\",\"instanciaId\":\"" + INSTANCIA + "\",\"regiao\":\"us east 1\"}",
            "{\"tipo\":\"ssm\",\"instanciaId\":\"" + INSTANCIA + "\",\"perfilAws\":\"--profile\"}",
            "{\"tipo\":\"ssm\",\"instanciaId\":\"" + INSTANCIA + "\",\"portaSshNaInstancia\":0}",
            "{\"tipo\":\"ssm\",\"instanciaId\":\"" + INSTANCIA + "\",\"portaSshNaInstancia\":70000}",
            "{\"tipo\":\"cloudflared\"}",
            "{\"tipo\":\"cloudflared\",\"hostname\":\"https://a.b\"}",
            "{\"tipo\":\"cloudflared\",\"hostname\":\"a.b;x\"}",
            "{\"tipo\":\"voador\"}")) {
            assertThrows(CofreCorrompidoException.class, () -> Json.mapper().readValue(json(4, ruim, ""), ConteudoCofre.class).paraMemoria(), ruim);
        }
    }

    @Test
    void idaEVoltaPeloCofreDeVerdadeEPeloArquivoTemporario() throws Exception {
        CaminhosApp caminhos = CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
        String id;
        try (CofreDeChaves cofre = CofreDeChaves.criar(caminhos, SENHA.clone(), ParametrosKdf.MINIMO)) {
            ChaveSsh chave = GeradorDeChaves.gerar("k", "mac");
            cofre.adicionarChave(chave);
            ConfigServidor cfg = new ConfigServidor(ConfigServidor.novoId(), "lab", "192.0.2.5", 22, "labuser", chave.id(), 7000, List.of(PIN), Transporte.DIRETO, null, Estado2fa.DESLIGADO);
            id = cfg.id();
            cofre.adicionarServidor(cfg, null);
            cofre.alterarServidor(id, atual -> atual.editar("lab", Transporte.ssm(INSTANCIA, "us-east-1", "p", 22), "x", 0, "labuser", 7000));
        }
        try (CofreDeChaves cofre = CofreDeChaves.abrir(caminhos, SENHA.clone(), ParametrosKdf.MINIMO)) {
            ConfigServidor lido = cofre.servidor(id).orElseThrow();
            assertEquals(Transporte.ssm(INSTANCIA, "us-east-1", "p", 22), lido.transporte());
            assertEquals(List.of(PIN), lido.pins(), "o pin sobreviveu à troca de modo e ao fechar e abrir o cofre");
            cofre.alterarServidor(id, atual -> atual.editar("lab", Transporte.cloudflared("ssh.example.com"), "x", 0, "labuser", 7000));
        }
        try (CofreDeChaves cofre = CofreDeChaves.abrir(caminhos, SENHA.clone(), ParametrosKdf.MINIMO)) {
            ConfigServidor lido = cofre.servidor(id).orElseThrow();
            assertEquals(Transporte.cloudflared("ssh.example.com"), lido.transporte());
            assertEquals(List.of(PIN), lido.pins());
            cofre.alterarServidor(id, atual -> atual.editar("lab", Transporte.DIRETO, "192.0.2.5", 22, "labuser", 7000));
        }
        try (CofreDeChaves cofre = CofreDeChaves.abrir(caminhos, SENHA.clone(), ParametrosKdf.MINIMO)) {
            ConfigServidor lido = cofre.servidor(id).orElseThrow();
            assertEquals(Transporte.DIRETO, lido.transporte());
            assertEquals("192.0.2.5", lido.host());
            assertEquals(List.of(PIN), lido.pins());
            assertNotEquals(ConfigServidor.HOST_DO_TRANSPORTE, lido.host());
        }
    }
}
