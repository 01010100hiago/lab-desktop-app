package br.com.labdesktop.teste;

import br.com.labdesktop.transporte.LancadorDeProcesso;
import br.com.labdesktop.transporte.LancadorReal;
import br.com.labdesktop.transporte.ProcessoExterno;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lança um processo de VERDADE (pelo {@link LancadorReal}, o mesmo de produção), mas no lugar do {@code aws} ou do {@code cloudflared} roda a {@link FerramentaFalsaDeTunel}, um programa
 * Java do próprio teste (nada de {@code sh}: roda igual no Windows). Os argumentos que o app montou chegam inteiros à ferramenta falsa; o primeiro (o caminho do programa) é que é trocado.
 */
public final class LancadorDeFerramentaFalsa implements LancadorDeProcesso {

    public record Lancado(List<String> argumentosDoApp, Map<String, String> ambienteDoApp, LancadorReal.ProcessoReal processo) { }

    public final List<Lancado> lancados = new CopyOnWriteArrayList<>();
    private final int portaDoAlvo;
    private final Map<String, String> variaveis;
    private final LancadorReal real = new LancadorReal();

    /** @param portaDoAlvo a porta do servidor (o SSHD embutido) para onde a ferramenta falsa repassa as conexões */
    public LancadorDeFerramentaFalsa(int portaDoAlvo, Map<String, String> variaveisDeComportamento) {
        this.portaDoAlvo = portaDoAlvo;
        this.variaveis = Map.copyOf(variaveisDeComportamento);
    }

    @Override
    public ProcessoExterno lancar(List<String> argumentos, Map<String, String> ambienteExtra) throws IOException {
        List<String> daFerramenta = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"),
            FerramentaFalsaDeTunel.class.getName()));
        daFerramenta.addAll(argumentos.subList(1, argumentos.size()));
        Map<String, String> ambiente = new HashMap<>(variaveis);
        ambiente.put("LABTEST_ALVO", Integer.toString(portaDoAlvo));
        // o PATH que o app pediu vai por outra variável (o PATH de verdade do filho precisa continuar achando o java)
        if (ambienteExtra.containsKey("PATH")) {
            ambiente.put("LABTEST_PATH_PEDIDO", ambienteExtra.get("PATH"));
        }
        LancadorReal.ProcessoReal p = (LancadorReal.ProcessoReal) real.lancar(daFerramenta, ambiente);
        lancados.add(new Lancado(List.copyOf(argumentos), Map.copyOf(ambienteExtra), p));
        return p;
    }
}
