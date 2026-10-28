package br.com.labdesktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Operações de DOMÍNIO que tocam o disco ({@code PlanoDeBackups.faltaDeEspaco}, que pergunta o espaço livre; {@code pastaDeDownloadsPadrao}; o {@code FileStore}) não podem ser chamadas da
 * EDT: um disco de rede ou um volume que demora trava a janela. {@link TesteEntradaESaidaDaUi} proíbe {@code Files.} dentro de {@code ui}, mas não enxerga o disco escondido atrás de um método
 * do domínio; este teste enxerga: toda chamada dessas operações em {@code ui} tem de estar dentro do TRABALHO de um {@code Tarefas.rodar(...)} (o primeiro argumento, que roda fora da EDT).
 */
class DiscoForaDaEdtTest {

    private static final Path UI = Path.of("src", "main", "java", "br", "com", "labdesktop", "ui");
    private static final List<String> DISCO = List.of("faltaDeEspaco(", "pastaDeDownloadsPadrao", "getFileStore", "getUsableSpace", "caminhoLongoDemaisNoWindows(");

    /** O código sem comentários de linha e de bloco (a documentação cita os nomes para explicar a regra). */
    static String semComentarios(String fonte) {
        StringBuilder sb = new StringBuilder();
        boolean bloco = false;
        for (String linha : fonte.split("\n", -1)) {
            String t = linha.strip();
            if (bloco) {
                bloco = !t.contains("*/");
                sb.append('\n');
                continue;
            }
            if (t.startsWith("/*")) {
                bloco = !t.contains("*/");
                sb.append('\n');
                continue;
            }
            sb.append(t.startsWith("//") || t.startsWith("*") ? "" : linha).append('\n');
        }
        return sb.toString();
    }

    /** Onde termina o primeiro argumento da chamada que abre em {@code abre} (o índice logo depois do {@code (}): a primeira vírgula no nível dele, ou o fecha-parêntese. */
    static int fimDoPrimeiroArgumento(String codigo, int abre) {
        int nivel = 1;
        boolean texto = false;
        for (int i = abre; i < codigo.length(); i++) {
            char c = codigo.charAt(i);
            if (texto) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    texto = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> texto = true;
                case '(', '{', '[' -> nivel++;
                case ')', '}', ']' -> {
                    nivel--;
                    if (nivel == 0) {
                        return i;
                    }
                }
                case ',' -> {
                    if (nivel == 1) {
                        return i;
                    }
                }
                default -> { }
            }
        }
        return codigo.length();
    }

    /** A posição está dentro do trabalho (primeiro argumento) de algum {@code Tarefas.rodar(}? */
    static boolean dentroDoTrabalhoDeUmaTarefa(String codigo, int pos) {
        int i = -1;
        while ((i = codigo.indexOf("Tarefas.rodar(", i + 1)) >= 0) {
            int abre = i + "Tarefas.rodar(".length();
            if (pos >= abre && pos < fimDoPrimeiroArgumento(codigo, abre)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void nenhumaChamadaDeDiscoDoDominioFicaNaEdt() throws IOException {
        List<String> fora = new ArrayList<>();
        int vistas = 0;
        try (Stream<Path> s = Files.walk(UI)) {
            // EscolhedorLocal É o seletor de arquivos do sistema (JFileChooser): ele lê o disco por natureza, e a pasta que ele recebe já foi lida fora da EDT por quem o chamou
            for (Path p : s.filter(x -> x.toString().endsWith(".java") && !x.endsWith("EscolhedorLocal.java")).sorted().toList()) {
                String codigo = semComentarios(Files.readString(p));
                for (String op : DISCO) {
                    int i = -1;
                    while ((i = codigo.indexOf(op, i + 1)) >= 0) {
                        vistas++;
                        if (!dentroDoTrabalhoDeUmaTarefa(codigo, i)) {
                            fora.add(UI.relativize(p) + ": " + op + " fora de Tarefas.rodar");
                        }
                    }
                }
            }
        }
        assertTrue(vistas >= 3, "o teste enxerga as chamadas do baixador de backups (viu " + vistas + ")");
        assertEquals(List.of(), fora, "disco do domínio na EDT");
    }

    @Test
    void oVarredorEnxergaUmaChamadaNaEdtEUmaNoTrabalho() {
        String ruim = "void a() { plano.faltaDeEspaco(destino); }";
        assertTrue(!dentroDoTrabalhoDeUmaTarefa(ruim, ruim.indexOf("faltaDeEspaco(")));
        String boa = "void a() { Tarefas.rodar(() -> plano.faltaDeEspaco(destino), v -> { x(); }, f -> { }); }";
        assertTrue(dentroDoTrabalhoDeUmaTarefa(boa, boa.indexOf("faltaDeEspaco(")));
        String noResultado = "void a() { Tarefas.rodar(() -> 1, v -> plano.faltaDeEspaco(destino), f -> { }); }";
        assertTrue(!dentroDoTrabalhoDeUmaTarefa(noResultado, noResultado.indexOf("faltaDeEspaco(")), "o callback de resultado roda NA EDT");
        String referencia = "Tarefas.rodar(ServicosDeArquivos::pastaDeDownloadsPadrao, p -> { }, f -> { })";
        assertTrue(dentroDoTrabalhoDeUmaTarefa(referencia, referencia.indexOf("pastaDeDownloadsPadrao")));
    }
}
