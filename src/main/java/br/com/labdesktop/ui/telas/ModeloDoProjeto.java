package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.modelo.Container;
import br.com.labdesktop.api.modelo.DetalheProjeto;
import br.com.labdesktop.api.modelo.EstadoProjeto;
import br.com.labdesktop.api.modelo.Limite;
import br.com.labdesktop.servidor.NivelDeSaude;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.TextosDeLimite;
import br.com.labdesktop.ui.telas.ModeloDeKpis.Kpi;
import br.com.labdesktop.util.Formato;
import java.time.Instant;
import java.util.List;

/**
 * O que a página de UM projeto sabe tirar do detalhe do agente, já em decisões e em texto: o estado, as somas dos containers,
 * quais botões fazem sentido agora e a linha de até cinco indicadores. Puro (sem Swing, sem relógio: "há 3 d" se calcula na
 * hora de mostrar), então cada regra tem teste. É o par, para o detalhe, do {@link LinhaDeProjeto} (que serve à lista).
 *
 * @param detalhe          o detalhe do projeto como o agente mandou
 * @param claudeDoServidor {@code recursos.claude} de {@code /api/versao} ({@code null}: o agente não diz; vale a presença de {@code sessao})
 */
public record ModeloDoProjeto(DetalheProjeto detalhe, Boolean claudeDoServidor) {

    /** A linha de indicadores tem até cinco: estado, containers, CPU, memória e consumo da semana. */
    public static final int MAXIMO_DE_INDICADORES = 5;

    public static ModeloDoProjeto de(DetalheProjeto detalhe) {
        return new ModeloDoProjeto(detalhe, null);
    }

    public static ModeloDoProjeto de(DetalheProjeto detalhe, Boolean claudeDoServidor) {
        return new ModeloDoProjeto(detalhe, claudeDoServidor);
    }

    public String nome() {
        return detalhe.nome();
    }

    /** Sem compose, nunca subiu, no ar (todos rodando), parcial ou parado: a mesma regra da lista. */
    public EstadoProjeto estado() {
        if (detalhe.composes().isEmpty()) {
            return EstadoProjeto.SEM_COMPOSE;
        }
        if (detalhe.containers().isEmpty()) {
            return EstadoProjeto.NUNCA_SUBIU;
        }
        int rodando = rodando();
        if (rodando == detalhe.containers().size()) {
            return EstadoProjeto.NO_AR;
        }
        return rodando > 0 ? EstadoProjeto.PARCIAL : EstadoProjeto.PARADO;
    }

    public Gravidade gravidade() {
        return LinhaDeProjeto.gravidadeDo(estado());
    }

    public int rodando() {
        return (int) detalhe.containers().stream().filter(Container::rodando).count();
    }

    public int total() {
        return detalhe.containers().size();
    }

    public boolean temCompose() {
        return !detalhe.composes().isEmpty();
    }

    /** Os containers existem e ao menos um roda: o botão é Parar. */
    public boolean mostrarParar() {
        return temCompose() && rodando() > 0;
    }

    /** Os containers estão parados ou nunca subiram: o botão é Iniciar. */
    public boolean mostrarIniciar() {
        return temCompose() && rodando() == 0;
    }

    /** A soma da CPU dos containers que rodam, ou {@code null} se nenhum deu leitura. */
    public Double cpuSomada() {
        List<Double> leituras = detalhe.containers().stream().filter(Container::rodando).map(Container::cpu)
            .filter(c -> c != null).toList();
        return leituras.isEmpty() ? null : leituras.stream().mapToDouble(Double::doubleValue).sum();
    }

    /** A soma da memória (bytes) dos containers que rodam, ou {@code null} se nenhum deu leitura. */
    public Long memoriaSomada() {
        List<Long> leituras = detalhe.containers().stream().filter(Container::rodando).map(Container::mem)
            .filter(m -> m != null).toList();
        return leituras.isEmpty() ? null : leituras.stream().mapToLong(Long::longValue).sum();
    }

