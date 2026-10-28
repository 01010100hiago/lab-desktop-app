package br.com.labdesktop.acesso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.acesso.DesligadorDeSenha.Avaliacao;
import br.com.labdesktop.acesso.DesligadorDeSenha.Desfecho;
import br.com.labdesktop.acesso.DesligadorDeSenha.Estado;
import br.com.labdesktop.acesso.DesligadorDeSenha.Motivo;
import br.com.labdesktop.acesso.DesligadorDeSenha.Passo;
import br.com.labdesktop.acesso.DesligadorDeSenha.Resultado;
import br.com.labdesktop.ssh.FalhaDeEntradaPorSenha;
import br.com.labdesktop.teste.SshdFalso;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Desligar o login por senha, camada de passos contra um servidor de mentira: sem sudo (só mostra o comando), sem systemd-run (não mexe), {@code sshd -t} que falha, prova da
 * chave que falha (desfaz sozinho), conferência final (geral, Match e métodos oferecidos), queda de conexão no meio ("talvez aplicado", o servidor desfaz sozinho pelo timer),
 * o app que morre, o timer cancelado só no sucesso. Nenhum comando fora das listas fixas, e a leitura nunca escreve.
 */
class DesligadorDeSenhaTest {

    private static final LocalDateTime AGORA = LocalDateTime.of(2026, 10, 28, 15, 30, 5);
    private static final String CARIMBO = "20261028-153005";

    private SshdFalso sshd;
    private final List<String> linhasDaTela = new ArrayList<>();
    private final AtomicInteger provas = new AtomicInteger();
    private volatile FalhaDeEntradaPorSenha falhaDaProva;
    private volatile int falhasDeRedeAntesDeEntrar;
    private volatile List<String> metodosOferecidos = List.of("publickey");
    private volatile Runnable aoSondar = () -> { };
    private volatile boolean interromperNaProva;

    @BeforeEach
    void montar() {
        sshd = new SshdFalso(CARIMBO);
        linhasDaTela.clear();
    }

    private DesligadorDeSenha desligador() {
        return new DesligadorDeSenha(sshd, () -> {
            provas.incrementAndGet();
            sshd.eventos.add("prova");
            if (interromperNaProva) {
                throw new InterruptedException("o app morreu");
            }
            if (falhasDeRedeAntesDeEntrar > 0) {
                falhasDeRedeAntesDeEntrar--;
                throw new FalhaDeEntradaPorSenha(FalhaDeEntradaPorSenha.Motivo.SEM_CONEXAO, "sem rede");
            }
            if (falhaDaProva != null) {
                throw falhaDaProva;
            }
        }, () -> AGORA, () -> {
            aoSondar.run();
            return metodosOferecidos;
        }, SshdFalso.USUARIO, Duration.ZERO);
    }

    private void observador(Passo p, Estado e, String frase) {
        linhasDaTela.add(p + ":" + e);
    }

    private Resultado rodarTudo() throws Exception {
        DesligadorDeSenha d = desligador();
        return d.desligar(d.avaliar(), this::observador);
    }

    @Test
    void avaliarSoLeENuncaEscreve() throws Exception {
        Avaliacao a = desligador().avaliar();

        assertTrue(a.podeDesligar(), a.bloqueio() == null ? "" : a.bloqueio().texto());
        assertEquals(DesligadorDeSenha.Permissao.SUDO_SEM_SENHA, a.permissao());
        assertEquals("sudo -n ", a.prefixo());
        assertEquals(Boolean.TRUE, a.senhaLigada());
        assertEquals(List.of(), sshd.escritas, "ler a configuração não muda nada no servidor");
        assertEquals(0, provas.get());
        assertTrue(a.achados().stream().anyMatch(l -> l.contains("Login por senha agora: ligado")), a.achados().toString());
        assertTrue(a.achados().stream().anyMatch(l -> l.contains("UsePAM: sim")), a.achados().toString());
        assertTrue(sshd.comandos.contains(ComandosDoAcesso.verificarSystemdRun()), "confere se há como armar o desfazer");
    }

