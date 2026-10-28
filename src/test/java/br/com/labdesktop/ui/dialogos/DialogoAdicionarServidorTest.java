package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.ImportadorDeChaves;
import br.com.labdesktop.cofre.InfoChave;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.servidor.AssistenteDeServidor;
import br.com.labdesktop.servidor.AssistenteDeServidor.Etapa;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * O assistente "Adicionar servidor" de ponta a ponta, com o COFRE de verdade, o {@code SshServer} embutido e o agente
 * falso: os três passos pelos controles do diálogo, como o usuário faria, e SEM nunca clicar em "Gerar chave" (que não
 * existe mais): a chave nasce sozinha ao avançar do passo dos dados.
 */
@ExtendWith(VerificaEdt.class)
class DialogoAdicionarServidorTest {

    private static final char[] SENHA = "uma-senha-mestra-de-teste".toCharArray();
    private static final Duration PRAZO = Duration.ofSeconds(30);

    @TempDir
    Path tmp;

    private ServidorSshDeTeste ssh;
    private AgenteDoLab agente;
    private CofreDeChaves cofre;
    private GerenciadorServidores gerenciador;
    private AssistenteDeServidor assistente;
    private final AvisosGravados avisos = new AvisosGravados();
    private final Clipboard areaDeTransferencia = new Clipboard("teste");
    private final List<ServidorLab> concluidos = new ArrayList<>();
    private final List<String> hostsConferidos = new ArrayList<>();
    private volatile boolean confirmaHost = true;

