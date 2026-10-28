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
 * A regra "NADA SE INSTALA SOZINHO", como fronteira do código: instalar, atualizar e remover o agente só nascem de UM lugar, o
 * diálogo de confirmação que o clique abre. Nem conectar, nem abrir o app, nem rotina de fundo, nem "atualização automática" chegam ao
 * instalador, e nenhum comando de ESCRITA do servidor é montado fora dele. (O comportamento, "conectar a um servidor sem agente só executa
 * leituras", é provado em {@code AgenteAusenteTest} e {@code AgenteNaoInstaladoNaJanelaTest}.)
 */
class SemInstalacaoAutomaticaTest {

    private static final Path RAIZ = Path.of("src", "main", "java", "br", "com", "labdesktop");

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

    @Test
    void soODialogoDeConfirmacaoChamaInstalarEoRemoverDoInstalador() throws IOException {
        assertEquals(List.of(), quemUsa(Pattern.compile("\\b(instalador|deste)\\.(instalar|remover)\\("),
            Set.of("ui/dialogos/DialogoDoAgente.java", "instalador/InstaladorDeAgente.java")),
            "só o diálogo que o clique abre pode instalar ou remover o agente");
    }

    @Test
    void oInstaladorSoENascidoNoServidorLabEEntregueAoDialogoPelaAcaoDoClique() throws IOException {
        assertEquals(List.of(), quemUsa(Pattern.compile("instaladorDoAgente\\("),
            Set.of("servidor/ServidorLab.java", "ui/AcoesDaInterface.java")),
            "o instalador só é montado por ServidorLab e só é pedido por AcoesDaInterface (que o entrega ao diálogo)");
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bnew InstaladorDeAgente\\("),
            Set.of("servidor/ServidorLab.java")), "ninguém mais constrói o instalador");
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bInstaladorDeAgente\\b"),
            Set.of("servidor/ServidorLab.java", "instalador/InstaladorDeAgente.java", "ui/dialogos/DialogoDoAgente.java", "ui/AcoesDaInterface.java")),
            "nada de conexão, sondador, abertura do app ou tela de dados enxerga o instalador");
    }

    @Test
    void asAcoesDoAgenteSoSaoChamadasPeloMenuPelaFaixaEPelosBotoesDoDono() throws IOException {
        assertEquals(List.of(), quemUsa(Pattern.compile("\\.(instalarAgente|atualizarAgente|removerAgente)\\("),
            Set.of("ui/AcoesDaInterface.java", "ui/MenuDoServidor.java", "ui/Navegacao.java")),
            "quem pode chamar as ações do agente são os botões e menus (cada um é um clique do usuário)");
        // e nenhuma delas é chamada de rotina de fundo: nada de Agendador, Fundo.agenda ou Sondador por perto do instalador
        for (String nome : List.of("instalador/InstaladorDeAgente.java", "ui/dialogos/DialogoDoAgente.java")) {
            String codigo = semComentarios(RAIZ.resolve(nome));
            assertTrue(!codigo.contains("Agendador") && !codigo.contains("Fundo.agenda") && !codigo.contains("ScheduledExecutor"),
                nome + " não agenda nada: roda a pedido");
        }
    }

    @Test
    void nenhumaRotaDeAtualizacaoAutomaticaExiste() throws IOException {
        // Nada com cara de "atualizar sozinho": nome de classe, método ou propriedade.
        Pattern automatica = Pattern.compile("(?i)(auto-?atualiz|atualizacaoAutomatica|atualizarSozinho|autoInstal|instalarSozinho|instalarAoConectar|instalarAutomaticamente)");
        assertEquals(List.of(), quemUsa(automatica, Set.of()), "não existe rota de atualização automática");
    }

    @Test
    void osComandosDeEscritaSoSaoMontadosDentroDoInstalador() throws IOException {
        Pattern escrita = Pattern.compile("ComandosRemotos\\.(instalarODocker|criarPasta|extrairPacote|construirImagem|removerContainer|subirContainer|"
            + "removerVolume|removerImagem|removerPasta|renomearContainer|pararContainer|iniciarContainer|removerContainerPeloNome)\\(");
        assertEquals(List.of(), quemUsa(escrita, Set.of("instalador/InstaladorDeAgente.java")),
            "comando de escrita do servidor só nasce no instalador (que só roda a pedido)");
    }

    @Test
    void criarImportarEAcrescentarAoComposeSoNascemDoCliqueEmAcoesDaInterface() throws IOException {
        // novo projeto: o ServidorLab (que conecta sozinho) só MONTA o ambiente (nada roda ao montar), e quem o pede é a ação do clique
        assertEquals(List.of(), quemUsa(Pattern.compile("\\.ambienteDeProjetos\\("), Set.of("ui/AcoesDaInterface.java", "servidor/ServidorLab.java")));
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bComandosDeProjeto\\."),
            Set.of("projetos/CriadorNoAmbiente.java", "projetos/ImportadorDeProjeto.java", "projetos/EscritorDeCompose.java", "ui/dialogos/DialogoNovoProjeto.java",
                "cloudflare/PublicadorNaInternet.java")),
            "clonar, mover, copiar, criar rede e conferir compose só nascem nas operações do Novo projeto");
        String lab = semComentarios(RAIZ.resolve("servidor/ServidorLab.java"));
        assertTrue(!lab.contains("new CriadorNoAmbiente(") && !lab.contains("new ImportadorDeProjeto(") && !lab.contains("new EscritorDeCompose("),
            "o ServidorLab não cria, não importa e não escreve compose");
        // o resto da fronteira (cada ponto de entrada é um botão) está em NovoProjetoSemEfeitoSozinhoTest
    }

    @Test
    void aLeituraQueDizSeOAgenteExisteEAunicaCoisaDoInstaladorQueRodaSemClique() throws IOException {
        // O ServidorLab (que conecta sozinho) só usa a LEITURA do diagnóstico: nenhuma outra chamada a ComandosRemotos, nenhum executor de escrita.
        String lab = semComentarios(RAIZ.resolve("servidor/ServidorLab.java"));
        assertTrue(!lab.contains("ComandosRemotos."), "o ServidorLab não monta comando nenhum: o diagnóstico mora no LeitorDaSituacaoDoAgente");
        String leitor = semComentarios(RAIZ.resolve("servidor/LeitorDaSituacaoDoAgente.java"));
        long chamadas = Pattern.compile("ComandosRemotos\\.\\w+\\(").matcher(leitor).results().count();
        assertEquals(1, chamadas);
        assertTrue(leitor.contains("ComandosRemotos.lerSituacaoDoAgente("));
    }
}
