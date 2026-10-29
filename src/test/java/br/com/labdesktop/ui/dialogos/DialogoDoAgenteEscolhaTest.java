package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.instalador.CatalogoDeInstalacao;
import br.com.labdesktop.instalador.ComandosRemotos;
import br.com.labdesktop.instalador.EscolhaDeInstalacao.Tipo;
import br.com.labdesktop.instalador.EstadoDoPasso;
import br.com.labdesktop.instalador.GanchosFalsos;
import br.com.labdesktop.instalador.InstaladorDeAgente;
import br.com.labdesktop.instalador.PacoteDoAgente;
import br.com.labdesktop.instalador.ParametrosDaInstalacao;
import br.com.labdesktop.instalador.Passo;
import br.com.labdesktop.instalador.PassoDeInstalacao;
import br.com.labdesktop.teste.ServidorSimulado;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.MolduraDeJanela;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A tela "O que instalar neste servidor" do {@link DialogoDoAgente}. Os três cartões (Completo marcado e "Recomendado", Escolher com a
 * lista de caixas e a trava das dependências, Só SSH que fecha sem instalar), a confirmação só com os passos escolhidos, e a regra de que sem o
 * clique de confirmação nada roda. Contra o servidor SIMULADO (sem SSH). Grava os PNGs nos dois temas em {@code build/telas/}.
 */
@ExtendWith(VerificaEdt.class)
class DialogoDoAgenteEscolhaTest {

    private static final Duration PRAZO = Duration.ofSeconds(25);
    private static final PacoteDoAgente PACOTE = PacoteDoAgente.de("0.1.0-abc1234", "abc1234def", new byte[2048]);
    private static final String NOME = "vps-1";

    private final ParametrosDaInstalacao padrao = ParametrosDaInstalacao.padrao(7000);
    private final ServidorSimulado servidor = new ServidorSimulado(padrao, PACOTE);
    private final GanchosFalsos ganchos = new GanchosFalsos();
    private final AvisosGravados avisos = new AvisosGravados();
    private final AtomicInteger mudancasNoServidor = new AtomicInteger();
    private final AtomicInteger pediramSoSsh = new AtomicInteger();
    /** Se o diálogo já estava fechado quando avisou "Só SSH" (o assistente conclui nesse aviso e não pode ter o filho aberto). */
    private final List<Boolean> fechadoNoAviso = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicReference<DialogoDoAgente> aberto = new java.util.concurrent.atomic.AtomicReference<>();

    @BeforeEach
    void preparar() {
        Visual.tema(Tema.CLARO);
        servidor.noGrupoDocker = true;
        DialogoDoAgente.definirPrazoDaTravaDaConfirmacaoParaTeste(0); // os testes clicam logo; a trava tem os testes dela
    }

    @AfterEach
    void restaurar() {
        Visual.tema(Tema.CLARO);
        DialogoDoAgente.definirPrazoDaTravaDaConfirmacaoParaTeste(600);
    }

    private DialogoDoAgente abrir(DialogoDoAgente.Acao acao, String versaoInstalada) {
        DialogoDoAgente d = Visual.naEdt(() -> new DialogoDoAgente(acao, new DialogoDoAgente.Alvo(NOME, 7000, versaoInstalada), PACOTE,
            p -> new InstaladorDeAgente(servidor, p, PACOTE, ganchos).comSonda(Duration.ofMillis(300), Duration.ofMillis(10)),
            mudancasNoServidor::incrementAndGet, () -> {
                pediramSoSsh.incrementAndGet();
                fechadoNoAviso.add(aberto.get().fechada());
            }, avisos, new CopiadorDeTexto(() -> new Clipboard("teste"))));
        aberto.set(d);
        Visual.naEdt(d::iniciarConferencia);
        return d;
    }

    private DialogoDoAgente abrirNaEscolha() {
        DialogoDoAgente d = abrir(DialogoDoAgente.Acao.INSTALAR, null);
        esperarPagina(d, "escolher");
        return d;
    }

    private static void esperarPagina(DialogoDoAgente d, String pagina) {
        Espera.ate(PRAZO, () -> "página=" + Visual.naEdt(d::paginaAtual), () -> pagina.equals(Visual.naEdt(d::paginaAtual)));
    }

    private static void esperarFim(DialogoDoAgente d) {
        Espera.ate(PRAZO, () -> "executando=" + Visual.naEdt(d::executando) + " situacao=" + Visual.naEdt(d::situacaoFinal),
            () -> !Visual.naEdt(d::executando) && !Visual.naEdt(d::situacaoFinal).isEmpty());
    }

