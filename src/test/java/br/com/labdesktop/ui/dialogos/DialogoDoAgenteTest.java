package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.instalador.EstadoDoPasso;
import br.com.labdesktop.instalador.GanchosDaInstalacao.Sonda;
import br.com.labdesktop.instalador.GanchosFalsos;
import br.com.labdesktop.instalador.InstaladorDeAgente;
import br.com.labdesktop.instalador.PacoteDoAgente;
import br.com.labdesktop.instalador.ParametrosDaInstalacao;
import br.com.labdesktop.instalador.PassoDeInstalacao;
import br.com.labdesktop.instalador.PassoDeRemocao;
import br.com.labdesktop.teste.ServidorSimulado;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.Botao;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A tela de instalar, atualizar e remover o agente: a conferência (só leitura), a confirmação com o nome do servidor no botão, o andamento
 * com estado ao vivo, o erro com o texto real do servidor e o "Tentar de novo". Contra o servidor SIMULADO, sem SSH. Grava os PNGs de
 * cada momento nos dois temas em {@code build/telas/}.
 */
@ExtendWith(VerificaEdt.class)
class DialogoDoAgenteTest {

    private static final Duration PRAZO = Duration.ofSeconds(25);
    private static final PacoteDoAgente PACOTE = PacoteDoAgente.de("0.1.0-abc1234", "abc1234def", new byte[2048]);
    private static final String NOME = "vps-1";

    private final ParametrosDaInstalacao padrao = ParametrosDaInstalacao.padrao(7000);
    private final ServidorSimulado servidor = new ServidorSimulado(padrao, PACOTE);
    private final GanchosFalsos ganchos = new GanchosFalsos();
    private final AvisosGravados avisos = new AvisosGravados();
    private final AtomicInteger mudancasNoServidor = new AtomicInteger();
    private final List<ParametrosDaInstalacao> fabricados = new ArrayList<>();

    @BeforeEach
    void preparar() {
        Visual.tema(Tema.CLARO);
        servidor.noGrupoDocker = true;
        DialogoDoAgente.definirPrazoDaTravaDaConfirmacaoParaTeste(0); // a trava do clique duplo tem os testes dela (DialogoDoAgenteEscolhaTest)
    }

    @AfterEach
    void restaurar() {
        Visual.tema(Tema.CLARO);
        DialogoDoAgente.definirPrazoDaTravaDaConfirmacaoParaTeste(600);
    }

    private DialogoDoAgente abrir(DialogoDoAgente.Acao acao, String versaoInstalada) {
        DialogoDoAgente d = Visual.naEdt(() -> new DialogoDoAgente(acao, new DialogoDoAgente.Alvo(NOME, 7000, versaoInstalada), PACOTE, p -> {
            fabricados.add(p);
            ServidorSimulado s = p.equals(padrao) ? servidor : new ServidorSimulado(p, PACOTE);
            return new InstaladorDeAgente(s, p, PACOTE, ganchos).comSonda(Duration.ofMillis(300), Duration.ofMillis(10));
        }, mudancasNoServidor::incrementAndGet, avisos, new CopiadorDeTexto(() -> new Clipboard("teste"))));
        Visual.naEdt(d::iniciarConferencia);
        return d;
    }

    private static void esperarPagina(DialogoDoAgente d, String pagina) {
        Espera.ate(PRAZO, () -> "página=" + Visual.naEdt(d::paginaAtual), () -> pagina.equals(Visual.naEdt(d::paginaAtual)));
    }

    /** Ao instalar num servidor sem agente a primeira tela é "O que instalar": o caminho de antes é Completo (já marcado) e Continuar. */
    private static void seguirComCompleto(DialogoDoAgente d) {
        esperarPagina(d, "escolher");
        Visual.naEdt(() -> d.botaoContinuar().doClick());
        assertEquals("confirmar", Visual.naEdt(d::paginaAtual));
    }

    private static void esperarFim(DialogoDoAgente d) {
        Espera.ate(PRAZO, () -> "executando=" + Visual.naEdt(d::executando) + " situacao=" + Visual.naEdt(d::situacaoFinal),
            () -> !Visual.naEdt(d::executando) && !Visual.naEdt(d::situacaoFinal).isEmpty());
    }

