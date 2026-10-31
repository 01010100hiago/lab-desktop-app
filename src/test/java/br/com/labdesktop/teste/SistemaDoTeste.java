package br.com.labdesktop.teste;

import br.com.labdesktop.arquivos.CaminhosLocais;
import br.com.labdesktop.local.PlataformaLocal;
import br.com.labdesktop.plataforma.SistemaOperacional;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * O sistema onde o teste roda, para os testes que mexem numa pasta temporária de VERDADE (o {@code @TempDir}): o caminho dessa pasta tem o formato do sistema
 * ({@code /tmp/x} no Mac e no Linux, {@code D:\a\x} no Windows), e o app fala de dois jeitos (ARQUITETURA 11.7): o <b>texto do sistema</b> (o que o programa, o git e
 * o Docker entendem) e o <b>caminho do app</b> (sempre com barra; no Windows {@code /D:/a/x}, que é o que o gerenciador de arquivos e os projetos usam). Nenhum teste
 * deve supor que um é igual ao outro, nem forçar o Mac com uma pasta do Windows: use estas funções.
 *
 * <p>No Mac e no Linux tudo aqui devolve o MESMO que o teste usava antes ({@link #sistema()} é {@code MAC}, o sistema que esses testes sempre forçaram); só no Windows
 * muda, e aí é o que o app de produção faz.
 */
public final class SistemaDoTeste {

    private SistemaDoTeste() { }

    /** Este computador de teste é um Windows? */
    public static boolean windows() {
        return SistemaOperacional.detectar() == SistemaOperacional.WINDOWS;
    }

    /** O disco onde o teste grava tem permissões POSIX ({@code rwx------})? Mac e Linux sim; o NTFS não (lá valem as ACLs, de {@code ProtecaoAcl} e das provas do Windows). */
    public static boolean posix() {
        return java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    /**
     * Confere a permissão de um arquivo ou pasta que o app protege: num disco POSIX é o texto exato ({@code rw-------}, {@code rwx------}); no NTFS quem protege é a ACL
     * ({@code ProtecaoAcl}), que o app descreve como "só o ..." (a entrada do proprietário e mais ninguém), nunca como "... e mais N".
     */
    public static void conferirPrivado(String esperadoNoPosix, String descritoPeloApp) {
        if (posix()) {
            org.junit.jupiter.api.Assertions.assertEquals(esperadoNoPosix, descritoPeloApp);
        } else {
            org.junit.jupiter.api.Assertions.assertTrue(descritoPeloApp.startsWith("só o "), "a ACL deixa só o proprietário: " + descritoPeloApp);
        }
    }

    private static final java.util.regex.Pattern NOME_RESERVADO_DO_WINDOWS =
        java.util.regex.Pattern.compile("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?");

    /**
     * Este caminho relativo ({@code a/b/c.txt}) pode ser criado no disco onde o teste roda? No Mac e no Linux tudo pode (só a barra e o byte nulo não, e nem o teste os usa). No NTFS
     * não: um segmento com {@code < > : " | ? *} ou caractere de controle (quebra de linha), com espaço ou ponto no fim, ou nome reservado ({@code CON}, {@code NUL}, {@code COM1}...) é
     * recusado pelo Java ({@code InvalidPathException}) ou pelo próprio Windows. O teste que cria esse nome de propósito o pula SÓ no Windows, e a regra do app que ele prova continua
     * provada no Mac e no Linux (e em texto, sem tocar no disco, pelos testes de unidade).
     */
    public static boolean nomeValidoNoDisco(String relativo) {
        if (!windows()) {
            return true;
        }
        for (String segmento : relativo.split("/")) {
            if (segmento.isEmpty() || segmento.endsWith(" ") || segmento.endsWith(".") || NOME_RESERVADO_DO_WINDOWS.matcher(segmento).matches()) {
                return false;
            }
            for (char c : segmento.toCharArray()) {
                if (c < 32 || "<>:\"|?*\\".indexOf(c) >= 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /** O sistema que o teste de "Este computador" simula: o Windows no Windows (a pasta de teste é do Windows) e o Mac nos outros (a escolha de sempre dos testes). */
    public static SistemaOperacional sistema() {
        return windows() ? SistemaOperacional.WINDOWS : SistemaOperacional.MAC;
    }

    /** A plataforma de teste com a pasta pessoal dada (texto do sistema). */
    public static PlataformaLocal plataforma(Path casa) {
        return new PlataformaLocal(sistema(), casa.toString(), Map.of());
    }

    /**
     * A plataforma "Mac" para os testes de TELA e de fluxo que simulam um Mac com o {@code ComputadorSimulado} (brew, soquete do Docker, {@code /Users}): o que elas provam (os passos, os
     * botões, as frases) não depende do sistema, e as particularidades do Windows têm testes próprios de texto. A pasta pessoal é a de teste escrita como o Mac a escreveria ({@link #comoNoMac}).
     */
    public static PlataformaLocal plataformaDoMac(Path casa) {
        return new PlataformaLocal(SistemaOperacional.MAC, comoNoMac(casa), Map.of());
    }

    /**
     * A pasta como texto POSIX ({@code /Users/ana/x}): no Mac e no Linux é a própria; no Windows é o caminho sem o disco ({@code D:\\a\\x} vira {@code /a/x}), que o Java resolve no disco
     * atual: a pasta de teste e o diretório de trabalho estão no mesmo disco, então é a mesma pasta. Só serve onde nenhum código de produção troca o texto pelo caminho do app.
     */
    public static String comoNoMac(Path pasta) {
        if (!windows()) {
            return pasta.toString();
        }
        Path abs = pasta.toAbsolutePath();
        return "/" + abs.getRoot().relativize(abs).toString().replace('\\', '/');
    }

    /** A ponte entre o caminho do app e o do sistema, a deste computador (a que o app de produção usa). */
    public static CaminhosLocais caminhos() {
        return CaminhosLocais.doSistema();
    }

    /** O caminho do app de uma pasta ou arquivo deste computador ({@code /tmp/x} no Mac e no Linux, {@code /D:/a/x} no Windows). */
    public static String app(Path caminhoDoSistema) {
        return caminhos().texto(caminhoDoSistema);
    }

    /** O caminho do app do texto do sistema. */
    public static String app(String textoDoSistema) {
        return caminhos().doTextoDoSistema(textoDoSistema);
    }

    /** O texto do sistema de um caminho do app. */
    public static String doSistema(String caminhoDoApp) {
        return caminhos().paraTextoDoSistema(caminhoDoApp);
    }

    /**
     * O {@link Path} de um argumento de comando de teste, que pode estar nos dois formatos: o caminho do app ({@code /D:/a/x}, o que um servidor "por SSH" recebe) ou o texto do
     * sistema ({@code D:\\a\\x}, o que o computador local recebe). Fora do Windows os dois são o mesmo texto.
     */
    public static Path path(String argumento) {
        return Path.of(windows() && argumento.matches("^/[A-Za-z]:(/.*)?$") ? doSistema(argumento) : argumento);
    }

    /** O caminho do app como operação (para o {@code AmbienteDeProjetos}): igual ao que o app de produção passa. */
    public static UnaryOperator<String> paraOSistema() {
        return caminhos()::paraTextoDoSistema;
    }
}
