package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.FixturesDeApi;
import br.com.labdesktop.api.modelo.DetalheProjeto;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ui.BarraServidores;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.ui.telas.TelaProjeto.Aba;
import br.com.labdesktop.ui.telas.TelaProjetoTest.Cena;
import br.com.labdesktop.util.Preferencias.Tema;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** A aba de lista do projeto, Backups: tabela paginada, somente leitura, com os vazios em texto (a aba Arquivos é o gerenciador de arquivos). */
@ExtendWith(VerificaEdt.class)
class PaginasDeListasTest {

    private static Cena cena(Aba aba, DetalheProjeto d) {
        Visual.tema(Tema.CLARO);
        return new Cena(true, aba).com(EstadoConexao.CONECTADO, d);
    }

    // ---------------------------------------------------------------------------------------------
    // Backups
    // ---------------------------------------------------------------------------------------------

    @Test
    void backupsMostramArquivoTamanhoEQuandoDoMaisRecenteAoMaisAntigo() {
        PaginaBackups p = cena(Aba.BACKUPS, FixturesDeApi.detalheDoProjetoSintetico()).tela.backups();
        var t = Visual.naEdt(p::tabela);
        assertEquals(5, Visual.naEdt(() -> t.linhasDaPagina().size()));
        assertEquals(List.of("orbita-2026-10-24T03-00-01.sql.gz", "42 KB", "24/10 00:00 (há 18 h)"), Visual.naEdt(() -> t.linhasDaPagina().get(0)));
        assertEquals("orbita-2026-10-20T03-00-01.sql.gz", Visual.naEdt(() -> t.linhasDaPagina().get(4).get(0)));
        assertEquals("5 backups", Visual.naEdt(p::textoDoApoio));
        assertEquals("Backups (5)", Visual.naEdt(() -> p.ctx.detalhe == null ? "" : "Backups (" + p.ctx.detalhe.backups().size() + ")"));
    }

    @Test
    void backupsVaziosExplicamDeOndeElesVem() {
        DetalheProjeto d = TelaProjetoTest.detalheCom("\"backups\": [", "\"backups\": [], \"backups_antigos\": [");
        Cena c = cena(Aba.BACKUPS, d);
        PaginaBackups p = c.tela.backups();
        assertEquals(0, Visual.naEdt(() -> p.tabela().linhasDaPagina().size()));
        assertTrue(Visual.naEdt(() -> TelaProjetoTest.textos(p.tabela())).contains("Nenhum backup ainda."));
        assertTrue(Visual.naEdt(() -> TelaProjetoTest.textos(p.tabela())).contains("pg_dump"));
        assertEquals("Backups (0)", Visual.naEdt(() -> c.tela.abas().botao("backups").getText()));
        assertEquals("", Visual.naEdt(p::textoDoApoio));
    }

    @Test
    void muitosBackupsSaoPaginadosEm10() {
        StringBuilder sb = new StringBuilder("\"backups\": [");
        for (int i = 1; i <= 25; i++) {
            sb.append(i > 1 ? "," : "").append("{\"arquivo\":\"b").append(i).append(".sql.gz\",\"tamanho\":1000,\"quando\":\"2026-10-01T03:00:00.000Z\"}");
        }
        sb.append("], \"backups_antigos\": [");
        DetalheProjeto d = TelaProjetoTest.detalheCom("\"backups\": [", sb.toString());
        PaginaBackups p = cena(Aba.BACKUPS, d).tela.backups();
        var t = Visual.naEdt(p::tabela);
        assertEquals(10, Visual.naEdt(() -> t.linhasDaPagina().size()));
        assertEquals("mostrando 1-10 de 25", Visual.naEdt(t::textoDoResumo));
        assertEquals(3, Visual.naEdt(t::totalDePaginas));
    }

    @Test
    void abaDeBackupsCabeEm1100x700() {
        for (Aba aba : new Aba[] {Aba.BACKUPS}) {
            Cena c = cena(aba, FixturesDeApi.detalheDoProjetoSintetico());
            int pedida = Visual.alturaPreferida(c.tela, 1100 - BarraServidores.LARGURA);
            assertTrue(pedida <= 672, aba + " pede " + pedida + " px");
            Visual.renderizar(c.tela, 1100 - BarraServidores.LARGURA, 672);
            assertFalse(Visual.naEdt(() -> c.tela.pagina(aba).rolagem().getVerticalScrollBar().isVisible()), aba.toString());
        }
    }
}
