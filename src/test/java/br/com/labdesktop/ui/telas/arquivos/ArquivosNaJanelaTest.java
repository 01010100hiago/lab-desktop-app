package br.com.labdesktop.ui.telas.arquivos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.Espera;
import br.com.labdesktop.arquivos.CenarioDeSftp;
import br.com.labdesktop.arquivos.FilaDeTransferencias.Estado;
import br.com.labdesktop.arquivos.FilaDeTransferencias.Retrato;
import br.com.labdesktop.arquivos.FilaDeTransferencias.Tipo;
import br.com.labdesktop.arquivos.MemoriaDeCaminhos;
import br.com.labdesktop.arquivos.ServicosDeArquivos;
import br.com.labdesktop.teste.CofreFalso;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.BarraServidores;
import br.com.labdesktop.ui.CenarioDeUi;
import br.com.labdesktop.ui.DialogosGravados;
import br.com.labdesktop.ui.NavegadorGravado;
import br.com.labdesktop.ui.PainelPrincipal;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.dialogos.Dialogo;
import br.com.labdesktop.ui.dialogos.DialogoApagarArquivos;
import br.com.labdesktop.ui.dialogos.DialogoConflitoDeTransferencia;
import br.com.labdesktop.ui.dialogos.DialogoNome;
import br.com.labdesktop.ui.telas.FluxosDeProjeto;
import br.com.labdesktop.ui.telas.TelaProjeto;
import br.com.labdesktop.ui.telas.TelaServidor;
import br.com.labdesktop.ui.telas.TelaServidor.Aba;
import br.com.labdesktop.ui.telas.arquivos.GerenciadorDeArquivos.Situacao;
import br.com.labdesktop.util.Preferencias.Tema;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.JViewport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/**
 * O gerenciador DENTRO da janela de verdade: a aba Arquivos da página do servidor e a aba Arquivos da página do projeto são o
 * MESMO componente (cada um no seu modo), com servidor vivo (SSH embutido com SFTP). Também gera os PNGs de {@code build/telas}
 * e confere que nada da tela fica cortado em 1100 x 700.
 */
@ExtendWith(VerificaEdt.class)
class ArquivosNaJanelaTest {

    private static final String O = CenarioDeSftp.ORBITA;

    @TempDir
    Path tmp;
    private CenarioDeUi cenario;
    private Path raizDoServidor;
    private final AvisosGravados avisos = new AvisosGravados();
    private final DialogosGravados dialogos = new DialogosGravados();
    private PainelPrincipal painel;
    private String acoesAntes;

    @BeforeEach
    void montar() throws Exception {
        acoesAntes = System.getProperty(FluxosDeProjeto.PROPRIEDADE_ACOES);
        System.setProperty(FluxosDeProjeto.PROPRIEDADE_ACOES, "true");
    }

    private void abrirJanela(Tema tema) throws Exception {
        Visual.tema(tema);
        cenario = new CenarioDeUi();
        raizDoServidor = tmp.resolve("servidor");
        Files.createDirectories(raizDoServidor);
        // o mesmo servidor SSH do resto do mundo de teste ganha o SFTP sobre uma pasta com a forma do lab
        cenario.ssh.comSftp(raizDoServidor.toRealPath());
        Files.createDirectories(raizDoServidor.resolve("srv/lab/projects/orbita"));
        CenarioDeSftpAjuda.popular(raizDoServidor);
        ServicosDeArquivos servicos = new ServicosDeArquivos(MemoriaDeCaminhos.emMemoria(), tmp.resolve("backups"));
        painel = Visual.naEdt(() -> new PainelPrincipal(cenario.gerenciador, null, avisos, dialogos, new NavegadorGravado(avisos), servicos));
    }

    @AfterEach
    void desmontar() throws Exception {
        if (painel != null) {
            Visual.naEdt(() -> painel.encerrar());
        }
        if (cenario != null) {
            cenario.close();
        }
        if (acoesAntes == null) {
            System.clearProperty(FluxosDeProjeto.PROPRIEDADE_ACOES);
        } else {
            System.setProperty(FluxosDeProjeto.PROPRIEDADE_ACOES, acoesAntes);
        }
    }

    private TelaServidor servidor() {
        return Visual.naEdt(() -> painel.navegacao().telaServidorDe(CofreFalso.ID));
    }

