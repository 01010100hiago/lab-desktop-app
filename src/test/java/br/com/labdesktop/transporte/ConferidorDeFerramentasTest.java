package br.com.labdesktop.transporte;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.plataforma.SistemaOperacional;
import br.com.labdesktop.teste.LancadorFalso;
import br.com.labdesktop.teste.PortasFalsas;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** "Conferir as ferramentas neste computador": onde estão, a versão, e o passo de instalação do sistema em que o app roda; nada além de --version. */
class ConferidorDeFerramentasTest {

    private static LocalizadorDeFerramenta soAws() {
        return f -> f == FerramentaExterna.AWS ? Optional.of("/opt/homebrew/bin/aws") : Optional.empty();
    }

    @Test
    void achadaMostraOCaminhoEAVersaoENaoAchadaMostraOPassoDoMac() throws Exception {
        LancadorFalso lancador = new LancadorFalso(new PortasFalsas(), l -> {
            l.processo.escrever("aws-cli/2.17.0 Python/3.12 Darwin/25 exe/arm64\n");
            l.cair(0);
        });
        RelatorioDeFerramentas r = new ConferidorDeFerramentas(soAws(), lancador, SistemaOperacional.MAC).conferirTodas();
        assertEquals(3, r.itens().size());
        var aws = r.de(FerramentaExterna.AWS).orElseThrow();
        assertTrue(aws.achada());
        assertEquals("encontrada em /opt/homebrew/bin/aws (aws-cli/2.17.0 Python/3.12 Darwin/25 exe/arm64)", aws.situacao());
        assertTrue(aws.passoDeInstalar().isEmpty());
        var plugin = r.de(FerramentaExterna.PLUGIN_SSM).orElseThrow();
        assertFalse(plugin.achada());
        assertEquals("não encontrada", plugin.situacao());
        assertEquals("brew install --cask session-manager-plugin", plugin.textoParaCopiar());
        assertEquals("brew install cloudflared", r.de(FerramentaExterna.CLOUDFLARED).orElseThrow().textoParaCopiar());
    }

    @Test
    void soExecutaDashDashVersionDoQueFoiAchado() throws Exception {
        LancadorFalso lancador = new LancadorFalso(new PortasFalsas(), l -> l.cair(0));
        new ConferidorDeFerramentas(f -> Optional.of("/x/" + f.nomeBase()), lancador, SistemaOperacional.LINUX).conferirTodas();
        assertEquals(List.of(List.of("/x/aws", "--version"), List.of("/x/session-manager-plugin", "--version"), List.of("/x/cloudflared", "--version")),
            lancador.lancamentos.stream().map(l -> l.argumentos).toList());
        assertTrue(lancador.lancamentos.stream().allMatch(l -> l.ambiente.isEmpty()));
        // o que não foi achado nem é lançado
        LancadorFalso nada = new LancadorFalso(new PortasFalsas(), l -> l.cair(0));
        new ConferidorDeFerramentas(f -> Optional.empty(), nada, SistemaOperacional.LINUX).conferirTodas();
        assertTrue(nada.lancamentos.isEmpty());
    }

    @Test
    void versaoQueDemoraDemaisEEncerradaESomeDoTexto() throws Exception {
        LancadorFalso lancador = new LancadorFalso(new PortasFalsas(), l -> l.processo.escrever("travado\n")); // nunca sai
        long inicio = System.nanoTime();
        var item = new ConferidorDeFerramentas(soAws(), lancador, SistemaOperacional.MAC, Duration.ofMillis(150)).conferir(FerramentaExterna.AWS);
        assertTrue(Duration.ofNanos(System.nanoTime() - inicio).toMillis() < 2_000);
        assertTrue(item.achada());
        assertEquals("encontrada em /opt/homebrew/bin/aws", item.situacao());
        assertEquals(0, lancador.vivos(), "o processo travado foi encerrado");
    }

