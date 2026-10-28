package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.acesso.ComandosDoAcesso;
import br.com.labdesktop.api.PedidoDeCodigo2fa;
import br.com.labdesktop.cofre.CofreDeChaves;
import br.com.labdesktop.cofre.InfoChave;
import br.com.labdesktop.cofre.ParametrosKdf;
import br.com.labdesktop.servidor.AssistenteDeServidor;
import br.com.labdesktop.servidor.GerenciadorServidores;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ssh.ConfigSsh;
import br.com.labdesktop.teste.AgenteDoLab;
import br.com.labdesktop.teste.ServidorSshDeTeste;
import br.com.labdesktop.teste.ShellDeTeste;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.SemTransbordo;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * O assistente "Adicionar servidor" pelo caminho da SENHA, pelos controles do diálogo, com o cofre, o servidor SSH embutido (senha e chave), o
 * {@code sh} de verdade instalando a chave e o agente falso: confere o host ANTES de a senha sair, entra uma vez, instala e prova a chave, conecta sem
 * pedir senha de novo e, no fim, pergunta (sem urgência) se desliga o login por senha. PNGs dos passos nos dois temas.
 */
@ExtendWith(VerificaEdt.class)
class DialogoAdicionarServidorSenhaTest {

    private static final char[] SENHA_MESTRA = "uma-senha-mestra-de-teste".toCharArray();
    private static final Duration PRAZO = Duration.ofSeconds(30);
    private static final String SENHA_DO_SERVIDOR = "senha-do-servidor-Zk4!";

    @TempDir
    Path tmp;

    private ServidorSshDeTeste ssh;
    private AgenteDoLab agente;
    private ShellDeTeste shell;
    private CofreDeChaves cofre;
    private GerenciadorServidores gerenciador;
    private AssistenteDeServidor assistente;
    private final AvisosGravados avisos = new AvisosGravados();
    private final Clipboard areaDeTransferencia = new Clipboard("teste");
    private final List<ServidorLab> concluidos = new ArrayList<>();
    private final List<Integer> senhasVistasQuandoOHostFoiConferido = new ArrayList<>();
    private final List<ServidorLab> pedidosParaDesligarASenha = new ArrayList<>();
    private volatile boolean confirmaHost = true;

