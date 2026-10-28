package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.api.modelo.DetalheProjeto;
import br.com.labdesktop.api.modelo.TelaClaude;
import br.com.labdesktop.api.modelo.TokensDoProjeto;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.BarraServidores;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.NavegadorGravado;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.telas.TelaProjeto.Aba;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.datatransfer.Clipboard;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A página de UM projeto desenhada de verdade (sem janela), com os dados SINTÉTICOS do projeto {@code orbita}: as seis abas,
 * a Visão geral numa tela só, os estados e os PNGs em {@code build/telas}.
 */
@ExtendWith(VerificaEdt.class)
class TelaProjetoTest {

    static final Instant AGORA = Instant.parse("2026-10-24T21:00:00Z");
    static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    /** Quem abriu a página, gravado: o que ela solicitou (voltar, sumiu, aba nova). */
    static final class SaidaGravada implements TelaProjeto.Saida {
        final List<String> pedidos = new ArrayList<>();

        @Override
        public void voltar() {
            pedidos.add("voltar");
        }

        @Override
        public void projetoSumiu() {
            pedidos.add("sumiu");
        }

        @Override
        public void abaMudou(Aba aba) {
            pedidos.add("aba:" + aba.id());
        }
    }

    /** Tudo o que um teste de página precisa, montado de uma vez. */
    static final class Cena {
        final AvisosGravados avisos = new AvisosGravados();
        final DialogosGravados dialogos = new DialogosGravados();
        final NavegadorGravado navegador = new NavegadorGravado(avisos);
        final SaidaGravada saida = new SaidaGravada();
        final Clipboard area = new Clipboard("teste");
        final FluxosDeProjeto fluxos;
        final TelaProjeto tela;
        String urlDeLogin;

        Cena(boolean acoes, Aba aba) {
            this.fluxos = Visual.naEdt(() -> new FluxosDeProjeto(null, avisos, dialogos, acoes));
            this.tela = Visual.naEdt(() -> {
                TelaProjeto t = new TelaProjeto(null, "orbita", fluxos, navegador, avisos, aba, saida, () -> urlDeLogin, () -> { },
                    new CopiadorDeTexto(() -> area));
                t.relogio(() -> AGORA, SP);
                return t;
            });
        }

        /** Alimenta a página como o Sondador faria: estado da conexão e detalhe. */
        Cena com(EstadoConexao conexao, DetalheProjeto detalhe) {
            Visual.naEdt(() -> {
                tela.aoMudarEstado(estado(conexao));
                if (detalhe != null) {
                    tela.aoReceberDetalhe(detalhe);
                }
            });
            return this;
        }

        Cena comConsumo(TokensDoProjeto tokens) {
            Visual.naEdt(() -> tela.consumo().receber(tokens));
            return this;
        }

        Cena naAba(Aba aba) {
            Visual.naEdt(() -> tela.mostrarAba(aba));
            return this;
        }
    }

    static EstadoServidor estado(EstadoConexao c) {
        return new EstadoServidor("s1", "lab", c, null, 0, null, null, 1, Estado2fa.DESLIGADO, "mac-de-teste", null,
            FixturesDeApi.limitesDoMock());
    }

    /** A fixture em UMA linha (sem as quebras do JSON indentado), para trocar trechos pelo texto de "chave": valor. */
    static String detalheEmUmaLinha() {
        return FixturesDeApi.lerRecursoDoProjeto("detalhe-sintetico.json").replaceAll("\\s*\\n\\s*", " ");
    }

    /** O detalhe sintético com um trecho trocado (a fixture é texto: trocar o texto é trocar o dado). */
    static DetalheProjeto detalheCom(String de, String para) {
        String json = detalheEmUmaLinha();
        assertTrue(json.contains(de), "a fixture não tem: " + de);
        return FixturesDeApi.de(json.replace(de, para), DetalheProjeto.class);
    }

