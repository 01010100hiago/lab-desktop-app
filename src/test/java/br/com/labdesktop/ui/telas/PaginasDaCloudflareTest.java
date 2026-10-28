package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.ServicosDeArquivos;
import br.com.labdesktop.cloudflare.ObservadorDaEtapa;
import br.com.labdesktop.cloudflare.PublicadorNaInternet;
import br.com.labdesktop.cloudflare.PublicadorPorArquivoTestApoio;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.teste.CloudflareFalsa;
import br.com.labdesktop.ui.AcoesDaInterface;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.CenarioDeCloudflare;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.NavegadorGravado;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.dialogos.DialogoConectarCloudflare;
import br.com.labdesktop.ui.dialogos.DialogoConfirmar;
import br.com.labdesktop.ui.dialogos.DialogoPublicar;
import br.com.labdesktop.ui.dialogos.DialogoRemoverEndereco;
import br.com.labdesktop.util.Preferencias.Tema;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * A aba "Cloudflare" do servidor e a aba "Internet" do projeto: conexão, ficha, lista de endereços do app com Abrir e Remover, os estados (não conectada, este computador, erro de
 * leitura), a trava das ações de escrita e o token que nunca aparece. PNGs nos dois temas; tudo contra a Cloudflare FALSA.
 */
@ExtendWith(VerificaEdt.class)
class PaginasDaCloudflareTest {

    private static final Duration PRAZO = Duration.ofSeconds(25);

    @TempDir
    Path tmp;

    CenarioDeCloudflare c;
    ServidorLab lab;
    final AvisosGravados avisos = new AvisosGravados();
    final DialogosGravados dialogos = new DialogosGravados();
    final NavegadorGravado navegador = new NavegadorGravado(avisos);
    AcoesDaInterface acoes;

    @BeforeEach
    void montar() throws Exception {
        Visual.tema(Tema.CLARO);
        c = new CenarioDeCloudflare(tmp);
        c.prepararOServidorDoDono(PublicadorPorArquivoTestApoio.COMPOSE, PublicadorPorArquivoTestApoio.CONFIG);
        lab = c.servidorLab();
        acoes = new AcoesDaInterface(null, null, avisos, dialogos, s -> { }, navegador, ServicosDeArquivos.descartaveis());
        acoes.usarTrava(() -> true); // os testes de tela ligam as ações; o teste da trava interna liga e desliga
    }

    @AfterEach
    void desmontar() {
        c.close();
        Visual.tema(Tema.CLARO);
    }

    private static <T> T ed(java.util.function.Supplier<T> s) {
        return Visual.naEdt(s);
    }

    private void publicarDemo() throws Exception {
        try (PublicadorNaInternet p = c.servico.publicador(x -> { })) {
            PublicadorNaInternet.Resultado r = p.executar(p.preparar(new PublicadorNaInternet.Pedido("demo", "app", "demo-app", 3000, "lab-demo", false)), ObservadorDaEtapa.NENHUM,
                br.com.labdesktop.arquivos.Cancelamento.NUNCA);
            assertEquals(PublicadorNaInternet.Desfecho.PUBLICADO, r.desfecho(), r.falha());
        }
    }

    private PaginaCloudflare paginaDoServidor(boolean liberadas) {
        return Visual.naEdt(() -> new PaginaCloudflare(lab, acoes, liberadas, avisos));
    }

    private void recarregar(PaginaCloudflare p) {
        Visual.naEdt(p::recarregar);
        Espera.ate(PRAZO, () -> "carregou? modo=" + ed(p::modo) + " erro=" + ed(p::textoDoErroDosEnderecos), () -> ed(p::carregou) && !ed(p::textoDoSelo).isEmpty());
        Espera.ate(PRAZO, "terminou de ler", () -> !CenarioDeCloudflare.textos(p).contains("Lendo da Cloudflare") && !CenarioDeCloudflare.textos(p).contains("Atualizando"));
    }

