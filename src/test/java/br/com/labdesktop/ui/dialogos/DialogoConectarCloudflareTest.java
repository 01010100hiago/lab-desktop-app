package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.cloudflare.ErroCloudflare;
import br.com.labdesktop.cloudflare.LabSimulado;
import br.com.labdesktop.cloudflare.PublicadorPorArquivoTestApoio;
import br.com.labdesktop.cloudflare.ServicoCloudflare;
import br.com.labdesktop.cloudflare.TunelCf;
import br.com.labdesktop.cloudflare.TunelPorArquivo;
import br.com.labdesktop.cofre.CofreException;
import br.com.labdesktop.cofre.ConfiguracaoCloudflare;
import br.com.labdesktop.seguranca.ListaPermitidaDeUrls;
import br.com.labdesktop.teste.CloudflareFalsa;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.CenarioDeCloudflare;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** "Conectar a Cloudflare": o passo a passo do token, a verificação, a escolha (domínio, túnel por API ou por arquivo), o cofre e o token que nunca fica à mostra. PNGs nos dois temas. */
@ExtendWith(VerificaEdt.class)
class DialogoConectarCloudflareTest {

    private static final Duration PRAZO = Duration.ofSeconds(25);

    @TempDir
    Path tmp;

    CenarioDeCloudflare c;
    final AvisosGravados avisos = new AvisosGravados();
    final List<String> paginasAbertas = new CopyOnWriteArrayList<>();
    final List<ServicoCloudflare.Descoberta> descobertas = new CopyOnWriteArrayList<>();
    final AtomicInteger conectados = new AtomicInteger();
    final Clipboard area = new Clipboard("teste");
    volatile boolean semListarTuneis;
    volatile java.util.concurrent.CountDownLatch segurarSalvar;

    @BeforeEach
    void montar() throws Exception {
        Visual.tema(Tema.CLARO);
        c = new CenarioDeCloudflare(tmp);
        c.prepararOServidorDoDono(PublicadorPorArquivoTestApoio.COMPOSE, PublicadorPorArquivoTestApoio.CONFIG);
    }

    @AfterEach
    void desmontar() {
        c.close();
        Visual.tema(Tema.CLARO);
    }

