package br.com.labdesktop.servidor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.labdesktop.acesso.ComandosDoAcesso;
import br.com.labdesktop.acesso.InstaladorDeChave;
import br.com.labdesktop.acesso.LimiteDeTentativas;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.InfoChave;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.cofre.Pin;
import br.com.labdesktop.seguranca.Zeragem;
import br.com.labdesktop.servidor.AssistenteDeServidor.Dados;
import br.com.labdesktop.servidor.AssistenteDeServidor.EtapaDoPrimeiroAcesso;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.FalhaDeEntradaPorSenha;
import br.com.labdesktop.ssh.FalhaDeEntradaPorSenha.Motivo;
import br.com.labdesktop.ssh.SondagemDeHost;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.teste.ShellDeTeste;
import br.com.labdesktop.util.CaminhosApp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O primeiro acesso por senha de ponta a ponta, com o cofre de verdade, o servidor SSH embutido (senha e chave) e o {@code sh} de verdade
 * instalando a chave numa pasta pessoal de teste: entra com a senha uma vez, instala a chave sem duplicar, PROVA por uma conexão nova só com a
 * chave e só então o servidor entra no cofre e conecta normalmente. Prova também as bordas: senha errada, três recusas e a pausa, servidor sem
 * senha, host não confirmado (o servidor falso nunca viu a senha), prova que falha (nada mais é mexido) e a senha sempre zerada.
 */
class PrimeiroAcessoPorSenhaTest {

    private static final char[] SENHA_MESTRA = "uma-senha-mestra-de-teste".toCharArray();
    private static final String USUARIO = "labuser";
    private static final String SENHA_DO_SERVIDOR = "senha-do-servidor-Zk4!";

    @TempDir
    Path tmp;

    private ServidorSshDeTeste ssh;
    private AgenteDoLab agente;
    private ShellDeTeste shell;
    private CofreDeChaves cofre;
    private GerenciadorServidores gerenciador;
    private AssistenteDeServidor assistente;
    private Instant agora = Instant.parse("2026-10-28T12:00:00Z");

