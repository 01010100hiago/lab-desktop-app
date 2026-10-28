package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.InfoChave;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.instalador.ComandosRemotos;
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
 * O último passo do assistente "Adicionar servidor" quando o SSH conecta e o servidor NÃO tem o agente (regra: perguntar, nunca
 * erro): a pergunta "Quer instalar agora?" sem vermelho, sem a palavra "erro" e sem nenhuma etapa marcada como falha, com os dois botões
 * ("Instalar agente" e "Agora não, usar só por SSH"), e sem executar o registro do token antes de o agente existir.
 */
@ExtendWith(VerificaEdt.class)
class DialogoAdicionarServidorSemAgenteTest {

    private static final char[] SENHA = "uma-senha-mestra-de-teste".toCharArray();
    private static final Duration PRAZO = Duration.ofSeconds(30);

    @TempDir
    Path tmp;

    private ServidorSshDeTeste ssh;
    private AgenteDoLab agente;
    private CofreDeChaves cofre;
    private GerenciadorServidores gerenciador;
    private final AvisosGravados avisos = new AvisosGravados();
    private final List<ServidorLab> concluidos = new ArrayList<>();
    private final List<ServidorLab> pedidosDeInstalacao = new ArrayList<>();
    private final AtomicReference<Runnable> aoInstalar = new AtomicReference<>();

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