    private DialogoConectarCloudflare.Motor motor() {
        ServicoCloudflare s = c.servico;
        return new DialogoConectarCloudflare.Motor() {
            @Override
            public Optional<ConfiguracaoCloudflare> escolhaAtual() {
                return s.escolha();
            }

            @Override
            public ServicoCloudflare.Descoberta descobrir(char[] token) throws ErroCloudflare {
                ServicoCloudflare.Descoberta d = s.descobrir(token);
                descobertas.add(d);
                return d;
            }

            @Override
            public List<TunelCf> tuneis(ServicoCloudflare.Descoberta d, String contaId) throws ErroCloudflare {
                if (semListarTuneis) {
                    throw new ErroCloudflare(ErroCloudflare.Tipo.SEM_PERMISSAO, "sem permissão");
                }
                return s.tuneisDaConta(d, contaId);
            }

            @Override
            public TunelPorArquivo.Conferencia conferirServidor(String caminho, String container) {
                return s.conferirServidor(caminho, container);
            }

            @Override
            public void salvar(ServicoCloudflare.Descoberta d, ConfiguracaoCloudflare escolha) throws CofreException {
                java.util.concurrent.CountDownLatch trava = segurarSalvar;
                if (trava != null) {
                    try {
                        trava.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                s.salvar(d, escolha);
            }

            @Override
            public void abrirPagina(String url) {
                paginasAbertas.add(url);
            }
        };
    }

    private DialogoConectarCloudflare abrir() {
        return Visual.naEdt(() -> new DialogoConectarCloudflare(motor(), avisos, new CopiadorDeTexto(() -> area), conectados::incrementAndGet));
    }

    private static <T> T ed(java.util.function.Supplier<T> s) {
        return Visual.naEdt(s);
    }

    private void colar(DialogoConectarCloudflare d, String token) {
        Visual.naEdt(() -> d.campoDoToken().setText(token));
    }

    private void verificarComOTokenCerto(DialogoConectarCloudflare d) {
        colar(d, CloudflareFalsa.TOKEN_CERTO);
        Visual.naEdt(() -> d.botaoPrincipal().doClick());
        Espera.ate(PRAZO, () -> "página: " + ed(d::paginaAtual) + " erro=" + ed(d::textoDoErroDoToken), () -> ed(d::paginaAtual) == DialogoConectarCloudflare.Pagina.ESCOLHA);
        Espera.ate(PRAZO, () -> "túneis: " + ed(d::itensDeTunel), () -> ed(d::itensDeTunel).size() >= 2);
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    void aPrimeiraPaginaEnsinaAsPermissoesMinimasAbreAPaginaOficialEAvisaDoToken() {
        DialogoConectarCloudflare d = abrir();
        assertEquals(DialogoConectarCloudflare.Pagina.TOKEN, ed(d::paginaAtual));
        assertEquals("Conectar a Cloudflare", d.titulo());
        assertFalse(ed(() -> d.botaoPrincipal().isEnabled()), "sem token digitado, não verifica");
        String t = CenarioDeCloudflare.textos(d.raiz());
        assertTrue(t.contains("Zona > DNS > Editar"), t);
        assertTrue(t.contains("Zona > Zona > Ler"), t);
        assertTrue(t.contains("Cloudflare Tunnel > Editar"), t);
        assertTrue(t.contains("Zona específica"), "a permissão só na zona escolhida: " + t);
        assertTrue(t.contains("vale como uma senha do DNS"), "o aviso: " + t);
        assertTrue(t.contains("nunca vai ao servidor"), t);
        assertTrue(t.contains("Meu perfil > Tokens de API"), t);
        assertTrue(t.contains("cloudflared login"), "diz que não usa cloudflared login: " + t);
        Visual.naEdt(() -> d.botaoAbrirPaginaDosTokens().doClick());
        assertEquals(List.of(ListaPermitidaDeUrls.PAGINA_DOS_TOKENS), paginasAbertas);
        assertTrue(ListaPermitidaDeUrls.permitida(ListaPermitidaDeUrls.Tipo.PAGINA_DA_CLOUDFLARE, paginasAbertas.get(0)), "e é uma página da lista de permitidas");
        CenarioDeCloudflare.fotografar(d, "dialogo-cloudflare-1-token");
    }

    @Test
    void osDoisBlocosDePermissoesTemCadaUmSeuBotaoDeCopiarEOBlocoDoArquivoNaoPedeEditarTunel() throws Exception {
        DialogoConectarCloudflare d = abrir();
        javax.swing.AbstractButton doArquivo = achar(d.raiz(), "Copiar as permissões (túnel por arquivo)");
        Visual.naEdt(() -> doArquivo.doClick());
        String copiado = (String) area.getContents(null).getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor);
        assertEquals(String.join("\n", DialogoConectarCloudflare.PERMISSOES_TUNEL_POR_ARQUIVO), copiado);
        assertFalse(copiado.contains("Tunnel") || copiado.contains("Túnel"), "túnel por arquivo: nada de editar túnel (apagaria túneis da conta): " + copiado);
        assertTrue(copiado.contains("DNS > Editar") && copiado.contains("Zona > Ler"), copiado);
        javax.swing.AbstractButton daApi = achar(d.raiz(), "Copiar as permissões (túnel pela API)");
        Visual.naEdt(() -> daApi.doClick());
        String daApiTexto = (String) area.getContents(null).getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor);
        assertEquals(String.join("\n", DialogoConectarCloudflare.PERMISSOES_TUNEL_PELA_API), daApiTexto);
        assertTrue(daApiTexto.contains("Cloudflare Tunnel > Editar") && daApiTexto.contains("Recursos da conta"), daApiTexto);
        String t = CenarioDeCloudflare.textos(d.raiz());
        assertTrue(t.contains("Recursos da zona") && t.contains("Recursos da conta"), "a dica diz onde escolher a zona e a conta");
    }

    @Test
    void fecharODialogoNoMeioDaGravacaoNaoZeraOTokenAntesDeGravar() throws Exception {
        java.util.concurrent.CountDownLatch segurar = new java.util.concurrent.CountDownLatch(1);
        segurarSalvar = segurar;
        DialogoConectarCloudflare d = abrir();
        verificarComOTokenCerto(d);
        Visual.naEdt(() -> d.escolherOTunelNumero(1));
        Visual.naEdt(() -> d.botaoPrincipal().doClick()); // começa a gravar (a gravação fica parada na trava do teste)
        Espera.ate(PRAZO, "gravação em andamento", () -> ed(d::ocupado));
        Visual.naEdt(() -> d.fechar());                    // o usuário fecha no meio da gravação
        segurar.countDown();
        Espera.ate(PRAZO, "conectou mesmo com o diálogo já fechado", () -> conectados.get() == 1);
        assertTrue(c.cofre.tokenCloudflare(c.servidorId).isPresent(), "o token chegou inteiro ao cofre (não foi zerado pelo fechar)");
        assertEquals("Bearer " + CloudflareFalsa.TOKEN_CERTO, c.cofre.tokenCloudflare(c.servidorId).orElseThrow().cabecalhoAuthorization());
        assertTrue(avisos.disse("ok", "Cloudflare conectada"), avisos.todos.toString());
    }

    @Test
    void apagarSenhaSobrescreveOBufferAntesDeTirarOTexto() {
        java.util.List<String> trocas = new java.util.ArrayList<>();
        javax.swing.JPasswordField campo = ed(() -> {
            javax.swing.JPasswordField f = new javax.swing.JPasswordField();
            f.setDocument(new javax.swing.text.PlainDocument() {
                @Override
                public void replace(int offset, int length, String text, javax.swing.text.AttributeSet attrs) throws javax.swing.text.BadLocationException {
                    trocas.add(text);
                    super.replace(offset, length, text, attrs);
                }
            });
            f.setText("segredo123");
            return f;
        });
        trocas.clear();
        Visual.naEdt(() -> br.com.labdesktop.ui.componentes.Campos.apagarSenha(campo));
        assertEquals(List.of("\0".repeat(10)), trocas, "primeiro troca tudo por \\0 do mesmo tamanho");
        assertEquals(0, ed(() -> campo.getPassword().length));
    }

    private static javax.swing.AbstractButton achar(java.awt.Component raiz, String texto) {
        java.util.ArrayDeque<java.awt.Component> fila = new java.util.ArrayDeque<>(List.of(raiz));
        while (!fila.isEmpty()) {
            java.awt.Component x = fila.poll();
            if (x instanceof javax.swing.AbstractButton b && texto.equals(b.getText())) {
                return b;
            }
            if (x instanceof java.awt.Container k) {
                fila.addAll(List.of(k.getComponents()));
            }
        }
        throw new AssertionError("não achei o botão " + texto);
    }

    @Test
    void tokenComFormaErradaOuRecusadoMostraAFraseENaoSai() {
        DialogoConectarCloudflare d = abrir();
        colar(d, "curto");
        assertTrue(ed(() -> d.botaoPrincipal().isEnabled()));
        Visual.naEdt(() -> d.botaoPrincipal().doClick());
        Espera.ate(PRAZO, "o erro", () -> !ed(d::textoDoErroDoToken).isEmpty());
        assertTrue(ed(d::textoDoErroDoToken).contains("não parece um token"), ed(d::textoDoErroDoToken));
        assertEquals(DialogoConectarCloudflare.Pagina.TOKEN, ed(d::paginaAtual));
        assertEquals(List.of(), c.cf.pedidos, "um token com forma errada nem chega à Cloudflare");

        c.cf.tokenAceito = "OutroTokenQualquerDeTeste0123456789abcd";
        colar(d, CloudflareFalsa.TOKEN_CERTO);
        assertEquals("", ed(d::textoDoErroDoToken), "mexer no campo limpa o erro de antes");
        Visual.naEdt(() -> d.botaoPrincipal().doClick());
        Espera.ate(PRAZO, "o erro de token", () -> ed(d::textoDoErroDoToken).contains("não aceitou o token"));
        assertFalse(ed(d::textoDoErroDoToken).contains(CloudflareFalsa.TOKEN_CERTO));
        assertTrue(ed(() -> d.botaoPrincipal().isEnabled()), "pode tentar de novo");
        CenarioDeCloudflare.fotografar(d, "dialogo-cloudflare-1-token-recusado");
    }

    @Test
    void verificarBemLimpaOCampoZeraOTokenDigitadoEMostraOsDominiosENuncaOToken() {
        DialogoConectarCloudflare d = abrir();
        verificarComOTokenCerto(d);
        assertEquals(0, ed(() -> d.campoDoToken().getPassword().length), "o campo do token foi limpo");
        assertEquals(List.of("example.com"), ed(() -> {
            List<String> l = new ArrayList<>();
            for (int i = 0; i < d.caixaDeZonas().getItemCount(); i++) {
                l.add(d.caixaDeZonas().getItemAt(i).nome());
            }
            return l;
        }));
        assertEquals("Conta: Conta de Teste", ed(d::textoDaConta));
        assertEquals(3, ed(d::itensDeTunel).size(), "os dois túneis e o 'outro'");
        assertTrue(ed(d::itensDeTunel).get(0).contains("lab") && ed(d::itensDeTunel).get(0).contains("gerido por arquivo no servidor"), ed(d::itensDeTunel).toString());
        assertTrue(ed(d::itensDeTunel).get(1).contains("gerido pela Cloudflare"));
        assertFalse(CenarioDeCloudflare.textos(d.raiz()).contains(CloudflareFalsa.TOKEN_CERTO), "o token nunca aparece na tela");
        assertEquals(1, descobertas.size());
        assertFalse(descobertas.get(0).toString().contains(CloudflareFalsa.TOKEN_CERTO));
        assertTrue(c.cofre.tokenCloudflare(c.servidorId).isEmpty(), "verificar não salva: só o 'Salvar e conectar'");
    }

    @Test
    void tunelGeridoPelaApiSalvaNoCofreEFechaSemPedirArquivoNemContainer() {
        DialogoConectarCloudflare d = abrir();
        verificarComOTokenCerto(d);
        Visual.naEdt(() -> d.escolherOTunelNumero(1));
        assertFalse(ed(d::blocoDoArquivoVisivel), "pela API não há arquivo nem container");
        assertTrue(ed(d::notaDaApiVisivel));
        assertTrue(ed(() -> d.botaoPrincipal().isEnabled()));
        CenarioDeCloudflare.fotografar(d, "dialogo-cloudflare-2-escolha-api");
        Visual.naEdt(() -> d.campoPrefixo().setText("site"));
        Visual.naEdt(() -> d.botaoPrincipal().doClick());
        Espera.ate(PRAZO, "conectou", () -> conectados.get() == 1);
        assertTrue(ed(d::fechou));
        ConfiguracaoCloudflare salva = c.cofre.configuracaoCloudflare(c.servidorId).orElseThrow();
        assertEquals(ConfiguracaoCloudflare.Tipo.API, salva.tipo());
        assertEquals(CloudflareFalsa.TUNEL_API, salva.tunelId());
        assertEquals("example.com", salva.zonaNome());
        assertEquals("site", salva.prefixo());
        assertEquals("Bearer " + CloudflareFalsa.TOKEN_CERTO, c.cofre.tokenCloudflare(c.servidorId).orElseThrow().cabecalhoAuthorization(), "o token está no cofre");
        assertTrue(avisos.disse("ok", "Cloudflare conectada"), avisos.todos.toString());
        assertTrue(descobertas.get(0).toString().contains("token=redigido"));
        assertTrue(c.cofre.tokenCloudflare(c.servidorId).orElseThrow().tamanho() > 0 && !c.cofre.tokenCloudflare(c.servidorId).orElseThrow().estaZerado(), "fechar o diálogo não zera o do cofre");
    }

    @Test
    void tunelPorArquivoPedeAConferenciaDoServidorAntesDeSalvar() {
        DialogoConectarCloudflare d = abrir();
        verificarComOTokenCerto(d);
        assertTrue(ed(d::blocoDoArquivoVisivel), "o primeiro túnel (lab) é por arquivo");
        assertEquals("/srv/lab/infra/cloudflared/config.yml", ed(() -> d.campoCaminho().getText()));
        assertEquals("cloudflared", ed(() -> d.campoContainer().getText()));
        assertFalse(ed(() -> d.botaoPrincipal().isEnabled()), "sem conferir o servidor, não salva");
        assertTrue(ed(d::textoDoErroDaEscolha).contains("Conferir no servidor"), ed(d::textoDoErroDaEscolha));
        CenarioDeCloudflare.fotografar(d, "dialogo-cloudflare-2-escolha-arquivo");
        Visual.naEdt(() -> d.botaoConferirNoServidor().doClick());
        Espera.ate(PRAZO, () -> "conferência: " + ed(d::textoDaConferencia), () -> ed(d::textoDaConferencia).startsWith("Tudo certo"));
        assertTrue(ed(d::textoDaConferencia).contains("túnel " + LabSimulado.UUID), ed(d::textoDaConferencia));
        assertTrue(ed(d::textoDaConferencia).contains("enxerga o arquivo em /etc/cloudflared/config.yml"), ed(d::textoDaConferencia));
        assertTrue(ed(() -> d.botaoPrincipal().isEnabled()));
        CenarioDeCloudflare.fotografar(d, "dialogo-cloudflare-2-escolha-arquivo-conferido");
        assertEquals(List.of("docker inspect cloudflared"), c.lab.comandos, "a conferência é só LEITURA");
        Visual.naEdt(() -> d.botaoPrincipal().doClick());
        Espera.ate(PRAZO, "conectou", () -> conectados.get() == 1);
        ConfiguracaoCloudflare salva = c.cofre.configuracaoCloudflare(c.servidorId).orElseThrow();
        assertEquals(ConfiguracaoCloudflare.Tipo.ARQUIVO, salva.tipo());
        assertEquals(LabSimulado.CONFIG, salva.caminhoDoConfig());
        assertEquals("cloudflared", salva.containerDoCloudflared());
        assertEquals(null, salva.caminhoNoContainer(), "vazio: o app descobre pelo docker inspect na hora de publicar");
        assertEquals(LabSimulado.UUID, salva.tunelId());
    }

    @Test
    void mudarOCaminhoDepoisDeConferirExigeConferirDeNovo() {
        DialogoConectarCloudflare d = abrir();
        verificarComOTokenCerto(d);
        Visual.naEdt(() -> d.botaoConferirNoServidor().doClick());
        Espera.ate(PRAZO, "conferiu", () -> ed(d::textoDaConferencia).startsWith("Tudo certo"));
        assertTrue(ed(() -> d.botaoPrincipal().isEnabled()));
        Visual.naEdt(() -> d.campoContainer().setText("outro-container"));
        assertFalse(ed(() -> d.botaoPrincipal().isEnabled()), "o que foi conferido era de outro container");
        assertEquals("", ed(d::textoDaConferencia));
    }

    @Test
    void conferenciaComProblemaMostraEmVermelhoENaoDeixaSalvar() {
        c.lab.containerExiste = false;
        DialogoConectarCloudflare d = abrir();
        verificarComOTokenCerto(d);
        Visual.naEdt(() -> d.botaoConferirNoServidor().doClick());
        Espera.ate(PRAZO, "problema", () -> ed(d::textoDaConferencia).contains("não existe no servidor"));
        assertFalse(ed(() -> d.botaoPrincipal().isEnabled()));
        CenarioDeCloudflare.fotografar(d, "dialogo-cloudflare-2-conferencia-com-problema");
        Visual.naEdt(() -> d.campoCaminho().setText("/etc/../x"));
        Visual.naEdt(() -> d.botaoConferirNoServidor().doClick());
        assertTrue(ed(d::textoDaConferencia).contains("não valem"), "caminho que não passa na validação nem sai: " + ed(d::textoDaConferencia));
    }

    @Test
    void oArquivoDeOutroTunelEBarrado() throws Exception {
        c.lab.escrever(LabSimulado.CONFIG, PublicadorPorArquivoTestApoio.CONFIG.replace(LabSimulado.UUID, "99999999-9999-9999-9999-999999999999"));
        DialogoConectarCloudflare d = abrir();
        verificarComOTokenCerto(d);
        Visual.naEdt(() -> d.botaoConferirNoServidor().doClick());
        Espera.ate(PRAZO, "problema", () -> ed(d::textoDaConferencia).contains("é do túnel 99999999-9999-9999-9999-999999999999"));
        assertFalse(ed(() -> d.botaoPrincipal().isEnabled()));
    }

    @Test
    void semPermissaoParaListarTunelesOUuidManualFuncionaEOArquivoPreencheOUuid() {
        semListarTuneis = true;
        DialogoConectarCloudflare d = abrir();
        colar(d, CloudflareFalsa.TOKEN_CERTO);
        Visual.naEdt(() -> d.botaoPrincipal().doClick());
        Espera.ate(PRAZO, () -> "página " + ed(d::paginaAtual), () -> ed(d::paginaAtual) == DialogoConectarCloudflare.Pagina.ESCOLHA);
        Espera.ate(PRAZO, () -> "situação: " + ed(d::textoDosTuneis), () -> ed(d::textoDosTuneis).contains("não lê a lista de túneis"));
        assertEquals(1, ed(d::itensDeTunel).size(), "só o 'outro túnel'");
        assertTrue(ed(d::blocoDoUuidVisivel));
        assertTrue(ed(d::blocoDoArquivoVisivel), "pelo UUID o túnel é gerido por arquivo");
        assertFalse(ed(() -> d.botaoPrincipal().isEnabled()));
        assertTrue(ed(d::textoDoErroDaEscolha).contains("UUID do túnel"), ed(d::textoDoErroDaEscolha));
        CenarioDeCloudflare.fotografar(d, "dialogo-cloudflare-2-escolha-uuid-manual");
        Visual.naEdt(() -> d.botaoConferirNoServidor().doClick());
        Espera.ate(PRAZO, () -> "conferência: " + ed(d::textoDaConferencia), () -> ed(d::textoDaConferencia).startsWith("Tudo certo"));
        assertEquals(LabSimulado.UUID, ed(() -> d.campoUuid().getText()), "o arquivo diz qual é o túnel: o campo foi preenchido");
        assertTrue(ed(() -> d.botaoPrincipal().isEnabled()));
        Visual.naEdt(() -> d.botaoPrincipal().doClick());
        Espera.ate(PRAZO, "conectou", () -> conectados.get() == 1);
        assertEquals(LabSimulado.UUID, c.cofre.configuracaoCloudflare(c.servidorId).orElseThrow().tunelId());
    }

    @Test
    void prefixoInvalidoENaoSalvaEOUuidMalFormadoTambem() {
        DialogoConectarCloudflare d = abrir();
        verificarComOTokenCerto(d);
        Visual.naEdt(() -> d.escolherOTunelNumero(1));
        Visual.naEdt(() -> d.campoPrefixo().setText("Lab Ruim"));
        assertFalse(ed(() -> d.botaoPrincipal().isEnabled()));
        assertTrue(ed(d::textoDoErroDaEscolha).contains("letras minúsculas"), ed(d::textoDoErroDaEscolha));
        Visual.naEdt(() -> d.campoPrefixo().setText("lab"));
        assertTrue(ed(() -> d.botaoPrincipal().isEnabled()));
        Visual.naEdt(() -> d.escolherOTunelNumero(2)); // "outro"
        Visual.naEdt(() -> d.campoUuid().setText("meu-tunel"));
        assertFalse(ed(() -> d.botaoPrincipal().isEnabled()), "o túnel é sempre o UUID, nunca o nome");
    }

    @Test
    void cancelarOuEscFechaEZeraTudoOQueOTokenDeixouNoDialogo() {
        DialogoConectarCloudflare d = abrir();
        verificarComOTokenCerto(d);
        ServicoCloudflare.Descoberta guardada = descobertas.get(0);
        Visual.naEdt(() -> d.botaoCancelar().doClick());
        assertTrue(ed(d::fechou));
        assertTrue(c.cofre.tokenCloudflare(c.servidorId).isEmpty(), "cancelar não guarda nada");
        assertEquals(0, conectados.get());
        // a cópia que a descoberta guardava foi zerada: salvar com ela agora não grava token zerado
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> c.servico.salvar(guardada, new ConfiguracaoCloudflare("a".repeat(32), "c", "b".repeat(32), "example.com",
            CloudflareFalsa.TUNEL_API, "t", ConfiguracaoCloudflare.Tipo.API, "lab", null, null, null)));
    }

    @Test
    void trocarAContaAbreComOQueJaEstavaGuardado() throws Exception {
        DialogoConectarCloudflare primeiro = abrir();
        verificarComOTokenCerto(primeiro);
        Visual.naEdt(() -> primeiro.escolherOTunelNumero(1));
        Visual.naEdt(() -> primeiro.campoPrefixo().setText("site"));
        Visual.naEdt(() -> primeiro.botaoPrincipal().doClick());
        Espera.ate(PRAZO, "conectou", () -> conectados.get() == 1);
        DialogoConectarCloudflare d = abrir();
        assertEquals("Trocar a conta da Cloudflare", d.titulo());
        assertEquals("site", ed(() -> d.campoPrefixo().getText()));
        verificarComOTokenCerto(d);
        assertEquals(CloudflareFalsa.TUNEL_API, ed(() -> ((TunelCf) null) == null ? c.servico.escolha().orElseThrow().tunelId() : ""), "o que já estava");
        assertTrue(ed(() -> d.caixaDeTuneis().getSelectedIndex()) == 1, "o túnel de antes vem escolhido");
    }
}