    @BeforeEach
    void montar() throws Exception {
        Visual.tema(Tema.CLARO);
        ssh = ServidorSshDeTeste.ed25519();
        agente = new AgenteDoLab();
        ssh.comandoRemoto(agente.comandosPorSsh());
        cofre = CofreDeChaves.criar(CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs")), SENHA.clone(), ParametrosKdf.MINIMO);
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

    private DialogoAdicionarServidor.SeletorDeArquivo semArquivo() {
        return janela -> Optional.empty();
    }

    private DialogoAdicionarServidor assistenteNaTela(boolean primeiro) {
        return assistenteNaTela(primeiro, semArquivo());
    }

    private DialogoAdicionarServidor assistenteNaTela(boolean primeiro, DialogoAdicionarServidor.SeletorDeArquivo seletor) {
        DialogoAdicionarServidor.ConfirmadorDeHost confirmador = (servidor, endereco, resultado, aoDecidir) -> {
            hostsConferidos.add(endereco + " " + resultado.impressao());
            aoDecidir.accept(confirmaHost);
        };
        DialogoAdicionarServidor d = Visual.naEdt(() -> new DialogoAdicionarServidor(assistente, primeiro, avisos, concluidos::add,
            confirmador, seletor, new CopiadorDeTexto(() -> areaDeTransferencia)));
        Visual.naEdt(() -> {
            d.campoDoNome().setText("lab");
            d.campoDoHost().setText("127.0.0.1");
            d.campoDaPorta().setText(String.valueOf(ssh.porta()));
            d.campoDoUsuario().setText("labuser");
            d.campoDaPortaDoAgente().setText(String.valueOf(agente.porta()));
        });
        return d;
    }

    private static PublicKey publica(InfoChave chave) throws Exception {
        return PublicKeyEntry.parsePublicKeyEntry(chave.publica()).resolvePublicKey(null, Collections.emptyMap(), PublicKeyEntryResolver.FAILING);
    }

    /** "Continuar" no passo dos dados e espera a chave que o app cria sozinho. Nenhum clique em "gerar". */
    private InfoChave continuarEEsperarAChave(DialogoAdicionarServidor d) {
        Visual.naEdt(() -> d.botaoContinuar().doClick());
        esperarPagina(d, "chave");
        Espera.ate(PRAZO, () -> "chave criada sozinha (erro='" + Visual.naEdt(d::textoDoErro) + "')",
            () -> Visual.naEdt(() -> d.chaveEscolhida() != null && d.mostraACaixaDaChave()));
        return Visual.naEdt(d::chaveEscolhida);
    }

    private void instalarAChave(InfoChave chave) throws Exception {
        ssh.aceitar(publica(chave)); // "colou" a chave no acesso do servidor
    }

    private static void esperarPagina(DialogoAdicionarServidor d, String pagina) {
        Espera.ate(PRAZO, () -> "página " + pagina + " (está em " + Visual.naEdt(d::paginaAtual) + ", erro='" + Visual.naEdt(d::textoDoErro) + "')",
            () -> pagina.equals(Visual.naEdt(d::paginaAtual)));
    }

    private static String textosDe(java.awt.Component c) {
        StringBuilder sb = new StringBuilder();
        if (c instanceof javax.swing.text.JTextComponent t) {
            sb.append(t.getText()).append('\n');
        } else if (c instanceof javax.swing.JLabel l && l.getText() != null) {
            sb.append(l.getText()).append('\n');
        } else if (c instanceof javax.swing.AbstractButton b && b.getText() != null) {
            sb.append(b.getText()).append('\n');
        }
        if (c instanceof java.awt.Container k) {
            for (java.awt.Component f : k.getComponents()) {
                sb.append(textosDe(f));
            }
        }
        return sb.toString();
    }

    private String copiadoParaAAreaDeTransferencia() throws Exception {
        return (String) areaDeTransferencia.getData(DataFlavor.stringFlavor);
    }

    // ---------------------------------------------------------------------------------------------
    // Passo 1
    // ---------------------------------------------------------------------------------------------

    @Test
    void naPrimeiraVezOsCamposVemVaziosComAPortaSshPadrao() {
        DialogoAdicionarServidor d = Visual.naEdt(() -> new DialogoAdicionarServidor(assistente, true, avisos, concluidos::add));
        assertEquals("", Visual.naEdt(() -> d.campoDoNome().getText()));
        assertEquals("", Visual.naEdt(() -> d.campoDoHost().getText()));
        assertEquals("22", Visual.naEdt(() -> d.campoDaPorta().getText()));
        assertEquals("", Visual.naEdt(() -> d.campoDoUsuario().getText()));
        assertEquals("7000", Visual.naEdt(() -> d.campoDaPortaDoAgente().getText()));
        assertEquals("dados", Visual.naEdt(d::paginaAtual));
        assertTrue(Visual.naEdt(() -> d.botaoContinuar().isEnabled()));
        assertFalse(Visual.naEdt(() -> d.botaoTestar().isEnabled()), "sem chave não dá para testar");
        DialogoAdicionarServidor outro = Visual.naEdt(() -> new DialogoAdicionarServidor(assistente, false, avisos, concluidos::add));
        assertEquals("", Visual.naEdt(() -> outro.campoDoHost().getText()), "do segundo servidor em diante também vêm vazios");
    }

    @Test
    void dadosInvalidosSaoRecusadosNoPassoDosDadosSemCriarChaveNemGravarNada() throws Exception {
        DialogoAdicionarServidor d = assistenteNaTela(true);
        Visual.naEdt(() -> d.campoDaPorta().setText("vinte e dois"));
        Visual.naEdt(() -> d.botaoContinuar().doClick());
        assertEquals("A porta do SSH precisa ser um número.", Visual.naEdt(d::textoDoErro));
        assertEquals("dados", Visual.naEdt(d::paginaAtual));
        Visual.naEdt(() -> d.campoDaPorta().setText(String.valueOf(ssh.porta())));
        Visual.naEdt(() -> d.campoDoHost().setText("-oProxyCommand=touch x"));
        Visual.naEdt(() -> d.botaoContinuar().doClick());
        assertTrue(Visual.naEdt(d::textoDoErro).contains("endereço"), Visual.naEdt(d::textoDoErro));
        assertEquals("dados", Visual.naEdt(d::paginaAtual));
        assertTrue(hostsConferidos.isEmpty());
        assertTrue(gerenciador.servidores().isEmpty());
        assertTrue(cofre.chaves().isEmpty(), "com dados ruins nenhuma chave é criada");
    }

    // ---------------------------------------------------------------------------------------------
    // Passo 2: a chave nasce sozinha
    // ---------------------------------------------------------------------------------------------

    @Test
    void avancarCriaAChaveSozinhaEMostraAPublicaOProximoPassoEOComandoRecolhido() throws Exception {
        DialogoAdicionarServidor d = assistenteNaTela(true);
        assertEquals(0, cofre.chaves().size());

        InfoChave chave = continuarEEsperarAChave(d);

        assertEquals(1, cofre.chaves().size(), "a chave já está guardada no cofre, sem nenhum botão de gerar");
        assertEquals(chave.id(), cofre.chaves().get(0).id());
        assertTrue(Visual.naEdt(d::chavePublicaMostrada).startsWith("ssh-ed25519 "), Visual.naEdt(d::chavePublicaMostrada));
        assertEquals(chave.publica(), Visual.naEdt(d::chavePublicaMostrada));
        assertEquals("Falta instalar esta chave no servidor: peça a quem administra o servidor para adicioná-la ao acesso do "
            + "usuário labuser (no painel do provedor, é o campo 'chaves SSH').", Visual.naEdt(d::proximoPassoMostrado));
        assertTrue(Visual.naEdt(() -> d.botaoTestar().isEnabled()));
        assertFalse(Visual.naEdt(() -> d.secaoDoComando().aberta()), "o comando técnico começa recolhido");
        assertFalse(Visual.naEdt(() -> d.opcoesAvancadas().aberta()), "as opções avançadas começam fechadas");
        assertTrue(Visual.naEdt(d::comandoMostrado).startsWith("mkdir -p ~/.ssh"), "o comando continua disponível");
        assertTrue(Visual.naEdt(d::comandoMostrado).contains("permitopen=\"127.0.0.1:" + agente.porta() + "\""));
        String tela = Visual.naEdt(() -> textosDe(d.raiz()));
        assertTrue(tela.contains(DialogoAdicionarServidor.TEXTO_DA_CHAVE), tela);
        assertTrue(tela.contains("guarda cifrada dentro do cofre. Não existe arquivo para guardar nem senha extra para "
            + "lembrar além da senha-mestra."), tela);
        assertFalse(tela.contains("Gere, escolha ou importe"), "o erro antigo não existe mais");
        assertFalse(tela.contains("Gerar chave"), "nenhum botão obrigatório de gerar");
        Visual.salvar(Visual.renderizar(d.raiz(), 560, Visual.alturaPreferida(d.raiz(), 560)), "assistente-passo-2-chave");
    }

    @Test
    void oTextoAoLadoDoComandoDizQueAChaveAindaAbreShell() throws Exception {
        DialogoAdicionarServidor d = assistenteNaTela(true);
        continuarEEsperarAChave(d);
        Visual.naEdt(() -> d.secaoDoComando().botao().doClick());
        String tela = Visual.naEdt(() -> textosDe(d.raiz()));

        assertTrue(tela.contains(InfoChave.RESTRICOES_DA_LINHA), tela);
        assertFalse(tela.contains("sem terminal"), tela);
        assertTrue(tela.contains("NÃO tira o shell"), tela);
    }

    @Test
    void copiarDaChavePublicaDizCopiadoECopiaDeVerdade() throws Exception {
        DialogoAdicionarServidor d = assistenteNaTela(true);
        InfoChave chave = continuarEEsperarAChave(d);

        Visual.naEdt(() -> d.botaoCopiarChave().doClick());

        assertEquals(chave.publica(), copiadoParaAAreaDeTransferencia());
        assertEquals("Copiado", Visual.naEdt(() -> d.botaoCopiarChave().getText()), "feedback no próprio botão");
        assertTrue(avisos.disse("info", "Copiado"));
        Espera.ate(Duration.ofSeconds(5), "o botão volta a dizer Copiar", () -> "Copiar".equals(Visual.naEdt(() -> d.botaoCopiarChave().getText())));
    }

    @Test
    void verComandoAbreEFechaEMostraOComandoTecnico() throws Exception {
        DialogoAdicionarServidor d = assistenteNaTela(true);
        continuarEEsperarAChave(d);
        assertFalse(Visual.naEdt(() -> d.secaoDoComando().aberta()));
        assertEquals("Ver comando", Visual.naEdt(() -> d.secaoDoComando().botao().getText()));

        Visual.naEdt(() -> d.secaoDoComando().botao().doClick());
        assertTrue(Visual.naEdt(() -> d.secaoDoComando().aberta()));
        assertEquals("Esconder o comando", Visual.naEdt(() -> d.secaoDoComando().botao().getText()));
        Visual.salvar(Visual.renderizar(d.raiz(), 560, Visual.alturaPreferida(d.raiz(), 560)), "assistente-passo-2-ver-comando");

        Visual.naEdt(() -> d.secaoDoComando().botao().doClick());
        assertFalse(Visual.naEdt(() -> d.secaoDoComando().aberta()));
    }

    @Test
    void mudarAPortaDoAgenteEVoltarAoPassoDaChaveAtualizaOComando() throws Exception {
        DialogoAdicionarServidor d = assistenteNaTela(true);
        InfoChave chave = continuarEEsperarAChave(d);
        Visual.naEdt(() -> d.botaoVoltar().doClick());
        assertEquals("dados", Visual.naEdt(d::paginaAtual));
        Visual.naEdt(() -> d.campoDaPortaDoAgente().setText("7123"));
        Visual.naEdt(() -> d.campoDoUsuario().setText("outrousuario"));
        Visual.naEdt(() -> d.botaoContinuar().doClick());

        esperarPagina(d, "chave");
        assertTrue(Visual.naEdt(d::comandoMostrado).contains("permitopen=\"127.0.0.1:7123\""));
        assertTrue(Visual.naEdt(d::proximoPassoMostrado).contains("usuário outrousuario"));
        assertEquals(chave.id(), Visual.naEdt(() -> d.chaveEscolhida().id()), "voltar e avançar reaproveita a chave, não cria outra");
        assertEquals(1, cofre.chaves().size());
    }

    @Test
    void falhaAoCriarAChaveMostraOErroEOBotaoDeTentarDeNovoENaoDeixaTestar() throws Exception {
        DialogoAdicionarServidor d = assistenteNaTela(true);
        cofre.bloquear(); // o cofre fechou no meio: criar a chave falha
        Visual.naEdt(() -> d.botaoContinuar().doClick());
        esperarPagina(d, "chave");
        Espera.ate(PRAZO, "erro de criar a chave", () -> Visual.naEdt(d::textoDoErro).startsWith("Não consegui criar a chave"));
        assertTrue(Visual.naEdt(() -> d.botaoRepetirGeracao().isVisible()));
        assertFalse(Visual.naEdt(() -> d.botaoTestar().isEnabled()));
        assertNull(Visual.naEdt(d::chaveEscolhida));
    }

    // ---------------------------------------------------------------------------------------------
    // O caminho completo
    // ---------------------------------------------------------------------------------------------

    @Test
    void caminhoCompletoSemClicarEmGerarChaveConfereHostConectaRegistraOTokenEConclui() throws Exception {
        DialogoAdicionarServidor d = assistenteNaTela(true);
        InfoChave chave = continuarEEsperarAChave(d); // passo 1 -> 2: a chave nasce sozinha
        instalarAChave(chave);

        Visual.naEdt(() -> d.botaoTestar().doClick());
        esperarPagina(d, "conexao");
        assertEquals(1, hostsConferidos.size());
        assertTrue(hostsConferidos.get(0).startsWith("127.0.0.1:" + ssh.porta() + " SHA256:"), hostsConferidos.get(0));
        assertEquals(1, cofre.chaves().size(), "nenhuma chave extra no caminho");

        Espera.ate(PRAZO, () -> "pronto (conexão=" + Visual.naEdt(d::estadoDaConexao) + ", msg=" + Visual.naEdt(d::mensagemDaConexao) + ")",
            () -> Visual.naEdt(() -> d.botaoConcluir().isEnabled()));
        for (Etapa e : new Etapa[] {Etapa.CONECTANDO, Etapa.REGISTRANDO_TOKEN, Etapa.TESTANDO_AGENTE, Etapa.LENDO_LIMITES}) {
            assertEquals(2, Visual.naEdt(() -> d.estadoDaEtapa(e)), "etapa " + e + " feita");
        }
        assertEquals("Conexão: Conectado", Visual.naEdt(d::estadoDaConexao));
        assertEquals(1, agente.tokens.size(), "o token deste computador foi registrado por SSH");
        ServidorLab lab = Visual.naEdt(d::servidorCadastrado);
        assertNotNull(lab);
        assertEquals(EstadoConexao.CONECTADO, lab.estado().conexao());
        assertEquals(chave.id(), cofre.servidor(lab.id()).orElseThrow().chaveId());
        Visual.salvar(Visual.renderizar(d.raiz(), 560, Visual.alturaPreferida(d.raiz(), 560)), "assistente-passo-3-pronto");

        Visual.naEdt(() -> d.botaoConcluir().doClick());
        assertEquals(List.of(lab), concluidos, "o servidor pronto é entregue para a janela selecioná-lo");
        assertTrue(avisos.disse("ok", "Servidor lab adicionado."));
        assertEquals(1, gerenciador.servidores().size());
    }

    @Test
    void sePeloDonoNaoConfiarNoHostNadaEGravadoENenhumaCredencialEnviada() throws Exception {
        confirmaHost = false;
        DialogoAdicionarServidor d = assistenteNaTela(true);
        InfoChave chave = continuarEEsperarAChave(d);
        instalarAChave(chave);
        Visual.naEdt(() -> d.botaoTestar().doClick());
        Espera.ate(PRAZO, "recusa", () -> Visual.naEdt(d::textoDoErro).contains("não confirmado"));
        assertEquals(DialogoAdicionarServidor.TEXTO_DE_SERVIDOR_NAO_CONFIRMADO, Visual.naEdt(d::textoDoErro));
        assertTrue(Visual.naEdt(d::textoDoErro).contains("Confira o endereço e a porta (botão Voltar)"), Visual.naEdt(d::textoDoErro));
        assertTrue(Visual.naEdt(d::textoDoErro).contains("outro aparelho na rede"), Visual.naEdt(d::textoDoErro));
        assertEquals("chave", Visual.naEdt(d::paginaAtual));
        assertTrue(gerenciador.servidores().isEmpty());
        assertTrue(cofre.servidores().isEmpty());
        assertEquals(0, ssh.pedidosDeAutenticacao.get());
        assertTrue(Visual.naEdt(() -> d.botaoTestar().isEnabled()), "dá para tentar de novo");
    }

    @Test
    void portaErradaMostraAFraseEmPortuguesNoPassoDaChaveQueMandaVoltarEConferir() throws Exception {
        int portaFechada;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            portaFechada = s.getLocalPort();
        }
        DialogoAdicionarServidor d = assistenteNaTela(true);
        Visual.naEdt(() -> d.campoDaPorta().setText(String.valueOf(portaFechada)));
        continuarEEsperarAChave(d);

        Visual.naEdt(() -> d.botaoTestar().doClick());

        Espera.ate(PRAZO, "erro da consulta", () -> !Visual.naEdt(d::textoDoErro).isEmpty());
        String erro = Visual.naEdt(d::textoDoErro);
        assertTrue(erro.startsWith("O servidor em 127.0.0.1:" + portaFechada + " recusou a conexão."), erro);
        assertTrue(erro.contains("botão Voltar"), erro);
        assertFalse(erro.toLowerCase().contains("connection refused") || erro.contains("Não consegui obter a chave do servidor ("), erro);
        assertEquals("chave", Visual.naEdt(d::paginaAtual));
        assertTrue(hostsConferidos.isEmpty(), "nem chegou a perguntar pela impressão digital");
        assertTrue(Visual.naEdt(() -> d.botaoTestar().isEnabled()), "dá para voltar, corrigir e tentar de novo");
    }

    @Test
    void comComandoEOpcoesAvancadasAbertosOMioloRolaEOsBotoesFicamSempreAVista() throws Exception {
        Dialogo.definirAlturaDaTelaParaTeste(800); // um notebook comum: 1280 por 800
        try {
            DialogoAdicionarServidor d = assistenteNaTela(true);
            continuarEEsperarAChave(d);
            var miolo = Visual.naEdt(d::mioloRolavel);
            int fechado = Visual.alturaPreferida(d.raiz(), 560); // alturaPreferida refaz o layout, como a janela de verdade faria

            Visual.naEdt(() -> d.secaoDoComando().botao().doClick());
            Visual.naEdt(() -> d.opcoesAvancadas().botao().doClick());
            int aberto = Visual.alturaPreferida(d.raiz(), 560);
            int conteudo = Visual.naEdt(() -> miolo.getViewport().getView().getPreferredSize().height);

            assertTrue(Visual.naEdt(miolo::rola), "tudo aberto passa de 80 % da tela: o miolo rola");
            assertTrue(aberto <= 800 * 0.8, "a janela cabe na tela com folga: " + aberto + " px");
            assertTrue(conteudo > aberto, "o conteúdo é mais alto que a janela (" + conteudo + " px contra " + aberto + " px)");
            assertTrue(aberto >= fechado, "abrir seções nunca encolhe a janela");
            for (var botao : List.of(d.botaoTestar(), d.botaoVoltar(), d.botaoCancelar())) {
                assertFalse(Visual.naEdt(() -> javax.swing.SwingUtilities.isDescendingFrom(botao, miolo)),
                    "o botão " + botao.getText() + " fica FORA da área que rola");
            }
            assertTrue(Visual.naEdt(() -> javax.swing.SwingUtilities.isDescendingFrom(d.botaoTestar(), d.raiz())));
            assertEquals(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, miolo.getHorizontalScrollBarPolicy());
            Visual.salvar(Visual.renderizar(d.raiz(), 560, Visual.alturaPreferida(d.raiz(), 560)), "assistente-passo-2-tudo-aberto-notebook");

            // Numa tela alta o mesmo conteúdo cabe inteiro e não rola.
            Dialogo.definirAlturaDaTelaParaTeste(1600);
            Visual.alturaPreferida(d.raiz(), 560);
            assertFalse(Visual.naEdt(miolo::rola), "numa tela de 1600 px de altura o mesmo conteúdo cabe inteiro");
            Dialogo.definirAlturaDaTelaParaTeste(800);
            Visual.naEdt(() -> d.secaoDoComando().botao().doClick());
            Visual.naEdt(() -> d.opcoesAvancadas().botao().doClick());
            Visual.alturaPreferida(d.raiz(), 560);
            assertFalse(Visual.naEdt(miolo::rola), "com tudo fechado, nem no notebook rola");
        } finally {
            Dialogo.definirAlturaDaTelaParaTeste(0);
        }
    }

    @Test
    void chaveNaoInstaladaMostraAInstrucaoDeNovoETentarDeNovoConcluiDepoisDeInstalar() throws Exception {
        DialogoAdicionarServidor d = assistenteNaTela(true);
        InfoChave chave = continuarEEsperarAChave(d);
        // NÃO instala a chave no servidor

        Visual.naEdt(() -> d.botaoTestar().doClick());
        esperarPagina(d, "conexao");
        Espera.ate(PRAZO, "falha de autenticação", () -> Visual.naEdt(d::mensagemDaConexao).contains("recusou a chave"));
        assertTrue(Visual.naEdt(d::mostraOComandoNaConexao), "o que fazer com a chave aparece na própria página");
        assertFalse(Visual.naEdt(d::mensagemDaConexao).contains("authorized_keys"), "sem jargão no caminho principal");
        assertFalse(Visual.naEdt(() -> d.botaoConcluir().isEnabled()));
        assertEquals(3, Visual.naEdt(() -> d.estadoDaEtapa(Etapa.CONECTANDO)), "a etapa que falhou fica marcada");
        Visual.salvar(Visual.renderizar(d.raiz(), 560, Visual.alturaPreferida(d.raiz(), 560)), "assistente-passo-3-chave-recusada");

        instalarAChave(chave); // o usuário pediu para instalar a chave
        Espera.ate(PRAZO, "botão de novo", () -> Visual.naEdt(() -> d.botaoTentarDeNovo().isEnabled()));
        Visual.naEdt(() -> d.botaoTentarDeNovo().doClick());
        Espera.ate(PRAZO, () -> "pronto (msg=" + Visual.naEdt(d::mensagemDaConexao) + ")", () -> Visual.naEdt(() -> d.botaoConcluir().isEnabled()));
        assertEquals(1, agente.tokens.size());
        assertEquals(1, gerenciador.servidores().size(), "tentar de novo reaproveita o servidor, não cria outro");
    }

    @Test
    void nomeDeDispositivoJaUsadoNoAgenteOferecSubstituirEFunciona() throws Exception {
        String nome = AssistenteDeServidor.sugerirNomeDoDispositivo();
        agente.tokens.put("b".repeat(64), nome); // outro Mac (ou um cofre antigo) já tem esse nome
        DialogoAdicionarServidor d = assistenteNaTela(true);
        instalarAChave(continuarEEsperarAChave(d));
        Visual.naEdt(() -> d.botaoTestar().doClick());
        esperarPagina(d, "conexao");
        Espera.ate(PRAZO, "conflito", () -> Visual.naEdt(() -> d.botaoSubstituirToken().isVisible()));
        assertTrue(Visual.naEdt(d::mensagemDaConexao).contains("revogar o antigo"), Visual.naEdt(d::mensagemDaConexao));
        assertFalse(Visual.naEdt(() -> d.botaoConcluir().isEnabled()));

        Visual.naEdt(() -> d.botaoSubstituirToken().doClick());
        Espera.ate(PRAZO, () -> "pronto (msg=" + Visual.naEdt(d::mensagemDaConexao) + ")", () -> Visual.naEdt(() -> d.botaoConcluir().isEnabled()));
        assertEquals(1, agente.tokens.size());
        assertFalse(agente.tokens.containsKey("b".repeat(64)), "a entrada antiga foi revogada");
    }

    // ---------------------------------------------------------------------------------------------
    // Opções avançadas
    // ---------------------------------------------------------------------------------------------

    @Test
    void usarUmaChaveDoCofreTrocaAChaveDescartaAQueOAppCriouEVoltarReaproveitaAGerada() throws Exception {
        InfoChave guardada = assistente.gerarChave("antiga");
        DialogoAdicionarServidor d = assistenteNaTela(false);
        Espera.ate(PRAZO, "combo carregado", () -> Visual.naEdt(() -> d.comboDeChaves().getItemCount() == 1));
        InfoChave criada = continuarEEsperarAChave(d);
        assertNotEquals(guardada.id(), criada.id());
        assertEquals(2, cofre.chaves().size());

        Visual.naEdt(() -> d.opcoesAvancadas().botao().doClick());
        assertTrue(Visual.naEdt(() -> d.opcoesAvancadas().aberta()));
        Visual.salvar(Visual.renderizar(d.raiz(), 560, Visual.alturaPreferida(d.raiz(), 560)), "assistente-passo-2-avancado");
        Visual.naEdt(() -> d.opcaoUsarExistente().doClick());
        assertEquals(guardada.id(), Visual.naEdt(() -> d.chaveEscolhida().id()));
        assertTrue(Visual.naEdt(d::comandoMostrado).contains(guardada.publica().split(" ")[1]));
        assertTrue(Visual.naEdt(() -> d.botaoTestar().isEnabled()));

        Visual.naEdt(() -> d.opcaoGerar().doClick());
        assertEquals(criada.id(), Visual.naEdt(() -> d.chaveEscolhida().id()), "voltar à opção padrão reaproveita a chave criada");
        assertEquals(2, cofre.chaves().size(), "e não cria outra");

        Visual.naEdt(() -> d.opcaoUsarExistente().doClick());
        instalarAChave(guardada);
        Visual.naEdt(() -> d.botaoTestar().doClick());
        esperarPagina(d, "conexao");
        Espera.ate(PRAZO, "a chave criada que ficou sem uso foi apagada do cofre", () -> cofre.chaves().size() == 1);
        assertEquals(guardada.id(), cofre.chaves().get(0).id());
        Espera.ate(PRAZO, () -> "pronto (msg=" + Visual.naEdt(d::mensagemDaConexao) + ")", () -> Visual.naEdt(() -> d.botaoConcluir().isEnabled()));
        assertEquals(guardada.id(), cofre.servidor(Visual.naEdt(d::servidorCadastrado).id()).orElseThrow().chaveId());
    }

    @Test
    void gerarOutraChaveTrocaAChaveEApagaAAntigaQueNinguemUsa() throws Exception {
        DialogoAdicionarServidor d = assistenteNaTela(true);
        InfoChave primeira = continuarEEsperarAChave(d);
        Visual.naEdt(() -> d.opcoesAvancadas().botao().doClick());
        assertTrue(Visual.naEdt(() -> d.botaoGerarOutra().isVisible()));

        Visual.naEdt(() -> d.botaoGerarOutra().doClick());
        Espera.ate(PRAZO, "chave nova", () -> Visual.naEdt(() -> !primeira.id().equals(d.chaveEscolhida().id())));
        InfoChave segunda = Visual.naEdt(d::chaveEscolhida);
        assertEquals(segunda.publica(), Visual.naEdt(d::chavePublicaMostrada));
        Espera.ate(PRAZO, "a antiga saiu do cofre", () -> cofre.chaves().size() == 1);
        assertEquals(segunda.id(), cofre.chaves().get(0).id());
    }

    private static byte[] chaveOpenSsh(KeyPair par, String senha) throws Exception {
        OpenSSHKeyEncryptionContext contexto = null;
        if (senha != null) {
            contexto = new OpenSSHKeyEncryptionContext();
            contexto.setPassword(senha);
            contexto.setCipherName("AES");
            contexto.setCipherType("256");
            contexto.setCipherMode("CTR");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(par, "origem", contexto, bytes);
        return bytes.toByteArray();
    }

    /** Abre o passo da chave, as opções avançadas e escolhe o arquivo dado; devolve o diálogo. */
    private DialogoAdicionarServidor importando(Path arquivo) throws Exception {
        AtomicReference<Path> escolhido = new AtomicReference<>(arquivo);
        DialogoAdicionarServidor d = assistenteNaTela(true, janela -> Optional.ofNullable(escolhido.get()));
        continuarEEsperarAChave(d);
        Visual.naEdt(() -> d.opcoesAvancadas().botao().doClick());
        Visual.naEdt(() -> d.opcaoImportar().doClick());
        assertTrue(Visual.naEdt(() -> d.botaoArquivo().isEnabled()));
        assertFalse(Visual.naEdt(d::mostraASenhaDoArquivo), "a senha do arquivo só aparece se o arquivo pedir");
        Visual.naEdt(() -> d.botaoArquivo().doClick());
        return d;
    }

    @Test
    void importarChaveDeArquivoSemSenhaGuardaNoCofreEUsaEssaChave() throws Exception {
        Path arquivo = tmp.resolve("id_ed25519");
        KeyPair par = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        Files.write(arquivo, chaveOpenSsh(par, null));

        DialogoAdicionarServidor d = importando(arquivo);

        Espera.ate(PRAZO, () -> "importada (erro='" + Visual.naEdt(d::textoDoErro) + "')",
            () -> Visual.naEdt(() -> d.opcaoImportar().isSelected() && d.chaveEscolhida() != null
                && d.chaveEscolhida().impressao().equals(KeyUtils.getFingerPrint(par.getPublic()))));
        assertEquals(2, cofre.chaves().size(), "a criada pelo app e a importada");
        assertTrue(avisos.disse("ok", "o arquivo original não foi alterado"));
        assertEquals(KeyUtils.getFingerPrint(par.getPublic()), cofre.chaves().get(1).impressao());
    }

    @Test
    void arquivoProtegidoPorSenhaPedeASenhaDoArquivoComMensagemClaraEImportaComElaSemEscolherDeNovo() throws Exception {
        Path arquivo = tmp.resolve("id_com_senha");
        KeyPair par = KeyUtils.generateKeyPair("ssh-ed25519", 256);
        Files.write(arquivo, chaveOpenSsh(par, "frase-do-arquivo"));

        DialogoAdicionarServidor d = importando(arquivo);

        Espera.ate(PRAZO, "pedido de senha", () -> Visual.naEdt(d::mostraASenhaDoArquivo));
        assertEquals("Este arquivo está protegido por uma senha própria, que não é a senha-mestra. Digite-a abaixo.",
            Visual.naEdt(d::textoDoErro));
        assertEquals(1, cofre.chaves().size(), "nada entrou no cofre");
        Visual.salvar(Visual.renderizar(d.raiz(), 560, Visual.alturaPreferida(d.raiz(), 560)), "assistente-passo-2-senha-do-arquivo");

        Visual.naEdt(() -> d.campoDaSenhaDoArquivo().setText("senha-errada"));
        Visual.naEdt(() -> d.botaoImportarComSenha().doClick());
        Espera.ate(PRAZO, "senha errada", () -> Visual.naEdt(d::textoDoErro).contains("A senha do arquivo não confere"));
        assertEquals(1, cofre.chaves().size());
        assertTrue(Visual.naEdt(d::mostraASenhaDoArquivo), "continua pedindo");
        assertEquals("", new String(Visual.naEdt(() -> d.campoDaSenhaDoArquivo().getPassword())), "o campo é limpo a cada tentativa");

        Visual.naEdt(() -> d.campoDaSenhaDoArquivo().setText("frase-do-arquivo"));
        Visual.naEdt(() -> d.botaoImportarComSenha().doClick());
        Espera.ate(PRAZO, () -> "importada (erro='" + Visual.naEdt(d::textoDoErro) + "')",
            () -> Visual.naEdt(() -> d.chaveEscolhida() != null && d.chaveEscolhida().impressao().equals(KeyUtils.getFingerPrint(par.getPublic()))));
        assertFalse(Visual.naEdt(d::mostraASenhaDoArquivo));
        assertEquals(2, cofre.chaves().size());
    }

    @Test
    void arquivoQueNaoEChaveDizEssaMensagemENadaEntraNoCofre() throws Exception {
        Path lixo = tmp.resolve("lixo.txt");
        Files.writeString(lixo, "isto não é uma chave");
        DialogoAdicionarServidor d = importando(lixo);
        Espera.ate(PRAZO, "erro", () -> !Visual.naEdt(d::textoDoErro).isEmpty());
        assertEquals("Esse arquivo não é uma chave SSH.", Visual.naEdt(d::textoDoErro));
        assertFalse(Visual.naEdt(d::mostraASenhaDoArquivo));
        assertEquals(1, cofre.chaves().size());
    }

    @Test
    void impressaoDigitalDeServidorNoLugarDaChaveDizQueNaoEChaveEQueNaoPrecisaDela() throws Exception {
        Path arquivo = tmp.resolve("impressao.txt");
        Files.writeString(arquivo, "SHA256:" + KeyUtils.getFingerPrint(KeyUtils.generateKeyPair("ssh-ed25519", 256).getPublic()).substring(7)
            + "\n", StandardCharsets.UTF_8);
        DialogoAdicionarServidor d = importando(arquivo);
        Espera.ate(PRAZO, "erro", () -> !Visual.naEdt(d::textoDoErro).isEmpty());
        assertEquals("Isso é a impressão digital de um servidor, não uma chave. Você não precisa dela aqui.", Visual.naEdt(d::textoDoErro));
        assertFalse(Visual.naEdt(d::mostraASenhaDoArquivo), "não pede senha de uma impressão digital");
        assertEquals(1, cofre.chaves().size());
        assertTrue(ImportadorDeChaves.MENSAGEM_IMPRESSAO.startsWith("Isso é a impressão digital"));
    }

    @Test
    void chavePublicaNoLugarDaPrivadaTemMensagemPropria() throws Exception {
        Path arquivo = tmp.resolve("id_ed25519.pub");
        Files.writeString(arquivo, PublicKeyEntry.toString(KeyUtils.generateKeyPair("ssh-ed25519", 256).getPublic()) + " alguem@mac\n");
        DialogoAdicionarServidor d = importando(arquivo);
        Espera.ate(PRAZO, "erro", () -> !Visual.naEdt(d::textoDoErro).isEmpty());
        assertTrue(Visual.naEdt(d::textoDoErro).startsWith("Isso é uma chave pública."), Visual.naEdt(d::textoDoErro));
        assertEquals(1, cofre.chaves().size());
    }

    // ---------------------------------------------------------------------------------------------
    // Visual
    // ---------------------------------------------------------------------------------------------

    @Test
    void desenhaOsPassosNosDoisTemas() throws Exception {
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(tema);
            DialogoAdicionarServidor d = Visual.naEdt(() -> new DialogoAdicionarServidor(assistente, true, avisos, concluidos::add));
            Visual.salvar(Visual.renderizar(d.raiz(), 560, Visual.alturaPreferida(d.raiz(), 560)), "assistente-passo-1-" + tema.name().toLowerCase());
        }
    }
}
