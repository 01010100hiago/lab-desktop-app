package br.com.labdesktop.servidor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import br.com.labdesktop.servidor.AssistenteDeServidor.Dados;
import br.com.labdesktop.servidor.AssistenteDeServidor.EtapaDoPrimeiroAcesso;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.Falha;
import br.com.labdesktop.ssh.FalhaDeEntradaPorSenha;
import br.com.labdesktop.ssh.FalhaDeEntradaPorSenha.Motivo;
import br.com.labdesktop.ssh.ProvaPorChave;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.ProxyTcp;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.teste.ShellDeTeste;
import br.com.labdesktop.util.CaminhosApp;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reparar a chave recusada: consertar um servidor que JÁ está cadastrado e que o lab recusa ("Chave recusada"), de ponta a ponta, com o cofre de verdade, o servidor SSH embutido (que
 * recusa a chave e aceita senha) e o {@code sh} de verdade instalando a chave: entra com a senha uma vez, instala a chave do cofre, PROVA numa conexão nova só por chave e
 * manda reconectar (o estado volta a "Conectado"). As bordas: senha errada, host que mudou (nada sai), sem impressão guardada, servidor sem senha, prova que falha, o freio
 * compartilhado com o assistente e a senha que nunca aparece em log, mensagem nem toString.
 */
class ReparoDaChaveRecusadaTest {

    private static final char[] SENHA_MESTRA = "uma-senha-mestra-de-teste".toCharArray();
    private static final String USUARIO = "labuser";
    private static final String SENHA_DO_SERVIDOR = "senha-do-servidor-Zk4!";
    private static final Duration PRAZO = Duration.ofSeconds(30);

    @TempDir
    Path tmp;

    private ServidorSshDeTeste ssh;
    private AgenteDoLab agente;
    private ShellDeTeste shell;
    private CofreDeChaves cofre;
    private GerenciadorServidores gerenciador;
    private AssistenteDeServidor assistente;
    private Instant agora = Instant.parse("2026-10-28T12:00:00Z");
    private LimiteDeTentativas limite;