    /**
     * O servidor tem sessões do Claude. O agente diz em {@code recursos.claude} de {@code /api/versao}; sem isso (agente antigo) vale o
     * detalhe: o objeto {@code sessao} (aberta ou não). Sem Claude o app não mostra nada dele. Hoje o agente do lab sempre traz
     * {@code sessao}; um agente sem Claude devolve {@code sessao: null} (ARQUITETURA 7.8).
     */
    public boolean temClaude() {
        return claudeDoServidor != null ? claudeDoServidor : detalhe.sessao() != null;
    }

    public boolean sessaoAberta() {
        return detalhe.sessao() != null && detalhe.sessao().estaAberta();
    }

    public Instant sessaoDesde() {
        return detalhe.sessao() == null ? null : detalhe.sessao().desde();
    }

    /** A sessão aberta não está na imagem atual (o agente diz que falta algo): oferece Reabrir. */
    public boolean sessaoDesatualizada() {
        var info = detalhe.sessao() == null ? null : detalhe.sessao().info();
        return sessaoAberta() && info != null && Boolean.FALSE.equals(info.atualizada());
    }

    /** O que falta na sessão desatualizada, em uma linha (vazio se o agente não disse). */
    public String motivoDaSessaoDesatualizada() {
        var info = detalhe.sessao() == null ? null : detalhe.sessao().info();
        return info == null || info.faltando().isEmpty() ? "" : String.join(", ", info.faltando());
    }

    public Limite limite() {
        return detalhe.limite();
    }

    public boolean pausado() {
        return detalhe.limite() != null && detalhe.limite().pausado();
    }

    /** "Abrir Claude": sem sessão e sem pausa (pausada, o caminho é Retomar). */
    public boolean oferecerAbrirClaude() {
        return temClaude() && !sessaoAberta() && !pausado();
    }

    /** "Retomar": a sessão está pausada pelo limite ou pelo freio. */
    public boolean oferecerRetomar() {
        return pausado();
    }

    /** {@code main@7be0d41}, ou {@code null} se a pasta não é repositório. */
    public String git() {
        return detalhe.git() == null ? null : detalhe.git().branch() + "@" + detalhe.git().commit();
    }

    /**
     * O estado do código em palavras simples: {@code Branch main, com mudanças que ainda não foram salvas no git},
     * {@code Branch main, tudo salvo} ou {@code Sem git na pasta principal}. Nunca o hash cru. É o que o AGENTE vê: ele só olha a pasta principal, e o repositório de muitos projetos mora numa
     * subpasta ({@code app/.git}); por isso a frase não diz "sem git" do projeto, e a linha "Código" a troca pela leitura do próprio app assim que ela chega.
     */
    public String textoDoCodigo() {
        if (detalhe.git() == null) {
            return "Sem git na pasta principal";
        }
        String branch = detalhe.git().branch() == null || detalhe.git().branch().isBlank() ? "sem nome" : detalhe.git().branch();
        return "Branch " + branch + (gitSujo() ? ", com mudanças que ainda não foram salvas no git" : ", tudo salvo");
    }

    public boolean gitSujo() {
        return detalhe.git() != null && Boolean.TRUE.equals(detalhe.git().sujo());
    }

    /** O instante da última mexida na pasta ({@code mexido} vem em milissegundos), ou {@code null}. */
    public Instant mexido() {
        return detalhe.mexido() == null ? null : Instant.ofEpochMilli(detalhe.mexido());
    }

