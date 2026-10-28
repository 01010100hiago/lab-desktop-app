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
 * A regra "NADA SE INSTALA SOZINHO", para o lado LOCAL, como fronteira do código (o comportamento é provado em
 * {@code VerificacoesDoComputadorTest}, {@code DialogoPrepararComputadorTest} e {@code ServidorLocalTest}): instalar um requisito, abrir o Docker,
 * criar e remover o laboratório só nascem do clique em "Preparar este computador"; nem conexão, nem sondador, nem timer, nem construtor, nem
 * abertura de tela chegam a eles, e nenhum comando de escrita do Docker local é montado fora do laboratório.
 */
class LocalSemInstalacaoAutomaticaTest {

    private static final Path RAIZ = Path.of("src", "main", "java", "br", "com", "labdesktop");

    private static final String ACOES = "ui/AcoesDaInterface.java";
    private static final String DIALOGO = "ui/dialogos/DialogoPrepararComputador.java";

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

    private static String codigo(String relativo) throws IOException {
        return semComentarios(RAIZ.resolve(relativo));
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

    @Test
    void oInstaladorDeRequisitosESoMontadoPeloMotorDaTelaDePreparar() throws IOException {
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bnew InstaladorDeRequisitos\\("), Set.of(ACOES)),
            "só o motor que a tela de Preparar usa constrói o instalador de requisitos");
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bInstaladorDeRequisitos\\b"),
            Set.of(ACOES, DIALOGO, "local/InstaladorDeRequisitos.java")), "nada de conexão, sondador ou rotina de fundo enxerga o instalador de requisitos");
    }

    @Test
    void oLaboratorioLocalSoESolicitadoPelaTelaDePrepararQueOAbriuOClique() throws IOException {
        assertEquals(List.of(), quemUsa(Pattern.compile("\\blaboratorioLocal\\("), Set.of("servidor/ServidorLab.java", ACOES)),
            "ServidorLab o monta (sem rodar) e só o motor da tela o pede");
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bnew LaboratorioLocal\\("), Set.of("servidor/ServidorLab.java")), "ninguém mais o constrói");
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bLaboratorioLocal\\b"),
            Set.of("servidor/ServidorLab.java", ACOES, DIALOGO, "local/LaboratorioLocal.java")),
            "conexão, sondador, abertura do app e telas de dados não enxergam o laboratório");
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bmotor\\.(laboratorio|instalar|abrirODocker)\\("), Set.of(DIALOGO)));
    }

    @Test
    void criarRemoverInstalarEAbrirODockerSoSaoDisparadosPorBotoesDoDialogo() throws IOException {
        String d = codigo(DIALOGO);
        for (String acao : List.of("criarOLaboratorio", "removerOLaboratorio", "aoClicarEmInstalarOPlano", "abrirODocker")) {
            String resto = d.replaceAll("addActionListener\\(e -> " + acao + "\\(\\)\\)", "")
                .replaceAll("this::" + acao + "\\b", "")
                .replaceAll("\\(\\) -> " + acao + "\\(\\)", "")
                .replaceAll("motor\\." + acao + "\\(", "motor.")
                .replaceAll("(private |public )?(void|boolean) " + acao + "\\(", "");
            assertFalse(Pattern.compile("\\b" + acao + "\\b").matcher(resto).find(),
                acao + " só é chamado por um botão (addActionListener) ou pelo 'Tentar de novo' do que o clique começou");
        }
        // o construtor e o antesDeMostrar só conferem (só leitura)
        assertTrue(d.contains("protected void antesDeMostrar() {\n        iniciarConferencia();\n    }"));
        assertFalse(Pattern.compile("(?s)public DialogoPrepararComputador\\(.*?\\n    }\\n").matcher(d).results()
            .anyMatch(m -> m.group().contains("criarOLaboratorio") || m.group().contains("motor.")), "o construtor não roda nada");
    }

    @Test
    void osComandosDeEscritaDoDockerLocalSoSaoMontadosNoLaboratorio() throws IOException {
        Pattern escrita = Pattern.compile("ComandosLocais\\.(criarRede|removerRede|construirImagem|subirContainer|removerContainer|renomearContainer|"
            + "pararContainer|iniciarContainer|criarVolume|removerVolume|removerImagem)\\(");
        assertEquals(List.of(), quemUsa(escrita, Set.of("local/LaboratorioLocal.java")),
            "comando de escrita do Docker local só nasce no laboratório (que só roda a pedido)");
    }

    @Test
    void osPlanosDeInstalacaoSaoSoDadosOferecidosPelaConferencia() throws IOException {
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bPlanosDeInstalacao\\."), Set.of("local/VerificacoesDoComputador.java", "local/PlanosDeInstalacao.java")),
            "só a conferência monta a lista de planos (dados): rodar um plano é do instalador de requisitos, com o clique");
    }

    @Test
    void oQueRodaSozinhoNoLocalNaoAgendaNemInstalaNada() throws IOException {
        Pattern fundo = Pattern.compile("Agendador|Fundo\\.agenda|ScheduledExecutor|java\\.util\\.Timer|javax\\.swing\\.Timer|new Timer\\(");
        for (String nome : List.of("local/InstaladorDeRequisitos.java", "local/LaboratorioLocal.java", "local/VerificacoesDoComputador.java",
            "servidor/LeitorDaSituacaoLocal.java", "local/ComandosLocais.java")) {
            assertFalse(fundo.matcher(codigo(nome)).find(), nome + " não agenda nada: roda a pedido");
        }
        // a conexão local e o leitor de situação (que rodam sozinhos) não enxergam nenhum comando de instalação nem de escrita
        for (String nome : List.of("local/ConexaoLocal.java", "servidor/LeitorDaSituacaoLocal.java")) {
            String c = codigo(nome);
            assertFalse(c.contains("InstaladorDeRequisitos") || c.contains("LaboratorioLocal") || c.contains("PlanosDeInstalacao")
                || Pattern.compile("\"(install|run|rm|rmi|build|create|stop|start|rename)\"").matcher(c).find(), nome);
        }
    }

    @Test
    void aConferenciaEODiagnosticoDeFundoSoRodamPeloExecutorQueImpoeALeitura() throws IOException {
        String exec = codigo("local/ExecutorSoDeLeitura.java");
        assertTrue(exec.contains("ComandosLocais.ehLeitura(argumentos)"), "a fronteira é imposta em produção, não só num teste");
        for (String nome : List.of("local/VerificacoesDoComputador.java", "servidor/LeitorDaSituacaoLocal.java")) {
            assertTrue(codigo(nome).contains("new ExecutorSoDeLeitura(executor)"), nome + " envolve o executor no que só deixa passar leitura");
        }
    }
}
