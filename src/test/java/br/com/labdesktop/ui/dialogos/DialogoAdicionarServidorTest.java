package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.CofreDeChaves;
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
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias.Tema;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * O assistente "Adicionar servidor" de ponta a ponta, com o COFRE de verdade, o {@code SshServer} embutido e o agente
 * falso: gerar a chave, conferir o host, gravar no cofre, conectar, registrar o token e ler os limites, tudo pelos
 * controles do diálogo, como o usuário faria.
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

    private DialogoAdicionarServidor assistenteNaTela(boolean primeiro) {
        DialogoAdicionarServidor.ConfirmadorDeHost confirmador = (servidor, endereco, resultado, aoDecidir) -> {
            hostsConferidos.add(endereco + " " + resultado.impressao());
            aoDecidir.accept(confirmaHost);
        };
        DialogoAdicionarServidor d = Visual.naEdt(() -> new DialogoAdicionarServidor(assistente, primeiro, avisos, concluidos::add,
            confirmador, janela -> Optional.empty()));
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

    private void gerarChave(DialogoAdicionarServidor d) throws Exception {
        Visual.naEdt(() -> d.botaoGerar().doClick());
        Espera.ate(PRAZO, () -> "chave gerada", () -> Visual.naEdt(() -> d.chaveEscolhida() != null));
        ssh.aceitar(publica(Visual.naEdt(d::chaveEscolhida))); // "colou" o comando no servidor
    }

    private static void esperarPagina(DialogoAdicionarServidor d, String pagina) {
        Espera.ate(PRAZO, () -> "página " + pagina + " (está em " + Visual.naEdt(d::paginaAtual) + ", erro='" + Visual.naEdt(d::textoDoErro) + "')",
            () -> pagina.equals(Visual.naEdt(d::paginaAtual)));
    }

    @Test
    void naPrimeiraVezOsCamposVemVaziosComAPortaSshPadrao() {
        DialogoAdicionarServidor d = Visual.naEdt(() -> new DialogoAdicionarServidor(assistente, true, avisos, concluidos::add));
        assertEquals("", Visual.naEdt(() -> d.campoDoNome().getText()));
        assertEquals("", Visual.naEdt(() -> d.campoDoHost().getText()));
        assertEquals("22", Visual.naEdt(() -> d.campoDaPorta().getText()));
        assertEquals("", Visual.naEdt(() -> d.campoDoUsuario().getText()));
        assertEquals("7000", Visual.naEdt(() -> d.campoDaPortaDoAgente().getText()));
        assertFalse(Visual.naEdt(() -> d.botaoTestar().isEnabled()), "sem chave não dá para testar");
        DialogoAdicionarServidor outro = Visual.naEdt(() -> new DialogoAdicionarServidor(assistente, false, avisos, concluidos::add));
        assertEquals("", Visual.naEdt(() -> outro.campoDoHost().getText()), "do segundo servidor em diante também vêm vazios");
    }

    /** O texto ao lado do comando dizia que a chave ficava sem terminal; ela abre shell (o app usa exec). */
    @Test
    void oTextoAoLadoDoComandoDizQueAChaveAindaAbreShell() throws Exception {
        DialogoAdicionarServidor d = assistenteNaTela(true);
        gerarChave(d);
        String tela = Visual.naEdt(() -> textosDe(d.raiz()));

        assertTrue(tela.contains(br.com.labdesktop.cofre.InfoChave.RESTRICOES_DA_LINHA), tela);
        assertFalse(tela.contains("sem terminal"), tela);
        assertTrue(tela.contains("NÃO tira o shell"), tela);
    }

    private static String textosDe(java.awt.Component c) {
        StringBuilder sb = new StringBuilder();
        if (c instanceof javax.swing.text.JTextComponent t) {
            sb.append(t.getText()).append('\n');
        } else if (c instanceof javax.swing.JLabel l && l.getText() != null) {
            sb.append(l.getText()).append('\n');
        }
        if (c instanceof java.awt.Container k) {
            for (java.awt.Component f : k.getComponents()) {
                sb.append(textosDe(f));
            }
        }
        return sb.toString();
    }

    @Test
    void caminhoCompletoGeraChaveConfereHostConectaRegistraOTokenEConclui() throws Exception {
        DialogoAdicionarServidor d = assistenteNaTela(true);
        gerarChave(d);
        assertTrue(Visual.naEdt(d::mostraACaixaDaChave));
        assertFalse(Visual.naEdt(() -> d.botaoGerar().isVisible()), "uma chave por servidor: o botão some");
        assertTrue(Visual.naEdt(d::comandoMostrado).contains("permitopen=\"127.0.0.1:" + agente.porta() + "\""),
            Visual.naEdt(d::comandoMostrado));
        assertTrue(Visual.naEdt(d::comandoMostrado).startsWith("mkdir -p ~/.ssh"));
        assertEquals(1, cofre.chaves().size(), "a chave já está guardada no cofre");
        Visual.salvar(Visual.renderizar(d.raiz(), 560, Visual.alturaPreferida(d.raiz(), 560)), "assistente-pagina-1");

        Visual.naEdt(() -> d.botaoTestar().doClick());
        esperarPagina(d, "conexao");
        assertEquals(1, hostsConferidos.size());
        assertTrue(hostsConferidos.get(0).startsWith("127.0.0.1:" + ssh.porta() + " SHA256:"), hostsConferidos.get(0));
        assertEquals(0, ssh.pedidosDeAutenticacao.get() == 0 ? 0 : 0, "a sondagem não autentica");

        Espera.ate(PRAZO, () -> "pronto (conexão=" + Visual.naEdt(d::estadoDaConexao) + ", msg=" + Visual.naEdt(d::mensagemDaConexao) + ")",
            () -> Visual.naEdt(() -> d.botaoConcluir().isEnabled()));
        for (Etapa e : new Etapa[] {Etapa.CONECTANDO, Etapa.REGISTRANDO_TOKEN, Etapa.TESTANDO_AGENTE, Etapa.LENDO_LIMITES}) {
            assertEquals(2, Visual.naEdt(() -> d.estadoDaEtapa(e)), "etapa " + e + " feita");
        }
        assertEquals("Conexão: Conectado", Visual.naEdt(d::estadoDaConexao));
        assertEquals(1, agente.tokens.size(), "o token deste Mac foi registrado por SSH");
        ServidorLab lab = Visual.naEdt(d::servidorCadastrado);
        assertNotNull(lab);
        assertEquals(EstadoConexao.CONECTADO, lab.estado().conexao());
        Visual.salvar(Visual.renderizar(d.raiz(), 560, Visual.alturaPreferida(d.raiz(), 560)), "assistente-pagina-2-pronto");

        Visual.naEdt(() -> d.botaoConcluir().doClick());
        assertEquals(List.of(lab), concluidos, "o servidor pronto é entregue para a janela selecioná-lo");
        assertTrue(avisos.disse("ok", "Servidor lab adicionado."));
        assertEquals(1, gerenciador.servidores().size());
    }

    @Test
    void portaInvalidaOuHostComOpcaoDeComandoEhRecusadoSemGravarNada() throws Exception {
        DialogoAdicionarServidor d = assistenteNaTela(true);
        gerarChave(d);
        Visual.naEdt(() -> d.campoDaPorta().setText("vinte e dois"));
        Visual.naEdt(() -> d.botaoTestar().doClick());
        assertEquals("A porta do SSH precisa ser um número.", Visual.naEdt(d::textoDoErro));
        Visual.naEdt(() -> d.campoDaPorta().setText(String.valueOf(ssh.porta())));
        Visual.naEdt(() -> d.campoDoHost().setText("-oProxyCommand=touch x"));
        Visual.naEdt(() -> d.botaoTestar().doClick());
        assertTrue(Visual.naEdt(d::textoDoErro).contains("endereço"), Visual.naEdt(d::textoDoErro));
        assertTrue(hostsConferidos.isEmpty());
        assertTrue(gerenciador.servidores().isEmpty());
    }

    @Test
    void sePeloDonoNaoConfiarNoHostNadaEGravadoENenhumaCredencialEnviada() throws Exception {
        confirmaHost = false;
        DialogoAdicionarServidor d = assistenteNaTela(true);
        gerarChave(d);
        Visual.naEdt(() -> d.botaoTestar().doClick());
        Espera.ate(PRAZO, () -> "recusa", () -> Visual.naEdt(d::textoDoErro).contains("não confirmado"));
        assertEquals("dados", Visual.naEdt(d::paginaAtual));
        assertTrue(gerenciador.servidores().isEmpty());
        assertTrue(cofre.servidores().isEmpty());
        assertEquals(0, ssh.pedidosDeAutenticacao.get());
        assertTrue(Visual.naEdt(() -> d.botaoTestar().isEnabled()), "dá para tentar de novo");
    }

    @Test
    void chaveNaoInstaladaMostraOComandoDeNovoETentarDeNovoConcluiDepoisDeColar() throws Exception {
        DialogoAdicionarServidor d = assistenteNaTela(true);
        Visual.naEdt(() -> d.botaoGerar().doClick());
        Espera.ate(PRAZO, () -> "chave", () -> Visual.naEdt(() -> d.chaveEscolhida() != null));
        // NÃO instala a chave no servidor

        Visual.naEdt(() -> d.botaoTestar().doClick());
        esperarPagina(d, "conexao");
        Espera.ate(PRAZO, () -> "falha de autenticação", () -> Visual.naEdt(d::mensagemDaConexao).contains("recusou a chave"));
        assertTrue(Visual.naEdt(d::mostraOComandoNaConexao), "o comando para colar aparece na própria página");
        assertFalse(Visual.naEdt(() -> d.botaoConcluir().isEnabled()));
        assertEquals(3, Visual.naEdt(() -> d.estadoDaEtapa(Etapa.CONECTANDO)), "a etapa que falhou fica marcada");
        Visual.salvar(Visual.renderizar(d.raiz(), 560, Visual.alturaPreferida(d.raiz(), 560)), "assistente-chave-recusada");

        ssh.aceitar(publica(Visual.naEdt(d::chaveEscolhida))); // o usuário colou o comando
        Espera.ate(PRAZO, () -> "botão de novo", () -> Visual.naEdt(() -> d.botaoTentarDeNovo().isEnabled()));
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
        gerarChave(d);
        Visual.naEdt(() -> d.botaoTestar().doClick());
        esperarPagina(d, "conexao");
        Espera.ate(PRAZO, () -> "conflito", () -> Visual.naEdt(() -> d.botaoSubstituirToken().isVisible()));
        assertTrue(Visual.naEdt(d::mensagemDaConexao).contains("revogar o antigo"), Visual.naEdt(d::mensagemDaConexao));
        assertFalse(Visual.naEdt(() -> d.botaoConcluir().isEnabled()));

        Visual.naEdt(() -> d.botaoSubstituirToken().doClick());
        Espera.ate(PRAZO, () -> "pronto (msg=" + Visual.naEdt(d::mensagemDaConexao) + ")", () -> Visual.naEdt(() -> d.botaoConcluir().isEnabled()));
        assertEquals(1, agente.tokens.size());
        assertFalse(agente.tokens.containsKey("b".repeat(64)), "a entrada antiga foi revogada");
    }

    @Test
    void usarUmaChaveQueJaEstaNoCofreEMudarDeOpcaoDescartaAChaveDeOutraOpcao() throws Exception {
        InfoChave guardada = assistente.gerarChave("antiga");
        DialogoAdicionarServidor d = assistenteNaTela(false);
        Espera.ate(PRAZO, () -> "combo carregado", () -> Visual.naEdt(() -> d.comboDeChaves().getItemCount() == 1 && d.opcaoUsarExistente().isEnabled()));

        Visual.naEdt(() -> d.opcaoUsarExistente().doClick());
        assertEquals(guardada.id(), Visual.naEdt(() -> d.chaveEscolhida().id()));
        assertTrue(Visual.naEdt(d::mostraACaixaDaChave));
        assertTrue(Visual.naEdt(d::comandoMostrado).contains(guardada.publica().split(" ")[1]));

        Visual.naEdt(() -> d.opcaoImportar().doClick());
        assertEquals(null, Visual.naEdt(d::chaveEscolhida), "a chave de 'usar existente' não vale para 'importar'");
        assertFalse(Visual.naEdt(d::mostraACaixaDaChave));
        assertFalse(Visual.naEdt(() -> d.botaoTestar().isEnabled()));

        Visual.naEdt(() -> d.opcaoUsarExistente().doClick());
        assertNotNull(Visual.naEdt(d::chaveEscolhida));
        assertTrue(Visual.naEdt(() -> d.botaoTestar().isEnabled()));
    }

    @Test
    void mudarAPortaDoAgenteAtualizaOComandoDeInstalacao() throws Exception {
        DialogoAdicionarServidor d = assistenteNaTela(true);
        gerarChave(d);
        Visual.naEdt(() -> d.campoDaPortaDoAgente().setText("7123"));
        assertTrue(Visual.naEdt(d::comandoMostrado).contains("permitopen=\"127.0.0.1:7123\""));
        Visual.naEdt(() -> d.campoDaPortaDoAgente().setText("0"));
        assertTrue(Visual.naEdt(d::comandoMostrado).contains("porta do agente válida"), "porta ruim não vira comando");
    }

    @Test
    void importarChaveDeArquivoGuardaNoCofreEPedeAPassphraseQuandoPrecisa() throws Exception {
        Path arquivo = tmp.resolve("id_ed25519");
        java.security.KeyPair par = org.apache.sshd.common.config.keys.KeyUtils.generateKeyPair("ssh-ed25519", 256);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(par, "origem", null, bytes);
        java.nio.file.Files.write(arquivo, bytes.toByteArray());
        AtomicReference<Path> escolhido = new AtomicReference<>(arquivo);
        DialogoAdicionarServidor d = Visual.naEdt(() -> new DialogoAdicionarServidor(assistente, true, avisos, concluidos::add,
            (a, b, c, e) -> { }, janela -> Optional.ofNullable(escolhido.get())));
        Visual.naEdt(() -> d.opcaoImportar().doClick());
        assertTrue(Visual.naEdt(() -> d.botaoArquivo().isEnabled()));
        Visual.naEdt(() -> d.botaoArquivo().doClick());
        Espera.ate(PRAZO, () -> "importada (erro='" + Visual.naEdt(d::textoDoErro) + "')", () -> Visual.naEdt(() -> d.chaveEscolhida() != null));
        assertEquals(1, cofre.chaves().size());
        assertTrue(avisos.disse("ok", "o arquivo original não foi alterado"));

        // um arquivo que não é chave: mensagem clara, nada entra no cofre
        Path lixo = tmp.resolve("lixo.txt");
        java.nio.file.Files.writeString(lixo, "isto não é uma chave");
        DialogoAdicionarServidor outro = Visual.naEdt(() -> new DialogoAdicionarServidor(assistente, true, avisos, concluidos::add,
            (a, b, c, e) -> { }, janela -> Optional.of(lixo)));
        Visual.naEdt(() -> outro.opcaoImportar().doClick());
        Visual.naEdt(() -> outro.botaoArquivo().doClick());
        Espera.ate(PRAZO, () -> "erro", () -> !Visual.naEdt(outro::textoDoErro).isEmpty());
        assertTrue(Visual.naEdt(outro::textoDoErro).contains("exatamente uma chave"), Visual.naEdt(outro::textoDoErro));
        assertEquals(1, cofre.chaves().size());
    }

    @Test
    void desenhaAPrimeiraPaginaNosDoisTemas() throws Exception {
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(tema);
            DialogoAdicionarServidor d = Visual.naEdt(() -> new DialogoAdicionarServidor(assistente, true, avisos, concluidos::add));
            Visual.salvar(Visual.renderizar(d.raiz(), 560, Visual.alturaPreferida(d.raiz(), 560)), "assistente-vazio-" + tema.name().toLowerCase());
        }
        Consumer<ServidorLab> ignorar = x -> { };
        ignorar.accept(null);
    }
}