    @BeforeEach
    void montar() throws Exception {
        assumeTrue(ShellDeTeste.temShell(), "precisa de /bin/sh");
        ssh = ServidorSshDeTeste.ed25519();
        ssh.aceitarSenha(USUARIO, SENHA_DO_SERVIDOR);
        agente = new AgenteDoLab();
        shell = new ShellDeTeste(Files.createDirectories(tmp.resolve("home")), ssh).outrosComandos(agente.comandosPorSsh());
        cofre = CofreDeChaves.criar(CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs")), SENHA_MESTRA.clone(), ParametrosKdf.MINIMO);
        gerenciador = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida());
        limite = new LimiteDeTentativas(AssistenteDeServidor.MAXIMO_DE_SENHAS_RECUSADAS, AssistenteDeServidor.JANELA_DAS_SENHAS_RECUSADAS, () -> agora);
        assistente = new AssistenteDeServidor(cofre, gerenciador, ConfigSsh.rapida(), limite);
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

    /** O servidor já cadastrado (com a impressão digital confirmada) que o lab recusa: o ponto de partida do usuário. */
    private ServidorLab servidorRecusado() throws Exception {
        InfoChave chave = assistente.gerarChave("lab");
        Pin pin = assistente.sondar("127.0.0.1", ssh.porta()).pin();
        ServidorLab lab = assistente.cadastrar(dados(), chave.id(), pin);
        lab.iniciar();
        // o app tenta UMA vez de novo sozinho depois da primeira recusa e então para (proximaTentativaEm vazio): o ponto de partida só vale depois disso
        Espera.ate(PRAZO, () -> "chave recusada e sem nova tentativa (está em " + lab.estado().conexao() + ")",
            () -> lab.estado().conexao() == EstadoConexao.ERRO_AUTENTICACAO && lab.estado().proximaTentativaEm() == null);
        assertEquals(Falha.Tipo.CHAVE_RECUSADA, lab.estado().falha().tipo());
        return lab;
    }

    private FalhaDeEntradaPorSenha errar(ServidorLab lab, String senha) {
        agora = agora.plus(Duration.ofSeconds(20)); // passa as pausas curtas entre uma recusa e outra
        return assertThrows(FalhaDeEntradaPorSenha.class, () -> assistente.repararAChaveComSenha(lab, senha(senha), x -> { }));
    }

    @Test
    void servidorQueRecusaAChaveEAceitaSenhaDepoisDoFluxoAChaveEntraEOEstadoVoltaAConectado() throws Exception {
        ServidorLab lab = servidorRecusado();
        InfoChave doApp = assistente.acessoDo(lab).orElseThrow().chave();
        assertFalse(Files.exists(shell.authorizedKeys()), "o servidor não tem a chave: foi por isso que recusou");
        char[] digitada = senha(SENHA_DO_SERVIDOR);
        List<EtapaDoPrimeiroAcesso> etapas = new ArrayList<>();

        InstaladorDeChave.Resultado r = assistente.repararAChaveComSenha(lab, digitada, etapas::add);

        assertEquals(InstaladorDeChave.Resultado.ADICIONADA, r);
        assertEquals(List.of(EtapaDoPrimeiroAcesso.ENTRANDO, EtapaDoPrimeiroAcesso.INSTALANDO, EtapaDoPrimeiroAcesso.PROVANDO), etapas);
        exigirZerada(digitada);
        assertEquals(List.of(SENHA_DO_SERVIDOR), ssh.senhasVistas, "a senha foi enviada UMA vez");
        assertEquals(List.of(ComandosDoAcesso.instalarChave(), ComandosDoAcesso.lerPermissoesDaPastaPessoal(), ProvaPorChave.COMANDO_DA_PROVA), shell.recebidos,
            "instalar a chave, ler as permissões (só leitura) e o 'echo' da prova: nada de sudo, de token nem de agente");
        String linhas = Files.readString(shell.authorizedKeys());
        assertTrue(linhas.contains(doApp.publica().split(" ")[1]), "a chave instalada é a mesma impressão digital que o app oferece (" + doApp.impressao() + ")");
        assertEquals(1, Files.readAllLines(shell.authorizedKeys()).size());
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(shell.authorizedKeys())));
        Espera.ate(PRAZO, () -> "volta a conectar (está em " + lab.estado().conexao() + ")", () -> lab.estado().conexao() == EstadoConexao.CONECTADO);
        assertEquals(1, ssh.senhasVistas.size(), "a reconexão é só por chave: nenhuma senha a mais");
        assertEquals(1, cofre.servidores().size(), "o cadastro continua o mesmo, sem servidor novo");
        assertEquals(List.of(doApp.id()), cofre.chaves().stream().map(InfoChave::id).toList(), "nenhuma chave nova no cofre");
    }

    @Test
    void repetirOReparoNaoDuplicaAChave() throws Exception {
        ServidorLab lab = servidorRecusado();
        assertEquals(InstaladorDeChave.Resultado.ADICIONADA, assistente.repararAChaveComSenha(lab, senha(SENHA_DO_SERVIDOR), x -> { }));
        assertEquals(InstaladorDeChave.Resultado.JA_EXISTIA, assistente.repararAChaveComSenha(lab, senha(SENHA_DO_SERVIDOR), x -> { }));
        assertEquals(1, Files.readAllLines(shell.authorizedKeys()).size());
    }

    @Test
    void senhaErradaDizOMotivoZeraASenhaENadaMaisEMexido() throws Exception {
        ServidorLab lab = servidorRecusado();
        char[] errada = senha("senha-errada-1");

        FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class, () -> assistente.repararAChaveComSenha(lab, errada, x -> { }));

        assertEquals(Motivo.SENHA_INCORRETA, e.motivo());
        exigirZerada(errada);
        assertEquals(List.of(), shell.recebidos, "com a senha errada nenhum comando rodou");
        assertFalse(Files.exists(shell.authorizedKeys()));
        assertEquals(EstadoConexao.ERRO_AUTENTICACAO, lab.estado().conexao(), "o estado segue como estava");
    }

    @Test
    void hostQueMudouParaAntesDaSenhaNuncaConfiaDeNovoSozinhoEOPinGuardadoFicaIntacto() throws Exception {
        InfoChave chave = assistente.gerarChave("lab");
        try (ServidorSshDeTeste outro = ServidorSshDeTeste.ed25519()) {
            Pin deOutraMaquina = outro.pin(); // o cadastro guarda a impressão de OUTRA máquina: este servidor "mudou"
            ServidorLab lab = assistente.cadastrar(dados(), chave.id(), deOutraMaquina);
            char[] digitada = senha(SENHA_DO_SERVIDOR);

            FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class, () -> assistente.repararAChaveComSenha(lab, digitada, x -> { }));

            assertEquals(Motivo.HOST_MUDOU, e.motivo());
            assertEquals(AssistenteDeServidor.TEXTO_DO_HOST_QUE_MUDOU, e.getMessage());
            assertTrue(e.getMessage().contains("A senha NÃO foi enviada") && e.getMessage().contains("Remover servidor"), e.getMessage());
            exigirZerada(digitada);
            assertEquals(0, ssh.pedidosDeAutenticacao.get(), "o servidor nem recebeu o pedido de autenticação");
            assertEquals(List.of(), ssh.senhasVistas, "o servidor NUNCA viu a senha");
            assertEquals(List.of(), shell.recebidos);
            assertEquals(List.of(deOutraMaquina), cofre.servidor(lab.id()).orElseThrow().pins(), "o app não trocou a impressão guardada por conta própria");
        }
    }

    @Test
    void servidorSemImpressaoConfirmadaNaoRecebeSenhaNemUmaConexao() throws Exception {
        ServidorLab lab = servidorRecusado();
        cofre.limparPins(lab.id());
        char[] digitada = senha(SENHA_DO_SERVIDOR);
        int pedidosAntes = ssh.pedidosDeAutenticacao.get();

        FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class, () -> assistente.repararAChaveComSenha(lab, digitada, x -> { }));

        assertEquals(Motivo.SEM_IMPRESSAO_CONFIRMADA, e.motivo());
        assertTrue(e.getMessage().contains("Conferir a impressão do servidor"), e.getMessage());
        exigirZerada(digitada);
        assertEquals(List.of(), ssh.senhasVistas);
        assertEquals(pedidosAntes, ssh.pedidosDeAutenticacao.get(), "nenhuma conexão nova com o servidor");
        assertEquals(List.of(), cofre.servidor(lab.id()).orElseThrow().pins(), "o app não gravou impressão nenhuma");
    }

    @Test
    void servidorQueNaoAceitaSenhaDizParaEntregarAChaveAQuemAdministra() throws Exception {
        ServidorLab lab = servidorRecusado();
        ssh.desligarSenha();

        FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class, () -> assistente.repararAChaveComSenha(lab, senha(SENHA_DO_SERVIDOR), x -> { }));

        assertEquals(Motivo.SERVIDOR_SEM_SENHA, e.motivo());
        assertEquals(AssistenteDeServidor.TEXTO_SERVIDOR_SEM_SENHA, e.getMessage());
        assertFalse(e.getMessage().contains("opções avançadas"), "no reparo não existe 'opções avançadas': a frase manda para Editar");
        assertEquals(List.of(), ssh.senhasVistas);
    }

    @Test
    void servidorQueNaoHonraAChaveInstaladaFalhaNaProvaSemMexerEmMaisNada() throws Exception {
        ServidorLab lab = servidorRecusado();
        shell.honrarAuthorizedKeys(false);

        FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class, () -> assistente.repararAChaveComSenha(lab, senha(SENHA_DO_SERVIDOR), x -> { }));

        assertEquals(Motivo.SEM_LOGIN_POR_CHAVE, e.motivo());
        assertTrue(e.getMessage().contains("não aceitou a chave"), e.getMessage());
        assertEquals(List.of(ComandosDoAcesso.instalarChave(), ComandosDoAcesso.lerPermissoesDaPastaPessoal()), shell.recebidos,
            "só a instalação da chave e uma leitura de permissões rodaram");
        assertTrue(agente.tokens.isEmpty());
    }

    @Test
    void oFreioDeQuatroRecusasEmDezMinutosValeTambemParaOReparoEEDividoComOAssistente() throws Exception {
        ServidorLab lab = servidorRecusado();
        InfoChave chave = assistente.acessoDo(lab).orElseThrow().chave();
        Pin pin = cofre.servidor(lab.id()).orElseThrow().pins().get(0);
        assertEquals(Motivo.SENHA_INCORRETA, errar(lab, "errada-1").motivo());
        // a 2ª e a 3ª recusas vêm pelo assistente de adicionar: é a MESMA conta (servidor, porta e usuário)
        for (String senha : List.of("errada-2", "errada-3")) {
            agora = agora.plus(Duration.ofSeconds(20));
            assertThrows(FalhaDeEntradaPorSenha.class, () -> assistente.instalarAChaveComSenha(dados(), chave, pin, senha(senha), x -> { }));
        }
        FalhaDeEntradaPorSenha quarta = errar(lab, "errada-4");
        assertTrue(quarta.getMessage().contains("não tenta mais por 10 minutos"), quarta.getMessage());

        char[] certa = senha(SENHA_DO_SERVIDOR); // a senha certa também é segurada: a pausa existe para o fail2ban não banir o endereço do usuário
        agora = agora.plus(Duration.ofMinutes(3));
        FalhaDeEntradaPorSenha pausa = assertThrows(FalhaDeEntradaPorSenha.class, () -> assistente.repararAChaveComSenha(lab, certa, x -> { }));
        assertEquals(Motivo.MUITAS_TENTATIVAS, pausa.motivo());
        assertTrue(pausa.getMessage().startsWith("Espere "), pausa.getMessage());
        exigirZerada(certa);
        assertEquals(List.of("errada-1", "errada-2", "errada-3", "errada-4"), ssh.senhasVistas, "a quinta (mesmo certa) NEM saiu do app");

        agora = agora.plus(Duration.ofMinutes(8)); // a primeira recusa saiu da janela
        assertEquals(InstaladorDeChave.Resultado.ADICIONADA, assistente.repararAChaveComSenha(lab, senha(SENHA_DO_SERVIDOR), x -> { }));
    }

    /** Espera o número de pedidos de autenticação do servidor parar de mudar (duas leituras iguais com folga) e devolve o valor final. */
    private int pedidosEstabilizados() throws InterruptedException {
        int anterior = -1;
        for (int i = 0; i < 100; i++) {
            int atual = ssh.pedidosDeAutenticacao.get();
            if (atual == anterior) {
                return atual;
            }
            anterior = atual;
            Thread.sleep(150);
        }
        throw new AssertionError("os pedidos de autenticação não pararam de crescer: " + anterior);
    }

    @Test
    void depoisDoSucessoEDepoisDaFalhaNaoHaNovaTentativaDeChaveEmLoop() throws Exception {
        ServidorLab lab = servidorRecusado();
        int base = pedidosEstabilizados();

        // falha: uma senha recusada = exatamente UM pedido de autenticação a mais, e depois disso NADA (nem retentativa do app)
        errar(lab, "errada-1");
        int aposAFalha = pedidosEstabilizados();
        assertEquals(base + 1, aposAFalha, "a senha recusada é um pedido só");
        Espera.naoAcontece(Duration.ofSeconds(1), "mais um pedido de autenticação depois da senha recusada", () -> ssh.pedidosDeAutenticacao.get() != aposAFalha);

        // sucesso: a senha, a prova e a reconexão; depois que conecta, nenhuma tentativa nova, por mais que se espere
        assistente.repararAChaveComSenha(lab, senha(SENHA_DO_SERVIDOR), x -> { });
        Espera.ate(PRAZO, "conectado", () -> lab.estado().conexao() == EstadoConexao.CONECTADO);
        int aposOSucesso = pedidosEstabilizados();
        assertTrue(aposOSucesso > aposAFalha, "a senha, a prova e a reconexão pediram autenticação");
        Espera.naoAcontece(Duration.ofSeconds(1), "mais um pedido de autenticação com o servidor já conectado", () -> ssh.pedidosDeAutenticacao.get() != aposOSucesso);
        assertEquals(EstadoConexao.CONECTADO, lab.estado().conexao());
    }

    @Test
    void provaQueFalhaContaNoFreioSemZerarERepetirNaoDaLoginsSemLimite() throws Exception {
        ServidorLab lab = servidorRecusado();
        shell.honrarAuthorizedKeys(false); // o servidor aceita a senha e grava a linha, mas não honra a chave (AuthorizedKeysFile, StrictModes...)
        int base = pedidosEstabilizados();
        int porCiclo = 0;
        for (int i = 1; i <= 4; i++) {
            agora = agora.plus(Duration.ofSeconds(20));
            FalhaDeEntradaPorSenha f = assertThrows(FalhaDeEntradaPorSenha.class, () -> assistente.repararAChaveComSenha(lab, senha(SENHA_DO_SERVIDOR), x -> { }));
            assertEquals(Motivo.SEM_LOGIN_POR_CHAVE, f.motivo(), "tentativa " + i);
            assertTrue(f.getMessage().contains("A chave já foi gravada em ~/.ssh/authorized_keys do servidor; o app não a removeu."), f.getMessage());
            int agoraPedidos = pedidosEstabilizados();
            if (i == 1) {
                porCiclo = agoraPedidos - base;
                assertTrue(porCiclo >= 2, "cada clique é um login por senha e uma chave recusada: " + porCiclo);
            }
            assertEquals(base + i * porCiclo, agoraPedidos, "cada tentativa gasta o mesmo número de autenticações");
            assertEquals(4 - i, limite.restantes(LimiteDeTentativas.chave("127.0.0.1", ssh.porta(), USUARIO)), "a prova que falha conta no freio e não zera");
        }
        assertEquals(4, ssh.senhasVistas.size());

        // a quinta, mesmo com a senha certa, nem sai do app: o servidor recebeu 4 senhas e 4 chaves recusadas, não mais
        int antes = ssh.pedidosDeAutenticacao.get();
        FalhaDeEntradaPorSenha pausa = assertThrows(FalhaDeEntradaPorSenha.class, () -> assistente.repararAChaveComSenha(lab, senha(SENHA_DO_SERVIDOR), x -> { }));
        assertEquals(Motivo.MUITAS_TENTATIVAS, pausa.motivo());
        assertEquals(antes, ssh.pedidosDeAutenticacao.get());
        assertEquals(4, ssh.senhasVistas.size());

        // quando o servidor passa a honrar a chave, a prova passa e SÓ então a conta zera
        shell.honrarAuthorizedKeys(true);
        agora = agora.plus(Duration.ofMinutes(11));
        assertEquals(InstaladorDeChave.Resultado.JA_EXISTIA, assistente.repararAChaveComSenha(lab, senha(SENHA_DO_SERVIDOR), x -> { }));
        assertEquals(4, limite.restantes(LimiteDeTentativas.chave("127.0.0.1", ssh.porta(), USUARIO)));
    }

    @Test
    void hostDaProvaDiferenteDizQueAChaveJaFoiInstaladaENaoContaNoFreio() throws Exception {
        InfoChave chave = assistente.gerarChave("lab");
        try (ProxyTcp proxy = new ProxyTcp(ssh.porta()); ServidorSshDeTeste outro = ServidorSshDeTeste.ed25519()) {
            Pin pin = assistente.sondar("127.0.0.1", proxy.porta()).pin();
            ServidorLab lab = assistente.cadastrar(new Dados("lab", "127.0.0.1", proxy.porta(), USUARIO, agente.porta()), chave.id(), pin);
            char[] digitada = senha(SENHA_DO_SERVIDOR);

            // entre a senha e a prova o endereço passa a levar a OUTRA máquina
            FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class, () -> assistente.repararAChaveComSenha(lab, digitada, etapa -> {
                if (etapa == EtapaDoPrimeiroAcesso.PROVANDO) {
                    proxy.trocarDestino(outro.porta());
                }
            }));

            assertEquals(Motivo.HOST_DA_PROVA_DIFERENTE, e.motivo());
            assertEquals(AssistenteDeServidor.TEXTO_DO_HOST_DA_PROVA_DIFERENTE, e.getMessage());
            assertTrue(e.getMessage().startsWith("A chave foi instalada, mas o servidor da prova não é o mesmo que você confirmou"), e.getMessage());
            assertFalse(e.getMessage().contains("NÃO foi enviada"), "aqui a senha FOI enviada, ao servidor confirmado");
            exigirZerada(digitada);
            assertEquals(List.of(SENHA_DO_SERVIDOR), ssh.senhasVistas, "a senha foi ao servidor confirmado");
            assertTrue(Files.readString(shell.authorizedKeys()).contains(chave.publica().split(" ")[1]), "e a chave ficou nele");
            assertEquals(0, outro.pedidosDeAutenticacao.get(), "a outra máquina nunca viu pedido de autenticação, nem senha");
            assertEquals(List.of(), outro.senhasVistas);
            assertEquals(List.of(pin), cofre.servidor(lab.id()).orElseThrow().pins(), "o app não trocou a impressão guardada");
            assertEquals(4, limite.restantes(LimiteDeTentativas.chave("127.0.0.1", proxy.porta(), USUARIO)), "um host diferente não é senha errada nem chave recusada: não conta");
        }
    }

    @Test
    void oFreioSobreviveATrocarOAssistenteEIssoEComoOAppBloqueiaEDesbloqueiaOCofre() throws Exception {
        ServidorLab lab = servidorRecusado();
        assertEquals(Motivo.SENHA_INCORRETA, errar(lab, "errada-1").motivo());
        assertEquals(Motivo.SENHA_INCORRETA, errar(lab, "errada-2").motivo());
        // o cofre travou e destravou: o app cria um assistente NOVO, mas passa o mesmo freio
        AssistenteDeServidor novo = new AssistenteDeServidor(cofre, gerenciador, ConfigSsh.rapida(), limite);
        for (String senha : List.of("errada-3", "errada-4")) {
            agora = agora.plus(Duration.ofSeconds(20));
            assertThrows(FalhaDeEntradaPorSenha.class, () -> novo.repararAChaveComSenha(lab, senha(senha), x -> { }));
        }
        agora = agora.plus(Duration.ofSeconds(20));
        FalhaDeEntradaPorSenha pausa = assertThrows(FalhaDeEntradaPorSenha.class, () -> novo.repararAChaveComSenha(lab, senha(SENHA_DO_SERVIDOR), x -> { }));
        assertEquals(Motivo.MUITAS_TENTATIVAS, pausa.motivo(), "a conta continuou: o assistente novo não deu mais 4 tentativas");
        assertEquals(4, ssh.senhasVistas.size());
        // e o freio padrão é UM objeto por pedido, para quem o guarda (a Aplicacao) o passar adiante
        assertTrue(AssistenteDeServidor.novoFreioDeSenhas() != AssistenteDeServidor.novoFreioDeSenhas());
    }

    @Test
    void doisServidoresComOMesmoEnderecoEUsuarioComMaiusculaDividemAMesmaConta() throws Exception {
        ServidorLab primeiro = servidorRecusado();
        InfoChave outraChave = assistente.gerarChave("outra");
        Pin pin = cofre.servidor(primeiro.id()).orElseThrow().pins().get(0);
        ServidorLab segundo = assistente.cadastrar(new Dados("lab2", "127.0.0.1", ssh.porta(), "LABUSER", agente.porta()), outraChave.id(), pin);
        assertEquals(2, cofre.servidores().size());
        errar(primeiro, "errada-1");
        agora = agora.plus(Duration.ofSeconds(20));
        assertThrows(FalhaDeEntradaPorSenha.class, () -> assistente.repararAChaveComSenha(segundo, senha("errada-2"), x -> { }));
        errar(primeiro, "errada-3");
        agora = agora.plus(Duration.ofSeconds(20));
        assertThrows(FalhaDeEntradaPorSenha.class, () -> assistente.repararAChaveComSenha(segundo, senha("errada-4"), x -> { }));
        FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class, () -> assistente.repararAChaveComSenha(segundo, senha(SENHA_DO_SERVIDOR), x -> { }));
        assertEquals(Motivo.MUITAS_TENTATIVAS, e.motivo(), "dois cadastros do mesmo servidor e usuário (maiúsculas à parte) dividem o freio");
        FalhaDeEntradaPorSenha daOutra = assertThrows(FalhaDeEntradaPorSenha.class, () -> assistente.repararAChaveComSenha(primeiro, senha(SENHA_DO_SERVIDOR), x -> { }));
        assertEquals(Motivo.MUITAS_TENTATIVAS, daOutra.motivo());
        assertEquals(4, ssh.senhasVistas.size());
    }

    @Test
    void senhaVaziaOuNulaNaoAbreConexaoEAindaNaoMexeEmNada() throws Exception {
        ServidorLab lab = servidorRecusado();
        int pedidos = pedidosEstabilizados();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> assistente.repararAChaveComSenha(lab, new char[0], x -> { }));
        assertEquals("Digite a senha do usuário labuser no servidor.", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> assistente.repararAChaveComSenha(lab, null, x -> { }));
        assertEquals(pedidos, pedidosEstabilizados(), "nenhuma conexão para uma senha vazia");
        assertEquals(List.of(), ssh.senhasVistas);
        assertEquals(4, limite.restantes(LimiteDeTentativas.chave("127.0.0.1", ssh.porta(), USUARIO)));
    }

    @Test
    void authorizedKeysQueNaoSeEscreveDizQueAChavePodeTerFicadoPelaMetadeENadaMaisEMexido() throws Exception {
        ServidorLab lab = servidorRecusado();
        Path ssh = Files.createDirectories(tmp.resolve("home").resolve(".ssh"));
        Files.createDirectory(ssh.resolve("authorized_keys")); // um diretório no lugar do arquivo: a gravação da linha falha (saída 6)
        char[] digitada = senha(SENHA_DO_SERVIDOR);

        FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class, () -> assistente.repararAChaveComSenha(lab, digitada, x -> { }));

        assertEquals(Motivo.FALHOU_AO_INSTALAR, e.motivo());
        assertTrue(e.getMessage().contains("não consegui gravar a linha no authorized_keys") && e.getMessage().contains("só em parte"), e.getMessage());
        exigirZerada(digitada);
        assertEquals(List.of(ComandosDoAcesso.instalarChave()), shell.recebidos, "só a instalação rodou: nada de prova, de token nem de limpeza");
        assertEquals(EstadoConexao.ERRO_AUTENTICACAO, lab.estado().conexao());
    }

    @Test
    void execNegadoNoServidorFalhaComFraseSimplesSemUsarSftpENadaMaisEMexido() throws Exception {
        ServidorLab lab = servidorRecusado();
        ssh.comandoRemoto((linha, stdin, saida) -> 126); // o servidor não deixa rodar o comando (shell restrito, ForceCommand...); SFTP nem está ligado neste servidor
        char[] digitada = senha(SENHA_DO_SERVIDOR);

        FalhaDeEntradaPorSenha e = assertThrows(FalhaDeEntradaPorSenha.class, () -> assistente.repararAChaveComSenha(lab, digitada, x -> { }));

        assertEquals(Motivo.FALHOU_AO_INSTALAR, e.motivo());
        assertTrue(e.getMessage().contains("código 126") && e.getMessage().contains("Confira o authorized_keys"), e.getMessage());
        exigirZerada(digitada);
        assertEquals(List.of(SENHA_DO_SERVIDOR), ssh.senhasVistas);
        assertFalse(Files.exists(shell.authorizedKeys()));
        assertEquals(EstadoConexao.ERRO_AUTENTICACAO, lab.estado().conexao());
    }

    @Test
    void servidorLocalEServidorSemChaveNoCofreNaoTemReparo() throws Exception {
        ServidorLab local = assistente.cadastrarLocal("Este computador", 7071);
        assertTrue(assistente.acessoDo(local).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> assistente.repararAChaveComSenha(local, senha("x"), x -> { }));
    }

    @Test
    void asEtapasOsErrosOsToStringENoLogNuncaCarregamASenha() throws Exception {
        PrintStream erroOriginal = System.err;
        ByteArrayOutputStream capturado = new ByteArrayOutputStream();
        System.setErr(new PrintStream(capturado, true, StandardCharsets.UTF_8));
        StringBuilder tudo = new StringBuilder();
        String distinta = "senha-DISTINTA-do-reparo-9Qx";
        try {
            ssh.aceitarSenha(USUARIO, "a-senha-certa-do-servidor");
            ServidorLab lab = servidorRecusado();
            // senha errada
            FalhaDeEntradaPorSenha errada = errar(lab, distinta);
            tudo.append(errada.getMessage()).append(errada).append(stack(errada));
            // host que mudou
            try (ServidorSshDeTeste outro = ServidorSshDeTeste.ed25519()) {
                InfoChave chave = assistente.acessoDo(lab).orElseThrow().chave();
                ServidorLab doOutro = assistente.cadastrar(new Dados("outro", "127.0.0.1", ssh.porta(), "x" + USUARIO, agente.porta()), chave.id(), outro.pin());
                FalhaDeEntradaPorSenha mudou = assertThrows(FalhaDeEntradaPorSenha.class,
                    () -> assistente.repararAChaveComSenha(doOutro, senha(distinta), e -> tudo.append(e.rotulo()).append(e)));
                tudo.append(mudou.getMessage()).append(mudou).append(stack(mudou));
            }
            // o caminho que dá certo, com a mesma senha distinta
            ssh.aceitarSenha(USUARIO, distinta);
            assistente.repararAChaveComSenha(lab, senha(distinta), e -> tudo.append(e.rotulo()).append(e));
            Espera.ate(PRAZO, "conectado", () -> lab.estado().conexao() == EstadoConexao.CONECTADO);
            tudo.append(lab.estado()).append(cofre.servidor(lab.id()).orElseThrow()).append(lab.config());
            assertNotNull(lab.estado().falha() == null ? "ok" : lab.estado().falha().mensagem());
        } finally {
            System.setErr(erroOriginal);
        }
        String log = capturado.toString(StandardCharsets.UTF_8);
        assertFalse(log.contains(distinta), "o log não pode ter a senha");
        assertFalse(tudo.toString().contains(distinta), "mensagem, toString, pilha, etapas e estado não carregam a senha");
        String arquivos = Files.walk(tmp.resolve("dados")).filter(Files::isRegularFile).map(p -> {
            try {
                return new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
            } catch (java.io.IOException e) {
                return "";
            }
        }).reduce("", String::concat);
        assertFalse(arquivos.contains(distinta), "a senha nunca vai ao disco");
    }

    private static String stack(Throwable t) {
        StringWriter w = new StringWriter();
        t.printStackTrace(new PrintWriter(w));
        return w.toString();
    }

    @Test
    void salvarOEditarSemMudarNadaNaoCorrompeOCadastroNemApagaAImpressaoNemOToken() throws Exception {
        ServidorLab lab = servidorRecusado();
        ConfigServidor antes = cofre.servidor(lab.id()).orElseThrow();

        GerenciadorServidores.ResultadoDaEdicao r = gerenciador.atualizar(lab.id(), dados());

        assertFalse(r.mudouAlgo(), "dados iguais não mudam nada");
        assertFalse(r.impressaoApagada());
        assertEquals(antes, cofre.servidor(lab.id()).orElseThrow(), "o cadastro, com impressão digital e chave, ficou idêntico");
        assertEquals(EstadoConexao.ERRO_AUTENTICACAO, lab.estado().conexao(), "não reconectou à toa");
        // o mesmo endereço escrito com maiúsculas e espaços é o mesmo endereço: nada muda e a impressão fica
        GerenciadorServidores.ResultadoDaEdicao igual = gerenciador.atualizar(lab.id(), new Dados("lab", " 127.0.0.1 ", ssh.porta(), USUARIO, agente.porta()));
        assertFalse(igual.mudouAlgo());
        assertEquals(antes.pins(), cofre.servidor(lab.id()).orElseThrow().pins());
    }
}