    @Test
    void semRootENemSudoSemSenhaNaoTentaENuncaPedeSenhaDeSudo() throws Exception {
        sshd.sudo = false;
        DesligadorDeSenha d = desligador();
        Avaliacao a = d.avaliar();

        assertEquals(DesligadorDeSenha.Permissao.NENHUMA, a.permissao());
        assertEquals(Motivo.SEM_PERMISSAO, a.bloqueio().motivo());
        assertTrue(a.bloqueio().texto().contains("nunca pede a senha de sudo"), a.bloqueio().texto());
        assertTrue(a.bloqueio().texto().contains("conexão NOVA"), "pede para manter a sessão e testar uma conexão nova: " + a.bloqueio().texto());
        assertEquals(List.of(ComandosDoAcesso.lerPermissoes()), sshd.comandos, "só perguntou o que o usuário pode; não tentou ler nem escrever");

        Resultado r = d.desligar(a, this::observador);
        assertEquals(Desfecho.NAO_APLICADA, r.desfecho());
        assertEquals(List.of(), sshd.escritas);
        assertEquals(List.of(), linhasDaTela, "nenhum passo andou");
        assertTrue(ComandosDoAcesso.comandoParaOsDonoRodar().startsWith("sudo sh -c '"), "o comando para o usuário rodar existe e é uma linha");
    }

    @Test
    void oComandoDoDonoNaoApagaArquivoAnteriorEGuardaCopia() {
        String c = ComandosDoAcesso.comandoParaOsDonoRodar();
        assertTrue(c.contains("[ -f $f ] && cp -p $f $f.lab-desktop-$d.bak"), "guarda a versão anterior do arquivo do app: " + c);
        assertTrue(c.contains("if [ -f $f.lab-desktop-$d.bak ]; then cp -p $f.lab-desktop-$d.bak $f; else rm -f $f; fi"), "só apaga se não havia arquivo antes: " + c);
        assertTrue(c.contains("ChallengeResponseAuthentication no"), c);
        assertTrue(ComandosDoAcesso.AVISO_DO_COMANDO_MANUAL.contains("sessão") && ComandosDoAcesso.AVISO_DO_COMANDO_MANUAL.contains("NOVA"));
    }

    @Test
    void rootNaoUsaSudo() throws Exception {
        sshd.root = true;
        sshd.sudo = false;
        DesligadorDeSenha d = desligador();
        Avaliacao a = d.avaliar();
        assertTrue(a.podeDesligar());
        assertEquals("", a.prefixo());
        Resultado r = d.desligar(a, this::observador);
        assertEquals(Desfecho.DESLIGADA, r.desfecho(), r.resumo());
        assertTrue(sshd.comandos.stream().noneMatch(c -> c.contains("sudo") && !c.equals(ComandosDoAcesso.lerPermissoes())), "root não usa sudo: " + sshd.comandos);
    }

    @Test
    void sucessoNaOrdemCertaCopiaArmaGravaValidaRecarregaProvaConfereECancelaOTimer() throws Exception {
        Resultado r = rodarTudo();

        assertEquals(Desfecho.DESLIGADA, r.desfecho(), r.resumo() + r.detalhes());
        assertEquals(Boolean.FALSE, r.senhaLigadaDepois());
        assertEquals("Login por senha: desligado. O servidor só oferece a chave numa conexão nova.", r.resumo());
        assertEquals(List.of("copia-principal", "armar", "gravar", "recarregar", "prova", "cancelar"), sshd.eventos,
            "o desfazer é armado ANTES de gravar e só é cancelado depois da prova e da conferência");
        assertFalse(sshd.timerArmado, "o timer foi cancelado no sucesso");
        String conteudo = sshd.pasta.get(SshdFalso.NOSSO);
        assertTrue(conteudo.contains("PasswordAuthentication no\n") && conteudo.contains("KbdInteractiveAuthentication no\n")
            && conteudo.contains("ChallengeResponseAuthentication no\n"), conteudo);
        assertTrue(conteudo.contains("2026-10-28 15:30"), "o arquivo diz quando foi criado");
        assertEquals(1, sshd.recargas);
        assertTrue(sshd.comandos.stream().noneMatch(c -> c.contains("restart")), "recarrega, nunca reinicia");
        assertTrue(r.comandoParaReverter().contains("rm " + ComandosDoAcesso.ARQUIVO_PROPRIO), r.comandoParaReverter());
        assertEquals(List.of("COPIA:ANDANDO", "COPIA:FEITO", "ARMAR:ANDANDO", "ARMAR:FEITO", "GRAVAR:ANDANDO", "GRAVAR:FEITO", "VALIDAR:ANDANDO", "VALIDAR:FEITO",
            "RECARREGAR:ANDANDO", "RECARREGAR:FEITO", "PROVAR:ANDANDO", "PROVAR:FEITO", "CONFERIR:ANDANDO", "CONFERIR:FEITO", "CANCELAR:ANDANDO", "CANCELAR:FEITO"), linhasDaTela);
        assertTrue(r.detalhes().stream().anyMatch(l -> l.contains("cloud-init")), r.detalhes().toString());
        assertTrue(r.detalhes().stream().anyMatch(l -> l.contains("guest agent") && l.contains("OS Login")), "avisa dos agentes do provedor: " + r.detalhes());
        int validar = sshd.comandos.indexOf(ComandosDoAcesso.validarConfiguracao("sudo -n "));
        int recarregar = sshd.comandos.indexOf(ComandosDoAcesso.recarregarSshd("sudo -n "));
        int armar = sshd.comandos.indexOf(ComandosDoAcesso.armarDesfazerNoServidor("sudo -n ", CARIMBO, false));
        int gravar = sshd.comandos.indexOf(ComandosDoAcesso.gravarArquivoProprio("sudo -n "));
        assertTrue(armar > 0 && armar < gravar && gravar < validar && validar < recarregar, "armar, gravar, validar e só então recarregar");
        // a conferência leu a configuração geral, a do usuário e (pela sonda) os métodos que o servidor oferece
        assertTrue(sshd.comandos.contains(ComandosDoAcesso.lerConfiguracaoParaEsteUsuario("sudo -n ", SshdFalso.USUARIO)));
    }

