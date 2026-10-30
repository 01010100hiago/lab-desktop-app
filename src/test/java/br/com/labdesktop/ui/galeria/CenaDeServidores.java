package br.com.labdesktop.ui.galeria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.ChaveSsh;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.cofre.GeradorDeChaves;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.cofre.TokenAgente;
import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.local.ServicosLocais;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.FerramentasFalsasDeTunel;
import br.com.labdesktop.teste.LancadorDeFerramentaFalsa;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.transporte.RegistroDeTuneis;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.MolduraDeJanela;
import br.com.labdesktop.ui.NavegadorGravado;
import br.com.labdesktop.ui.PainelPrincipal;
import br.com.labdesktop.ui.SemTransbordo;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.telas.TelaServidores;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias.Tema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Três servidores de exemplo no cofre (um de verdade, no sentido de teste: o SSH embutido e o agente de mentira por trás de todos), cada um num estado diferente na tela
 * "Servidores": {@code servidor-demo} conectado direto, {@code nuvem} por AWS SSM (selo "Sem porta aberta") e {@code vps-1} marcado "Protegido". A ferramenta do túnel é a falsa
 * do repositório (nada de AWS de verdade). Nomes, usuários e endereços inventados.
 */
final class CenaDeServidores implements AutoCloseable {

    static final Duration PRAZO = Duration.ofSeconds(40);

    final ServidorSshDeTeste ssh;
    final AgenteDoLab agente;
    final CofreDeChaves cofre;
    final GerenciadorServidores gerenciador;
    final PainelPrincipal painel;
    final ServidorLab conectado;
    final ServidorLab semPortaAberta;
    final ServidorLab protegido;
    private final RegistroDeTuneis registro = new RegistroDeTuneis(false);
    private final List<java.security.PublicKey> chaves = new ArrayList<>();
    private final AvisosGravados avisos = new AvisosGravados();
    private final DialogosGravados dialogos = new DialogosGravados();

    CenaDeServidores(Path tmp) throws Exception {
        Visual.tema(Tema.CLARO);
        ssh = ServidorSshDeTeste.ed25519();
        agente = new AgenteDoLab();
        agente.perfil = "monitor"; // o agente que o app instala: o servidor não vira "protegido" sozinho
        ssh.comandoRemoto(agente.comandosPorSsh());
        cofre = CofreDeChaves.criar(CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs")), "uma-senha-mestra-de-teste".toCharArray(), ParametrosKdf.MINIMO);
        ConfigServidor a = gravar(CenaDoServidor.NOME, Transporte.DIRETO, false);
        ConfigServidor b = gravar("nuvem", Transporte.ssm("i-0123456789abcdef0", "us-east-1", "meu-perfil", 22), false);
        ConfigServidor c = gravar("vps-1", Transporte.DIRETO, true);
        gerenciador = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida(), ServicosLocais.padrao(),
            FerramentasFalsasDeTunel.abridor(new LancadorDeFerramentaFalsa(ssh.porta(), Map.of()), registro));
        conectado = gerenciador.adicionar(a.id());
        semPortaAberta = gerenciador.adicionar(b.id());
        protegido = gerenciador.adicionar(c.id());
        gerenciador.iniciarTodos();
        for (ServidorLab l : List.of(conectado, semPortaAberta, protegido)) {
            Espera.ate(PRAZO, () -> l.estado().nome() + ": " + l.estado().conexao() + " " + l.estado().falha(), () -> l.estado().conexao() == EstadoConexao.CONECTADO);
        }
        painel = Visual.naEdt(() -> new PainelPrincipal(gerenciador, null, avisos, dialogos, new NavegadorGravado(avisos)));
    }