    private DialogoAdicionarServidor abrir() {
        AssistenteDeServidor assistente = new AssistenteDeServidor(cofre, gerenciador, ConfigSsh.rapida());
        DialogoAdicionarServidor d = Visual.naEdt(() -> new DialogoAdicionarServidor(assistente, true, avisos, concluidos::add,
            (servidor, endereco, resultado, aoDecidir) -> aoDecidir.accept(true), janela -> java.util.Optional.empty(),
            new CopiadorDeTexto(() -> new Clipboard("teste")), (lab, depois) -> {
                pedidosDeInstalacao.add(lab);
                aoInstalar.set(depois);
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

    /** Dados, chave, sondagem e conexão, até a pergunta do último passo. */
    private DialogoAdicionarServidor ateAPergunta() throws Exception {
        DialogoAdicionarServidor d = abrir();
        Visual.naEdt(() -> d.botaoContinuar().doClick());
        Espera.ate(PRAZO, "chave", () -> Visual.naEdt(() -> d.chaveEscolhida() != null && d.mostraACaixaDaChave()));
        InfoChave chave = Visual.naEdt(d::chaveEscolhida);
        ssh.aceitar(PublicKeyEntry.parsePublicKeyEntry(chave.publica()).resolvePublicKey(null, Collections.emptyMap(), PublicKeyEntryResolver.FAILING));
        Visual.naEdt(() -> d.botaoTestar().doClick());
        Espera.ate(PRAZO, () -> "pergunta (conexão=" + Visual.naEdt(d::estadoDaConexao) + ", msg=" + Visual.naEdt(d::mensagemDaConexao) + ")",
            () -> Visual.naEdt(d::perguntandoDoAgente));
        return d;
    }

    @Test
    void oUltimoPassoPerguntaQuerInstalarSemErroSemVermelhoESemRegistrarOToken() throws Exception {
        DialogoAdicionarServidor d = ateAPergunta();

        assertEquals(DialogoAdicionarServidor.TEXTO_DA_PERGUNTA_DO_AGENTE, Visual.naEdt(d::mensagemDaConexao));
        assertTrue(Visual.naEdt(d::mensagemDaConexao).startsWith("O servidor está conectado por SSH, mas o agente ainda não está instalado nele. "
            + "Quer instalar agora?"));
        String tudo = Visual.naEdt(() -> textos(d.raiz())).toLowerCase();
        assertFalse(tudo.contains("erro"), "a pergunta não usa a palavra erro: " + tudo);
        assertFalse(tudo.contains("falhou") || tudo.contains("não consegui"), tudo);
        for (Etapa e : Etapa.values()) {
            if (e != Etapa.PRONTO) {
                assertTrue(Visual.naEdt(() -> d.estadoDaEtapa(e)) != 3, "nenhuma etapa marcada como falha: " + e);
            }
        }
        assertEquals(2, Visual.naEdt(() -> d.estadoDaEtapa(Etapa.CONECTANDO)), "conectar deu certo");
        assertEquals(0, Visual.naEdt(() -> d.estadoDaEtapa(Etapa.REGISTRANDO_TOKEN)), "o registro do token espera o agente existir");
        assertTrue(Visual.naEdt(() -> d.botaoInstalarAgente().isVisible()));
        assertTrue(Visual.naEdt(() -> d.botaoAgoraNao().isVisible()));
        assertEquals("Instalar agente", Visual.naEdt(() -> d.botaoInstalarAgente().getText()));
        assertEquals("Agora não, usar só por SSH", Visual.naEdt(() -> d.botaoAgoraNao().getText()));
        assertFalse(Visual.naEdt(() -> d.botaoConcluir().isEnabled()));
        assertFalse(Visual.naEdt(() -> d.botaoTentarDeNovo().isVisible()));
        assertFalse(Visual.naEdt(() -> d.botaoSubstituirToken().isVisible()));
        // a faixa de estado da conexão diz a verdade sem alarme
        assertEquals("Conexão: Agente não instalado", Visual.naEdt(d::estadoDaConexao));

        assertTrue(agente.comandosCli.isEmpty(), "o registro do acesso NÃO rodou: " + agente.comandosCli);
        assertTrue(agente.tokens.isEmpty());
        for (String c : ssh.comandos) {
            assertTrue(ComandosRemotos.ehLeitura(c), "o assistente só leu o servidor: " + c);
        }
        assertTrue(pedidosDeInstalacao.isEmpty(), "nada abre sozinho: só o clique pede a instalação");
        Visual.salvar(Visual.renderizar(d.raiz(), 560, Visual.alturaPreferida(d.raiz(), 560)), "assistente-passo-3-pergunta-agente-claro");
        Visual.tema(Tema.ESCURO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(d.raiz()));
        Visual.salvar(Visual.renderizar(d.raiz(), 560, Visual.alturaPreferida(d.raiz(), 560)), "assistente-passo-3-pergunta-agente-escuro");
        Visual.tema(Tema.CLARO);
    }

    @Test
    void semOPacoteDoAgenteNoAppOUltimoPassoNaoOferecInstalarEDizPorque() throws Exception {
        String anterior = br.com.labdesktop.instalador.PacoteDoAgente.trocarDescritorParaTeste("/agente-que-nao-existe/agente.json");
        try {
            DialogoAdicionarServidor d = ateAPergunta();
            assertEquals(DialogoAdicionarServidor.TEXTO_SEM_O_AGENTE_NO_APP, Visual.naEdt(d::mensagemDaConexao));
            assertTrue(Visual.naEdt(d::mensagemDaConexao).contains("compilada sem o agente de monitoramento"));
            assertFalse(Visual.naEdt(d::mensagemDaConexao).contains("Quer instalar"), "não promete instalar o que não existe");
            assertFalse(Visual.naEdt(() -> d.botaoInstalarAgente().isVisible()), "sem pacote não há o que instalar");
            assertTrue(Visual.naEdt(() -> d.botaoAgoraNao().isVisible()), "o caminho é seguir só por SSH");
            assertFalse(Visual.naEdt(() -> textos(d.raiz())).toLowerCase().contains("erro"));
            assertTrue(pedidosDeInstalacao.isEmpty());
        } finally {
            br.com.labdesktop.instalador.PacoteDoAgente.trocarDescritorParaTeste(anterior);
        }
    }

    private static String textos(java.awt.Component c) {
        StringBuilder sb = new StringBuilder();
        if (!c.isVisible()) {
            return "";
        }
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

    @Test
    void agoraNaoConcluiOUsoSoPorSshOServidorFicaNoCofreSemToken() throws Exception {
        DialogoAdicionarServidor d = ateAPergunta();
        ServidorLab lab = Visual.naEdt(d::servidorCadastrado);

        Visual.naEdt(() -> d.botaoAgoraNao().doClick());

        assertEquals(List.of(lab), concluidos, "o servidor é entregue à janela, como num fim normal");
        assertTrue(avisos.disse("ok", "adicionado, só por SSH"), avisos.todos.toString());
        assertTrue(avisos.disse("ok", "cartão do servidor"), "diz onde instalar depois");
        assertTrue(cofre.servidor(lab.id()).isPresent());
        assertTrue(lab.estado().semToken());
        assertTrue(agente.tokens.isEmpty());
        assertEquals(EstadoConexao.SSH_OK_SEM_AGENTE, lab.estado().conexao(), "o servidor segue usável por SSH");
        assertTrue(pedidosDeInstalacao.isEmpty());
    }

    @Test
    void instalarAgentePedeAConfirmacaoEQuandoOAgenteExisteOAssistenteConclui() throws Exception {
        DialogoAdicionarServidor d = ateAPergunta();
        ServidorLab lab = Visual.naEdt(d::servidorCadastrado);

        Visual.naEdt(() -> d.botaoInstalarAgente().doClick());

        assertEquals(List.of(lab), pedidosDeInstalacao, "o botão só ABRE a confirmação (aqui, capturada)");
        assertTrue(agente.comandosCli.isEmpty());
        assertFalse(Visual.naEdt(() -> d.botaoConcluir().isEnabled()));

        // o usuário confirmou e o instalador pôs o agente no ar: o diálogo da confirmação avisa o assistente, que segue
        agente.fora = false;
        agente.perfil = "monitor";
        agente.diagnostico = "existe:running";
        Visual.naEdt(() -> aoInstalar.get().run());
        Espera.ate(PRAZO, () -> "pronto (msg=" + Visual.naEdt(d::mensagemDaConexao) + ")", () -> Visual.naEdt(() -> d.botaoConcluir().isEnabled()));
        assertFalse(Visual.naEdt(d::perguntandoDoAgente));
        assertTrue(Visual.naEdt(d::mensagemDaConexao).contains("Tudo certo: o servidor respondeu e o token deste Mac está registrado."),
            Visual.naEdt(d::mensagemDaConexao));
        assertEquals(1, agente.tokens.size(), "agora sim o token foi registrado, com o agente de pé");
        assertEquals(EstadoConexao.CONECTADO, lab.estado().conexao());
        assertFalse(Visual.naEdt(() -> d.botaoInstalarAgente().isVisible()));
        Visual.naEdt(() -> d.botaoConcluir().doClick());
        assertEquals(List.of(lab), concluidos);
    }
}
