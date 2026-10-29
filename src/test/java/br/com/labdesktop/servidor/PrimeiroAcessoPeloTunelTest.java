package br.com.labdesktop.servidor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.acesso.ComandosDoAcesso;
import br.com.labdesktop.acesso.InstaladorDeChave;
import br.com.labdesktop.acesso.LimiteDeTentativas;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.cofre.InfoChave;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.cofre.Pin;
import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.local.ServicosLocais;
import br.com.labdesktop.servidor.AssistenteDeServidor.Dados;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.Falha;
import br.com.labdesktop.ssh.FalhaDeEntradaPorSenha;
import br.com.labdesktop.ssh.SondagemDeHost;
import br.com.labdesktop.ssh.SondagemException;
import br.com.labdesktop.teste.FerramentasFalsasDeTunel;
import br.com.labdesktop.teste.LancadorDeFerramentaFalsa;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.transporte.RegistroDeTuneis;
import br.com.labdesktop.util.CaminhosApp;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O primeiro acesso e a edição de um servidor SEM PORTA ABERTA, com o cofre de verdade (arquivo temporário), o SSH embutido do repo e um túnel externo de mentira (um processo
 * Java do teste, rodado pelo lançador de produção). Prova que a sondagem do host, a entrada com senha, a prova da chave, a conexão e o conserto passam pelo túnel, que cada túnel é fechado
 * ao fim do passo e que editar o servidor por outro modo mantém a confiança no servidor. Sem {@code sh}: o servidor SSH de teste responde ao comando de instalar a chave.
 */
class PrimeiroAcessoPeloTunelTest {

    private static final char[] SENHA_MESTRA = "uma-senha-mestra-de-teste".toCharArray();
    private static final String USUARIO = "ubuntu";
    private static final String SENHA_DO_SERVIDOR = "senha-do-servidor-Zk4!";
    private static final Transporte SSM = Transporte.ssm("i-0123456789abcdef0", "us-east-1", "meu-perfil", 22);
    private static final Transporte CLOUD = Transporte.cloudflared("ssh.example.com");
    private static final Duration PRAZO = Duration.ofSeconds(30);

    @TempDir
    Path tmp;

    private ServidorSshDeTeste ssh;
    private CofreDeChaves cofre;
    private GerenciadorServidores gerenciador;
    private AssistenteDeServidor assistente;
    private LancadorDeFerramentaFalsa lancador;
    private final RegistroDeTuneis registro = new RegistroDeTuneis(false);
    private final List<String> comandos = new ArrayList<>();

