package br.com.labdesktop.local;

import br.com.labdesktop.instalador.ComandosRemotos;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interpreta o texto das LEITURAS do Docker de {@link ComandosLocais}. Os formatos não levam aspas ({@code {{.Labels}}} em vez de
 * {@code {{.Label "x"}}}): no Windows o Java passa os argumentos ao {@code docker.exe} numa linha só, e aspas dentro de um argumento chegam
 * quebradas. O Docker devolve os rótulos como {@code chave=valor,chave=valor}; aqui se acha a chave que interessa.
 */
final class LeituraDoDocker {

    private LeituraDoDocker() { }

    /** Um container lido por {@link ComandosLocais#lerContainerPeloNome}. */
    record Container(String nome, String rotuloDoAgente, String rotuloLocal, String estado) {
        /** Foi o laboratório local que o criou: tem o rótulo do agente do app E o rótulo local. Um painel instalado por SSH só tem o primeiro. */
        boolean doApp() {
            return !rotuloDoAgente.isBlank();
        }

        boolean rodando() {
            return "running".equals(estado);
        }
    }

    /** Uma rede lida por {@link ComandosLocais#lerRede}. */
    record Rede(String nome, String rotuloLocal) {
        boolean doApp() {
            return !rotuloLocal.isBlank();
        }
    }

    /** O valor do rótulo na lista {@code a=b,c=d} do Docker; vazio se a chave não está. */
    static String rotulo(String rotulos, String chave) {
        Matcher m = Pattern.compile("(?:^|,)" + Pattern.quote(chave) + "=([^,]*)").matcher(rotulos == null ? "" : rotulos);
        return m.find() ? m.group(1).strip() : "";
    }

    /** {@code nome|rótulos|estado}: o nome é o que vem antes do primeiro {@code |} e o estado o que vem depois do último. */
    static List<Container> containers(String saida) {
        List<Container> l = new ArrayList<>();
        for (String linha : saida.split("\\R")) {
            int a = linha.indexOf('|');
            int z = linha.lastIndexOf('|');
            if (a < 0 || z <= a) {
                continue;
            }
            String rotulos = linha.substring(a + 1, z);
            l.add(new Container(linha.substring(0, a).strip(), rotulo(rotulos, ComandosRemotos.ROTULO_DO_AGENTE),
                rotulo(rotulos, ComandosLocais.ROTULO_LOCAL), linha.substring(z + 1).strip()));
        }
        return l;
    }

    /** {@code nome|rótulos}. */
    static List<Rede> redes(String saida) {
        List<Rede> l = new ArrayList<>();
        for (String linha : saida.split("\\R")) {
            int a = linha.indexOf('|');
            if (a < 0) {
                continue;
            }
            l.add(new Rede(linha.substring(0, a).strip(), rotulo(linha.substring(a + 1), ComandosLocais.ROTULO_LOCAL)));
        }
        return l;
    }
}