    @Test
    void tecladoInterativoLigadoTambemEDesligadoEORelatorioDiz() throws Exception {
        sshd.tecladoBase = true;
        DesligadorDeSenha d = desligador();
        Avaliacao a = d.avaliar();
        assertTrue(a.achados().stream().anyMatch(l -> l.contains("Teclado interativo")), a.achados().toString());
        Resultado r = d.desligar(a, this::observador);
        assertEquals(Desfecho.DESLIGADA, r.desfecho());
        assertTrue(sshd.pasta.get(SshdFalso.NOSSO).contains("KbdInteractiveAuthentication no"));
    }

    @Test
    void jaDesligadaNaoMexeEmNada() throws Exception {
        sshd.senhaBase = false;
        DesligadorDeSenha d = desligador();
        Avaliacao a = d.avaliar();
        assertEquals(Motivo.JA_DESLIGADA, a.bloqueio().motivo());
        Resultado r = d.desligar(a, this::observador);
        assertEquals(Desfecho.JA_ESTAVA_DESLIGADA, r.desfecho());
        assertEquals(List.of(), sshd.escritas);
    }

    @Test
    void metodosCombinadosNaoSaoTocadosPorqueTrancariamTodoMundo() throws Exception {
        sshd.metodos = "publickey,keyboard-interactive";
        Avaliacao a = desligador().avaliar();
        assertEquals(Motivo.METODOS_COMBINADOS, a.bloqueio().motivo());
        assertTrue(a.bloqueio().texto().contains("publickey,keyboard-interactive"), a.bloqueio().texto());
        assertEquals(Desfecho.NAO_APLICADA, desligador().desligar(a, this::observador).desfecho());
        assertEquals(List.of(), sshd.escritas);
    }

    @Test
    void soPublickeyComoMetodoEstaBem() throws Exception {
        sshd.metodos = "publickey";
        assertTrue(desligador().avaliar().podeDesligar());
    }

    @Test
    void loginPorChaveDesligadoNoServidorRecusaParaNaoTrancarODono() throws Exception {
        sshd.chavePermitida = false;
        Avaliacao a = desligador().avaliar();
        assertEquals(Motivo.CHAVE_DESLIGADA, a.bloqueio().motivo());
        assertEquals(List.of(), sshd.escritas);
    }

    @Test
    void arquivoQueVemAntesDefineSenhaDetectaEAvisaESemEscrever() throws Exception {
        sshd.pasta.put("01-provedor.conf", "PasswordAuthentication yes\n");
        DesligadorDeSenha d = desligador();
        Avaliacao a = d.avaliar();

        assertEquals(Motivo.ARQUIVO_ANTERIOR, a.bloqueio().motivo());
        assertTrue(a.bloqueio().texto().contains("01-provedor.conf"), a.bloqueio().texto());
        assertTrue(a.bloqueio().texto().contains("o primeiro valor"), a.bloqueio().texto());
        assertEquals(Desfecho.NAO_APLICADA, d.desligar(a, this::observador).desfecho());
        assertEquals(List.of(), sshd.escritas, "não mexe em arquivo que não é dele nem grava um que não valeria");
    }