    @Test
    void programaQueNaoAbreAindaContaComoAchado() throws Exception {
        LancadorFalso lancador = new LancadorFalso(new PortasFalsas(), l -> { });
        lancador.falharAoLancar(new java.io.IOException("Permission denied"));
        var item = new ConferidorDeFerramentas(soAws(), lancador, SistemaOperacional.MAC).conferir(FerramentaExterna.AWS);
        assertEquals("encontrada em /opt/homebrew/bin/aws", item.situacao());
    }

    @Test
    void aVersaoPassaPeloRedatorEFicaCurtaEEmTextoSemControle() throws Exception {
        String token = "lab_" + "Q".repeat(43);
        LancadorFalso lancador = new LancadorFalso(new PortasFalsas(), l -> {
            l.processo.escrever("<html>" + token + "\u001b[0m " + "x".repeat(500) + "\n");
            l.cair(0);
        });
        var item = new ConferidorDeFerramentas(soAws(), lancador, SistemaOperacional.MAC).conferir(FerramentaExterna.AWS);
        assertFalse(item.versao().contains(token));
        assertTrue(item.versao().length() <= 80);
        assertFalse(item.versao().chars().anyMatch(Character::isISOControl));
        assertFalse(item.versao().startsWith("<html"));
    }

    @Test
    void osPassosDeInstalacaoDeCadaSistemaSaoOsPedidosETextoPuro() {
        assertEquals(List.of("brew install awscli"), GuiaDeInstalacao.passos(FerramentaExterna.AWS, SistemaOperacional.MAC));
        assertEquals(List.of("brew install --cask session-manager-plugin"), GuiaDeInstalacao.passos(FerramentaExterna.PLUGIN_SSM, SistemaOperacional.MAC));
        assertEquals(List.of("brew install cloudflared"), GuiaDeInstalacao.passos(FerramentaExterna.CLOUDFLARED, SistemaOperacional.MAC));
        assertEquals(List.of("winget install Amazon.AWSCLI"), GuiaDeInstalacao.passos(FerramentaExterna.AWS, SistemaOperacional.WINDOWS));
        assertEquals(List.of("winget install Cloudflare.cloudflared"), GuiaDeInstalacao.passos(FerramentaExterna.CLOUDFLARED, SistemaOperacional.WINDOWS));
        String plugin = GuiaDeInstalacao.textoDosPassos(FerramentaExterna.PLUGIN_SSM, SistemaOperacional.WINDOWS);
        assertTrue(plugin.contains("Session Manager plugin") && plugin.contains("instalador oficial da AWS"), plugin);
        assertFalse(plugin.contains("http"), "sem inventar endereço");
        for (FerramentaExterna f : FerramentaExterna.values()) {
            String linux = GuiaDeInstalacao.textoDosPassos(f, SistemaOperacional.LINUX);
            assertTrue(linux.contains("pacote") || linux.contains("instalador"), linux);
            assertFalse(linux.contains("http"), linux);
            assertFalse(linux.startsWith("brew") || linux.contains("winget"), "nada do Mac nem do Windows no Linux: " + linux);
        }
        for (SistemaOperacional so : SistemaOperacional.values()) {
            for (FerramentaExterna f : FerramentaExterna.values()) {
                assertFalse(GuiaDeInstalacao.passos(f, so).isEmpty());
            }
        }
    }

    @Test
    void aMensagemDeAusenciaDizQueOAppNaoInstalaENomeiaOSistema() {
        FalhaDoTunel f = GuiaDeInstalacao.ausente(FerramentaExterna.CLOUDFLARED, SistemaOperacional.WINDOWS);
        assertTrue(f.mensagem().contains("Windows"));
        assertTrue(f.mensagem().contains("O app não instala nada"));
        assertTrue(f.mensagem().contains("winget install Cloudflare.cloudflared"));
        assertEquals("winget install Cloudflare.cloudflared", f.textoCopiavel());
        assertEquals(Map.of(), Map.of());
    }
}
