package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.modelo.DetalheServidor;
import br.com.labdesktop.api.modelo.InfoServidor;
import br.com.labdesktop.api.modelo.InfoServidor.Processo;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.telas.TabelaDeLinhas.Coluna;
import java.util.ArrayList;
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

    private final TabelaDeLinhas tabela = new TabelaDeLinhas(new Coluna("Programa", 1.0, false, true),
        new Coluna("Usuário", 0.7, false, false), new Coluna("CPU", 0.4, true, false), new Coluna("Memória", 0.5, true, false),
        new Coluna("Projeto / container", 1.6, false, false));

    BlocoDeProcessos() {
        super("Processos que mais gastam", "processo");
        apoio(Rotulo.dica("os 20 que mais usam o processador"));
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
        List<List<String>> linhas = new ArrayList<>();
        for (Processo p : info == null ? List.<Processo>of() : info.processos()) {
            linhas.add(List.of(p.nome() == null ? FormatosDoServidor.SEM_DADO : p.nome(),
                p.usuario() == null ? FormatosDoServidor.SEM_DADO : p.usuario(), FormatosDoServidor.percentual(p.cpu()),
                FormatosDoServidor.bytes(p.mem()), origem(p, projetoPorPid.get(p.pid()))));
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

    List<List<String>> linhas() {
        return tabela.linhas();
    }
}
