package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.ChaveSsh;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.ConfigServidor;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.cofre.GeradorDeChaves;
import br.com.labdesktop.cofre.InfoChave;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.servidor.AssistenteDeServidor;
import br.com.labdesktop.servidor.AssistenteDeServidor.Dados;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.ProtecaoDoServidor;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * A caixa "Servidor protegido" nos dois diálogos, com o cofre em arquivo temporário e o SSH embutido do repo: ligar, desligar e persistir no Editar
 * servidor; a caixa desmarcada por padrão no primeiro passo de Adicionar servidor, o que ela grava, e o último passo que conclui como Só SSH sem perguntar.
 */
@ExtendWith(VerificaEdt.class)
class ServidorProtegidoNosDialogosTest {

    private static final char[] SENHA = "uma-senha-mestra-de-teste".toCharArray();
    private static final Duration PRAZO = Duration.ofSeconds(30);

    @TempDir
    Path tmp;

    private ServidorSshDeTeste ssh;
    private AgenteDoLab agente;
    private CofreDeChaves cofre;
    private GerenciadorServidores gerenciador;
    private final AvisosGravados avisos = new AvisosGravados();

    @BeforeEach
    void montar() throws Exception {
        Visual.tema(Tema.CLARO);
        ssh = ServidorSshDeTeste.ed25519();
        agente = new AgenteDoLab();
        agente.fora = true;
        agente.diagnostico = "ausente";
        ssh.comandoRemoto(agente.comandosPorSsh());
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

    private ServidorLab servidorNoCofre(boolean protegido) throws Exception {
        ChaveSsh chave = GeradorDeChaves.gerar("k", "mac");
        cofre.adicionarChave(chave);
        ConfigServidor cfg = new ConfigServidor(ConfigServidor.novoId(), "vps-1", "192.0.2.5", 2222, "labuser", chave.id(), 7000, List.of(),
            Transporte.DIRETO, null, Estado2fa.DESLIGADO, protegido, false);
        cofre.adicionarServidor(cfg, null);
        return gerenciador.adicionar(cfg.id()); // sem iniciar: nenhuma conexão
    }

    // ---------------------------------------------------------------------------------------------
    // Editar servidor
    // ---------------------------------------------------------------------------------------------

    private DialogoEditarServidor editar(ServidorLab lab, List<Dados> gravados) {
        return Visual.naEdt(() -> new DialogoEditarServidor(lab.config(), dados -> {
            gravados.add(dados);
            return gerenciador.atualizar(lab.id(), dados);
        }, avisos));
    }

    @Test
    void aCaixaMostraOEstadoAtualEAFraseDoDono() throws Exception {
        ServidorLab comum = servidorNoCofre(false);
        DialogoEditarServidor d = editar(comum, new ArrayList<>());
        assertEquals("Servidor protegido", Visual.naEdt(() -> d.caixaProtegido().getText()));
        assertFalse(Visual.naEdt(() -> d.caixaProtegido().isSelected()));
        String tudo = Visual.naEdt(() -> textos(d.raiz()));
        assertTrue(tudo.contains("O app nunca instala, atualiza nem remove nada neste servidor. Use para servidores que já estão em produção."), tudo);

        ServidorLab protegido = servidorNoCofre(true);
        assertTrue(Visual.naEdt(() -> editar(protegido, new ArrayList<>()).caixaProtegido().isSelected()));
    }

    @Test
    void marcarLigaOSalvarSemDizerReconectarEDesfazerODesligaDeNovo() throws Exception {
        ServidorLab lab = servidorNoCofre(false);
        DialogoEditarServidor d = editar(lab, new ArrayList<>());
        assertFalse(Visual.naEdt(() -> d.botaoSalvar().isEnabled()));
        assertEquals("Salvar e reconectar", Visual.naEdt(() -> d.botaoSalvar().getText()));

        Visual.naEdt(() -> d.caixaProtegido().doClick());
        assertTrue(Visual.naEdt(() -> d.botaoSalvar().isEnabled()), "mudar a caixa já é uma mudança");
        assertEquals("Salvar", Visual.naEdt(() -> d.botaoSalvar().getText()), "só a proteção mudou: não reconecta nada");

        Visual.naEdt(() -> d.campoDoNome().setText("vps-2"));
        assertEquals("Salvar e reconectar", Visual.naEdt(() -> d.botaoSalvar().getText()), "mudou também o nome: reconecta");

        Visual.naEdt(() -> d.campoDoNome().setText("vps-1"));
        Visual.naEdt(() -> d.caixaProtegido().doClick()); // desfez
        assertFalse(Visual.naEdt(() -> d.botaoSalvar().isEnabled()), "voltou ao que era: nada a salvar");
    }

    @Test
    void ligarSalvarEPersistirNoCofreDepoisDesligarEPersistir() throws Exception {
        ServidorLab lab = servidorNoCofre(false);
        List<Dados> gravados = new ArrayList<>();
        DialogoEditarServidor d = editar(lab, gravados);

        Visual.naEdt(() -> d.caixaProtegido().doClick());
        Visual.naEdt(() -> d.botaoSalvar().doClick());
        Espera.ate(PRAZO, "gravou", () -> cofre.servidor(lab.id()).orElseThrow().protegido());
        assertEquals(Boolean.TRUE, gravados.get(0).protegido(), "os dados levam o valor da caixa");
        Espera.ate(PRAZO, "avisou", () -> avisos.disse("ok", "protegido"));
        assertTrue(avisos.disse("ok", "o app não instala, atualiza nem remove nada nele"), avisos.todos.toString());
        assertTrue(lab.estado().protegido());

        // o usuário reabre e desmarca
        List<Dados> segunda = new ArrayList<>();
        DialogoEditarServidor d2 = editar(lab, segunda);
        assertTrue(Visual.naEdt(() -> d2.caixaProtegido().isSelected()), "a caixa reabre marcada");
        Visual.naEdt(() -> d2.caixaProtegido().doClick());
        Visual.naEdt(() -> d2.botaoSalvar().doClick());
        Espera.ate(PRAZO, "desgravou", () -> !cofre.servidor(lab.id()).orElseThrow().protegido());
        assertEquals(Boolean.FALSE, segunda.get(0).protegido());
        assertTrue(cofre.servidor(lab.id()).orElseThrow().protecaoAutomaticaRecusada());
        Espera.ate(PRAZO, "aviso de desligado", () -> avisos.disse("ok", "sem proteção"));
        assertFalse(lab.estado().protegido());
    }

    @Test
    void salvarSemMexerNaCaixaNemNoRestoNaoGrava() throws Exception {
        ServidorLab lab = servidorNoCofre(true);
        List<Dados> gravados = new ArrayList<>();
        DialogoEditarServidor d = editar(lab, gravados);
        Visual.naEdt(() -> d.botaoSalvar().doClick());
        assertTrue(gravados.isEmpty());
    }

    // ---------------------------------------------------------------------------------------------
    // Adicionar servidor
    // ---------------------------------------------------------------------------------------------

    private final List<ServidorLab> concluidos = new ArrayList<>();
    private final List<ServidorLab> pedidosDeInstalacao = new ArrayList<>();
    private final AtomicReference<Runnable> aoSoSsh = new AtomicReference<>();

    private DialogoAdicionarServidor abrir(boolean primeiro) {
        AssistenteDeServidor assistente = new AssistenteDeServidor(cofre, gerenciador, ConfigSsh.rapida());
        DialogoAdicionarServidor d = Visual.naEdt(() -> new DialogoAdicionarServidor(assistente, primeiro, avisos, concluidos::add,
            (servidor, endereco, resultado, aoDecidir) -> aoDecidir.accept(true), janela -> java.util.Optional.empty(),
            new CopiadorDeTexto(() -> new Clipboard("teste")), (lab, depois, soSsh) -> {
                pedidosDeInstalacao.add(lab);
                aoSoSsh.set(soSsh);
            }));
        Visual.naEdt(() -> {
            d.campoDoNome().setText("vps-1");
            d.campoDoHost().setText("127.0.0.1");
            d.campoDaPorta().setText(String.valueOf(ssh.porta()));
            d.campoDoUsuario().setText("labuser");
            d.campoDaPortaDoAgente().setText(String.valueOf(agente.porta()));
        });
        return d;
    }

    private void ateOFim(DialogoAdicionarServidor d, java.util.function.BooleanSupplier ate, String o) throws Exception {
        Visual.naEdt(() -> d.botaoContinuar().doClick());
        Espera.ate(PRAZO, "chave", () -> Visual.naEdt(() -> d.chaveEscolhida() != null && d.mostraACaixaDaChave()));
        InfoChave chave = Visual.naEdt(d::chaveEscolhida);
        PublicKey publica = PublicKeyEntry.parsePublicKeyEntry(chave.publica()).resolvePublicKey(null, Collections.emptyMap(), PublicKeyEntryResolver.FAILING);
        ssh.aceitar(publica);
        Visual.naEdt(() -> d.botaoTestar().doClick());
        Espera.ate(PRAZO, () -> o + " (conexão=" + Visual.naEdt(d::estadoDaConexao) + ", msg=" + Visual.naEdt(d::mensagemDaConexao) + ")", ate);
    }

    @Test
    void aCaixaDoPrimeiroPassoNasceDesmarcadaTantoNaPrimeiraVezQuantoNasOutras() {
        for (boolean primeiro : new boolean[] {true, false}) {
            DialogoAdicionarServidor d = abrir(primeiro);
            assertFalse(Visual.naEdt(() -> d.caixaProtegido().isSelected()), "primeiro=" + primeiro);
            assertEquals("Servidor protegido", Visual.naEdt(() -> d.caixaProtegido().getText()));
            assertTrue(Visual.naEdt(() -> d.caixaProtegido().isShowing() || d.caixaProtegido().isVisible()));
            String tudo = Visual.naEdt(() -> textos(d.raiz()));
            assertTrue(tudo.contains("O app nunca instala, atualiza nem remove nada neste servidor. Use para servidores que já estão em produção."), tudo);
        }
    }

    @Test
    void semMarcarACaixaOServidorSemAgentePerguntaComoSempreEFicaDesprotegido() throws Exception {
        DialogoAdicionarServidor d = abrir(true);
        ateOFim(d, () -> Visual.naEdt(d::perguntandoDoAgente), "pergunta");
        ServidorLab lab = Visual.naEdt(d::servidorCadastrado);
        assertFalse(cofre.servidor(lab.id()).orElseThrow().protegido());
        assertTrue(Visual.naEdt(() -> d.botaoInstalarAgente().isVisible()), "a pergunta de sempre");
        assertTrue(pedidosDeInstalacao.isEmpty());
    }

    @Test
    void comACaixaMarcadaOServidorJaNasceProtegidoEOUltimoPassoConcluiComoSoSshSemPerguntar() throws Exception {
        DialogoAdicionarServidor d = abrir(true);
        Visual.naEdt(() -> d.caixaProtegido().doClick());
        assertTrue(Visual.naEdt(() -> d.caixaProtegido().isSelected()));

        ateOFim(d, () -> !concluidos.isEmpty() || Visual.naEdt(d::perguntandoDoAgente), "conclusão");

        assertFalse(Visual.naEdt(d::perguntandoDoAgente), "protegido: o assistente não pergunta");
        assertFalse(Visual.naEdt(() -> d.botaoInstalarAgente().isVisible()));
        assertEquals(1, concluidos.size(), "concluiu como Só SSH (o servidor é entregue à janela)");
        ServidorLab lab = concluidos.get(0);
        assertTrue(cofre.servidor(lab.id()).orElseThrow().protegido(), "gravado no cofre desde o cadastro");
        assertTrue(lab.estado().protegido());
        assertTrue(pedidosDeInstalacao.isEmpty(), "ninguém pediu para instalar");
        assertTrue(avisos.disse("ok", "só por SSH e protegido"), avisos.todos.toString());
        assertTrue(lab.estado().semToken(), "nada foi registrado: não há agente");
        assertTrue(agente.comandosCli.isEmpty());
        for (String c : ssh.comandos) {
            assertTrue(br.com.labdesktop.instalador.ComandosRemotos.ehLeitura(c), "só leitura: " + c);
        }
    }

    @Test
    void oRotuloDaCaixaEAFraseSaoAsDoDono() {
        assertEquals("Servidor protegido", ProtecaoDoServidor.ROTULO_DA_CAIXA);
        assertEquals("O app nunca instala, atualiza nem remove nada neste servidor. Use para servidores que já estão em produção.", ProtecaoDoServidor.FRASE_DA_CAIXA);
        assertNotNull(ProtecaoDoServidor.RECUSA);
    }

    private static String textos(java.awt.Component c) {
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
                sb.append(textos(f));
            }
        }
        return sb.toString();
    }
}
