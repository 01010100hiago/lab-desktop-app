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
import br.com.labdesktop.servidor.ResumoDeSeguranca;
import br.com.labdesktop.servidor.ResumoDeSeguranca.Leitura;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.Falha;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.telas.ModeloDeKpisTest;
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

/** A página do servidor desenhada de verdade (sem janela): abas, blocos, estados e os PNGs em build/telas. */
@ExtendWith(VerificaEdt.class)
class TelaServidorTest {

    static final Instant AGORA = Instant.parse("2026-10-24T21:00:00Z");
    static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    static TelaServidor nova() {
        AvisosGravados avisos = new AvisosGravados();
        DialogosGravados dialogos = new DialogosGravados();
        return Visual.naEdt(() -> {
            TelaServidor t = new TelaServidor(null, TelaProjetosTest.nova(avisos, dialogos), () -> { });
            t.visaoGeral().relogio(() -> AGORA, SP);
            return t;
        });
    }

    static EstadoServidor estado(EstadoConexao c) {
        return TelaProjetosTest.estado(c);
    }

    /** Alimenta a visão geral como o Sondador faria, tudo na EDT. */
    static void alimentar(TelaServidor t, EstadoConexao conexao, ResumoDeSeguranca seguranca, boolean comDetalhe) {
        Visual.naEdt(() -> {
            VisaoGeralDoServidor v = t.visaoGeral();
            v.aoMudarEstado(estado(conexao));
            v.aoReceberServidor(FixturesDeApi.servidorSintetico());
            if (comDetalhe) {
                v.aoReceberDetalhe(FixturesDeApi.detalheSintetico());
            }
            if (seguranca != null) {
                v.aoReceberSeguranca(seguranca);
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
        return Visual.naEdt(() -> Textos.de(c));
    }

    // ---------------------------------------------------------------------------------------------
    // Abas
    // ---------------------------------------------------------------------------------------------

    @Test
    void abreNaVisaoGeralEAlternaComProjetosSemMudarATelaDeProjetos() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        assertEquals(Aba.VISAO_GERAL, t.abaAtual());
        assertEquals(Aba.VISAO_GERAL, Visual.naEdt(t::abaMarcada));
        assertTrue(Visual.naEdt(() -> t.visaoGeral().isVisible()));
        assertFalse(Visual.naEdt(() -> t.projetos().isVisible()));
        assertEquals(List.of("Visão geral", "Projetos"), List.of(Visual.naEdt(() -> t.abas().botao("visao-geral").getText()),
            Visual.naEdt(() -> t.abas().botao("projetos").getText())));

        Visual.naEdt(() -> t.abas().botao("projetos").doClick());
        assertEquals(Aba.PROJETOS, t.abaAtual());
        assertEquals(Aba.PROJETOS, Visual.naEdt(t::abaMarcada));
        assertTrue(Visual.naEdt(() -> t.projetos().isVisible()));
        assertFalse(Visual.naEdt(() -> t.visaoGeral().isVisible()));
        // a TelaProjetos é a de sempre: a mesma instância, com a lista e os filtros dela
        assertNotNull(Visual.naEdt(() -> t.projetos().botaoDeFiltro(ListagemDeProjetos.Filtro.TODOS)));

        Visual.naEdt(() -> t.abas().botao("visao-geral").doClick());
        assertEquals(Aba.VISAO_GERAL, t.abaAtual());
        assertTrue(Visual.naEdt(() -> t.visaoGeral().isVisible()));
    }

    @Test
    void mostrarAbaPorCodigoMarcaOTituloEMantemOEstado() {
        TelaServidor t = nova();
        Visual.naEdt(() -> t.mostrarAba(Aba.PROJETOS));
        assertEquals(Aba.PROJETOS, Visual.naEdt(t::abaMarcada));
        Visual.naEdt(() -> t.mostrarAba(Aba.PROJETOS));
        assertEquals(Aba.PROJETOS, t.abaAtual(), "repetir não muda nada");
    }

    // ---------------------------------------------------------------------------------------------
    // Conteúdo
    // ---------------------------------------------------------------------------------------------

    @Test
    void primeiraLinhaTemNoMaximoSeisIndicadoresEEsteTesteConta() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, ModeloDeKpisTest.comAtaque(), true);
        List<Component> kpis = Visual.naEdt(() -> todos(t.visaoGeral()).stream()
            .filter(c -> c.getName() != null && c.getName().startsWith("kpi-")).toList());
        assertEquals(6, kpis.size());
        assertTrue(kpis.size() <= 6);
        assertEquals(6, Visual.naEdt(() -> t.visaoGeral().faixaDeKpis().cartoes().size()));
        // nada de rede, carga, versões, Docker detalhado nem sessões na linha de cima
        String linha = texto(Visual.naEdt(() -> t.visaoGeral().faixaDeKpis()));
        for (String proibido : new String[] {"Descida", "Subida", "Carga", "Compose", "pnpm", "lab-claude"}) {
            assertFalse(linha.contains(proibido), proibido);
        }
        for (var c : Visual.naEdt(() -> t.visaoGeral().faixaDeKpis().cartoes())) {
            String selo = Visual.naEdt(c::textoDoSelo);
            assertTrue(List.of("OK", "ATENÇÃO", "CRÍTICO", "SEM DADO").contains(selo), "selo em texto: " + selo);
        }
    }

