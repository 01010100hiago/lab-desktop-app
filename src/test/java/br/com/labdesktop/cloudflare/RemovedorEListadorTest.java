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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Ver e remover os endereços que o APP criou (e só esses), nos dois tipos de túnel, com a mesma reversão da publicação. */
class RemovedorEListadorTest {

    private static final Clock RELOGIO = Clock.fixed(Instant.parse("2026-10-28T15:30:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tmp;

    LabSimulado lab;
    CloudflareFalsa cf;
    TokenCloudflare token;
    ConfiguracaoCloudflare porArquivo;
    ConfiguracaoCloudflare pelaApi;

    @BeforeEach
    void montar() throws Exception {
        lab = new LabSimulado(tmp);
        cf = new CloudflareFalsa();
        token = TokenCloudflare.deChars(CloudflareFalsa.TOKEN_CERTO.toCharArray());
        porArquivo = lab.configPorArquivo();
        pelaApi = new ConfiguracaoCloudflare(CloudflareFalsa.CONTA, "Conta", CloudflareFalsa.ZONA, "example.com", CloudflareFalsa.TUNEL_API, "t", ConfiguracaoCloudflare.Tipo.API, "lab", null, null, null);
        lab.escrever(LabSimulado.PROJETOS + "/demo/docker-compose.yml", PublicadorPorArquivoTest.COMPOSE);
        lab.escrever(LabSimulado.CONFIG, PublicadorPorArquivoTest.CONFIG);
    }

    @AfterEach
    void desmontar() throws Exception {
        token.zerar();
        cf.close();
        lab.close();
    }

    private ClienteCloudflare api() {
        return new ClienteCloudflare(cf.base(), token, ClienteCloudflare.Opcoes.rapida());
    }

    private RemovedorDeEndereco removedorDoArquivo() {
        ClienteCloudflare a = api();
        return new RemovedorDeEndereco(a, porArquivo, new TunelPorArquivo(lab.ambiente(), porArquivo, false, RELOGIO, d -> { }));
    }

    private void publicarDemo() throws Exception {
        ClienteCloudflare a = api();
        try (PublicadorNaInternet p = new PublicadorNaInternet(lab.ambiente(), a, porArquivo, new TunelPorArquivo(lab.ambiente(), porArquivo, false, RELOGIO, d -> { }), x -> { },
            (h, c) -> new TestadorDeUrl.Resultado(TestadorDeUrl.Estado.NO_AR, 200, "ok"), RELOGIO)) {
            PublicadorNaInternet.Resultado r = p.executar(p.preparar(PublicadorPorArquivoTest.pedido()), ObservadorDaEtapa.NENHUM, Cancelamento.NUNCA);
            assertEquals(PublicadorNaInternet.Desfecho.PUBLICADO, r.desfecho(), r.falha());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // listar
    // ---------------------------------------------------------------------------------------------

    @Test
    void listaSoOsEnderecosDoAppDeEsteTunelEDizSeARegraExiste() throws Exception {
        publicarDemo();
        cf.semear("meu-painel.example.com", "CNAME", LabSimulado.UUID + ".cfargotunnel.com", "do usuário, sem marca");
        cf.semear("lab-de-outro-tunel.example.com", "CNAME", CloudflareFalsa.TUNEL_API + ".cfargotunnel.com", "lab-desktop: outro");
        cf.semear("lab-sem-regra.example.com", "CNAME", LabSimulado.UUID + ".cfargotunnel.com", "lab-desktop: demo");
        try (ClienteCloudflare a = api()) {
            List<EnderecoDoApp> l = new ListadorDeEnderecos(a, porArquivo, lab.ambiente()).listar(null);
            assertEquals(List.of("lab-demo.example.com", "lab-sem-regra.example.com"), l.stream().map(EnderecoDoApp::hostname).toList(), "sem o do usuário e sem o de outro túnel");
            EnderecoDoApp demo = l.get(0);
            assertEquals("demo", demo.projeto());
            assertEquals("http://demo-app:3000", demo.servico());
            assertEquals(EnderecoDoApp.Regra.SIM, demo.regra());
            assertTrue(demo.temDns());
            assertEquals("https://lab-demo.example.com/", demo.url());
            assertEquals(EnderecoDoApp.Regra.NAO, l.get(1).regra(), "o DNS é do app, mas a regra não está no config.yml");
            assertEquals(List.of("lab-demo.example.com"), new ListadorDeEnderecos(a, porArquivo, lab.ambiente()).listar("demo").stream().map(EnderecoDoApp::hostname).toList().subList(0, 1));
            assertEquals(List.of(), new ListadorDeEnderecos(a, porArquivo, lab.ambiente()).listar("outro-projeto"));
        }
        assertEquals(List.of(), cf.escritas().stream().filter(w -> w.metodo().equals("GET")).toList());
        assertTrue(cf.pedidos.stream().allMatch(p -> p.metodo().equals("GET") || cf.escritas().contains(p)), "listar só LÊ");
    }

    @Test
    void listarSoFazGet() throws Exception {
        publicarDemo();
        int escritasAntes = cf.escritas().size();
        int reiniciosAntes = lab.reinicios.get();
        try (ClienteCloudflare a = api()) {
            new ListadorDeEnderecos(a, porArquivo, lab.ambiente()).listar(null);
        }
        assertEquals(escritasAntes, cf.escritas().size(), "nenhuma escrita na Cloudflare");
        assertEquals(reiniciosAntes, lab.reinicios.get());
    }

    @Test
    void seOConfigNaoAbreOsEnderecosVoltamComARegraNaoConferida() throws Exception {
        publicarDemo();
        java.nio.file.Files.delete(lab.arquivo(LabSimulado.CONFIG));
        try (ClienteCloudflare a = api()) {
            List<EnderecoDoApp> l = new ListadorDeEnderecos(a, porArquivo, lab.ambiente()).listar(null);
            assertEquals(1, l.size());
            assertEquals(EnderecoDoApp.Regra.NAO_CONFERIDA, l.get(0).regra());
        }
    }

    @Test
    void naApiOServicoVemDaConfiguracaoDoTunel() throws Exception {
        cf.tunel(CloudflareFalsa.TUNEL_API).configuracao = (ObjectNode) Json.mapper().readTree("{\"ingress\":[{\"hostname\":\"lab-demo.example.com\",\"service\":\"http://demo-app:3000\"},"
            + "{\"service\":\"http_status:404\"}]}");
        cf.semear("lab-demo.example.com", "CNAME", CloudflareFalsa.TUNEL_API + ".cfargotunnel.com", "lab-desktop: demo");
        try (ClienteCloudflare a = api()) {
            List<EnderecoDoApp> l = new ListadorDeEnderecos(a, pelaApi, null).listar(null);
            assertEquals("http://demo-app:3000", l.get(0).servico());
            assertEquals(EnderecoDoApp.Regra.SIM, l.get(0).regra());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // remover (túnel por arquivo)
    // ---------------------------------------------------------------------------------------------

    @Test
    void removerTiraARegraEODnsEOArquivoVoltaExatamenteAoQueEra() throws Exception {
        publicarDemo();
        int reiniciosAntes = lab.reinicios.get();
        try (RemovedorDeEndereco r = removedorDoArquivo()) {
            RemovedorDeEndereco.Previa previa = r.preparar("lab-demo.example.com");
            assertTrue(previa.podeRemover(), previa.bloqueio());
            assertEquals("demo", previa.projeto());
            assertTrue(previa.diferencaDoTunel().contains("- "), previa.diferencaDoTunel());
            assertTrue(previa.diferencaDoTunel().contains("-   # lab-desktop: lab-demo.example.com"), "a marca sai junto: " + previa.diferencaDoTunel());
            assertTrue(previa.passos().stream().anyMatch(s -> s.contains("apagar o registro CNAME lab-demo.example.com")), previa.passos().toString());
            RemovedorDeEndereco.Resultado res = r.executar(previa, ObservadorDaEtapa.NENHUM, Cancelamento.NUNCA);
            assertTrue(res.removido(), res.falha());
        }
        assertEquals(PublicadorPorArquivoTest.CONFIG, lab.ler(LabSimulado.CONFIG), "o config.yml voltou exatamente ao que o usuário tinha");
        assertEquals(List.of(), cf.registros.stream().filter(x -> x.nome.equals("lab-demo.example.com")).toList());
        assertEquals(reiniciosAntes + 1, lab.reinicios.get());
        assertEquals(PublicadorPorArquivoTest.CONFIG, lab.configNoUltimoReinicio, "o cloudflared reiniciou já sem a regra");
    }

    @Test
    void nuncaApagaRegraNemRegistroQueNaoSaoDoApp() throws Exception {
        cf.semear("meu-painel.example.com", "CNAME", LabSimulado.UUID + ".cfargotunnel.com", "do usuário, sem marca");
        try (RemovedorDeEndereco r = removedorDoArquivo()) {
            RemovedorDeEndereco.Previa previa = r.preparar("meu-painel.example.com");
            assertFalse(previa.podeRemover());
            assertTrue(previa.bloqueio().contains("não foi criado por este app"), previa.bloqueio());
            assertTrue(previa.bloqueio().contains("não foi criada por este app (não tem a marca"), "a regra do config.yml também é do usuário: " + previa.bloqueio());
            RemovedorDeEndereco.Resultado res = r.executar(previa, ObservadorDaEtapa.NENHUM, Cancelamento.NUNCA);
            assertFalse(res.removido());
        }
        assertEquals(PublicadorPorArquivoTest.CONFIG, lab.ler(LabSimulado.CONFIG));
        assertEquals(1, cf.registros.size());
        assertEquals(List.of(), cf.escritas());
        assertEquals(0, lab.reinicios.get());
    }

    @Test
    void registroDoAppSemARegraNoConfigRemoveSoOQueExiste() throws Exception {
        cf.semear("lab-sem-regra.example.com", "CNAME", LabSimulado.UUID + ".cfargotunnel.com", "lab-desktop: demo");
        try (RemovedorDeEndereco r = removedorDoArquivo()) {
            RemovedorDeEndereco.Previa previa = r.preparar("lab-sem-regra.example.com");
            assertTrue(previa.podeRemover(), previa.bloqueio());
            assertTrue(previa.passos().stream().anyMatch(s -> s.contains("já não está")), previa.passos().toString());
            assertTrue(r.executar(previa, ObservadorDaEtapa.NENHUM, Cancelamento.NUNCA).removido());
        }
        assertEquals(List.of(), cf.registros);
        assertEquals(0, lab.reinicios.get(), "sem regra para tirar, o cloudflared nem reinicia");
        assertEquals(PublicadorPorArquivoTest.CONFIG, lab.ler(LabSimulado.CONFIG));
    }

    @Test
    void enderecoDeOutroDominioOuComDoisNiveisOuInexistenteEBloqueado() throws Exception {
        try (RemovedorDeEndereco r = removedorDoArquivo()) {
            for (String ruim : List.of("lab-x.outro.example.com", "a.b.example.com", "example.com", "", "../x.example.com")) {
                assertFalse(r.preparar(ruim).podeRemover(), ruim);
            }
            RemovedorDeEndereco.Previa nada = r.preparar("nada.example.com");
            assertFalse(nada.podeRemover());
            assertTrue(nada.bloqueio().contains("Não achei nada deste endereço"), nada.bloqueio());
        }
    }

    @Test
    void seOApagarODnsFalhaARegraVoltaParaNadaFicarPelaMetade() throws Exception {
        publicarDemo();
        String comRegra = lab.ler(LabSimulado.CONFIG);
        cf.semPermissao(Permissao.DNS_ESCREVER);
        RemovedorDeEndereco.Resultado res;
        try (RemovedorDeEndereco r = removedorDoArquivo()) {
            res = r.executar(r.preparar("lab-demo.example.com"), ObservadorDaEtapa.NENHUM, Cancelamento.NUNCA);
        }
        assertFalse(res.removido());
        assertTrue(res.falha().contains("O token não tem permissão para apagar o registro de DNS"), res.falha());
        assertEquals(List.of("Devolvi a regra do túnel como estava."), res.desfeito());
        assertEquals(comRegra, lab.ler(LabSimulado.CONFIG), "a regra voltou");
        assertEquals(comRegra, lab.configNoUltimoReinicio, "e o cloudflared reiniciou de novo com ela");
        assertEquals(1, cf.registros.size());
        assertTrue(lab.rodando);
    }

    // ---------------------------------------------------------------------------------------------
    // remover (túnel pela API)
    // ---------------------------------------------------------------------------------------------

    @Test
    void naApiSoRemoveComORegistroMarcadoEDevolveARegraSeODnsFalha() throws Exception {
        cf.tunel(CloudflareFalsa.TUNEL_API).configuracao = (ObjectNode) Json.mapper().readTree("{\"ingress\":[{\"hostname\":\"lab-demo.example.com\",\"service\":\"http://demo-app:3000\"},"
            + "{\"hostname\":\"lab-de-teste.example.com\",\"service\":\"http://app:80\"},{\"service\":\"http_status:404\"}]}");
        cf.semear("lab-demo.example.com", "CNAME", CloudflareFalsa.TUNEL_API + ".cfargotunnel.com", "lab-desktop: demo");
        ClienteCloudflare a = api();
        try (RemovedorDeEndereco r = new RemovedorDeEndereco(a, pelaApi, new TunelPelaApi(a, pelaApi))) {
            // a regra sem registro marcado: a API não guarda marca, então o app não a tira
            RemovedorDeEndereco.Previa semMarca = r.preparar("lab-de-teste.example.com");
            assertFalse(semMarca.podeRemover());
            assertTrue(semMarca.bloqueio().contains("não guarda marca na regra"), semMarca.bloqueio());
            // com o registro marcado, remove regra e DNS
            RemovedorDeEndereco.Previa previa = r.preparar("lab-demo.example.com");
            assertTrue(previa.podeRemover(), previa.bloqueio());
            assertTrue(r.executar(previa, ObservadorDaEtapa.NENHUM, Cancelamento.NUNCA).removido());
        }
        assertEquals(List.of("lab-de-teste.example.com -> http://app:80", "(todo o resto) -> http_status:404"), RegraDaApi.linhas(cf.tunel(CloudflareFalsa.TUNEL_API).configuracao));
        assertEquals(List.of(), cf.registros);
        assertEquals(0, lab.reinicios.get());
    }

    @Test
    void naApiSeODnsNaoSaiARegraVolta() throws Exception {
        ObjectNode antes = (ObjectNode) Json.mapper().readTree("{\"ingress\":[{\"hostname\":\"lab-demo.example.com\",\"service\":\"http://demo-app:3000\"},{\"service\":\"http_status:404\"}]}");
        cf.tunel(CloudflareFalsa.TUNEL_API).configuracao = antes.deepCopy();
        cf.semear("lab-demo.example.com", "CNAME", CloudflareFalsa.TUNEL_API + ".cfargotunnel.com", "lab-desktop: demo");
        cf.falhar("DELETE /zones/.*", 500, "{\"success\":false,\"errors\":[{\"code\":1,\"message\":\"boom\"}]}", 1);
        ClienteCloudflare a = api();
        RemovedorDeEndereco.Resultado res;
        try (RemovedorDeEndereco r = new RemovedorDeEndereco(a, pelaApi, new TunelPelaApi(a, pelaApi))) {
            res = r.executar(r.preparar("lab-demo.example.com"), ObservadorDaEtapa.NENHUM, Cancelamento.NUNCA);
        }
        assertFalse(res.removido());
        assertEquals(antes, cf.tunel(CloudflareFalsa.TUNEL_API).configuracao, "a regra voltou");
        assertEquals(1, cf.registros.size());
    }
}
