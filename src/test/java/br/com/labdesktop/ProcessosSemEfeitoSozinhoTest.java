package br.com.labdesktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * A fronteira do código para Processos e Backups: nada que encerra, reinicia, instala ou publica nasce fora do clique do usuário. Encerrar e reiniciar só saem de {@code AcoesDaInterface} (com a trava das ações),
 * o sinal e o {@code taskkill} só são montados dentro de {@code processos}, o git só se instala pelo diálogo de confirmação, o download de backup só LÊ do servidor e ninguém mexe na configuração
 * do git. Os comportamentos estão nos testes de cada parte; este varre o código.
 */
class ProcessosSemEfeitoSozinhoTest {

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
            if (permitidos.contains(nome)) {
                continue;
            }
            if (padrao.matcher(semComentarios(p)).find()) {
                fora.add(nome);
            }
        }
        return fora;
    }

    private static Set<String> dentroDeProcessos() throws IOException {
        Set<String> s = new java.util.HashSet<>();
        for (Path p : todos()) {
            String nome = RAIZ.relativize(p).toString().replace('\\', '/');
            if (nome.startsWith("processos/")) {
                s.add(nome);
            }
        }
        return s;
    }

    @Test
    void encerrarEReiniciarSoSaemDasAcoesDaJanelaDepoisDoCliqueEDaTrava() throws IOException {
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bcontrole\\.(encerrar|forcar|reiniciar)\\("), Set.of(ACOES)),
            "o controle só age pelo motor do diálogo de confirmação, montado em AcoesDaInterface");
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bnew DialogoProcesso\\("), Set.of(ACOES)), "só AcoesDaInterface abre a confirmação");
        // que a trava vale por dentro (e que "Sem permissão", que só mostra um comando, passa por ela) é COMPORTAMENTO: ProcessosPelasAcoesDaJanelaTest clica com a trava fechada
    }

    @Test
    void oSinalOTaskkillEOSudoDoDonoSoSaoMontadosDentroDeProcessos() throws IOException {
        Set<String> permitidos = dentroDeProcessos();
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bComandosDeProcessos\\b"), permitidos), "ninguém de fora monta o comando de sinal");
        assertEquals(List.of(), quemUsa(Pattern.compile("kill -(TERM|KILL|[0-9])"), permitidos));
        assertEquals(List.of(), quemUsa(Pattern.compile("\"taskkill\""), permitidos));
        assertEquals(List.of(), quemUsa(Pattern.compile("\\.destroy(Forcibly)?\\(\\)"), Set.of("processos/ProcessosDoSistema.java", "processos/ControleLocal.java", "terminal/TransporteLocal.java", "local/ProcessosLocais.java",
            "terminal/SessaoTerminal.java", "ui/terminal/SshTtyConnector.java", "ui/terminal/PainelTerminal.java",
            "plataforma/ConsultaPorProcesso.java", "AutoTeste.java", "transporte/LancadorReal.java")), "ninguém mais mata processo do computador (as exceções encerram o PRÓPRIO processo filho que o app abriu, o túnel do SSM ou da Cloudflare, uma consulta de sistema e o autoteste)");
    }

    @Test
    void nenhumComandoDeProcessoUsaSudoNemSenha() throws IOException {
        for (String nome : List.of("processos/ControleRemoto.java", "processos/ControleLocal.java", "processos/AvaliadorDeProcessos.java")) {
            String codigo = semComentarios(RAIZ.resolve(nome));
            assertTrue(!Pattern.compile("\"sudo").matcher(codigo).find(), nome + " nunca monta um comando com sudo (o do usuário vem de ComandosDeProcessos, só para mostrar)");
        }
        String comandos = semComentarios(RAIZ.resolve("processos/ComandosDeProcessos.java"));
        assertEquals(0, Pattern.compile("sudo (kill|docker|sh)").matcher(comandos).results().count(), "nenhum comando que o app executa tem sudo");
        // o sudo só existe no texto que o USUÁRIO copia e roda sozinho (nunca passa por executor): ComandoParaODono, e em mais nenhum arquivo do app
        assertEquals(List.of(), quemUsa(Pattern.compile("sudo (kill|docker|sh)"), Set.of("processos/ComandoParaODono.java")).stream()
            .filter(n -> n.startsWith("processos/")).toList(), "no pacote de processos o comando com sudo só está em ComandoParaODono");
    }

    @Test
    void oGitSoSeInstalaPeloDialogoDeConfirmacaoENuncaMexeNaConfiguracao() throws IOException {
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bgit\\.instalar\\("), Set.of(ACOES, "instalador/InstaladorDeGit.java")), "só o motor do diálogo instala");
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bnew DialogoInstalarGit\\("), Set.of(ACOES)));
        assertEquals(List.of(), quemUsa(Pattern.compile("instaladorDeGit\\("), Set.of("servidor/ServidorLab.java", ACOES, "ui/telas/BlocoDoGit.java")),
            "o ServidorLab só monta; quem pede é a ação do clique e o bloco que lê o git (git --version)");
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bnew InstaladorDeGit\\("), Set.of("servidor/ServidorLab.java")));
        assertEquals(List.of(), quemUsa(Pattern.compile("git config|\"git\", \"config\"|\"config\", \"--global\""), Set.of()), "o app nunca mexe na configuração do git");
        assertEquals(List.of(), quemUsa(Pattern.compile("GerenciadorDePacotes\\.[a-z]+\\(.*comandosDeInstalar|\\.comandosDeInstalar\\("), Set.of("instalador/InstaladorDeGit.java", "instalador/GerenciadorDePacotes.java")),
            "os comandos de instalar só são montados no instalador");
    }

    @Test
    void baixarBackupSoLeDoServidor() throws IOException {
        for (String nome : List.of("ui/telas/arquivos/BaixadorDeBackups.java", "arquivos/PlanoDeBackups.java", "arquivos/NomeLocalSeguro.java")) {
            String codigo = semComentarios(RAIZ.resolve(nome));
            for (String escrita : List.of(".enviar(", ".apagar(", ".renomear(", ".gravarAtomico(", ".criarPasta(", ".criarArquivoVazio(", ".mover(", "docker ", "sudo")) {
                assertTrue(!codigo.contains(escrita), nome + " não escreve no servidor: " + escrita);
            }
        }
        assertTrue(semComentarios(RAIZ.resolve("ui/telas/arquivos/BaixadorDeBackups.java")).contains(".baixar("), "e baixa pela fila de transferências");
    }

    @Test
    void adicionarDominioSoEscreveNoUltimoPassoPeloDialogoDePublicar() throws IOException {
        // o assistente (4 passos) só pergunta e lê: nenhum método de escrita do publicador é chamado dentro dele
        String assistente = semComentarios(RAIZ.resolve("ui/dialogos/DialogoAdicionarDominio.java"));
        for (String escrita : List.of(".executar(", "publicador(", "removedor(", ".preparar(", "PublicadorNaInternet.Pedido")) {
            assertTrue(!assistente.contains(escrita), "o assistente não escreve nem prepara a publicação: " + escrita);
        }
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bnew DialogoAdicionarDominio\\("), Set.of(ACOES)));
        // a trava vale por dentro: AdicionarDominioPelaInterfaceTest.semAsAcoesLigadasNadaAbreEOAppDiz clica com ela fechada
    }
}
