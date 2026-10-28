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
 * A regra do novo projeto, como fronteira do código (o comportamento é provado nos testes do pacote {@code projetos}, dos diálogos e de
 * {@code NovoProjetoLocalNaJanelaTest}): criar, importar e acrescentar container só nascem do CLIQUE em "Criar projeto", "Importar projeto" ou "Gravar
 * no docker-compose.yml". Abrir o diálogo, abrir a aba, o Sondador, um timer ou o app subindo nunca criam pasta, clonam, copiam, mexem em compose
 * nem rodam Docker. Os comandos novos são só dessas operações (e Iniciar, Parar e Reiniciar do local, que também são botões).
 */
class NovoProjetoSemEfeitoSozinhoTest {

    private static final Path RAIZ = Path.of("src", "main", "java", "br", "com", "labdesktop");
    private static final String ACOES = "ui/AcoesDaInterface.java";
    private static final String DIALOGO = "ui/dialogos/DialogoNovoProjeto.java";
    private static final String DIALOGO_CONTAINER = "ui/dialogos/DialogoAdicionarContainer.java";

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
    void asOperacoesSoSaoMontadasPelaAcaoDoClique() throws IOException {
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bnew (CriadorNoAmbiente|CriadorPeloAgente|ImportadorDeProjeto|EscritorDeCompose)\\("), Set.of(ACOES)),
            "só AcoesDaInterface (pedida pelo botão, pelo convite ou pelo menu) monta quem cria, importa e escreve compose");
        assertEquals(List.of(), quemUsa(Pattern.compile("\\.(novoProjeto|adicionarContainer)\\("), Set.of("ui/telas/TelaProjetos.java", "ui/PainelPrincipal.java")),
            "só a lista (botão e convite), a página (botão) e o item de menu abrem os diálogos");
        assertEquals(List.of(), quemUsa(Pattern.compile("\\.adicionarContainerDe\\(|\\.novoProjetoNoServidorEmFoco\\("),
            Set.of("ui/telas/TelaServidor.java", "ui/MenuApp.java")));
    }

