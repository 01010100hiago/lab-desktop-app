package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.api.modelo.DetalheServidor;
import br.com.labdesktop.api.modelo.InfoServidor;
import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.servidor.ParsersDeSeguranca;
import br.com.labdesktop.servidor.ResumoDeSeguranca;
import br.com.labdesktop.servidor.ResumoDeSeguranca.Leitura;
import br.com.labdesktop.servidor.ResumoDeSeguranca.TipoDeTentativa;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.Falha;
import br.com.labdesktop.teste.SaidasDeSeguranca;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.BarraServidores;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.TextosDeTela;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.telas.SituacaoDaVisao.Modo;
import br.com.labdesktop.ui.telas.TelaServidor.Aba;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** A página do servidor desenhada de verdade (sem janela): abas, o Resumo numa tela só, a Segurança em sub-abas, estados e PNGs. */
@ExtendWith(VerificaEdt.class)
class TelaServidorTest {

    static final Instant AGORA = Instant.parse("2026-10-24T21:00:00Z");
    static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    static TelaServidor nova() {
        AvisosGravados avisos = new AvisosGravados();
        DialogosGravados dialogos = new DialogosGravados();
        return Visual.naEdt(() -> {
            TelaServidor t = new TelaServidor(null, TelaProjetosTest.nova(avisos, dialogos), () -> { });
            for (Aba a : Aba.values()) {
                if (t.pagina(a) != null) {
                    t.pagina(a).relogio(() -> AGORA, SP);
                }
            }
            return t;
        });
    }

    static EstadoServidor estado(EstadoConexao c) {
        return TelaProjetosTest.estado(c);
    }

    /** Alimenta TODAS as abas como o Sondador faria (no app, cada uma recebe só o que assinou), tudo na EDT. */
    static void alimentar(TelaServidor t, EstadoConexao conexao, ResumoDeSeguranca seguranca, boolean comDetalhe) {
        alimentar(t, conexao, FixturesDeApi.servidorSintetico(), comDetalhe ? FixturesDeApi.detalheSintetico() : null, seguranca);
    }

    static void alimentar(TelaServidor t, EstadoConexao conexao, InfoServidor info, DetalheServidor detalhe, ResumoDeSeguranca seguranca) {
        Visual.naEdt(() -> {
            for (Aba a : Aba.values()) {
                PaginaDoServidor v = t.pagina(a);
                if (v == null) {
                    continue;
                }
                v.aoMudarEstado(estado(conexao));
                v.aoReceberServidor(info);
                if (detalhe != null) {
                    v.aoReceberDetalhe(detalhe);
                }
                if (seguranca != null) {
                    v.aoReceberSeguranca(seguranca);
                }
            }
        });
    }

    static List<Component> todos(Component raiz) {
        List<Component> l = new ArrayList<>();
        coletar(raiz, l);
        return l;
    }

    private static void coletar(Component c, List<Component> l) {
        l.add(c);
        if (c instanceof Container k) {
            for (Component f : k.getComponents()) {
                coletar(f, l);
            }
        }
    }

    static String texto(Component c) {
        return Visual.naEdt(() -> TextosDeTela.de(c));
    }

    static ResumoDeSeguranca resumoGrande(int tentativas, int logins) {
        return new ResumoDeSeguranca(AGORA,
            Leitura.lida(ParsersDeSeguranca.lerRegistroDoSsh(SaidasDeSeguranca.registroGrande(AGORA, tentativas, logins), AGORA, false)),
            Leitura.lida(ParsersDeSeguranca.lerPortas(SaidasDeSeguranca.SS)),
            Leitura.lida(ParsersDeSeguranca.lerNft(SaidasDeSeguranca.NFT, 2222)),
            Leitura.lida(ParsersDeSeguranca.lerSshd(SaidasDeSeguranca.SSHD_SEGURO)),
            Leitura.lida(ParsersDeSeguranca.lerFail2ban(true, SaidasDeSeguranca.FAIL2BAN)));
    }

    // ---------------------------------------------------------------------------------------------
    // Abas
    // ---------------------------------------------------------------------------------------------