    @Test
    void arquivoDeNuvemQueVemDepoisNaoAtrapalhaERelata() throws Exception {
        sshd.pasta.put("50-cloud-init.conf", "PasswordAuthentication yes\n");
        DesligadorDeSenha d = desligador();
        Avaliacao a = d.avaliar();
        assertTrue(a.podeDesligar(), a.bloqueio() == null ? "" : a.bloqueio().texto());
        assertTrue(a.achados().stream().anyMatch(l -> l.contains("50-cloud-init.conf")), "relata o que encontrou: " + a.achados());
        assertEquals(Desfecho.DESLIGADA, d.desligar(a, this::observador).desfecho());
    }

    @Test
    void semIncludeDaPastaOuSemAPastaNaoEscreve() throws Exception {
        sshd.principal = "Port 22\nPasswordAuthentication yes\n";
        Avaliacao a = desligador().avaliar();
        assertEquals(Motivo.NAO_LE_A_PASTA, a.bloqueio().motivo());
        sshd.principal = "Include /etc/ssh/sshd_config.d/*.conf\nPasswordAuthentication yes\n";
        sshd.pastaExiste = false;
        assertEquals(Motivo.NAO_LE_A_PASTA, desligador().avaliar().bloqueio().motivo());
        assertEquals(List.of(), sshd.escritas);
    }

    @Test
    void includeComAspasEComVariosGlobsTambemEEntendido() throws Exception {
        sshd.principal = "Include \"/etc/ssh/outra/*.conf\" \"/etc/ssh/sshd_config.d/*.conf\"\nPasswordAuthentication yes\n";
        assertTrue(desligador().avaliar().podeDesligar());
    }

    @Test
    void servidorSemSystemdRunNaoMexeEDizPorque() throws Exception {
        sshd.systemdRun = false;
        DesligadorDeSenha d = desligador();
        Avaliacao a = d.avaliar();
        assertEquals(Motivo.SEM_DESFAZER_NO_SERVIDOR, a.bloqueio().motivo());
        assertTrue(a.bloqueio().texto().contains("desfazer automático") && a.bloqueio().texto().contains("Nada foi alterado"), a.bloqueio().texto());
        assertEquals(Desfecho.NAO_APLICADA, d.desligar(a, this::observador).desfecho());
        assertEquals(List.of(), sshd.escritas);
    }

    @Test
    void blocoMatchViraAvisoENaoBloqueio() throws Exception {
        sshd.principal += "Match Address 10.0.0.0/8\n  PasswordAuthentication yes\n";
        Avaliacao a = desligador().avaliar();
        assertTrue(a.podeDesligar());
        assertTrue(a.avisos().stream().anyMatch(l -> l.contains("bloco Match")), a.avisos().toString());
    }

    @Test
    void naoConseguirArmarODesfazerNaoMexeEmNada() throws Exception {
        sshd.armarFalha = true;
        Resultado r = rodarTudo();
        assertEquals(Desfecho.FALHOU_SEM_EFEITO, r.desfecho());
        assertTrue(r.resumo().contains("desfazer automático"), r.resumo());
        assertFalse(sshd.eventos.contains("gravar"), "sem o desfazer armado nada é gravado");
        assertFalse(sshd.temArquivoNosso());
    }

    @Test
    void sshdTFalhandoNadaEhRecarregadoEOArquivoEDesfeitoEOTimerCancelado() throws Exception {
        sshd.validacaoFalha = true;
        Resultado r = rodarTudo();

        assertEquals(Desfecho.FALHOU_SEM_EFEITO, r.desfecho());
        assertEquals(0, sshd.recargas, "o sshd nem foi tocado");
        assertFalse(sshd.temArquivoNosso(), "o arquivo foi desfeito");
        assertFalse(sshd.timerArmado, "o timer foi cancelado");
        assertEquals(0, provas.get(), "sem recarga não há o que provar");
        assertTrue(r.resumo().contains("não aceitou a configuração"), r.resumo());
        assertTrue(r.detalhes().stream().anyMatch(l -> l.contains("Bad configuration option")), r.detalhes().toString());
        assertTrue(linhasDaTela.contains("VALIDAR:FALHOU") && linhasDaTela.contains("GRAVAR:DESFEITO"), linhasDaTela.toString());
        assertFalse(linhasDaTela.contains("RECARREGAR:ANDANDO"));
    }

