package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.ServicosDeArquivos;
import br.com.labdesktop.cloudflare.EnderecoDoApp;
import br.com.labdesktop.cloudflare.PublicadorPorArquivoTestApoio;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.teste.CloudflareFalsa;
import br.com.labdesktop.ui.dialogos.DialogoAdicionarDominio;
import br.com.labdesktop.ui.dialogos.DialogoConectarCloudflare;
import br.com.labdesktop.ui.dialogos.DialogoEscolherProjeto;
import br.com.labdesktop.ui.dialogos.DialogoPublicar;
import br.com.labdesktop.util.Preferencias.Tema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * "Adicionar domínio" pelas ações da janela, de ponta a ponta contra a Cloudflare FALSA e o servidor simulado: com a Cloudflare conectada abre o assistente e vai até publicar de verdade; sem
 * conexão abre o "Conectar a Cloudflare" PRIMEIRO e, depois de salvar, volta ao assistente; sem as ações ligadas não escreve nada; em Este computador explica.
 */
@ExtendWith(VerificaEdt.class)
class AdicionarDominioPelaInterfaceTest {

    private static final Duration PRAZO = Duration.ofSeconds(30);

    @TempDir
    Path tmp;

    CenarioDeCloudflare c;
    ServidorLab lab;
    final AvisosGravados avisos = new AvisosGravados();
    final DialogosGravados dialogos = new DialogosGravados();
    final AtomicInteger depois = new AtomicInteger();
    AcoesDaInterface acoes;

    @BeforeEach
    void montar() throws Exception {
        Visual.tema(Tema.CLARO);
        c = new CenarioDeCloudflare(tmp);
        c.prepararOServidorDoDono(PublicadorPorArquivoTestApoio.COMPOSE, PublicadorPorArquivoTestApoio.CONFIG);
        lab = c.servidorLab();
        acoes = new AcoesDaInterface(null, null, avisos, dialogos, s -> { }, new NavegadorGravado(avisos), ServicosDeArquivos.descartaveis());
        acoes.usarTrava(() -> true);
    }

    @AfterEach
    void desmontar() {
        c.close();
        Visual.tema(Tema.CLARO);
    }

    private static <T> T ed(java.util.function.Supplier<T> s) {
        return Visual.naEdt(s);
    }

    private void passarPelosQuatroPassos(DialogoAdicionarDominio d, String nome) {
        Visual.naEdt(() -> d.botaoContinuar().doClick(0)); // conta
        Visual.naEdt(() -> d.botaoContinuar().doClick(0)); // domínio
        Visual.naEdt(() -> d.campoNome().setText(nome));
        Visual.naEdt(() -> d.botaoContinuar().doClick(0)); // endereço
        Espera.ate(PRAZO, "o serviço do compose", () -> ed(() -> d.caixaDeServicos().getItemCount()) == 1);
    }

    @Test
    void conectadoAbreOAssistenteEVaiAtePublicarDeVerdade() throws Exception {
        c.conectarPorArquivo();
        Visual.naEdt(() -> acoes.adicionarDominio(lab, "demo", depois::incrementAndGet));
        DialogoAdicionarDominio d = dialogos.ultimoComo(DialogoAdicionarDominio.class);
        assertEquals("CONECTADA", ed(d::seloDaConta));
        assertTrue(ed(d::textoDaConta).contains("Conta de Teste"), ed(d::textoDaConta));
        passarPelosQuatroPassos(d, "meu-site");
        Visual.naEdt(() -> d.botaoContinuar().doClick(0));
        DialogoPublicar p = dialogos.ultimoComo(DialogoPublicar.class);
        assertEquals("Adicionar domínio: passo 5 de 5", p.titulo());
        Espera.ate(PRAZO, "o serviço do compose", () -> ed(() -> p.caixaDeServicos().getItemCount()) == 1);
        assertEquals("meu-site", ed(() -> p.campoNome().getText()), "o último passo nasce preenchido pelo assistente");
        assertEquals("app", ed(() -> p.caixaDeServicos().getSelectedItem()));
        assertEquals("3000", ed(() -> p.campoPorta().getText()));
        assertTrue(ed(() -> p.botaoVoltar().isVisible()), "o último passo tem Voltar");
        Visual.naEdt(() -> p.botaoConferir().doClick());
        Espera.ate(PRAZO, () -> "prévia: " + ed(p::textoDoBloqueio), () -> ed(() -> p.previa() != null));
        assertTrue(ed(() -> p.botaoPublicar().isEnabled()));
        assertEquals(List.of(), c.cf.escritas(), "até aqui nada foi escrito na Cloudflare");
        Visual.naEdt(() -> p.botaoPublicar().doClick());
        Espera.ate(PRAZO, () -> "fim: " + ed(p::seloDoResultado), () -> !ed(p::ocupado) && !ed(p::seloDoResultado).isEmpty());
        assertEquals("NO AR", ed(p::seloDoResultado));
        assertEquals(1, depois.get(), "a aba Internet se atualiza");
        List<EnderecoDoApp> enderecos = c.servico.enderecos("demo");
        assertEquals(List.of("meu-site.example.com"), enderecos.stream().map(EnderecoDoApp::hostname).toList());
        assertTrue(c.lab.ler(br.com.labdesktop.cloudflare.LabSimulado.PROJETOS + "/demo/docker-compose.yml").contains("container_name: demo-app"));
    }

