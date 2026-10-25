package br.com.labdesktop.arquivos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.arquivos.EntradaRemota.Tipo;
import br.com.labdesktop.arquivos.VisaoDaPasta.Coluna;
import br.com.labdesktop.arquivos.VisaoDaPasta.Ordem;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Ordenação, filtro e ocultos: o que a tabela mostra de uma pasta. */
class VisaoDaPastaTest {

    private static EntradaRemota e(String nome, Tipo tipo, long tamanho, String data) {
        return new EntradaRemota(nome, "/x/" + nome, tipo, false, tamanho, Instant.parse(data), 0644, "ana", "dev", null);
    }

    private static final List<EntradaRemota> PASTA = List.of(
        e("zeta.txt", Tipo.ARQUIVO, 10, "2026-10-01T00:00:00Z"),
        e("Árvore", Tipo.PASTA, 0, "2026-10-02T00:00:00Z"),
        e(".env", Tipo.ARQUIVO, 5, "2026-10-03T00:00:00Z"),
        e("arq10.log", Tipo.ARQUIVO, 300, "2026-10-04T00:00:00Z"),
        e("arq2.log", Tipo.ARQUIVO, 20, "2026-10-05T00:00:00Z"),
        e("beta", Tipo.PASTA, 0, "2026-10-06T00:00:00Z"),
        e(".git", Tipo.PASTA, 0, "2026-10-07T00:00:00Z"));

    private static List<String> nomes(List<EntradaRemota> l) {
        return l.stream().map(EntradaRemota::nome).toList();
    }

    @Test
    void pastasPrimeiroOcultosEscondidosENomeDoJeitoHumano() {
        assertEquals(List.of("Árvore", "beta", "arq2.log", "arq10.log", "zeta.txt"),
            nomes(VisaoDaPasta.montar(PASTA, "", false, Ordem.PADRAO)), "arq2 antes de arq10; acento e maiúscula não mandam");
    }

    @Test
    void mostrarOcultosTrazOsQueComecamComPonto() {
        assertEquals(List.of(".git", "Árvore", "beta", ".env", "arq2.log", "arq10.log", "zeta.txt"),
            nomes(VisaoDaPasta.montar(PASTA, "", true, Ordem.PADRAO)));
    }

    @Test
    void filtroIgnoraAcentoEMaiusculaEPegaQualquerPedaco() {
        assertEquals(List.of("Árvore"), nomes(VisaoDaPasta.montar(PASTA, "arvore", false, Ordem.PADRAO)));
        assertEquals(List.of("arq2.log", "arq10.log"), nomes(VisaoDaPasta.montar(PASTA, "  LOG ", false, Ordem.PADRAO)));
        assertEquals(List.of(), nomes(VisaoDaPasta.montar(PASTA, "nao-existe", false, Ordem.PADRAO)));
        assertEquals(List.of(".env"), nomes(VisaoDaPasta.montar(PASTA, "env", true, Ordem.PADRAO)));
        assertEquals(List.of(), nomes(VisaoDaPasta.montar(PASTA, "env", false, Ordem.PADRAO)), "oculto fica escondido mesmo se o filtro o acha");
    }

    @Test
    void pastasContinuamPrimeiroEmQualquerColunaEDirecao() {
        for (Coluna c : Coluna.values()) {
            for (boolean cresc : new boolean[] {true, false}) {
                List<EntradaRemota> v = VisaoDaPasta.montar(PASTA, "", false, new Ordem(c, cresc));
                assertTrue(v.get(0).ehPasta() && v.get(1).ehPasta() && !v.get(2).ehPasta(), c + " " + cresc + " " + nomes(v));
            }
        }
    }

    @Test
    void tamanhoOrdenaDoMaiorPraMenorNoPrimeiroCliqueEInverteNoSegundo() {
        Ordem o = Ordem.PADRAO.clicar(Coluna.TAMANHO);
        assertEquals(new Ordem(Coluna.TAMANHO, false), o);
        assertEquals(List.of("arq10.log", "arq2.log", "zeta.txt"), nomes(VisaoDaPasta.montar(PASTA, "", false, o)).subList(2, 5));
        Ordem de = o.clicar(Coluna.TAMANHO);
        assertEquals(List.of("zeta.txt", "arq2.log", "arq10.log"), nomes(VisaoDaPasta.montar(PASTA, "", false, de)).subList(2, 5));
    }

    @Test
    void dataOrdenaDoMaisNovoPrimeiro() {
        Ordem o = Ordem.PADRAO.clicar(Coluna.MODIFICADO);
        assertEquals(List.of("arq2.log", "arq10.log", "zeta.txt"), nomes(VisaoDaPasta.montar(PASTA, "", false, o)).subList(2, 5));
    }

    @Test
    void nomeDecrescenteInverteDentroDeCadaGrupo() {
        assertEquals(List.of("beta", "Árvore", "zeta.txt", "arq10.log", "arq2.log"),
            nomes(VisaoDaPasta.montar(PASTA, "", false, new Ordem(Coluna.NOME, false))));
    }

    @Test
    void listaDevolvidaNaoMudaDepoisEEhNova() {
        List<EntradaRemota> v = VisaoDaPasta.montar(PASTA, "", false, Ordem.PADRAO);
        assertEquals(7, PASTA.size());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, () -> v.add(PASTA.get(0)));
    }

    @Test
    void rotulosDeTipo() {
        assertEquals("Pasta", VisaoDaPasta.TiposDeArquivo.rotulo(PASTA.get(1)));
        assertEquals("Registro", VisaoDaPasta.TiposDeArquivo.rotulo(PASTA.get(3)));
        assertEquals("Arquivo", VisaoDaPasta.TiposDeArquivo.rotulo(e("sem-extensao", Tipo.ARQUIVO, 1, "2026-10-01T00:00:00Z")));
        assertEquals("Link para pasta", VisaoDaPasta.TiposDeArquivo.rotulo(
            new EntradaRemota("l", "/l", Tipo.PASTA, true, 0, null, 0777, "", "", "/x")));
        assertEquals("Link quebrado", VisaoDaPasta.TiposDeArquivo.rotulo(
            new EntradaRemota("l", "/l", Tipo.LINK_QUEBRADO, true, 0, null, 0777, "", "", "/nada")));
    }
}