    @Test
    void recargaQueFalhaDesfazOArquivoESemProvar() throws Exception {
        sshd.recarregarFalha = true;
        Resultado r = rodarTudo();
        assertEquals(Desfecho.FALHOU_SEM_EFEITO, r.desfecho());
        assertFalse(sshd.temArquivoNosso());
        assertEquals(0, provas.get());
        assertTrue(r.resumo().contains("recarregar"), r.resumo());
    }

    @Test
    void provaDaChaveQueFalhaDesfazSozinhoERecarregaDeNovoEConfere() throws Exception {
        falhaDaProva = new FalhaDeEntradaPorSenha(FalhaDeEntradaPorSenha.Motivo.SEM_LOGIN_POR_CHAVE, "O servidor não aceitou a chave.");
        Resultado r = rodarTudo();

        assertEquals(Desfecho.DESFEITA, r.desfecho(), r.resumo());
        assertFalse(sshd.temArquivoNosso(), "o arquivo que o app criou foi apagado");
        assertEquals(2, sshd.recargas, "recarregou ao aplicar e de novo ao desfazer");
        assertEquals(List.of("copia-principal", "armar", "gravar", "recarregar", "prova", "apagar", "recarregar", "cancelar"), sshd.eventos);
        assertEquals(1, provas.get(), "chave RECUSADA é resposta, não azar de rede: uma tentativa só");
        assertTrue(r.resumo().contains("DESFEZ") && r.resumo().contains("NÃO entrou"), r.resumo());
        assertTrue(r.detalhes().stream().anyMatch(l -> l.contains("Conferi: o servidor voltou a aceitar senha")), r.detalhes().toString());
        assertTrue(linhasDaTela.contains("PROVAR:FALHOU") && linhasDaTela.contains("GRAVAR:DESFEITO"), linhasDaTela.toString());
        int validarDoDesfazer = sshd.comandos.lastIndexOf(ComandosDoAcesso.validarConfiguracao("sudo -n "));
        assertTrue(validarDoDesfazer > sshd.comandos.indexOf(ComandosDoAcesso.recarregarSshd("sudo -n ")), "confere a sintaxe antes da recarga do desfazer");
    }

    @Test
    void aProvaTentaDeNovoSoQuandoEFalhaDeRede() throws Exception {
        falhasDeRedeAntesDeEntrar = 2;
        Resultado r = rodarTudo();
        assertEquals(Desfecho.DESLIGADA, r.desfecho(), "o sshd acabou de recarregar: a 3ª tentativa entrou");
        assertEquals(3, provas.get());

        provas.set(0);
        sshd = new SshdFalso(CARIMBO);
        falhasDeRedeAntesDeEntrar = 5;
        Resultado desfeito = rodarTudo();
        assertEquals(Desfecho.DESFEITA, desfeito.desfecho());
        assertEquals(3, provas.get(), "no máximo 3 tentativas");
    }

    @Test
    void conferenciaFinalQueAindaVeASenhaLigadaTambemDesfaz() throws Exception {
        sshd.ignoraONosso = true;
        Resultado r = rodarTudo();
        assertEquals(Desfecho.DESFEITA, r.desfecho());
        assertTrue(r.resumo().contains("continua aceitando senha"), r.resumo());
        assertFalse(sshd.temArquivoNosso());
        assertFalse(sshd.timerArmado);
    }

    @Test
    void servidorQueAindaOfereceSenhaNumaConexaoNovaDesfazMesmoComASshdTDizendoNao() throws Exception {
        metodosOferecidos = List.of("publickey", "password");
        Resultado r = rodarTudo();
        assertEquals(Desfecho.DESFEITA, r.desfecho());
        assertTrue(r.resumo().contains("continua oferecendo senha numa conexão nova"), r.resumo());
        assertFalse(sshd.temArquivoNosso());
    }

