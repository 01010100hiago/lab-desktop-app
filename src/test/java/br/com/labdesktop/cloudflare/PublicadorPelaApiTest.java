package br.com.labdesktop.cloudflare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.cofre.ConfiguracaoCloudflare;
import br.com.labdesktop.cofre.TokenCloudflare;
import br.com.labdesktop.teste.CloudflareFalsa;
import br.com.labdesktop.teste.CloudflareFalsa.Permissao;
import br.com.labdesktop.util.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Publicar com o túnel gerido pela API ({@code config_src = cloudflare}): lê a configuração, acrescenta a regra e grava, sem reiniciar nada no servidor. */
class PublicadorPelaApiTest {

    private static final Clock RELOGIO = Clock.fixed(Instant.parse("2026-10-28T15:30:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tmp;

    LabSimulado lab;
    CloudflareFalsa cf;
    TokenCloudflare token;
    ConfiguracaoCloudflare cfg;
    final List<String> recriados = new CopyOnWriteArrayList<>();

    @BeforeEach
    void montar() throws Exception {
        lab = new LabSimulado(tmp);
        cf = new CloudflareFalsa();
        token = TokenCloudflare.deChars(CloudflareFalsa.TOKEN_CERTO.toCharArray());
        cfg = new ConfiguracaoCloudflare(CloudflareFalsa.CONTA, "Conta de Teste", CloudflareFalsa.ZONA, "example.com", CloudflareFalsa.TUNEL_API, "projetos-na-nuvem",
            ConfiguracaoCloudflare.Tipo.API, "lab", null, null, null);
        lab.escrever(LabSimulado.PROJETOS + "/demo/docker-compose.yml", PublicadorPorArquivoTest.COMPOSE);
    }

    @AfterEach
    void desmontar() throws Exception {
        token.zerar();
        cf.close();
        lab.close();
    }

    PublicadorNaInternet publicador() {
        ClienteCloudflare api = new ClienteCloudflare(cf.base(), token, ClienteCloudflare.Opcoes.rapida());
        return new PublicadorNaInternet(lab.ambiente(), api, cfg, new TunelPelaApi(api, cfg), recriados::add,
            (h, c) -> new TestadorDeUrl.Resultado(TestadorDeUrl.Estado.NO_AR, 200, "No ar"), RELOGIO);
    }

    @Test
    void aPreviaMostraARegraNoJsonENaoGravaNada() throws Exception {
        try (PublicadorNaInternet p = publicador()) {
            PublicadorNaInternet.Previa previa = p.preparar(PublicadorPorArquivoTest.pedido());
            assertTrue(previa.podePublicar(), previa.bloqueio());
            assertTrue(previa.ondeEstaORegistro().contains("projetos-na-nuvem"), previa.ondeEstaORegistro());
            assertTrue(previa.diferencaDoTunel().contains("+ lab-demo.example.com -> http://demo-app:3000"), previa.diferencaDoTunel());
            assertTrue(previa.avisos().stream().anyMatch(a -> a.contains("entrega as regras novas ao cloudflared sozinha")), previa.avisos().toString());
            assertTrue(previa.passos().stream().noneMatch(s -> s.contains("reiniciar")), "pela API não há reinício: " + previa.passos());
        }
        assertEquals(List.of(), cf.escritas());
        assertEquals(List.of(), lab.comandos, "nem o Docker do servidor é consultado: o túnel é da API");
        assertEquals(3, cf.tunel(CloudflareFalsa.TUNEL_API).versao);
    }

    @Test
    void publicarGravaARegraAntesDaFinalPreservandoOResto() throws Exception {
        ObjectNode original = (ObjectNode) Json.mapper().readTree("{\"ingress\":[{\"hostname\":\"meu-painel.example.com\",\"service\":\"http://app:3000\",\"originRequest\":{\"noTLSVerify\":true}},"
            + "{\"service\":\"http_status:404\"}],\"warp-routing\":{\"enabled\":true}}");
        cf.tunel(CloudflareFalsa.TUNEL_API).configuracao = original;
        PublicadorNaInternet.Resultado r;
        try (PublicadorNaInternet p = publicador()) {
            r = p.executar(p.preparar(PublicadorPorArquivoTest.pedido()), (e, s, f) -> { }, Cancelamento.NUNCA);
        }
        assertEquals(PublicadorNaInternet.Desfecho.PUBLICADO, r.desfecho(), r.falha());
        ObjectNode agora = cf.tunel(CloudflareFalsa.TUNEL_API).configuracao;
        assertEquals(List.of("meu-painel.example.com -> http://app:3000", "lab-demo.example.com -> http://demo-app:3000", "(todo o resto) -> http_status:404"), RegraDaApi.linhas(agora));
        assertTrue(agora.path("ingress").get(0).path("originRequest").path("noTLSVerify").asBoolean(), "as opções da regra do usuário ficam");
        assertTrue(agora.path("warp-routing").path("enabled").asBoolean(), "o roteamento WARP fica");
        assertEquals(0, lab.reinicios.get(), "nenhum reinício: a Cloudflare entrega a configuração");
        assertEquals(1, cf.registros.size());
        assertEquals("lab-desktop: demo", cf.registro("lab-demo.example.com").comentario);
        assertEquals(List.of("demo"), recriados);
        assertTrue(lab.comandos.stream().noneMatch(c -> c.contains("cloudflared") || c.contains("restart")), lab.comandos.toString());
    }

    @Test
    void semPermissaoParaGravarOTunelDesfazOComposeENaoCriaDns() throws Exception {
        cf.semPermissao(Permissao.TUNEL_ESCREVER);
        PublicadorNaInternet.Resultado r;
        try (PublicadorNaInternet p = publicador()) {
            r = p.executar(p.preparar(PublicadorPorArquivoTest.pedido()), (e, s, f) -> { }, Cancelamento.NUNCA);
        }
        assertEquals(PublicadorNaInternet.Desfecho.FALHOU, r.desfecho());
        assertEquals(Etapa.REGRA, r.etapaDaFalha());
        assertTrue(r.falha().contains("O token não tem permissão para gravar a configuração do túnel"), r.falha());
        assertEquals(PublicadorPorArquivoTest.COMPOSE, lab.ler(LabSimulado.PROJETOS + "/demo/docker-compose.yml"));
        assertEquals(List.of(), cf.registros);
        assertEquals(3, cf.tunel(CloudflareFalsa.TUNEL_API).versao);
    }

    @Test
    void seAConfiguracaoMudaDepoisDaPreviaNadaEGravado() throws Exception {
        try (PublicadorNaInternet p = publicador()) {
            PublicadorNaInternet.Previa previa = p.preparar(PublicadorPorArquivoTest.pedido());
            ObjectNode dele = (ObjectNode) Json.mapper().readTree("{\"ingress\":[{\"hostname\":\"x.example.com\",\"service\":\"http://x:1\"},{\"service\":\"http_status:404\"}]}");
            cf.tunel(CloudflareFalsa.TUNEL_API).configuracao = dele;
            PublicadorNaInternet.Resultado r = p.executar(previa, (e, s, f) -> { }, Cancelamento.NUNCA);
            assertEquals(PublicadorNaInternet.Desfecho.FALHOU, r.desfecho());
            assertTrue(r.falha().contains("mudou na Cloudflare depois que você viu"), r.falha());
            assertEquals(dele, cf.tunel(CloudflareFalsa.TUNEL_API).configuracao, "o que a outra pessoa gravou ficou intacto");
        }
        assertEquals(PublicadorPorArquivoTest.COMPOSE, lab.ler(LabSimulado.PROJETOS + "/demo/docker-compose.yml"));
    }

    @Test
    void seAGravacaoPerdeARespostaMasGravouOAppConfereEDevolveAAnterior() throws Exception {
        ObjectNode antes = cf.tunel(CloudflareFalsa.TUNEL_API).configuracao.deepCopy();
        cf.executarEPerderAResposta("PUT .*/configurations", 1);
        PublicadorNaInternet.Resultado r;
        try (PublicadorNaInternet p = publicador()) {
            r = p.executar(p.preparar(PublicadorPorArquivoTest.pedido()), (e, s, f) -> { }, Cancelamento.NUNCA);
        }
        assertEquals(PublicadorNaInternet.Desfecho.FALHOU, r.desfecho());
        assertTrue(r.falha().contains("Conferi"), r.falha());
        assertEquals(antes, cf.tunel(CloudflareFalsa.TUNEL_API).configuracao, "a configuração gravada (resposta perdida) foi devolvida");
        assertEquals(PublicadorPorArquivoTest.COMPOSE, lab.ler(LabSimulado.PROJETOS + "/demo/docker-compose.yml"));
        assertEquals(List.of(), cf.registros);
    }

    @Test
    void seODnsFalhaDevolveAConfiguracaoAnteriorDoTunel() throws Exception {
        ObjectNode antes = cf.tunel(CloudflareFalsa.TUNEL_API).configuracao.deepCopy();
        cf.semPermissao(Permissao.DNS_ESCREVER);
        PublicadorNaInternet.Resultado r;
        try (PublicadorNaInternet p = publicador()) {
            r = p.executar(p.preparar(PublicadorPorArquivoTest.pedido()), (e, s, f) -> { }, Cancelamento.NUNCA);
        }
        assertEquals(PublicadorNaInternet.Desfecho.FALHOU, r.desfecho());
        assertEquals(Etapa.DNS, r.etapaDaFalha());
        assertTrue(r.falha().contains("criar o registro de DNS"), r.falha());
        assertEquals(antes, cf.tunel(CloudflareFalsa.TUNEL_API).configuracao, "a regra que a execução gravou foi tirada");
        assertEquals(PublicadorPorArquivoTest.COMPOSE, lab.ler(LabSimulado.PROJETOS + "/demo/docker-compose.yml"));
        assertFalse(r.desfeito().isEmpty());
    }

    @Test
    void regraDuplicadaNaApiBloqueiaESeJaEstavaComOMesmoServicoNaoGravaDeNovo() throws Exception {
        cf.tunel(CloudflareFalsa.TUNEL_API).configuracao = (ObjectNode) Json.mapper().readTree("{\"ingress\":[{\"hostname\":\"lab-demo.example.com\",\"service\":\"http://outro:9\"},"
            + "{\"service\":\"http_status:404\"}]}");
        try (PublicadorNaInternet p = publicador()) {
            PublicadorNaInternet.Previa previa = p.preparar(PublicadorPorArquivoTest.pedido());
            assertFalse(previa.podePublicar());
            assertTrue(previa.bloqueio().contains("já está nas regras do túnel e aponta para http://outro:9"), previa.bloqueio());
        }
        cf.tunel(CloudflareFalsa.TUNEL_API).configuracao = (ObjectNode) Json.mapper().readTree("{\"ingress\":[{\"hostname\":\"lab-demo.example.com\",\"service\":\"http://demo-app:3000\"},"
            + "{\"service\":\"http_status:404\"}]}");
        int versao = cf.tunel(CloudflareFalsa.TUNEL_API).versao;
        try (PublicadorNaInternet p = publicador()) {
            PublicadorNaInternet.Resultado r = p.executar(p.preparar(PublicadorPorArquivoTest.pedido()), (e, s, f) -> { }, Cancelamento.NUNCA);
            assertEquals(PublicadorNaInternet.Desfecho.PUBLICADO, r.desfecho(), r.falha());
        }
        assertEquals(versao, cf.tunel(CloudflareFalsa.TUNEL_API).versao, "a regra já estava lá: nada gravado");
    }
}
