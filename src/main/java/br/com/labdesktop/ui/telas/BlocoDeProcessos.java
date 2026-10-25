package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.modelo.DetalheServidor;
import br.com.labdesktop.api.modelo.InfoServidor;
import br.com.labdesktop.api.modelo.InfoServidor.Processo;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.componentes.TabelaPaginada;
import br.com.labdesktop.ui.componentes.TabelaPaginada.Coluna;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * "Processos que mais gastam": os 20 programas que mais usam o processador, com o dono (usuário), CPU, memória e o
 * projeto ou container a que pertencem. O projeto vem do detalhe do servidor quando ele já chegou; antes disso, deduz-se
 * do nome do container da sessão ({@code lab-claude-<projeto>}) e, se não der, mostra só o container. CPU em branco
 * (traço) é normal na primeira varredura do agente.
 */
final class BlocoDeProcessos extends CaixaDoServidor {

    private static final long serialVersionUID = 1L;
    private static final String PREFIXO_DA_SESSAO = "lab-claude-";

    /** Uma linha, com os números crus (a ordenação usa eles, não o texto). */
    record Linha(String nome, String usuario, Double cpu, Long mem, String origem) { }

    private static final Comparator<Double> CPU = Comparator.nullsFirst(Comparator.<Double>naturalOrder());
    private static final Comparator<Long> MEM = Comparator.nullsFirst(Comparator.<Long>naturalOrder());

    private final TabelaPaginada<Linha> tabela = new TabelaPaginada<>(20, List.of(
        new Coluna<>("Programa", 1.0, false, true, l -> l.nome(), null),
        new Coluna<>("Usuário", 0.7, false, false, l -> l.usuario(), null),
        new Coluna<>("CPU", 0.4, true, false, l -> FormatosDoServidor.percentual(l.cpu()), Comparator.comparing(Linha::cpu, CPU)),
        new Coluna<>("Memória", 0.5, true, false, l -> FormatosDoServidor.bytes(l.mem()), Comparator.comparing(Linha::mem, MEM)),
        new Coluna<>("Projeto / container", 1.6, false, false, l -> l.origem(), null)));

    BlocoDeProcessos() {
        super("Processos que mais gastam", "processo");
        apoio(Rotulo.dica("os 20 que mais usam o processador · clique em CPU ou Memória para ordenar"));
        linha(tabela, 0);
        atualizar(null, null);
    }

    void atualizar(InfoServidor info, DetalheServidor detalhe) {
        Map<Integer, String> projetoPorPid = new HashMap<>();
        if (detalhe != null) {
            for (Processo p : detalhe.processos()) {
                if (p.pid() != null) {
                    projetoPorPid.put(p.pid(), p.projeto() == null ? "Sem dono" : p.projeto());
                }
            }
        }
        List<Linha> linhas = new ArrayList<>();
        for (Processo p : info == null ? List.<Processo>of() : info.processos()) {
            linhas.add(new Linha(p.nome() == null ? FormatosDoServidor.SEM_DADO : p.nome(),
                p.usuario() == null ? FormatosDoServidor.SEM_DADO : p.usuario(), p.cpu(), p.mem(),
                origem(p, projetoPorPid.get(p.pid()))));
        }
        tabela.definir(linhas, "Sem dados de processos ainda.");
        terminar();
    }

    /** O texto da coluna "Projeto / container". */
    static String origem(Processo p, String projetoDoDetalhe) {
        String container = p.container();
        String projeto = projetoDoDetalhe != null ? projetoDoDetalhe : p.projeto();
        if (projeto == null && container != null && container.startsWith(PREFIXO_DA_SESSAO)) {
            projeto = container.substring(PREFIXO_DA_SESSAO.length());
        }
        if ("(servidor)".equals(projeto)) {
            return "o próprio servidor (fora de container)";
        }
        if ("(lab)".equals(projeto)) {
            projeto = "infraestrutura do lab";
        }
        if (container == null) {
            return projeto == null ? "o próprio servidor (fora de container)" : projeto;
        }
        return projeto == null ? container : projeto + " · " + container;
    }

    /** O texto das linhas da tela, na ordem. */
    List<List<String>> linhas() {
        return tabela.linhasDaPagina();
    }

    TabelaPaginada<Linha> tabela() {
        return tabela;
    }
}