    private static void escolherOTipo(DialogoDoAgente d, Tipo t) {
        Visual.naEdt(() -> d.cartao(t).doClick());
    }

    private static void marcar(DialogoDoAgente d, String id, boolean marcado) {
        Visual.naEdt(() -> {
            if (d.caixaDoItem(id).isSelected() != marcado) {
                d.caixaDoItem(id).doClick();
            }
        });
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
                    d.mioloRolavel().rolarParaOTopo();
                }
            });
            int altura = Math.min(900, Visual.alturaPreferida(d.raiz(), 560));
            Visual.salvar(Visual.renderizar(d.raiz(), 560, altura), nome + (t == Tema.CLARO ? "-claro" : "-escuro"));
            if (nome.equals("dialogo-agente-escolha-tres-cartoes")) { // galeria do README
                Visual.salvarNaGaleria(Visual.renderizar(d.raiz(), 560, altura), "o-que-instalar" + (t == Tema.CLARO ? "-claro" : "-escuro"), d.titulo(), t == Tema.ESCURO,
                    MolduraDeJanela.Tipo.DIALOGO);
            }
        }
        Visual.tema(Tema.CLARO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(d.raiz()));
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
        } else if (c instanceof br.com.labdesktop.ui.componentes.Selo s) {
            sb.append(s.texto()).append('\n');
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

    private String frasesDoQueVaiAcontecer(DialogoDoAgente d) {
        return String.join("\n", Visual.naEdt(d::frasesDoQueVaiAcontecer));
    }

    // ---------------------------------------------------------------------------------------------
    // Os três cartões
    // ---------------------------------------------------------------------------------------------

    @Test
    void osTresCartoesComCompletoMarcadoERecomendadoEOContinuarLigado() {
        DialogoDoAgente d = abrirNaEscolha();

        assertTrue(Visual.naEdt(() -> d.cartao(Tipo.COMPLETO).isSelected()), "o padrão é Completo");
        assertFalse(Visual.naEdt(() -> d.cartao(Tipo.ESCOLHER).isSelected()));
        assertFalse(Visual.naEdt(() -> d.cartao(Tipo.SO_SSH).isSelected()));
        assertEquals("Completo", Visual.naEdt(() -> d.cartao(Tipo.COMPLETO).getText()));
        assertEquals("Escolher", Visual.naEdt(() -> d.cartao(Tipo.ESCOLHER).getText()));
        assertEquals("Só SSH, não instalar nada", Visual.naEdt(() -> d.cartao(Tipo.SO_SSH).getText()));
        String tela = textosDaRaiz(d);
        assertTrue(tela.contains("RECOMENDADO"), "o selo Recomendado está no cartão Completo: " + tela);
        assertTrue(tela.contains("O que instalar em vps-1"), tela);
        assertEquals("Continuar", Visual.naEdt(() -> d.botaoContinuar().getText()));
        assertTrue(Visual.naEdt(() -> d.botaoContinuar().isEnabled()));
        assertFalse(Visual.naEdt(d::listaDeItensVisivel), "a lista de caixas só aparece em Escolher: a primeira tela não é uma lista técnica");
        assertFalse(Visual.naEdt(() -> d.oQueIssoFaz().aberta()), "os detalhes ficam em \"O que isso faz?\", recolhido");
        assertTrue(tela.contains("O que isso faz?"), tela);
        assertEquals("Instala tudo o que falta neste servidor: Agente de monitoramento.", Visual.naEdt(d::fraseDoCompleto),
            "o Docker já está no servidor: o Completo é só o agente");
        assertFalse(tela.toLowerCase().contains("perfil"), "a palavra perfil não aparece: " + tela);
        assertTrue(servidor.escritas().isEmpty(), "só leitura até o clique de confirmação");
        assertEquals("escolher", Visual.naEdt(d::paginaAtual));
        fotografar(d, "dialogo-agente-escolha-tres-cartoes");
    }

    /**
     * Sem rolagem: o miolo do diálogo só rola quando o conteúdo passa de 80 % da altura da tela. Os três cartões cabem até numa tela de 700 px de
     * altura (a janela de 1100 por 700); com a lista de caixas aberta e a combinação travada, numa tela de 800 px (a de um notebook comum).
     */
    @Test
    void aTelaDeEscolhaCabeSemRolagem() {
        try {
            Dialogo.definirAlturaDaTelaParaTeste(700);
            DialogoDoAgente d = abrirNaEscolha();
            Visual.alturaPreferida(d.raiz(), 560);
            assertFalse(Visual.naEdt(() -> d.mioloRolavel().rola()), "os três cartões rolam numa tela de 700 px");

            Dialogo.definirAlturaDaTelaParaTeste(800);
            servidor.ubuntu().semDocker();
            DialogoDoAgente comDocker = abrirNaEscolha();
            escolherOTipo(comDocker, Tipo.ESCOLHER);
            Visual.alturaPreferida(comDocker.raiz(), 560);
            assertFalse(Visual.naEdt(() -> comDocker.mioloRolavel().rola()), "Escolher com as duas caixas rola numa tela de 800 px");
            marcar(comDocker, "docker", false);
            Visual.alturaPreferida(comDocker.raiz(), 560);
            assertTrue(Visual.naEdt(comDocker::explicacaoDaEscolha).length() > 0);
            assertFalse(Visual.naEdt(() -> comDocker.mioloRolavel().rola()), "a combinação travada (com a explicação) rola numa tela de 800 px");
        } finally {
            Dialogo.definirAlturaDaTelaParaTeste(0);
        }
    }

    @Test
    void escolherComODockerFaltandoMostraAsDuasCaixasMarcadasComOQueCadaUmExige() {
        servidor.ubuntu().semDocker();
        DialogoDoAgente d = abrirNaEscolha();
        escolherOTipo(d, Tipo.ESCOLHER);

        assertTrue(Visual.naEdt(d::listaDeItensVisivel));
        assertEquals(List.of("docker", "agente"), Visual.naEdt(d::itensListados), "a lista vem do catálogo de descrições, na ordem");
        assertTrue(Visual.naEdt(() -> d.caixaDoItem("docker").isSelected()));
        assertTrue(Visual.naEdt(() -> d.caixaDoItem("agente").isSelected()));
        assertEquals("Docker", Visual.naEdt(() -> d.caixaDoItem("docker").getText()));
        assertEquals("Agente de monitoramento", Visual.naEdt(() -> d.caixaDoItem("agente").getText()));
        String tela = textosDaRaiz(d);
        assertTrue(tela.contains("Alcança: O Docker (equivale a root); escuta só em 127.0.0.1."), tela);
        assertTrue(tela.contains("Exige: Debian ou Ubuntu"), "o que o Docker exige: " + tela);
        assertTrue(tela.contains("Precisa do Docker: falta neste servidor, marque-o também."), "o que o agente exige: " + tela);
        assertTrue(Visual.naEdt(() -> d.botaoContinuar().isEnabled()));
        assertEquals("", Visual.naEdt(d::explicacaoDaEscolha));
        assertEquals("Instala tudo o que falta neste servidor: Docker e Agente de monitoramento.", Visual.naEdt(d::fraseDoCompleto));
        fotografar(d, "dialogo-agente-escolha-docker-faltando");
    }

    @Test
    void comODockerNoServidorOEscolherSoTemOAgenteEOExigeDizQueOJaTem() {
        DialogoDoAgente d = abrirNaEscolha();
        escolherOTipo(d, Tipo.ESCOLHER);
        assertEquals(List.of("agente"), Visual.naEdt(d::itensListados));
        assertNull(Visual.naEdt(() -> d.caixaDoItem("docker")), "o Docker só aparece se faltar");
        assertTrue(textosDaRaiz(d).contains("Precisa do Docker: já está neste servidor."), textosDaRaiz(d));
    }

    // ---------------------------------------------------------------------------------------------
    // A trava das dependências
    // ---------------------------------------------------------------------------------------------

    @Test
    void desmarcarODockerComOAgenteMarcadoTravaOContinuarEExplica() {
        servidor.ubuntu().semDocker();
        DialogoDoAgente d = abrirNaEscolha();
        escolherOTipo(d, Tipo.ESCOLHER);

        marcar(d, "docker", false);

        assertFalse(Visual.naEdt(() -> d.botaoContinuar().isEnabled()), "combinação inválida: o app nunca instala metade");
        String explicacao = Visual.naEdt(d::explicacaoDaEscolha);
        assertTrue(explicacao.contains("Agente de monitoramento precisa do Docker, que falta neste servidor."), explicacao);
        assertTrue(explicacao.contains("Marque o Docker também, ou desmarque o Agente de monitoramento."), explicacao);
        Visual.naEdt(() -> d.botaoContinuar().doClick()); // mesmo clicando, nada anda
        assertEquals("escolher", Visual.naEdt(d::paginaAtual));
        assertNull(Visual.naEdt(d::escolhaEmVigor));
        fotografar(d, "dialogo-agente-escolha-travada");

        marcar(d, "docker", true);
        assertTrue(Visual.naEdt(() -> d.botaoContinuar().isEnabled()));
        assertEquals("", Visual.naEdt(d::explicacaoDaEscolha));

        marcar(d, "agente", false); // só o Docker: válido
        assertTrue(Visual.naEdt(() -> d.botaoContinuar().isEnabled()));
        marcar(d, "docker", false); // nada marcado
        assertFalse(Visual.naEdt(() -> d.botaoContinuar().isEnabled()));
        assertTrue(Visual.naEdt(d::explicacaoDaEscolha).contains("Marque pelo menos um item"), Visual.naEdt(d::explicacaoDaEscolha));
    }

    // ---------------------------------------------------------------------------------------------
    // Só SSH
    // ---------------------------------------------------------------------------------------------

    @Test
    void soSshFechaSemInstalarNadaESemPerguntarDeNovo() {
        servidor.ubuntu().semDocker();
        DialogoDoAgente d = abrirNaEscolha();
        escolherOTipo(d, Tipo.SO_SSH);
        assertEquals("Usar só por SSH", Visual.naEdt(() -> d.botaoContinuar().getText()));
        assertTrue(Visual.naEdt(() -> d.botaoContinuar().isEnabled()));

        Visual.naEdt(() -> d.botaoContinuar().doClick());

        assertTrue(Visual.naEdt(d::fechada), "a tela fecha");
        assertEquals(1, pediramSoSsh.get(), "quem abriu é avisado: o assistente conclui como Agora não");
        assertEquals(List.of(true), fechadoNoAviso, "o diálogo já estava fechado quando avisou: o assistente conclui sem este filho aberto");
        assertTrue(servidor.escritas().isEmpty(), "nada foi escrito no servidor: " + servidor.escritas());
        assertTrue(servidor.envios.isEmpty());
        assertTrue(ganchos.chamadas.isEmpty(), "o instalador não chegou a instalar nem a registrar token");
        assertEquals(0, mudancasNoServidor.get(), "o servidor não mudou: nada a reconferir");
        assertNull(Visual.naEdt(d::escolhaEmVigor));
        assertFalse(Visual.naEdt(d::executando));
        assertEquals(List.of(), avisos.todos, "sem alarme nem erro");
    }

    @Test
    void cancelarNaEscolhaFechaSemAvisarQueOServidorFicouSoPorSsh() {
        DialogoDoAgente d = abrirNaEscolha();
        Visual.naEdt(() -> d.botaoCancelarNaEscolha().doClick());
        assertTrue(Visual.naEdt(d::fechada));
        assertEquals(0, pediramSoSsh.get(), "Cancelar não é uma escolha: só Só SSH registra");
        assertTrue(servidor.escritas().isEmpty());
    }

    // ---------------------------------------------------------------------------------------------
    // Confirmação e execução só do que foi escolhido
    // ---------------------------------------------------------------------------------------------

    @Test
    void escolherSoODockerConfirmaEInstalaSoOsPassosDoDocker() {
        servidor.ubuntu().semDocker();
        DialogoDoAgente d = abrirNaEscolha();
        escolherOTipo(d, Tipo.ESCOLHER);
        marcar(d, "agente", false);
        Visual.naEdt(() -> d.botaoContinuar().doClick());

        assertEquals("confirmar", Visual.naEdt(d::paginaAtual));
        assertEquals("Instalar em vps-1", Visual.naEdt(() -> d.botaoConfirmar().getText()));
        assertTrue(Visual.naEdt(() -> d.botaoConfirmar().isEnabled()));
        String passos = frasesDoQueVaiAcontecer(d);
        assertTrue(passos.contains("1. Instalar o Docker pelo repositório oficial"), passos);
        assertFalse(passos.contains("container"), "o agente não foi escolhido: nada de container na lista: " + passos);
        assertFalse(passos.contains("pacote"), passos);
        assertFalse(passos.contains("imagem"), passos);
        String tela = textosDaRaiz(d);
        assertTrue(tela.contains("só o Docker"), tela);
        assertTrue(tela.contains("NADA foi instalado ainda"), tela);
        assertFalse(tela.contains("equivale a ser administrador (root): instale só em servidor seu"), "o texto do agente não vale aqui");
        assertTrue(tela.contains("O agente de monitoramento NÃO é instalado agora"), tela);
        assertFalse(Visual.naEdt(() -> d.opcoesAvancadas().isVisible()), "o nome do container não faz sentido sem o agente");
        List<String> verificacoes = Visual.naEdt(d::frasesDasVerificacoes);
        assertEquals(3, verificacoes.size(), "só Sistema, Permissão e Docker: " + verificacoes);
        assertEquals(List.of(PassoDeInstalacao.DOCKER), Visual.naEdt(d::passosMostrados));
        assertTrue(servidor.escritas().isEmpty(), "sem o clique de confirmação nada roda");
        fotografar(d, "dialogo-agente-confirmar-so-docker");

        Visual.naEdt(() -> d.botaoConfirmar().doClick());
        esperarFim(d);
        assertEquals("Docker instalado", Visual.naEdt(d::situacaoFinal));
        assertTrue(Visual.naEdt(d::textoFinal).contains("O agente de monitoramento ainda não foi instalado"), Visual.naEdt(d::textoFinal));
        assertEquals(ComandosRemotos.instalarODocker(false, "ubuntu"), servidor.escritas());
        assertNull(servidor.containerNome);
        assertTrue(ganchos.chamadas.isEmpty());
        assertEquals(EstadoDoPasso.FEITO, Visual.naEdt(() -> d.estadoDoPasso(PassoDeInstalacao.DOCKER)));
        assertEquals(EstadoDoPasso.PENDENTE, Visual.naEdt(() -> d.estadoDoPasso(PassoDeInstalacao.SUBIR_CONTAINER)), "o passo do agente nem apareceu");
        assertEquals(1, mudancasNoServidor.get());
        assertTrue(avisos.disse("ok", "Docker instalado em vps-1"));
        fotografar(d, "dialogo-agente-sucesso-so-docker");
    }

    @Test
    void escolherSoOAgenteComODockerPresenteNaoTemLinhaNemPassoDoDocker() {
        DialogoDoAgente d = abrirNaEscolha();
        escolherOTipo(d, Tipo.ESCOLHER);
        Visual.naEdt(() -> d.botaoContinuar().doClick());

        assertEquals("confirmar", Visual.naEdt(d::paginaAtual));
        String passos = frasesDoQueVaiAcontecer(d);
        assertFalse(passos.contains("Docker: já está instalado"), "não escolhido, não vira linha: " + passos);
        assertTrue(passos.contains("1. Enviar o pacote do agente"), passos);
        assertTrue(passos.contains("4. Conferir que o agente responde e registrar o token"), passos);
        assertEquals(12, Visual.naEdt(d::frasesDasVerificacoes).size(), "com o agente valem as doze conferências");
        assertTrue(textosDaRaiz(d).contains("equivale a ser administrador (root)"), "o texto de segurança do agente");
        fotografar(d, "dialogo-agente-confirmar-so-agente");

        Visual.naEdt(() -> d.botaoConfirmar().doClick());
        esperarFim(d);
        assertEquals("Agente instalado", Visual.naEdt(d::situacaoFinal));
        List<Passo> mostrados = Visual.naEdt(d::passosMostrados);
        assertEquals(5, mostrados.size());
        assertFalse(mostrados.contains(PassoDeInstalacao.DOCKER));
        assertEquals("painel-lab", servidor.containerNome);
    }

    @Test
    void completoComODockerFaltandoInstalaOsDoisNaOrdem() {
        servidor.ubuntu().semDocker();
        DialogoDoAgente d = abrirNaEscolha();
        Visual.naEdt(() -> d.botaoContinuar().doClick()); // Completo, já marcado

        assertEquals("confirmar", Visual.naEdt(d::paginaAtual));
        String passos = frasesDoQueVaiAcontecer(d);
        assertTrue(passos.contains("1. Instalar o Docker"), passos);
        assertTrue(passos.contains("2. Enviar o pacote do agente"), passos);
        assertEquals(List.of(PassoDeInstalacao.values()), Visual.naEdt(d::passosMostrados));
        Visual.naEdt(() -> d.botaoConfirmar().doClick());
        esperarFim(d);
        assertEquals("Agente instalado", Visual.naEdt(d::situacaoFinal));
        assertEquals("painel-lab", servidor.containerNome);
        assertTrue(servidor.escritas().subList(0, 8).equals(ComandosRemotos.instalarODocker(false, "ubuntu")));
    }

    @Test
    void semOCliqueDeConfirmacaoNadaRodaNemEscolhendoCompletoNemEscolhendoOsItens() {
        servidor.ubuntu().semDocker();
        DialogoDoAgente d = abrirNaEscolha();
        assertTrue(servidor.escritas().isEmpty());
        escolherOTipo(d, Tipo.ESCOLHER);
        marcar(d, "agente", false);
        Visual.naEdt(() -> d.botaoContinuar().doClick());
        assertEquals("confirmar", Visual.naEdt(d::paginaAtual));
        assertTrue(servidor.escritas().isEmpty(), "escolher não instala: " + servidor.escritas());
        assertTrue(servidor.envios.isEmpty());
        assertTrue(ganchos.chamadas.isEmpty());
        assertFalse(Visual.naEdt(d::executando));
        Visual.naEdt(() -> d.botaoCancelar().doClick());
        assertTrue(Visual.naEdt(d::fechada));
        assertTrue(servidor.escritas().isEmpty(), "cancelar na confirmação também não muda nada");
        assertEquals(0, mudancasNoServidor.get());
    }

    @Test
    void voltarDaConfirmacaoMantemAEscolhaEPermiteMudarDeIdeia() {
        servidor.ubuntu().semDocker();
        DialogoDoAgente d = abrirNaEscolha();
        escolherOTipo(d, Tipo.ESCOLHER);
        marcar(d, "agente", false);
        Visual.naEdt(() -> d.botaoContinuar().doClick());
        assertEquals(List.of(PassoDeInstalacao.DOCKER), Visual.naEdt(d::passosMostrados));

        Visual.naEdt(() -> d.botaoVoltar().doClick());
        assertEquals("escolher", Visual.naEdt(d::paginaAtual));
        assertTrue(Visual.naEdt(() -> d.cartao(Tipo.ESCOLHER).isSelected()), "a escolha de antes continua à vista");
        assertTrue(Visual.naEdt(() -> d.caixaDoItem("docker").isSelected()));
        assertFalse(Visual.naEdt(() -> d.caixaDoItem("agente").isSelected()));

        marcar(d, "agente", true);
        Visual.naEdt(() -> d.botaoContinuar().doClick());
        assertEquals("confirmar", Visual.naEdt(d::paginaAtual));
        assertEquals(List.of(PassoDeInstalacao.values()), Visual.naEdt(d::passosMostrados));
        assertTrue(servidor.escritas().isEmpty());
    }

    @Test
    void conferirDeNovoNaConfirmacaoMantemAEscolhaReavaliadaComOServidorComoEleEstaAgora() {
        servidor.fedora().semDocker(); // o app não instala o Docker no Fedora
        DialogoDoAgente d = abrirNaEscolha();
        Visual.naEdt(() -> d.botaoContinuar().doClick()); // Completo: o Docker e o agente
        assertFalse(Visual.naEdt(() -> d.botaoConfirmar().isEnabled()), "o Docker precisa ser instalado antes, à mão");
        assertEquals("Já instalei, continuar", Visual.naEdt(() -> d.botaoConferirDeNovo().getText()));

        servidor.dockerInstalado = true; // o usuário instalou o Docker
        Visual.naEdt(() -> d.botaoConferirDeNovo().doClick());
        Espera.ate(PRAZO, "confirmação liberada", () -> Visual.naEdt(() -> d.botaoConfirmar().isEnabled()));
        assertEquals("confirmar", Visual.naEdt(d::paginaAtual), "a escolha segue valendo: não volta à primeira tela");
        assertEquals(List.of("agente"), Visual.naEdt(() -> d.escolhaEmVigor().itens().stream().map(i -> i.id()).toList()),
            "o Docker já está lá: sobra o agente");
        assertTrue(servidor.escritas().isEmpty());
    }

    // ---------------------------------------------------------------------------------------------
    // Quem já tem agente, Atualizar e Remover não veem a escolha
    // ---------------------------------------------------------------------------------------------

    @Test
    void quemJaTemOAgenteNaoVeAEscolhaEVaiDiretoParaAConfirmacao() {
        servidor.containerNome = "painel-lab";
        servidor.rotuloDoContainer = "0.1.0-abc1234";
        DialogoDoAgente d = abrir(DialogoDoAgente.Acao.INSTALAR, "0.1.0-abc1234");
        esperarPagina(d, "confirmar");
        assertNotNull(Visual.naEdt(d::escolhaEmVigor));
        assertFalse(textosDaRaiz(d).contains("O que instalar"), textosDaRaiz(d));
        assertNull(Visual.naEdt(() -> d.botaoVoltar().getParent()), "sem a escolha não há para onde voltar: o botão nem está na tela");
    }

    @Test
    void atualizarVaiDiretoAConfirmacaoSemOPassoDoDockerQueJaExiste() {
        servidor.containerNome = "painel-lab";
        servidor.rotuloDoContainer = "0.0.9-velha";
        servidor.volumeExiste = true;
        servidor.pastaExiste = true;
        ganchos.tokenJaValia = true;
        DialogoDoAgente d = abrir(DialogoDoAgente.Acao.ATUALIZAR, "0.0.9-velha");
        esperarPagina(d, "confirmar");
        assertFalse(frasesDoQueVaiAcontecer(d).contains("Instalar o Docker") || frasesDoQueVaiAcontecer(d).contains("Docker: já está"),
            "o que já existe não é reoferecido: " + frasesDoQueVaiAcontecer(d));
        assertFalse(Visual.naEdt(d::passosMostrados).contains(PassoDeInstalacao.DOCKER));
        assertEquals(5, Visual.naEdt(d::passosMostrados).size());
        Visual.naEdt(() -> d.botaoConfirmar().doClick());
        esperarFim(d);
        assertEquals("Agente atualizado", Visual.naEdt(d::situacaoFinal));
    }

    @Test
    void removerNaoTemEscolhaEContinuaComoSempre() {
        servidor.containerNome = "painel-lab";
        servidor.rotuloDoContainer = "0.1.0-abc1234";
        servidor.volumeExiste = true;
        servidor.imagemExiste = true;
        servidor.pastaExiste = true;
        DialogoDoAgente d = abrir(DialogoDoAgente.Acao.REMOVER, "0.1.0-abc1234");
        assertEquals("confirmar", Visual.naEdt(d::paginaAtual));
        assertNull(Visual.naEdt(d::escolhaEmVigor));
        assertFalse(textosDaRaiz(d).contains("Só SSH"));
        Visual.naEdt(() -> d.botaoConfirmar().doClick());
        esperarFim(d);
        assertEquals("Agente removido", Visual.naEdt(d::situacaoFinal));
        assertTrue(servidor.dockerInstalado, "remover não desinstala o Docker");
    }

    @Test
    void oCatalogoDaTelaTemSoOsItensQueOInstaladorFazDeFormaIndependente() {
        assertEquals(List.of("docker", "agente"), CatalogoDeInstalacao.itens().stream().map(i -> i.id()).toList());
        List<String> todos = new ArrayList<>();
        CatalogoDeInstalacao.itens().forEach(i -> todos.add(i.titulo() + i.frase() + i.alcance() + i.exige()));
        assertFalse(String.join(" ", todos).toLowerCase().contains("perfil"), "texto para o usuário sem a palavra perfil");
    }

    // ---------------------------------------------------------------------------------------------
    // Clique duplo, nome inválido escondido, escolha de relatório velho, "Tentar de novo" parcial
    // ---------------------------------------------------------------------------------------------

    private void cliqueDuploEEnterDuploNaoInstalam(Tipo tipo) {
        DialogoDoAgente.definirPrazoDaTravaDaConfirmacaoParaTeste(400);
        DialogoDoAgente d = abrirNaEscolha();
        escolherOTipo(d, tipo);

        // o segundo clique cai onde estava o "Continuar": no botão de instalar, que acabou de aparecer no mesmo lugar
        Visual.naEdt(() -> {
            d.botaoContinuar().doClick();
            d.botaoConfirmar().doClick();
        });
        assertEquals("confirmar", Visual.naEdt(d::paginaAtual));
        assertFalse(Visual.naEdt(d::executando), "o clique duplo não instala");
        assertFalse(Visual.naEdt(() -> d.botaoConfirmar().isEnabled()), "o botão de instalar nasce desligado");
        assertTrue(servidor.escritas().isEmpty(), "nada foi escrito: " + servidor.escritas());
        assertTrue(ganchos.chamadas.isEmpty());

        // dois Enter: nenhum botão é o padrão enquanto a trava dura
        Visual.naEdt(() -> {
            javax.swing.JButton padrao = d.botaoDoEnter();
            assertTrue(padrao == null || padrao != d.botaoConfirmar(), "o Instalar não responde ao Enter ainda");
            if (padrao != null) {
                padrao.doClick();
            }
        });
        assertFalse(Visual.naEdt(d::executando));
        assertTrue(servidor.escritas().isEmpty());

        // passado o prazo, um clique deliberado instala
        Espera.ate(PRAZO, "o botão liga", () -> Visual.naEdt(() -> d.botaoConfirmar().isEnabled()));
        assertEquals(Visual.naEdt(d::botaoConfirmar), Visual.naEdt(d::botaoDoEnter), "e volta a ser o botão do Enter");
        Visual.naEdt(() -> d.botaoConfirmar().doClick());
        esperarFim(d);
        assertEquals("Agente instalado", Visual.naEdt(d::situacaoFinal));
        assertEquals("painel-lab", servidor.containerNome);
    }

    @Test
    void cliqueDuploEDoisEnterEmContinuarComCompletoNaoInstalamSemOCliqueDeConfirmacao() {
        cliqueDuploEEnterDuploNaoInstalam(Tipo.COMPLETO);
    }

    @Test
    void cliqueDuploEDoisEnterEmContinuarComEscolherNaoInstalamSemOCliqueDeConfirmacao() {
        cliqueDuploEEnterDuploNaoInstalam(Tipo.ESCOLHER);
    }

    @Test
    void nomeDeContainerInvalidoDigitadoNaoDeixaOInstalarSoODockerMudo() {
        servidor.ubuntu().semDocker();
        DialogoDoAgente d = abrirNaEscolha();
        Visual.naEdt(() -> d.botaoContinuar().doClick()); // Completo
        Visual.naEdt(() -> d.campoDoContainer().setText("Painel; rm -rf /"));
        Visual.naEdt(() -> d.botaoVoltar().doClick());
        escolherOTipo(d, Tipo.ESCOLHER);
        marcar(d, "agente", false); // só o Docker: o nome do container (escondido) não conta
        Visual.naEdt(() -> d.botaoContinuar().doClick());
        assertEquals("confirmar", Visual.naEdt(d::paginaAtual));

        Visual.naEdt(() -> d.botaoConfirmar().doClick());
        esperarFim(d);
        assertEquals("Docker instalado", Visual.naEdt(d::situacaoFinal), "o botão não ficou mudo");
        assertEquals(ComandosRemotos.instalarODocker(false, "ubuntu"), servidor.escritas());
    }

    @Test
    void tentarDeNovoComEscolhaParcialRefazSoOQueFoiEscolhido() {
        servidor.ubuntu().semDocker();
        servidor.falharUmaVezEm(c -> c.contains("apt-get install -y docker-ce"), 100, "E: Unable to locate package docker-ce");
        DialogoDoAgente d = abrirNaEscolha();
        escolherOTipo(d, Tipo.ESCOLHER);
        marcar(d, "agente", false);
        Visual.naEdt(() -> d.botaoContinuar().doClick());
        Visual.naEdt(() -> d.botaoConfirmar().doClick());
        esperarFim(d);
        assertEquals("Parou em um passo", Visual.naEdt(d::situacaoFinal));
        assertTrue(Visual.naEdt(d::textoFinal).contains("Unable to locate package"), Visual.naEdt(d::textoFinal));
        assertEquals(List.of(PassoDeInstalacao.DOCKER), Visual.naEdt(d::passosMostrados));

        Visual.naEdt(() -> d.botaoTentarDeNovo().doClick());
        Espera.ate(PRAZO, "sucesso", () -> "Docker instalado".equals(Visual.naEdt(d::situacaoFinal)));
        assertEquals(List.of(PassoDeInstalacao.DOCKER), Visual.naEdt(d::passosMostrados), "a segunda tentativa também só tem o passo do Docker");
        assertNull(servidor.containerNome, "o agente não foi escolhido: nunca foi criado");
        assertTrue(servidor.envios.isEmpty());
        assertTrue(ganchos.chamadas.isEmpty());
        assertEquals(1, mudancasNoServidor.get());
    }

    @Test
    void conferirDeNovoNoSentidoInversoOAgenteSeguePoisODockerQueEstavaMarcadoJaFoiInstaladoAMao() {
        servidor.fedora().semDocker(); // o app não instala o Docker no Fedora
        DialogoDoAgente d = abrirNaEscolha();
        escolherOTipo(d, Tipo.ESCOLHER); // marcados: Docker e agente
        Visual.naEdt(() -> d.botaoContinuar().doClick());
        assertFalse(Visual.naEdt(() -> d.botaoConfirmar().isEnabled()), "bloqueado: o Docker precisa ser instalado à mão");

        servidor.dockerInstalado = true; // o usuário o instalou
        Visual.naEdt(() -> d.botaoConferirDeNovo().doClick());
        Espera.ate(PRAZO, "confirmação liberada", () -> Visual.naEdt(() -> d.botaoConfirmar().isEnabled()));
        assertEquals("confirmar", Visual.naEdt(d::paginaAtual));
        assertEquals(List.of("agente"), Visual.naEdt(() -> d.escolhaEmVigor().itens().stream().map(i -> i.id()).toList()), "sobra só o agente");
        assertFalse(Visual.naEdt(d::passosMostrados).contains(PassoDeInstalacao.DOCKER));

        Visual.naEdt(() -> d.botaoVoltar().doClick());
        assertNull(Visual.naEdt(() -> d.caixaDoItem("docker")), "o Docker sumiu da lista: já está no servidor");
        assertTrue(Visual.naEdt(() -> d.caixaDoItem("agente").isSelected()));
    }
}
