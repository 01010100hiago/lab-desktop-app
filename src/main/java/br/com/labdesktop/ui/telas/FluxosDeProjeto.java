package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.api.ErroApi.Conflito;
import br.com.labdesktop.api.ErroApi.ErroRede;
import br.com.labdesktop.api.modelo.Limite;
import br.com.labdesktop.servidor.AcoesDoServidor;
import br.com.labdesktop.servidor.Inscricao;
import br.com.labdesktop.servidor.ServidorLab;
import br.com.labdesktop.ui.MensagensDeErro;
import br.com.labdesktop.ui.Tarefas;
import br.com.labdesktop.ui.TextosDeLimite;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.dialogos.Apresentador;
import br.com.labdesktop.ui.dialogos.DialogoConfirmar;
import br.com.labdesktop.ui.dialogos.DialogoLimite;
import br.com.labdesktop.ui.dialogos.DialogoLimiteExcedido;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * As ações sobre um projeto e o que acontece em volta delas (ARQUITETURA 8.3, tradução do {@code index.html}): iniciar,
 * parar, reiniciar, abrir, fechar e reabrir a sessão do Claude, definir o limite e Retomar, com o diálogo do 409 ("ainda
 * acima do limite"). Serve à lista de projetos e à faixa das sessões pausadas.
 *
 * <p>Regras do painel web que vêm junto: o botão fica ocupado até a ação terminar (iniciar e parar podem levar
 * minutos); a mensagem sai num aviso; estourar o prazo NÃO marca falha, o resultado é incerto e a tela consulta o
 * estado ({@code AcoesDoServidor} já força o refresh depois, com sucesso ou não); POST e PUT nunca são reenviados
 * sozinhos. Tudo de rede roda fora da EDT; esta classe só é chamada na EDT.
 *
 * <p><b>Modo somente leitura.</b> Construída com {@code liberadas == false}, a tela esconde os botões e cada método de ação
 * recusa a chamada com um aviso, sem tocar no servidor. Um clique acidental em "parar" num projeto de um servidor real roda
 * {@code compose down} de até 12 min; "Abrir Claude" abre uma sessão de verdade (com {@code pg_dump} antes). A aplicação
 * liga as ações por padrão ({@link #liberadasPorPropriedade()}); {@code -Dlabdesktop.acoes=false} as desliga.
 */
public final class FluxosDeProjeto {

    public static final String INICIAR = "iniciar";
    public static final String PARAR = "parar";
    public static final String REINICIAR = "reiniciar";
    public static final String ABRIR = "abrir";
    public static final String FECHAR = "fechar";
    public static final String REABRIR = "reabrir";
    public static final String RETOMAR = "retomar";
    public static final String LIMITE = "limite";
    public static final String CODIGO = "codigo";
    public static final String MENSAGEM = "mensagem";
    /** Prefixo da ação de reiniciar UM container: a chave é {@code container:<nome>}, uma por container. */
    public static final String PREFIXO_DE_CONTAINER = "container:";
    /** Prefixo de uma tecla enviada à sessão: a chave é {@code tecla:<Enter|Escape...>}. */
    public static final String PREFIXO_DE_TECLA = "tecla:";

    /** A propriedade de sistema que liga ou desliga as ações de escrita. */
    public static final String PROPRIEDADE_ACOES = "labdesktop.acoes";

    /** Uma chamada ao agente. */
    @FunctionalInterface
    private interface Chamada<T> {
        T executar() throws ErroApi;
    }

    private final ServidorLab servidor;
    private final Notificacoes avisos;
    private final Apresentador apresentador;
    private final boolean liberadas;
    private final Map<String, Set<String>> ocupadas = new HashMap<>();
    private final List<Consumer<String>> ouvintes = new CopyOnWriteArrayList<>();

    /** Somente leitura: as ações ficam escondidas e recusadas. */
    public FluxosDeProjeto(ServidorLab servidor, Notificacoes avisos, Apresentador apresentador) {
        this(servidor, avisos, apresentador, false);
    }

    /**
     * @param liberadas {@code true} para as ações de escrita funcionarem (o padrão da aplicação, ou o
     *                  desenvolvedor com {@code -Dlabdesktop.acoes=true}); veja {@link #liberadasPorPropriedade()}
     */
    public FluxosDeProjeto(ServidorLab servidor, Notificacoes avisos, Apresentador apresentador, boolean liberadas) {
        this.servidor = servidor;
        this.avisos = avisos;
        this.apresentador = apresentador;
        this.liberadas = liberadas;
    }

    /** O que a aplicação passa ao construtor: as ações só ligam com {@code -Dlabdesktop.acoes=true}. */
    public static boolean liberadasPorPropriedade() {
        return Boolean.getBoolean(PROPRIEDADE_ACOES);
    }

    /** Quem mostra os avisos (a página do projeto usa os mesmos da lista). */
    Notificacoes avisos() {
        return avisos;
    }

    /** Quem mostra os diálogos (o gerenciador de arquivos usa o mesmo da lista). */
    Apresentador apresentador() {
        return apresentador;
    }

    /** {@code true} se as ações de escrita funcionam; a tela esconde os botões quando for {@code false}. */
    public boolean liberadas() {
        return liberadas;
    }

    /**
     * No modo somente leitura nenhuma ação de escrita sai do app. Devolve {@code true} (e avisa o usuário) quando a chamada deve parar.
     */
    private boolean somenteLeitura() {
        if (liberadas) {
            return false;
        }
        avisos.atencao("A lista de projetos está em modo somente leitura: iniciar, parar e abrir o Claude estão desligados. "
            + "Use o painel web ou ligue as ações (-Dlabdesktop.acoes=true).");
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // Ocupação
    // ---------------------------------------------------------------------------------------------

    /** As ações em andamento de um projeto. */
    public Set<String> ocupadas(String projeto) {
        return Set.copyOf(ocupadas.getOrDefault(projeto, Set.of()));
    }

    public boolean ocupado(String projeto, String acao) {
        return ocupadas.getOrDefault(projeto, Set.of()).contains(acao);
    }

    /** Avisa (na EDT) o nome do projeto cuja ocupação mudou. */
    public Inscricao observar(Consumer<String> ouvinte) {
        ouvintes.add(ouvinte);
        return () -> ouvintes.remove(ouvinte);
    }

    private boolean marcar(String projeto, String acao) {
        Set<String> conj = ocupadas.computeIfAbsent(projeto, k -> new HashSet<>());
        if (!conj.add(acao)) {
            return false; // o clique repetido enquanto a ação roda não dispara outra
        }
        ouvintes.forEach(o -> o.accept(projeto));
        return true;
    }

    private void soltar(String projeto, String acao) {
        Set<String> conj = ocupadas.get(projeto);
        if (conj != null && conj.remove(acao)) {
            if (conj.isEmpty()) {
                ocupadas.remove(projeto);
            }
            ouvintes.forEach(o -> o.accept(projeto));
        }
    }

    private <T> void rodar(String projeto, String acao, Chamada<T> chamada, Consumer<T> aoConcluir,
                           Consumer<Throwable> aoFalhar) {
        if (!marcar(projeto, acao)) {
            return;
        }
        Tarefas.rodar(chamada::executar, resultado -> {
            soltar(projeto, acao);
            aoConcluir.accept(resultado);
        }, falha -> {
            soltar(projeto, acao);
            aoFalhar.accept(falha);
        });
    }

    /** O tratamento comum de falha: rede incerta, conflito (sessão pausada...) e erro de verdade. */
    private void falhou(String projeto, Throwable falha) {
        String nome = TextosDeLimite.nomeAmigavel(projeto);
        if (falha instanceof ErroRede) {
            avisos.atencao("Sem resposta do servidor para " + nome + ". A ação pode ter funcionado: conferindo o estado…");
        } else if (falha instanceof Conflito c) {
            avisos.atencao(nome + ": " + c.mensagem());
        } else {
            avisos.erro("Erro em " + nome + ": " + MensagensDeErro.de(falha));
        }
    }

    private AcoesDoServidor acoes() {
        return servidor.acoes();
    }

    // ---------------------------------------------------------------------------------------------
    // Ações
    // ---------------------------------------------------------------------------------------------

    public void iniciar(String projeto) {
        if (somenteLeitura()) {
            return;
        }
        rodar(projeto, INICIAR, () -> acoes().iniciar(projeto), r -> avisos.ok(projeto + ": no ar."), f -> falhou(projeto, f));
    }

    public void parar(String projeto) {
        if (somenteLeitura()) {
            return;
        }
        rodar(projeto, PARAR, () -> acoes().parar(projeto), r -> avisos.ok(projeto + ": parado."), f -> falhou(projeto, f));
    }

    public void reiniciar(String projeto) {
        if (somenteLeitura()) {
            return;
        }
        rodar(projeto, REINICIAR, () -> acoes().reiniciar(projeto), r -> avisos.ok(projeto + ": reiniciado."),
            f -> falhou(projeto, f));
    }

    /** Abre a sessão do Claude (idempotente). Sem terminal nativo ainda: o aviso diz isso com franqueza. */
    public void abrirSessao(String projeto) {
        if (somenteLeitura()) {
            return;
        }
        rodar(projeto, ABRIR, () -> acoes().abrirSessao(projeto),
            r -> avisos.ok(TextosDeLimite.nomeAmigavel(projeto) + ": sessão do Claude aberta. O terminal nativo ainda não "
                + "existe no app: na aba Sessão do Claude do projeto dá para ler a tela e escrever mensagens; o resto, pelo painel web."),
            f -> falhou(projeto, f));
    }

    public void fecharSessao(String projeto) {
        if (somenteLeitura()) {
            return;
        }
        rodar(projeto, FECHAR, () -> {
            acoes().fecharSessao(projeto);
            return Boolean.TRUE;
        }, r -> avisos.ok(TextosDeLimite.nomeAmigavel(projeto) + ": sessão do Claude encerrada."), f -> falhou(projeto, f));
    }

    /** Envia o código de login do Claude (o alerta "Login do Claude pendente"). */
    public void enviarCodigoDeLogin(String projeto, String codigo) {
        if (somenteLeitura()) {
            return;
        }
        rodar(projeto, CODIGO, () -> {
            acoes().enviarCodigoDeLogin(projeto, codigo);
            return Boolean.TRUE;
        }, r -> avisos.ok(projeto + ": código enviado para o terminal."), f -> falhou(projeto, f));
    }

    /** Pergunta antes: a sessão atual é encerrada e a tarefa em andamento, interrompida. */
    public void pedirReabrir(String projeto) {
        if (somenteLeitura()) {
            return;
        }
        apresentador.mostrar(new DialogoConfirmar("Reabrir sessão do Claude", "atualizar",
            "A sessão atual será encerrada; tarefa em andamento é interrompida; a conversa é retomada com --continue quando "
                + "existir para este caminho (sessões antigas em /workspace começam do zero); o harness retoma o fluxo com "
                + "/constellation:resume.", "Reabrir", false, sim -> {
                    if (sim) {
                        reabrirSessao(projeto);
                    }
                }));
    }

    void reabrirSessao(String projeto) {
        if (somenteLeitura()) {
            return;
        }
        rodar(projeto, REABRIR, () -> acoes().reabrirSessao(projeto),
            r -> avisos.ok(TextosDeLimite.nomeAmigavel(projeto)
                + ": sessão reaberta (retomando com --continue quando houver histórico)."),
            f -> falhou(projeto, f));
    }

    /**
     * Retomar: tira a pausa e reabre a sessão. Um 409 com os quatro números abre o diálogo "ainda acima do limite", com
     * os dois caminhos do painel web: aumentar o limite (e tentar de novo) ou liberar até a semana virar.
     */
    public void retomar(String projeto, Limite limiteAtual) {
        if (somenteLeitura()) {
            return;
        }
        retomar(projeto, limiteAtual, false);
    }

    private void retomar(String projeto, Limite limiteAtual, boolean liberar) {
        String nome = TextosDeLimite.nomeAmigavel(projeto);
        rodar(projeto, RETOMAR, () -> acoes().retomar(projeto, liberar, true), resposta ->
            avisos.ok(nome + ": " + (liberar ? "liberada até a semana virar; " : "")
                + (resposta.sessao() != null ? "sessão retomada." : "pausa removida.")),
            falha -> {
                if (falha instanceof Conflito c && c.aindaEstoura()) {
                    abrirExcedido(projeto, c, limiteAtual);
                } else {
                    falhou(projeto, falha);
                }
            });
    }

    private void abrirExcedido(String projeto, Conflito conflito, Limite limiteAtual) {
        apresentador.mostrar(new DialogoLimiteExcedido(projeto, conflito, limiteAtual, Instant.now(), ZoneId.systemDefault(),
            escolha -> {
                switch (escolha) {
                    case AUMENTAR -> definirLimite(projeto, limiteAtual, () -> {
                        avisos.info("Retomando " + TextosDeLimite.nomeAmigavel(projeto) + "…");
                        retomar(projeto, limiteAtual, false);
                    });
                    case LIBERAR -> {
                        avisos.info("Liberando e retomando " + TextosDeLimite.nomeAmigavel(projeto) + "…");
                        retomar(projeto, limiteAtual, true);
                    }
                    case CANCELAR -> { /* nada a fazer */ }
                }
            }));
    }

    /** O diálogo "Definir limite"; {@code depois} roda na EDT quando o agente aceita. */
    public void definirLimite(String projeto, Limite limiteAtual, Runnable depois) {
        if (somenteLeitura()) {
            return;
        }
        apresentador.mostrar(new DialogoLimite(projeto, limiteAtual, teto -> {
            acoes().definirLimite(projeto, teto);
        }, teto -> {
            avisos.ok(TextosDeLimite.nomeAmigavel(projeto) + ": "
                + (teto == null ? "limite removido." : "limite de " + teto + "% da semana definido."));
            if (depois != null) {
                depois.run();
            }
        }));
    }

    // ---------------------------------------------------------------------------------------------
    // Ações da página do projeto
    // ---------------------------------------------------------------------------------------------

    /** A chave de ocupação do botão Reiniciar de um container. */
    public static String chaveDoContainer(String container) {
        return PREFIXO_DE_CONTAINER + container;
    }

    /** {@code docker restart} de um container do projeto (o botão da tabela de containers). */
    public void reiniciarContainer(String projeto, String container) {
        if (somenteLeitura()) {
            return;
        }
        rodar(projeto, chaveDoContainer(container), () -> {
            acoes().reiniciarContainer(projeto, container);
            return Boolean.TRUE;
        }, r -> avisos.ok(projeto + ": " + container + " reiniciado."), f -> falhou(projeto, f));
    }

    /**
     * Escreve uma mensagem na sessão do Claude (e dá Enter). {@code aoConcluir} roda na EDT só quando o agente aceitou: é
     * quando a caixa de texto pode ser limpa (se falhar, o que o usuário escreveu fica lá).
     */
    public void enviarMensagem(String projeto, String texto, Runnable aoConcluir) {
        if (somenteLeitura()) {
            return;
        }
        rodar(projeto, MENSAGEM, () -> {
            acoes().enviarMensagem(projeto, texto);
            return Boolean.TRUE;
        }, r -> {
            if (aoConcluir != null) {
                aoConcluir.run();
            }
        }, f -> falhou(projeto, f));
    }

    /** Envia uma tecla (Enter, Escape) à sessão do Claude. */
    public void enviarTecla(String projeto, String tecla) {
        if (somenteLeitura()) {
            return;
        }
        rodar(projeto, PREFIXO_DE_TECLA + tecla, () -> {
            acoes().enviarTecla(projeto, tecla);
            return Boolean.TRUE;
        }, r -> { }, f -> falhou(projeto, f));
    }

    /**
     * "Liberar até a semana virar": o projeto fica sem teto e sem freio até a semana do plano virar, e a sessão volta. É uma
     * decisão que gasta crédito da conta, por isso pergunta antes (o diálogo do 409 já oferece o mesmo caminho, mas aqui o
     * usuário chega sem ter passado por ele).
     */
    public void liberar(String projeto, Limite limiteAtual) {
        if (somenteLeitura()) {
            return;
        }
        apresentador.mostrar(new DialogoConfirmar("Liberar até a semana virar", "play",
            "O projeto fica sem limite e sem freio até a semana do plano virar, e a sessão do Claude volta a abrir. O "
                + "consumo continua sendo contado na conta.", "Liberar", false, sim -> {
                    if (sim) {
                        avisos.info("Liberando e retomando " + TextosDeLimite.nomeAmigavel(projeto) + "…");
                        retomar(projeto, limiteAtual, true);
                    }
                }));
    }
}