    @Test
    void semComoConferirNuncaAfirmaQueASenhaFoiDesligada() throws Exception {
        metodosOferecidos = null; // a conexão nova de conferência não deu
        sshd.leituraParaUsuarioFalha = true;
        Resultado r = rodarTudo();

        assertEquals(Desfecho.SEM_CONFERENCIA, r.desfecho());
        assertTrue(r.resumo().contains("NÃO consegui conferir se a senha está mesmo desligada"), r.resumo());
        assertFalse(r.resumo().contains("Login por senha: desligado"), "não afirma: " + r.resumo());
        assertTrue(r.detalhes().stream().anyMatch(l -> l.contains("Match")), "diz que o Match não foi conferido: " + r.detalhes());
        assertTrue(sshd.temArquivoNosso(), "a prova da chave passou: o arquivo fica");
        assertFalse(sshd.timerArmado);
    }

    @Test
    void blocoMatchQueAindaAceitaSenhaParaEsteUsuarioViraParcialSemDesfazer() throws Exception {
        sshd.matchLigaAsenhaParaEsteUsuario = true;
        Resultado r = rodarTudo();
        assertEquals(Desfecho.PARCIAL, r.desfecho());
        assertTrue(r.resumo().contains("Match ainda aceita senha"), r.resumo());
        assertTrue(sshd.temArquivoNosso());
        assertFalse(sshd.timerArmado);
    }

    @Test
    void seODesfazerNaoConseguirDizOComandoParaODonoRodar() throws Exception {
        falhaDaProva = new FalhaDeEntradaPorSenha(FalhaDeEntradaPorSenha.Motivo.SEM_LOGIN_POR_CHAVE, "não entrou");
        sshd.apagarFalha = true;
        Resultado r = rodarTudo();
        assertEquals(Desfecho.PRECISA_DE_AJUDA, r.desfecho());
        assertTrue(r.resumo().contains("NÃO conseguiu desfazer"), r.resumo());
        assertTrue(r.detalhes().stream().anyMatch(l -> l.contains("rm " + ComandosDoAcesso.ARQUIVO_PROPRIO)), r.detalhes().toString());
        assertTrue(r.detalhes().stream().anyMatch(l -> l.contains("desfazer automático do servidor")), "o timer do servidor ainda repete: " + r.detalhes());
        assertTrue(sshd.timerArmado, "o timer segue armado: é a rede de proteção quando o desfazer do app falha");
    }

    @Test
    void quedaNoMeioDoTeeSemEfeitoOAppConfereEDesfazSemDizerQueNadaFoiGravado() throws Exception {
        sshd.cairNoComando = ComandosDoAcesso.gravarArquivoProprio("sudo -n ");
        Resultado r = rodarTudo();
        assertEquals(Desfecho.DESFEITA, r.desfecho(), r.resumo());
        assertTrue(r.resumo().contains("Conferi o servidor"), "leu o estado real: " + r.resumo());
        assertFalse(r.resumo().contains("Nada foi gravado") || r.resumo().contains("Nada ficou diferente"), r.resumo());
        assertFalse(sshd.temArquivoNosso());
        assertFalse(sshd.timerArmado);
    }

    @Test
    void quedaNoMeioDoTeeComEfeitoOAppConfereEDesfazOQueELeu() throws Exception {
        sshd.cairNoComando = ComandosDoAcesso.gravarArquivoProprio("sudo -n ");
        sshd.cairComEfeito = true; // o arquivo foi gravado, mas a confirmação se perdeu
        Resultado r = rodarTudo();
        assertEquals(Desfecho.DESFEITA, r.desfecho(), r.resumo());
        assertTrue(r.resumo().contains("o arquivo do app existia"), "viu que o arquivo existia: " + r.resumo());
        assertFalse(sshd.temArquivoNosso());
    }

