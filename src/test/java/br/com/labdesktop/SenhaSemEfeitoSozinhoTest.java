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
 * A regra do primeiro acesso por senha como fronteira do código: a senha só existe em {@code char[]} e só sai do app DEPOIS de o usuário confirmar o host; nada é instalado nem
 * desligado sozinho. Instalar a chave só nasce do assistente (depois do clique em "Entrar e instalar a chave"), e desligar a senha só do diálogo de confirmação
 * (depois do clique e do nome do servidor digitado). Nem conectar, nem abrir o app, nem rotina de fundo chegam lá.
 */
class SenhaSemEfeitoSozinhoTest {

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
    void aSenhaSoEAbertaPeloAssistenteDepoisDoClique() throws IOException {
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bSessaoComSenha\\.abrir\\("), Set.of("servidor/AssistenteDeServidor.java")),
            "só o assistente abre uma sessão com senha");
        assertEquals(List.of(), quemUsa(Pattern.compile("\\binstalarAChaveComSenha\\("), Set.of("servidor/AssistenteDeServidor.java", "ui/dialogos/DialogoAdicionarServidor.java")),
            "só o diálogo do assistente (no clique de Entrar e instalar a chave) chama o primeiro acesso");
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bSessaoComSenha\\b"), Set.of("servidor/AssistenteDeServidor.java", "ssh/SessaoComSenha.java")),
            "nada de conexão, sondador ou tela enxerga a sessão com senha");
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bEntradaPorSenha\\b"), Set.of("ssh/EntradaPorSenha.java", "ssh/SessaoComSenha.java")),
            "o fator de senha existe só no cliente da sessão com senha: o cliente normal do app é só publickey");
    }

    @Test
    void oClienteNormalNaoTemFatorDeSenha() throws IOException {
        String fabrica = semComentarios(RAIZ.resolve("ssh/ClienteSshFactory.java"));
        assertTrue(!fabrica.contains("Password") && !fabrica.contains("EntradaPorSenha"), "ClienteSshFactory continua só com publickey");
        String conexao = semComentarios(RAIZ.resolve("ssh/ConexaoSsh.java"));
        assertTrue(!conexao.contains("Password") && !conexao.contains("EntradaPorSenha") && !conexao.contains("SessaoComSenha"), "ConexaoSsh nunca usa senha");
    }

    @Test
    void asEscritasDoAcessoSoNascemNosDoisLugaresQueRodamDepoisDoClique() throws IOException {
        Pattern escrita = Pattern.compile("ComandosDoAcesso\\.(instalarChave|copiaDoArquivoPrincipal|copiaDoArquivoProprio|gravarArquivoProprio|recarregarSshd|"
            + "restaurarArquivoProprio|apagarArquivoProprio)\\(");
        assertEquals(List.of(), quemUsa(escrita, Set.of("acesso/DesligadorDeSenha.java", "acesso/InstaladorDeChave.java")),
            "comando de escrita do acesso só nasce no instalador da chave e no desligador (que só rodam a pedido)");
    }

    @Test
    void desligarASenhaSoNasceNaConfirmacaoQueOCliqueAbre() throws IOException {
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bDesligadorDeSenha\\b"),
            Set.of("acesso/DesligadorDeSenha.java", "servidor/ServidorLab.java", "ui/AcoesDaInterface.java", "ui/dialogos/DialogoDesligarSenha.java")),
            "nada de conexão, sondador, abertura do app ou tela de dados enxerga o desligador");
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bnew DesligadorDeSenha\\("), Set.of("servidor/ServidorLab.java")), "só o ServidorLab monta o desligador");
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bdesligador\\.(desligar|avaliar)\\("), Set.of("ui/AcoesDaInterface.java")),
            "só a ação do clique entrega o desligador ao diálogo");
        assertEquals(List.of(), quemUsa(Pattern.compile("\\.desligadorDeSenha\\("), Set.of("ui/AcoesDaInterface.java", "servidor/ServidorLab.java")),
            "só AcoesDaInterface pede o desligador");
        assertEquals(List.of(), quemUsa(Pattern.compile("\\bmotor\\.desligar\\("), Set.of("ui/dialogos/DialogoDesligarSenha.java")),
            "só o diálogo de confirmação manda desligar");
        String lab = semComentarios(RAIZ.resolve("servidor/ServidorLab.java"));
        assertTrue(!lab.contains(".avaliar(") && !lab.contains(".desligar("), "o ServidorLab (que conecta sozinho) só MONTA o desligador");
    }

    @Test
    void asAcoesDeDesligarSoSaoChamadasPeloBotaoDoAssistenteEPeloDaAbaSeguranca() throws IOException {
        assertEquals(List.of(), quemUsa(Pattern.compile("\\.desligarSenha\\("),
            Set.of("ui/AcoesDaInterface.java", "ui/dialogos/DialogoAdicionarServidor.java", "ui/telas/TelaServidor.java")),
            "quem pede a confirmação são o botão do assistente e o da aba Segurança (cada um é um clique do usuário)");
        // e nenhuma delas é chamada de rotina de fundo
        for (String nome : List.of("acesso/DesligadorDeSenha.java", "acesso/InstaladorDeChave.java", "ui/dialogos/DialogoDesligarSenha.java")) {
            String codigo = semComentarios(RAIZ.resolve(nome));
            assertTrue(!codigo.contains("Agendador") && !codigo.contains("Fundo.agenda") && !codigo.contains("ScheduledExecutor"), nome + " não agenda nada: roda a pedido");
        }
    }

    @Test
    void aSenhaNuncaViraStringNemEscritaEmLogNoCaminhoDoPrimeiroAcesso() throws IOException {
        for (String nome : List.of("ssh/EntradaPorSenha.java", "ssh/SessaoComSenha.java")) {
            String codigo = semComentarios(RAIZ.resolve(nome));
            assertTrue(!codigo.contains("new String(senha") && !codigo.contains("String.valueOf(senha") && !codigo.contains("senha.toString")
                && !codigo.contains("addPasswordIdentity") && !codigo.contains("UserAuthPasswordFactory"), nome + ": a senha nunca vira String");
            assertTrue(!Pattern.compile("LOG\\.\\w+\\([^;]*,\\s*senha\\s*[,)]").matcher(codigo).find(), nome + ": a senha nunca vai para o log");
        }
        String assistente = semComentarios(RAIZ.resolve("servidor/AssistenteDeServidor.java"));
        assertTrue(assistente.contains("Zeragem.zerar(senha)"), "o assistente zera a senha ao terminar");
    }
}