    private static void fotografar(DialogoDoAgente d, String nome) {
        for (Tema t : Tema.values()) {
            if (t == Tema.SISTEMA) {
                continue;
            }
            Visual.tema(t);
            Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(d.raiz()));
            Visual.naEdt(() -> {
                if (d.mioloRolavel() != null) {
                    d.mioloRolavel().rolarParaOTopo(); // o PNG mostra o começo da tela, como o usuário a vê ao abrir
                }
            });
            int altura = Math.min(900, Visual.alturaPreferida(d.raiz(), 560));
            Visual.salvar(Visual.renderizar(d.raiz(), 560, altura), nome + (t == Tema.CLARO ? "-claro" : "-escuro"));
        }
        Visual.tema(Tema.CLARO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(d.raiz()));
    }

    private String tudo(DialogoDoAgente d) {
        return String.join("\n", Visual.naEdt(d::frasesDasVerificacoes)) + "\n" + String.join("\n", Visual.naEdt(d::frasesDoQueVaiAcontecer));
    }

    // ---------------------------------------------------------------------------------------------
    // Confirmar
    // ---------------------------------------------------------------------------------------------

    @Test
    void conferirSoLeEAConfirmacaoTemONomeDoServidorNoBotaoESemInstalarNada() {
        DialogoDoAgente d = abrir(DialogoDoAgente.Acao.INSTALAR, null);
        seguirComCompleto(d);

        assertTrue(servidor.escritas().isEmpty(), "só leitura até o clique: " + servidor.escritas());
        assertEquals("Instalar em vps-1", Visual.naEdt(() -> d.botaoConfirmar().getText()));
        assertTrue(Visual.naEdt(() -> d.botaoConfirmar().isEnabled()));
        assertEquals("Cancelar", Visual.naEdt(() -> d.botaoCancelar().getText()));
        assertFalse(Visual.naEdt(d::executando));
        List<String> verificacoes = Visual.naEdt(d::frasesDasVerificacoes);
        assertEquals(12, verificacoes.size(), verificacoes.toString());
        assertTrue(verificacoes.get(0).startsWith("Sistema (ok): Debian GNU/Linux 13"), verificacoes.get(0));
        assertTrue(verificacoes.stream().anyMatch(v -> v.startsWith("Docker (ok): Docker 27.3.1")));
        assertTrue(Visual.naEdt(d::textoDoBloqueio).isEmpty());
        // o que o usuário lê antes de confirmar
        String texto = tudo(d);
        assertTrue(texto.contains("Subir o container painel-lab"), texto);
        assertTrue(texto.contains("127.0.0.1:7000"));
        assertTrue(texto.contains("pasta ~/lab-agente"));
        assertTrue(texto.contains("desfaz só o que ele criou"));
        fotografar(d, "dialogo-agente-confirmar");
    }

    @Test
    void aTelaDeConfirmacaoDizOQueOAgenteAlcancaAntesDeConfirmar() {
        DialogoDoAgente d = abrir(DialogoDoAgente.Acao.INSTALAR, null);
        seguirComCompleto(d);
        String tela = textosDaRaiz(d);
        assertTrue(tela.contains("acesso ao Docker do servidor"), tela);
        assertTrue(tela.contains("equivale a ser administrador (root)"), tela);
        assertTrue(tela.contains("127.0.0.1"), tela);
        assertTrue(tela.contains("nunca em 0.0.0.0"), tela);
        assertTrue(tela.contains("apenas como hash"), tela);
        assertTrue(tela.contains("não abre porta no firewall"), tela);
        assertTrue(tela.contains("NADA foi instalado ainda"), tela);
        assertEquals(4, DialogoDoAgente.TEXTOS_DE_SEGURANCA.size());
    }

    private static String textosDaRaiz(DialogoDoAgente d) {
        StringBuilder sb = new StringBuilder();
        Visual.naEdt(() -> juntar(d.raiz(), sb));
        return sb.toString();
    }

    private static void juntar(java.awt.Component c, StringBuilder sb) {
        if (!c.isVisible()) {
            return;
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
                juntar(f, sb);
            }
        }
    }

    @Test
    void problemaBloqueiaEMostraOComandoExatoQueQuemAdministraDeveRodar() {
        servidor.semDocker().semPermissaoNenhuma();
        DialogoDoAgente d = abrir(DialogoDoAgente.Acao.INSTALAR, null);
        seguirComCompleto(d);

        assertFalse(Visual.naEdt(() -> d.botaoConfirmar().isEnabled()), "com problema não se confirma");
        assertTrue(Visual.naEdt(d::textoDoBloqueio).contains("impedem de continuar"), Visual.naEdt(d::textoDoBloqueio));
        assertTrue(Visual.naEdt(d::comandosSugeridos).contains("echo \"labuser ALL=(ALL) NOPASSWD:ALL\" | sudo tee /etc/sudoers.d/90-lab-desktop >/dev/null && sudo chmod 0440 /etc/sudoers.d/90-lab-desktop && sudo visudo -cf /etc/sudoers.d/90-lab-desktop"),
            Visual.naEdt(d::comandosSugeridos).toString());
        assertEquals("Já instalei, continuar", Visual.naEdt(() -> d.botaoConferirDeNovo().getText()),
            "sem Docker e sem administrador: quem administra instala o Docker, e o usuário volta");
        assertTrue(servidor.escritas().isEmpty());
        fotografar(d, "dialogo-agente-problema");
    }

    @Test
    void dockerSemPermissaoBloqueiaEOBotaoVolta_aConferirDeNovo() {
        servidor.semPermissaoNenhuma(); // o Docker existe, o usuário não pode usá-lo
        DialogoDoAgente d = abrir(DialogoDoAgente.Acao.INSTALAR, null);
        seguirComCompleto(d);
        assertFalse(Visual.naEdt(() -> d.botaoConfirmar().isEnabled()));
        assertEquals("Conferir de novo", Visual.naEdt(() -> d.botaoConferirDeNovo().getText()));
        assertTrue(Visual.naEdt(d::comandosSugeridos).contains("sudo usermod -aG docker labuser"), Visual.naEdt(d::comandosSugeridos).toString());
        servidor.noGrupoDocker = true; // o administrador rodou o comando
        Visual.naEdt(() -> d.botaoConferirDeNovo().doClick());
        Espera.ate(PRAZO, "liberou", () -> Visual.naEdt(() -> d.botaoConfirmar().isEnabled()));
    }

    @Test
    void distribuicaoSemSuportePedeJaInstaleiContinuarEDepoisLiberaOBotao() {
        servidor.fedora().semDocker();
        DialogoDoAgente d = abrir(DialogoDoAgente.Acao.INSTALAR, null);
        seguirComCompleto(d);
        assertFalse(Visual.naEdt(() -> d.botaoConfirmar().isEnabled()));
        assertEquals("Já instalei, continuar", Visual.naEdt(() -> d.botaoConferirDeNovo().getText()));
        assertTrue(Visual.naEdt(d::textoDoBloqueio).contains("Já instalei, continuar"), Visual.naEdt(d::textoDoBloqueio));

        servidor.dockerInstalado = true; // o usuário instalou o Docker no servidor
        Visual.naEdt(() -> d.botaoConferirDeNovo().doClick());
        Espera.ate(PRAZO, "o botão liberou", () -> Visual.naEdt(() -> d.botaoConfirmar().isEnabled()));
        assertEquals("Instalar em vps-1", Visual.naEdt(() -> d.botaoConfirmar().getText()));
    }

    @Test
    void cancelarNaConfirmacaoNaoMudaNadaNoServidor() {
        DialogoDoAgente d = abrir(DialogoDoAgente.Acao.INSTALAR, null);
        seguirComCompleto(d);
        Visual.naEdt(() -> d.botaoCancelar().doClick());
        assertTrue(servidor.escritas().isEmpty());
        assertEquals(0, mudancasNoServidor.get());
        assertTrue(servidor.envios.isEmpty());
        assertTrue(ganchos.chamadas.isEmpty());
    }

    @Test
    void opcoesAvancadasFechadasEMudarONomeDoContainerConfereDeNovoComOutroNome() {
        DialogoDoAgente d = abrir(DialogoDoAgente.Acao.INSTALAR, null);
        seguirComCompleto(d);
        assertFalse(Visual.naEdt(() -> d.opcoesAvancadas().aberta()), "as opções de teste ficam recolhidas");
        assertEquals("painel-lab", Visual.naEdt(() -> d.campoDoContainer().getText()));

        Visual.naEdt(() -> d.campoDoContainer().setText("painel-teste"));
        Visual.naEdt(() -> d.botaoConferirDeNovo().doClick());
        Espera.ate(PRAZO, "nova conferência", () -> fabricados.size() == 2 && "confirmar".equals(Visual.naEdt(d::paginaAtual))
            && Visual.naEdt(d::relatorio) != null);
        assertEquals("painel-teste", fabricados.get(1).container());
        assertEquals(7000, fabricados.get(1).porta(), "a porta é a do servidor");
        assertTrue(tudo(d).contains("Subir o container painel-teste"), tudo(d));

        Visual.naEdt(() -> d.campoDoContainer().setText("Painel; rm -rf /"));
        Visual.naEdt(() -> d.botaoConferirDeNovo().doClick());
        assertTrue(textosDaRaiz(d).contains("só pode ter letras minúsculas"), "nome fora da regra nem vira instalador");
        assertEquals(2, fabricados.size());
    }

    @Test
    void seOServidorFoiProtegidoComATelaAbertaMudarOContainerMostraAFraseEDesligaOBotao() {
        AtomicInteger montados = new AtomicInteger();
        DialogoDoAgente d = Visual.naEdt(() -> new DialogoDoAgente(DialogoDoAgente.Acao.INSTALAR, new DialogoDoAgente.Alvo(NOME, 7000, null), PACOTE, p -> {
            if (montados.incrementAndGet() > 1) {
                throw new br.com.labdesktop.servidor.ProtecaoDoServidor.ServidorProtegidoException(); // o usuário protegeu o servidor depois de abrir
            }
            return new InstaladorDeAgente(servidor, p, PACOTE, ganchos).comSonda(Duration.ofMillis(300), Duration.ofMillis(10));
        }, mudancasNoServidor::incrementAndGet, avisos, new CopiadorDeTexto(() -> new Clipboard("teste"))));
        Visual.naEdt(d::iniciarConferencia);
        seguirComCompleto(d);

        Visual.naEdt(() -> d.campoDoContainer().setText("painel-teste"));
        Visual.naEdt(() -> d.botaoConferirDeNovo().doClick()); // a EDT não leva a exceção para a rua

        assertTrue(textosDaRaiz(d).contains("Este servidor é protegido: o app não instala, atualiza nem remove nada aqui. Para mudar, abra Editar servidor."), textosDaRaiz(d));
        assertTrue(Visual.naEdt(() -> d.opcoesAvancadas().aberta()), "a seção onde a frase mora abre");
        assertFalse(Visual.naEdt(() -> d.botaoConfirmar().isEnabled()), "e o Instalar em fica desligado");
        assertTrue(servidor.escritas().isEmpty());
    }

    // ---------------------------------------------------------------------------------------------
    // Andamento, sucesso, erro
    // ---------------------------------------------------------------------------------------------

    @Test
    void confirmarMostraOAndamentoPassoAPassoEOSucessoAvisaOServidorQueMudou() throws Exception {
        CountDownLatch noBuild = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        servidor.antesDe(c -> c.contains(" build "), () -> {
            noBuild.countDown();
            try {
                soltar.await(PRAZO.toSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        DialogoDoAgente d = abrir(DialogoDoAgente.Acao.INSTALAR, null);
        seguirComCompleto(d);

        Visual.naEdt(() -> d.botaoConfirmar().doClick());
        assertEquals("andamento", Visual.naEdt(d::paginaAtual));
        assertTrue(noBuild.await(PRAZO.toSeconds(), TimeUnit.SECONDS));
        Espera.ate(PRAZO, "construindo", () -> Visual.naEdt(() -> d.estadoDoPasso(PassoDeInstalacao.CONSTRUIR_IMAGEM)) == EstadoDoPasso.EM_ANDAMENTO);
        assertFalse(Visual.naEdt(d::passosMostrados).contains(PassoDeInstalacao.DOCKER), "o Docker já está no servidor e não foi escolhido: não vira passo");
        assertEquals(EstadoDoPasso.FEITO, Visual.naEdt(() -> d.estadoDoPasso(PassoDeInstalacao.ENVIAR_PACOTE)));
        assertEquals(EstadoDoPasso.PENDENTE, Visual.naEdt(() -> d.estadoDoPasso(PassoDeInstalacao.SUBIR_CONTAINER)));
        assertTrue(Visual.naEdt(() -> d.botaoCancelarExecucao().isVisible()));
        assertTrue(Visual.naEdt(d::executando));
        assertTrue(Visual.naEdt(d::textoDoLog).contains("$ " + br.com.labdesktop.instalador.ComandosRemotos.criarPasta(padrao)), "o log completo vai enchendo");
        fotografar(d, "dialogo-agente-andamento");

        soltar.countDown();
        esperarFim(d);
        assertEquals("Agente instalado", Visual.naEdt(d::situacaoFinal));
        assertTrue(Visual.naEdt(d::terminouComSucesso));
        assertTrue(Visual.naEdt(d::textoFinal).contains("0.1.0-abc1234"));
        assertTrue(Visual.naEdt(() -> d.botaoConcluir().isVisible()));
        assertFalse(Visual.naEdt(() -> d.botaoCancelarExecucao().isVisible()));
        assertEquals(5, Visual.naEdt(d::passosMostrados).size(), "os cinco passos do agente (o Docker já estava e não foi escolhido)");
        for (br.com.labdesktop.instalador.Passo p : Visual.naEdt(d::passosMostrados)) {
            assertTrue(Visual.naEdt(() -> d.estadoDoPasso(p)) == EstadoDoPasso.FEITO || Visual.naEdt(() -> d.estadoDoPasso(p)) == EstadoDoPasso.JA_ESTAVA_FEITO, p.toString());
        }
        assertEquals(1, mudancasNoServidor.get(), "o app é avisado UMA vez para repetir o teste do agente");
        assertTrue(avisos.disse("ok", "Agente instalado em vps-1"));
        assertEquals("painel-lab", servidor.containerNome);
        fotografar(d, "dialogo-agente-sucesso");
    }

    @Test
    void falhaMostraOPassoOErroRealDoServidorOQueFoiDesfeitoETentarDeNovoConclui() {
        servidor.falharUmaVezEm(c -> c.contains(" build "), 1, "ERROR: failed to solve: node:22-alpine: failed to resolve source metadata");
        DialogoDoAgente d = abrir(DialogoDoAgente.Acao.INSTALAR, null);
        seguirComCompleto(d);
        Visual.naEdt(() -> d.botaoConfirmar().doClick());
        esperarFim(d);

        assertEquals("Parou em um passo", Visual.naEdt(d::situacaoFinal));
        String erro = Visual.naEdt(d::textoFinal);
        assertTrue(erro.contains("Passo: Construir a imagem do agente no servidor."), erro);
        assertTrue(erro.contains("failed to resolve source metadata"), "o erro REAL do servidor, como texto: " + erro);
        assertEquals(EstadoDoPasso.FALHOU, Visual.naEdt(() -> d.estadoDoPasso(PassoDeInstalacao.CONSTRUIR_IMAGEM)));
        assertTrue(Visual.naEdt(() -> d.detalheDoPasso(PassoDeInstalacao.CONSTRUIR_IMAGEM)).contains("failed to resolve"));
        assertEquals(EstadoDoPasso.DESFEITO, Visual.naEdt(() -> d.estadoDoPasso(PassoDeInstalacao.ENVIAR_PACOTE)));
        assertEquals(List.of("Desfeito: Apaguei a pasta lab-agente do instalador."), Visual.naEdt(d::linhasDoDesfeito));
        assertTrue(Visual.naEdt(() -> d.botaoTentarDeNovo().isVisible()));
        assertTrue(Visual.naEdt(() -> d.botaoFechar().isVisible()));
        assertFalse(Visual.naEdt(() -> d.botaoConcluir().isVisible()));
        assertEquals(0, mudancasNoServidor.get(), "nada ficou no servidor: o app não precisa reconferir");
        assertFalse(servidor.pastaExiste);
        fotografar(d, "dialogo-agente-erro");

        Visual.naEdt(() -> d.botaoTentarDeNovo().doClick());
        esperarFim(d);
        Espera.ate(PRAZO, "sucesso", () -> "Agente instalado".equals(Visual.naEdt(d::situacaoFinal)));
        assertEquals(1, mudancasNoServidor.get());
    }

    @Test
    void cancelarNoMeioInterrompeDesfazEMostraCancelado() throws Exception {
        CountDownLatch noBuild = new CountDownLatch(1);
        servidor.antesDe(c -> c.contains(" build "), () -> {
            noBuild.countDown();
            try {
                Thread.sleep(PRAZO.toMillis()); // "o build demora": só sai quando o Cancelar interrompe a thread
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        DialogoDoAgente d = abrir(DialogoDoAgente.Acao.INSTALAR, null);
        seguirComCompleto(d);
        Visual.naEdt(() -> d.botaoConfirmar().doClick());
        assertTrue(noBuild.await(PRAZO.toSeconds(), TimeUnit.SECONDS));

        Visual.naEdt(() -> d.botaoCancelarExecucao().doClick());
        assertEquals("Cancelando…", Visual.naEdt(() -> d.botaoCancelarExecucao().getText()));
        esperarFim(d);

        assertEquals("Cancelado", Visual.naEdt(d::situacaoFinal));
        assertTrue(Visual.naEdt(d::textoFinal).contains("foi desfeito"));
        assertFalse(servidor.pastaExiste);
        assertNull(servidor.containerNome);
        assertEquals(0, mudancasNoServidor.get());
    }

    /**
     * O comando que NÃO obedece à interrupção (um canal que não solta) não pode prender a janela: o botão não fica para sempre em
     * "Cancelando…"; passado o prazo vira "Fechar mesmo assim", e o clique (ou Esc) fecha.
     */
    @Test
    void cancelarComComandoQueNaoSoltaOferecePassadoOPrazoFecharMesmoAssim() throws Exception {
        DialogoDoAgente.definirPrazoParaFecharMesmoAssimParaTeste(300);
        CountDownLatch noBuild = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        try {
            servidor.antesDe(c -> c.contains(" build "), () -> {
                noBuild.countDown();
                boolean interrompido = false;
                while (true) { // ignora a interrupção de propósito, como o waitFor do MINA
                    try {
                        soltar.await(10, TimeUnit.SECONDS);
                        break;
                    } catch (InterruptedException e) {
                        interrompido = true;
                    }
                }
                if (interrompido) {
                    Thread.currentThread().interrupt();
                }
            });
            DialogoDoAgente d = abrir(DialogoDoAgente.Acao.INSTALAR, null);
            seguirComCompleto(d);
            Visual.naEdt(() -> d.botaoConfirmar().doClick());
            assertTrue(noBuild.await(PRAZO.toSeconds(), TimeUnit.SECONDS));

            Visual.naEdt(() -> d.botaoCancelarExecucao().doClick());
            assertEquals("Cancelando…", Visual.naEdt(() -> d.botaoCancelarExecucao().getText()));
            assertFalse(Visual.naEdt(() -> d.botaoCancelarExecucao().isEnabled()));
            Espera.ate(PRAZO, "o botão deixa de ficar em Cancelando…", () -> "Fechar mesmo assim".equals(Visual.naEdt(() -> d.botaoCancelarExecucao().getText())));
            assertTrue(Visual.naEdt(() -> d.botaoCancelarExecucao().isEnabled()));
            assertFalse(Visual.naEdt(d::fechada));

            Visual.naEdt(() -> d.botaoCancelarExecucao().doClick());
            assertTrue(Visual.naEdt(d::fechada), "o segundo pedido fecha a janela");
        } finally {
            soltar.countDown();
            DialogoDoAgente.definirPrazoParaFecharMesmoAssimParaTeste(8000);
        }
    }

    private static void assertNull(Object o) {
        org.junit.jupiter.api.Assertions.assertNull(o);
    }

    @Test
    void agenteQueNaoRespondeMostraOsLogsDoContainerNoErro() {
        servidor.logsDoContainer = "Error: listen EADDRINUSE: address already in use 127.0.0.1:7000";
        ganchos.sonda(Sonda.NAO_RESPONDE);
        DialogoDoAgente d = abrir(DialogoDoAgente.Acao.INSTALAR, null);
        seguirComCompleto(d);
        Visual.naEdt(() -> d.botaoConfirmar().doClick());
        esperarFim(d);
        assertTrue(Visual.naEdt(d::textoFinal).contains("EADDRINUSE"), Visual.naEdt(d::textoFinal));
        assertTrue(Visual.naEdt(d::linhasDoDesfeito).stream().anyMatch(l -> l.contains("Removi o container painel-lab")));
    }

    // ---------------------------------------------------------------------------------------------
    // Atualizar e remover
    // ---------------------------------------------------------------------------------------------

    @Test
    void atualizarMostraAVersaoAtualEAFuturaEMantemOsDados() {
        servidor.containerNome = "painel-lab";
        servidor.rotuloDoContainer = "0.0.9-velha";
        servidor.volumeExiste = true;
        servidor.pastaExiste = true;
        ganchos.tokenJaValia = true;
        DialogoDoAgente d = abrir(DialogoDoAgente.Acao.ATUALIZAR, "0.0.9-velha");
        esperarPagina(d, "confirmar");
        assertEquals("Atualizar em vps-1", Visual.naEdt(() -> d.botaoConfirmar().getText()));
        String tela = textosDaRaiz(d);
        assertTrue(tela.contains("Versão instalada: 0.0.9-velha. Versão do pacote deste app: 0.1.0-abc1234."), tela);
        assertTrue(tela.contains("Os dados e o token deste computador ficam como estão"), tela);
        fotografar(d, "dialogo-agente-atualizar");

        Visual.naEdt(() -> d.botaoConfirmar().doClick());
        esperarFim(d);
        assertEquals("Agente atualizado", Visual.naEdt(d::situacaoFinal));
        assertTrue(servidor.volumeExiste, "os dados ficam");
        assertEquals("0.1.0-abc1234", servidor.rotuloDoContainer);
    }

    @Test
    void removerTemConfirmacaoComONomeNoBotaoCancelarEODefaultENaoConfereNada() {
        servidor.containerNome = "painel-lab";
        servidor.rotuloDoContainer = "0.1.0-abc1234";
        servidor.volumeExiste = true;
        servidor.imagemExiste = true;
        servidor.pastaExiste = true;
        DialogoDoAgente d = abrir(DialogoDoAgente.Acao.REMOVER, "0.1.0-abc1234");
        assertEquals("confirmar", Visual.naEdt(d::paginaAtual));
        assertTrue(servidor.comandos.isEmpty(), "remover não confere nada antes do clique");
        Botao confirmar = Visual.naEdt(d::botaoConfirmar);
        assertEquals("Remover agente de vps-1", confirmar.getText());
        assertEquals(Botao.Variante.PERIGO, Visual.naEdt(confirmar::variante));
        assertEquals(Botao.Variante.PRIMARIO, Visual.naEdt(() -> d.botaoCancelar().variante()), "Cancelar é o botão em destaque");
        String tela = textosDaRaiz(d);
        assertTrue(tela.contains("O Docker continua instalado"), tela);
        assertTrue(tela.contains("só se foi o app que o criou"), tela);
        fotografar(d, "dialogo-agente-remover");

        Visual.naEdt(() -> confirmar.doClick());
        esperarFim(d);
        assertEquals("Agente removido", Visual.naEdt(d::situacaoFinal));
        assertEquals(EstadoDoPasso.FEITO, Visual.naEdt(() -> d.estadoDoPasso(PassoDeRemocao.CONTAINER)));
        assertEquals(EstadoDoPasso.FEITO, Visual.naEdt(() -> d.estadoDoPasso(PassoDeRemocao.PASTA)));
        assertNull(servidor.containerNome);
        assertTrue(servidor.dockerInstalado);
        assertEquals(1, mudancasNoServidor.get());
        assertNotNull(Visual.naEdt(d::textoFinal));
    }

    @Test
    void removerUmContainerQueNaoEDoAppNaoToca() {
        servidor.containerNome = "painel-lab";
        servidor.rotuloDoContainer = "";
        DialogoDoAgente d = abrir(DialogoDoAgente.Acao.REMOVER, null);
        Visual.naEdt(() -> d.botaoConfirmar().doClick());
        esperarFim(d);
        assertEquals("O app não removeu nada", Visual.naEdt(d::situacaoFinal));
        assertTrue(Visual.naEdt(d::textoFinal).contains("não foi criado por este app"));
        assertEquals("painel-lab", servidor.containerNome);
        assertTrue(servidor.escritas().isEmpty());
        assertEquals(0, mudancasNoServidor.get());
    }
}
