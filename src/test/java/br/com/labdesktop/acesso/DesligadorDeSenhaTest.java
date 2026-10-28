package br.com.labdesktop.acesso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.acesso.DesligadorDeSenha.Avaliacao;
import br.com.labdesktop.acesso.DesligadorDeSenha.Desfecho;
import br.com.labdesktop.acesso.DesligadorDeSenha.Estado;
import br.com.labdesktop.acesso.DesligadorDeSenha.Motivo;
import br.com.labdesktop.acesso.DesligadorDeSenha.Passo;
import br.com.labdesktop.acesso.DesligadorDeSenha.Resultado;
import br.com.labdesktop.ssh.FalhaDeEntradaPorSenha;
import br.com.labdesktop.teste.SshdFalso;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Desligar o login por senha, camada de passos contra um servidor de mentira: sem sudo (só mostra o comando), {@code sshd -t} que falha (nada
 * recarregado), prova da chave que falha (desfaz sozinho), arquivo anterior que manda mais (detecta e avisa), UsePAM e teclado interativo,
 * métodos combinados, o que o app encontrou e o sucesso. Nenhum comando fora das listas fixas, e a leitura nunca escreve.
 */
class DesligadorDeSenhaTest {

    private static final LocalDateTime AGORA = LocalDateTime.of(2026, 10, 28, 15, 30, 5);
    private static final String CARIMBO = "20261028-153005";

    private SshdFalso sshd;
    private final List<String> linhasDaTela = new ArrayList<>();
    private final AtomicInteger provas = new AtomicInteger();
    private volatile FalhaDeEntradaPorSenha falhaDaProva;

    @BeforeEach
    void montar() {
        sshd = new SshdFalso(CARIMBO);
        linhasDaTela.clear();
    }

    private DesligadorDeSenha desligador() {
        return new DesligadorDeSenha(sshd, () -> {
            provas.incrementAndGet();
            sshd.eventos.add("prova");
            if (falhaDaProva != null) {
                throw falhaDaProva;
            }
        }, () -> AGORA);
    }

    private void observador(Passo p, Estado e, String frase) {
        linhasDaTela.add(p + ":" + e);
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
    }

    @Test
    void semRootENemSudoSemSenhaNaoTentaENuncaPedeSenhaDeSudo() throws Exception {
        sshd.sudo = false;
        DesligadorDeSenha d = desligador();
        Avaliacao a = d.avaliar();

        assertEquals(DesligadorDeSenha.Permissao.NENHUMA, a.permissao());
        assertEquals(Motivo.SEM_PERMISSAO, a.bloqueio().motivo());
        assertTrue(a.bloqueio().texto().contains("nunca pede a senha de sudo"), a.bloqueio().texto());
        assertEquals(List.of(ComandosDoAcesso.lerPermissoes()), sshd.comandos, "só perguntou o que o usuário pode; não tentou ler nem escrever");

        Resultado r = d.desligar(a, this::observador);
        assertEquals(Desfecho.NAO_APLICADA, r.desfecho());
        assertEquals(List.of(), sshd.escritas);
        assertEquals(List.of(), linhasDaTela, "nenhum passo andou");
        assertTrue(ComandosDoAcesso.comandoParaOsDonoRodar().startsWith("sudo sh -c '"), "o comando para o usuário rodar existe e é uma linha");
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
        assertEquals(Desfecho.DESLIGADA, r.desfecho());
        assertTrue(sshd.comandos.stream().noneMatch(c -> c.contains("sudo") && !c.equals(ComandosDoAcesso.lerPermissoes())), "root não usa sudo: " + sshd.comandos);
    }

