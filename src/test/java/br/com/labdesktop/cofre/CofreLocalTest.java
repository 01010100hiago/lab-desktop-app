package br.com.labdesktop.cofre;

import static br.com.labdesktop.cofre.Fixtures.abrir;
import static br.com.labdesktop.cofre.Fixtures.caminhos;
import static br.com.labdesktop.cofre.Fixtures.criar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.util.CaminhosApp;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** O servidor "Este computador" no cofre: sem SSH, sem chave, um só, e o cofre antigo abre igual. */
class CofreLocalTest {

    @Test
    void servidorLocalNaoTemChaveNemEnderecoDeSshEVoltaIgualDoArquivo(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        ConfigServidor local = ConfigServidor.novoLocal("Este computador", ConfigServidor.PORTA_AGENTE_LOCAL_PADRAO);
        try (CofreDeChaves cofre = criar(c)) {
            cofre.adicionarServidor(local, null);
        }
        try (CofreDeChaves cofre = abrir(c)) {
            ConfigServidor lido = cofre.servidor(local.id()).orElseThrow();
            assertEquals(local, lido);
            assertTrue(lido.ehLocal());
            assertEquals(Transporte.Tipo.LOCAL, lido.transporte().tipo());
            assertEquals(7070, lido.portaAgente());
            assertEquals(null, lido.chaveId());
            assertTrue(lido.pins().isEmpty());
            assertTrue(cofre.chaves().isEmpty(), "nenhuma chave nasce para o servidor local");
            assertThrows(CofreException.class, () -> cofre.keyPairDoServidor(local.id()), "não há chave SSH para entregar ao MINA");
        }
    }

    @Test
    void soExisteUmServidorLocal(@TempDir Path tmp) throws Exception {
        try (CofreDeChaves cofre = criar(caminhos(tmp))) {
            cofre.adicionarServidor(ConfigServidor.novoLocal("Este computador", 7070), null);
            assertThrows(IllegalArgumentException.class, () -> cofre.adicionarServidor(ConfigServidor.novoLocal("Outro", 7071), null));
        }
    }

    @Test
    void localConvivComServidorRemotoERemoverUmNaoMexeNaChaveDoOutro(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        ConfigServidor remoto = Fixtures.popular(c);
        ConfigServidor local = ConfigServidor.novoLocal("Este computador", 7070);
        try (CofreDeChaves cofre = abrir(c)) {
            cofre.adicionarServidor(local, null);
            assertEquals(2, cofre.servidores().size());
            assertFalse(cofre.removerServidor(local.id(), true), "o local não tem chave para apagar");
            assertEquals(1, cofre.chaves().size(), "a chave do remoto continua");
            assertTrue(cofre.servidor(remoto.id()).isPresent());
        }
    }

    @Test
    void cofreAntigoSoComServidorRemotoAbreIgualENaoTemRegistroDeAgente(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Fixtures.popular(c); // cofre de antes: só servidor remoto
        try (CofreDeChaves cofre = abrir(c)) {
            ConfigServidor remoto = cofre.servidores().get(0);
            assertFalse(remoto.ehLocal());
            assertEquals(Transporte.DIRETO, remoto.transporte());
            assertFalse(cofre.agenteInstalado(remoto.id()).isPresent(), "cofre antigo não lembra container nenhum");
        }
    }

    @Test
    void comoFalarComOAgenteInstaladoFicaNoCofreESaiJuntoComOServidor(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        ConfigServidor remoto = Fixtures.popular(c);
        try (CofreDeChaves cofre = abrir(c)) {
            cofre.gravarAgenteInstalado(remoto.id(), new AgenteInstalado("painel-teste", true));
        }
        try (CofreDeChaves cofre = abrir(c)) {
            AgenteInstalado a = cofre.agenteInstalado(remoto.id()).orElseThrow();
            assertEquals("painel-teste", a.container());
            assertTrue(a.comSudo());
            assertEquals("sudo -n docker", a.docker());
            cofre.gravarAgenteInstalado(remoto.id(), new AgenteInstalado("painel-lab", false));
            assertEquals("docker", cofre.agenteInstalado(remoto.id()).orElseThrow().docker());
            cofre.gravarAgenteInstalado(remoto.id(), null);
            assertFalse(cofre.agenteInstalado(remoto.id()).isPresent());
        }
        assertThrows(IllegalArgumentException.class, () -> new AgenteInstalado("Painel Ruim; rm", false));
    }

    @Test
    void ocofreComServidorLocalGravaVersao2ParaUmAppAntigoDizerClaroQueNaoSuportaESemLocalContinuaVersao1(@TempDir Path tmp) throws Exception {
        ConteudoEmMemoria semLocal = ConteudoEmMemoria.vazio();
        assertEquals(1, ConteudoCofre.de(semLocal).versao);
        ConfigServidor local = ConfigServidor.novoLocal("Este computador", 7070);
        ConteudoEmMemoria comLocal = semLocal.comServidor(new ConteudoEmMemoria.ServidorGuardado(local, null));
        ConteudoCofre json = ConteudoCofre.de(comLocal);
        assertEquals(2, json.versao, "um app mais antigo vê 'versão não suportada', não 'chave inexistente'");
        assertEquals(local, json.paraMemoria().servidores.get(0).config, "e este app lê a versão 2");
        ConteudoCofre futuro = ConteudoCofre.de(comLocal);
        futuro.versao = 3;
        assertThrows(CofreCorrompidoException.class, futuro::paraMemoria);
    }
}