    @BeforeEach
    void montar() throws Exception {
        assumeTrue(ShellDeTeste.temShell(), "precisa de /bin/sh");
        ssh = ServidorSshDeTeste.ed25519();
        ssh.aceitarSenha(USUARIO, SENHA_DO_SERVIDOR);
        agente = new AgenteDoLab();
        Path home = Files.createDirectories(tmp.resolve("home"));
        shell = new ShellDeTeste(home, ssh).outrosComandos(agente.comandosPorSsh());
        CaminhosApp caminhos = CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
        cofre = CofreDeChaves.criar(caminhos, SENHA_MESTRA.clone(), ParametrosKdf.MINIMO);
        gerenciador = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida());
        assistente = new AssistenteDeServidor(cofre, gerenciador, ConfigSsh.rapida(),
            new LimiteDeTentativas(AssistenteDeServidor.MAXIMO_DE_SENHAS_RECUSADAS, AssistenteDeServidor.JANELA_DAS_SENHAS_RECUSADAS, () -> agora));
    }

    @AfterEach
    void desmontar() throws Exception {
        if (gerenciador != null) {
            gerenciador.fechar().join();
            cofre.close();
            agente.close();
            ssh.close();
        }
    }

    private Dados dados() {
        return new Dados("lab", "127.0.0.1", ssh.porta(), USUARIO, agente.porta());
    }

    private static char[] senha(String s) {
        return s.toCharArray();
    }

    private static void exigirZerada(char[] c) {
        for (char ch : c) {
            assertEquals('\0', ch, "a senha digitada tem de ficar zerada");
        }
    }

    @Test
    void entraComASenhaInstalaAChaveProvaEDepoisOServidorConectaSoPorChave() throws Exception {
        InfoChave chave = assistente.gerarChave("lab");
        SondagemDeHost.Resultado sonda = assistente.sondar("127.0.0.1", ssh.porta());
        char[] digitada = senha(SENHA_DO_SERVIDOR);
        List<EtapaDoPrimeiroAcesso> etapas = new ArrayList<>();

        InstaladorDeChave.Resultado r = assistente.instalarAChaveComSenha(dados(), chave, sonda.pin(), digitada, etapas::add);

        assertEquals(InstaladorDeChave.Resultado.ADICIONADA, r);
        assertEquals(List.of(EtapaDoPrimeiroAcesso.ENTRANDO, EtapaDoPrimeiroAcesso.INSTALANDO, EtapaDoPrimeiroAcesso.PROVANDO), etapas);
        exigirZerada(digitada);
        assertEquals(List.of(SENHA_DO_SERVIDOR), ssh.senhasVistas, "a senha foi enviada UMA vez");
        assertEquals(List.of(ComandosDoAcesso.instalarChave(), ComandosDoAcesso.lerPermissoesDaPastaPessoal(), br.com.labdesktop.ssh.ProvaPorChave.COMANDO_DA_PROVA), shell.recebidos,
            "instalar a chave, ler as permissões (só leitura) e o 'echo' da prova (nada de token nem de agente)");
        assertTrue(Files.readString(shell.authorizedKeys()).contains(chave.publica().split(" ")[1]));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(shell.authorizedKeys())));
        assertTrue(ssh.autenticacoesAceitas.get() >= 1, "a prova entrou por chave numa conexão nova");
        assertEquals(1, ssh.chavesOferecidas.stream().distinct().count(), "só a chave do cofre foi oferecida");
        assertEquals(List.of(), cofre.servidores(), "o servidor só entra no cofre depois: o primeiro acesso não grava nada além da chave");

        // e agora o resto do assistente de sempre conecta SÓ por chave, sem senha nenhuma
        ServidorLab lab = assistente.cadastrar(dados(), chave.id(), sonda.pin());
        assistente.concluir(lab, "mac-de-teste", e -> { });
        assertEquals(EstadoConexao.CONECTADO, lab.estado().conexao());
        assertEquals(1, ssh.senhasVistas.size(), "nenhuma senha a mais: a conexão normal só usa a chave");
    }

    @Test
    void conectarPeloCaminhoDeAntesNuncaInstalaChaveNemTocaNoSshd() throws Exception {
        InfoChave chave = assistente.gerarChave("lab");
        SondagemDeHost.Resultado sonda = assistente.sondar("127.0.0.1", ssh.porta());
        ServidorLab lab = assistente.cadastrar(dados(), chave.id(), sonda.pin());
        ssh.aceitar(cofre.keyPairDoServidor(lab.id()).getPublic()); // o usuário instalou a chave à mão
        assistente.concluir(lab, "mac-de-teste", e -> { });

        assertEquals(EstadoConexao.CONECTADO, lab.estado().conexao());
        assertEquals(List.of(), ssh.senhasVistas, "sem senha nenhuma");
        assertTrue(ssh.comandos.stream().noneMatch(c -> c.equals(ComandosDoAcesso.instalarChave()) || c.contains("sshd") || c.contains("authorized_keys")),
            "conectar não instala chave nem mexe no sshd: " + ssh.comandos);
        assertFalse(Files.exists(shell.authorizedKeys()), "nenhum authorized_keys foi tocado");
    }

    @Test
    void repetirOPrimeiroAcessoNaoDuplicaAChave() throws Exception {
        InfoChave chave = assistente.gerarChave("lab");
        Pin pin = assistente.sondar("127.0.0.1", ssh.porta()).pin();
        assertEquals(InstaladorDeChave.Resultado.ADICIONADA, assistente.instalarAChaveComSenha(dados(), chave, pin, senha(SENHA_DO_SERVIDOR), e -> { }));
        assertEquals(InstaladorDeChave.Resultado.JA_EXISTIA, assistente.instalarAChaveComSenha(dados(), chave, pin, senha(SENHA_DO_SERVIDOR), e -> { }));
        assertEquals(1, Files.readAllLines(shell.authorizedKeys()).size());
    }

    @Test
    void senhaErradaDizOMotivoZeraASenhaENadaMaisEMexido() throws Exception {
        InfoChave chave = assistente.gerarChave("lab");
        Pin pin = assistente.sondar("127.0.0.1", ssh.porta()).pin();
        char[] errada = senha("senha-errada-1");

        FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class,
            () -> assistente.instalarAChaveComSenha(dados(), chave, pin, errada, x -> { }));

        assertEquals(Motivo.SENHA_INCORRETA, e.motivo());
        exigirZerada(errada);
        assertEquals(List.of(), shell.recebidos, "com a senha errada nenhum comando rodou");
        assertFalse(Files.exists(shell.authorizedKeys()));
        assertEquals(List.of(), cofre.servidores());
    }

    private FalhaDeEntradaPorSenha errarComSenha(InfoChave chave, Pin pin, String senha) {
        agora = agora.plus(Duration.ofSeconds(20)); // passa as pausas curtas entre uma recusa e outra
        return assertThrows(FalhaDeEntradaPorSenha.class, () -> assistente.instalarAChaveComSenha(dados(), chave, pin, senha(senha), x -> { }));
    }

    @Test
    void quatroRecusasNaJanelaDeDezMinutosPausamATentativaSeguinteEMesmoACertaNaoSai() throws Exception {
        InfoChave chave = assistente.gerarChave("lab");
        Pin pin = assistente.sondar("127.0.0.1", ssh.porta()).pin();
        assertEquals(Motivo.SENHA_INCORRETA, errarComSenha(chave, pin, "errada-1").motivo());
        FalhaDeEntradaPorSenha segunda = errarComSenha(chave, pin, "errada-2");
        assertTrue(segunda.getMessage().contains("Restam 2 tentativas"), segunda.getMessage());
        assertTrue(segunda.getMessage().contains("o servidor pode bloquear o seu endereço depois de várias senhas erradas"), segunda.getMessage());
        FalhaDeEntradaPorSenha terceira = errarComSenha(chave, pin, "errada-3");
        assertTrue(terceira.getMessage().contains("Resta 1 tentativa") || terceira.getMessage().contains("Restam 1 tentativa"), terceira.getMessage());
        FalhaDeEntradaPorSenha quarta = errarComSenha(chave, pin, "errada-4");
        assertTrue(quarta.getMessage().contains("não tenta mais por 10 minutos"), quarta.getMessage());

        char[] quinta = senha(SENHA_DO_SERVIDOR); // até a certa é segurada: a pausa existe para não banir o endereço do usuário
        agora = agora.plus(Duration.ofMinutes(3));
        FalhaDeEntradaPorSenha pausa = assertThrows(FalhaDeEntradaPorSenha.class,
            () -> assistente.instalarAChaveComSenha(dados(), chave, pin, quinta, x -> { }));
        assertEquals(Motivo.MUITAS_TENTATIVAS, pausa.motivo());
        assertTrue(pausa.getMessage().startsWith("Espere "), pausa.getMessage());
        assertTrue(pausa.getMessage().contains("o servidor pode bloquear o seu endereço"), pausa.getMessage());
        exigirZerada(quinta);
        assertEquals(List.of("errada-1", "errada-2", "errada-3", "errada-4"), ssh.senhasVistas, "a quinta (mesmo certa) NEM saiu do app");

        agora = agora.plus(Duration.ofMinutes(8)); // a primeira recusa saiu da janela
        assertEquals(InstaladorDeChave.Resultado.ADICIONADA, assistente.instalarAChaveComSenha(dados(), chave, pin, senha(SENHA_DO_SERVIDOR), x -> { }));
    }

    @Test
    void asRecusasContamAcumuladasMesmoComPausasEOHostEmMaiusculasEOMesmo() throws Exception {
        InfoChave chave = assistente.gerarChave("lab");
        Pin pin = assistente.sondar("127.0.0.1", ssh.porta()).pin();
        for (int i = 1; i <= 4; i++) {
            errarComSenha(chave, pin, "errada-" + i);
        }
        Dados maiusculo = new Dados("lab", "127.0.0.1 ", ssh.porta(), "LABUSER", agente.porta());
        FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class,
            () -> assistente.instalarAChaveComSenha(maiusculo, chave, pin, senha(SENHA_DO_SERVIDOR), x -> { }));
        assertEquals(Motivo.MUITAS_TENTATIVAS, e.motivo(), "o mesmo servidor e usuário escritos de outro jeito contam na mesma conta");
        assertEquals(4, ssh.senhasVistas.size());
    }

    @Test
    void senhaCertaZeraAContaDeTentativas() throws Exception {
        InfoChave chave = assistente.gerarChave("lab");
        Pin pin = assistente.sondar("127.0.0.1", ssh.porta()).pin();
        for (int i = 0; i < 3; i++) {
            errarComSenha(chave, pin, "errada");
        }
        agora = agora.plus(Duration.ofSeconds(30));
        assistente.instalarAChaveComSenha(dados(), chave, pin, senha(SENHA_DO_SERVIDOR), x -> { });
        for (int i = 0; i < 3; i++) {
            errarComSenha(chave, pin, "errada");
        }
        // 3 + acerto + 3: o acerto zerou a conta, então nunca chegou a 4
        agora = agora.plus(Duration.ofSeconds(30));
        assertEquals(InstaladorDeChave.Resultado.JA_EXISTIA,
            assistente.instalarAChaveComSenha(dados(), chave, pin, senha(SENHA_DO_SERVIDOR), x -> { }));
    }

    @Test
    void hostNaoConfirmadoNuncaRecebeASenhaEOUsuarioVeAFraseDeHostQueMudou() throws Exception {
        InfoChave chave = assistente.gerarChave("lab");
        try (ServidorSshDeTeste outro = ServidorSshDeTeste.ed25519()) {
            Pin deOutro = outro.pin();
            char[] digitada = senha(SENHA_DO_SERVIDOR);
            FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class,
                () -> assistente.instalarAChaveComSenha(dados(), chave, deOutro, digitada, x -> { }));
            assertEquals(Motivo.HOST_MUDOU, e.motivo());
            exigirZerada(digitada);
            assertEquals(0, ssh.pedidosDeAutenticacao.get(), "o servidor nem recebeu o pedido de autenticação");
            assertEquals(List.of(), ssh.senhasVistas, "o servidor NUNCA viu a senha");
            assertEquals(List.of(), shell.recebidos);
        }
    }

    @Test
    void servidorSemSenhaHabilitadaDizEOCaminhoAntigoFicaDisponivel() throws Exception {
        ssh.desligarSenha();
        InfoChave chave = assistente.gerarChave("lab");
        Pin pin = assistente.sondar("127.0.0.1", ssh.porta()).pin();
        FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class,
            () -> assistente.instalarAChaveComSenha(dados(), chave, pin, senha(SENHA_DO_SERVIDOR), x -> { }));
        assertEquals(Motivo.SERVIDOR_SEM_SENHA, e.motivo());
        assertTrue(e.getMessage().contains("opções avançadas"), e.getMessage());
        assertEquals(List.of(), ssh.senhasVistas);
        assertEquals(1, cofre.chaves().size(), "a chave segue no cofre: o caminho antigo (mostrar a pública) usa a mesma");
    }

    @Test
    void servidorSemLoginPorChaveFalhaNaProvaENadaMaisEMexido() throws Exception {
        shell.honrarAuthorizedKeys(false); // o authorized_keys recebe a linha, mas o servidor não a honra (PubkeyAuthentication no, por exemplo)
        InfoChave chave = assistente.gerarChave("lab");
        Pin pin = assistente.sondar("127.0.0.1", ssh.porta()).pin();
        char[] digitada = senha(SENHA_DO_SERVIDOR);

        FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class,
            () -> assistente.instalarAChaveComSenha(dados(), chave, pin, digitada, x -> { }));

        assertEquals(Motivo.SEM_LOGIN_POR_CHAVE, e.motivo());
        assertTrue(e.getMessage().contains("não aceitou a chave"), e.getMessage());
        assertTrue(e.getMessage().contains("permite login por chave"), e.getMessage());
        exigirZerada(digitada);
        assertEquals(List.of(ComandosDoAcesso.instalarChave(), ComandosDoAcesso.lerPermissoesDaPastaPessoal()), shell.recebidos, "só a instalação da chave e uma leitura de permissões rodaram: nada de token, agente ou limpeza");
        assertEquals(List.of(), cofre.servidores(), "nenhum servidor entrou no cofre");
        assertTrue(agente.tokens.isEmpty());
    }

    @Test
    void provaQueFalhaComPastaPessoalGravavelPorGrupoCitaOStrictModesSemAlterarNada() throws Exception {
        shell.honrarAuthorizedKeys(false);
        Path home = tmp.resolve("home");
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwxrwxr-x"));
        InfoChave chave = assistente.gerarChave("lab");
        Pin pin = assistente.sondar("127.0.0.1", ssh.porta()).pin();

        FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class,
            () -> assistente.instalarAChaveComSenha(dados(), chave, pin, senha(SENHA_DO_SERVIDOR), x -> { }));

        assertEquals(Motivo.SEM_LOGIN_POR_CHAVE, e.motivo());
        assertTrue(e.getMessage().contains("a pasta pessoal do usuário") && e.getMessage().contains("StrictModes"), e.getMessage());
        assertEquals("rwxrwxr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(home)), "o app não alterou a pasta pessoal");
    }

    @Test
    void contaBloqueadaPeloServidorViraMensagemClaraEContaParaAPausa() throws Exception {
        ssh.largarAoReceberSenha();
        InfoChave chave = assistente.gerarChave("lab");
        Pin pin = assistente.sondar("127.0.0.1", ssh.porta()).pin();
        FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class,
            () -> assistente.instalarAChaveComSenha(dados(), chave, pin, senha(SENHA_DO_SERVIDOR), x -> { }));
        assertEquals(Motivo.CONTA_BLOQUEADA, e.motivo());
        assertTrue(e.getMessage().contains("bloqueada"), e.getMessage());
    }

    @Test
    void senhaVaziaOuDadosInvalidosNaoChegamAoServidorEAindaZeram() throws Exception {
        InfoChave chave = assistente.gerarChave("lab");
        Pin pin = assistente.sondar("127.0.0.1", ssh.porta()).pin();
        assertThrows(IllegalArgumentException.class, () -> assistente.instalarAChaveComSenha(dados(), chave, pin, new char[0], x -> { }));
        char[] digitada = senha(SENHA_DO_SERVIDOR);
        assertThrows(IllegalArgumentException.class,
            () -> assistente.instalarAChaveComSenha(new Dados("lab", "-oProxyCommand=x", 22, USUARIO, 7000), chave, pin, digitada, x -> { }));
        exigirZerada(digitada);
        assertEquals(0, ssh.pedidosDeAutenticacao.get());
    }

    @Test
    void asEtapasNaoCarregamASenhaEOChaveiroDoCofreNaoGuardaNada() throws Exception {
        InfoChave chave = assistente.gerarChave("lab");
        Pin pin = assistente.sondar("127.0.0.1", ssh.porta()).pin();
        AtomicReference<String> vistas = new AtomicReference<>("");
        assistente.instalarAChaveComSenha(dados(), chave, pin, senha(SENHA_DO_SERVIDOR), e -> vistas.updateAndGet(a -> a + e.rotulo() + e));
        assertFalse(vistas.get().contains(SENHA_DO_SERVIDOR));
        // o cofre, depois de gravado, não tem a senha do servidor em lugar nenhum
        String arquivos = Files.walk(tmp.resolve("dados")).filter(Files::isRegularFile).map(p -> {
            try {
                return new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.ISO_8859_1);
            } catch (java.io.IOException e) {
                return "";
            }
        }).reduce("", String::concat);
        assertFalse(arquivos.contains(SENHA_DO_SERVIDOR), "a senha do servidor nunca vai ao disco");
        Zeragem.zerar(new char[0]);
    }
}
