package br.com.labdesktop.cloudflare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.cofre.ConfiguracaoCloudflare;
import br.com.labdesktop.cofre.TokenCloudflare;
import br.com.labdesktop.projetos.FalhaDoProjeto;
import br.com.labdesktop.teste.CloudflareFalsa;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Publicar na internet com o túnel por ARQUIVO (o do usuário), de ponta a ponta contra o servidor SSH embutido (SFTP e um Docker de mentira) e a Cloudflare falsa: o que a prévia
 * lê, o que o clique escreve, na ordem, e cada falha com a reversão (a regra, o DNS, o compose, o reinício). Nada toca a conta nem o lab de verdade.
 */
class PublicadorPorArquivoTest {

    static final String COMPOSE = "name: \"demo\"\n"
        + "services:\n"
        + "  app:\n"
        + "    image: node:22-alpine\n"
        + "    ports:\n"
        + "      - \"127.0.0.1:3000:3000\"\n"
        + "    networks:\n"
        + "      - default\n"
        + "    restart: unless-stopped\n";

    static final String CONFIG = "tunnel: " + LabSimulado.UUID + "\n"
        + "credentials-file: /etc/cloudflared/" + LabSimulado.UUID + ".json\n"
        + "\n"
        + "ingress:\n"
        + "  - hostname: meu-painel.example.com\n"
        + "    service: http://app:3000\n"
        + "  - service: http_status:404\n";