    private ConfigServidor gravar(String nome, Transporte transporte, boolean protegido) throws Exception {
        ChaveSsh chave = GeradorDeChaves.gerar("k-" + nome, "mac-de-teste");
        cofre.adicionarChave(chave);
        chaves.add(chave.paraKeyPair().getPublic());
        ssh.aceitar(chaves.toArray(new java.security.PublicKey[0]));
        TokenAgente token = TokenAgente.gerar();
        agente.tokens.put(token.hashSha256Hex(), "mac-de-teste");
        ConfigServidor cfg = new ConfigServidor(ConfigServidor.novoId(), nome, "127.0.0.1", ssh.porta(), "labuser", chave.id(), agente.porta(), List.of(ssh.pin()), transporte,
            "mac-de-teste", Estado2fa.DESLIGADO, protegido, false);
        cofre.adicionarServidor(cfg, token);
        return cfg;
    }

    /** Abre a tela "Servidores" e espera os três cartões mostrarem o estado de cada um. */
    void abrirServidores() {
        Visual.naEdt(() -> painel.barra().clicarEmGerenciar());
        for (ServidorLab l : List.of(conectado, semPortaAberta, protegido)) {
            Espera.ate(PRAZO, "cartão de " + l.estado().nome(), () -> Visual.naEdt(() -> painel.navegacao().telaDeServidores().cartao(l.id())) != null);
        }
        TelaServidores.CartaoDeServidor c2 = cartao(semPortaAberta);
        TelaServidores.CartaoDeServidor c3 = cartao(protegido);
        Espera.ate(PRAZO, () -> "selos: " + CenaDoServidor.textosDe(painel).replace('\n', '|'),
            () -> Visual.naEdt(() -> c2.seloDoModo().isVisible() && c3.mostraOSeloProtegido()) && CenaDoServidor.textosDe(painel).chars().filter(ch -> ch == '·').count() >= 0
                && !CenaDoServidor.textosDe(painel).contains("Carregando"));
    }

    TelaServidores.CartaoDeServidor cartao(ServidorLab lab) {
        return Visual.naEdt(() -> painel.navegacao().telaDeServidores().cartao(lab.id()));
    }

    /** Confere o que a imagem tem de mostrar: os três estados, sem dado do computador de teste, sem transbordo. */
    void conferir() {
        String t = CenaDoServidor.textosDe(painel);
        assertTrue(t.contains(CenaDoServidor.NOME) && t.contains("nuvem") && t.contains("vps-1"), t);
        assertEquals("Conectado", Visual.naEdt(() -> cartao(conectado).textoDoEstado()));
        assertFalse(Visual.naEdt(() -> cartao(conectado).seloDoModo().isVisible() || cartao(conectado).mostraOSeloProtegido()), "o primeiro cartão é o conectado comum");
        assertEquals("SEM PORTA ABERTA: SSM", Visual.naEdt(() -> cartao(semPortaAberta).seloDoModo().texto()));
        assertEquals("PROTEGIDO", Visual.naEdt(() -> cartao(protegido).seloProtegido().texto()));
        for (ServidorLab l : List.of(conectado, semPortaAberta, protegido)) {
            assertEquals("Conectado", Visual.naEdt(() -> cartao(l).textoDoEstado()), l.estado().nome());
        }
        CenaDoServidor.confereDadosInventados(t);
        Visual.naEdt(() -> painel.setSize(1440, 860));
        List<String> achados = Visual.naEdt(() -> SemTransbordo.achados(painel));
        assertTrue(achados.isEmpty(), "a tela transborda: " + achados);
    }

    void fotografar(String nome) {
        conferir();
        if (CenaDoServidor.deveGravar(nome)) {
            Visual.galeriaNosDoisTemas(painel, 1440, 860, nome, "Lab Desktop", MolduraDeJanela.Tipo.JANELA);
        }
    }

    @Override
    public void close() throws java.io.IOException {
        Visual.naEdt(painel::encerrar);
        gerenciador.fechar().join();
        registro.encerrarTodos();
        cofre.close();
        agente.close();
        ssh.close();
        Visual.tema(Tema.CLARO);
    }
}
