package br.com.labdesktop.servidor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.AcessoAoCofre;
import br.com.labdesktop.cofre.ChaveSsh;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.CofreException;
import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.cofre.InfoChave;
import br.com.labdesktop.cofre.ImportadorDeChaves;
import br.com.labdesktop.cofre.GeradorDeChaves;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.cofre.TokenAgente;
import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.servidor.AssistenteDeServidor.Dados;
import br.com.labdesktop.servidor.GerenciadorServidores.OpcoesDeRemocao;
import br.com.labdesktop.servidor.GerenciadorServidores.ResultadoDaEdicao;
import br.com.labdesktop.servidor.GerenciadorServidores.ResultadoDaRemocao;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.Falha;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.util.CaminhosApp;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Editar e remover servidor no domínio: cofre de verdade (KDF mínimo), {@code SshServer} embutido e agente falso, sem
 * janela. Cobre a regra do pin (mudar o endereço apaga a impressão digital, mudar só o nome não), a remoção coordenada
 * (cofre mais conexão, chave compartilhada, revogação no servidor, falha do cofre) e o servidor que já não existe.
 */
class EdicaoERemocaoDeServidorTest {

    private static final char[] SENHA = "uma-senha-mestra-de-teste".toCharArray();
    private static final Duration PRAZO = Duration.ofSeconds(25);

    @TempDir
    Path tmp;

    private ServidorSshDeTeste ssh;
    private AgenteDoLab agente;
    private CofreDeChaves cofre;
    private GerenciadorServidores gerenciador;
    /** O que o servidor de teste recebeu para o comando de tirar a chave do authorized_keys (o resto vai ao agente falso). */
    private final List<String> comandosDeChave = new CopyOnWriteArrayList<>();
    private volatile int statusDoComandoDeChave = 0;
    private final List<PublicKey> chavesAceitas = new ArrayList<>();

    @BeforeEach
    void montar() throws Exception {
        ssh = ServidorSshDeTeste.ed25519();
        agente = new AgenteDoLab();
        ServidorSshDeTeste.Manipulador doAgente = agente.comandosPorSsh();
        ssh.comandoRemoto((linha, stdin, saida) -> {
            if (linha.startsWith("sh -c")) { // tirar a chave do authorized_keys
                comandosDeChave.add(stdin);
                return statusDoComandoDeChave;
            }
            return doAgente.executar(linha, stdin, saida);
        });
        cofre = CofreDeChaves.criar(CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs")), SENHA.clone(), ParametrosKdf.MINIMO);
        gerenciador = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida());
    }

    @AfterEach
    void desmontar() throws Exception {
        gerenciador.fechar().join();
        cofre.close();
        agente.close();
        ssh.close();
    }

    private ChaveSsh novaChave(String nome) throws Exception {
        ChaveSsh chave = GeradorDeChaves.gerar(nome, "mac");
        cofre.adicionarChave(chave);
        chavesAceitas.add(chave.paraKeyPair().getPublic());
        ssh.aceitar(chavesAceitas.toArray(PublicKey[]::new));
        return chave;
    }

    /** Uma chave que veio de um ARQUIVO do usuário (o "importar" do assistente), com o texto que o usuário tem em disco. */
    private ChaveSsh novaChaveImportada(String nome, byte[] arquivo) throws Exception {
        ChaveSsh chave = ImportadorDeChaves.importar(arquivo, null, nome, "mac");
        cofre.adicionarChave(chave);
        chavesAceitas.add(chave.paraKeyPair().getPublic());
        ssh.aceitar(chavesAceitas.toArray(PublicKey[]::new));
        return chave;
    }

    /** O conteúdo de um {@code id_ed25519} qualquer, como o OpenSSH o escreve. */
    private static byte[] arquivoDeChaveDoDono() throws Exception {
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(KeyUtils.generateKeyPair("ssh-ed25519", 256), "usuario@notebook", null, saida);
        return saida.toByteArray();
    }

    /** Cadastra no cofre (com pin, token e tudo) e passa a acompanhar; ainda parado. */
    private ServidorLab servidor(String nome, ChaveSsh chave) throws Exception {
        TokenAgente token = TokenAgente.gerar();
        String dispositivo = "mac-" + nome;
        agente.tokens.put(token.hashSha256Hex(), dispositivo);
        ConfigServidor cfg = new ConfigServidor(ConfigServidor.novoId(), nome, "127.0.0.1", ssh.porta(), "labuser", chave.id(),
            agente.porta(), List.of(ssh.pin()), Transporte.DIRETO, dispositivo, Estado2fa.DESLIGADO);
        cofre.adicionarServidor(cfg, token);
        return gerenciador.adicionar(cfg.id());
    }

