package br.com.labdesktop.cloudflare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.cloudflare.ErroCloudflare.Tipo;
import br.com.labdesktop.cofre.ChaveSsh;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.cofre.ConfiguracaoCloudflare;
import br.com.labdesktop.cofre.GeradorDeChaves;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.cofre.TokenCloudflare;
import br.com.labdesktop.projetos.FalhaDoProjeto;
import br.com.labdesktop.teste.CloudflareFalsa;
import br.com.labdesktop.teste.CloudflareFalsa.Permissao;
import br.com.labdesktop.util.CaminhosApp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A fachada da Cloudflare de um servidor, com o cofre de VERDADE: conectar (descobrir e salvar), o token só do cofre, desconectar, e o que vale no computador local. */
class ServicoCloudflareTest {

    private static final Clock RELOGIO = Clock.fixed(Instant.parse("2026-10-28T15:30:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tmp;

    CloudflareFalsa cf;
    CofreDeChaves cofre;
    String servidorId;
    LabSimulado lab;

    @BeforeEach
    void montar() throws Exception {
        cf = new CloudflareFalsa();
        cofre = CofreDeChaves.criar(CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs")), "uma-senha-mestra-de-teste".toCharArray(), ParametrosKdf.MINIMO);
        ChaveSsh chave = GeradorDeChaves.gerar("lab", "mac-de-teste");
        cofre.adicionarChave(chave);
        ConfigServidor s = ConfigServidor.novo("lab", "192.0.2.5", 2222, "labuser", chave.id());
        cofre.adicionarServidor(s, null);
        servidorId = s.id();
    }

    @AfterEach
    void desmontar() throws Exception {
        cf.close();
        cofre.close();
        if (lab != null) {
            lab.close();
        }
    }

    private ServicoCloudflare servico(boolean local) {
        return new ServicoCloudflare(cofre, servidorId, local, raiz -> lab == null ? null : lab.ambiente(), cf::base, ClienteCloudflare.Opcoes.rapida(),
            () -> (h, c) -> new TestadorDeUrl.Resultado(TestadorDeUrl.Estado.NO_AR, 200, "No ar"), RELOGIO, d -> { });
    }

    private static char[] colar(String t) {
        return ("  " + t + "\n").toCharArray();
    }

    @Test
    void descobrirConfereOTokenEListaContasEDominiosSemSalvarNada() throws Exception {
        char[] colado = colar(CloudflareFalsa.TOKEN_CERTO);
        try (ServicoCloudflare.Descoberta d = servico(false).descobrir(colado)) {
            assertEquals("active", d.verificado().status());
            assertEquals(List.of("example.com"), d.zonas().stream().map(ZonaCf::nome).toList());
            assertEquals(List.of(CloudflareFalsa.CONTA), d.contas().stream().map(ContaCf::id).toList());
            assertEquals(List.of(), d.avisos());
            assertFalse(d.toString().contains(CloudflareFalsa.TOKEN_CERTO));
        }
        assertTrue(cofre.tokenCloudflare(servidorId).isEmpty(), "descobrir não salva nada");
        assertTrue(cofre.configuracaoCloudflare(servidorId).isEmpty());
        assertEquals(List.of(), cf.escritas());
        assertEquals(new String(colado), "  " + CloudflareFalsa.TOKEN_CERTO + "\n", "o char[] de quem digitou NÃO é zerado aqui: quem o digitou o zera");
    }

    @Test
    void semAListaDeContasUsaAContaDosDominiosComAviso() throws Exception {
        cf.semPermissao(Permissao.CONTAS);
        try (ServicoCloudflare.Descoberta d = servico(false).descobrir(colar(CloudflareFalsa.TOKEN_CERTO))) {
            assertEquals(List.of(CloudflareFalsa.CONTA), d.contas().stream().map(ContaCf::id).toList());
            assertEquals("Conta de Teste", d.contas().get(0).nome());
            assertTrue(d.avisos().get(0).contains("não enxerga a lista de contas"), d.avisos().toString());
        }
    }

    @Test
    void tokenRuimSemPermissaoDeZonaOuSemDominioDaMensagemQueEnsina() throws Exception {
        ErroCloudflare curto = assertThrows(ErroCloudflare.class, () -> servico(false).descobrir("curto".toCharArray()));
        assertEquals(Tipo.TOKEN_INVALIDO, curto.tipo());
        assertTrue(curto.mensagem().contains("não parece um token"), curto.mensagem());
        assertEquals(List.of(), cf.pedidos, "um token com forma errada nem sai do computador");

        cf.tokenAceito = "OutroTokenQualquerDeTeste0123456789abcd";
        ErroCloudflare errado = assertThrows(ErroCloudflare.class, () -> servico(false).descobrir(colar(CloudflareFalsa.TOKEN_CERTO)));
        assertEquals(Tipo.TOKEN_INVALIDO, errado.tipo());
        cf.tokenAceito = CloudflareFalsa.TOKEN_CERTO;

        cf.semPermissao(Permissao.ZONAS);
        ErroCloudflare semZona = assertThrows(ErroCloudflare.class, () -> servico(false).descobrir(colar(CloudflareFalsa.TOKEN_CERTO)));
        assertEquals(Tipo.SEM_PERMISSAO, semZona.tipo());
        assertTrue(semZona.mensagem().contains("Zona > Zona > Ler"), semZona.mensagem());

        cf.permissoes = java.util.Set.of(Permissao.values());
        cf.zonas.clear();
        ErroCloudflare vazio = assertThrows(ErroCloudflare.class, () -> servico(false).descobrir(colar(CloudflareFalsa.TOKEN_CERTO)));
        assertEquals(Tipo.NAO_ENCONTRADO, vazio.tipo());
        assertTrue(vazio.mensagem().contains("Zona específica"), vazio.mensagem());
        for (ErroCloudflare e : List.of(curto, errado, semZona, vazio)) {
            assertFalse(e.mensagem().contains(CloudflareFalsa.TOKEN_CERTO));
        }
        assertTrue(cofre.tokenCloudflare(servidorId).isEmpty(), "nenhuma falha salva coisa alguma");
    }

    private ConfiguracaoCloudflare escolhaPelaApi() {
        return new ConfiguracaoCloudflare(CloudflareFalsa.CONTA, "Conta de Teste", CloudflareFalsa.ZONA, "example.com", CloudflareFalsa.TUNEL_API, "projetos-na-nuvem",
            ConfiguracaoCloudflare.Tipo.API, "lab", null, null, null);
    }

    @Test
    void salvarGuardaOTokenECofreEDesconectarOZeraEOApaga() throws Exception {
        ServicoCloudflare s = servico(false);
        assertFalse(s.conectado());
        try (ServicoCloudflare.Descoberta d = s.descobrir(colar(CloudflareFalsa.TOKEN_CERTO))) {
            List<TunelCf> tuneis = s.tuneisDaConta(d, CloudflareFalsa.CONTA);
            assertEquals(2, tuneis.size());
            s.salvar(d, escolhaPelaApi());
        }
        assertTrue(s.conectado());
        assertEquals(escolhaPelaApi(), s.escolha().orElseThrow());
        TokenCloudflare doCofre = cofre.tokenCloudflare(servidorId).orElseThrow();
        assertEquals("Bearer " + CloudflareFalsa.TOKEN_CERTO, doCofre.cabecalhoAuthorization(), "o cofre ficou com uma cópia, e fechar a descoberta não a zerou");
        assertEquals(CloudflareFalsa.TOKEN_CERTO.length(), doCofre.tamanho());
        assertTrue(s.conferirToken().status().equals("active"));
        assertFalse(doCofre.estaZerado(), "conferir o token não o zera");
        s.desconectar();
        assertFalse(s.conectado());
        assertTrue(doCofre.estaZerado(), "desconectar zera o token na memória");
        assertTrue(cofre.tokenCloudflare(servidorId).isEmpty());
        assertTrue(s.escolha().isEmpty());
    }

    @Test
    void umPublicarOuRemoverPorVezNoServidorETravaSoSoltaNoFimDaExecucao() throws Exception {
        ServicoCloudflare s = servico(false);
        java.util.concurrent.CountDownLatch dentro = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch soltar = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CompletableFuture<String> primeiro = java.util.concurrent.CompletableFuture.supplyAsync(() -> s.comTravaDeEscrita(() -> {
            dentro.countDown();
            try {
                soltar.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "primeiro terminou";
        }, () -> "ocupado"));
        dentro.await();
        assertEquals("ocupado", s.comTravaDeEscrita(() -> "NÃO deveria rodar", () -> "ocupado"), "enquanto a primeira não termina, a segunda é recusada sem rodar");
        soltar.countDown();
        assertEquals("primeiro terminou", primeiro.get());
        assertEquals("rodou", s.comTravaDeEscrita(() -> "rodou", () -> "ocupado"), "terminou: a trava soltou");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> s.comTravaDeEscrita(() -> {
            throw new IllegalStateException("falhou");
        }, () -> "ocupado"));
        assertEquals("rodou de novo", s.comTravaDeEscrita(() -> "rodou de novo", () -> "ocupado"), "mesmo uma execução que lançou solta a trava");
    }

    @Test
    void aDescobertaFechadaZeraOTokenQueGuardava() throws Exception {
        ServicoCloudflare.Descoberta d = servico(false).descobrir(colar(CloudflareFalsa.TOKEN_CERTO));
        assertFalse(d.token().estaZerado());
        d.close();
        assertTrue(d.token().estaZerado());
        assertThrows(RuntimeException.class, () -> servico(false).salvar(d, escolhaPelaApi()), "salvar com a descoberta já fechada não grava um token zerado");
        assertTrue(cofre.tokenCloudflare(servidorId).isEmpty());
    }

    @Test
    void oTokenSoSaiDoCofreComoCopiaQueOClienteZera() throws Exception {
        ServicoCloudflare s = servico(false);
        try (ServicoCloudflare.Descoberta d = s.descobrir(colar(CloudflareFalsa.TOKEN_CERTO))) {
            s.salvar(d, escolhaPelaApi());
        }
        TokenCloudflare doCofre = cofre.tokenCloudflare(servidorId).orElseThrow();
        List<EnderecoDoApp> l = s.enderecos(null);
        assertEquals(List.of(), l);
        s.conferirToken();
        assertFalse(doCofre.estaZerado(), "cada cliente usa e zera a cópia dele; o do cofre continua");
        try (RemovedorDeEndereco r = s.removedor()) {
            assertFalse(r.toString().contains(CloudflareFalsa.TOKEN_CERTO));
        }
        assertFalse(doCofre.estaZerado());
        assertTrue(cf.pedidos.stream().allMatch(p -> p.autorizacao().equals("Bearer " + CloudflareFalsa.TOKEN_CERTO)));
    }

    @Test
    void semConectarNadaFuncionaEADizEmPortugues() throws Exception {
        ServicoCloudflare s = servico(false);
        for (org.junit.jupiter.api.function.Executable e : List.<org.junit.jupiter.api.function.Executable>of(() -> s.enderecos(null), s::removedor, s::conferirToken, () -> s.publicador(x -> { }))) {
            FalhaDoProjeto f = assertThrows(FalhaDoProjeto.class, e);
            assertTrue(f.mensagem().contains("não está conectada"), f.mensagem());
        }
        assertEquals(List.of(), cf.pedidos);
    }

    @Test
    void noComputadorLocalOAppNaoMontaTunelEDizPorque() throws Exception {
        ServicoCloudflare s = servico(true);
        assertTrue(s.ehLocal());
        FalhaDoProjeto f = assertThrows(FalhaDoProjeto.class, () -> s.publicador(x -> { }));
        assertTrue(f.mensagem().contains("pede um servidor com o cloudflared"), f.mensagem());
        assertTrue(assertThrows(FalhaDoProjeto.class, s::removedor).mensagem().contains("não tem túnel configurado"));
        assertEquals(List.of(), cf.pedidos);
    }

    @Test
    void conferirOServidorLeOArquivoOTunelEOContainerSoPorLeitura() throws Exception {
        Files.createDirectories(tmp.resolve("lab"));
        lab = new LabSimulado(tmp.resolve("lab"));
        lab.escrever(LabSimulado.CONFIG, PublicadorPorArquivoTest.CONFIG);
        ServicoCloudflare s = servico(false);
        TunelPorArquivo.Conferencia ok = s.conferirServidor(LabSimulado.CONFIG, "cloudflared");
        assertTrue(ok.ok(), ok.problemas().toString());
        assertTrue(ok.arquivoExiste());
        assertEquals(LabSimulado.UUID, ok.tunelDoArquivo());
        assertTrue(ok.container().rodando());
        assertEquals("/etc/cloudflared/config.yml", ok.container().caminhoDoConfigNoContainer().orElseThrow());
        assertEquals(List.of("docker inspect cloudflared"), lab.comandos, "só leitura");
        assertEquals(PublicadorPorArquivoTest.CONFIG, lab.ler(LabSimulado.CONFIG));

        lab.containerExiste = false;
        TunelPorArquivo.Conferencia semContainer = s.conferirServidor(LabSimulado.CONFIG, "cloudflared");
        assertFalse(semContainer.ok());
        assertTrue(semContainer.problemas().get(0).contains("não existe no servidor"), semContainer.problemas().toString());
        lab.containerExiste = true;
        TunelPorArquivo.Conferencia semArquivo = s.conferirServidor("/srv/lab/infra/outro/config.yml", "cloudflared");
        assertFalse(semArquivo.ok());
        assertTrue(semArquivo.problemas().get(0).contains("não existe no servidor"), semArquivo.problemas().toString());
        lab.escrever(LabSimulado.CONFIG, "tunnel: " + LabSimulado.UUID + "\nurl: http://localhost:80\n");
        assertTrue(s.conferirServidor(LabSimulado.CONFIG, "cloudflared").problemas().stream().anyMatch(p -> p.contains("formato antigo, com url:") || p.contains("não tem a seção")));
        lab.escrever(LabSimulado.CONFIG, "ingress:\n  - hostname: a.example.com\n    service: http://a:1\n");
        TunelPorArquivo.Conferencia semFinal = s.conferirServidor(LabSimulado.CONFIG, "cloudflared");
        assertTrue(semFinal.ok());
        assertTrue(semFinal.avisos().get(0).contains("não tem a regra final"), semFinal.avisos().toString());
        lab.semPermissaoNoDocker = true;
        assertTrue(s.conferirServidor(LabSimulado.CONFIG, "cloudflared").problemas().stream().anyMatch(p -> p.contains("não alcança o Docker")));
    }
}