    /** Há containers que dá para ver nos logs. */
    public boolean temContainers() {
        return !detalhe.containers().isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // Indicadores
    // ---------------------------------------------------------------------------------------------

    /** Os cinco indicadores da Visão geral, nesta ordem: estado, containers, CPU, memória e consumo da semana. */
    public List<Kpi> indicadores() {
        List<Kpi> todos = new java.util.ArrayList<>(List.of(indicadorDoEstado(), indicadorDeContainers(), indicadorDeCpu(), indicadorDeMemoria()));
        if (temClaude()) {
            todos.add(indicadorDeConsumo()); // sem Claude não há consumo nem limite do plano
        }
        return List.copyOf(todos);
    }

    private Kpi indicadorDoEstado() {
        EstadoProjeto e = estado();
        String valor = switch (e) {
            case NO_AR -> "No ar";
            case PARCIAL -> "Parcial";
            case PARADO -> "Parado";
            case NUNCA_SUBIU -> "Nunca subiu";
            case SEM_COMPOSE -> "Sem compose";
        };
        String legenda = switch (e) {
            case NO_AR -> "todos rodando";
            case PARCIAL -> "alguns parados";
            case PARADO -> "nenhum rodando";
            case NUNCA_SUBIU -> "ainda não foi iniciado";
            case SEM_COMPOSE -> "sem arquivo compose";
        };
        NivelDeSaude nivel = switch (e) {
            case NO_AR -> NivelDeSaude.OK;
            case PARCIAL, SEM_COMPOSE -> NivelDeSaude.ATENCAO;
            case PARADO, NUNCA_SUBIU -> NivelDeSaude.SEM_DADO;
        };
        boolean semSelo = nivel == NivelDeSaude.SEM_DADO; // parado é um estado normal, não um problema nem um dado que falta
        return new Kpi("estado", "ESTADO", "pulso", valor, legenda, "Estado do projeto: " + valor + ", " + legenda + ".", nivel,
            valor, semSelo ? "" : null);
    }

    private Kpi indicadorDeContainers() {
        int total = total();
        int rodando = rodando();
        if (total == 0) {
            return new Kpi("containers", "CONTAINERS", "docker", Formato.SEM_DADO, "nenhum container criado",
                "O projeto ainda não tem containers.", NivelDeSaude.SEM_DADO, "", "");
        }
        NivelDeSaude nivel = rodando == total ? NivelDeSaude.OK : rodando > 0 ? NivelDeSaude.ATENCAO : NivelDeSaude.SEM_DADO;
        return new Kpi("containers", "CONTAINERS", "docker", rodando + " de " + total, "rodando agora",
            rodando + " de " + total + " containers rodando agora.", nivel, "", nivel == NivelDeSaude.SEM_DADO ? "" : null);
    }

    private Kpi indicadorDeCpu() {
        Double cpu = cpuSomada();
        String valor = cpu == null ? Formato.SEM_DADO : Formato.fmtNum(cpu, 1) + "%";
        return new Kpi("cpu", "CPU", "cpu", valor, "dos containers rodando",
            "Uso do processador somado nos containers que rodam (100% é um núcleo inteiro).", NivelDeSaude.SEM_DADO, "", "");
    }

    private Kpi indicadorDeMemoria() {
        Long mem = memoriaSomada();
        String valor = mem == null ? Formato.SEM_DADO : Formato.fmtBytes(mem);
        return new Kpi("memoria", "MEMÓRIA", "memoria", valor, "dos containers rodando",
            "Memória usada somada nos containers que rodam.", NivelDeSaude.SEM_DADO, "", "");
    }

    private Kpi indicadorDeConsumo() {
        Limite l = limite();
        if (l == null) {
            return new Kpi("consumo", "CONSUMO", "moeda", Formato.SEM_DADO, "sem leitura",
                "O agente ainda não informou o consumo da semana.", NivelDeSaude.SEM_DADO, "", "");
        }
        String dicaBase = "Fatia estimada da semana do plano usada por este projeto. ";
        if (l.tetoPct() == null) {
            return new Kpi("consumo", "CONSUMO", "moeda", TextosDeLimite.fmtUsado(l), "da semana, sem limite",
                dicaBase + "Nenhum limite definido: abra a aba Consumo e limite para definir um.", NivelDeSaude.SEM_DADO, "", "");
        }
        boolean liberado = Boolean.TRUE.equals(l.liberado());
        NivelDeSaude nivel = l.pausado() ? NivelDeSaude.CRITICO
            : "atencao".equals(l.estado()) ? NivelDeSaude.ATENCAO : NivelDeSaude.OK;
        String selo = l.pausado() ? "Pausado" : liberado ? "Liberado" : null;
        String legenda = liberado ? "liberado na semana" : "de " + br.com.labdesktop.util.Formato.fmtNum(l.tetoPct()) + "% da semana";
        String dica = dicaBase + TextosDeLimite.texto(l) + (l.pausado() ? ". A sessão do Claude está pausada." : ".");
        return new Kpi("consumo", "CONSUMO", "moeda", TextosDeLimite.fmtUsado(l), legenda, dica, nivel, "", selo);
    }
}