    @Test
    void abreNoResumoTemSeisAbasEAlternaSemMudarATelaDeProjetos() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        assertEquals(Aba.RESUMO, t.abaAtual());
        assertEquals(List.of("Resumo", "Segurança", "Containers", "Processos", "Máquina", "Projetos"),
            java.util.Arrays.stream(Aba.values()).map(a -> Visual.naEdt(() -> t.abas().botao(a.id()).getText())).toList());
        for (Aba a : Aba.values()) {
            Visual.naEdt(() -> t.abas().botao(a.id()).doClick());
            assertEquals(a, t.abaAtual());
            assertEquals(a, Visual.naEdt(t::abaMarcada), "rótulo e conteúdo andam juntos");
            for (Aba outra : Aba.values()) {
                Component c = outra == Aba.PROJETOS ? t.projetos() : t.pagina(outra);
                assertEquals(outra == a, Visual.naEdt(c::isVisible), a + " na frente: " + outra);
            }
        }
        // a TelaProjetos é a de sempre, com a lista e os filtros dela
        assertNotNull(Visual.naEdt(() -> t.projetos().botaoDeFiltro(ListagemDeProjetos.Filtro.TODOS)));
    }

    @Test
    void asSetasDoTecladoTrocamDeAbaEOFocoFicaVisivel() {
        TelaServidor t = nova();
        var botao = Visual.naEdt(() -> t.abas().botao("resumo"));
        Visual.naEdt(() -> botao.getActionMap().get("proxima-aba").actionPerformed(null));
        assertEquals(Aba.SEGURANCA, t.abaAtual());
        Visual.naEdt(() -> t.abas().botao("seguranca").getActionMap().get("aba-anterior").actionPerformed(null));
        assertEquals(Aba.RESUMO, t.abaAtual());
        assertTrue(Visual.naEdt(botao::isFocusable));
    }

    @Test
    void cadaAbaSoUsaOsSeusTopicos() {
        TelaServidor t = nova();
        assertEquals(java.util.Set.of("servidor", "detalhe", "seguranca"), t.resumo().topicos());
        assertEquals(java.util.Set.of("seguranca"), t.seguranca().topicos());
        assertEquals(java.util.Set.of("servidor"), t.containers().topicos());
        assertEquals(java.util.Set.of("servidor", "detalhe"), t.processos().topicos());
        assertEquals(java.util.Set.of("servidor", "detalhe"), t.maquina().topicos());
    }

    // ---------------------------------------------------------------------------------------------
    // Resumo
    // ---------------------------------------------------------------------------------------------

    /** O conteúdo do Resumo pede só a altura da tela; a página inteira (com a barra de abas) cabe na janela. */
    private static void conferirQueOResumoCabe(int larguraDaJanela, int alturaDaJanela, ResumoDeSeguranca seg, InfoServidor info,
                                               DetalheServidor detalhe) {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, info, detalhe, seg);
        int largura = larguraDaJanela - BarraServidores.LARGURA;
        int altura = alturaDaJanela - 28; // a barra de título da janela
        int pedida = Visual.alturaPreferida(t, largura);
        assertTrue(pedida <= altura, "o Resumo pede " + pedida + " px de altura, mas a janela de " + larguraDaJanela + "x"
            + alturaDaJanela + " dá " + altura);
        BufferedImage img = Visual.renderizar(t, largura, altura);
        assertFalse(Visual.naEdt(() -> t.resumo().rolagem().getVerticalScrollBar().isVisible()), "o Resumo não rola");
        assertFalse(Visual.naEdt(() -> t.resumo().rolagem().getHorizontalScrollBar().isVisible()), "nem na horizontal");
        assertEquals(altura, img.getHeight());
        Visual.naEdt(() -> {
            int alturaDoConteudo = t.resumo().pilha().getPreferredSize().height;
            assertTrue(alturaDoConteudo + t.abas().getPreferredSize().height <= altura, "conteúdo " + alturaDoConteudo);
            return null;
        });
    }

    @Test
    void oResumoCabeSemRolagemEm1100x700() {
        conferirQueOResumoCabe(1100, 700, ModeloDeKpisTest.comAtaque(), FixturesDeApi.servidorSintetico(), FixturesDeApi.detalheSintetico());
    }

    @Test
    void aSegurancaTambemCabeEm1100x700ComUmaPaginaCheiaDeTentativas() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, resumoGrande(60, 6), true);
        Visual.naEdt(() -> t.mostrarAba(Aba.SEGURANCA));
        int largura = 1100 - BarraServidores.LARGURA;
        int pedida = Visual.alturaPreferida(t, largura);
        assertTrue(pedida <= 700 - 28, "a Segurança pede " + pedida + " px: as 15 linhas da página cabem na janela de 700");
        Visual.renderizar(t, largura, 700 - 28);
        assertFalse(Visual.naEdt(() -> t.seguranca().rolagem().getVerticalScrollBar().isVisible()));
        // trocar de sub-aba nunca deixa a página mais comprida que a das tentativas
        for (String id : new String[] {PaginaSeguranca.SUB_ORIGENS, PaginaSeguranca.SUB_LOGINS, PaginaSeguranca.SUB_FIREWALL, PaginaSeguranca.SUB_SSH}) {
            Visual.naEdt(() -> t.seguranca().mostrarSubAba(id));
            assertTrue(Visual.alturaPreferida(t, largura) <= 700 - 28, id);
        }
    }

    @Test
    void oResumoCabeSemRolagemEm1440x860() {
        conferirQueOResumoCabe(1440, 860, ModeloDeKpisTest.comAtaque(), FixturesDeApi.servidorSintetico(), FixturesDeApi.detalheSintetico());
    }

    @Test
    void oResumoCabeMesmoNoPiorCasoComCincoAlertasCriticos() {
        InfoServidor info = problemas();
        DetalheServidor detalhe = FixturesDeApi.de(FixturesDeApi.lerRecurso("servidor-detalhe-sintetico.json")
            .replace("\"tomada\": true", "\"tomada\": false"), DetalheServidor.class);
        conferirQueOResumoCabe(1100, 700, ModeloDeKpisTest.comAtaque(), info, detalhe);
    }

    /** O detalhe sintético sem o container sem dono (o resto igual). */
    static DetalheServidor detalheSemContainerSemDono() {
        return FixturesDeApi.de(FixturesDeApi.lerRecurso("servidor-detalhe-sintetico.json").replace("\"projeto\": null", "\"projeto\": \"gama\""),
            DetalheServidor.class);
    }

    /** O servidor com disco a 91%, memória quase sem espaço e CPU a 92 °C. */
    static InfoServidor problemas() {
        return FixturesDeApi.de(FixturesDeApi.lerRecurso("servidor-sintetico.json")
            .replace("\"usado\": 64424509440", "\"usado\": 97710505984")
            .replace("\"disponivel\": 5690831667", "\"disponivel\": 400000000")
            .replace("\"temperatura\": 62", "\"temperatura\": 92"), InfoServidor.class);
    }

    @Test
    void listaDeAtencaoOrdenadaPorGravidadeELimitadaACinco() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        DetalheServidor detalhe = FixturesDeApi.de(FixturesDeApi.lerRecurso("servidor-detalhe-sintetico.json")
            .replace("\"tomada\": true", "\"tomada\": false"), DetalheServidor.class);
        alimentar(t, EstadoConexao.CONECTADO, problemas(), detalhe, ModeloDeKpisTest.comAtaque());
        var lista = Visual.naEdt(() -> t.resumo().listaDeAtencao());
        List<String> textos = Visual.naEdt(lista::textos);
        // 5 críticos (segurança fora da rede, disco, memória, CPU, bateria), 2 de atenção ficam de fora: o teto é 5
        assertEquals(5, textos.size());
        assertTrue(textos.stream().allMatch(x -> x.startsWith("CRÍTICO")), textos.toString());
        assertTrue(textos.contains("CRÍTICO: Disco /: 91% cheio > Máquina"), textos.toString());
        assertTrue(textos.contains("CRÍTICO: Memória: só 5% disponível > Processos"), textos.toString());
        assertTrue(textos.contains("CRÍTICO: CPU quente: 92 °C > Máquina"), textos.toString());
        assertTrue(textos.contains("CRÍTICO: Segurança: login de fora da rede local (24 h) > Segurança"), textos.toString());
        assertTrue(textos.stream().anyMatch(x -> x.contains("bateria")), textos.toString());
    }

    @Test
    void listaDeAtencaoPoeOCriticoAntesDoDeAtencaoMesmoGeradoDepois() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        InfoServidor soDisco = FixturesDeApi.de(FixturesDeApi.lerRecurso("servidor-sintetico.json")
            .replace("\"usado\": 64424509440", "\"usado\": 97710505984"), InfoServidor.class);
        // as tentativas (atenção) são geradas ANTES do disco (crítico), mas o crítico vai para o topo
        String registro = SaidasDeSeguranca.registroComAtaque(AGORA).lines().filter(l -> !l.contains("Accepted password"))
            .reduce("", (a, b) -> a + b + "\n");
        alimentar(t, EstadoConexao.CONECTADO, soDisco, FixturesDeApi.detalheSintetico(),
            ModeloDeKpisTest.resumo(registro, SaidasDeSeguranca.SSHD_SEGURO, true));
        List<String> textos = Visual.naEdt(() -> t.resumo().listaDeAtencao().textos());
        assertEquals(List.of("CRÍTICO: Disco /: 91% cheio > Máquina",
            "ATENÇÃO: Segurança: 7 tentativas de entrada em 24 h > Segurança",
            "ATENÇÃO: 1 container sem dono rodando > Containers"), textos);
    }

    @Test
    void semProblemasDizQueNadaPrecisaDeAtencao() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, FixturesDeApi.servidorSintetico(), detalheSemContainerSemDono(), ModeloDeKpisTest.calmo());
        assertTrue(Visual.naEdt(() -> t.resumo().listaDeAtencao().itens()).isEmpty());
        assertTrue(texto(t.resumo().listaDeAtencao()).contains("Nada precisa de atenção agora"));
    }

    @Test
    void cadaIndicadorClicavelAbreAAbaCerta() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, ModeloDeKpisTest.calmo(), true);
        var cartoes = Visual.naEdt(() -> t.resumo().faixaDeKpis().cartoes());
        String[][] esperado = {{"seguranca", "SEGURANCA"}, {"containers", "CONTAINERS"}, {"cpu", "PROCESSOS"}, {"memoria", "PROCESSOS"},
            {"disco", "MAQUINA"}};
        for (String[] e : esperado) {
            Visual.naEdt(() -> t.mostrarAba(Aba.RESUMO));
            var cartao = cartoes.stream().filter(c -> Visual.naEdt(() -> c.kpi().chave()).equals(e[0])).findFirst().orElseThrow();
            Visual.naEdt(cartao::clicar);
            assertEquals(Aba.valueOf(e[1]), t.abaAtual(), "o indicador " + e[0]);
            assertEquals(Aba.valueOf(e[1]), Visual.naEdt(t::abaMarcada));
        }
        Visual.naEdt(() -> t.mostrarAba(Aba.RESUMO));
        var saude = cartoes.stream().filter(c -> Visual.naEdt(() -> c.kpi().chave()).equals("saude")).findFirst().orElseThrow();
        Visual.naEdt(saude::clicar);
        assertEquals(Aba.RESUMO, t.abaAtual(), "a Saúde não leva a lugar nenhum");
        assertTrue(Visual.naEdt(() -> cartoes.get(1).isFocusable()), "os clicáveis entram na ordem do Tab");
        assertFalse(Visual.naEdt(saude::isFocusable));
        assertTrue(Visual.naEdt(() -> cartoes.get(3).getAccessibleContext().getAccessibleDescription()).contains("Clique para abrir a aba Máquina"));
    }

    @Test
    void cadaLinhaDeAtencaoLevaAAbaDoAssunto() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, problemas(), FixturesDeApi.detalheSintetico(), ModeloDeKpisTest.comAtaque());
        var lista = Visual.naEdt(() -> t.resumo().listaDeAtencao());
        for (int i = 0; i < Visual.naEdt(() -> lista.itens().size()); i++) {
            int n = i;
            Visual.naEdt(() -> t.mostrarAba(Aba.RESUMO));
            String destino = Visual.naEdt(() -> lista.itens().get(n).destino());
            Visual.naEdt(() -> lista.abrir(n));
            assertEquals(destino, t.abaAtual().rotulo());
        }
    }

    @Test
    void primeiraLinhaTemNoMaximoSeisIndicadoresSemRedeCargaOuVersoes() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, ModeloDeKpisTest.comAtaque(), true);
        List<Component> kpis = Visual.naEdt(() -> todos(t.resumo()).stream()
            .filter(c -> c.getName() != null && c.getName().startsWith("kpi-")).toList());
        assertEquals(6, kpis.size());
        String linha = texto(Visual.naEdt(() -> t.resumo().faixaDeKpis()));
        for (String proibido : new String[] {"Descida", "Subida", "Carga", "Compose", "pnpm", "lab-claude"}) {
            assertFalse(linha.contains(proibido), proibido);
        }
        for (var c : Visual.naEdt(() -> t.resumo().faixaDeKpis().cartoes())) {
            assertTrue(List.of("OK", "ATENÇÃO", "CRÍTICO", "SEM DADO").contains(Visual.naEdt(c::textoDoSelo)));
        }
    }

    @Test
    void cabecalhoEnxutoMostraNomeEstadoMaquinaETempoLigado() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, null, false);
        var c = Visual.naEdt(() -> t.resumo().cabecalho());
        assertEquals("lab", Visual.naEdt(c::textoDoNome));
        assertEquals("CONECTADO", Visual.naEdt(c::textoDoEstado));
        String linha = Visual.naEdt(c::textoDaLinhaDeBaixo);
        assertTrue(linha.contains("Máquina servidor-exemplo"), linha);
        assertTrue(linha.contains("Debian GNU/Linux 13 (trixie)"), linha);
        assertTrue(linha.contains("Ligada há 3 h 57 min"), linha);
        assertTrue(texto(c).contains("Leitura das 17:59:57"), texto(c));
    }

    @Test
    void tendenciasEmGradeCompactaComUmGraficoPorSerieEHistoricoDeSessentaPontos() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, null, false);
        var graficos = Visual.naEdt(() -> t.resumo().blocoDeTendencias().graficos());
        assertEquals(5, graficos.size(), "CPU, memória, descida, subida e temperatura");
        for (int i = 0; i < 4; i++) {
            assertEquals(60, graficos.get(i).quantidadeDePontos());
        }
        assertEquals(57, graficos.get(4).quantidadeDePontos(), "a temperatura começa com 3 nulos: descartados");
        var legendas = Visual.naEdt(() -> t.resumo().blocoDeTendencias().legendas());
        assertTrue(legendas.get(0).startsWith("agora "), legendas.get(0));
        assertTrue(legendas.get(0).contains("mín") && legendas.get(0).contains("máx"), legendas.get(0));
        assertTrue(legendas.get(2).contains("kb/s"), "a rede em bits: " + legendas.get(2));
        assertTrue(texto(t.resumo().blocoDeTendencias()).contains("últimos 10 min"));
    }

    // ---------------------------------------------------------------------------------------------
    // Containers, Processos, Máquina
    // ---------------------------------------------------------------------------------------------

    @Test
    void abaContainersMostraOResumoOsTamanhosEAsSessoesDoClaude() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, null, false);
        var b = Visual.naEdt(() -> t.containers().bloco());
        assertEquals("8 rodando · 2 parados · 0 em pausa", Visual.naEdt(b::textoDoResumo));
        var sessoes = Visual.naEdt(b::linhasDeSessoes);
        assertEquals(2, sessoes.size());
        assertEquals("alfa", sessoes.get(0).get(0));
        assertEquals("lab-claude-alfa", sessoes.get(0).get(1));
        assertEquals("20/10 20:53 (há 4 d)", sessoes.get(0).get(2));
        assertEquals("0,6%", sessoes.get(0).get(3));
        assertEquals("233 MB", sessoes.get(0).get(4));
        String texto = texto(b);
        assertTrue(texto.contains("40 imagens e 6 volumes"), texto);
        assertTrue(texto.contains("cache de build"), texto);
    }

    @Test
    void abaProcessosMostraDonoCpuMemoriaEProjetoVindoDoDetalheOuDoContainer() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, FixturesDeApi.servidorSintetico(), null, null);
        var antes = Visual.naEdt(() -> t.processos().bloco().linhas());
        assertEquals(4, antes.size());
        assertEquals(List.of("claude", "labuser", "—", "403 MB", "alfa · lab-claude-alfa"), antes.get(0), "sem detalhe: deduz da sessão");
        assertEquals("gama-db", antes.get(2).get(4));
        assertEquals("o próprio servidor (fora de container)", antes.get(3).get(4));
        Visual.naEdt(() -> t.processos().aoReceberDetalhe(FixturesDeApi.detalheSintetico()));
        var depois = Visual.naEdt(() -> t.processos().bloco().linhas());
        assertEquals("gama · gama-db", depois.get(2).get(4), "com o detalhe, o projeto vem do agente");
        assertEquals("12%", depois.get(1).get(2));
    }

    @Test
    void abaProcessosOrdenaPorCpuEPorMemoriaAoClicarNoCabecalho() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, FixturesDeApi.servidorSintetico(), FixturesDeApi.detalheSintetico(), null);
        var tabela = Visual.naEdt(() -> t.processos().bloco().tabela());
        assertEquals(List.of("claude", "node", "postgres", "dockerd"), Visual.naEdt(() -> t.processos().bloco().linhas()).stream().map(l -> l.get(0)).toList());
        assertNotNull(Visual.naEdt(() -> tabela.botaoDoCabecalho(2)), "CPU ordena");
        assertNotNull(Visual.naEdt(() -> tabela.botaoDoCabecalho(3)), "memória ordena");
        assertEquals(null, Visual.naEdt(() -> tabela.botaoDoCabecalho(0)));
        Visual.naEdt(() -> tabela.botaoDoCabecalho(2).doClick());
        assertEquals(List.of("node", "postgres", "dockerd", "claude"), Visual.naEdt(() -> t.processos().bloco().linhas()).stream().map(l -> l.get(0)).toList(),
            "CPU do maior para o menor, a sem leitura por último");
        Visual.naEdt(() -> tabela.botaoDoCabecalho(2).doClick());
        assertEquals("claude", Visual.naEdt(() -> t.processos().bloco().linhas()).get(0).get(0), "segundo clique inverte");
        Visual.naEdt(() -> tabela.botaoDoCabecalho(3).doClick());
        assertEquals(List.of("claude", "node", "dockerd", "postgres"), Visual.naEdt(() -> t.processos().bloco().linhas()).stream().map(l -> l.get(0)).toList(),
            "memória do maior para o menor: 403, 176, 143, 92 MB");
        Visual.salvar(Visual.renderizar(t, 900, 330), "servidor-processos-ordenado");
    }

    @Test
    void abaMaquinaMostraVersoesDiscosSensoresEnergiaHardwareSemRecolher() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, null, true);
        var d = Visual.naEdt(() -> t.maquina().detalhes());
        var versoes = Visual.naEdt(d::camposDeVersoes);
        assertEquals("Painel (Node)", versoes.get(0).rotulo());
        assertEquals("24.1.0", versoes.get(0).valor());
        assertEquals("2.1.300", versoes.get(1).valor(), "Claude Code da imagem das sessões");
        assertEquals("22.1.0", versoes.get(2).valor());
        assertEquals("29.8.0", versoes.get(3).valor());
        assertEquals("5.6.0", versoes.get(4).valor());
        assertEquals("12.15.0", versoes.get(5).valor());
        var discos = Visual.naEdt(d::linhasDeDiscos);
        assertEquals(List.of("/", "60,0 GB", "100,0 GB", "35,0 GB", "60%"), discos.get(0));
        var sensores = Visual.naEdt(d::linhasDeSensores);
        assertEquals(3, sensores.size());
        assertEquals(List.of("Processador (pacote)", "62 °C", "87 °C", "105 °C"), sensores.get(1));
        assertEquals(List.of("Placa (ACPI)", "54 °C", "—", "—"), sensores.get(0), "max e crit nulos viram traço");
        assertTrue(Visual.naEdt(d::textoDeEnergia).contains("Na tomada"));
        assertTrue(Visual.naEdt(d::textoDeEnergia).contains("Sem bateria"));
        var maquina = Visual.naEdt(d::camposDaMaquina);
        assertEquals("Fabricante Exemplo Modelo X1", maquina.get(0).valor());
        assertEquals("B123 · 05/17/2012", maquina.get(2).valor());
        String tudo = Visual.naEdt(d::textoCompleto);
        assertTrue(tudo.contains("Carga em 1 minuto"), tudo);
        assertTrue(tudo.contains("Núcleo 1"), "a CPU por núcleo");
        assertTrue(tudo.contains("Descida (download)"));
        assertTrue(tudo.contains("5,7 kb/s"));
        assertTrue(tudo.contains("Cache"), "a memória em detalhe");
    }

    @Test
    void semODetalheAMaquinaAindaMostraVersoesEAFraseDeLeitura() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, null, false);
        var d = Visual.naEdt(() -> t.maquina().detalhes());
        assertTrue(Visual.naEdt(d::textoCompleto).contains("Lendo os sensores"));
        assertTrue(Visual.naEdt(d::linhasDeSensores).isEmpty());
        assertEquals("24.1.0", Visual.naEdt(d::camposDeVersoes).get(0).valor());
    }

    @Test
    void campoAusenteViraTracoSemExcecaoEmTodasAsAbas() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, FixturesDeApi.de("{\"cpu\":{},\"memoria\":{\"swap\":{}},\"discos\":[{}],\"docker\":{},"
            + "\"processos\":[{}],\"sessoes\":[{}],\"historico\":{},\"rede\":{},\"node\":{}}", InfoServidor.class),
            FixturesDeApi.de("{\"sensores\":[{}],\"energia\":{},\"maquina\":{}}", DetalheServidor.class), null);
        for (Aba a : Aba.values()) {
            if (t.pagina(a) != null) {
                Visual.naEdt(() -> t.mostrarAba(a));
                Visual.renderizar(t, 900, 700);
            }
        }
        assertEquals("—", Visual.naEdt(() -> t.resumo().faixaDeKpis().cartoes().get(1).textoDoValor()));
        assertEquals(List.of("—", "—", "—", "—", "o próprio servidor (fora de container)"),
            Visual.naEdt(() -> t.processos().bloco().linhas().get(0)));
    }

    // ---------------------------------------------------------------------------------------------
    // Segurança
    // ---------------------------------------------------------------------------------------------

    @Test
    void segurancaTemFaixaDeResumoESubAbasEUmaSoVisivel() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, ModeloDeKpisTest.comAtaque(), true);
        PaginaSeguranca p = t.seguranca();
        List<String> faixa = Visual.naEdt(p::textosDoResumo);
        assertEquals(5, faixa.size());
        assertTrue(faixa.get(0).contains("TENTATIVAS · 24 H 7"), faixa.toString());
        assertTrue(faixa.get(1).contains("TENTATIVAS · 7 DIAS 8"), faixa.toString());
        assertTrue(faixa.get(2).contains("IPS BLOQUEADOS 2"), faixa.toString());
        assertTrue(faixa.get(3).contains("Só chave") && faixa.get(3).contains("root desligado"), faixa.toString());
        assertTrue(faixa.get(4).contains("Ativo"), faixa.toString());
        assertEquals(List.of("Tentativas", "Origens", "Logins aceitos", "Firewall e portas", "SSH e bloqueio"),
            java.util.stream.Stream.of(PaginaSeguranca.SUB_TENTATIVAS, PaginaSeguranca.SUB_ORIGENS, PaginaSeguranca.SUB_LOGINS,
                PaginaSeguranca.SUB_FIREWALL, PaginaSeguranca.SUB_SSH).map(id -> Visual.naEdt(() -> p.subAbas().botao(id).getText())).toList());
        assertEquals(PaginaSeguranca.SUB_TENTATIVAS, p.subAbaAtual());
        Visual.naEdt(() -> p.subAbas().botao(PaginaSeguranca.SUB_ORIGENS).doClick());
        assertEquals(PaginaSeguranca.SUB_ORIGENS, p.subAbaAtual());
    }

    @Test
    void tentativasEmTabelaPaginadaDe15ComFiltroDePeriodoETipoOrdenadoDoMaisRecente() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, resumoGrande(60, 4), true);
        PaginaSeguranca p = t.seguranca();
        var tabela = p.tabelaDeTentativas();
        assertEquals(60, tabela.total());
        assertEquals(15, tabela.porPagina());
        assertEquals(15, tabela.linhasDaPagina().size());
        assertEquals("mostrando 1-15 de 60", tabela.textoDoResumo());
        // colunas: quando, tipo, IP, usuário tentado; a primeira linha é a mais recente (1 min antes da leitura)
        var primeira = tabela.linhasDaPagina().get(0);
        assertEquals(4, primeira.size());
        assertEquals("Usuário inventado", primeira.get(1));
        assertEquals("203.0.113.1", primeira.get(2));
        assertEquals("admin", primeira.get(3));
        assertEquals("—", tabela.linhasDaPagina().get(2).get(3), "varredura não tem usuário: traço");

        Visual.naEdt(() -> tabela.botaoProxima().doClick());
        Visual.naEdt(() -> tabela.botaoProxima().doClick());
        assertEquals(3, tabela.pagina());
        // mudar o período volta à página 1: em 24 h cabem 60 conexões a cada 10 min? só 144 min = 14,4 conexões por 24 h... 
        Visual.naEdt(() -> p.escolherPeriodo("24h"));
        assertEquals(1, tabela.pagina());
        assertEquals(60, tabela.totalFiltrado(), "as 60 estão nas últimas 10 h");

        Visual.naEdt(() -> tabela.botaoProxima().doClick());
        Visual.naEdt(() -> p.escolherTipo(TipoDeTentativa.VARREDURA));
        assertEquals(1, tabela.pagina(), "mudar o tipo também volta à página 1");
        assertEquals(15, tabela.totalFiltrado());
        assertTrue(tabela.linhasDaPagina().stream().allMatch(l -> l.get(1).equals("Varredura")));
        Visual.naEdt(() -> p.escolherTipo(null));
        assertEquals(60, tabela.totalFiltrado());
    }

    @Test
    void filtroDe24HorasCortaComOReferencialDaLeitura() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        // 200 conexões, uma a cada 10 min: 144 nas últimas 24 h
        alimentar(t, EstadoConexao.CONECTADO, resumoGrande(200, 0), true);
        PaginaSeguranca p = t.seguranca();
        Visual.naEdt(() -> p.escolherPeriodo("24h"));
        assertEquals(144, p.tabelaDeTentativas().totalFiltrado());
        Visual.naEdt(() -> p.escolherPeriodo("7d"));
        assertEquals(200, p.tabelaDeTentativas().totalFiltrado());
    }

    @Test
    void maisDe500TentativasDizMostrandoOs500MaisRecentes() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, resumoGrande(620, 0), true);
        var tabela = t.seguranca().tabelaDeTentativas();
        assertEquals(500, tabela.total());
        assertEquals(34, tabela.totalDePaginas());
        assertTrue(tabela.textoDaNota().contains("mostrando os 500 mais recentes"), tabela.textoDaNota());
        assertTrue(tabela.textoDaNota().contains("620"), tabela.textoDaNota());
        assertTrue(Visual.naEdt(() -> t.seguranca().textosDoResumo()).get(1).contains("620"), "o resumo conta todas");
    }

    @Test
    void saidaTruncadaDoSshMantemOAvisoPeloMenos() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        ResumoDeSeguranca r = new ResumoDeSeguranca(AGORA,
            Leitura.lida(ParsersDeSeguranca.lerRegistroDoSsh(SaidasDeSeguranca.registroGrande(AGORA, 30, 2), AGORA, true)),
            Leitura.indisponivel(), Leitura.indisponivel(), Leitura.indisponivel(), Leitura.indisponivel());
        alimentar(t, EstadoConexao.CONECTADO, r, true);
        assertTrue(t.seguranca().tabelaDeTentativas().textoDaNota().contains("pelo menos"), t.seguranca().tabelaDeTentativas().textoDaNota());
        assertTrue(Visual.naEdt(() -> t.seguranca().textosDoResumo()).get(0).contains("30+"));
    }

    @Test
    void origensTop10ComBarraProporcionalTipoPredominanteEUltimoHorario() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, resumoGrande(120, 0), true);
        var linhas = Visual.naEdt(() -> t.seguranca().linhasDeOrigens());
        assertEquals(10, linhas.size());
        assertEquals(4, linhas.get(0).size());
        assertTrue(linhas.get(0).get(2).matches("Adivinhar senha|Usuário inventado|Varredura|Outros"), linhas.get(0).toString());
        assertTrue(linhas.get(0).get(3).contains("(há "), linhas.get(0).get(3));
        Visual.naEdt(() -> t.seguranca().mostrarSubAba(PaginaSeguranca.SUB_ORIGENS));
        String s = Visual.naEdt(() -> t.seguranca().textoDoPainel(PaginaSeguranca.SUB_ORIGENS));
        assertTrue(s.contains("As 10 que mais tentaram, de 18 endereços"), s);
        assertTrue(s.contains("Sem geolocalização"), s);
        var barras = Visual.naEdt(() -> todos(t.seguranca()).stream().filter(c -> c instanceof br.com.labdesktop.ui.componentes.BarraSeveridade)
            .map(c -> ((br.com.labdesktop.ui.componentes.BarraSeveridade) c).fracao()).toList());
        assertTrue(barras.stream().anyMatch(f -> f == 1.0), "o maior ocupa a barra toda");
        assertTrue(barras.stream().allMatch(f -> f > 0 && f <= 1.0));
    }

    @Test
    void loginsAceitosEmTabelaPaginadaComOsDeAtencaoNoTopoEDestacados() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, resumoGrande(10, 40), true);
        var tabela = t.seguranca().tabelaDeLogins();
        assertEquals(40, tabela.total());
        assertEquals("mostrando 1-15 de 40", tabela.textoDoResumo());
        var pagina1 = tabela.linhasDaPagina();
        // os 10 de fora da rede (i % 4 == 0), todos por senha, vêm primeiro; depois os da LAN
        assertTrue(pagina1.subList(0, 10).stream().allMatch(l -> l.get(4).equals("fora da rede local, com senha")), pagina1.toString());
        assertTrue(pagina1.subList(10, 15).stream().allMatch(l -> l.get(4).equals("—")));
        assertEquals("deploy", pagina1.get(0).get(0));
        assertEquals("203.0.113.77:50000", pagina1.get(0).get(1), "o mais recente dos de atenção");
        assertEquals("203.0.113.77:50036", pagina1.get(9).get(1), "o mais antigo dos de atenção, ainda antes dos da LAN");
        assertTrue(pagina1.get(10).get(2).startsWith("chave ED25519 SHA256:"));
    }

    @Test
    void firewallEPortasLadoALadoESshEBloqueioNasSuasSubAbas() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, ModeloDeKpisTest.comAtaque(), true);
        String fw = Visual.naEdt(() -> t.seguranca().textoDoPainel(PaginaSeguranca.SUB_FIREWALL));
        assertTrue(fw.contains("nftables: bloqueia o que não tem regra"), fw);
        assertTrue(fw.contains("Porta 2222 (SSH): permitido só de 10.0.0.0/24"), fw);
        assertTrue(fw.contains("Porta 8080"), fw);
        assertTrue(fw.contains("sshd"), fw);
        String ssh = Visual.naEdt(() -> t.seguranca().textoDoPainel(PaginaSeguranca.SUB_SSH));
        assertTrue(ssh.contains("desligada: só entra quem tem a chave"), ssh);
        assertTrue(ssh.contains("fail2ban ativo: 2 IPs bloqueados agora (40 desde que começou)."), ssh);
        assertTrue(ssh.contains("203.0.113.7"), ssh);
    }

    @Test
    void segurancaSemPermissaoDizAFraseEmTodasAsSubAbasSemErroFeio() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        ResumoDeSeguranca sem = new ResumoDeSeguranca(AGORA, Leitura.semPermissao(), Leitura.indisponivel(), Leitura.semPermissao(),
            Leitura.semPermissao(), Leitura.indisponivel());
        alimentar(t, EstadoConexao.CONECTADO, sem, true);
        PaginaSeguranca p = t.seguranca();
        for (String id : new String[] {PaginaSeguranca.SUB_TENTATIVAS, PaginaSeguranca.SUB_ORIGENS, PaginaSeguranca.SUB_LOGINS}) {
            String s = Visual.naEdt(() -> p.textoDoPainel(id));
            assertTrue(s.contains("Não consegui ler o registro de segurança (precisa de sudo sem senha para o usuário)."), id + ": " + s);
        }
        assertTrue(Visual.naEdt(() -> p.textoDoPainel(PaginaSeguranca.SUB_FIREWALL)).contains("Sem permissão para ler esta parte"));
        assertFalse(Visual.naEdt(() -> p.textoDoPainel(PaginaSeguranca.SUB_SSH)).contains("null"));
        assertTrue(Visual.naEdt(p::textosDoResumo).get(0).contains("sem permissão"));
        var kpi = Visual.naEdt(() -> t.resumo().faixaDeKpis().cartoes().get(5));
        assertEquals("—", Visual.naEdt(kpi::textoDoValor));
        assertEquals("SEM DADO", Visual.naEdt(kpi::textoDoSelo));
    }

    @Test
    void semTentativasATabelaDizNenhumaTentativaNosUltimos7Dias() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, ModeloDeKpisTest.calmo(), true);
        assertTrue(Visual.naEdt(() -> t.seguranca().textoDoPainel(PaginaSeguranca.SUB_TENTATIVAS)).contains("Nenhuma tentativa de entrada nos últimos 7 dias."));
        assertTrue(Visual.naEdt(() -> t.seguranca().textosDoResumo()).get(0).contains("nenhuma tentativa"));
        assertTrue(Visual.naEdt(() -> t.seguranca().textoDoPainel(PaginaSeguranca.SUB_ORIGENS)).contains("Nenhuma origem"));
    }

    @Test
    void segurancaAntesDaPrimeiraLeituraMostraOEsqueleto() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        Visual.naEdt(() -> t.seguranca().aoMudarEstado(estado(EstadoConexao.CONECTADO)));
        assertTrue(Visual.naEdt(() -> t.seguranca().mostraEsqueleto()));
        assertFalse(Visual.naEdt(() -> t.seguranca().mostraCorpo()));
    }

    // ---------------------------------------------------------------------------------------------
    // Estados
    // ---------------------------------------------------------------------------------------------

    @Test
    void carregandoMostraOEsqueletoESemCorpo() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        Visual.naEdt(() -> t.resumo().aoMudarEstado(estado(EstadoConexao.CONECTADO)));
        assertEquals(Modo.CARREGANDO, t.resumo().situacao().modo());
        assertTrue(Visual.naEdt(() -> t.resumo().mostraEsqueleto()));
        assertFalse(Visual.naEdt(() -> t.resumo().mostraCorpo()));
        Visual.salvar(Visual.renderizar(t, 900, 500), "servidor-carregando");
    }

    @Test
    void semConexaoComDadosMostraOsUltimosDadosEsmaecidosComAHoraEmTodasAsAbas() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, ModeloDeKpisTest.calmo(), true);
        Visual.naEdt(() -> {
            for (Aba a : Aba.values()) {
                if (t.pagina(a) != null) {
                    t.pagina(a).aoMudarEstado(estado(EstadoConexao.RECONECTANDO));
                }
            }
        });
        for (Aba a : List.of(Aba.RESUMO, Aba.CONTAINERS, Aba.PROCESSOS, Aba.MAQUINA)) {
            var p = t.pagina(a);
            assertEquals(Modo.SEM_CONEXAO_COM_DADOS, p.situacao().modo(), a.name());
            assertEquals("Sem conexão com o servidor: mostrando os últimos dados de 17:59", Visual.naEdt(() -> p.faixaDeDados().textoDoTitulo()), a.name());
            assertTrue(Visual.naEdt(p::mostraCorpo), "os dados antigos continuam na tela");
            assertTrue(Visual.naEdt(p::corpoEsmaecido));
        }
        var seg = t.seguranca();
        assertEquals("Sem conexão com o servidor: mostrando os últimos dados de 18:00", Visual.naEdt(() -> seg.faixaDeDados().textoDoTitulo()));
        assertTrue(Visual.naEdt(seg::corpoEsmaecido));
        assertEquals("RECONECTANDO", Visual.naEdt(() -> t.resumo().cabecalho().textoDoEstado()));
        Visual.salvar(Visual.renderizar(t, 1224, Visual.alturaPreferida(t, 1224)), "servidor-sem-conexao");

        Visual.naEdt(() -> t.resumo().aoMudarEstado(estado(EstadoConexao.CONECTADO)));
        assertFalse(Visual.naEdt(() -> t.resumo().faixaDeDados().isVisible()), "voltou: o aviso some");
        assertFalse(Visual.naEdt(() -> t.resumo().corpoEsmaecido()));
    }

    @Test
    void semConexaoESemDadosMostraOCartaoDeSemDados() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        Visual.naEdt(() -> t.resumo().aoMudarEstado(estado(EstadoConexao.RECONECTANDO)));
        assertEquals(Modo.SEM_CONEXAO_SEM_DADOS, t.resumo().situacao().modo());
        var sem = Visual.naEdt(() -> t.resumo().semDados());
        assertTrue(Visual.naEdt(sem::isVisible));
        assertEquals("Sem conexão com o servidor", Visual.naEdt(sem::textoDoTitulo));
        assertFalse(Visual.naEdt(() -> t.resumo().mostraCorpo()));
    }

    @Test
    void agenteDesatualizadoExplicaQueFaltaOAgente() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        EstadoServidor e = new EstadoServidor("s1", "lab", EstadoConexao.SSH_OK_SEM_AGENTE,
            new Falha(Falha.Tipo.AGENTE_DESATUALIZADO, "404"), 0, null, null, 1, Estado2fa.DESLIGADO, "mac", null, null);
        Visual.naEdt(() -> t.resumo().aoMudarEstado(e));
        var sem = Visual.naEdt(() -> t.resumo().semDados());
        assertTrue(Visual.naEdt(sem::isVisible));
        assertEquals("O agente do painel não responde neste servidor", Visual.naEdt(sem::textoDoTitulo));
        assertTrue(Visual.naEdt(sem::textoDaExplicacao).contains("Falta publicar o painel no servidor"));
        assertEquals("AGENTE DESATUALIZADO", Visual.naEdt(() -> t.resumo().cabecalho().textoDoEstado()));
    }

    // ---------------------------------------------------------------------------------------------
    // PNGs
    // ---------------------------------------------------------------------------------------------

    private static void fotografar(Tema tema, Aba aba, String subAba, ResumoDeSeguranca seg, int larguraDaJanela, String nome) {
        Visual.tema(tema);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, seg, true);
        Visual.naEdt(() -> t.mostrarAba(aba));
        if (subAba != null) {
            Visual.naEdt(() -> t.seguranca().mostrarSubAba(subAba));
        }
        int largura = larguraDaJanela - BarraServidores.LARGURA;
        BufferedImage img = Visual.renderizar(t, largura, Visual.alturaPreferida(t, largura));
        Visual.salvar(img, nome);
    }

    @Test
    void geraOsPngsDasAbas() {
        ResumoDeSeguranca ataque = resumoGrande(60, 6);
        fotografar(Tema.ESCURO, Aba.RESUMO, null, ModeloDeKpisTest.comAtaque(), 1440, "servidor-resumo-escuro");
        fotografar(Tema.CLARO, Aba.RESUMO, null, ModeloDeKpisTest.calmo(), 1440, "servidor-resumo-claro");
        fotografar(Tema.ESCURO, Aba.RESUMO, null, ModeloDeKpisTest.comAtaque(), 1100, "servidor-resumo-escuro-1100");
        fotografar(Tema.CLARO, Aba.RESUMO, null, ModeloDeKpisTest.calmo(), 1100, "servidor-resumo-claro-1100");
        fotografar(Tema.CLARO, Aba.SEGURANCA, PaginaSeguranca.SUB_TENTATIVAS, ataque, 1440, "servidor-seguranca-tentativas");
        fotografar(Tema.ESCURO, Aba.SEGURANCA, PaginaSeguranca.SUB_TENTATIVAS, ataque, 1100, "servidor-seguranca-tentativas-escuro-1100");
        fotografar(Tema.CLARO, Aba.SEGURANCA, PaginaSeguranca.SUB_ORIGENS, ataque, 1440, "servidor-seguranca-origens");
        fotografar(Tema.CLARO, Aba.SEGURANCA, PaginaSeguranca.SUB_LOGINS, ataque, 1100, "servidor-seguranca-logins");
        fotografar(Tema.CLARO, Aba.SEGURANCA, PaginaSeguranca.SUB_FIREWALL, ataque, 1100, "servidor-seguranca-firewall");
        fotografar(Tema.CLARO, Aba.SEGURANCA, PaginaSeguranca.SUB_SSH, ataque, 1100, "servidor-seguranca-ssh");
        fotografar(Tema.CLARO, Aba.MAQUINA, null, ataque, 1440, "servidor-maquina");
        fotografar(Tema.ESCURO, Aba.MAQUINA, null, ataque, 1100, "servidor-maquina-escuro-1100");
        fotografar(Tema.CLARO, Aba.CONTAINERS, null, ataque, 1100, "servidor-containers");
        fotografar(Tema.CLARO, Aba.PROCESSOS, null, ataque, 1100, "servidor-processos");
    }
}