    private void conectar(ServidorLab lab) {
        lab.iniciar();
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.CONECTADO);
    }

    private Dados dados(ServidorLab lab, String nome, String host, int porta, String usuario) {
        return new Dados(nome, host, porta, usuario, lab.config().portaAgente());
    }

    // ---------------------------------------------------------------------------------------------
    // Editar
    // ---------------------------------------------------------------------------------------------

    @Test
    void editarOEnderecoApagaOPinAvisaEReconectaParandoEmConfirmarServidor() throws Exception {
        ServidorLab lab = servidor("lab", novaChave("k"));
        conectar(lab);
        assertEquals(1, cofre.servidor(lab.id()).orElseThrow().pins().size());

        ResultadoDaEdicao r = gerenciador.atualizar(lab.id(), dados(lab, "lab", "localhost", ssh.porta(), "labuser"));

        assertTrue(r.mudouAlgo());
        assertTrue(r.impressaoApagada(), "mudou o endereço: a impressão digital guardada foi apagada");
        assertTrue(cofre.servidor(lab.id()).orElseThrow().pins().isEmpty());
        assertEquals("localhost", cofre.servidor(lab.id()).orElseThrow().host(), "persistido no cofre");
        Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.ERRO_CHAVE_HOST);
        assertEquals(Falha.Tipo.PIN_AUSENTE, lab.estado().falha().tipo(), "pede para confirmar a impressão de novo");
        assertTrue(lab.tunel().isEmpty(), "a conexão antiga foi jogada fora");
    }

    @Test
    void editarAPortaDoSshTambemApagaOPin() throws Exception {
        ServidorLab lab = servidor("lab", novaChave("k"));

        ResultadoDaEdicao r = gerenciador.atualizar(lab.id(), dados(lab, "lab", "127.0.0.1", ssh.porta() + 1, "labuser"));

        assertTrue(r.impressaoApagada());
        assertTrue(cofre.servidor(lab.id()).orElseThrow().pins().isEmpty());
        assertEquals(ssh.porta() + 1, cofre.servidor(lab.id()).orElseThrow().porta());
    }

    @Test
    void editarSoONomeNaoApagaOPinMostraONomeNovoEReconectaComOsMesmosDados() throws Exception {
        ServidorLab lab = servidor("lab", novaChave("k"));
        conectar(lab);
        int autenticacoesAntes = ssh.autenticacoesAceitas.get();
        long geracaoAntes = lab.estado().geracao();

        ResultadoDaEdicao r = gerenciador.atualizar(lab.id(), dados(lab, "lab da sala", "127.0.0.1", ssh.porta(), "labuser"));

        assertTrue(r.mudouAlgo());
        assertFalse(r.impressaoApagada());
        assertEquals(1, cofre.servidor(lab.id()).orElseThrow().pins().size(), "só o nome mudou: o pin continua");
        assertEquals("lab da sala", lab.estado().nome());
        assertEquals("lab da sala", lab.config().nome());
        Espera.ate(PRAZO, () -> "reconectou, estado=" + lab.estado(),
            () -> lab.estado().conexao() == EstadoConexao.CONECTADO && lab.estado().geracao() > geracaoAntes);
        assertTrue(ssh.autenticacoesAceitas.get() > autenticacoesAntes, "abriu uma sessão SSH nova");
        assertEquals("mac-lab", cofre.servidor(lab.id()).orElseThrow().nomeDoDispositivo(), "o token continua o mesmo");
    }

    @Test
    void editarOUsuarioMantemOPinEReconectaComOUsuarioNovo() throws Exception {
        ServidorLab lab = servidor("lab", novaChave("k"));

        ResultadoDaEdicao r = gerenciador.atualizar(lab.id(), dados(lab, "lab", "127.0.0.1", ssh.porta(), "outrousuario"));

        assertFalse(r.impressaoApagada());
        assertEquals("outrousuario", cofre.servidor(lab.id()).orElseThrow().usuario());
        assertEquals(1, cofre.servidor(lab.id()).orElseThrow().pins().size());
    }

    @Test
    void editarSemMudarNadaNaoGravaNemReconecta() throws Exception {
        ServidorLab lab = servidor("lab", novaChave("k"));
        conectar(lab);
        int autenticacoesAntes = ssh.autenticacoesAceitas.get();

        ResultadoDaEdicao r = gerenciador.atualizar(lab.id(), dados(lab, "lab", "127.0.0.1", ssh.porta(), "labuser"));

        assertFalse(r.mudouAlgo());
        assertFalse(r.impressaoApagada());
        Espera.naoAcontece(Duration.ofMillis(700), "reconexão sem mudança", () -> ssh.autenticacoesAceitas.get() != autenticacoesAntes);
        assertEquals(EstadoConexao.CONECTADO, lab.estado().conexao());
    }

    @Test
    void editarComDadosInvalidosOuServidorInexistenteRecusaSemMexerEmNada() throws Exception {
        ServidorLab lab = servidor("lab", novaChave("k"));
        ConfigServidor antes = cofre.servidor(lab.id()).orElseThrow();

        IllegalArgumentException host = assertThrows(IllegalArgumentException.class,
            () -> gerenciador.atualizar(lab.id(), dados(lab, "lab", "-oProxyCommand=x", 22, "u")));
        assertTrue(host.getMessage().contains("endereço"), host.getMessage());
        assertThrows(IllegalArgumentException.class, () -> gerenciador.atualizar(lab.id(), dados(lab, "", "h", 22, "u")));
        assertThrows(IllegalArgumentException.class, () -> gerenciador.atualizar(lab.id(), dados(lab, "lab", "h", 0, "u")));
        IllegalArgumentException sumiu = assertThrows(IllegalArgumentException.class,
            () -> gerenciador.atualizar("nao-existe", dados(lab, "lab", "h", 22, "u")));
        assertTrue(sumiu.getMessage().contains("não está mais"), sumiu.getMessage());

        assertEquals(antes, cofre.servidor(lab.id()).orElseThrow());
    }

    // ---------------------------------------------------------------------------------------------
    // Remover
    // ---------------------------------------------------------------------------------------------

    @Test
    void removerMantemAChaveQuandoOutroServidorAUsaEOOutroSegueConectando() throws Exception {
        ChaveSsh compartilhada = novaChave("compartilhada");
        ServidorLab a = servidor("a", compartilhada);
        ServidorLab b = servidor("b", compartilhada);
        assertEquals(List.of("b"), gerenciador.outrosQueUsamAChaveDe(a.id()));

        ResultadoDaRemocao r = gerenciador.removerServidor(a.id(), new OpcoesDeRemocao(true, false));

        assertTrue(r.existia());
        assertFalse(r.chaveApagada(), "o outro servidor ainda usa a chave: ela fica");
        assertEquals(1, cofre.chaves().size());
        assertFalse(compartilhada.estaZerado());
        assertEquals(List.of(b), gerenciador.servidores());
        assertEquals(List.of(b.id()), cofre.servidores().stream().map(ConfigServidor::id).toList());
        conectar(b); // prova de que a chave dele continua inteira
        assertEquals(List.of(), gerenciador.outrosQueUsamAChaveDe(b.id()), "agora b é o único: ninguém mais usa a chave");
    }

    @Test
    void removerComApagarAChaveLivreApagaServidorTokenEChaveEZeraOsSegredos() throws Exception {
        ChaveSsh chave = novaChave("k");
        ServidorLab lab = servidor("lab", chave);
        TokenAgente token = cofre.tokenDoServidor(lab.id()).orElseThrow();

        ResultadoDaRemocao r = gerenciador.removerServidor(lab.id(), new OpcoesDeRemocao(true, false));

        assertTrue(r.existia());
        assertTrue(r.chaveApagada());
        assertTrue(cofre.chaves().isEmpty());
        assertTrue(chave.estaZerado());
        assertTrue(token.estaZerado());
    }

    @Test
    void removerSemApagarAChaveDeixaAChaveNoCofre() throws Exception {
        ChaveSsh chave = novaChave("k");
        ServidorLab lab = servidor("lab", chave);

        ResultadoDaRemocao r = gerenciador.removerServidor(lab.id(), new OpcoesDeRemocao(false, false));

        assertFalse(r.chaveApagada());
        assertEquals(1, cofre.chaves().size());
        assertFalse(chave.estaZerado());
    }

    @Test
    void removerOUltimoServidorDeixaOEstadoVazioEAvisaQuemObservaALista() throws Exception {
        ServidorLab lab = servidor("lab", novaChave("k"));
        List<List<String>> listas = new CopyOnWriteArrayList<>();
        gerenciador.observarLista(l -> listas.add(l.stream().map(ServidorLab::id).toList()));
        assertEquals(List.of(List.of(lab.id())), listas);

        gerenciador.removerServidor(lab.id(), new OpcoesDeRemocao(true, false));

        assertTrue(gerenciador.servidores().isEmpty());
        assertTrue(cofre.servidores().isEmpty());
        assertTrue(cofre.chaves().isEmpty());
        assertEquals(List.of(List.of(lab.id()), List.of()), listas, "a lista vazia chegou: as telas mostram o estado vazio");
    }

    @Test
    void removerComConexaoAtivaFechaConexaoTunelESondador() throws Exception {
        ServidorLab lab = servidor("lab", novaChave("k"));
        conectar(lab);
        assertTrue(lab.tunel().isPresent());
        assertFalse(ssh.sshd.getActiveSessions().isEmpty());

        gerenciador.removerServidor(lab.id(), new OpcoesDeRemocao(true, false));

        assertEquals(EstadoConexao.DESCONECTADO, lab.conexao().info().estado(), "a conexão SSH foi fechada");
        assertTrue(lab.tunel().isEmpty(), "o túnel foi fechado");
        Espera.ate(PRAZO, "o servidor de teste não tem mais sessão aberta", () -> ssh.sshd.getActiveSessions().isEmpty());
        int pedidos = agente.http.pedidos.size();
        lab.atualizarAgora(); // o Sondador foi fechado: nenhuma consulta nova sai
        Espera.naoAcontece(Duration.ofMillis(700), "consulta ao agente depois de remover", () -> agente.http.pedidos.size() > pedidos);
        assertTrue(gerenciador.servidor(lab.id()).isEmpty());
        assertEquals("lab", lab.config().nome(), "um retrato que ainda estava na fila da tela não estoura: vale a última config lida");
    }

    @Test
    void removerServidorInexistenteNaoQuebraENaoMudaNada() throws Exception {
        ServidorLab lab = servidor("lab", novaChave("k"));

        ResultadoDaRemocao r = gerenciador.removerServidor("nao-existe", new OpcoesDeRemocao(true, true));

        assertFalse(r.existia());
        assertFalse(r.chaveApagada());
        assertTrue(r.avisos().isEmpty());
        assertEquals(List.of(lab), gerenciador.servidores());
        assertEquals(1, cofre.servidores().size());

        gerenciador.removerServidor(lab.id(), new OpcoesDeRemocao(true, false));
        ResultadoDaRemocao segunda = gerenciador.removerServidor(lab.id(), new OpcoesDeRemocao(true, false));
        assertFalse(segunda.existia(), "remover duas vezes: a segunda não faz nada e não é erro");
    }

    @Test
    void duasEntradasComAMesmaChavePublicaContamComoUmaChaveSo() throws Exception {
        byte[] arquivo = arquivoDeChaveDoDono();
        ChaveSsh k1 = novaChaveImportada("de-teste-1", arquivo);
        ChaveSsh k2 = novaChaveImportada("de-teste-2", arquivo); // o cofre aceita: ids diferentes, mesma chave pública
        assertTrue(!k1.id().equals(k2.id()) && k1.impressao().equals(k2.impressao()));
        ServidorLab a = servidor("a", k1);
        ServidorLab b = servidor("b", k2);
        conectar(a);

        assertEquals(List.of("b"), gerenciador.outrosQueUsamAChaveDe(a.id()), "para o servidor é a mesma chave");
        assertEquals(List.of("a"), gerenciador.outrosQueUsamAChaveDe(b.id()));

        ResultadoDaRemocao r = gerenciador.removerServidor(a.id(), new OpcoesDeRemocao(true, true));

        assertTrue(r.tokenRevogado(), "o token é só de a");
        assertFalse(r.chaveTiradaDoServidor(), "tirar a chave do authorized_keys deixaria b sem acesso");
        assertTrue(comandosDeChave.isEmpty(), "o comando de tirar a chave nem foi rodado");
        assertFalse(r.chaveApagada(), "b ainda usa uma chave com a mesma chave pública: a de a fica no cofre");
        assertTrue(r.avisos().stream().anyMatch(av -> av.contains("outro servidor seu também a usa")), r.avisos().toString());
        conectar(b);
    }

    // ---------------------------------------------------------------------------------------------
    // Revogar o acesso do app no servidor
    // ---------------------------------------------------------------------------------------------

    @Test
    void revogarComConexaoApagaOTokenNoAgenteETiraAChaveDoAuthorizedKeysDepoisRemove() throws Exception {
        ChaveSsh chave = novaChave("k");
        ServidorLab lab = servidor("lab", chave);
        conectar(lab);
        assertEquals(1, agente.tokens.size());
        String blob = cofre.infoDaChave(chave.id()).orElseThrow().publica().split(" ")[1];

        ResultadoDaRemocao r = gerenciador.removerServidor(lab.id(), new OpcoesDeRemocao(true, true));

        assertTrue(r.tokenRevogado());
        assertTrue(r.chaveTiradaDoServidor());
        assertTrue(r.avisos().isEmpty(), r.avisos().toString());
        assertTrue(agente.tokens.isEmpty(), "o token deste Mac saiu do agente");
        assertEquals(List.of(blob + "\n"), comandosDeChave, "só o texto da chave foi, por stdin");
        assertTrue(cofre.servidores().isEmpty());
        assertTrue(cofre.chaves().isEmpty());
    }

    @Test
    void revogarNaoTiraAChaveDoServidorQuandoOutroServidorAUsa() throws Exception {
        ChaveSsh compartilhada = novaChave("compartilhada");
        ServidorLab a = servidor("a", compartilhada);
        servidor("b", compartilhada);
        conectar(a);

        ResultadoDaRemocao r = gerenciador.removerServidor(a.id(), new OpcoesDeRemocao(true, true));

        assertTrue(r.tokenRevogado(), "o token é só de a");
        assertFalse(r.chaveTiradaDoServidor(), "tirar a chave quebraria o outro servidor");
        assertTrue(comandosDeChave.isEmpty(), "o comando de tirar a chave nem foi rodado");
        assertEquals(1, cofre.chaves().size());
    }

    @Test
    void seARevogacaoFalharOAppAvisaERemoveMesmoAssim() throws Exception {
        ServidorLab lab = servidor("lab", novaChave("k"));
        conectar(lab);
        statusDoComandoDeChave = 2; // o servidor não conseguiu mexer no arquivo

        ResultadoDaRemocao r = gerenciador.removerServidor(lab.id(), new OpcoesDeRemocao(true, true));

        assertTrue(r.existia(), "removeu mesmo assim");
        assertTrue(r.tokenRevogado());
        assertFalse(r.chaveTiradaDoServidor());
        assertEquals(1, r.avisos().size());
        assertTrue(r.avisos().get(0).startsWith("Não consegui tirar a chave deste app do acesso do usuário labuser"), r.avisos().get(0));
        assertTrue(cofre.servidores().isEmpty());
    }

    @Test
    void seAChaveForAUnicaDoArquivoOAppDizQueElaFicouNoServidor() throws Exception {
        ServidorLab lab = servidor("lab", novaChave("k"));
        conectar(lab);
        statusDoComandoDeChave = 3;

        ResultadoDaRemocao r = gerenciador.removerServidor(lab.id(), new OpcoesDeRemocao(true, true));

        assertFalse(r.chaveTiradaDoServidor());
        assertEquals(1, r.avisos().size());
        assertTrue(r.avisos().get(0).contains("única"), r.avisos().get(0));
        assertTrue(cofre.servidores().isEmpty());
    }

    @Test
    void semConexaoNaoHaComoRevogarMasORemoverSegue() throws Exception {
        ServidorLab lab = servidor("lab", novaChave("k")); // nunca conectou: DESCONECTADO

        ResultadoDaRemocao r = gerenciador.removerServidor(lab.id(), new OpcoesDeRemocao(true, true));

        assertTrue(r.existia());
        assertFalse(r.tokenRevogado());
        assertFalse(r.chaveTiradaDoServidor());
        assertEquals(1, r.avisos().size());
        assertTrue(r.avisos().get(0).contains("não estava conectado"), r.avisos().get(0));
        assertTrue(agente.tokens.size() == 1, "o token ficou no agente: não havia como falar com ele");
        assertTrue(comandosDeChave.isEmpty());
        assertTrue(cofre.servidores().isEmpty());
    }

    @Test
    void chaveImportadaDeUmArquivoDoDonoNuncaETiradaDoServidorMasOTokenSai() throws Exception {
        ChaveSsh doDono = novaChaveImportada("de-teste", arquivoDeChaveDoDono());
        ServidorLab lab = servidor("lab", doDono);
        conectar(lab);
        assertEquals(InfoChave.Origem.IMPORTADA, gerenciador.origemDaChaveDe(lab.id()));

        ResultadoDaRemocao r = gerenciador.removerServidor(lab.id(), new OpcoesDeRemocao(true, true));

        assertTrue(r.tokenRevogado(), "o token é do app: sai do painel do servidor");
        assertFalse(r.chaveTiradaDoServidor(), "a chave é a do usuário, que ele usa no terminal: o app não mexe no authorized_keys");
        assertTrue(comandosDeChave.isEmpty(), "o comando de tirar a chave nem foi rodado");
        assertTrue(agente.tokens.isEmpty());
        assertEquals(1, r.avisos().size());
        assertTrue(r.avisos().get(0).contains("veio de um arquivo seu") && r.avisos().get(0).contains("NÃO a tirou"), r.avisos().get(0));
        assertTrue(r.chaveApagada(), "a cópia do cofre pode sair: o arquivo original do usuário não é mexido");
        assertTrue(cofre.servidores().isEmpty());
    }

    @Test
    void chaveDeOrigemDesconhecidaDeUmCofreAntigoTambemNaoETiradaDoServidor() throws Exception {
        CofreFalso falso = new CofreFalso("127.0.0.1", ssh.porta(), List.of(ssh.pin())).comPortaDoAgente(agente.porta())
            .comOrigemDaChave(InfoChave.Origem.DESCONHECIDA);
        TokenAgente token = TokenAgente.gerar();
        falso.comToken(token, "mac-de-teste");
        agente.tokens.put(token.hashSha256Hex(), "mac-de-teste");
        ssh.aceitar(falso.par().getPublic());
        try (GerenciadorServidores g = new GerenciadorServidores(falso, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida())) {
            ServidorLab lab = g.servidor(CofreFalso.ID).orElseThrow();
            lab.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + lab.estado(), () -> lab.estado().conexao() == EstadoConexao.CONECTADO);

            ResultadoDaRemocao r = g.removerServidor(CofreFalso.ID, new OpcoesDeRemocao(true, true));

            assertTrue(r.tokenRevogado());
            assertFalse(r.chaveTiradaDoServidor(), "sem saber quem criou a chave, o app não a tira do servidor");
            assertTrue(comandosDeChave.isEmpty());
            g.fechar().join();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // O cofre falha na gravação
    // ---------------------------------------------------------------------------------------------

    @Test
    void seOCofreNaoGravarARemocaoNadaFoiRevogadoNoServidorEOMesmoServidorSegueNaListaConectado() throws Exception {
        // Um cofre de verdade para o servidor viver, com a gravação de "remover" quebrada. A revogação no servidor é
        // irreversível, por isso só pode rodar DEPOIS de o cofre ter gravado: com o disco cheio, o servidor tem de seguir
        // aceitando o token e a chave que o app ainda tem.
        CofreFalso falso = new CofreFalso("127.0.0.1", ssh.porta(), List.of(ssh.pin())).comPortaDoAgente(agente.porta());
        TokenAgente token = TokenAgente.gerar();
        falso.comToken(token, "mac-de-teste");
        agente.tokens.put(token.hashSha256Hex(), "mac-de-teste");
        ssh.aceitar(falso.par().getPublic());
        AcessoAoCofre quebrado = (AcessoAoCofre) Proxy.newProxyInstance(AcessoAoCofre.class.getClassLoader(),
            new Class<?>[] {AcessoAoCofre.class}, (proxy, metodo, args) -> {
                if (metodo.getName().equals("removerServidor")) {
                    throw new CofreException("O disco está cheio.");
                }
                try {
                    return metodo.invoke(falso, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        try (GerenciadorServidores g = new GerenciadorServidores(quebrado, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida())) {
            ServidorLab original = g.servidor(CofreFalso.ID).orElseThrow();
            original.iniciar();
            Espera.ate(PRAZO, () -> "estado=" + original.estado(), () -> original.estado().conexao() == EstadoConexao.CONECTADO);

            CofreException e = assertThrows(CofreException.class,
                () -> g.removerServidor(CofreFalso.ID, new OpcoesDeRemocao(true, true)));

            assertEquals("O disco está cheio.", e.getMessage());
            assertSame(original, g.servidor(CofreFalso.ID).orElseThrow(), "o mesmo servidor, que nem chegou a sair da lista");
            assertEquals(1, agente.tokens.size(), "o token NÃO foi revogado no agente");
            assertTrue(comandosDeChave.isEmpty(), "a chave NÃO foi tirada do authorized_keys");
            assertEquals(EstadoConexao.CONECTADO, original.estado().conexao(), "e a conexão nunca caiu");
            assertEquals(1, g.servidores().size());
            assertNotNull(original.config());
            g.fechar().join();
        }
    }
}