    @Test
    void voltarDoUltimoPassoReabreOAssistenteNoQuartoPassoSemPerderOMotor() throws Exception {
        c.conectarPorArquivo();
        Visual.naEdt(() -> acoes.adicionarDominio(lab, "demo", null));
        DialogoAdicionarDominio d = dialogos.ultimoComo(DialogoAdicionarDominio.class);
        passarPelosQuatroPassos(d, "outro-nome");
        Visual.naEdt(() -> d.botaoContinuar().doClick(0));
        DialogoPublicar p = dialogos.ultimoComo(DialogoPublicar.class);
        Espera.ate(PRAZO, "serviço", () -> ed(() -> p.caixaDeServicos().getItemCount()) == 1);
        Visual.naEdt(() -> p.botaoVoltar().doClick(0));
        assertTrue(ed(p::fechou));
        DialogoAdicionarDominio de_novo = dialogos.ultimoComo(DialogoAdicionarDominio.class);
        Espera.ate(PRAZO, "serviço", () -> ed(() -> de_novo.caixaDeServicos().getItemCount()) == 1);
        assertEquals("SERVICO", ed(de_novo::passoAtual));
        Visual.naEdt(() -> de_novo.botaoVoltar().doClick(0));
        assertEquals("outro-nome", ed(() -> de_novo.campoNome().getText()));
        // e dá para seguir de novo até a confirmação
        Visual.naEdt(() -> de_novo.botaoContinuar().doClick(0));
        Espera.ate(PRAZO, "serviço", () -> ed(() -> de_novo.caixaDeServicos().getItemCount()) == 1);
        Visual.naEdt(() -> de_novo.botaoContinuar().doClick(0));
        DialogoPublicar outra = dialogos.ultimoComo(DialogoPublicar.class);
        Visual.naEdt(() -> outra.botaoConferir().doClick());
        Espera.ate(PRAZO, () -> "prévia: " + ed(outra::textoDoBloqueio), () -> ed(() -> outra.previa() != null));
        assertTrue(ed(() -> outra.botaoPublicar().isEnabled()), "o motor continuou vivo depois do Voltar");
    }

    @Test
    void semCloudflareConectadaAbreOConectarPrimeiroEDepoisVoltaAoAssistente() throws Exception {
        Visual.naEdt(() -> acoes.adicionarDominio(lab, "demo", null));
        DialogoConectarCloudflare conectar = dialogos.ultimoComo(DialogoConectarCloudflare.class);
        assertEquals(1, dialogos.mostrados.size(), "nada de assistente antes de conectar");
        Visual.naEdt(() -> conectar.campoDoToken().setText(CloudflareFalsa.TOKEN_CERTO));
        Visual.naEdt(() -> conectar.botaoPrincipal().doClick());
        Espera.ate(PRAZO, () -> "escolha: " + ed(conectar::paginaAtual), () -> ed(conectar::paginaAtual) == DialogoConectarCloudflare.Pagina.ESCOLHA);
        Espera.ate(PRAZO, () -> "túneis: " + ed(conectar::itensDeTunel), () -> ed(conectar::itensDeTunel).size() >= 2);
        Visual.naEdt(() -> conectar.escolherOTunelNumero(1));
        Visual.naEdt(() -> conectar.botaoPrincipal().doClick());
        Espera.ate(PRAZO, "voltou ao assistente", () -> dialogos.ultimo() instanceof DialogoAdicionarDominio);
        DialogoAdicionarDominio d = dialogos.ultimoComo(DialogoAdicionarDominio.class);
        assertEquals("CONECTADA", ed(d::seloDaConta));
        assertTrue(ed(() -> d.botaoContinuar().isEnabled()));
    }

    @Test
    void semAsAcoesLigadasNadaAbreEOAppDiz() {
        acoes.usarTrava(() -> false);
        Visual.naEdt(() -> acoes.adicionarDominio(lab, "demo", null));
        assertTrue(dialogos.mostrados.isEmpty());
        assertTrue(avisos.ultimo().texto().contains("As ações que escrevem no servidor estão desligadas"), avisos.ultimo().texto());
    }

    @Test
    void emEsteComputadorExplicaQueNaoHaTunel() {
        lab.definirCloudflare(c.novoServico(true));
        Visual.naEdt(() -> acoes.adicionarDominio(lab, "demo", null));
        assertTrue(dialogos.mostrados.isEmpty());
        assertTrue(avisos.ultimo().texto().contains("Este computador não tem um túnel"), avisos.ultimo().texto());
    }

    @Test
    void doServidorPerguntaParaQualProjetoEDepoisAbreOAssistente() throws Exception {
        c.conectarPorArquivo();
        Visual.naEdt(() -> acoes.adicionarDominioEscolhendoOProjeto(lab, List.of("alfa", "demo"), null));
        DialogoEscolherProjeto e = dialogos.ultimoComo(DialogoEscolherProjeto.class);
        assertEquals("alfa", ed(() -> e.lista().getItemAt(0)));
        Visual.naEdt(() -> e.lista().setSelectedItem("demo"));
        Visual.naEdt(() -> e.botaoContinuar().doClick(0));
        DialogoAdicionarDominio d = dialogos.ultimoComo(DialogoAdicionarDominio.class);
        assertTrue(ed(() -> CenarioDeCloudflare.textos(d.raiz())).contains("Projeto demo"));
    }

    @Test
    void doServidorSemProjetosAvisa() {
        Visual.naEdt(() -> acoes.adicionarDominioEscolhendoOProjeto(lab, List.of(), null));
        assertTrue(dialogos.mostrados.isEmpty());
        assertTrue(avisos.ultimo().texto().contains("ainda não tem projetos"));
        assertFalse(avisos.todos.isEmpty());
    }
}