    @BeforeEach
    void montar() throws Exception {
        assumeTrue(ShellDeTeste.temShell(), "precisa de /bin/sh");
        Visual.tema(Tema.CLARO);
        ssh = ServidorSshDeTeste.ed25519();
        ssh.aceitarSenha("labuser", SENHA_DO_SERVIDOR);
        agente = new AgenteDoLab();
        shell = new ShellDeTeste(Files.createDirectories(tmp.resolve("home")), ssh).outrosComandos(agente.comandosPorSsh());
        cofre = CofreDeChaves.criar(CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs")), SENHA_MESTRA.clone(), ParametrosKdf.MINIMO);
        gerenciador = new GerenciadorServidores(cofre, PedidoDeCodigo2fa.NUNCA, ConfigSsh.rapida());
        assistente = new AssistenteDeServidor(cofre, gerenciador, ConfigSsh.rapida());
    }

    @AfterEach
    void desmontar() throws Exception {
        if (gerenciador != null) {
            gerenciador.fechar().join();
            cofre.close();
            agente.close();
            ssh.close();
        }
        Visual.tema(Tema.CLARO);
    }

    private DialogoAdicionarServidor assistenteNaTela() {
        DialogoAdicionarServidor.ConfirmadorDeHost confirmador = (servidor, endereco, resultado, aoDecidir) -> {
            senhasVistasQuandoOHostFoiConferido.add(ssh.senhasVistas.size());
            aoDecidir.accept(confirmaHost);
        };
        DialogoAdicionarServidor d = Visual.naEdt(() -> new DialogoAdicionarServidor(assistente, true, avisos, concluidos::add, confirmador,
            janela -> Optional.empty(), new CopiadorDeTexto(() -> areaDeTransferencia), null, (lab, depois) -> {
                pedidosParaDesligarASenha.add(lab);
                depois.run();
            }));
        Visual.naEdt(() -> {
            d.campoDoNome().setText("lab");
            d.campoDoHost().setText("127.0.0.1");
            d.campoDaPorta().setText(String.valueOf(ssh.porta()));
            d.campoDoUsuario().setText("labuser");
            d.campoDaPortaDoAgente().setText(String.valueOf(agente.porta()));
        });
        return d;
    }

    private InfoChave noPassoDaChave(DialogoAdicionarServidor d) {
        Visual.naEdt(() -> d.botaoContinuar().doClick());
        Espera.ate(PRAZO, () -> "passo da chave (erro='" + Visual.naEdt(d::textoDoErro) + "')",
            () -> "chave".equals(Visual.naEdt(d::paginaAtual)) && Visual.naEdt(() -> d.chaveEscolhida() != null));
        return Visual.naEdt(d::chaveEscolhida);
    }

    private static void esperarPagina(DialogoAdicionarServidor d, String pagina) {
        Espera.ate(PRAZO, () -> "página " + pagina + " (está em " + Visual.naEdt(d::paginaAtual) + ", erro='" + Visual.naEdt(d::textoDoErro) + "')",
            () -> pagina.equals(Visual.naEdt(d::paginaAtual)));
    }

    private static void digitarEEntrar(DialogoAdicionarServidor d, String senha) {
        Visual.naEdt(() -> d.campoDaSenhaDoServidor().setText(senha));
        Visual.naEdt(() -> d.botaoEntrarComSenha().doClick());
    }

    /** Sem transbordo na largura do diálogo (560) e numa janela de 1100 x 700 (o diálogo cresce de lado e continua sem sair de nenhum contêiner nem rolar para o lado). */
    private static void semTransbordo(DialogoAdicionarServidor d) {
        for (int largura : new int[] {560, 1100}) {
            Visual.alturaPreferida(d.raiz(), largura);
            List<String> achados = Visual.naEdt(() -> SemTransbordo.achados(d.raiz()));
            assertTrue(achados.isEmpty(), "transborda em " + largura + " px: " + achados);
        }
        Visual.alturaPreferida(d.raiz(), 560);
    }

    @Test
    void oPassoDaChaveOfereceASenhaComoCaminhoPrincipalEGuardaAChavePublicaNasOpcoesAvancadas() throws Exception {
        DialogoAdicionarServidor d = assistenteNaTela();
        noPassoDaChave(d);

        assertEquals("Senha do usuário labuser no servidor (usada só agora, não fica guardada)", Visual.naEdt(d::rotuloDoCampoDaSenha));
        assertTrue(Visual.naEdt(() -> d.botaoEntrarComSenha().isVisible() && d.botaoEntrarComSenha().isEnabled()));
        assertEquals(Visual.naEdt(d::botaoEntrarComSenha), Visual.naEdt(d::botaoDoEnter), "o Enter entra com a senha");
        assertTrue(Visual.naEdt(() -> javax.swing.SwingUtilities.isDescendingFrom(d.botaoTestar(), d.opcoesAvancadas())), "o caminho de antes mora nas opções avançadas");
        assertFalse(Visual.naEdt(() -> d.opcoesAvancadas().aberta()), "e elas começam fechadas");
        Visual.naEdt(() -> d.opcoesAvancadas().botao().doClick());
        assertTrue(Visual.naEdt(() -> d.opcoesAvancadas().aberta()));
        assertTrue(textosDe(d).contains(DialogoAdicionarServidor.TEXTO_DA_SENHA), "diz o que o app faz com a senha: " + textosDe(d));
        semTransbordo(d);
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(tema);
            DialogoAdicionarServidor novo = assistenteNaTela();
            noPassoDaChave(novo);
            Visual.salvar(Visual.renderizar(novo.raiz(), 560, Visual.alturaPreferida(novo.raiz(), 560)), "assistente-passo-2-senha-" + tema.name().toLowerCase());
            Visual.naEdt(() -> novo.opcoesAvancadas().botao().doClick());
            Visual.salvar(Visual.renderizar(novo.raiz(), 560, Visual.alturaPreferida(novo.raiz(), 560)), "assistente-passo-2-senha-avancado-" + tema.name().toLowerCase());
        }
    }

