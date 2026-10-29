package br.com.labdesktop.projetos;

import br.com.labdesktop.local.ExecutorDeProcesso;
import br.com.labdesktop.ssh.ProvisionadorRemoto;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Roda UM programa com os argumentos em LISTA, no computador local ou num servidor por SSH. É a tomada de {@link AmbienteDeProjetos} para tudo o
 * que não é arquivo ({@code git clone}, {@code docker network create}, {@code docker compose config}, {@code mv}).
 *
 * <p>No computador local, vai direto para o processo (nada de shell). Por SSH, o servidor recebe UMA linha de texto, então CADA argumento tem de
 * ser feito só de caracteres seguros ({@link #SEGURO}: letras, números e {@code _ @ % + = : . , / ~ -}), sem aspas, espaço, {@code ; | & $ ` ( ) < >}
 * nem quebra de linha; um que não passe é recusado ANTES de qualquer envio ({@link IllegalArgumentException}). Assim nada que o usuário digita vira
 * comando: o texto dele só entra depois de ter passado pelas regras de nome, de endereço e de caminho.
 */
@FunctionalInterface
public interface ExecutorDeArgumentos {

    /** Os caracteres que um argumento pode ter numa linha de comando remota (nenhum precisa de aspas). */
    Pattern SEGURO = Pattern.compile("^[A-Za-z0-9_@%+=:.,/~-]{1,400}$");

    /** Nome de variável de ambiente que o app aceita passar. */
    Pattern NOME_DE_VARIAVEL = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    /**
     * @param ambiente variáveis a mais para este comando (nome em maiúsculas; valor só de caracteres seguros)
     * @param stdin    bytes enviados à entrada do programa (pode ser vazio, nunca {@code null})
     * @throws IOException o programa não existe, o prazo estourou ou a conexão caiu
     */
    ResultadoExec executar(List<String> argumentos, Map<String, String> ambiente, byte[] stdin, Duration prazo)
        throws IOException, InterruptedException;

    default ResultadoExec executar(List<String> argumentos, Duration prazo) throws IOException, InterruptedException {
        return executar(argumentos, Map.of(), new byte[0], prazo);
    }

    /**
     * Como {@link #executar(List, Map, byte[], Duration)}, mas com algumas posições da lista que são TEXTO LIVRE (um nome de arquivo com espaço ou acento, o nome de quem assina
     * um commit, um formato do git com parênteses). Neste computador não muda nada (lista de argumentos, nunca shell). Por SSH essas posições, e SÓ elas, vão entre aspas simples
     * (ver {@link #aspar}), depois de conferir que não têm caractere de controle: o texto chega ao programa como UM argumento, sem que nada nele vire comando. As outras
     * posições continuam presas à regra {@link #SEGURO}. O padrão repassa tudo sem aspas, então um executor que não sabe aspar recusa o que não é seguro (nunca interpreta).
     *
     * @param livres as posições (a partir de 0) da lista que são texto livre
     */
    default ResultadoExec executarComTexto(List<String> argumentos, java.util.Set<Integer> livres, Map<String, String> ambiente, byte[] stdin, Duration prazo)
        throws IOException, InterruptedException {
        return executar(argumentos, ambiente, stdin, prazo);
    }

    /** Neste computador: o executor de processos do laboratório local. */
    static ExecutorDeArgumentos deProcessos(ExecutorDeProcesso processos) {
        return processos::executar;
    }

    /** Num servidor, por SSH: monta a linha {@code env VAR=valor programa argumentos...} (só com argumentos seguros; o texto livre, entre aspas simples). */
    static ExecutorDeArgumentos porSsh(ProvisionadorRemoto.Executor remoto) {
        return new ExecutorDeArgumentos() {
            @Override
            public ResultadoExec executar(List<String> argumentos, Map<String, String> ambiente, byte[] stdin, Duration prazo) throws IOException, InterruptedException {
                return remoto.executar(linhaRemota(argumentos, ambiente), stdin, prazo);
            }

            @Override
            public ResultadoExec executarComTexto(List<String> argumentos, java.util.Set<Integer> livres, Map<String, String> ambiente, byte[] stdin, Duration prazo)
                throws IOException, InterruptedException {
                return remoto.executar(linhaRemota(argumentos, livres, ambiente), stdin, prazo);
            }
        };
    }

    /** O limite de um texto livre numa linha remota. */
    int MAXIMO_DO_TEXTO_LIVRE = 1000;

    /**
     * Põe um texto entre aspas simples para um shell POSIX: dentro das aspas simples nada tem sentido, e a própria aspa simples vira {@code '\''} (fecha, aspa escapada, abre).
     * Recusa ({@link IllegalArgumentException}) o que um shell ou o canal não aguenta: vazio, caractere de controle (nova linha, tabulação, NUL, ESC) e mais de
     * {@value #MAXIMO_DO_TEXTO_LIVRE} caracteres. Acento e espaço passam.
     */
    static String aspar(String texto) {
        if (texto == null || texto.isEmpty() || texto.length() > MAXIMO_DO_TEXTO_LIVRE) {
            throw new IllegalArgumentException("Texto fora da regra do comando remoto.");
        }
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (c < 0x20 || c == 0x7F || (c >= 0x80 && c < 0xA0)) {
                throw new IllegalArgumentException("Texto com caractere de controle no comando remoto.");
            }
        }
        return "'" + texto.replace("'", "'\\''") + "'";
    }

    /** Como {@link #linhaRemota(List, Map)}, com as posições {@code livres} entre aspas simples. */
    static String linhaRemota(List<String> argumentos, java.util.Set<Integer> livres, Map<String, String> ambiente) {
        if (argumentos == null || argumentos.isEmpty()) {
            throw new IllegalArgumentException("Comando vazio.");
        }
        List<String> partes = new ArrayList<>();
        if (!ambiente.isEmpty()) {
            partes.add("env");
            ambiente.forEach((nome, valor) -> {
                if (!NOME_DE_VARIAVEL.matcher(nome).matches() || valor == null || !SEGURO.matcher(valor).matches()) {
                    throw new IllegalArgumentException("Variável de ambiente fora da regra do comando remoto.");
                }
                partes.add(nome + "=" + valor);
            });
        }
        for (int i = 0; i < argumentos.size(); i++) {
            String a = argumentos.get(i);
            if (livres.contains(i)) {
                partes.add(aspar(a));
            } else if (a == null || !SEGURO.matcher(a).matches()) {
                throw new IllegalArgumentException("Argumento fora da regra do comando remoto.");
            } else {
                partes.add(a);
            }
        }
        return String.join(" ", partes);
    }

    /** A linha que vai ao servidor. {@link IllegalArgumentException} se algum argumento ou variável não é seguro. */
    static String linhaRemota(List<String> argumentos, Map<String, String> ambiente) {
        if (argumentos == null || argumentos.isEmpty()) {
            throw new IllegalArgumentException("Comando vazio.");
        }
        List<String> partes = new ArrayList<>();
        if (!ambiente.isEmpty()) {
            partes.add("env");
            ambiente.forEach((nome, valor) -> {
                if (!NOME_DE_VARIAVEL.matcher(nome).matches() || valor == null || !SEGURO.matcher(valor).matches()) {
                    throw new IllegalArgumentException("Variável de ambiente fora da regra do comando remoto.");
                }
                partes.add(nome + "=" + valor);
            });
        }
        for (String a : argumentos) {
            if (a == null || !SEGURO.matcher(a).matches()) {
                throw new IllegalArgumentException("Argumento fora da regra do comando remoto.");
            }
            partes.add(a);
        }
        return String.join(" ", partes);
    }
}
