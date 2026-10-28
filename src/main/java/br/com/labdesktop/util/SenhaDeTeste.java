package br.com.labdesktop.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.Set;

/**
 * Abertura automática de um cofre de TESTE (opção {@code --senha-de-teste}), só para desenvolvimento. É propositalmente
 * estreita para não virar caminho para o cofre de verdade: exige uma pasta explícita ({@code --pasta}, nunca a padrão),
 * lê só {@value #NOME_DO_ARQUIVO} dentro dela, e só se for um arquivo comum (sem link simbólico), do próprio usuário e com
 * permissão 0600. Qualquer dúvida devolve vazio e o app pede a senha como sempre.
 */
public final class SenhaDeTeste {

    public static final String OPCAO = "--senha-de-teste";
    public static final String NOME_DO_ARQUIVO = "SENHA-DE-TESTE.txt";

    private SenhaDeTeste() { }

    /** Dentro de uma imagem do jpackage o launcher define esta propriedade; rodando pelo Gradle ou pelo {@code java -cp} ela não existe. */
    static final String PROPRIEDADE_DO_JPACKAGE = "jpackage.app-version";

    /** A senha de teste só existe para desenvolvimento: o app instalado (imagem do jpackage) a ignora, mesmo com a opção e uma pasta de teste. */
    public static boolean valeNesteApp() {
        return valeNesteApp(System.getProperty(PROPRIEDADE_DO_JPACKAGE) != null);
    }

    static boolean valeNesteApp(boolean imagemEmpacotada) {
        return !imagemEmpacotada;
    }

    /** {@code true} se a opção veio nos argumentos. */
    public static boolean pedida(String[] args) {
        if (args != null) {
            for (String a : args) {
                if (OPCAO.equals(a)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * O arquivo da senha de teste, se todas as condições de segurança valem. Faz E/S: chame fora da EDT.
     *
     * @param motivo recebe, quando devolve vazio, o porquê (para o log); pode ser {@code null}
     */
    public static Optional<Path> arquivoSeguro(CaminhosApp caminhos, StringBuilder motivo) {
        if (caminhos.ehPastaPadrao()) {
            return recusar(motivo, "só vale com --pasta, nunca na pasta de dados padrão (a do cofre de verdade)");
        }
        Path arquivo = caminhos.pastaDados().resolve(NOME_DO_ARQUIVO);
        try {
            if (!Files.isRegularFile(arquivo, LinkOption.NOFOLLOW_LINKS)) {
                return recusar(motivo, NOME_DO_ARQUIVO + " não existe na pasta ou não é um arquivo comum");
            }
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(arquivo, LinkOption.NOFOLLOW_LINKS);
            if (!perms.equals(PosixFilePermissions.fromString("rw-------"))) {
                return recusar(motivo, NOME_DO_ARQUIVO + " precisa ter permissão 0600");
            }
            String titular = Files.getOwner(arquivo, LinkOption.NOFOLLOW_LINKS).getName();
            if (!titular.equals(System.getProperty("user.name"))) {
                return recusar(motivo, NOME_DO_ARQUIVO + " precisa ser do usuário atual");
            }
        } catch (IOException | UnsupportedOperationException e) {
            return recusar(motivo, "não consegui conferir " + NOME_DO_ARQUIVO + ": " + e.getMessage());
        }
        return Optional.of(arquivo);
    }

    private static Optional<Path> recusar(StringBuilder motivo, String texto) {
        if (motivo != null) {
            motivo.append(texto);
        }
        return Optional.empty();
    }
}