    @Test
    void osComandosDeProjetoSoSaoMontadosPelasOperacoes() throws IOException {
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bComandosDeProjeto\\."),
            Set.of("projetos/CriadorNoAmbiente.java", "projetos/ImportadorDeProjeto.java", "projetos/EscritorDeCompose.java", "ui/dialogos/DialogoNovoProjeto.java",
                "cloudflare/PublicadorNaInternet.java")),
            "clonar, mover, copiar, criar rede e conferir compose só nascem das operações (o diálogo só MOSTRA o comando que o usuário confere)");
        // e o diálogo só usa os construtores de mover e copiar para MOSTRAR o comando no resumo
        String d = codigo(DIALOGO);
        assertFalse(d.contains("ComandosDeProjeto.clonar") || d.contains("ComandosDeProjeto.criarRede") || d.contains("ComandosDeProjeto.validarCompose"));
        assertEquals(List.of(), quemUsa(Pattern.compile("ComandosLocais\\.(comporProjeto|reiniciarContainerDoProjeto)\\("), Set.of("local/ProjetosLocais.java")),
            "Iniciar, Parar e Reiniciar do local só existem em ProjetosLocais");
    }

    @Test
    void iniciarPararEReiniciarDoLocalSoSaoChamadosPelasAcoesDoServidor() throws IOException {
        assertEquals(List.of(), quemUsa(Pattern.compile("\\blocais\\.(iniciar|parar|reiniciar|reiniciarContainer)\\(|\\bprojetosLocais\\.(iniciar|parar|reiniciar|reiniciarContainer)\\("),
            Set.of("servidor/AcoesDoServidor.java")), "e as ações do servidor só rodam por botão (FluxosDeProjeto)");
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bnew ProjetosLocais\\("), Set.of("servidor/ServidorLab.java")));
    }

    @Test
    void aLeituraDosProjetosLocaisSoRodaPeloExecutorQueImpoeALeitura() throws IOException {
        String p = codigo("local/ProjetosLocais.java");
        assertTrue(p.contains("this.leitura = new ExecutorSoDeLeitura(executor);"), "a lista (que roda a cada 10 s, sozinha) só passa pelo executor que só deixa leitura");
        assertEquals(1, Pattern.compile("\\bleitura\\.executar\\(").matcher(p).results().count(), "uma única leitura do Docker, e é a da lista");
        assertTrue(Pattern.compile("leitura\\.executar\\(ComandosLocais\\.lerContainersDosProjetos\\(\\)").matcher(p).find());
        // e as escritas ficam em métodos de ação (os que só os botões chamam), nunca na leitura
        String leituraDaLista = p.substring(p.indexOf("public ListaProjetos lista()"), p.indexOf("public String iniciar(String nome)"));
        assertFalse(leituraDaLista.contains("escrita.executar"), "ler a lista não escreve nada");
    }

    @Test
    void oQueRodaSozinhoNoPacoteDeProjetosNaoAgendaNada() throws IOException {
        Pattern fundo = Pattern.compile("Agendador|Fundo\\.agenda|ScheduledExecutor|java\\.util\\.Timer|javax\\.swing\\.Timer|new Timer\\(");
        try (Stream<Path> s = Files.walk(RAIZ.resolve("projetos"))) {
            for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                assertFalse(fundo.matcher(semComentarios(p)).find(), p + " não agenda nada: roda a pedido");
            }
        }
        assertFalse(fundo.matcher(codigo("local/ProjetosLocais.java")).find());
    }

    @Test
    void osDialogosSoDisparamCriarImportarEGravarPorBotao() throws IOException {
        String d = codigo(DIALOGO);
        // criar() e importar() só são chamados por aoClicarNoPrincipal (o botão primário); o construtor, antesDeMostrar e irPara nunca os chamam
        for (String acao : List.of("criar", "importar")) {
            long chamadas = Pattern.compile("(?<![.\\w])" + acao + "\\(\\)").matcher(d).results().count();
            assertEquals(2, chamadas, acao + "(): a definição e UMA chamada, a do botão (aoClicarNoPrincipal)");
        }
        assertTrue(Pattern.compile("(?s)private void aoClicarNoPrincipal\\(\\) \\{.*?criar\\(\\);.*?importar\\(\\);").matcher(d).find());
        assertTrue(d.contains("protected void antesDeMostrar() {\n    }"), "antesDeMostrar não faz nada");
        // os motores (que escrevem) só são chamados dentro de criar() e importar()
        for (String chamada : List.of("motor.criar(", "motor.clonar(", "motor.importarPasta(", "motor.adotar(")) {
            assertEquals(1, d.split(Pattern.quote(chamada), -1).length - 1, chamada + " aparece uma vez só");
        }
        // gravar() do "Adicionar container" também é de um botão só
        String c = codigo(DIALOGO_CONTAINER);
        assertEquals(2, Pattern.compile("(?<![.\\w])gravar\\(\\)").matcher(c).results().count(), "gravar(): a definição e a chamada do botão");
        assertEquals(1, c.split(Pattern.quote("motor.gravar("), -1).length - 1);
        assertTrue(c.contains("gravar.addActionListener(e -> gravar());"));
        assertTrue(c.contains("iniciar.addActionListener(e -> {"), "Iniciar é outro botão");
        assertFalse(c.contains("aoIniciar.run();\n    }\n\n    private void escolher"), "gravar não inicia: Iniciar tem o clique dele");
        // preparar() só LÊ: quem o chama é a mudança dos campos
        assertEquals(1, c.split(Pattern.quote("motor.preparar("), -1).length - 1);
    }

    @Test
    void oEscritorDeComposeNuncaSobeNadaNemRodaOutroComandoQueAConferencia() throws IOException {
        String e = codigo("projetos/EscritorDeCompose.java");
        assertEquals(1, Pattern.compile("executor\\(\\)\\.executar\\(").matcher(e).results().count(), "o único programa que ele roda é a conferência");
        assertTrue(e.contains("ComandosDeProjeto.validarCompose("));
        assertFalse(e.contains("\"up\"") || e.contains("\"start\"") || e.contains("INICIAR"), "iniciar é outro clique, de outra tela");
    }
}