    private static void fotografar(PaginaCloudflare p, String nome) {
        for (Tema t : List.of(Tema.CLARO, Tema.ESCURO)) {
            Visual.tema(t);
            Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(p));
            Visual.salvar(Visual.renderizar(p, 1100, 700), nome + "-" + (t == Tema.CLARO ? "claro" : "escuro"));
        }
        Visual.tema(Tema.CLARO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(p));
    }

    // =====================================================================================================
    // A aba Cloudflare do servidor
    // =====================================================================================================

    @Test
    void naoConectadaExplicaEOferecoConectarQueAbreODialogoDoToken() {
        PaginaCloudflare p = paginaDoServidor(true);
        recarregar(p);
        assertEquals(PaginaCloudflare.Modo.NAO_CONECTADO, ed(p::modo));
        assertEquals("NÃO CONECTADA", ed(p::textoDoSelo));
        String t = CenarioDeCloudflare.textos(p);
        assertTrue(t.contains("token de API que você mesmo"), t);
        assertTrue(t.contains("cofre cifrado"), t);
        assertTrue(ed(() -> p.botaoConectar().isShowing() || p.botaoConectar().isVisible()));
        fotografar(p, "pagina-cloudflare-1-nao-conectada");
        Visual.naEdt(() -> p.botaoConectar().doClick());
        DialogoConectarCloudflare d = dialogos.ultimoComo(DialogoConectarCloudflare.class);
        assertEquals("Conectar a Cloudflare", d.titulo());
        assertEquals(List.of(), c.cf.pedidos, "abrir o diálogo não fala com a Cloudflare");
    }

    @Test
    void conectadaMostraAFichaSemEnderecosEOTokenNuncaAparece() throws Exception {
        c.conectarPorArquivo();
        PaginaCloudflare p = paginaDoServidor(true);
        recarregar(p);
        assertEquals(PaginaCloudflare.Modo.CONECTADO, ed(p::modo));
        assertEquals("CONECTADA", ed(p::textoDoSelo));
        String t = CenarioDeCloudflare.textos(p);
        assertTrue(t.contains("example.com"), t);
        assertTrue(t.contains("config.yml do servidor (" + "/srv/lab/infra/cloudflared/config.yml" + "), container cloudflared"), t);
        assertTrue(t.contains("guardado no cofre, cifrado"), t);
        assertTrue(t.contains("Nenhum endereço criado pelo app ainda"), t);
        assertFalse(t.contains(CloudflareFalsa.TOKEN_CERTO), "o token nunca aparece");
        assertEquals(List.of(), ed(p::enderecosMostrados));
        assertEquals(List.of(), c.cf.escritas(), "abrir a aba só LÊ");
        assertEquals(0, c.lab.reinicios.get(), "abrir a aba não reinicia nada");
        fotografar(p, "pagina-cloudflare-2-conectada-sem-enderecos");
    }

    @Test
    void listaOsEnderecosDoAppComAbrirERemoverEOsBotoesAbremOsDialogosCertos() throws Exception {
        c.conectarPorArquivo();
        publicarDemo();
        c.cf.semear("lab-de-teste.example.com", "CNAME", "x.cfargotunnel.com", "do usuário");
        c.cf.pedidos.clear();
        PaginaCloudflare p = paginaDoServidor(true);
        recarregar(p);
        assertEquals(List.of("lab-demo.example.com"), ed(p::enderecosMostrados), "só o que o app criou (marca lab-desktop:)");
        String t = CenarioDeCloudflare.textos(p);
        assertTrue(t.contains("projeto demo"), t);
        assertTrue(t.contains("vai para http://demo-app:3000"), t);
        assertTrue(t.contains("NO TÚNEL"), t);
        assertTrue(c.cf.escritas().isEmpty());
        fotografar(p, "pagina-cloudflare-3-com-enderecos");

        Visual.naEdt(() -> p.botoesDeAbrir().get(0).doClick());
        assertEquals(List.of("https://lab-demo.example.com/"), navegador.abertas.stream().map(java.net.URI::toString).toList());

        assertEquals(1, ed(() -> p.botoesDeRemover().size()));
        Visual.naEdt(() -> p.botoesDeRemover().get(0).doClick());
        DialogoRemoverEndereco d = dialogos.ultimoComo(DialogoRemoverEndereco.class);
        Visual.naEdt(() -> d.botaoCancelar().doClick());
        assertEquals(List.of(), c.cf.escritas(), "abrir e cancelar o diálogo não escreve nada");
    }

    @Test
    void semATravaLiberadaAsAcoesRecusamPublicarERemoverPorDentroMesmoSeAlguemAsChamar() throws Exception {
        c.conectarPorArquivo();
        publicarDemo();
        c.cf.pedidos.clear();
        int dialogosAntes = dialogos.mostrados.size();
        acoes.usarTrava(() -> false);
        Visual.naEdt(() -> acoes.publicarNaInternet(lab, "demo", null));
        Visual.naEdt(() -> acoes.removerEnderecoDaInternet(lab, "lab-demo.example.com", null));
        assertEquals(dialogosAntes, dialogos.mostrados.size(), "nenhum diálogo abriu");
        assertEquals(2, avisos.todos.stream().filter(a -> a.tipo().equals("atencao") && a.texto().contains("desligadas")).count(), avisos.textos().toString());
        assertEquals(List.of(), c.cf.pedidos, "nem uma chamada à Cloudflare");
        acoes.usarTrava(() -> true);
        Visual.naEdt(() -> acoes.removerEnderecoDaInternet(lab, "lab-demo.example.com", null));
        assertEquals(dialogosAntes + 1, dialogos.mostrados.size(), "com a trava liberada o diálogo abre");
    }

    @Test
    void semAsAcoesLiberadasORemoverApareceDesligadoComAExplicacaoENaoSome() throws Exception {
        c.conectarPorArquivo();
        publicarDemo();
        PaginaCloudflare p = paginaDoServidor(false);
        recarregar(p);
        assertEquals(List.of("lab-demo.example.com"), ed(p::enderecosMostrados));
        assertEquals(1, ed(() -> p.botoesDeRemover().size()), "o botão não some");
        assertFalse(ed(() -> p.botoesDeRemover().get(0).isEnabled()), "mas fica desligado");
        assertEquals("As ações de escrita estão desligadas nesta versão do app.", ed(() -> p.botoesDeRemover().get(0).getToolTipText()));
        assertEquals(1, ed(() -> p.botoesDeAbrir().size()), "ver e abrir continuam");
        assertFalse(ed(() -> p.botaoAdicionarDominio().isEnabled()), "Adicionar domínio também aparece desligado");
        assertTrue(CenarioDeCloudflare.textos(p).contains("Remover endereços fica desligado"));
    }

    @Test
    void conferirOTokenDizSeAindaValeENuncaMostraOToken() throws Exception {
        c.conectarPorArquivo();
        PaginaCloudflare p = paginaDoServidor(true);
        recarregar(p);
        Visual.naEdt(() -> p.botaoConferirToken().doClick());
        Espera.ate(PRAZO, () -> "resultado: " + ed(p::textoDoResultadoDoToken), () -> ed(p::textoDoResultadoDoToken).contains("ativo"));
        c.cf.tokenAceito = "OutroTokenQualquerDeTeste0123456789abcd";
        Visual.naEdt(() -> p.botaoConferirToken().doClick());
        Espera.ate(PRAZO, () -> "resultado: " + ed(p::textoDoResultadoDoToken), () -> ed(p::textoDoResultadoDoToken).contains("não aceitou"));
        assertFalse(CenarioDeCloudflare.textos(p).contains(CloudflareFalsa.TOKEN_CERTO));
        fotografar(p, "pagina-cloudflare-4-token-recusado");
    }

    @Test
    void erroDeLeituraApareceEmPortuguesSemOTokenENaoEsvaziaAFicha() throws Exception {
        c.conectarPorArquivo();
        c.cf.falhar("GET /zones/.*/dns_records", 500, "{\"success\":false,\"errors\":[{\"code\":1,\"message\":\"erro interno " + CloudflareFalsa.TOKEN_CERTO + "\"}]}", 5);
        PaginaCloudflare p = paginaDoServidor(true);
        Visual.naEdt(p::recarregar);
        Espera.ate(PRAZO, () -> "erro: " + ed(p::textoDoErroDosEnderecos), () -> !ed(p::textoDoErroDosEnderecos).isEmpty());
        assertFalse(ed(p::textoDoErroDosEnderecos).contains(CloudflareFalsa.TOKEN_CERTO), ed(p::textoDoErroDosEnderecos));
        assertEquals("CONECTADA", ed(p::textoDoSelo), "a conta continua conectada: só a leitura falhou");
        fotografar(p, "pagina-cloudflare-5-erro-de-leitura");
    }

    @Test
    void desconectarPerguntaEDepoisApagaOTokenDoCofreEVoltaAoEstadoDeNaoConectada() throws Exception {
        c.conectarPorArquivo();
        PaginaCloudflare p = paginaDoServidor(true);
        recarregar(p);
        Visual.naEdt(() -> p.botaoDesconectar().doClick());
        DialogoConfirmar conf = dialogos.ultimoComo(DialogoConfirmar.class);
        assertTrue(c.cofre.tokenCloudflare(c.servidorId).isPresent(), "perguntou antes: nada foi apagado");
        Visual.naEdt(() -> conf.botaoConfirmar().doClick());
        Espera.ate(PRAZO, () -> "avisos " + avisos.textos(), () -> avisos.disse("ok", "Cloudflare desconectada"));
        Espera.ate(PRAZO, "voltou ao não conectada", () -> ed(p::modo) == PaginaCloudflare.Modo.NAO_CONECTADO);
        assertTrue(c.cofre.tokenCloudflare(c.servidorId).isEmpty());
        assertTrue(c.cofre.configuracaoCloudflare(c.servidorId).isEmpty());
        assertEquals(List.of(), c.cf.escritas(), "desconectar não mexe na Cloudflare");
    }

    @Test
    void esteComputadorExplicaQueNaoHaTunel() {
        lab.definirCloudflare(c.novoServico(true));
        PaginaCloudflare p = paginaDoServidor(true);
        recarregar(p);
        assertEquals(PaginaCloudflare.Modo.LOCAL, ed(p::modo));
        assertEquals("SEM TÚNEL", ed(p::textoDoSelo));
        assertTrue(CenarioDeCloudflare.textos(p).contains("Este computador"), CenarioDeCloudflare.textos(p));
        fotografar(p, "pagina-cloudflare-6-este-computador");
    }

    // =====================================================================================================
    // A aba Internet do projeto
    // =====================================================================================================

    /** Os pedidos que a aba fez à janela. */
    static final class SaidaDaInternet implements TelaProjeto.Saida {
        final List<String> pedidos = new CopyOnWriteArrayList<>();

        @Override
        public void voltar() { }

        @Override
        public void projetoSumiu() { }

        @Override
        public void abaMudou(TelaProjeto.Aba aba) { }

        @Override
        public void publicarNaInternet(Runnable depois) {
            pedidos.add("publicar");
        }

        @Override
        public void conectarCloudflare(Runnable depois) {
            pedidos.add("conectar");
        }

        @Override
        public void removerEndereco(String hostname, Runnable depois) {
            pedidos.add("remover:" + hostname);
        }

        @Override
        public void abrirEndereco(String hostname) {
            pedidos.add("abrir:" + hostname);
        }
    }

    private TelaProjeto telaDoProjeto(boolean liberadas, SaidaDaInternet saida) {
        return Visual.naEdt(() -> {
            FluxosDeProjeto fluxos = new FluxosDeProjeto(null, avisos, dialogos, liberadas);
            TelaProjeto t = new TelaProjeto(lab, "demo", fluxos, navegador, avisos, TelaProjeto.Aba.INTERNET, saida, () -> null, () -> { },
                new br.com.labdesktop.ui.componentes.CopiadorDeTexto(() -> new java.awt.datatransfer.Clipboard("teste")));
            return t;
        });
    }

    private void recarregar(TelaProjeto t) {
        PaginaInternet p = t.internet();
        Visual.naEdt(p::recarregar);
        Espera.ate(PRAZO, () -> "carregou? modo=" + ed(p::modo) + " erro=" + ed(p::textoDoErro), () -> ed(p::carregou) && !ed(p::textoDoSelo).isEmpty());
        Espera.ate(PRAZO, "terminou de ler", () -> !ed(p::textos).contains("Lendo da Cloudflare") && !ed(p::textos).contains("Atualizando"));
    }

    private static void fotografar(TelaProjeto t, String nome) {
        for (Tema tema : List.of(Tema.CLARO, Tema.ESCURO)) {
            Visual.tema(tema);
            Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(t));
            Visual.salvar(Visual.renderizar(t, 1100, 700), nome + "-" + (tema == Tema.CLARO ? "claro" : "escuro"));
        }
        Visual.tema(Tema.CLARO);
        Visual.naEdt(() -> javax.swing.SwingUtilities.updateComponentTreeUI(t));
    }

    @Test
    void abaInternetExisteAoLadoDasOutrasENaoDependeDoDetalheDoProjeto() {
        TelaProjeto t = telaDoProjeto(true, new SaidaDaInternet());
        assertEquals("Internet", ed(() -> t.abas().botao("internet").getText()));
        assertTrue(ed(() -> t.internet().independeDoDetalhe()));
        assertFalse(ed(() -> t.internet().mostraEsqueleto()), "sem detalhe, sem esqueleto: a aba fala só com o cofre e a Cloudflare");
        assertTrue(ed(() -> t.internet().mostraCorpo()));
    }

    @Test
    void naoConectadaOferecoConectarQueChamaAJanela() {
        SaidaDaInternet saida = new SaidaDaInternet();
        TelaProjeto t = telaDoProjeto(true, saida);
        recarregar(t);
        PaginaInternet p = t.internet();
        assertEquals(PaginaInternet.Modo.NAO_CONECTADO, ed(p::modo));
        assertEquals("NÃO CONECTADA", ed(p::textoDoSelo));
        assertTrue(ed(p::textos).contains("conecte a sua conta da Cloudflare"), ed(p::textos));
        assertFalse(ed(() -> (p.componentes().contains(p.botaoPublicar()) && p.aparece(p.botaoPublicar()))), "sem conta, não há o que publicar");
        Visual.naEdt(() -> p.botaoConectar().doClick());
        assertEquals(List.of("conectar"), saida.pedidos);
        fotografar(t, "aba-internet-1-nao-conectada");
    }

    @Test
    void conectadaListaSoOsEnderecosDoProjetoEPublicarChamaAJanela() throws Exception {
        c.conectarPorArquivo();
        publicarDemo();
        c.cf.semear("lab-outro.example.com", "CNAME", CloudflareFalsa.TUNEL_ARQUIVO + ".cfargotunnel.com", "lab-desktop: outro");
        c.cf.pedidos.clear();
        SaidaDaInternet saida = new SaidaDaInternet();
        TelaProjeto t = telaDoProjeto(true, saida);
        recarregar(t);
        PaginaInternet p = t.internet();
        assertEquals("CONECTADA", ed(p::textoDoSelo));
        assertEquals(List.of("lab-demo.example.com"), ed(p::enderecosMostrados), "só os endereços DESTE projeto");
        assertTrue(ed(p::textos).contains("Domínio example.com"), ed(p::textos));
        assertTrue(ed(() -> (p.componentes().contains(p.botaoPublicar()) && p.aparece(p.botaoPublicar()))));
        assertEquals(List.of(), c.cf.escritas());
        fotografar(t, "aba-internet-2-conectada-com-endereco");
        Visual.naEdt(() -> p.botaoPublicar().doClick());
        Visual.naEdt(() -> p.botoesDeAbrir().get(0).doClick());
        Visual.naEdt(() -> p.botoesDeRemover().get(0).doClick());
        assertEquals(List.of("publicar", "abrir:lab-demo.example.com", "remover:lab-demo.example.com"), saida.pedidos);
    }

    @Test
    void semEnderecoDoProjetoDizQueAindaNaoTemEMostraPublicar() throws Exception {
        c.conectarPorArquivo();
        TelaProjeto t = telaDoProjeto(true, new SaidaDaInternet());
        recarregar(t);
        PaginaInternet p = t.internet();
        assertTrue(ed(p::textos).contains("ainda não tem endereço público"), ed(p::textos));
        assertTrue(ed(() -> (p.componentes().contains(p.botaoPublicar()) && p.aparece(p.botaoPublicar()))));
        fotografar(t, "aba-internet-3-conectada-sem-endereco");
    }

    @Test
    void semAsAcoesLiberadasPublicarAdicionarERemoverApareceremDesligados() throws Exception {
        c.conectarPorArquivo();
        publicarDemo();
        TelaProjeto t = telaDoProjeto(false, new SaidaDaInternet());
        recarregar(t);
        PaginaInternet p = t.internet();
        assertTrue(ed(() -> (p.componentes().contains(p.botaoPublicar()) && p.aparece(p.botaoPublicar()))), "o botão não some");
        assertFalse(ed(() -> p.botaoPublicar().isEnabled()));
        assertFalse(ed(() -> p.botaoAdicionarDominio().isEnabled()));
        assertEquals("As ações de escrita estão desligadas nesta versão do app.", ed(() -> p.botaoPublicar().getToolTipText()));
        assertEquals(1, ed(() -> p.botoesDeRemover().size()));
        assertFalse(ed(() -> p.botoesDeRemover().get(0).isEnabled()));
        assertEquals(List.of("lab-demo.example.com"), ed(p::enderecosMostrados), "ver continua");
        assertTrue(ed(p::textos).contains("ficam desligados"), ed(p::textos));
    }

    @Test
    void esteComputadorExplicaQueNaoHaTunelNaAbaDoProjeto() {
        lab.definirCloudflare(c.novoServico(true));
        TelaProjeto t = telaDoProjeto(true, new SaidaDaInternet());
        recarregar(t);
        PaginaInternet p = t.internet();
        assertEquals(PaginaInternet.Modo.LOCAL, ed(p::modo));
        assertEquals("SEM TÚNEL", ed(p::textoDoSelo));
        assertFalse(ed(() -> (p.componentes().contains(p.botaoPublicar()) && p.aparece(p.botaoPublicar()))));
        fotografar(t, "aba-internet-4-este-computador");
    }

    @Test
    void erroDeLeituraNaAbaDoProjetoApareceSemOToken() throws Exception {
        c.conectarPorArquivo();
        c.cf.falhar("GET /zones/.*/dns_records", 500, "{\"success\":false,\"errors\":[{\"code\":1,\"message\":\"erro " + CloudflareFalsa.TOKEN_CERTO + "\"}]}", 5);
        TelaProjeto t = telaDoProjeto(true, new SaidaDaInternet());
        PaginaInternet p = t.internet();
        Visual.naEdt(p::recarregar);
        Espera.ate(PRAZO, () -> "erro: " + ed(p::textoDoErro), () -> !ed(p::textoDoErro).isEmpty());
        assertFalse(ed(p::textoDoErro).contains(CloudflareFalsa.TOKEN_CERTO));
        assertFalse(ed(p::textos).contains(CloudflareFalsa.TOKEN_CERTO));
    }

    @Test
    void mudarDeAbaDescartaALeituraQueEstavaEmVoo() throws Exception {
        c.conectarPorArquivo();
        publicarDemo();
        TelaProjeto t = telaDoProjeto(true, new SaidaDaInternet());
        PaginaInternet p = t.internet();
        Visual.naEdt(p::recarregar);
        Visual.naEdt(p::aoSumir);
        Espera.dormir(400);
        assertTrue(ed(() -> p.enderecosMostrados().isEmpty()), "a leitura de antes de sair foi descartada: " + ed(p::enderecosMostrados));
    }
}
