package br.com.labdesktop.servidor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.cofre.InfoChave;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.servidor.AssistenteDeServidor.Dados;
import br.com.labdesktop.servidor.AssistenteDeServidor.ErroDoAssistente;
import br.com.labdesktop.servidor.AssistenteDeServidor.Etapa;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.ErroProvisionamento;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.SondagemDeHost;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.util.CaminhosApp;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O roteiro do "Adicionar servidor" inteiro, com o COFRE de verdade, o {@code SshServer} embutido e o agente falso:
 * gerar a chave, sondar o host, gravar servidor e pin, conectar, registrar o token e ler os limites. É o que a
 * janela chama passo a passo, sem a janela.
 */
class AssistenteDeServidorTest {

    private static final char[] SENHA = "uma-senha-mestra-de-teste".toCharArray();
    private static final String DISPOSITIVO = "mac-de-teste";

    @TempDir
    Path tmp;

    private ServidorSshDeTeste ssh;
    private AgenteDoLab agente;
    private CofreDeChaves cofre;
    private GerenciadorServidores gerenciador;
    private AssistenteDeServidor assistente;

    @BeforeEach
    void montar() throws Exception {
        ssh = ServidorSshDeTeste.ed25519();
        agente = new AgenteDoLab();
        ssh.comandoRemoto(agente.comandosPorSsh());
        CaminhosApp caminhos = CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
        cofre = CofreDeChaves.criar(caminhos, SENHA.clone(), ParametrosKdf.MINIMO);
        gerenciador = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida());
        assistente = new AssistenteDeServidor(cofre, gerenciador, ConfigSsh.rapida());
    }

    @AfterEach
    void desmontar() throws Exception {
        gerenciador.fechar().join();
        cofre.close();
        agente.close();
        ssh.close();
    }

    private Dados dados() {
        return new Dados("lab", "127.0.0.1", ssh.porta(), "labuser", agente.porta());
    }

    /** Gera a chave, sonda e cadastra; devolve o servidor (ainda parado). */
    private ServidorLab cadastrar() throws Exception {
        InfoChave chave = assistente.gerarChave("lab");
        SondagemDeHost.Resultado sonda = assistente.sondar("127.0.0.1", ssh.porta());
        return assistente.cadastrar(dados(), chave.id(), sonda.pin());
    }

    private void instalarAChavePublica(ServidorLab lab) throws Exception {
        ssh.aceitar(cofre.keyPairDoServidor(lab.id()).getPublic()); // "colou" a linha no authorized_keys
    }

    @Test
    void caminhoFelizGeraSondaCadastraConectaRegistraOTokenELeOsLimites() throws Exception {
        InfoChave chave = assistente.gerarChave("lab");
        assertEquals(List.of(chave.id()), cofre.chaves().stream().map(InfoChave::id).toList(), "a chave já está no cofre");
        assertTrue(chave.publica().startsWith("ssh-ed25519 "));
        assertTrue(chave.publica().contains(" lab-desktop@"), chave.publica());
        assertTrue(chave.comandoDeInstalacao(7000).contains("permitopen=\"127.0.0.1:7000\""));

        SondagemDeHost.Resultado sonda = assistente.sondar("127.0.0.1", ssh.porta());
        assertEquals(ssh.pin().impressao(), sonda.impressao());
        assertEquals(0, ssh.pedidosDeAutenticacao.get(), "a sondagem não chegou a autenticar");

        ServidorLab lab = assistente.cadastrar(dados(), chave.id(), sonda.pin());
        ConfigServidor gravado = cofre.servidor(lab.id()).orElseThrow();
        assertEquals(List.of(sonda.pin()), gravado.pins(), "servidor e pin entram no cofre juntos");
        assertEquals(Transporte.DIRETO, gravado.transporte());
        assertEquals(EstadoConexao.DESCONECTADO, lab.estado().conexao(), "cadastrar não conecta");
        assertEquals(List.of(lab), gerenciador.servidores());

        instalarAChavePublica(lab);
        List<Etapa> etapas = new ArrayList<>();
        assistente.concluir(lab, DISPOSITIVO, etapas::add);

        assertEquals(List.of(Etapa.CONECTANDO, Etapa.REGISTRANDO_TOKEN, Etapa.TESTANDO_AGENTE, Etapa.LENDO_LIMITES, Etapa.PRONTO), etapas);
        assertEquals(EstadoConexao.CONECTADO, lab.estado().conexao());
        assertNotNull(lab.estado().limites());
        assertEquals(DISPOSITIVO, lab.estado().versao().cliente());
        assertEquals(DISPOSITIVO, cofre.servidor(lab.id()).orElseThrow().nomeDoDispositivo());
        assertEquals(1, agente.tokens.size());
        assertTrue(cofre.tokenDoServidor(lab.id()).isPresent());
        assertEquals(1, ssh.chavesOferecidas.stream().distinct().count(), "só a chave do cofre foi oferecida");
    }

    @Test
    void chaveNaoInstaladaFalhaRapidoComMensagemEDepoisDeInstaladaOMesmoServidorConclui() throws Exception {
        ServidorLab lab = cadastrar();

        ErroDoAssistente erro = assertThrows(ErroDoAssistente.class, () -> assistente.concluir(lab, DISPOSITIVO, e -> { }));
        assertEquals(ErroDoAssistente.Motivo.CHAVE_RECUSADA, erro.motivo());
        assertTrue(erro.getMessage().contains("instalar a chave pública"), erro.getMessage());
        assertTrue(agente.tokens.isEmpty(), "sem sessão SSH nada foi registrado");

        instalarAChavePublica(lab);
        assistente.concluir(lab, DISPOSITIVO, e -> { });
        assertEquals(EstadoConexao.CONECTADO, lab.estado().conexao());
        assertEquals(1, agente.tokens.size());
    }

    /**
     * O painel do servidor ainda é o de antes do app (falta o deploy do painel atualizado): o assistente manda publicar o painel em
     * vez de mostrar a pilha crua do node, e não tenta registrar nada.
     */
    @Test
    void agenteAnteriorAoAppMandaPublicarOPainelEAntesDeRegistrarQualquerCoisa() throws Exception {
        agente.agenteAntigo = true;
        ServidorLab lab = cadastrar();
        instalarAChavePublica(lab);

        ErroDoAssistente erro = assertThrows(ErroDoAssistente.class, () -> assistente.concluir(lab, DISPOSITIVO, e -> { }));

        assertEquals(ErroDoAssistente.Motivo.AGENTE_DESATUALIZADO, erro.motivo());
        assertTrue(erro.getMessage().contains("script de publicação"), erro.getMessage());
        assertTrue(erro.getMessage().contains("Publique o painel"), erro.getMessage());
        assertFalse(erro.getMessage().contains("Cannot find module") || erro.getMessage().contains("node:internal"),
            "nada da pilha do node chega ao usuário: " + erro.getMessage());
        assertTrue(agente.comandosCli.isEmpty(), "o registro do acesso nem foi tentado: " + agente.comandosCli);
        assertTrue(lab.estado().semToken(), "nada foi gravado no cofre");
        assertEquals(EstadoConexao.SSH_OK_SEM_AGENTE, lab.estado().conexao(), "o SSH segue de pé para tentar de novo");

        agente.agenteAntigo = false; // o usuário publicou o painel com o script de publicação
        assistente.concluir(lab, DISPOSITIVO, e -> { });
        assertEquals(EstadoConexao.CONECTADO, lab.estado().conexao());
        assertEquals(1, agente.tokens.size());
    }

    @Test
    void concluirDeNovoComTokenJaRegistradoNaoRegistraOutro() throws Exception {
        ServidorLab lab = cadastrar();
        instalarAChavePublica(lab);
        assistente.concluir(lab, DISPOSITIVO, e -> { });
        List<Etapa> segunda = new ArrayList<>();

        assistente.concluir(lab, DISPOSITIVO, segunda::add);

        assertFalse(segunda.contains(Etapa.REGISTRANDO_TOKEN), "o token deste Mac já existe: " + segunda);
        assertEquals(1, agente.tokens.size());
    }

    @Test
    void nomeDeDispositivoJaUsadoNoAgenteDaTokenConflitoEOReprovisionarResolve() throws Exception {
        agente.tokens.put("a".repeat(64), DISPOSITIVO); // outro Mac (ou o cofre antigo) já usa o nome
        ServidorLab lab = cadastrar();
        instalarAChavePublica(lab);

        ErroProvisionamento erro = assertThrows(ErroProvisionamento.class,
            () -> assistente.concluir(lab, DISPOSITIVO, e -> { }));
        assertEquals(ErroProvisionamento.TOKEN_CONFLITO, erro.codigo());
        assertTrue(lab.estado().semToken(), "nada foi gravado no cofre");

        lab.reprovisionarToken(DISPOSITIVO); // a tela só chama isto DEPOIS de o usuário confirmar
        assistente.concluir(lab, DISPOSITIVO, e -> { });
        assertEquals(1, agente.tokens.size());
        assertFalse(agente.tokens.containsKey("a".repeat(64)));
    }

    @Test
    void confiarNoHostDeUmServidorSemPinGravaOPinEConecta() throws Exception {
        InfoChave chave = assistente.gerarChave("lab");
        ConfigServidor semPin = new ConfigServidor(ConfigServidor.novoId(), "lab", "127.0.0.1", ssh.porta(), "labuser",
            chave.id(), agente.porta(), List.of(), Transporte.DIRETO, null, Estado2fa.DESLIGADO);
        cofre.adicionarServidor(semPin, null);
        ServidorLab lab = gerenciador.adicionar(semPin.id());
        instalarAChavePublica(lab);
        lab.iniciar();
        Espera.ate(Duration.ofSeconds(20), () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.ERRO_CHAVE_HOST);
        assertEquals(0, ssh.pedidosDeAutenticacao.get(), "sem pin nada é autenticado");

        SondagemDeHost.Resultado sonda = assistente.sondar("127.0.0.1", ssh.porta());
        assistente.confiarNoHost(lab, sonda.pin());

        Espera.ate(Duration.ofSeconds(20), () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.CONECTADO);
        assertEquals(List.of(sonda.pin()), cofre.servidor(lab.id()).orElseThrow().pins());
    }

    @Test
    void importarOMesmoArquivoDeNovoReaproveitaAChaveEmVezDeCriarUmaCopia() throws Exception {
        java.io.ByteArrayOutputStream saida = new java.io.ByteArrayOutputStream();
        org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(
            org.apache.sshd.common.config.keys.KeyUtils.generateKeyPair("ssh-ed25519", 256), "usuario@notebook", null, saida);
        Path arquivo = tmp.resolve("id_ed25519");
        java.nio.file.Files.write(arquivo, saida.toByteArray());

        InfoChave primeira = assistente.importarChave(arquivo, null, "para o lab");
        InfoChave segunda = assistente.importarChave(arquivo, null, "para o outro servidor");

        assertEquals(InfoChave.Origem.IMPORTADA, primeira.origem());
        assertEquals(primeira.id(), segunda.id(), "é a mesma chave: não nasce uma segunda entrada no cofre");
        assertEquals(1, cofre.chaves().size());
        assertEquals("para o lab", cofre.chaves().get(0).nome(), "a que já existia fica como estava");
        assertEquals(InfoChave.Origem.GERADA_PELO_APP, assistente.gerarChave("outra").origem(), "a criada pelo app é do app");
    }

    @Test
    void osDadosDoFormularioSaoValidadosComMensagemEmPortugues() {
        assertThrows(IllegalArgumentException.class, () -> new Dados("", "h", 22, "u", 7000).validar());
        IllegalArgumentException host = assertThrows(IllegalArgumentException.class,
            () -> new Dados("lab", "-oProxyCommand=x", 22, "u", 7000).validar());
        assertTrue(host.getMessage().contains("endereço"), host.getMessage());
        IllegalArgumentException porta = assertThrows(IllegalArgumentException.class, () -> new Dados("lab", "h", 0, "u", 7000).validar());
        assertTrue(porta.getMessage().contains("porta"));
    }
}