    @Test
    void sucessoNaOrdemCertaCopiaGravaValidaRecarregaProvaEConfere() throws Exception {
        DesligadorDeSenha d = desligador();
        Avaliacao a = d.avaliar();
        Resultado r = d.desligar(a, this::observador);

        assertEquals(Desfecho.DESLIGADA, r.desfecho(), r.resumo() + r.detalhes());
        assertEquals(Boolean.FALSE, r.senhaLigadaDepois());
        assertEquals("Login por senha: desligado. O servidor só aceita a chave.", r.resumo());
        assertEquals(List.of("copia-principal", "gravar", "recarregar", "prova"), sshd.eventos, "cópia, gravação, recarga e só então a prova");
        String conteudo = sshd.pasta.get(SshdFalso.NOSSO);
        assertTrue(conteudo.contains("PasswordAuthentication no\n") && conteudo.contains("KbdInteractiveAuthentication no\n"), conteudo);
        assertTrue(conteudo.contains("2026-10-28 15:30"), "o arquivo diz quando foi criado");
        assertEquals(ComandosDoAcesso.CONFIG_PRINCIPAL + ".lab-desktop-" + CARIMBO + ".bak", r.copiaDoPrincipal());
        assertTrue(sshd.copias.containsKey(r.copiaDoPrincipal()), "a cópia datada do arquivo foi feita");
        assertEquals(1, sshd.recargas);
        assertTrue(sshd.comandos.stream().noneMatch(c -> c.contains("restart")), "recarrega, nunca reinicia");
        assertTrue(r.comandoParaReverter().contains("rm " + ComandosDoAcesso.ARQUIVO_PROPRIO), r.comandoParaReverter());
        assertEquals(List.of("COPIA:ANDANDO", "COPIA:FEITO", "GRAVAR:ANDANDO", "GRAVAR:FEITO", "VALIDAR:ANDANDO", "VALIDAR:FEITO", "RECARREGAR:ANDANDO",
            "RECARREGAR:FEITO", "PROVAR:ANDANDO", "PROVAR:FEITO", "CONFERIR:ANDANDO", "CONFERIR:FEITO"), linhasDaTela);
        assertTrue(r.detalhes().stream().anyMatch(l -> l.contains("cloud-init")), "avisa que ferramenta de configuração pode desfazer: " + r.detalhes());
        // a validação (sshd -t) rodou ANTES da recarga
        int validar = sshd.comandos.indexOf(ComandosDoAcesso.validarConfiguracao("sudo -n "));
        int recarregar = sshd.comandos.indexOf(ComandosDoAcesso.recarregarSshd("sudo -n "));
        assertTrue(validar > 0 && validar < recarregar, "sshd -t antes de recarregar");
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
    void arquivoDeNuvemQueVemDepoisNaoAtrapalhaOQueNaoAtrapalhaERelata() throws Exception {
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
    void blocoMatchViraAvisoENaoBloqueio() throws Exception {
        sshd.principal += "Match Address 10.0.0.0/8\n  PasswordAuthentication yes\n";
        Avaliacao a = desligador().avaliar();
        assertTrue(a.podeDesligar());
        assertTrue(a.avisos().stream().anyMatch(l -> l.contains("bloco Match")), a.avisos().toString());
    }

    @Test
    void sshdTFalhandoNadaEhRecarregadoEOArquivoEDesfeito() throws Exception {
        sshd.validacaoFalha = true;
        DesligadorDeSenha d = desligador();
        Resultado r = d.desligar(d.avaliar(), this::observador);

        assertEquals(Desfecho.FALHOU_SEM_EFEITO, r.desfecho());
        assertEquals(0, sshd.recargas, "o sshd nem foi tocado");
        assertFalse(sshd.temArquivoNosso(), "o arquivo foi desfeito");
        assertEquals(0, provas.get(), "sem recarga não há o que provar");
        assertTrue(r.resumo().contains("não aceitou a configuração"), r.resumo());
        assertTrue(r.detalhes().stream().anyMatch(l -> l.contains("Bad configuration option")), r.detalhes().toString());
        assertTrue(linhasDaTela.contains("VALIDAR:FALHOU") && linhasDaTela.contains("GRAVAR:DESFEITO"), linhasDaTela.toString());
        assertFalse(linhasDaTela.contains("RECARREGAR:ANDANDO"));
    }

    @Test
    void recargaQueFalhaDesfazOArquivoESemProvar() throws Exception {
        sshd.recarregarFalha = true;
        DesligadorDeSenha d = desligador();
        Resultado r = d.desligar(d.avaliar(), this::observador);
        assertEquals(Desfecho.FALHOU_SEM_EFEITO, r.desfecho());
        assertFalse(sshd.temArquivoNosso());
        assertEquals(0, provas.get());
        assertTrue(r.resumo().contains("recarregar"), r.resumo());
    }

    @Test
    void provaDaChaveQueFalhaDesfazSozinhoERecarregaDeNovo() throws Exception {
        falhaDaProva = new FalhaDeEntradaPorSenha(FalhaDeEntradaPorSenha.Motivo.SEM_LOGIN_POR_CHAVE, "O servidor não aceitou a chave.");
        DesligadorDeSenha d = desligador();
        Resultado r = d.desligar(d.avaliar(), this::observador);

        assertEquals(Desfecho.DESFEITA, r.desfecho(), r.resumo());
        assertFalse(sshd.temArquivoNosso(), "o arquivo que o app criou foi apagado");
        assertEquals(2, sshd.recargas, "recarregou ao aplicar e de novo ao desfazer");
        assertEquals(List.of("copia-principal", "gravar", "recarregar", "prova", "apagar", "recarregar"), sshd.eventos);
        assertTrue(r.resumo().contains("DESFEZ"), r.resumo());
        assertTrue(r.resumo().contains("NÃO entrou"), r.resumo());
        assertTrue(linhasDaTela.contains("PROVAR:FALHOU") && linhasDaTela.contains("GRAVAR:DESFEITO"), linhasDaTela.toString());
        assertTrue(sshd.senhaBase, "a senha continua como estava");
        int validarDoDesfazer = sshd.comandos.lastIndexOf(ComandosDoAcesso.validarConfiguracao("sudo -n "));
        assertTrue(validarDoDesfazer > sshd.comandos.indexOf(ComandosDoAcesso.recarregarSshd("sudo -n ")), "confere a sintaxe antes da recarga do desfazer");
    }

    @Test
    void conferenciaFinalQueAindaVeASenhaLigadaTambemDesfaz() throws Exception {
        sshd.ignoraONosso = true;
        DesligadorDeSenha d = desligador();
        Resultado r = d.desligar(d.avaliar(), this::observador);
        assertEquals(Desfecho.DESFEITA, r.desfecho());
        assertTrue(r.resumo().contains("continua aceitando senha"), r.resumo());
        assertFalse(sshd.temArquivoNosso());
    }

    @Test
    void seODesfazerNaoConseguirDizOComandoParaODonoRodar() throws Exception {
        falhaDaProva = new FalhaDeEntradaPorSenha(FalhaDeEntradaPorSenha.Motivo.SEM_LOGIN_POR_CHAVE, "não entrou");
        sshd.apagarFalha = true;
        DesligadorDeSenha d = desligador();
        Resultado r = d.desligar(d.avaliar(), this::observador);
        assertEquals(Desfecho.PRECISA_DE_AJUDA, r.desfecho());
        assertTrue(r.resumo().contains("NÃO conseguiu desfazer"), r.resumo());
        assertTrue(r.detalhes().stream().anyMatch(l -> l.contains("rm " + ComandosDoAcesso.ARQUIVO_PROPRIO)), r.detalhes().toString());
    }

    @Test
    void conexaoQueCaiDepoisDeGravarTentaDesfazer() throws Exception {
        sshd.cairNoComando = ComandosDoAcesso.validarConfiguracao("sudo -n ");
        DesligadorDeSenha d = desligador();
        Resultado r = d.desligar(d.avaliar(), this::observador);
        // a conexão caiu uma vez (o comando de validação lançou); o desfazer usa a mesma conexão e roda
        assertEquals(Desfecho.DESFEITA, r.desfecho(), r.resumo());
        assertFalse(sshd.temArquivoNosso());
    }

    @Test
    void segundaVezGuardaOArquivoAnteriorDoAppERestauraSeFalhar() throws Exception {
        String antes = "# de antes\nPasswordAuthentication yes\nKbdInteractiveAuthentication no\n";
        sshd.pasta.put(SshdFalso.NOSSO, antes);
        sshd.senhaBase = true;
        sshd.ignoraONosso = false;
        // o arquivo de antes diz "yes": o sshd -T mostra a senha ligada; o app prevê o conteúdo NOVO no lugar do antigo
        falhaDaProva = new FalhaDeEntradaPorSenha(FalhaDeEntradaPorSenha.Motivo.SEM_LOGIN_POR_CHAVE, "não entrou");
        DesligadorDeSenha d = desligador();
        Avaliacao a = d.avaliar();
        assertTrue(a.temArquivoProprio());
        Resultado r = d.desligar(a, this::observador);
        assertEquals(Desfecho.DESFEITA, r.desfecho());
        assertEquals(antes, sshd.pasta.get(SshdFalso.NOSSO), "o arquivo do app voltou ao que era");
        assertTrue(sshd.eventos.contains("copia-propria") && sshd.eventos.contains("restaurar"), sshd.eventos.toString());
    }

    @Test
    void asLinhasDeComandoSaoSempreFixasEOPrefixoEOCarimboSaoValidados() {
        for (String c : ComandosDoAcesso.todasAsEscritasDeExemplo()) {
            assertTrue(c.chars().noneMatch(ch -> ch < 0x20 || ch == 0x7F), "uma linha sem controle: " + c);
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> ComandosDoAcesso.recarregarSshd("sudo -n; rm -rf /; "));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> ComandosDoAcesso.copiaDoArquivoPrincipal("", "2026-10-28; rm"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> ComandosDoAcesso.gravarArquivoProprio("doas "));
        assertNotNull(ComandosDoAcesso.comandoParaReverter());
    }
}