    private void esperarNormal(GerenciadorDeArquivos g) {
        Espera.ate(Duration.ofSeconds(30), () -> "situação=" + Visual.naEdt(g::situacao) + " em voo=" + g.emVoo() + " " + Visual.naEdt(g::caminhoAtual),
            () -> Visual.naEdt(g::situacao) == Situacao.NORMAL && g.emVoo() == 0);
    }

    /** Mostra a aba Arquivos do servidor e começa a ler (a janela de verdade faria isso ao ficar visível). */
    private GerenciadorDeArquivos abaDoServidor() {
        Visual.naEdt(() -> painel.mostrarArquivos());
        GerenciadorDeArquivos g = Visual.naEdt(() -> servidor().arquivos());
        Visual.naEdt(g::iniciar);
        esperarNormal(g);
        return g;
    }

    private GerenciadorDeArquivos abaDoProjeto() {
        Visual.naEdt(() -> painel.navegacao().abrirProjeto(cenario.lab, "orbita"));
        TelaProjeto t = Visual.naEdt(() -> servidor().projetoAberto());
        Visual.naEdt(() -> t.mostrarAba(TelaProjeto.Aba.ARQUIVOS));
        GerenciadorDeArquivos g = Visual.naEdt(t::arquivos);
        Visual.naEdt(g::iniciar);
        esperarNormal(g);
        return g;
    }

    // ---------------------------------------------------------------------------------------------
    // A aba existe, e é o mesmo componente
    // ---------------------------------------------------------------------------------------------

    @Test
    void aPaginaDoServidorTemAAbaArquivosAoLadoDeMaquinaEProjetos() throws Exception {
        abrirJanela(Tema.CLARO);
        TelaServidor t = servidor();
        assertEquals(List.of("Resumo", "Segurança", "Containers", "Processos", "Máquina", "Projetos", "Arquivos", "Terminal", "Cloudflare"),
            java.util.Arrays.stream(Aba.values()).map(a -> Visual.naEdt(() -> t.abas().botao(a.id()).getText())).toList());
        Visual.naEdt(() -> t.abas().botao(Aba.ARQUIVOS.id()).doClick());
        assertEquals(Aba.ARQUIVOS, Visual.naEdt(t::abaAtual));
        assertTrue(Visual.naEdt(() -> t.arquivos().isVisible()));
        // o atalho do menu Visualizar
        Visual.naEdt(() -> t.mostrarAba(Aba.RESUMO));
        Visual.naEdt(painel::mostrarArquivos);
        assertEquals(Aba.ARQUIVOS, Visual.naEdt(t::abaAtual));
    }

    @Test
    void aAbaDoServidorEAAbaDoProjetoSaoOMesmoComponenteEmDoisModos() throws Exception {
        abrirJanela(Tema.CLARO);
        GerenciadorDeArquivos doServidor = abaDoServidor();
        GerenciadorDeArquivos doProjeto = abaDoProjeto();
        assertSame(GerenciadorDeArquivos.class, doServidor.getClass());
        assertSame(GerenciadorDeArquivos.class, doProjeto.getClass());
        assertFalse(doServidor == doProjeto);
        assertEquals("/srv/lab/projects", Visual.naEdt(() -> doServidor.escopo().raiz()));
        assertEquals(O, Visual.naEdt(() -> doProjeto.escopo().raiz()), "no projeto a raiz é a pasta do projeto");
        assertTrue(Visual.naEdt(() -> doServidor.botao("raiz").isVisible()), "o servidor inteiro tem o seletor de raiz");
        assertFalse(Visual.naEdt(() -> doProjeto.botao("raiz").isVisible()), "o projeto não tem");
        assertTrue(Visual.naEdt(() -> doServidor.botao("enviar").isVisible()), "as ações de escrita estão ligadas neste teste");
    }