    @Test
    void caminhoCompletoPelaSenhaConfereOHostAntesDaSenhaInstalaProvaConectaEPerguntaSeDesliga() throws Exception {
        DialogoAdicionarServidor d = assistenteNaTela();
        InfoChave chave = noPassoDaChave(d);

        digitarEEntrar(d, SENHA_DO_SERVIDOR);
        esperarPagina(d, "conexao");

        assertEquals(List.of(0), senhasVistasQuandoOHostFoiConferido, "quando o usuário confirmou a impressão digital, o servidor AINDA não tinha visto nenhuma senha");
        assertEquals(List.of(SENHA_DO_SERVIDOR), ssh.senhasVistas, "a senha saiu uma vez, depois da confirmação");
        assertEquals("", new String(Visual.naEdt(() -> d.campoDaSenhaDoServidor().getPassword())), "o campo da senha foi apagado");
        assertTrue(Files.readString(shell.authorizedKeys()).contains(chave.publica().split(" ")[1]), "a chave foi instalada no authorized_keys");
        assertTrue(Visual.naEdt(d::senhaFoiUsada));
        assertEquals(1, cofre.servidores().size(), "o servidor entrou no cofre depois da prova");

        Espera.ate(PRAZO, () -> "pronto (msg=" + Visual.naEdt(d::mensagemDaConexao) + ")", () -> Visual.naEdt(() -> d.botaoConcluir().isEnabled()));
        assertEquals(1, ssh.senhasVistas.size(), "o passo 3 só usa a chave: nenhuma senha a mais");
        assertEquals(1, agente.tokens.size());
        Visual.salvar(Visual.renderizar(d.raiz(), 560, Visual.alturaPreferida(d.raiz(), 560)), "assistente-passo-3-chave-instalada");

        Visual.naEdt(() -> d.botaoConcluir().doClick());
        assertEquals("senha", Visual.naEdt(d::paginaAtual), "depois de concluir, a pergunta opcional; a janela NÃO fecha sozinha");
        assertEquals(List.of(), concluidos);
        assertEquals("Manter a senha", Visual.naEdt(() -> d.botaoManterASenha().getText()));
        assertEquals(Visual.naEdt(d::botaoManterASenha), Visual.naEdt(d::botaoDoEnter), "o padrão é Manter a senha");
        String tela = textosDe(d);
        assertTrue(tela.contains(DialogoAdicionarServidor.TEXTO_DA_PERGUNTA_DA_SENHA), tela);
        assertTrue(tela.contains("É mais seguro: ninguém consegue adivinhar a senha. Você continua entrando pelo app, que usa a chave."), tela);
        assertEquals(List.of(), pedidosParaDesligarASenha, "perguntar não desliga nada");
        assertTrue(ssh.comandos.stream().noneMatch(c -> c.contains("sshd")), "nenhum comando do sshd rodou: " + ssh.comandos);
        semTransbordo(d);
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(tema);
            DialogoAdicionarServidor novo = assistenteNaTela();
            irAteAPergunta(novo);
            Visual.salvar(Visual.renderizar(novo.raiz(), 560, Visual.alturaPreferida(novo.raiz(), 560)), "assistente-passo-4-pergunta-" + tema.name().toLowerCase());
        }
    }

    private void irAteAPergunta(DialogoAdicionarServidor d) throws Exception {
        // cada tema monta um servidor novo: limpa o que o anterior deixou para o caminho completo valer outra vez
        Files.deleteIfExists(shell.authorizedKeys());
        for (var s : new ArrayList<>(gerenciador.servidores())) {
            gerenciador.removerServidor(s.id(), new br.com.labdesktop.servidor.GerenciadorServidores.OpcoesDeRemocao(false, false));
        }
        agente.tokens.clear();
        noPassoDaChave(d);
        digitarEEntrar(d, SENHA_DO_SERVIDOR);
        esperarPagina(d, "conexao");
        Espera.ate(PRAZO, "pronto", () -> Visual.naEdt(() -> d.botaoConcluir().isEnabled()));
        Visual.naEdt(() -> d.botaoConcluir().doClick());
        esperarPagina(d, "senha");
    }

    private static String textosDe(DialogoAdicionarServidor d) {
        return Visual.naEdt(() -> br.com.labdesktop.ui.TextosDeTela.de(d.raiz()));
    }

    @Test
    void osBotoesDoPassoDaSenhaEDaPerguntaEnxergamSeNosDoisTemas() throws Exception {
        List<String> ruins = new ArrayList<>();
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            for (boolean naPergunta : new boolean[] {false, true}) {
                Visual.tema(tema);
                DialogoAdicionarServidor d = assistenteNaTela();
                if (naPergunta) {
                    irAteAPergunta(d);
                } else {
                    noPassoDaChave(d);
                }
                javax.swing.JRootPane raiz = Visual.naEdt(() -> {
                    javax.swing.JRootPane r = new javax.swing.JRootPane();
                    r.setContentPane(d.raiz());
                    r.setDefaultButton(d.botaoDoEnter());
                    return r;
                });
                int h = Visual.alturaPreferida(d.raiz(), 560) + 20;
                for (var m : Visual.naEdt(() -> br.com.labdesktop.ui.AuditoriaDeCores.auditar(raiz, 560, h, true))) {
                    if (m.falhou()) {
                        ruins.add(tema + (naPergunta ? " pergunta" : " senha") + " | " + m);
                    }
                }
            }
        }
        assertTrue(ruins.isEmpty(), "botões com pouco contraste:\n" + String.join("\n", ruins));
    }

    @Test
    void manterASenhaEntregaOServidorEFechaSemMexerNoSshd() throws Exception {
        DialogoAdicionarServidor d = assistenteNaTela();
        irAteAPergunta(d);
        Visual.naEdt(() -> d.botaoManterASenha().doClick());
        assertEquals(1, concluidos.size(), "o servidor pronto é entregue à janela");
        assertTrue(Visual.naEdt(d::paginaAtual).equals("senha"));
        assertEquals(List.of(), pedidosParaDesligarASenha);
        assertTrue(ssh.comandos.stream().noneMatch(c -> c.contains("sshd")));
    }

    @Test
    void desligarASenhaSoAbreAConfirmacaoEQuandoElaFechaOServidorEEntregue() throws Exception {
        DialogoAdicionarServidor d = assistenteNaTela();
        irAteAPergunta(d);
        Visual.naEdt(() -> d.botaoDesligarASenha().doClick());
        assertEquals(1, pedidosParaDesligarASenha.size(), "abriu a confirmação para este servidor");
        assertEquals(concluidos.get(0), pedidosParaDesligarASenha.get(0));
        assertTrue(ssh.comandos.stream().noneMatch(c -> c.contains("sshd")), "clicar em Desligar a senha só abre a confirmação: nada foi mexido no servidor");
    }

    @Test
    void senhaErradaMostraAFraseApagaOCampoENadaEGravadoEAPausaVemDepoisDeQuatro() throws Exception {
        java.util.concurrent.atomic.AtomicReference<java.time.Instant> relogio = new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.parse("2026-10-28T12:00:00Z"));
        assistente = new AssistenteDeServidor(cofre, gerenciador, ConfigSsh.rapida(), new br.com.labdesktop.acesso.LimiteDeTentativas(
            AssistenteDeServidor.MAXIMO_DE_SENHAS_RECUSADAS, AssistenteDeServidor.JANELA_DAS_SENHAS_RECUSADAS, relogio::get));
        DialogoAdicionarServidor d = assistenteNaTela();
        noPassoDaChave(d);
        for (int i = 1; i <= 4; i++) {
            relogio.updateAndGet(t -> t.plusSeconds(20));
            digitarEEntrar(d, "errada-" + i);
            int quantas = i;
            Espera.ate(PRAZO, "recusa " + i, () -> Visual.naEdt(d::textoDoErro).contains("não aceitou esta senha") && ssh.senhasVistas.size() == quantas
                && !Visual.naEdt(d::ocupado));
            assertEquals("", new String(Visual.naEdt(() -> d.campoDaSenhaDoServidor().getPassword())), "o campo é apagado a cada tentativa");
            assertEquals("chave", Visual.naEdt(d::paginaAtual));
        }
        assertTrue(Visual.naEdt(d::textoDoErro).contains("não tenta mais por 10 minutos"), Visual.naEdt(d::textoDoErro));
        assertEquals(List.of(0), senhasVistasQuandoOHostFoiConferido, "o host foi conferido uma vez só: tentar de novo não pergunta outra vez");
        assertEquals(List.of(), cofre.servidores());
        assertEquals(List.of(), shell.recebidos);

        digitarEEntrar(d, SENHA_DO_SERVIDOR);
        Espera.ate(PRAZO, "pausa", () -> Visual.naEdt(d::textoDoErro).contains("Espere"));
        assertTrue(Visual.naEdt(d::textoDoErro).contains("o servidor pode bloquear o seu endereço"), Visual.naEdt(d::textoDoErro));
        assertEquals(4, ssh.senhasVistas.size(), "a quinta (mesmo certa) não saiu do app");
        semTransbordo(d);
        Visual.salvar(Visual.renderizar(d.raiz(), 560, Visual.alturaPreferida(d.raiz(), 560)), "assistente-passo-2-senha-pausa");
    }

    @Test
    void hostNaoConfirmadoNuncaRecebeASenha() throws Exception {
        confirmaHost = false;
        DialogoAdicionarServidor d = assistenteNaTela();
        noPassoDaChave(d);
        digitarEEntrar(d, SENHA_DO_SERVIDOR);
        Espera.ate(PRAZO, "recusa", () -> Visual.naEdt(d::textoDoErro).contains("não confirmado"));
        assertEquals(DialogoAdicionarServidor.TEXTO_DE_SERVIDOR_NAO_CONFIRMADO, Visual.naEdt(d::textoDoErro));
        assertEquals(List.of(), ssh.senhasVistas, "o servidor NUNCA viu a senha");
        assertEquals(0, ssh.pedidosDeAutenticacao.get());
        assertEquals(List.of(), cofre.servidores());
        assertEquals("chave", Visual.naEdt(d::paginaAtual));
    }

    @Test
    void servidorSemSenhaHabilitadaAbreAsOpcoesAvancadasComAChavePublicaEOCaminhoAntigoFunciona() throws Exception {
        ssh.desligarSenha();
        DialogoAdicionarServidor d = assistenteNaTela();
        InfoChave chave = noPassoDaChave(d);
        digitarEEntrar(d, SENHA_DO_SERVIDOR);
        Espera.ate(PRAZO, "frase", () -> Visual.naEdt(d::textoDoErro).contains("não aceita entrar com senha"));
        assertTrue(Visual.naEdt(() -> d.opcoesAvancadas().aberta()), "o caminho antigo abre sozinho");
        assertTrue(Visual.naEdt(() -> d.botaoTestar().isEnabled()));
        assertEquals(chave.publica(), Visual.naEdt(d::chavePublicaMostrada), "mostra a chave pública");
        assertEquals(List.of(), ssh.senhasVistas);
        semTransbordo(d);
        Visual.salvar(Visual.renderizar(d.raiz(), 560, Visual.alturaPreferida(d.raiz(), 560)), "assistente-passo-2-sem-senha-no-servidor");

        // o usuário instala a chave pelo comando e segue pelo caminho de antes (Testar conexão): sem pergunta de senha no fim
        ssh.aceitar(publica(chave));
        Visual.naEdt(() -> d.botaoTestar().doClick());
        esperarPagina(d, "conexao");
        Espera.ate(PRAZO, "pronto", () -> Visual.naEdt(() -> d.botaoConcluir().isEnabled()));
        Visual.naEdt(() -> d.botaoConcluir().doClick());
        assertEquals(1, concluidos.size(), "pelo caminho de antes não se sabe se o servidor tem senha: não há a pergunta");
    }

    private static java.security.PublicKey publica(InfoChave chave) throws Exception {
        return org.apache.sshd.common.config.keys.PublicKeyEntry.parsePublicKeyEntry(chave.publica())
            .resolvePublicKey(null, java.util.Collections.emptyMap(), org.apache.sshd.common.config.keys.PublicKeyEntryResolver.FAILING);
    }

    @Test
    void servidorQueNaoAceitaAChaveDepoisDaSenhaDizOMotivoENadaMaisEMexido() throws Exception {
        shell.honrarAuthorizedKeys(false);
        DialogoAdicionarServidor d = assistenteNaTela();
        noPassoDaChave(d);
        digitarEEntrar(d, SENHA_DO_SERVIDOR);
        Espera.ate(PRAZO, "frase", () -> Visual.naEdt(d::textoDoErro).contains("não aceitou a chave"));
        assertTrue(Visual.naEdt(d::textoDoErro).contains("permite login por chave"), Visual.naEdt(d::textoDoErro));
        assertTrue(Visual.naEdt(() -> d.opcoesAvancadas().aberta()));
        assertEquals("chave", Visual.naEdt(d::paginaAtual));
        assertEquals(List.of(), cofre.servidores(), "nenhum servidor no cofre");
        assertTrue(agente.tokens.isEmpty());
        assertEquals(List.of(ComandosDoAcesso.instalarChave(), ComandosDoAcesso.lerPermissoesDaPastaPessoal()), shell.recebidos, "só instalou a chave e leu permissões: nenhum outro comando");
        assertFalse(Visual.naEdt(d::senhaFoiUsada));
    }

    @Test
    void senhaVaziaPedeParaDigitarSemIrAoServidor() throws Exception {
        DialogoAdicionarServidor d = assistenteNaTela();
        noPassoDaChave(d);
        Visual.naEdt(() -> d.botaoEntrarComSenha().doClick());
        assertTrue(Visual.naEdt(d::textoDoErro).startsWith("Digite a senha do usuário labuser no servidor"), Visual.naEdt(d::textoDoErro));
        assertEquals(List.of(), senhasVistasQuandoOHostFoiConferido, "nem chegou a conferir o host");
        assertNotNull(Visual.naEdt(d::chaveEscolhida));
    }

    @Test
    void aSenhaNuncaApareceEmNenhumTextoDaTelaNemNosAvisos() throws Exception {
        DialogoAdicionarServidor d = assistenteNaTela();
        noPassoDaChave(d);
        digitarEEntrar(d, SENHA_DO_SERVIDOR);
        esperarPagina(d, "conexao");
        Espera.ate(PRAZO, "pronto", () -> Visual.naEdt(() -> d.botaoConcluir().isEnabled()));
        assertFalse(textosDe(d).contains(SENHA_DO_SERVIDOR));
        assertFalse(String.join("\n", avisos.textos()).contains(SENHA_DO_SERVIDOR));
    }
}