    private static final Clock RELOGIO = Clock.fixed(Instant.parse("2026-10-28T15:30:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tmp;

    LabSimulado lab;
    CloudflareFalsa cf;
    TokenCloudflare token;
    ClienteCloudflare api;
    ConfiguracaoCloudflare cfg;
    final List<String> recriados = new CopyOnWriteArrayList<>();
    volatile Exception falhaAoRecriar;
    final AtomicInteger testes = new AtomicInteger();
    volatile TestadorDeUrl.Resultado resultadoDoTeste = new TestadorDeUrl.Resultado(TestadorDeUrl.Estado.NO_AR, 200, "No ar: o endereço respondeu (código 200).");
    final List<String> eventos = new CopyOnWriteArrayList<>();

    @BeforeEach
    void montar() throws Exception {
        lab = new LabSimulado(tmp);
        cf = new CloudflareFalsa();
        token = TokenCloudflare.deChars(CloudflareFalsa.TOKEN_CERTO.toCharArray());
        cfg = lab.configPorArquivo();
        lab.escrever(LabSimulado.PROJETOS + "/demo/docker-compose.yml", COMPOSE);
        lab.escrever(LabSimulado.CONFIG, CONFIG);
    }

    @AfterEach
    void desmontar() throws Exception {
        if (api != null) {
            api.close();
        }
        token.zerar();
        cf.close();
        lab.close();
    }

    PublicadorNaInternet publicador() {
        api = new ClienteCloudflare(cf.base(), token, ClienteCloudflare.Opcoes.rapida()); // um cliente novo por publicador: fechar o publicador zera a cópia do token dele
        TunelPorArquivo tunel = new TunelPorArquivo(lab.ambiente(), cfg, false, RELOGIO, d -> { });
        return new PublicadorNaInternet(lab.ambiente(), api, cfg, tunel, projeto -> {
            recriados.add(projeto);
            if (falhaAoRecriar != null) {
                throw falhaAoRecriar;
            }
        }, (host, c) -> {
            testes.incrementAndGet();
            return resultadoDoTeste;
        }, RELOGIO);
    }

    static PublicadorNaInternet.Pedido pedido() {
        return new PublicadorNaInternet.Pedido("demo", "app", "demo-app", 3000, "lab-demo", true);
    }

    ObservadorDaEtapa observador() {
        return (e, s, f) -> eventos.add(e + ":" + s);
    }

    String compose() throws Exception {
        return lab.ler(LabSimulado.PROJETOS + "/demo/docker-compose.yml");
    }

    String config() throws Exception {
        return lab.ler(LabSimulado.CONFIG);
    }

    Object arquivoDe(String caminho) throws Exception {
        return Files.readAttributes(lab.arquivo(caminho), BasicFileAttributes.class).fileKey();
    }

    // ---------------------------------------------------------------------------------------------
    // a prévia só lê
    // ---------------------------------------------------------------------------------------------

    @Test
    void aPreviaMostraTudoENaoEscreveNada() throws Exception {
        try (PublicadorNaInternet p = publicador()) {
            PublicadorNaInternet.Previa previa = p.preparar(pedido());
            assertTrue(previa.podePublicar(), previa.bloqueio());
            assertEquals("lab-demo.example.com", previa.hostname());
            assertEquals("https://lab-demo.example.com/", previa.url());
            assertEquals("http://demo-app:3000", previa.destinoDaRegra());
            assertEquals(ComposeComEdge.Situacao.EDITAVEL, previa.compose());
            assertTrue(previa.diferencaDoCompose().contains("+     container_name: demo-app"), previa.diferencaDoCompose());
            assertTrue(previa.diferencaDoCompose().contains("+       - edge"), previa.diferencaDoCompose());
            assertTrue(previa.diferencaDoCompose().contains("+ networks:"), previa.diferencaDoCompose());
            assertTrue(previa.diferencaDoTunel().contains("+   - hostname: lab-demo.example.com"), previa.diferencaDoTunel());
            assertTrue(previa.diferencaDoTunel().contains("+     service: http://demo-app:3000"));
            assertTrue(previa.diferencaDoTunel().contains("# lab-desktop: lab-demo.example.com (projeto demo, 2026-10-28)"), "a marca do app aparece na prévia");
            assertTrue(previa.passos().stream().anyMatch(s -> s.startsWith("docker-compose.yml do projeto demo")), previa.passos().toString());
            assertTrue(previa.passos().stream().anyMatch(s -> s.contains("reiniciar SÓ o container cloudflared")), previa.passos().toString());
            assertTrue(previa.passos().stream().anyMatch(s -> s.contains("CNAME lab-demo.example.com -> " + LabSimulado.UUID + ".cfargotunnel.com")));
            assertTrue(previa.passos().stream().anyMatch(s -> s.startsWith("Projeto: recriar")));
            assertTrue(previa.avisos().stream().anyMatch(s -> s.contains("O cloudflared será reiniciado")), previa.avisos().toString());
        }
        assertEquals(COMPOSE, compose());
        assertEquals(CONFIG, config());
        assertEquals(List.of(), lab.nomes(LabSimulado.PROJETOS + "/demo").stream().filter(n -> n.endsWith(".bak")).toList(), "nenhuma cópia: nada foi escrito");
        assertEquals(List.of(), cf.escritas(), "nada foi escrito na Cloudflare");
        assertEquals(0, lab.reinicios.get());
        assertEquals(List.of("docker inspect cloudflared"), lab.comandos, "o único comando da prévia é uma LEITURA");
        assertEquals(List.of(), recriados);
        assertEquals(0, testes.get());
    }

    // ---------------------------------------------------------------------------------------------
    // o caminho feliz
    // ---------------------------------------------------------------------------------------------

    @Test
    void publicarFazTudoNaOrdemCertaComCopiasDatadasENoMesmoArquivoDoConfig() throws Exception {
        Object inode = arquivoDe(LabSimulado.CONFIG);
        PublicadorNaInternet.Resultado r;
        try (PublicadorNaInternet p = publicador()) {
            r = p.executar(p.preparar(pedido()), observador(), Cancelamento.NUNCA);
        }
        assertEquals(PublicadorNaInternet.Desfecho.PUBLICADO, r.desfecho(), r.falha());
        assertEquals(TestadorDeUrl.Estado.NO_AR, r.teste().estado());
        assertEquals("https://lab-demo.example.com/", r.url());

        // o compose
        assertTrue(compose().contains("    container_name: demo-app\n"));
        assertTrue(compose().contains("      - default\n      - edge\n"));
        assertTrue(compose().endsWith("\nnetworks:\n  edge:\n    external: true\n"));
        assertEquals(COMPOSE, lab.ler(LabSimulado.PROJETOS + "/demo/docker-compose.yml.20261028-153000.bak"), "a cópia datada é fiel");

        // o config.yml: a regra ANTES da final, com a marca do app, no MESMO arquivo, com cópia datada
        String esperado = "tunnel: " + LabSimulado.UUID + "\ncredentials-file: /etc/cloudflared/" + LabSimulado.UUID + ".json\n\ningress:\n"
            + "  - hostname: meu-painel.example.com\n    service: http://app:3000\n"
            + "  # lab-desktop: lab-demo.example.com (projeto demo, 2026-10-28)\n  - hostname: lab-demo.example.com\n    service: http://demo-app:3000\n"
            + "  - service: http_status:404\n";
        assertEquals(esperado, config());
        assertEquals(inode, arquivoDe(LabSimulado.CONFIG), "o MESMO arquivo: o painel e o cloudflared montam o arquivo, não a pasta");
        assertEquals(CONFIG, lab.ler(LabSimulado.CONFIG + ".20261028-153000.bak"));
        assertEquals(2, r.copias().size());

        // o cloudflared: conferiu, reiniciou UMA vez e carregou o arquivo novo
        assertEquals(1, lab.reinicios.get());
        assertEquals(esperado, lab.configNoUltimoReinicio, "o reinício leu o arquivo já com a regra");
        List<String> c = lab.comandos;
        int compor = c.indexOf(c.stream().filter(x -> x.startsWith("docker compose")).findFirst().orElseThrow());
        int validar = c.indexOf(c.stream().filter(x -> x.contains("ingress validate")).findFirst().orElseThrow());
        int reiniciar = c.indexOf("docker restart cloudflared");
        assertTrue(compor < validar && validar < reiniciar, "compose conferido, regras conferidas, SÓ ENTÃO o reinício: " + c);
        assertEquals("docker exec cloudflared cloudflared tunnel --config " + LabSimulado.CONFIG_NO_CONTAINER + " ingress validate", c.get(validar));
        assertTrue(c.stream().noneMatch(x -> x.contains("--overwrite-dns") || x.contains("route dns") || x.contains("tunnel create")), "nada de cloudflared login nem route dns: o DNS é pela API");

        // o DNS: CNAME para o UUID, com proxy e a marca
        CloudflareFalsa.Registro dns = cf.registro("lab-demo.example.com");
        assertEquals("CNAME", dns.tipo);
        assertEquals(LabSimulado.UUID + ".cfargotunnel.com", dns.conteudo);
        assertTrue(dns.proxied);
        assertEquals("lab-desktop: demo", dns.comentario);

        // o projeto foi recriado e o endereço testado
        assertEquals(List.of("demo"), recriados);
        assertEquals(1, testes.get());
        assertEquals(List.of("COMPOSE:ANDANDO", "COMPOSE:FEITO", "REGRA:ANDANDO", "REGRA:FEITO", "CONFERIR_E_REINICIAR:ANDANDO", "CONFERIR_E_REINICIAR:ANDANDO", "CONFERIR_E_REINICIAR:FEITO",
            "DNS:ANDANDO", "DNS:FEITO", "PROJETO:ANDANDO", "PROJETO:FEITO", "TESTE:ANDANDO", "TESTE:FEITO"), eventos);
    }

    @Test
    void oTokenDaCloudflareNuncaChegaAoServidorNemApareceNosResultados() throws Exception {
        PublicadorNaInternet.Resultado r;
        try (PublicadorNaInternet p = publicador()) {
            PublicadorNaInternet.Previa previa = p.preparar(pedido());
            r = p.executar(previa, observador(), Cancelamento.NUNCA);
            assertFalse(previa.toString().contains(CloudflareFalsa.TOKEN_CERTO));
        }
        for (String comando : lab.comandos) {
            assertFalse(comando.contains(CloudflareFalsa.TOKEN_CERTO), "nenhum comando no servidor leva o token: " + comando);
        }
        for (String entrada : lab.ssh.entradas) {
            assertFalse(entrada.contains(CloudflareFalsa.TOKEN_CERTO), "nem o stdin");
        }
        assertFalse(r.toString().contains(CloudflareFalsa.TOKEN_CERTO));
        assertFalse(String.join("\n", eventos).contains(CloudflareFalsa.TOKEN_CERTO));
        assertFalse(config().contains(CloudflareFalsa.TOKEN_CERTO));
        assertFalse(compose().contains(CloudflareFalsa.TOKEN_CERTO));
        assertTrue(cf.pedidos.stream().allMatch(x -> x.autorizacao().equals("Bearer " + CloudflareFalsa.TOKEN_CERTO)), "o token só viaja no cabeçalho, para a Cloudflare");
        assertTrue(cf.pedidos.stream().noneMatch(x -> x.corpo().contains(CloudflareFalsa.TOKEN_CERTO) || x.caminho().contains(CloudflareFalsa.TOKEN_CERTO) || x.query().contains(CloudflareFalsa.TOKEN_CERTO)));
    }

    @Test
    void composeJaProntoNaoEEscritoEPodeNaoRecriarOProjeto() throws Exception {
        String pronto = COMPOSE.replace("  app:\n", "  app:\n    container_name: demo-app\n").replace("      - default\n", "      - default\n      - edge\n") + "\nnetworks:\n  edge:\n    external: true\n";
        lab.escrever(LabSimulado.PROJETOS + "/demo/docker-compose.yml", pronto);
        PublicadorNaInternet.Pedido semRecriar = new PublicadorNaInternet.Pedido("demo", "app", "qualquer-nome", 3000, "lab-demo", false);
        try (PublicadorNaInternet p = publicador()) {
            PublicadorNaInternet.Previa previa = p.preparar(semRecriar);
            assertEquals(ComposeComEdge.Situacao.PRONTO, previa.compose());
            assertEquals("http://demo-app:3000", previa.destinoDaRegra(), "vale o container_name que o serviço já tinha");
            assertTrue(previa.avisos().stream().anyMatch(a -> a.contains("já tem container_name")), previa.avisos().toString());
            PublicadorNaInternet.Resultado r = p.executar(previa, observador(), Cancelamento.NUNCA);
            assertEquals(PublicadorNaInternet.Desfecho.PUBLICADO, r.desfecho(), r.falha());
        }
        assertEquals(pronto, compose());
        assertEquals(List.of(), lab.nomes(LabSimulado.PROJETOS + "/demo").stream().filter(n -> n.endsWith(".bak")).toList(), "não escreveu, não guardou cópia");
        assertEquals(List.of(), recriados);
        assertTrue(eventos.contains("PROJETO:PULADO"));
        assertTrue(eventos.contains("COMPOSE:PULADO"));
    }

    // ---------------------------------------------------------------------------------------------
    // o que impede de publicar (a prévia diz; o clique não faz nada)
    // ---------------------------------------------------------------------------------------------

    @Test
    void composeDoDonoQueOAppNaoEscreveBloqueiaEMostraOBlocoParaCopiar() throws Exception {
        String crlf = COMPOSE.replace("\n", "\r\n");
        lab.escrever(LabSimulado.PROJETOS + "/demo/docker-compose.yml", crlf);
        try (PublicadorNaInternet p = publicador()) {
            PublicadorNaInternet.Previa previa = p.preparar(pedido());
            assertFalse(previa.podePublicar());
            assertEquals(ComposeComEdge.Situacao.MANUAL, previa.compose());
            assertTrue(previa.bloqueio().contains("copie o bloco"), previa.bloqueio());
            assertTrue(previa.blocoParaCopiar().contains("container_name: demo-app"), previa.blocoParaCopiar());
            assertTrue(previa.blocoParaCopiar().contains("external: true"));
            PublicadorNaInternet.Resultado r = p.executar(previa, observador(), Cancelamento.NUNCA);
            assertEquals(PublicadorNaInternet.Desfecho.FALHOU, r.desfecho());
        }
        assertEquals(crlf, compose());
        assertEquals(CONFIG, config());
        assertEquals(List.of("docker inspect cloudflared"), lab.comandos, "o clique em um pedido bloqueado não roda nada");
        assertEquals(List.of(), cf.escritas());
    }

    @Test
    void composeEmLatin1NaoEDecodificadoESemOServicoOuSemArquivoBloqueia() throws Exception {
        Files.write(lab.arquivo(LabSimulado.PROJETOS + "/demo/docker-compose.yml"), "services:\n  app:\n    image: x # ação\n".getBytes(Charset.forName("ISO-8859-1")));
        try (PublicadorNaInternet p = publicador()) {
            assertTrue(p.preparar(pedido()).bloqueio().contains("não está em UTF-8"));
            lab.escrever(LabSimulado.PROJETOS + "/demo/docker-compose.yml", COMPOSE);
            assertTrue(p.preparar(new PublicadorNaInternet.Pedido("demo", "outro", "demo-outro", 80, "lab-demo", false)).bloqueio().contains("Não achei o serviço \"outro\""));
            Files.delete(lab.arquivo(LabSimulado.PROJETOS + "/demo/docker-compose.yml"));
            assertTrue(p.preparar(pedido()).bloqueio().contains("não tem docker-compose.yml"));
        }
    }

    @Test
    void enderecoQueJaExisteNoDnsDoDonoBloqueiaEODoProprioAppEReaproveitado() throws Exception {
        cf.semear("lab-demo.example.com", "CNAME", "outro-tunel.cfargotunnel.com", "do usuário");
        try (PublicadorNaInternet p = publicador()) {
            PublicadorNaInternet.Previa previa = p.preparar(pedido());
            assertFalse(previa.podePublicar());
            assertTrue(previa.bloqueio().contains("não foi criado por este app"), previa.bloqueio());
        }
        cf.registros.clear();
        cf.semear("lab-demo.example.com", "CNAME", LabSimulado.UUID + ".cfargotunnel.com", "lab-desktop: demo");
        try (PublicadorNaInternet p = publicador()) {
            PublicadorNaInternet.Previa previa = p.preparar(pedido());
            assertTrue(previa.podePublicar(), previa.bloqueio());
            assertTrue(previa.dnsJaEraDoApp());
            PublicadorNaInternet.Resultado r = p.executar(previa, observador(), Cancelamento.NUNCA);
            assertEquals(PublicadorNaInternet.Desfecho.PUBLICADO, r.desfecho(), r.falha());
        }
        assertEquals(1, cf.registros.size(), "reaproveitado, não duplicado");
        assertEquals(List.of(), cf.escritas().stream().filter(w -> w.metodo().equals("POST")).toList());
    }

    @Test
    void regraQueJaExisteNoConfigBloqueiaEOContainerOuOConfigAusenteTambem() throws Exception {
        lab.escrever(LabSimulado.CONFIG, CONFIG.replace("meu-painel.example.com", "lab-demo.example.com"));
        try (PublicadorNaInternet p = publicador()) {
            PublicadorNaInternet.Previa previa = p.preparar(pedido());
            assertFalse(previa.podePublicar());
            assertTrue(previa.bloqueio().contains("não foi criada por este app"), previa.bloqueio());
            lab.escrever(LabSimulado.CONFIG, CONFIG);
            lab.containerExiste = false;
            assertTrue(p.preparar(pedido()).bloqueio().contains("não existe no servidor"));
            lab.containerExiste = true;
            lab.rodando = false;
            assertTrue(p.preparar(pedido()).bloqueio().contains("não está rodando"));
            lab.rodando = true;
            lab.semPermissaoNoDocker = true;
            assertTrue(p.preparar(pedido()).bloqueio().contains("não alcança o Docker"));
            lab.semPermissaoNoDocker = false;
            Files.delete(lab.arquivo(LabSimulado.CONFIG));
            assertTrue(p.preparar(pedido()).bloqueio().contains("não existe em " + LabSimulado.CONFIG));
        }
    }

    @Test
    void configDeOutroTunelBloqueia() throws Exception {
        lab.escrever(LabSimulado.CONFIG, CONFIG.replace(LabSimulado.UUID, "99999999-9999-9999-9999-999999999999"));
        try (PublicadorNaInternet p = publicador()) {
            PublicadorNaInternet.Previa previa = p.preparar(pedido());
            assertFalse(previa.podePublicar());
            assertTrue(previa.bloqueio().contains("é do túnel 99999999-9999-9999-9999-999999999999"), previa.bloqueio());
        }
    }

    @Test
    void configSemARegraFinalOuVazioEPreviaComAviso() throws Exception {
        lab.escrever(LabSimulado.CONFIG, "tunnel: " + LabSimulado.UUID + "\ningress:\n  - hostname: a.example.com\n    service: http://a:1\n");
        try (PublicadorNaInternet p = publicador()) {
            PublicadorNaInternet.Previa previa = p.preparar(pedido());
            assertTrue(previa.podePublicar(), previa.bloqueio());
            assertTrue(previa.avisos().stream().anyMatch(a -> a.contains("não tinha a regra final")), previa.avisos().toString());
            PublicadorNaInternet.Resultado r = p.executar(previa, observador(), Cancelamento.NUNCA);
            assertEquals(PublicadorNaInternet.Desfecho.PUBLICADO, r.desfecho(), r.falha());
            assertTrue(config().endsWith("    service: http://demo-app:3000\n  - service: http_status:404\n"), config());
            assertTrue(ConfigYmlDoCloudflared.analisar(config()).temRegraFinal());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // falhou no meio: desfaz SÓ o que a execução fez
    // ---------------------------------------------------------------------------------------------

    private void conferirTudoComoAntes(boolean oCloudflaredFoiReiniciado) throws Exception {
        assertEquals(COMPOSE, compose(), "o compose voltou como estava");
        assertEquals(CONFIG, config(), "o config.yml voltou como estava");
        assertEquals(List.of(), cf.registros.stream().filter(r -> r.nome.equals("lab-demo.example.com")).toList(), "nenhum DNS ficou");
        assertEquals(oCloudflaredFoiReiniciado ? 2 : 0, lab.reinicios.get(), "o cloudflared só é reiniciado de novo se tinha sido reiniciado antes");
        assertTrue(lab.rodando, "e termina de pé");
    }

    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "depende de permissões POSIX ou do shell do Unix; no Windows valem ProtecaoAcl e as provas do Windows")
    @Test
    void asCopiasDatadasTemOsModosDosOriginaisEACopiaDoComposeFicaForaDoGit() throws Exception {
        Path compose = lab.arquivo(LabSimulado.PROJETOS + "/demo/docker-compose.yml");
        Path config = lab.arquivo(LabSimulado.CONFIG);
        Files.setPosixFilePermissions(compose, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        Files.setPosixFilePermissions(config, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
        lab.escrever(LabSimulado.PROJETOS + "/demo/.gitignore", "node_modules/\ndist/\ncoverage/\n.env\n.env.*\n!.env.example\n*.log\n.DS_Store\nbackups/\n.pgdata/\n.minio/\n");
        PublicadorNaInternet.Resultado r;
        try (PublicadorNaInternet p = publicador()) {
            r = p.executar(p.preparar(pedido()), observador(), Cancelamento.NUNCA);
        }
        assertEquals(PublicadorNaInternet.Desfecho.PUBLICADO, r.desfecho(), r.falha());
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(compose)), "o compose do usuário continua 0600");
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(
            lab.arquivo(LabSimulado.PROJETOS + "/demo/docker-compose.yml.20261028-153000.bak"))), "a cópia nasce com o mesmo modo");
        assertEquals("rw-r-----", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(config)), "o config.yml é gravado no lugar e conserva o modo");
        assertEquals("rw-r-----", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(
            lab.arquivo(LabSimulado.CONFIG + ".20261028-153000.bak"))), "a cópia do config.yml não é mais aberta que ele");
        assertTrue(lab.ler(LabSimulado.PROJETOS + "/demo/.gitignore").contains("docker-compose.yml.*.bak"), "a cópia entra no .gitignore do esqueleto do app");
    }

    @Test
    void seOGitIgnoreEDoDonoOAppNaoOReescreveEAvisa() throws Exception {
        lab.escrever(LabSimulado.PROJETOS + "/demo/.gitignore", "node_modules\n");
        PublicadorNaInternet.Resultado r;
        try (PublicadorNaInternet p = publicador()) {
            r = p.executar(p.preparar(pedido()), observador(), Cancelamento.NUNCA);
        }
        assertEquals(PublicadorNaInternet.Desfecho.PUBLICADO, r.desfecho(), r.falha());
        assertEquals("node_modules\n", lab.ler(LabSimulado.PROJETOS + "/demo/.gitignore"));
        assertTrue(r.avisos().stream().anyMatch(a -> a.contains(".gitignore") && a.contains("docker-compose.yml.*.bak")), r.avisos().toString());
    }

    @Test
    void dnsDeOutroProjetoDoAppOuDeQualquerOutroTipoNoMesmoNomeBloqueiaAPrevia() throws Exception {
        cf.semear("lab-demo.example.com", "CNAME", LabSimulado.UUID + ".cfargotunnel.com", "lab-desktop: outro-projeto");
        try (PublicadorNaInternet p = publicador()) {
            PublicadorNaInternet.Previa previa = p.preparar(pedido());
            assertFalse(previa.podePublicar());
            assertTrue(previa.bloqueio().contains("do projeto outro-projeto") && previa.bloqueio().contains("e não de demo"), previa.bloqueio());
        }
        cf.registros.clear();
        cf.semear("lab-demo.example.com", "TXT", "v=spf1 -all", "do usuário");
        try (PublicadorNaInternet p = publicador()) {
            PublicadorNaInternet.Previa previa = p.preparar(pedido());
            assertFalse(previa.podePublicar(), "um CNAME não convive com um TXT no mesmo nome");
            assertTrue(previa.bloqueio().contains("TXT"), previa.bloqueio());
        }
        cf.registros.clear();
        cf.semear("lab-demo.example.com", "CNAME", LabSimulado.UUID + ".cfargotunnel.com", "lab-desktop: demo");
        try (PublicadorNaInternet p = publicador()) {
            assertTrue(p.preparar(pedido()).dnsJaEraDoApp(), "do MESMO projeto, o registro do app é reaproveitado");
        }
    }

    @Test
    void seARecriacaoDoProjetoFalhaODesfazerDevolveOComposeEDizQueOsContainersJaForamRecriados() throws Exception {
        falhaAoRecriar = new IllegalStateException("o docker compose up falhou");
        PublicadorNaInternet.Resultado r;
        try (PublicadorNaInternet p = publicador()) {
            r = p.executar(p.preparar(pedido()), observador(), Cancelamento.NUNCA);
        }
        assertEquals(PublicadorNaInternet.Desfecho.FALHOU, r.desfecho());
        assertEquals(Etapa.PROJETO, r.etapaDaFalha());
        assertEquals(COMPOSE, compose());
        assertTrue(r.naoConsegui().stream().anyMatch(t -> t.contains("já tinham sido recriados") && t.contains("Iniciar")), r.naoConsegui().toString());
    }

    @Test
    void seOutraPessoaMexeNoConfigDepoisDaGravacaoODesfazerNaoSobrescreveEDiz() throws Exception {
        lab.validarSempreFalha = true;
        boolean[] mexeu = {false};
        ObservadorDaEtapa mexedor = (e, st, f) -> {
            if (e == Etapa.CONFERIR_E_REINICIAR && st == ObservadorDaEtapa.Estado.ANDANDO && !mexeu[0]) {
                mexeu[0] = true;
                try {
                    lab.escrever(LabSimulado.CONFIG, lab.ler(LabSimulado.CONFIG) + "# mexida do usuário agora\n");
                } catch (java.io.IOException e2) {
                    throw new java.io.UncheckedIOException(e2);
                }
            }
        };
        PublicadorNaInternet.Resultado r;
        try (PublicadorNaInternet p = publicador()) {
            r = p.executar(p.preparar(pedido()), mexedor, Cancelamento.NUNCA);
        }
        assertEquals(PublicadorNaInternet.Desfecho.FALHOU, r.desfecho());
        assertTrue(config().endsWith("# mexida do usuário agora\n"), "a mudança do usuário ficou: o app não a apagou");
        assertTrue(r.falha().contains("NÃO o sobrescrevi"), r.falha());
        assertEquals(0, lab.reinicios.get(), "a conferência falhou antes de reiniciar: nada foi reiniciado");
    }

    @Test
    void seOCloudflaredRecusaAsRegrasODevolveOArquivoENadaEReiniciado() throws Exception {
        Object inode = arquivoDe(LabSimulado.CONFIG);
        lab.validarSempreFalha = true;
        PublicadorNaInternet.Resultado r;
        try (PublicadorNaInternet p = publicador()) {
            r = p.executar(p.preparar(pedido()), observador(), Cancelamento.NUNCA);
        }
        assertEquals(PublicadorNaInternet.Desfecho.FALHOU, r.desfecho());
        assertTrue(r.falha().contains("O cloudflared não aceitou as regras"), r.falha());
        assertTrue(r.falha().contains("The last ingress rule must match all URLs"), "o motivo real do cloudflared, como texto: " + r.falha());
        assertTrue(r.falha().contains("nada foi reiniciado"), r.falha());
        assertEquals(Etapa.REGRA, r.etapaDaFalha());
        conferirTudoComoAntes(false);
        assertEquals(inode, arquivoDe(LabSimulado.CONFIG), "devolvido NO LUGAR (o mesmo arquivo)");
        assertEquals(List.of("Desfiz o docker-compose.yml."), r.desfeito());
        assertEquals(List.of(), r.naoConsegui());
        assertEquals(List.of(), recriados);
        assertEquals(0, testes.get());
        assertTrue(eventos.contains("CONFERIR_E_REINICIAR:FALHOU") && eventos.contains("COMPOSE:DESFEITO"), eventos.toString());
        assertTrue(r.copias().stream().anyMatch(c -> c.endsWith(".bak")), "as cópias ficam, para o usuário ver o que aconteceu");
    }

    @Test
    void seOCloudflaredNaoVoltaDepoisDoReinicioODevolveOArquivoEReiniciaDeNovo() throws Exception {
        lab.oPrimeiroReinicioNaoVolta = true;
        PublicadorNaInternet.Resultado r;
        try (PublicadorNaInternet p = publicador()) {
            r = p.executar(p.preparar(pedido()), observador(), Cancelamento.NUNCA);
        }
        assertEquals(PublicadorNaInternet.Desfecho.FALHOU, r.desfecho());
        assertTrue(r.falha().contains("não voltou a rodar"), r.falha());
        assertTrue(r.falha().contains("reiniciado de novo"), r.falha());
        conferirTudoComoAntes(true);
        assertEquals(CONFIG, lab.configNoUltimoReinicio, "o segundo reinício leu o arquivo de antes");
        assertEquals(List.of(), r.naoConsegui());
    }

    @Test
    void seOCloudflaredSobeECaiEmSeguidaNaoValeComoVoltouORestauraOArquivoEReiniciaDeNovo() throws Exception {
        lab.sobeECaiEmSeguida = true;
        PublicadorNaInternet.Resultado r = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(15), () -> {
            try (PublicadorNaInternet p = publicador()) {
                return p.executar(p.preparar(pedido()), observador(), Cancelamento.NUNCA);
            }
        });
        assertEquals(PublicadorNaInternet.Desfecho.FALHOU, r.desfecho());
        assertTrue(r.falha().contains("não ficou de pé"), r.falha());
        assertTrue(r.falha().contains("reiniciado de novo"), r.falha());
        assertEquals(Etapa.REGRA, r.etapaDaFalha(), "a falha é da etapa que reinicia o cloudflared");
        assertEquals(0, testes.get(), "nada de DNS nem de teste do endereço");
        assertEquals(0, cf.registros.size(), "o DNS nem chegou a ser criado");
        assertEquals(2, lab.reinicios.get(), "reiniciou para a regra nova e DE NOVO ao devolver o arquivo");
        assertEquals(CONFIG, config(), "o config.yml voltou como estava");
        assertEquals(CONFIG, lab.configNoUltimoReinicio, "o segundo reinício já leu o arquivo de antes");
        assertEquals(List.of(), r.naoConsegui());
    }

    @Test
    void seODockerReiniciaOCloudflaredSozinhoEmLoopNaoValeComoVoltouORestauraOArquivo() throws Exception {
        lab.emCrashLoop = true;
        PublicadorNaInternet.Resultado r = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(15), () -> {
            try (PublicadorNaInternet p = publicador()) {
                return p.executar(p.preparar(pedido()), observador(), Cancelamento.NUNCA);
            }
        });
        assertEquals(PublicadorNaInternet.Desfecho.FALHOU, r.desfecho());
        assertTrue(r.falha().contains("não ficou de pé"), r.falha());
        assertEquals(2, lab.reinicios.get());
        assertEquals(CONFIG, config());
        assertEquals(0, cf.registros.size());
        assertEquals(0, testes.get());
        // o segundo reinício (o do desfazer) já não está em loop: o app diz que devolveu tudo
        assertEquals(List.of(), r.naoConsegui());
    }

    @Test
    void seODnsFalhaPorqueAlguemCriouOMesmoNomeNoMeioDesfazRegraEComposeSemTocarNoRegistroAlheio() throws Exception {
        try (PublicadorNaInternet p = publicador()) {
            PublicadorNaInternet.Previa previa = p.preparar(pedido());
            assertTrue(previa.podePublicar());
            cf.semear("lab-demo.example.com", "CNAME", "outro.cfargotunnel.com", "do usuário"); // apareceu depois de o usuário ver a prévia
            PublicadorNaInternet.Resultado r = p.executar(previa, observador(), Cancelamento.NUNCA);
            assertEquals(PublicadorNaInternet.Desfecho.FALHOU, r.desfecho());
            assertEquals(Etapa.DNS, r.etapaDaFalha());
            assertTrue(r.falha().contains("Já existe"), r.falha());
            assertEquals(List.of("Desfiz a regra no túnel.", "Desfiz o docker-compose.yml."), r.desfeito(), "de trás para frente");
        }
        assertEquals(COMPOSE, compose());
        assertEquals(CONFIG, config());
        assertEquals(2, lab.reinicios.get());
        assertEquals(1, cf.registros.size());
        assertEquals("do usuário", cf.registro("lab-demo.example.com").comentario, "o registro alheio nunca é apagado");
        assertEquals(List.of(), cf.escritas().stream().filter(w -> w.metodo().equals("DELETE")).toList());
    }

    @Test
    void seAPostagemDoDnsPerdeARespostaMasCriouOAppConfereEContinua() throws Exception {
        cf.executarEPerderAResposta("POST /zones/.*/dns_records", 1);
        PublicadorNaInternet.Resultado r;
        try (PublicadorNaInternet p = publicador()) {
            r = p.executar(p.preparar(pedido()), observador(), Cancelamento.NUNCA);
        }
        assertEquals(PublicadorNaInternet.Desfecho.PUBLICADO, r.desfecho(), r.falha());
        assertEquals(1, cf.registros.size(), "criado uma vez só: o app conferiu em vez de repetir");
        assertEquals(1, cf.escritas().stream().filter(w -> w.metodo().equals("POST")).count());
    }

    @Test
    void seOProjetoNaoRecriaDesfazTudoInclusiveOQueJaEstavaNoAr() throws Exception {
        falhaAoRecriar = new IllegalStateException("compose up falhou: porta 3000 em uso");
        PublicadorNaInternet.Resultado r;
        try (PublicadorNaInternet p = publicador()) {
            r = p.executar(p.preparar(pedido()), observador(), Cancelamento.NUNCA);
        }
        assertEquals(PublicadorNaInternet.Desfecho.FALHOU, r.desfecho());
        assertEquals(Etapa.PROJETO, r.etapaDaFalha());
        assertTrue(r.falha().contains("Não consegui recriar o projeto: compose up falhou"), r.falha());
        assertEquals(List.of("Desfiz o registro de DNS lab-demo.example.com.", "Desfiz a regra no túnel.", "Desfiz o docker-compose.yml."), r.desfeito());
        conferirTudoComoAntes(true);
        assertEquals(1, cf.escritas().stream().filter(w -> w.metodo().equals("DELETE")).count(), "o DNS que ESTA execução criou foi apagado");
    }

    @Test
    void cancelarEntreOsPassosDesfazOQueJaFoiFeito() throws Exception {
        Cancelamento cancelamento = new Cancelamento();
        ObservadorDaEtapa cancelaDepoisDoReinicio = (e, s, f) -> {
            eventos.add(e + ":" + s);
            if (e == Etapa.CONFERIR_E_REINICIAR && s == ObservadorDaEtapa.Estado.FEITO) {
                cancelamento.cancelar();
            }
        };
        PublicadorNaInternet.Resultado r;
        try (PublicadorNaInternet p = publicador()) {
            r = p.executar(p.preparar(pedido()), cancelaDepoisDoReinicio, cancelamento);
        }
        assertEquals(PublicadorNaInternet.Desfecho.CANCELADO, r.desfecho());
        conferirTudoComoAntes(true);
        assertEquals(List.of(), recriados);
        assertEquals(List.of(), cf.escritas(), "cancelou antes do DNS: nada foi escrito na Cloudflare");
    }

    @Test
    void seOConfigMudaDepoisDaPreviaNadaEGravadoENemOQueOutroEscreveESobrescrito() throws Exception {
        try (PublicadorNaInternet p = publicador()) {
            PublicadorNaInternet.Previa previa = p.preparar(pedido());
            String doOutro = CONFIG + "# o usuário mexeu agora\n";
            lab.escrever(LabSimulado.CONFIG, doOutro);
            PublicadorNaInternet.Resultado r = p.executar(previa, observador(), Cancelamento.NUNCA);
            assertEquals(PublicadorNaInternet.Desfecho.FALHOU, r.desfecho());
            assertTrue(r.falha().contains("mudou depois que você viu"), r.falha());
            assertEquals(doOutro, config(), "o que o outro escreveu ficou intacto");
        }
        assertEquals(COMPOSE, compose(), "o compose (que já tinha sido gravado) foi devolvido");
        assertEquals(0, lab.reinicios.get());
        assertEquals(List.of(), cf.escritas());
    }

    @Test
    void seOComposeMudaDepoisDaPreviaNadaEGravado() throws Exception {
        try (PublicadorNaInternet p = publicador()) {
            PublicadorNaInternet.Previa previa = p.preparar(pedido());
            String doOutro = COMPOSE + "# editado\n";
            lab.escrever(LabSimulado.PROJETOS + "/demo/docker-compose.yml", doOutro);
            PublicadorNaInternet.Resultado r = p.executar(previa, observador(), Cancelamento.NUNCA);
            assertEquals(PublicadorNaInternet.Desfecho.FALHOU, r.desfecho());
            assertEquals(Etapa.COMPOSE, r.etapaDaFalha());
            assertEquals(doOutro, compose());
        }
        assertEquals(CONFIG, config());
        assertEquals(List.of(), cf.escritas());
    }

    @Test
    void seODockerRecusaOComposeNovoNadaEGravado() throws Exception {
        lab.escrever(LabSimulado.PROJETOS + "/demo/docker-compose.yml", COMPOSE + "# INVALIDO\n");
        try (PublicadorNaInternet p = publicador()) {
            PublicadorNaInternet.Resultado r = p.executar(p.preparar(pedido()), observador(), Cancelamento.NUNCA);
            assertEquals(PublicadorNaInternet.Desfecho.FALHOU, r.desfecho());
            assertTrue(r.falha().contains("O Docker não aceitou o compose com a rede edge"), r.falha());
            assertTrue(r.falha().contains("undefined network"), r.falha());
        }
        assertEquals(COMPOSE + "# INVALIDO\n", compose());
        assertEquals(CONFIG, config());
        assertEquals(0, lab.reinicios.get());
    }

    @Test
    void testeQueNaoPassaNaoDesfazNadaEDizOMotivo() throws Exception {
        resultadoDoTeste = new TestadorDeUrl.Resultado(TestadorDeUrl.Estado.FORA_DO_AR, 0, "O endereço ainda não existe na internet: o DNS novo pode levar alguns minutos para chegar.");
        PublicadorNaInternet.Resultado r;
        try (PublicadorNaInternet p = publicador()) {
            r = p.executar(p.preparar(pedido()), observador(), Cancelamento.NUNCA);
        }
        assertEquals(PublicadorNaInternet.Desfecho.PUBLICADO, r.desfecho(), "tudo está no lugar");
        assertFalse(r.teste().noAr());
        assertTrue(r.teste().frase().contains("DNS novo pode levar alguns minutos"));
        assertEquals(1, cf.registros.size());
        assertTrue(config().contains("lab-demo.example.com"));
        assertEquals(1, lab.reinicios.get());
        assertTrue(eventos.contains("TESTE:FALHOU"));
        assertNull(r.falha());
        assertEquals(List.of(), r.desfeito());
    }

    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "depende de permissões POSIX ou do shell do Unix; no Windows valem ProtecaoAcl e as provas do Windows")
    @Test
    void semPermissaoParaEscreverNoConfigODizEDevolveTudo() throws Exception {
        Path config = lab.arquivo(LabSimulado.CONFIG);
        java.nio.file.attribute.PosixFilePermission[] so = {java.nio.file.attribute.PosixFilePermission.OWNER_READ};
        Files.setPosixFilePermissions(config, new java.util.HashSet<>(List.of(so)));
        try {
            if (Files.isWritable(config)) {
                return; // rodando como administrador: não há como negar a escrita
            }
            PublicadorNaInternet.Resultado r;
            try (PublicadorNaInternet p = publicador()) {
                r = p.executar(p.preparar(pedido()), observador(), Cancelamento.NUNCA);
            }
            assertEquals(PublicadorNaInternet.Desfecho.FALHOU, r.desfecho());
            assertTrue(r.falha().contains("não pode escrever no config.yml"), r.falha());
            assertEquals(COMPOSE, compose());
            assertEquals(0, lab.reinicios.get());
            assertEquals(List.of(), cf.escritas());
        } finally {
            Files.setPosixFilePermissions(config, new java.util.HashSet<>(List.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)));
        }
    }

    @Test
    void publicarNoComputadorLocalNaoExiste() {
        br.com.labdesktop.projetos.AmbienteDeProjetos local = new br.com.labdesktop.projetos.AmbienteDeProjetos(true, "/x", null, null, c -> c);
        api = new ClienteCloudflare(cf.base(), token, ClienteCloudflare.Opcoes.rapida());
        PublicadorNaInternet p = new PublicadorNaInternet(local, api, cfg, new TunelPelaApi(api, new ConfiguracaoCloudflare("a".repeat(32), "c", "b".repeat(32), "example.com", LabSimulado.UUID, "t",
            ConfiguracaoCloudflare.Tipo.API, "lab", null, null, null)), x -> { }, (h, c) -> null, RELOGIO);
        FalhaDoProjeto f = org.junit.jupiter.api.Assertions.assertThrows(FalhaDoProjeto.class, () -> p.preparar(pedido()));
        assertTrue(f.mensagem().contains("pede um servidor com o cloudflared"), f.mensagem());
    }

    @Test
    void pedidoComNomeRuimNaoChegaARede() {
        List<PublicadorNaInternet.Pedido> ruins = new ArrayList<>(List.of(
            new PublicadorNaInternet.Pedido("demo", "app", "demo-app", 3000, "Lab-Demo", false),
            new PublicadorNaInternet.Pedido("demo", "app", "demo-app", 3000, "a.b", false),
            new PublicadorNaInternet.Pedido("demo", "app", "demo-app", 0, "lab-demo", false),
            new PublicadorNaInternet.Pedido("demo", "app", "Demo App; rm", 3000, "lab-demo", false),
            new PublicadorNaInternet.Pedido("Demo", "app", "demo-app", 3000, "lab-demo", false),
            new PublicadorNaInternet.Pedido("demo", "../x", "demo-app", 3000, "lab-demo", false)));
        try (PublicadorNaInternet p = publicador()) {
            for (PublicadorNaInternet.Pedido ruim : ruins) {
                org.junit.jupiter.api.Assertions.assertThrows(FalhaDoProjeto.class, () -> p.preparar(ruim), ruim.toString());
            }
        }
        assertEquals(List.of(), lab.comandos);
        assertEquals(List.of(), cf.pedidos);
    }
}
