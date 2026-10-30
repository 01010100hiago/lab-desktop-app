package br.com.labdesktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A fronteira do Git do projeto no código: o Git do projeto LÊ sem trava e ESCREVE só depois do clique, com a trava, por um único caminho. Ler (a aba Git, a linha Código) nunca importa o que escreve; os
 * comandos que escrevem só são montados e rodados em {@code git/CriadorDeRepositorio} (e na área temporária fora do projeto); ninguém monta {@code --global}, {@code push}, {@code add -f} nem remoto; e a
 * conferência da janela só usa o executor de leitura. Os comportamentos estão nos testes de cada parte; este varre o código.
 */
class GitDoProjetoSemEfeitoSozinhoTest {

    private static final Path RAIZ = Path.of("src", "main", "java", "br", "com", "labdesktop");
    private static final String ACOES = "ui/AcoesDaInterface.java";

    private static List<Path> todos() throws IOException {
        try (Stream<Path> s = Files.walk(RAIZ)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String semComentarios(Path p) throws IOException {
        StringBuilder sb = new StringBuilder();
        boolean bloco = false;
        for (String linha : Files.readAllLines(p)) {
            String t = linha.strip();
            if (bloco) {
                bloco = !t.contains("*/");
                continue;
            }
            if (t.startsWith("/*")) {
                bloco = !t.contains("*/");
                continue;
            }
            if (t.startsWith("//") || t.startsWith("*")) {
                continue;
            }
            sb.append(linha).append('\n');
        }
        return sb.toString();
    }

    private static List<String> quemUsa(Pattern padrao, Set<String> permitidos) throws IOException {
        List<String> fora = new ArrayList<>();
        for (Path p : todos()) {
            String nome = RAIZ.relativize(p).toString().replace('\\', '/');
            if (!permitidos.contains(nome) && padrao.matcher(semComentarios(p)).find()) {
                fora.add(nome);
            }
        }
        return fora;
    }

    private static String arquivo(String nome) throws IOException {
        return semComentarios(RAIZ.resolve(nome));
    }

    private static String metodo(String codigo, String de, String ate) {
        int i = codigo.indexOf(de);
        int j = codigo.indexOf(ate, i + de.length());
        assertTrue(i >= 0 && j > i, "não achei o trecho " + de);
        return codigo.substring(i, j);
    }

    @Test
    void osComandosQueEscrevemSoSaoMontadosEmUmLugar() throws IOException {
        assertEquals(List.of(), quemUsa(Pattern.compile("ComandosDeGit\\.(iniciar|gravarIdentidadeLocal|adicionarDaLista|limparPreparo|commitar)\\("), Set.of("git/CriadorDeRepositorio.java")),
            "init, identidade, add, reset e commit só no criador");
        assertEquals(List.of(), quemUsa(Pattern.compile("ComandosDeGit\\.(criarAreaTemporaria|escreverExclusoes|apagarAreaTemporaria)\\("), Set.of("git/AreaTemporaria.java")),
            "a área temporária fora do projeto só nasce e morre em AreaTemporaria");
        assertEquals(List.of(), quemUsa(Pattern.compile("ExecutorDeGit\\.completo\\("), Set.of("git/CriadorDeRepositorio.java")), "só o criador tem o executor que escreve");
        assertEquals(List.of(), quemUsa(Pattern.compile("ExecutorDeGit\\.paraConferir\\("), Set.of("git/CriadorDeRepositorio.java")));
        assertEquals(List.of(), quemUsa(Pattern.compile("ExecutorDeGit\\.soDeLeitura\\("), Set.of("git/LeitorDeGitPorExecutor.java")), "a leitura só usa o executor que recusa escrita");
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bnew CriadorDeRepositorio\\("), Set.of("servidor/ServidorLab.java")), "só o ServidorLab monta");
    }

    @Test
    void criarSoSaiDaAcaoDaJanelaDepoisDoCliqueEDaTrava() throws IOException {
        assertEquals(List.of(), quemUsa(Pattern.compile("\\.criarRepositorio\\("), Set.of("ui/MotorDeCriarRepositorio.java")), "o motor da janela é o único que chama criarRepositorio");
        assertEquals(List.of(), quemUsa(Pattern.compile("operacoesDeGit\\("), Set.of("servidor/ServidorLab.java", ACOES)), "quem pede as operações de escrita é a ação do clique");
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bnew DialogoCriarRepositorio\\("), Set.of(ACOES)));
        String acao = metodo(arquivo(ACOES), "public void criarRepositorioGit(", "public void conectarCloudflare(");
        assertTrue(acao.contains("new MotorDeCriarRepositorio(operacoes, trava)"), "o motor da janela é o que confere a trava na hora de criar");
        assertTrue(acao.contains("trava.getAsBoolean()"), "e a janela já nasce sabendo se a trava está ligada");
        String motor = arquivo("ui/MotorDeCriarRepositorio.java");
        assertTrue(motor.indexOf("trava.getAsBoolean()") > 0 && motor.indexOf("trava.getAsBoolean()") < motor.indexOf("operacoes.criarRepositorio("), "a trava vem ANTES de criarRepositorio dentro do motor");
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bnew MotorDeCriarRepositorio\\("), Set.of(ACOES)));
    }

    @Test
    void leituraNuncaImportaOQueEscreve() throws IOException {
        for (String nome : List.of("git/LeitorDeGit.java", "git/LeitorDeGitPorExecutor.java", "git/LeitorDeGitDoProjeto.java", "git/RepositoriosAbaixo.java", "ui/telas/PaginaGit.java", "ui/telas/LinhaDoCodigo.java", "ui/telas/VistaDeDiff.java", "ui/telas/TabelaDeDados.java")) {
            String codigo = arquivo(nome);
            for (String proibido : List.of("OperacoesDeGit", "CriadorDeRepositorio", "PedidoDeCriacao", "ExecutorDeGit.completo", "ExecutorDeGit.paraConferir", "AreaTemporaria", "gravarAtomico", "ComandosDeGit.iniciar")) {
                assertFalse(codigo.contains(proibido), nome + " não pode usar " + proibido);
            }
        }
        // as telas de leitura nem conhecem os comandos de escrita
        assertEquals(List.of(), quemUsa(Pattern.compile("ComandosDeGit"), Set.of("git/ComandosDeGit.java", "git/LeitorDeGitPorExecutor.java", "git/CriadorDeRepositorio.java", "git/AreaTemporaria.java",
            "git/ExecutorDeGit.java", "git/ErroDoGit.java", "git/RepositoriosAbaixo.java")), "ninguém fora do pacote git escreve uma linha de git");
    }

    @Test
    void aConferenciaSoUsaOExecutorDeLeituraEAAreaTemporaria() throws IOException {
        String codigo = arquivo("git/CriadorDeRepositorio.java");
        String conferir = metodo(codigo, "public ConferenciaDoRepositorio conferirCriacao()", "private static final List<String> PASSOS");
        assertFalse(conferir.contains("completo."), "a conferência não escreve no projeto");
        assertFalse(conferir.contains("gravarAtomico"));
        assertFalse(conferir.contains("ComandosDeGit.iniciar"));
        assertFalse(conferir.contains("ComandosDeGit.adicionarDaLista"));
        assertTrue(conferir.contains("paraConferir.rodar("));
        // a criação confere a liberação ANTES de qualquer escrita
        String criar = metodo(codigo, "public ResultadoDaCriacao criarRepositorio(", "private static boolean cancelou(");
        assertTrue(criar.indexOf("liberaACriacao()") < criar.indexOf("completo.rodar("), "liberação primeiro, escrita depois");
        assertTrue(criar.indexOf("ehRepositorio(pasta)") < criar.indexOf("ComandosDeGit.iniciar("), "confere que ainda não é repositório antes do git init");
        assertTrue(criar.indexOf("amostrasDeSegredoObedecem(completo, null)") < criar.indexOf("ComandosDeGit.adicionarDaLista("), "o git check-ignore vem antes do git add");
        assertTrue(criar.indexOf("rastreadosQueParecemSegredo(") > criar.indexOf("ComandosDeGit.adicionarDaLista("), "e o que foi preparado é conferido depois do add");
        assertTrue(criar.indexOf("rastreadosQueParecemSegredo(") < criar.indexOf("ComandosDeGit.commitar("), "e antes do commit");
        assertFalse(criar.contains("apagar"), "o app nunca apaga o .git: só mostra o comando");
        assertFalse(codigo.contains("Files.delete") || codigo.contains("deleteRecursively") || codigo.contains(".apagar("), "nenhum apagar no criador");
    }

    @Test
    void nenhumArgumentoProibidoNosComandosDoGit() throws IOException {
        for (String nome : List.of("git/ComandosDeGit.java", "git/CriadorDeRepositorio.java", "git/LeitorDeGitPorExecutor.java", "git/AreaTemporaria.java")) {
            // as linhas que só COMPARAM com um argumento (a fronteira por forma, que reconhece --global para chamá-lo de escrita) ficam de fora: o que vale é nunca MONTAR
            String codigo = String.join("\n", arquivo(nome).lines().filter(l -> !l.contains(".contains(") && !l.contains(".equals(")).toList());
            for (String proibido : List.of("\"--global\"", "\"--system\"", "\"-f\"", "\"--force\"", "\"--force-with-lease\"", "\"push\"", "\"remote\"", "\"clone\"", "\"fetch\"", "\"pull\"", "\"checkout\"",
                "\"merge\"", "\"rebase\"", "\"gc\"", "\"clean\"", "\"-A\"", "\"--all\"", "\"--hard\"", "\"submodule\"")) {
                assertFalse(codigo.contains(proibido), nome + " não pode ter o argumento " + proibido);
            }
        }
        String comandos = String.join("\n", arquivo("git/ComandosDeGit.java").lines().filter(l -> !l.contains(".equals(") && !l.contains(".contains(")).toList());
        assertEquals(3, Pattern.compile("\"config\"").matcher(comandos).results().count(), "git config só em três lugares: ler user.name/user.email, listar as chaves que executam programa (--get-regexp, só os nomes) e gravar com --local");
        assertTrue(comandos.contains("\"--local\""));
        assertTrue(comandos.contains("\"--get\""));
        assertTrue(comandos.contains("\"--get-regexp\"") && comandos.contains("\"--name-only\""));
        assertTrue(Pattern.compile("\"add\",\\s*\"--pathspec-from-file=-\",\\s*\"--pathspec-file-nul\"").matcher(comandos).find(), "git add só da lista que vem pela entrada");
        assertFalse(Pattern.compile("\"add\"[^;]*\"\\.\"").matcher(comandos).find(), "nunca git add .");
    }

    @Test
    void oFindQueProcuraRepositoriosEmSubpastasSoLeENuncaTemAcaoQueEscreve() throws IOException {
        String comandos = String.join("\n", arquivo("git/ComandosDeGit.java").lines().filter(l -> !l.contains(".equals(") && !l.contains(".contains(")).toList());
        for (String proibida : List.of("\"-exec\"", "\"-execdir\"", "\"-delete\"", "\"-fprint\"", "\"-fprint0\"", "\"-fls\"", "\"-ok\"", "\"-okdir\"", "\"-L\"", "\"-follow\"")) {
            assertFalse(comandos.contains(proibida), "o find do app não pode ter " + proibida);
        }
        assertEquals(1, Pattern.compile("\"find\"").matcher(comandos).results().count(), "uma só linha de find, montada em um só lugar");
        assertTrue(comandos.contains("\"-print0\"") && comandos.contains("\"-prune\"") && comandos.contains("\"-not\", \"-type\", \"l\""), "só imprime, e não segue link");
        // quem procura repositórios só lê: nenhum desses dois tem o que escreve
        for (String nome : List.of("git/RepositoriosAbaixo.java", "git/LeitorDeGitDoProjeto.java")) {
            String codigo = arquivo(nome);
            for (String proibido : List.of("Files.write", "Files.delete", "Files.createDirector", "Files.move", "Files.copy", "ProcessBuilder", "ExecutorDeGit.completo", "ExecutorDeGit.paraConferir")) {
                assertFalse(codigo.contains(proibido), nome + " não pode usar " + proibido);
            }
        }
        assertEquals(List.of(), quemUsa(Pattern.compile("RepositoriosAbaixo\\.(local|porFind)\\("), Set.of("git/LeitorDeGitPorExecutor.java", "git/CriadorDeRepositorio.java")), "só o leitor e o criador montam a procura");
    }

    @Test
    void nenhumProcessoOuShellNoPacoteGit() throws IOException {
        assertEquals(List.of(), quemUsa(Pattern.compile("ProcessBuilder|Runtime\\.getRuntime|\"sh\"\\s*,\\s*\"-c\"|\"bash\""), Set.of(
            "transporte/LancadorReal.java", "local/ProcessosLocais.java", "local/ComandosLocais.java", "local/ExecutorDeLinhaFixa.java", "terminal/TransporteLocal.java", "AutoTeste.java", "plataforma/ConsultaPorProcesso.java",
            "plataforma/LeitorDeAparencia.java", "util/NativosDoApp.java", "util/NomeDoComputador.java", "util/AparenciaDoSistema.java", "processos/ProcessosDoSistema.java",
            "ui/Navegacao.java", "ui/JanelaPrincipal.java", "util/Abridor.java")).stream().filter(n -> n.startsWith("git/") || n.contains("Git")).toList(),
            "o pacote git roda programa só pelo executor do ambiente do projeto");
    }

    @Test
    void oExecutorDeLeituraEAssinaturaDeLeitura() throws IOException {
        String leitor = arquivo("git/LeitorDeGitPorExecutor.java");
        assertTrue(leitor.contains("ExecutorDeGit.soDeLeitura("));
        String exec = arquivo("git/ExecutorDeGit.java");
        assertTrue(exec.contains("naturezaPelaForma"), "o executor confere a forma do comando, não o que o objeto diz de si");
    }
}