    @BeforeEach
    void montar() throws Exception {
        ssh = ServidorSshDeTeste.ed25519();
        ssh.aceitarSenha(USUARIO, SENHA_DO_SERVIDOR);
        ssh.comandoRemoto((linha, in, out) -> {
            comandos.add(linha);
            if (linha.equals(ComandosDoAcesso.instalarChave())) {
                out.append(ComandosDoAcesso.MARCADOR_DE_INICIO).append('\n').append(ComandosDoAcesso.SAIDA_ADICIONADA).append('\n').append(ComandosDoAcesso.MARCADOR_DE_FIM).append('\n');
            }
            if (linha.equals(br.com.labdesktop.ssh.ProvaPorChave.COMANDO_DA_PROVA)) {
                out.append("lab-desktop-prova\n"); // a prova da chave: o servidor "roda" o echo
            }
            return 0;
        });
        lancador = new LancadorDeFerramentaFalsa(ssh.porta(), Map.of());
        CaminhosApp caminhos = CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
        cofre = CofreDeChaves.criar(caminhos, SENHA_MESTRA.clone(), ParametrosKdf.MINIMO);
        gerenciador = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida(), ServicosLocais.padrao(),
            FerramentasFalsasDeTunel.abridor(lancador, registro));
        assistente = new AssistenteDeServidor(cofre, gerenciador, ConfigSsh.rapida(),
            new LimiteDeTentativas(AssistenteDeServidor.MAXIMO_DE_SENHAS_RECUSADAS, AssistenteDeServidor.JANELA_DAS_SENHAS_RECUSADAS, () -> java.time.Instant.parse("2026-10-29T12:00:00Z")));
    }

    @AfterEach
    void desmontar() throws Exception {
        gerenciador.fechar();
        registro.encerrarTodos();
        cofre.close();
        ssh.close();
    }

    private static boolean vivo(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private void todosOsTunelFechados() throws InterruptedException {
        for (var l : lancador.lancados) {
            for (int i = 0; i < 100 && vivo(l.processo().pid()); i++) {
                Thread.sleep(50);
            }
            assertFalse(vivo(l.processo().pid()), "um túnel ficou aberto: " + l.argumentosDoApp());
        }
        assertEquals(0, registro.quantos());
    }

    @Test
    void sondaOHostPeloTunelSemAutenticarEFechaOTunelNoFim() throws Exception {
        SondagemDeHost.Resultado r = assistente.sondar(SSM, "", 0);
        assertEquals(ssh.pin(), r.pin(), "a chave do host é a do SSH atrás do túnel, a mesma de uma conexão direta");
        assertEquals(0, ssh.pedidosDeAutenticacao.get(), "a sondagem nunca chega a pedir autenticação");
        assertEquals(1, lancador.lancados.size());
        assertEquals("/ferramentas/aws", lancador.lancados.get(0).argumentosDoApp().get(0));
        todosOsTunelFechados();
    }

    @Test
    void sondagemQueNaoSobeOTunelDizPorQueSemFalarDeHostEPorta() throws Exception {
        var semFerramenta = FerramentasFalsasDeTunel.abridor(lancador, f -> java.util.Optional.empty(), registro);
        GerenciadorServidores g = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida(), ServicosLocais.padrao(), semFerramenta);
        AssistenteDeServidor a = new AssistenteDeServidor(cofre, g, ConfigSsh.rapida());
        SondagemException e = assertThrows(SondagemException.class, () -> a.sondar(CLOUD, "", 0));
        assertTrue(e.getMessage().contains("cloudflared"), e.getMessage());
        assertTrue(e.getMessage().contains("brew install cloudflared") || e.getMessage().contains("winget install") || e.getMessage().contains("pacote"), e.getMessage());
        assertTrue(lancador.lancados.isEmpty());
        g.fechar();
    }

    @Test
    void entraComSenhaPeloTunelInstalaAChaveEProvaEOServidorEntraNoCofreComOTransporte() throws Exception {
        InfoChave chave = assistente.gerarChave("k-tunel");
        ssh.aceitar(cofre.chaveSsh(chave.id()).orElseThrow().paraKeyPair().getPublic()); // o comando de instalar é simulado: a chave passa a valer
        Pin pin = assistente.sondar(SSM, "", 0).pin();
        Dados dados = new Dados("nuvem", "", 0, USUARIO, ConfigServidor.PORTA_AGENTE_PADRAO).comTransporte(SSM);
        dados.validar(); // sem endereço nem porta: não são pedidos

        InstaladorDeChave.Resultado r = assistente.instalarAChaveComSenha(dados, chave, pin, SENHA_DO_SERVIDOR.toCharArray(), etapa -> { });
        assertEquals(InstaladorDeChave.Resultado.ADICIONADA, r);
        assertTrue(comandos.contains(ComandosDoAcesso.instalarChave()));
        assertEquals(List.of(SENHA_DO_SERVIDOR), ssh.senhasVistas);
        // sondagem + sessão com senha + prova da chave: cada uma o seu túnel, todos fechados
        assertEquals(3, lancador.lancados.size(), "três caminhos pelo túnel: sondagem, senha, prova");
        todosOsTunelFechados();

        ServidorLab lab = assistente.cadastrar(dados, chave.id(), pin);
        ConfigServidor gravado = cofre.servidor(lab.id()).orElseThrow();
        assertEquals(SSM, gravado.transporte());
        assertEquals(ConfigServidor.HOST_DO_TRANSPORTE, gravado.host());
        assertEquals(List.of(pin), gravado.pins());

        lab.iniciar();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado().conexao() + " falha=" + lab.estado().falha(),
            () -> lab.estado().conexao() == EstadoConexao.SSH_OK_SEM_AGENTE || lab.estado().conexao() == EstadoConexao.CONECTADO);
        assertEquals(4, lancador.lancados.size(), "a conexão do app sobe o seu túnel");
        gerenciador.remover(lab.id()).join();
        todosOsTunelFechados();
    }

    @Test
    void hostNaoConfirmadoNuncaRecebeASenhaNemPeloTunel() throws Exception {
        InfoChave chave = assistente.gerarChave("k-tunel");
        Pin deOutroServidor = new Pin("ssh-ed25519", "SHA256:" + "A".repeat(43));
        Dados dados = new Dados("nuvem", "", 0, USUARIO, ConfigServidor.PORTA_AGENTE_PADRAO).comTransporte(CLOUD);
        FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class,
            () -> assistente.instalarAChaveComSenha(dados, chave, deOutroServidor, SENHA_DO_SERVIDOR.toCharArray(), etapa -> { }));
        assertEquals(FalhaDeEntradaPorSenha.Motivo.HOST_MUDOU, e.motivo());
        assertTrue(ssh.senhasVistas.isEmpty(), "o servidor atrás do túnel NUNCA viu a senha");
        todosOsTunelFechados();
    }

    @Test
    void editarOModoMantemAConfiancaEMudarOServidorApagaEPedeParaConferirDeNovo() throws Exception {
        InfoChave chave = assistente.gerarChave("k-tunel");
        ssh.aceitar(cofre.chaveSsh(chave.id()).orElseThrow().paraKeyPair().getPublic());
        Pin pin = assistente.sondar(CLOUD, "", 0).pin();
        Dados dados = new Dados("nuvem", "", 0, USUARIO, ConfigServidor.PORTA_AGENTE_PADRAO).comTransporte(CLOUD);
        ServidorLab lab = assistente.cadastrar(dados, chave.id(), pin);
        lab.iniciar();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado().conexao() + " falha=" + lab.estado().falha(),
            () -> lab.estado().conexao() == EstadoConexao.SSH_OK_SEM_AGENTE || lab.estado().conexao() == EstadoConexao.CONECTADO);

        // Cloudflare -> SSM: o servidor é o mesmo, o pin fica, e a conexão volta sem pedir conferência
        var troca = gerenciador.atualizar(lab.id(), dados.comTransporte(SSM));
        assertFalse(troca.impressaoApagada(), "trocar o modo não apaga a confiança");
        assertEquals(List.of(pin), cofre.servidor(lab.id()).orElseThrow().pins());
        Espera.ate(PRAZO, () -> "estado=" + lab.estado().conexao() + " falha=" + lab.estado().falha(),
            () -> lab.estado().conexao() == EstadoConexao.SSH_OK_SEM_AGENTE || lab.estado().conexao() == EstadoConexao.CONECTADO);
        assertEquals("/ferramentas/aws", lancador.lancados.get(lancador.lancados.size() - 1).argumentosDoApp().get(0));

        // outra instância dentro do SSM: outra máquina, a confiança é apagada e a conexão para pedindo a conferência
        Transporte outra = Transporte.ssm("i-0fff1234def567890", "us-east-1", "meu-perfil", 22);
        var outraInstancia = gerenciador.atualizar(lab.id(), dados.comTransporte(outra));
        assertTrue(outraInstancia.impressaoApagada());
        assertTrue(cofre.servidor(lab.id()).orElseThrow().pins().isEmpty());
        Espera.ate(PRAZO, () -> "estado=" + lab.estado().conexao(), () -> lab.estado().conexao() == EstadoConexao.ERRO_CHAVE_HOST);
        assertEquals(Falha.Tipo.PIN_AUSENTE, lab.estado().falha().tipo());
        assertNotEquals(0, lancador.lancados.size());
        gerenciador.remover(lab.id()).join();
        todosOsTunelFechados();
    }

    @Test
    void daParaVoltarAoDiretoPeloEditar() throws Exception {
        InfoChave chave = assistente.gerarChave("k-tunel");
        ssh.aceitar(cofre.chaveSsh(chave.id()).orElseThrow().paraKeyPair().getPublic());
        Pin pin = assistente.sondar(SSM, "", 0).pin();
        ServidorLab lab = assistente.cadastrar(new Dados("nuvem", "", 0, USUARIO, ConfigServidor.PORTA_AGENTE_PADRAO).comTransporte(SSM), chave.id(), pin);
        var volta = gerenciador.atualizar(lab.id(), new Dados("nuvem", "127.0.0.1", ssh.porta(), USUARIO, ConfigServidor.PORTA_AGENTE_PADRAO).comTransporte(Transporte.DIRETO));
        assertFalse(volta.impressaoApagada());
        ConfigServidor c = cofre.servidor(lab.id()).orElseThrow();
        assertEquals(Transporte.DIRETO, c.transporte());
        assertEquals("127.0.0.1", c.host());
        assertEquals(ssh.porta(), c.porta());
        assertEquals(List.of(pin), c.pins());
        lab.iniciar();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado().conexao() + " falha=" + lab.estado().falha(),
            () -> lab.estado().conexao() == EstadoConexao.SSH_OK_SEM_AGENTE || lab.estado().conexao() == EstadoConexao.CONECTADO);
        int lancamentosAntes = lancador.lancados.size();
        assertEquals(1, lancamentosAntes, "só a sondagem do começo subiu túnel: o direto conecta no endereço");
    }
}