    @Test
    void cabecalhoMostraNomeEstadoMaquinaSistemaKernelETempoLigado() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, null, false);
        var c = Visual.naEdt(() -> t.visaoGeral().cabecalho());
        assertEquals("lab", Visual.naEdt(c::textoDoNome));
        assertEquals("CONECTADO", Visual.naEdt(c::textoDoEstado));
        String linha = Visual.naEdt(c::textoDaLinhaDeBaixo);
        assertTrue(linha.contains("Máquina servidor-exemplo"), linha);
        assertTrue(linha.contains("Sistema Debian GNU/Linux 13 (trixie)"), linha);
        assertTrue(linha.contains("Kernel 6.12.0-exemplo-amd64"), linha);
        assertTrue(linha.contains("Ligada há 3 h 57 min"), linha);
    }

    @Test
    void blocoDeContainersEDeSessoes() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, null, false);
        var b = Visual.naEdt(() -> t.visaoGeral().blocoDeContainers());
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
    void tendenciasTemUmGraficoPorSerieComOHistoricoDeSessentaPontos() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, null, false);
        var graficos = Visual.naEdt(() -> t.visaoGeral().blocoDeTendencias().graficos());
        assertEquals(5, graficos.size(), "CPU, memória, descida, subida e temperatura");
        assertEquals(60, graficos.get(0).quantidadeDePontos());
        assertEquals(60, graficos.get(1).quantidadeDePontos());
        assertEquals(60, graficos.get(2).quantidadeDePontos());
        assertEquals(60, graficos.get(3).quantidadeDePontos());
        assertEquals(57, graficos.get(4).quantidadeDePontos(), "a temperatura começa com 3 nulos: descartados");
        var legendas = Visual.naEdt(() -> t.visaoGeral().blocoDeTendencias().legendas());
        assertTrue(legendas.get(0).startsWith("agora "), legendas.get(0));
        assertTrue(legendas.get(0).contains("mín") && legendas.get(0).contains("máx"), legendas.get(0));
        assertTrue(texto(Visual.naEdt(() -> t.visaoGeral().blocoDeTendencias())).contains("últimos 10 min"));
        assertTrue(legendas.get(2).contains("kb/s"), "a rede em bits: " + legendas.get(2));
    }

    @Test
    void processosComDonoCpuMemoriaEProjetoVindoDoDetalheOuDoNomeDoContainer() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, null, false);
        var antes = Visual.naEdt(() -> t.visaoGeral().blocoDeProcessos().linhas());
        assertEquals(4, antes.size());
        assertEquals(List.of("claude", "labuser", "—", "403 MB", "alfa · lab-claude-alfa"), antes.get(0), "sem detalhe: deduz da sessão");
        assertEquals("gama-db", antes.get(2).get(4), "sem detalhe e sem prefixo da sessão: só o container");
        assertEquals("o próprio servidor (fora de container)", antes.get(3).get(4));

        Visual.naEdt(() -> t.visaoGeral().aoReceberDetalhe(FixturesDeApi.detalheSintetico()));
        var depois = Visual.naEdt(() -> t.visaoGeral().blocoDeProcessos().linhas());
        assertEquals("gama · gama-db", depois.get(2).get(4), "com o detalhe, o projeto vem do agente");
        assertEquals("12%", depois.get(1).get(2));
        assertEquals("root", depois.get(3).get(1));
    }

    @Test
    void detalhesDaMaquinaComecamFechadosEMostramVersoesSensoresEnergiaEHardware() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, null, true);
        var d = Visual.naEdt(() -> t.visaoGeral().detalhesDaMaquina());
        assertFalse(Visual.naEdt(() -> d.secao().aberta()), "recolhível e fechada de partida");
        Visual.naEdt(() -> d.secao().botao().doClick());
        assertTrue(Visual.naEdt(() -> d.secao().aberta()));

        var versoes = Visual.naEdt(d::camposDeVersoes);
        assertEquals("Painel (Node)", versoes.get(0).rotulo());
        assertEquals("24.1.0", versoes.get(0).valor());
        assertEquals("2.1.300", versoes.get(1).valor(), "Claude Code da imagem das sessões");
        assertEquals("22.1.0", versoes.get(2).valor());
        assertEquals("29.8.0", versoes.get(3).valor());
        assertEquals("5.6.0", versoes.get(4).valor());
        assertEquals("12.15.0", versoes.get(5).valor());

        var sensores = Visual.naEdt(d::linhasDeSensores);
        assertEquals(3, sensores.size());
        assertEquals(List.of("Processador (pacote) · coretemp", "62 °C", "87 °C", "105 °C"), sensores.get(1));
        assertEquals(List.of("Placa (ACPI) · acpitz", "54 °C", "—", "—"), sensores.get(0), "max e crit nulos viram traço");

        assertTrue(Visual.naEdt(d::textoDeEnergia).contains("Na tomada"));
        assertTrue(Visual.naEdt(d::textoDeEnergia).contains("Sem bateria"));
        var maquina = Visual.naEdt(d::camposDaMaquina);
        assertEquals("Fabricante Exemplo Modelo X1", maquina.get(0).valor());
        assertEquals("B123 · 05/17/2012", maquina.get(2).valor());
        assertEquals("8,0 GB", maquina.get(5).valor());
        String tudo = Visual.naEdt(d::textoCompleto);
        assertTrue(tudo.contains("Em 1 minuto") || tudo.contains("Carga em 1 minuto"), tudo);
        assertTrue(tudo.contains("Núcleo 1"), "a CPU por núcleo");
        assertTrue(tudo.contains("Descida (download)"));
        assertTrue(tudo.contains("5,7 kb/s"));
    }

    @Test
    void semODetalheOsSensoresMostramTracoOuAFraseDeFalha() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, null, false);
        var d = Visual.naEdt(() -> t.visaoGeral().detalhesDaMaquina());
        assertTrue(Visual.naEdt(d::textoCompleto).contains("Lendo os sensores"));
        assertTrue(Visual.naEdt(d::linhasDeSensores).isEmpty());
        // as versões e a CPU por núcleo não dependem do detalhe: vêm de /api/servidor
        assertEquals("24.1.0", Visual.naEdt(d::camposDeVersoes).get(0).valor());
    }

    @Test
    void campoAusenteViraTracoSemExcecaoEmTodosOsBlocos() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        Visual.naEdt(() -> {
            t.visaoGeral().aoMudarEstado(estado(EstadoConexao.CONECTADO));
            t.visaoGeral().aoReceberServidor(FixturesDeApi.de("{\"cpu\":{},\"memoria\":{\"swap\":{}},\"discos\":[{}],\"docker\":{},"
                + "\"processos\":[{}],\"sessoes\":[{}],\"historico\":{},\"rede\":{},\"node\":{}}", InfoServidor.class));
            t.visaoGeral().aoReceberDetalhe(FixturesDeApi.de("{\"sensores\":[{}],\"energia\":{},\"maquina\":{}}", DetalheServidor.class));
        });
        Visual.renderizar(t, 1100, Visual.alturaPreferida(t, 1100));
        assertEquals("—", Visual.naEdt(() -> t.visaoGeral().faixaDeKpis().cartoes().get(1).textoDoValor()));
        assertEquals(List.of("—", "—", "—", "—", "o próprio servidor (fora de container)"), Visual.naEdt(() -> t.visaoGeral().blocoDeProcessos().linhas().get(0)));
    }

    // ---------------------------------------------------------------------------------------------
    // Segurança
    // ---------------------------------------------------------------------------------------------

    @Test
    void blocoDeSegurancaComAtaqueMostraTentativasTiposOrigensELogins() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, ModeloDeKpisTest.comAtaque(), true);
        String s = texto(Visual.naEdt(() -> t.visaoGeral().blocoDeSeguranca()));
        assertTrue(s.contains("7 tentativas de entrada nas últimas 24 h."), s);
        assertTrue(s.contains("Nos últimos 7 dias: 8."), s);
        assertTrue(s.contains("Tentou usuários que não existem"), s);
        assertTrue(s.contains("Varredura ou protocolo estranho"), s);
        assertTrue(s.contains("203.0.113.5"), s);
        assertTrue(s.contains("4 tentativas"), s);
        assertTrue(s.contains("Alguém entrou de fora da rede local"), s);
        assertTrue(s.contains("de fora da rede local"), "o login de fora é destacado: " + s);
        assertTrue(s.contains("ED25519 SHA256:AbCdEf"), s);
        assertTrue(s.contains("Porta 2222 (SSH): permitido só de 10.0.0.0/24"), s);
        assertTrue(s.contains("nftables: bloqueia o que não tem regra"), s);
        assertTrue(s.contains("desligada: só entra quem tem a chave"), s);
        assertTrue(s.contains("fail2ban ativo: 2 IPs bloqueados agora (40 desde que começou)."), s);
        assertTrue(s.contains("sshd"), "processo da porta aberta");
        assertTrue(s.contains("somente leitura"), s);
    }

    @Test
    void blocoDeSegurancaSemAtaqueTemTomTranquiloEMostraOsLoginsLegitimos() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, ModeloDeKpisTest.calmo(), true);
        String s = texto(Visual.naEdt(() -> t.visaoGeral().blocoDeSeguranca()));
        assertTrue(s.contains("Nenhuma tentativa de entrada nas últimas 24 h."), s);
        assertFalse(s.contains("Como tentaram"), "sem tentativas não há quadro de tipos");
        assertTrue(s.toUpperCase().contains("LOGINS ACEITOS RECENTEMENTE"), s);
        assertTrue(s.contains("10.0.0.0:54321"), s);
    }

    @Test
    void blocoDeSegurancaSemPermissaoNaoMostraErroFeio() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        ResumoDeSeguranca sem = new ResumoDeSeguranca(AGORA, Leitura.semPermissao(), Leitura.indisponivel(), Leitura.semPermissao(),
            Leitura.semPermissao(), Leitura.indisponivel());
        alimentar(t, EstadoConexao.CONECTADO, sem, true);
        String s = texto(Visual.naEdt(() -> t.visaoGeral().blocoDeSeguranca()));
        assertTrue(s.contains("Não consegui ler o registro de segurança (precisa de sudo sem senha para o usuário)."), s);
        assertFalse(s.contains("Exception") || s.contains("null"), s);
        var kpi = Visual.naEdt(() -> t.visaoGeral().faixaDeKpis().cartoes().get(5));
        assertEquals("—", Visual.naEdt(kpi::textoDoValor));
        assertEquals("sem permissão para ler", Visual.naEdt(kpi::textoDaLegenda));
        assertEquals("SEM DADO", Visual.naEdt(kpi::textoDoSelo));
    }

    @Test
    void blocoDeSegurancaAntesDaPrimeiraLeituraDizQueEstaLendo() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, null, false);
        assertTrue(texto(Visual.naEdt(() -> t.visaoGeral().blocoDeSeguranca())).contains("Lendo o registro de segurança"));
    }

    // ---------------------------------------------------------------------------------------------
    // Estados
    // ---------------------------------------------------------------------------------------------

    @Test
    void carregandoMostraOEsqueletoESemCorpo() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        Visual.naEdt(() -> t.visaoGeral().aoMudarEstado(estado(EstadoConexao.CONECTADO)));
        assertEquals(Modo.CARREGANDO, t.visaoGeral().situacao().modo());
        assertTrue(Visual.naEdt(() -> t.visaoGeral().mostraEsqueleto()));
        assertFalse(Visual.naEdt(() -> t.visaoGeral().mostraCorpo()));
        Visual.salvar(Visual.renderizar(t, 1100, 600), "servidor-carregando");
    }

    @Test
    void semConexaoComDadosMostraOsUltimosDadosEsmaecidosComAHora() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, ModeloDeKpisTest.calmo(), true);
        Visual.naEdt(() -> t.visaoGeral().aoMudarEstado(estado(EstadoConexao.RECONECTANDO)));
        assertEquals(Modo.SEM_CONEXAO_COM_DADOS, t.visaoGeral().situacao().modo());
        var faixa = Visual.naEdt(() -> t.visaoGeral().faixaDeDados());
        assertTrue(Visual.naEdt(faixa::isVisible));
        assertEquals("Sem conexão com o servidor: mostrando os últimos dados de 17:59", Visual.naEdt(faixa::textoDoTitulo));
        assertTrue(Visual.naEdt(() -> t.visaoGeral().mostraCorpo()), "os dados antigos continuam na tela");
        assertTrue(Visual.naEdt(() -> t.visaoGeral().corpoEsmaecido()));
        assertEquals("RECONECTANDO", Visual.naEdt(() -> t.visaoGeral().cabecalho().textoDoEstado()));
        Visual.salvar(Visual.renderizar(t, 1440, Visual.alturaPreferida(t, 1440)), "servidor-sem-conexao");

        Visual.naEdt(() -> t.visaoGeral().aoMudarEstado(estado(EstadoConexao.CONECTADO)));
        assertFalse(Visual.naEdt(faixa::isVisible), "voltou: o aviso some");
        assertFalse(Visual.naEdt(() -> t.visaoGeral().corpoEsmaecido()));
    }

    @Test
    void semConexaoESemDadosMostraOCartaoDeSemDados() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        Visual.naEdt(() -> t.visaoGeral().aoMudarEstado(estado(EstadoConexao.RECONECTANDO)));
        assertEquals(Modo.SEM_CONEXAO_SEM_DADOS, t.visaoGeral().situacao().modo());
        var sem = Visual.naEdt(() -> t.visaoGeral().semDados());
        assertTrue(Visual.naEdt(sem::isVisible));
        assertEquals("Sem conexão com o servidor", Visual.naEdt(sem::textoDoTitulo));
        assertFalse(Visual.naEdt(() -> t.visaoGeral().mostraCorpo()));
        assertFalse(Visual.naEdt(() -> t.visaoGeral().mostraEsqueleto()));
    }

    @Test
    void agenteDesatualizadoExplicaQueFaltaOAgente() {
        Visual.tema(Tema.CLARO);
        TelaServidor t = nova();
        EstadoServidor e = new EstadoServidor("s1", "lab", EstadoConexao.SSH_OK_SEM_AGENTE,
            new Falha(Falha.Tipo.AGENTE_DESATUALIZADO, "404"), 0, null, null, 1, Estado2fa.DESLIGADO, "mac", null, null);
        Visual.naEdt(() -> t.visaoGeral().aoMudarEstado(e));
        var sem = Visual.naEdt(() -> t.visaoGeral().semDados());
        assertTrue(Visual.naEdt(sem::isVisible));
        assertEquals("O agente do painel não responde neste servidor", Visual.naEdt(sem::textoDoTitulo));
        assertTrue(Visual.naEdt(sem::textoDaExplicacao).contains("Falta publicar o painel no servidor"));
        assertEquals("AGENTE DESATUALIZADO", Visual.naEdt(() -> t.visaoGeral().cabecalho().textoDoEstado()));
    }

    // ---------------------------------------------------------------------------------------------
    // PNGs
    // ---------------------------------------------------------------------------------------------

    private static void fotografar(Tema tema, ResumoDeSeguranca seg, boolean detalhesAbertos, int largura, String nome) {
        Visual.tema(tema);
        TelaServidor t = nova();
        alimentar(t, EstadoConexao.CONECTADO, seg, true);
        Visual.naEdt(() -> t.visaoGeral().detalhesDaMaquina().secao().definirAberta(detalhesAbertos));
        BufferedImage img = Visual.renderizar(t, largura, Visual.alturaPreferida(t, largura));
        Visual.salvar(img, nome);
    }

    @Test
    void geraOsPngsDaVisaoGeral() {
        fotografar(Tema.ESCURO, ModeloDeKpisTest.comAtaque(), false, 1440, "servidor-visao-geral-escuro");
        fotografar(Tema.CLARO, ModeloDeKpisTest.calmo(), true, 1440, "servidor-visao-geral-claro");
        fotografar(Tema.ESCURO, ModeloDeKpisTest.calmo(), false, 1440, "servidor-visao-geral-calmo-escuro");
        fotografar(Tema.CLARO, ModeloDeKpisTest.comAtaque(), true, 1440, "servidor-visao-geral-ataque-claro");
        fotografar(Tema.ESCURO, ModeloDeKpisTest.comAtaque(), false, 1100, "servidor-visao-geral-escuro-1100");
        fotografar(Tema.CLARO, ModeloDeKpisTest.calmo(), true, 1100, "servidor-visao-geral-claro-1100");
    }
}