    @Test
    void semAPropriedadeDeAcoesAsDuasAbasSoLeem() throws Exception {
        System.setProperty(FluxosDeProjeto.PROPRIEDADE_ACOES, "false");
        abrirJanela(Tema.CLARO);
        GerenciadorDeArquivos doServidor = abaDoServidor();
        GerenciadorDeArquivos doProjeto = abaDoProjeto();
        for (GerenciadorDeArquivos g : List.of(doServidor, doProjeto)) {
            assertTrue(Visual.naEdt(() -> g.avisoDeSomenteLeitura().isVisible()));
            assertFalse(Visual.naEdt(() -> g.botao("apagar").isVisible()));
            assertFalse(Visual.naEdt(() -> g.botao("enviar").isVisible()));
            assertTrue(Visual.naEdt(() -> g.botao("baixar").isVisible()));
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Esc, e sair do projeto com edição no ar
    // ---------------------------------------------------------------------------------------------

    @Test
    void deixarOProjetoComUmEditorSujoGuardaAPaginaEVoltarOsDevolve() throws Exception {
        abrirJanela(Tema.CLARO);
        GerenciadorDeArquivos g = abaDoProjeto();
        TelaProjeto pagina = Visual.naEdt(() -> servidor().projetoAberto());
        Visual.naEdt(() -> g.abrirEntrada(g.entrada("README.md").orElseThrow()));
        Espera.ate(Duration.ofSeconds(30), "editor", () -> Visual.naEdt(() -> g.areaDoEditor(O + "/README.md")) != null);
        var area = Visual.naEdt(() -> g.areaDoEditor(O + "/README.md"));
        Visual.naEdt(() -> area.setText(area.getText() + "\nnão salvei ainda\n"));
        assertTrue(Visual.naEdt(g::temAlteracoesNaoSalvas));

        Visual.naEdt(() -> servidor().voltarParaALista());
        assertTrue(Visual.naEdt(() -> servidor().projetoAberto() == null));
        assertTrue(avisos.disse("atencao", "Guardei as alterações não salvas em orbita"));
        Visual.naEdt(() -> painel.navegacao().abrirProjeto(cenario.lab, "orbita"));
        assertSame(pagina, Visual.naEdt(() -> servidor().projetoAberto()), "a mesma página, com o editor onde estava");
        assertTrue(Visual.naEdt(() -> pagina.arquivos().areaDoEditor(O + "/README.md").getText()).endsWith("não salvei ainda\n"));
        assertTrue(Visual.naEdt(() -> pagina.arquivos().temAlteracoesNaoSalvas()));
    }

    @Test
    void deixarOProjetoSemEdicaoFechaAPaginaDeVerdade() throws Exception {
        abrirJanela(Tema.CLARO);
        abaDoProjeto();
        TelaProjeto pagina = Visual.naEdt(() -> servidor().projetoAberto());
        Visual.naEdt(() -> servidor().voltarParaALista());
        assertTrue(Visual.naEdt(pagina.arquivos()::encerrado), "o canal SFTP e as inscrições foram soltos");
        Visual.naEdt(() -> painel.navegacao().abrirProjeto(cenario.lab, "orbita"));
        assertFalse(pagina == Visual.naEdt(() -> servidor().projetoAberto()), "uma página nova");
    }

    // ---------------------------------------------------------------------------------------------
    // Cabe em 1100 x 700, sem cortes
    // ---------------------------------------------------------------------------------------------

    /** Os componentes visíveis (fora do que rola) que passam da borda da raiz, ou botões que não cabem no próprio texto. */
    private static List<String> cortes(Component raiz) {
        List<String> r = new ArrayList<>();
        Visual.naEdt(() -> coletarCortes(raiz, raiz, r));
        return r;
    }

    private static void coletarCortes(Component raiz, Component c, List<String> r) {
        if (!c.isVisible()) {
            return;
        }
        Rectangle b = javax.swing.SwingUtilities.convertRectangle(c.getParent() == null ? c : c.getParent(), c.getBounds(), raiz);
        if (c != raiz && (b.x < -1 || b.x + b.width > raiz.getWidth() + 1) && !(c.getParent() instanceof JViewport)) {
            r.add("passa da borda lateral: " + c.getClass().getSimpleName() + " " + b);
        }
        if (c instanceof AbstractButton ab && ab.getText() != null && !ab.getText().isEmpty() && ab.getWidth() > 0
            && ab.getWidth() + 1 < ab.getPreferredSize().width && !(c.getParent() instanceof JViewport)) {
            r.add("botão cortado: '" + ab.getText() + "' tem " + ab.getWidth() + " e pede " + ab.getPreferredSize().width);
        }
        if (c instanceof JViewport) {
            return; // o que está dentro de uma área rolável pode passar
        }
        if (c instanceof Container k) {
            for (Component f : k.getComponents()) {
                coletarCortes(raiz, f, r);
            }
        }
    }

    @Test
    void nada_fica_cortado_na_aba_do_servidor_e_na_do_projeto_em_1100x700() throws Exception {
        abrirJanela(Tema.CLARO);
        GerenciadorDeArquivos g = abaDoServidor();
        int largura = 1100 - BarraServidores.LARGURA;
        Visual.renderizar(g, largura, 700 - 28 - 38);
        assertEquals(List.of(), cortes(g));
        Visual.naEdt(() -> g.irPara(O));
        Espera.ate(Duration.ofSeconds(30), "orbita", () -> O.equals(Visual.naEdt(g::caminhoAtual)) && Visual.naEdt(g::situacao) == Situacao.NORMAL);
        Visual.renderizar(g, largura, 700 - 28 - 38);
        assertEquals(List.of(), cortes(g));
        Visual.naEdt(() -> g.mostrarOcultos(true));
        Visual.renderizar(g, largura, 700 - 28 - 38);
        assertEquals(List.of(), cortes(g));
    }

    // ---------------------------------------------------------------------------------------------
    // PNGs
    // ---------------------------------------------------------------------------------------------

    private BufferedImage janela(int largura, int altura) {
        return Visual.renderizar(painel, largura, altura - 28);
    }

    @Test
    void geraOsPngsDaJanela() throws Exception {
        abrirJanela(Tema.ESCURO);
        GerenciadorDeArquivos g = abaDoServidor();
        Visual.naEdt(() -> g.irPara(O));
        Espera.ate(Duration.ofSeconds(30), "orbita", () -> O.equals(Visual.naEdt(g::caminhoAtual)) && Visual.naEdt(g::situacao) == Situacao.NORMAL);
        Espera.ate(Duration.ofSeconds(30), "árvore", () -> Visual.naEdt(() -> g.arvore().caminhosCarregados().contains("/srv/lab/projects")));
        Visual.naEdt(() -> g.selecionarNaTabela("docker-compose.yml"));
        Visual.salvar(janela(1440, 860), "arquivos-servidor-escuro");
        Visual.salvar(janela(1100, 700), "arquivos-servidor-escuro-1100");
        Visual.naEdt(() -> painel.encerrar());
        cenario.close();
        cenario = null;

        abrirJanela(Tema.CLARO);
        GerenciadorDeArquivos g2 = abaDoServidor();
        Visual.naEdt(() -> g2.irPara(O));
        Espera.ate(Duration.ofSeconds(30), "orbita", () -> O.equals(Visual.naEdt(g2::caminhoAtual)) && Visual.naEdt(g2::situacao) == Situacao.NORMAL);
        Espera.ate(Duration.ofSeconds(30), "árvore", () -> Visual.naEdt(() -> g2.arvore().caminhosCarregados().contains("/srv/lab/projects")));
        Visual.naEdt(() -> g2.selecionarNaTabela("README.md"));
        Visual.salvar(janela(1440, 860), "arquivos-servidor-claro");
        Visual.salvar(janela(1100, 700), "arquivos-servidor-claro-1100");
    }

    @Test
    void geraOsPngsDoProjetoDoEditorEDasTransferencias() throws Exception {
        abrirJanela(Tema.ESCURO);
        GerenciadorDeArquivos g = abaDoProjeto();
        Visual.naEdt(() -> g.mostrarOcultos(true));
        Espera.ate(Duration.ofSeconds(30), "árvore", () -> Visual.naEdt(() -> g.arvore().caminhosCarregados().contains(O)));
        Visual.salvar(janela(1100, 700), "arquivos-projeto-escuro-1100");
        Visual.salvar(janela(1440, 860), "arquivos-projeto-escuro");

        // o editor, com uma alteração no ar
        Visual.naEdt(() -> g.abrirEntrada(g.entrada("docker-compose.yml").orElseThrow()));
        Espera.ate(Duration.ofSeconds(30), "editor", () -> Visual.naEdt(() -> g.areaDoEditor(O + "/docker-compose.yml")) != null);
        var area = Visual.naEdt(() -> g.areaDoEditor(O + "/docker-compose.yml"));
        Visual.naEdt(() -> area.setText(area.getText() + "    mem_limit: 512m\n"));
        Visual.salvar(janela(1100, 700), "arquivos-editor");

        // as transferências (retratos montados à mão: andando, na fila, esperando, concluída, falhou)
        Visual.naEdt(() -> g.abas().selecionar("navegador"));
        Visual.naEdt(() -> g.painelDeTransferencias().definir(List.of(
            new Retrato(1, Tipo.ENVIO, "orbita-2026-10.zip para orbita", Estado.EM_ANDAMENTO, 37_400_000, 98_000_000, 2_400_000,
                Duration.ofSeconds(25), 0, 1, 0, 0, "orbita-2026-10.zip", ""),
            new Retrato(2, Tipo.DOWNLOAD, "3 itens para Downloads", Estado.ESPERANDO_VOCE, 1_000_000, 5_000_000, 0, null, 1, 3, 0, 0, "logs.tar.gz", ""),
            new Retrato(3, Tipo.ENVIO, "docs para orbita", Estado.CONCLUIDA, 120_000, 120_000, 0, null, 6, 6, 0, 0, "", "6 arquivos enviados"),
            new Retrato(4, Tipo.ENVIO, "banco.sql para orbita", Estado.FALHOU, 3_000_000, 90_000_000, 0, null, 0, 1, 0, 1, "",
                "O servidor desconectou no meio. Envie ou baixe de novo (a retomada ainda não existe)."))));
        Visual.salvar(janela(1100, 700), "arquivos-transferencias");
        Visual.salvar(janela(1440, 860), "arquivos-transferencias-1440");
    }

    private static BufferedImage dialogo(Dialogo d, int largura) {
        int altura = Visual.alturaPreferida(d.raiz(), largura);
        return Visual.renderizar(d.raiz(), largura, altura);
    }

    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "depende de permissões POSIX ou do shell do Unix; no Windows valem ProtecaoAcl e as provas do Windows")
    @Test
    void geraOsPngsDosDialogosEDosEstados() throws Exception {
        abrirJanela(Tema.CLARO);
        GerenciadorDeArquivos g = abaDoProjeto();
        Visual.naEdt(() -> g.selecionarNaTabela("src"));
        Visual.naEdt(() -> g.botao("apagar").doClick(0));
        // o diálogo de apagar chega depois da contagem do que há dentro da pasta
        Espera.ate(Duration.ofSeconds(30), "diálogo de apagar", () -> Visual.naEdt(() -> dialogos.ultimo()) instanceof DialogoApagarArquivos);
        Visual.salvar(dialogo(Visual.naEdt(() -> dialogos.ultimo()), 520), "arquivos-apagar-confirmacao");

        // já existe: um envio de um arquivo que o servidor já tem
        Path local = Files.writeString(tmp.resolve("README.md"), "versão do computador, com mais coisa dentro\n".repeat(30));
        Visual.naEdt(() -> g.enviarArquivosLocais(List.of(local), O));
        Espera.ate(Duration.ofSeconds(30), "diálogo de conflito", () -> Visual.naEdt(() -> dialogos.ultimo()) instanceof DialogoConflitoDeTransferencia);
        var conflito = (DialogoConflitoDeTransferencia) Visual.naEdt(() -> dialogos.ultimo());
        Visual.salvar(dialogo(conflito, 560), "arquivos-conflito");
        Visual.naEdt(() -> conflito.botaoCancelar().doClick(0));

        // sem permissão para abrir uma pasta
        Path fechada = Files.createDirectories(raizDoServidor.resolve("srv/lab/projects/orbita/privada"));
        Files.setPosixFilePermissions(fechada, PosixFilePermissions.fromString("---------"));
        try {
            Visual.naEdt(() -> g.atualizar());
            Espera.ate(Duration.ofSeconds(30), "privada listada", () -> Visual.naEdt(() -> g.entrada("privada").isPresent()));
            Visual.naEdt(() -> g.irPara(O + "/privada"));
            Espera.ate(Duration.ofSeconds(30), "sem permissão", () -> Visual.naEdt(g::situacao) == Situacao.SEM_PERMISSAO);
            Visual.salvar(janela(1100, 700), "arquivos-sem-permissao");
            Visual.salvar(janela(1440, 860), "arquivos-sem-permissao-1440");
        } finally {
            Files.setPosixFilePermissions(fechada, PosixFilePermissions.fromString("rwx------"));
        }

        // nova pasta
        Visual.naEdt(() -> g.irPara(O));
        Espera.ate(Duration.ofSeconds(30), "orbita", () -> O.equals(Visual.naEdt(g::caminhoAtual)) && Visual.naEdt(g::situacao) == Situacao.NORMAL);
        Visual.naEdt(() -> g.botao("nova-pasta").doClick(0));
        var nome = (DialogoNome) Visual.naEdt(() -> dialogos.ultimo());
        Visual.naEdt(() -> nome.campo().setText("relatórios/outubro"));
        Visual.salvar(dialogo(nome, 520), "arquivos-nome-invalido");
    }
}