    @Test
    void quedaNoMeioDoTeeSemVoltarDizTalvezAplicadoEOServidorDesfazSozinhoEmDoisMinutos() throws Exception {
        sshd.cairNoComando = ComandosDoAcesso.gravarArquivoProprio("sudo -n ");
        sshd.cairComEfeito = true;
        sshd.cairParaSempre = true; // a conexão não volta
        Resultado r = rodarTudo();

        assertEquals(Desfecho.INCERTO, r.desfecho(), r.resumo());
        assertTrue(r.resumo().contains("NÃO sabe se a regra chegou a ser gravada ou aplicada"), r.resumo());
        assertTrue(r.resumo().contains("desfazer automático foi armado"), r.resumo());
        assertFalse(r.resumo().contains("Nada foi gravado") || r.resumo().contains("Nada ficou diferente"), r.resumo());
        assertTrue(sshd.temArquivoNosso() && sshd.timerArmado, "o servidor ficou com a regra e o timer armado");
        sshd.passarOPrazo(); // o app morreu e passaram 2 minutos
        assertFalse(sshd.temArquivoNosso(), "o servidor voltou sozinho");
        assertFalse(sshd.timerArmado);
        assertEquals(1, sshd.recargas, "e recarregou o sshd");
    }

    @Test
    void quedaNoMeioDoReloadComEfeitoFicaIncertaEOTimerRestauraOServidor() throws Exception {
        sshd.cairNoComando = ComandosDoAcesso.recarregarSshd("sudo -n ");
        sshd.cairComEfeito = true;
        sshd.cairParaSempre = true;
        Resultado r = rodarTudo();
        assertEquals(Desfecho.INCERTO, r.desfecho(), r.resumo());
        assertEquals(1, sshd.recargas, "o sshd chegou a recarregar com a regra");
        assertTrue(sshd.timerArmado);
        sshd.passarOPrazo();
        assertFalse(sshd.temArquivoNosso());
        assertEquals(2, sshd.recargas, "o timer recarregou de novo, sem a regra");
    }

    @Test
    void oAppQueMorreNoMeioDeixaOTimerQueVoltaOServidor() throws Exception {
        interromperNaProva = true; // a thread foi interrompida / o processo morreu na hora da prova
        Resultado r = rodarTudo();
        Thread.interrupted(); // o desligador devolve a flag; o teste a limpa
        assertEquals(Desfecho.INCERTO, r.desfecho(), r.resumo());
        assertTrue(sshd.temArquivoNosso() && sshd.timerArmado);
        sshd.passarOPrazo();
        assertFalse(sshd.temArquivoNosso(), "sem o app, o servidor volta sozinho");
    }

    @Test
    void oTimerSoECanceladoDepoisDaProvaEDaConferencia() throws Exception {
        Resultado r = rodarTudo();
        assertEquals(Desfecho.DESLIGADA, r.desfecho());
        List<String> ev = sshd.eventos;
        assertTrue(ev.indexOf("cancelar") > ev.indexOf("prova") && ev.indexOf("cancelar") == ev.size() - 1, "cancelar é o último passo: " + ev);
        // e a conferência (leitura da config geral, do usuário e a sonda) rodou antes do cancelamento
        int cancelar = sshd.comandos.indexOf(ComandosDoAcesso.cancelarDesfazerNoServidor("sudo -n ", CARIMBO));
        assertTrue(sshd.comandos.lastIndexOf(ComandosDoAcesso.lerConfiguracaoParaEsteUsuario("sudo -n ", SshdFalso.USUARIO)) < cancelar);
    }

    @Test
    void naoConseguirCancelarOTimerAvisaQueOServidorVaiVoltarSozinho() throws Exception {
        sshd.cancelarFalha = true;
        Resultado r = rodarTudo();
        assertEquals(Desfecho.PRECISA_DE_AJUDA, r.desfecho());
        assertTrue(r.resumo().contains("NÃO conseguiu cancelar o desfazer automático") && r.resumo().contains("systemctl stop"), r.resumo());
        assertTrue(sshd.timerArmado);
    }

    @Test
    void seOTimerJaDisparouAntesDoCancelamentoOResultadoDizQueOServidorVoltou() throws Exception {
        aoSondar = sshd::passarOPrazo; // a operação demorou: o servidor desfez sozinho logo depois da conferência
        Resultado r = rodarTudo();
        assertEquals(Desfecho.DESFEITA, r.desfecho());
        assertTrue(r.resumo().contains("disparou antes de o app terminar"), r.resumo());
        assertFalse(sshd.temArquivoNosso());
    }

