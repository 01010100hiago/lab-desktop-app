package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.modelo.EstadoProjeto;
import br.com.labdesktop.api.modelo.GitInfo;
import br.com.labdesktop.api.modelo.InfoSessao;
import br.com.labdesktop.api.modelo.Limite;
import br.com.labdesktop.api.modelo.Projeto;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.Severidade;
import br.com.labdesktop.ui.TextosDeLimite;
import br.com.labdesktop.util.Formato;
import java.time.Instant;

/**
 * O que UMA linha da lista de projetos mostra, já em texto (ARQUITETURA 8.3, tradução do {@code linhaProjeto} do
 * {@code index.html}). É o retrato imutável entre o DTO do agente e os componentes Swing: a tela só desenha isto.
 * Como é {@code record}, dois retratos iguais são {@code equals}, e a lista atualiza por nome SEM recriar nem
 * redesenhar a linha que não mudou (preserva foco, menu aberto e o que o usuário digitou).
 *
 * <p>Nada aqui depende do relógio: "há 5 min" e afins são calculados na hora de mostrar, a partir dos
 * {@code Instant} guardados, para o retrato só mudar quando o DADO muda.
 */
public record LinhaDeProjeto(
    String nome,
    String descricao,
    boolean semDocker,
    EstadoProjeto estado,
    Gravidade gravidadeDoEstado,
    boolean temCompose,
    boolean algumContainerRodando,
    /** {@code 2/3}, ou {@code null} sem compose (a tela mostra o travessão). */
    String containers,
    String cpu,
    String memoria,
    Consumo consumo,
    LimiteDaLinha limite,
    String git,
    boolean gitSujo,
    String urlPrincipal,
    int urlsExtras,
    boolean sessaoAberta,
    Claude claude,
    String urlDeLogin,
    boolean oferecerAbrirClaude,
    boolean oferecerReiniciarSessao,
    boolean oferecerDefinirLimite,
    boolean oferecerFecharSessao) {

    /** O consumo de 7 dias (US$ equivalente de API, não é cobrança); {@code null} sem uso registrado. */
    public record Consumo(String custo, String tokens) { }

    /** A segunda linha da célula de consumo: a fatia da semana contra o teto do projeto. */
    public record LimiteDaLinha(
        String texto,
        String descricao,
        Severidade severidade,
        boolean temBarra,
        double fracao,
        boolean pausado,
        boolean liberado) { }

    /** O que a coluna "Claude" mostra. */
    public enum TipoDeClaude { LOGIN_PENDENTE, SESSAO_ABERTA, PAUSADA, NENHUMA }

    /**
     * A coluna "Claude".
     *
     * @param desatualizada   a sessão aberta não está na imagem atual (oferece Reabrir)
     * @param motivoDesatualizada o que falta, para a dica do selo
     * @param pausadaPorFreio quando pausada: freio global (âmbar) em vez de teto do projeto (terracota)
     * @param rotuloDaPausa   {@code pausada pelo limite} ou {@code pausada pelo freio}
     * @param pausadoEm       desde quando, para a dica ("há 5 min" calculado na hora de mostrar)
     * @param dicaDaPausa     explicação sem a parte do tempo
     */
    public record Claude(
        TipoDeClaude tipo,
        boolean desatualizada,
        String motivoDesatualizada,
        boolean pausadaPorFreio,
        String rotuloDaPausa,
        Instant pausadoEm,
        String dicaDaPausa) { }

    /** Monta o retrato de um projeto (a regra de {@code estadoProjeto}, {@code claudeCelula} e {@code acoesLinha} do front). */
    public static LinhaDeProjeto de(Projeto p) {
        EstadoProjeto estado = p.estado();
        long rodando = p.containers().stream().filter(c -> c.rodando()).count();

        Consumo consumo = null;
        if (p.tokens() != null) {
            consumo = new Consumo(Formato.fmtUsd(p.tokens().semanaCustoUsd()),
                " · " + Formato.fmtCompacto(p.tokens().semanaSaida()));
        }

        LimiteDaLinha limite = null;
        Limite l = p.limite();
        if (l != null) {
            String texto = TextosDeLimite.texto(l) + (Boolean.TRUE.equals(l.liberado()) ? " · liberado" : "");
            String descricao = "Limite de " + p.nome() + ": " + texto + (l.pausado() ? " — sessão pausada" : "");
            limite = new LimiteDaLinha(texto, descricao, TextosDeLimite.severidade(l), TextosDeLimite.temBarra(l),
                Math.min(1.0, TextosDeLimite.fracaoDoTeto(l)), l.pausado(), Boolean.TRUE.equals(l.liberado()));
        }

        GitInfo g = p.git();
        String git = g == null ? null : g.branch() + "@" + g.commit();
        boolean gitSujo = g != null && Boolean.TRUE.equals(g.sujo());

        InfoSessao info = p.sessaoInfo();
        boolean desatualizada = p.temSessaoClaude() && info != null && !Boolean.TRUE.equals(info.atualizada());
        String falta = info == null || info.faltando().isEmpty() ? "" : String.join(", ", info.faltando());

        TipoDeClaude tipo;
        if (p.temLoginPendente()) {
            tipo = TipoDeClaude.LOGIN_PENDENTE;
        } else if (p.temSessaoClaude()) {
            tipo = TipoDeClaude.SESSAO_ABERTA;
        } else if (p.pausado()) {
            tipo = TipoDeClaude.PAUSADA;
        } else {
            tipo = TipoDeClaude.NENHUMA;
        }
        boolean freio = TextosDeLimite.pausaPorFreio(l);
        String dicaPausa = !p.pausado() ? ""
            : freio ? "Pausada pelo freio global: a semana da conta passou do percentual de freio."
                : "Pausada pelo limite do projeto: " + TextosDeLimite.texto(l) + ".";
        Claude claude = new Claude(tipo, desatualizada, falta, freio, TextosDeLimite.rotuloPausa(l),
            l != null && l.pausa() != null ? l.pausa().em() : null, dicaPausa);

        return new LinhaDeProjeto(
            p.nome(), p.descricao() == null ? "" : p.descricao(), Boolean.FALSE.equals(p.acessoDocker()),
            estado, gravidadeDo(estado),
            !p.composes().isEmpty(), rodando > 0,
            p.composes().isEmpty() ? null : rodando + "/" + p.containers().size(),
            p.cpu() == null ? null : Formato.fmtNum(p.cpu(), 1) + "%",
            p.mem() == null ? null : Formato.fmtBytes(p.mem()),
            consumo, limite, git, gitSujo,
            p.urls().isEmpty() ? null : p.urls().get(0), Math.max(0, p.urls().size() - 1),
            p.temSessaoClaude(), claude,
            p.temLoginPendente() ? p.login().url() : null,
            !p.temSessaoClaude() && !p.pausado(),
            p.temSessaoClaude() && info != null && Boolean.TRUE.equals(info.atualizada()),
            l != null,
            p.temSessaoClaude());
    }

    /** O estado exibido na coluna Estado e a cor do ponto. */
    public static Gravidade gravidadeDo(EstadoProjeto estado) {
        return switch (estado) {
            case NO_AR -> Gravidade.OK;
            case PARCIAL, SEM_COMPOSE -> Gravidade.ATENCAO;
            case PARADO, NUNCA_SUBIU -> Gravidade.NEUTRO;
        };
    }

    /** "No ar" = TODOS os containers rodando (não confundir com {@code Projeto.rodando}, que vale com ao menos um). */
    public boolean estaNoAr() {
        return estado == EstadoProjeto.NO_AR;
    }

    /** Os containers existem e ao menos um roda: o botão da linha é Parar. */
    public boolean mostrarParar() {
        return temCompose && algumContainerRodando;
    }

    /** Os containers estão parados ou nunca subiram: o botão da linha é Iniciar. */
    public boolean mostrarIniciar() {
        return temCompose && !algumContainerRodando;
    }

    /** Há alguma ação no menu "mais ações" (Reiniciar, Reiniciar sessão, Definir limite, Fechar sessão). */
    public boolean temMenu() {
        return temCompose || oferecerReiniciarSessao || oferecerDefinirLimite || oferecerFecharSessao;
    }

    /** Linhas com alerta (login pendente) ganham o friso âmbar à esquerda e a faixa de login embaixo. */
    public boolean temAlerta() {
        return claude.tipo() == TipoDeClaude.LOGIN_PENDENTE;
    }
}
