package br.com.labdesktop.ui.componentes;

import java.time.Duration;
import java.time.Instant;

/**
 * O estado de uma tela que espera a resposta do servidor, como modelo PURO (sem Swing, sem relógio próprio: quem usa passa o "agora"). A regra que o usuário
 * pediu: o "carregando" aparece NA HORA, some quando chega o dado ou vira erro, e NUNCA fica preso: passado o prazo vira "demorou mais que o normal" com
 * a saída "tentar de novo".
 *
 * <pre>
 *   CARREGANDO --(chegou o dado)--> PRONTO
 *   CARREGANDO --(passou o prazo)--> DEMOROU --(chegou o dado)--> PRONTO
 *   CARREGANDO ou DEMOROU --(falhou)--> ERRO
 *   DEMOROU ou ERRO --(tentar de novo)--> CARREGANDO (o prazo recomeça)
 * </pre>
 *
 * Imutável: cada transição devolve o estado novo (o mesmo objeto quando nada muda).
 *
 * @param fase     onde está
 * @param inicio   quando a espera atual começou (só vale em CARREGANDO e DEMOROU)
 * @param prazo    quanto esperar antes de dizer que demorou
 * @param mensagem a frase do erro (só em ERRO)
 */
public record EstadoDeCarga(Fase fase, Instant inicio, Duration prazo, String mensagem) {

    /** Os momentos de uma espera. */
    public enum Fase { CARREGANDO, DEMOROU, ERRO, PRONTO }

    /** Quanto esperar antes de dizer "demorou mais que o normal". */
    public static final Duration PRAZO_PADRAO = Duration.ofSeconds(15);

    public static final String FRASE_DEMOROU = "Demorou mais que o normal.";
    public static final String DICA_DEMOROU = "O servidor ainda não respondeu. Você pode esperar mais um pouco ou tentar de novo.";
    public static final String BOTAO_TENTAR_DE_NOVO = "Tentar de novo";

    public EstadoDeCarga {
        prazo = prazo == null || prazo.isNegative() || prazo.isZero() ? PRAZO_PADRAO : prazo;
        mensagem = mensagem == null ? "" : mensagem;
    }

    /** Começa a esperar agora. */
    public static EstadoDeCarga carregando(Instant agora, Duration prazo) {
        return new EstadoDeCarga(Fase.CARREGANDO, agora, prazo, "");
    }

    /** A frase padrão do indicador: {@code frase("projetos")} é "Carregando projetos...". */
    public static String frase(String assunto) {
        return "Carregando " + assunto + "...";
    }

    /** O relógio andou: passado o prazo, vira DEMOROU. */
    public EstadoDeCarga passou(Instant agora) {
        if (fase == Fase.CARREGANDO && !agora.isBefore(inicio.plus(prazo))) {
            return new EstadoDeCarga(Fase.DEMOROU, inicio, prazo, "");
        }
        return this;
    }

    /** O dado chegou (de qualquer fase: uma resposta atrasada ainda vale). */
    public EstadoDeCarga chegou() {
        return fase == Fase.PRONTO ? this : new EstadoDeCarga(Fase.PRONTO, inicio, prazo, "");
    }

    /** A consulta falhou. Com o dado já na tela (PRONTO) nada muda: a tela segue com a última leitura. */
    public EstadoDeCarga falhou(String frase) {
        if (fase == Fase.PRONTO) {
            return this;
        }
        return new EstadoDeCarga(Fase.ERRO, inicio, prazo, frase);
    }

    /** "Tentar de novo": sai do DEMOROU e do ERRO e recomeça a contar; nas outras fases não faz nada. */
    public EstadoDeCarga tentarDeNovo(Instant agora) {
        if (fase == Fase.DEMOROU || fase == Fase.ERRO) {
            return new EstadoDeCarga(Fase.CARREGANDO, agora, prazo, "");
        }
        return this;
    }

    /** O indicador de carregando (o girando e a frase) está à vista? */
    public boolean mostraIndicador() {
        return fase == Fase.CARREGANDO;
    }

    /** Falta alguma saída para o usuário (tentar de novo)? */
    public boolean oferecerTentarDeNovo() {
        return fase == Fase.DEMOROU || fase == Fase.ERRO;
    }
}