    @Test
    void segundaVezGuardaOArquivoAnteriorDoAppERestauraSeFalhar() throws Exception {
        String antes = "# de antes\nPasswordAuthentication yes\nKbdInteractiveAuthentication no\n";
        sshd.pasta.put(SshdFalso.NOSSO, antes);
        falhaDaProva = new FalhaDeEntradaPorSenha(FalhaDeEntradaPorSenha.Motivo.SEM_LOGIN_POR_CHAVE, "não entrou");
        DesligadorDeSenha d = desligador();
        Avaliacao a = d.avaliar();
        assertTrue(a.temArquivoProprio());
        Resultado r = d.desligar(a, this::observador);
        assertEquals(Desfecho.DESFEITA, r.desfecho());
        assertEquals(antes, sshd.pasta.get(SshdFalso.NOSSO), "o arquivo do app voltou ao que era");
        assertTrue(sshd.eventos.contains("copia-propria") && sshd.eventos.contains("restaurar"), sshd.eventos.toString());
        assertTrue(sshd.comandos.contains(ComandosDoAcesso.armarDesfazerNoServidor("sudo -n ", CARIMBO, true)), "o timer desta vez restaura em vez de apagar");
    }

    @Test
    void oTimerDeUmaSegundaVezRestauraAVersaoAnteriorQuandoDispara() throws Exception {
        String antes = "# de antes\nPasswordAuthentication yes\n";
        sshd.pasta.put(SshdFalso.NOSSO, antes);
        sshd.cairNoComando = ComandosDoAcesso.recarregarSshd("sudo -n ");
        sshd.cairComEfeito = true;
        sshd.cairParaSempre = true;
        rodarTudo();
        sshd.passarOPrazo();
        assertEquals(antes, sshd.pasta.get(SshdFalso.NOSSO));
    }

    @Test
    void cancelarFalhandoNaCopiaDoArquivoProprioNaoMexeEmNada() throws Exception {
        sshd.pasta.put(SshdFalso.NOSSO, "x\n");
        sshd.cairNoComando = ComandosDoAcesso.copiaDoArquivoProprio("sudo -n ", CARIMBO);
        Resultado r = rodarTudo();
        // a queda na CÓPIA acontece antes de qualquer mudança: o app diz isso, e só isso
        assertEquals(Desfecho.FALHOU_SEM_EFEITO, r.desfecho(), r.resumo());
        assertTrue(r.resumo().contains("antes de qualquer mudança"), r.resumo());
        assertFalse(sshd.eventos.contains("gravar"));
    }

    @Test
    void asLinhasDeComandoSaoSempreFixasEOPrefixoEOCarimboSaoValidados() {
        for (String c : ComandosDoAcesso.todasAsEscritasDeExemplo()) {
            assertTrue(c.chars().noneMatch(ch -> ch < 0x20 || ch == 0x7F), "uma linha sem controle: " + c);
        }
        String armar = ComandosDoAcesso.armarDesfazerNoServidor("sudo -n ", CARIMBO, false);
        assertTrue(armar.contains("systemd-run --quiet --on-active=120 --unit=lab-desktop-desfazer-" + CARIMBO), armar);
        assertTrue(armar.contains("rm -f " + ComandosDoAcesso.ARQUIVO_PROPRIO) && armar.contains("systemctl reload ssh || systemctl reload sshd"), armar);
        assertTrue(ComandosDoAcesso.armarDesfazerNoServidor("", CARIMBO, true).contains("cp -p " + ComandosDoAcesso.nomeDaCopiaDoProprio(CARIMBO)));
        assertTrue(ComandosDoAcesso.gravarArquivoProprio("sudo -n ").contains("/usr/sbin/restorecon") && ComandosDoAcesso.gravarArquivoProprio("").contains("|| true"),
            "restorecon só se existir e sem falhar: " + ComandosDoAcesso.gravarArquivoProprio("sudo -n "));
        assertThrows(IllegalArgumentException.class, () -> ComandosDoAcesso.recarregarSshd("sudo -n; rm -rf /; "));
        assertThrows(IllegalArgumentException.class, () -> ComandosDoAcesso.copiaDoArquivoPrincipal("", "2026-10-28; rm"));
        assertThrows(IllegalArgumentException.class, () -> ComandosDoAcesso.gravarArquivoProprio("doas "));
        assertThrows(IllegalArgumentException.class, () -> ComandosDoAcesso.armarDesfazerNoServidor("", "x; rm", false));
        assertThrows(IllegalArgumentException.class, () -> ComandosDoAcesso.lerConfiguracaoParaEsteUsuario("", "ana; rm -rf /"));
        assertNotNull(ComandosDoAcesso.comandoParaReverter());
    }
}