    static final String LIMITE_PAUSADO = "{\"teto_pct\": 20, \"usado_pp\": 22.4, \"estado\": \"pausado\", \"liberado\": false, "
        + "\"pausa\": {\"motivo\": \"teto\", \"em\": \"2026-10-24T20:25:00.000Z\"}, "
        + "\"zera_em\": \"2026-10-29T18:00:00.000Z\", \"desde\": \"2026-10-22T18:00:00.000Z\"}";

    /** O detalhe com a sessão pausada pelo limite (22,4 de 20 pontos), sem sessão aberta. */
    static DetalheProjeto detalhePausado() {
        String json = detalheEmUmaLinha();
        String novo = json.substring(0, json.indexOf("\"limite\"")) + "\"limite\": " + LIMITE_PAUSADO + " }";
        return FixturesDeApi.de(novo.replace("\"aberta\": true", "\"aberta\": false"), DetalheProjeto.class);
    }

    static DetalheProjeto detalheSemTeto() {
        return detalheCom("\"teto_pct\": 20", "\"teto_pct\": null");
    }

    /** O detalhe sintético com {@code n} containers (os 4 primeiros são os de sempre; os outros, rodando). */
    static DetalheProjeto detalheComContainers(int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 1; i <= n; i++) {
            if (i > 1) {
                sb.append(',');
            }
            sb.append("{\"nome\":\"orbita-servico").append(i).append("-1\",\"estado\":\"").append(i % 5 == 0 ? "exited" : "running")
                .append("\",\"status\":\"Up 1 hour\",\"portas\":\"127.0.0.1:").append(4000 + i).append("->3000/tcp\",")
                .append("\"imagem\":\"orbita-servico").append(i).append(":latest\",\"criado\":\"2026-10-21T14:05:00.000Z\",")
                .append("\"cpu\":0.5,\"mem\":50000000,\"memLimite\":1000000000,\"net\":\"1MB / 1MB\",\"bloco\":\"0B / 0B\",\"pids\":5}");
        }
        sb.append(']');
        String json = detalheEmUmaLinha().replaceFirst("\"containers\": \\[.*?\\], \"urls\"", java.util.regex.Matcher.quoteReplacement("\"containers\": " + sb + ", \"urls\""));
        return FixturesDeApi.de(json, DetalheProjeto.class);
    }

    static final String HOSTS_FORA_DA_LISTA = "[\"orbita-lab.example.com\", \"malvado.example.org\"]";

    static String textos(java.awt.Component c) {
        return Visual.naEdt(() -> br.com.labdesktop.ui.TextosDeTela.de(c));
    }

    // ---------------------------------------------------------------------------------------------
    // Cabeçalho, abas e navegação
    // ---------------------------------------------------------------------------------------------

    @Test
    void cabecalhoTemVoltarONomeOEstadoEAHoraDaLeitura() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true, Aba.VISAO_GERAL).com(EstadoConexao.CONECTADO, FixturesDeApi.detalheDoProjetoSintetico());
        assertEquals("‹ Projetos", Visual.naEdt(() -> c.tela.botaoVoltar().getText()));
        assertEquals("orbita", Visual.naEdt(c.tela::textoDoNome));
        assertEquals("PARCIAL", Visual.naEdt(() -> c.tela.seloDoEstado().texto()));
        assertEquals("Leitura das 18:00:00", Visual.naEdt(c.tela::textoDaLeitura), "21:00 UTC no fuso de São Paulo");
        assertNotNull(Visual.naEdt(() -> c.tela.botaoAtualizar()));
    }

    @Test
    void antesDoDetalheOCabecalhoNaoMostraEstado() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true, Aba.VISAO_GERAL);
        assertFalse(Visual.naEdt(() -> c.tela.seloDoEstado().isVisible()));
        assertEquals("", Visual.naEdt(c.tela::textoDaLeitura));
    }

    @Test
    void sao6AbasNaOrdemDaTelaESoUmaVisivel() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true, Aba.VISAO_GERAL).com(EstadoConexao.CONECTADO, FixturesDeApi.detalheDoProjetoSintetico());
        assertEquals(List.of("Visão geral", "Logs", "Terminal", "Arquivos", "Backups (5)", "Consumo e limite"),
            java.util.Arrays.stream(Aba.values()).map(a -> Visual.naEdt(() -> c.tela.abas().botao(a.id()).getText())).toList());
        for (Aba a : Aba.values()) {
            Visual.naEdt(() -> c.tela.abas().botao(a.id()).doClick());
            assertEquals(a, c.tela.abaAtual());
            assertEquals(a, Visual.naEdt(c.tela::abaMarcada), "rótulo e conteúdo andam juntos");
            for (Aba outra : Aba.values()) {
                java.awt.Component pagina = outra == Aba.ARQUIVOS ? c.tela.arquivos() : c.tela.pagina(outra);
                assertEquals(outra == a, Visual.naEdt(pagina::isVisible), a + " na frente: " + outra);
            }
        }
    }

    @Test
    void trocarDeAbaAvisaQuemAbriuParaLembrar() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true, Aba.VISAO_GERAL).com(EstadoConexao.CONECTADO, FixturesDeApi.detalheDoProjetoSintetico());
        Visual.naEdt(() -> c.tela.abas().botao("logs").doClick());
        Visual.naEdt(() -> c.tela.abas().botao("consumo").doClick());
        Visual.naEdt(() -> c.tela.abas().botao("consumo").doClick());
        assertEquals(List.of("aba:logs", "aba:consumo"), c.saida.pedidos, "só avisa quando a aba muda de fato");
    }

    @Test
    void abreNaAbaQueOProjetoEstava() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true, Aba.TERMINAL);
        assertEquals(Aba.TERMINAL, c.tela.abaAtual());
        assertEquals(Aba.TERMINAL, Visual.naEdt(c.tela::abaMarcada));
        assertTrue(Visual.naEdt(() -> c.tela.pagina(Aba.TERMINAL).isVisible()));
        assertFalse(Visual.naEdt(() -> c.tela.pagina(Aba.VISAO_GERAL).isVisible()));
        assertTrue(c.saida.pedidos.isEmpty(), "abrir na aba lembrada não é uma troca de aba");
    }

    @Test
    void asSetasTrocamDeAbaEOFocoFicaVisivel() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true, Aba.VISAO_GERAL);
        var botao = Visual.naEdt(() -> c.tela.abas().botao("visao-geral"));
        Visual.naEdt(() -> botao.getActionMap().get("proxima-aba").actionPerformed(null));
        assertEquals(Aba.LOGS, c.tela.abaAtual());
        Visual.naEdt(() -> c.tela.abas().botao("logs").getActionMap().get("aba-anterior").actionPerformed(null));
        assertEquals(Aba.VISAO_GERAL, c.tela.abaAtual());
        assertTrue(Visual.naEdt(botao::isFocusable));
    }

    @Test
    void oContadorDeBackupsMoraNoRotuloDaAba() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true, Aba.VISAO_GERAL);
        assertEquals("Backups", Visual.naEdt(() -> c.tela.abas().botao("backups").getText()), "antes do detalhe, sem número");
        c.com(EstadoConexao.CONECTADO, FixturesDeApi.detalheDoProjetoSintetico());
        assertEquals("Backups (5)", Visual.naEdt(() -> c.tela.abas().botao("backups").getText()));
        c.com(EstadoConexao.CONECTADO, detalheCom("\"backups\": [", "\"backups\": [], \"backups_antigos\": ["));
        assertEquals("Backups (0)", Visual.naEdt(() -> c.tela.abas().botao("backups").getText()));
    }

    @Test
    void voltarEEscVoltamParaALista() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true, Aba.VISAO_GERAL);
        Visual.naEdt(() -> c.tela.botaoVoltar().doClick());
        assertEquals(List.of("voltar"), c.saida.pedidos);
        assertEquals("voltar-aos-projetos", Visual.naEdt(() -> c.tela.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW)
            .get(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0))), "Esc está ligado na janela");
        Visual.naEdt(() -> c.tela.aoApertarEsc(null));
        assertEquals(List.of("voltar", "voltar"), c.saida.pedidos);
        Visual.naEdt(() -> c.tela.getActionMap().get("voltar-aos-projetos").actionPerformed(null));
        assertEquals(3, c.saida.pedidos.size(), "a ação do mapa também volta");
    }

    @Test
    void escNumCampoComTextoSoLimpaOCampo() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true, Aba.TERMINAL);
        javax.swing.JTextField campo = Visual.naEdt(() -> new javax.swing.JTextField("minha mensagem pela metade"));
        Visual.naEdt(() -> c.tela.aoApertarEsc(campo));
        assertEquals("", Visual.naEdt(() -> campo.getText()), "o Esc limpou o campo");
        assertTrue(c.saida.pedidos.isEmpty(), "e não saiu da página: a mensagem digitada não se perde por engano");
        Visual.naEdt(() -> c.tela.aoApertarEsc(campo));
        assertEquals(List.of("voltar"), c.saida.pedidos, "campo vazio: Esc volta");
    }

    @Test
    void projetoQueSomeDoServidorAvisaQuemAbriu() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true, Aba.VISAO_GERAL).com(EstadoConexao.CONECTADO, FixturesDeApi.detalheDoProjetoSintetico());
        Visual.naEdt(() -> c.tela.aoFalharDetalhe(new br.com.labdesktop.api.ErroApi.NaoEncontrado(null, "Projeto não existe.")));
        assertEquals(List.of("sumiu"), c.saida.pedidos);
    }

    @Test
    void falhaDeRedeEErroComDadosNaoTiramOProjetoDaTela() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true, Aba.VISAO_GERAL).com(EstadoConexao.CONECTADO, FixturesDeApi.detalheDoProjetoSintetico());
        Visual.naEdt(() -> c.tela.aoFalharDetalhe(new br.com.labdesktop.api.ErroApi.ErroRede("caiu", null)));
        Visual.naEdt(() -> c.tela.aoFalharDetalhe(new br.com.labdesktop.api.ErroApi.ServidorErro(500, null, "falhou")));
        assertTrue(c.saida.pedidos.isEmpty());
        assertTrue(Visual.naEdt(() -> c.tela.visaoGeral().mostraCorpo()), "a última leitura continua na tela");
    }

    @Test
    void primeiraLeituraComErroMostraOCartaoDeSemDados() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true, Aba.VISAO_GERAL).com(EstadoConexao.CONECTADO, null);
        Visual.naEdt(() -> c.tela.aoFalharDetalhe(new br.com.labdesktop.api.ErroApi.ServidorErro(500, null, "Docker caiu")));
        var sem = Visual.naEdt(() -> c.tela.visaoGeral().semDados());
        assertTrue(Visual.naEdt(sem::isVisible));
        assertEquals("Não consegui carregar as informações do projeto", Visual.naEdt(sem::textoDoTitulo));
        assertEquals("Docker caiu", Visual.naEdt(sem::textoDaExplicacao));
        assertFalse(Visual.naEdt(() -> c.tela.visaoGeral().mostraCorpo()));
    }

    // ---------------------------------------------------------------------------------------------
    // Estados da página (em todas as abas)
    // ---------------------------------------------------------------------------------------------

    @Test
    void carregandoMostraOEsqueletoESemCorpoEmTodasAsAbas() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true, Aba.VISAO_GERAL).com(EstadoConexao.CONECTADO, null);
        for (Aba a : Aba.values()) {
            if (a == Aba.ARQUIVOS) {
                continue; // o gerenciador de arquivos tem os próprios estados (GerenciadorDeArquivosTest)
            }
            PaginaDoProjeto p = c.tela.pagina(a);
            assertEquals(SituacaoDaVisao.Modo.CARREGANDO, Visual.naEdt(() -> p.situacao().modo()), a.toString());
            assertTrue(Visual.naEdt(p::mostraEsqueleto), a + ": esqueleto");
            assertFalse(Visual.naEdt(p::mostraCorpo), a + ": sem corpo");
        }
    }

    @Test
    void semConexaoComDadosEsmaeceEDizAHoraEmTodasAsAbas() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true, Aba.VISAO_GERAL).com(EstadoConexao.CONECTADO, FixturesDeApi.detalheDoProjetoSintetico());
        Visual.naEdt(() -> c.tela.aoMudarEstado(estado(EstadoConexao.RECONECTANDO)));
        for (Aba a : Aba.values()) {
            if (a == Aba.ARQUIVOS) {
                continue;
            }
            PaginaDoProjeto p = c.tela.pagina(a);
            assertEquals(SituacaoDaVisao.Modo.SEM_CONEXAO_COM_DADOS, Visual.naEdt(() -> p.situacao().modo()), a.toString());
            assertTrue(Visual.naEdt(p::mostraCorpo), a + ": os últimos dados continuam");
            assertTrue(Visual.naEdt(p::corpoEsmaecido), a + ": esmaecidos");
            assertTrue(Visual.naEdt(() -> p.faixaDeDados().textoDoTitulo()).contains("mostrando os últimos dados de 18:00"), a.toString());
        }
    }

    @Test
    void semConexaoESemDadosMostraOCartaoComOTexto() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true, Aba.VISAO_GERAL).com(EstadoConexao.RECONECTANDO, null);
        PaginaDoProjeto p = c.tela.pagina(Aba.VISAO_GERAL);
        assertEquals(SituacaoDaVisao.Modo.SEM_CONEXAO_SEM_DADOS, Visual.naEdt(() -> p.situacao().modo()));
        assertTrue(Visual.naEdt(() -> p.semDados().isVisible()));
        assertEquals("Sem conexão com o servidor", Visual.naEdt(() -> p.semDados().textoDoTitulo()));
    }

    @Test
    void agenteForaExplicaQueFaltaOAgente() {
        Visual.tema(Tema.CLARO);
        Cena c = new Cena(true, Aba.VISAO_GERAL).com(EstadoConexao.SSH_OK_SEM_AGENTE, FixturesDeApi.detalheDoProjetoSintetico());
        PaginaDoProjeto p = c.tela.pagina(Aba.VISAO_GERAL);
        assertEquals(SituacaoDaVisao.Modo.SEM_AGENTE_COM_DADOS, Visual.naEdt(() -> p.situacao().modo()));
        assertTrue(Visual.naEdt(() -> p.faixaDeDados().textoDoTitulo()).startsWith("O agente do painel não responde"));
    }

    // ---------------------------------------------------------------------------------------------
    // Texto do servidor nunca é HTML
    // ---------------------------------------------------------------------------------------------

    @Test
    void descricaoEUrlComHtmlAparecemComoTexto() {
        Visual.tema(Tema.CLARO);
        DetalheProjeto d = detalheCom("Painel de relatórios de exemplo (dados inventados para os testes do app).", "<html><b>negrito</b><img src=x>");
        Cena c = new Cena(true, Aba.VISAO_GERAL).com(EstadoConexao.CONECTADO, d);
        String desc = Visual.naEdt(() -> c.tela.visaoGeral().textoDaDescricao());
        assertFalse(desc.toLowerCase().startsWith("<html"), "o Swing renderizaria HTML: " + desc);
        assertTrue(desc.contains("<b>negrito</b>"), "mas o texto continua lá, como texto");
        var rotulo = Visual.naEdt(() -> c.tela.visaoGeral().componentes().stream().filter(x -> x instanceof javax.swing.JLabel l
            && l.getText().contains("negrito")).findFirst().orElseThrow());
        assertEquals(Boolean.TRUE, Visual.naEdt(() -> ((javax.swing.JComponent) rotulo).getClientProperty("html.disable")));
    }

    // ---------------------------------------------------------------------------------------------
    // PNGs
    // ---------------------------------------------------------------------------------------------

    private static void fotografar(Tema tema, Aba aba, int larguraDaJanela, int alturaDaJanela, String nome, boolean acoes,
                                   DetalheProjeto detalhe, TelaClaude telaDoClaude) {
        Visual.tema(tema);
        Cena c = new Cena(acoes, aba).com(EstadoConexao.CONECTADO, detalhe).comConsumo(FixturesDeApi.consumoSintetico());
        Visual.naEdt(() -> c.tela.logs().receber(FixturesDeApi.lerRecursoDoProjeto("logs-sintetico.txt"), 300));
        if (telaDoClaude != null) {
            Visual.naEdt(() -> c.tela.terminal().receberTela(telaDoClaude));
        }
        int largura = larguraDaJanela - BarraServidores.LARGURA;
        int altura = alturaDaJanela - 28;
        Visual.renderizar(c.tela, largura, altura); // primeira passada: o layout dá tamanho à área de logs
        Visual.naEdt(() -> { // "seguir o fim" leva ao fim agora que a área tem tamanho (na janela isso acontece sozinho)
            c.tela.logs().caixaSeguir().doClick();
            c.tela.logs().caixaSeguir().doClick();
        });
        Visual.naEdt(() -> { });
        BufferedImage img = Visual.renderizar(c.tela, largura, altura);
        Visual.salvar(img, nome);
    }

    /** Sessão desatualizada e mais containers que a página cabe: o pior caso da Visão geral. */
    static DetalheProjeto desatualizadaComTudo() {
        String falta = "imagem antiga (Claude desatualizado)";
        String json = detalheComContainers(8).containers().isEmpty() ? "" : detalheEmUmaLinha();
        DetalheProjeto muitos = detalheComContainers(8);
        String containers;
        try {
            containers = br.com.labdesktop.util.Json.mapper().writeValueAsString(muitos.containers());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return FixturesDeApi.de(json.replace("\"atualizada\": true, \"faltando\": []", "\"atualizada\": false, \"faltando\": [\"" + falta + "\"]")
            .replaceFirst("\"containers\": \\[.*?\\], \"urls\"", java.util.regex.Matcher.quoteReplacement("\"containers\": " + containers + ", \"urls\"")),
            DetalheProjeto.class);
    }

    @Test
    void geraOsPngsDaPaginaDoProjeto() {
        DetalheProjeto d = FixturesDeApi.detalheDoProjetoSintetico();
        TelaClaude exec = FixturesDeApi.telaSintetica("tela-executando");
        fotografar(Tema.ESCURO, Aba.VISAO_GERAL, 1440, 860, "projeto-visao-geral-escuro", true, d, null);
        fotografar(Tema.CLARO, Aba.VISAO_GERAL, 1440, 860, "projeto-visao-geral-claro", true, d, null);
        fotografar(Tema.ESCURO, Aba.VISAO_GERAL, 1100, 700, "projeto-visao-geral-escuro-1100", true, d, null);
        fotografar(Tema.CLARO, Aba.LOGS, 1440, 860, "projeto-logs", true, d, null);
        fotografar(Tema.ESCURO, Aba.LOGS, 1100, 700, "projeto-logs-escuro-1100", true, d, null);
        fotografar(Tema.CLARO, Aba.TERMINAL, 1440, 860, "projeto-sessao", true, d, exec);
        fotografar(Tema.ESCURO, Aba.TERMINAL, 1100, 700, "projeto-sessao-escuro-1100", true, d, exec);
        fotografar(Tema.CLARO, Aba.CONSUMO, 1440, 860, "projeto-consumo", true, d, null);
        fotografar(Tema.ESCURO, Aba.CONSUMO, 1100, 700, "projeto-consumo-escuro-1100", true, d, null);
        fotografar(Tema.CLARO, Aba.CONSUMO, 1440, 860, "projeto-consumo-pausado", true, detalhePausado(), null);
        fotografar(Tema.ESCURO, Aba.CONSUMO, 1100, 700, "projeto-consumo-pausado-escuro-1100", true, detalhePausado(), null);
        fotografar(Tema.CLARO, Aba.TERMINAL, 1100, 700, "projeto-sessao-login", true, d, FixturesDeApi.telaSintetica("tela-login"));
        fotografar(Tema.CLARO, Aba.VISAO_GERAL, 1100, 700, "projeto-visao-geral-claro-1100", true, desatualizadaComTudo(), null);
        fotografar(Tema.CLARO, Aba.VISAO_GERAL, 1100, 700, "projeto-visao-geral-somente-leitura", false, d, null);
        fotografar(Tema.CLARO, Aba.BACKUPS, 1100, 700, "projeto-backups", true, d, null);
    }
}
