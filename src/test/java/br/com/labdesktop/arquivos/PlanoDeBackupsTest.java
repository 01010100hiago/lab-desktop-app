package br.com.labdesktop.arquivos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.arquivos.PlanoDeBackups.Backup;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** O plano de baixar backups: caminho do servidor montado com nome validado, nome local seguro, soma, limite de aviso e espaço no disco. */
class PlanoDeBackupsTest {

    private static final String PROJETO = "/srv/lab/projects/app-loja";
    private static final Instant QUANDO = Instant.parse("2026-10-28T12:00:00Z");

    private static Backup b(String nome, long tamanho) {
        return new Backup(nome, tamanho, QUANDO);
    }

    @Test
    void montaOCaminhoDoServidorDentroDaPastaBackupsDoProjeto() {
        PlanoDeBackups p = PlanoDeBackups.de(PROJETO, List.of(b("db-1.sql.gz", 1000), b("db-2.sql.gz", 2000)));
        assertEquals(2, p.itens().size());
        assertEquals("/srv/lab/projects/app-loja/backups/db-1.sql.gz", p.entradas().get(0).caminho());
        assertEquals("db-1.sql.gz", p.entradas().get(0).nome(), "o nome local é o seguro");
        assertEquals(3000, p.bytes());
        assertTrue(p.recusados().isEmpty());
        assertFalse(p.grande());
        assertFalse(p.algumNomeMudou());
        assertTrue(p.entradas().stream().allMatch(e -> e.tipo() == EntradaRemota.Tipo.ARQUIVO && !e.link()), "só arquivo comum");
    }

    @Test
    void nomeQueEhCaminhoNaoEntraEFicaNaListaDeRecusados() {
        PlanoDeBackups p = PlanoDeBackups.de(PROJETO, List.of(b("../../etc/passwd", 10), b("..", 1), b("a/b.sql", 5), b("bom.sql", 7)));
        assertEquals(List.of("bom.sql"), p.itens().stream().map(PlanoDeBackups.Item::nomeLocal).toList());
        assertEquals(3, p.recusados().size());
        assertTrue(p.recusados().get(0).motivo().contains("não é um nome de arquivo seguro"), p.recusados().get(0).motivo());
        assertEquals(7, p.bytes());
    }

    @Test
    void caractereReservadoDoWindowsTrocaOMostraNoPlano() {
        PlanoDeBackups p = PlanoDeBackups.de(PROJETO, List.of(b("dump:12:00.sql", 100)));
        assertEquals("dump_12_00.sql", p.itens().get(0).nomeLocal());
        assertTrue(p.algumNomeMudou());
        assertEquals("/srv/lab/projects/app-loja/backups/dump:12:00.sql", p.entradas().get(0).caminho(), "no servidor o nome é o de lá");
    }

    @Test
    void doisNomesQueViramOMesmoNaoSaemJuntos() {
        // no Mac e no Windows um disco comum não distingue maiúscula de minúscula: dois nomes assim se sobrescreveriam
        PlanoDeBackups p = PlanoDeBackups.de(PROJETO, List.of(b("Backup.SQL", 1), b("backup.sql", 1), b("BACKUP.sql", 1)));
        assertEquals(1, p.itens().size());
        assertEquals(2, p.recusados().size());
        assertTrue(p.recusados().get(0).motivo().contains("mesmo nome"));
    }

    @Test
    void tamanhoDesconhecidoContaZeroEMantemMenosUm() {
        PlanoDeBackups p = PlanoDeBackups.de(PROJETO, List.of(new Backup("x.sql", null, QUANDO)));
        assertEquals(0, p.bytes());
        assertEquals(-1, p.entradas().get(0).tamanho());
    }

    @Test
    void acimaDe500MegasPedeConfirmacao() {
        assertFalse(PlanoDeBackups.de(PROJETO, List.of(b("a.sql", PlanoDeBackups.LIMITE_DE_AVISO))).grande());
        assertTrue(PlanoDeBackups.de(PROJETO, List.of(b("a.sql", PlanoDeBackups.LIMITE_DE_AVISO + 1))).grande());
        assertTrue(PlanoDeBackups.de(PROJETO, List.of(b("a.sql", 300L * 1024 * 1024), b("b.sql", 300L * 1024 * 1024))).grande(), "a soma é que conta");
    }

    @Test
    void semEspacoLivreDizQuantoFalta() {
        PlanoDeBackups p = PlanoDeBackups.de(PROJETO, List.of(b("a.sql", 200L * 1024 * 1024)));
        assertEquals(java.util.Optional.empty(), p.faltaDeEspaco(200L * 1024 * 1024 + PlanoDeBackups.FOLGA_DO_DISCO));
        String frase = p.faltaDeEspaco(100L * 1024 * 1024).orElseThrow();
        assertTrue(frase.startsWith("Não há espaço livre suficiente neste computador: faltam "), frase);
        assertTrue(frase.contains("164") || frase.contains("MB"), frase);
        assertTrue(frase.contains("Libere espaço ou escolha outro disco"));
    }

    @Test
    void oEspacoDaPastaDeVerdadeSePergunta(@org.junit.jupiter.api.io.TempDir java.nio.file.Path pasta) {
        assertEquals(java.util.Optional.empty(), PlanoDeBackups.de(PROJETO, List.of(b("a.sql", 1000))).faltaDeEspaco(pasta));
        assertTrue(PlanoDeBackups.de(PROJETO, List.of(b("a.sql", Long.MAX_VALUE / 4))).faltaDeEspaco(pasta).isPresent());
    }

    @Test
    void semNadaEscolhidoOPlanoEhVazio() {
        assertTrue(PlanoDeBackups.de(PROJETO, List.of()).vazio());
    }
}
