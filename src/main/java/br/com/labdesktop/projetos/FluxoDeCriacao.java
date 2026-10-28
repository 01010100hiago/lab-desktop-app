package br.com.labdesktop.projetos;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Cria um projeto e transforma o resultado no que a tela diz, SEM nunca repetir o pedido. A regra do prazo: se a criação falha por tempo (ou
 * por um erro do servidor que deixa o resultado incerto), o app RELÊ a lista do servidor ({@link CriadorDeProjeto#existe}) em vez de tentar de
 * novo: o projeto aparece ("criado, o servidor só demorou") ou a frase manda conferir a lista ("talvez já tenha sido criado"). Bloqueia: thread de
 * fundo, nunca a EDT.
 */
public final class FluxoDeCriacao {

    /** O que aconteceu. */
    public enum Tipo {
        /** O projeto existe: pode ir para a página dele. */
        CRIADO,
        /** O resultado é incerto: a tela diz "talvez já tenha sido criado, confira a lista" e NÃO oferece repetir sozinha. */
        TALVEZ,
        /** Não foi criado; a frase diz por quê e o usuário pode corrigir e tentar de novo. */
        FALHOU,
        /** O servidor pede o código do 2FA ou recusou o token: o fluxo padrão do app mostra o aviso e a faixa. */
        NAO_AUTORIZADO,
        CANCELADO
    }

    /** O desfecho para a tela. {@code mensagem} é texto puro; {@code avisos} são os cuidados que o usuário deve ler. */
    public record Desfecho(Tipo tipo, String nome, String mensagem, List<String> avisos) {
        public Desfecho {
            avisos = List.copyOf(avisos);
        }

        /** O projeto pode existir (criado de certeza ou talvez): a lista precisa ser relida. */
        public boolean listaPrecisaRelerSe() {
            return tipo == Tipo.CRIADO || tipo == Tipo.TALVEZ;
        }
    }

    private FluxoDeCriacao() { }

    /** Cria {@code nome} (já conferido) e devolve o desfecho. Nunca lança por falha do projeto; só repassa a interrupção. */
    public static Desfecho criar(CriadorDeProjeto criador, String nome, Boolean guiaIa) {
        try {
            CriadorDeProjeto.Resultado r = criador.criar(nome, guiaIa);
            return new Desfecho(Tipo.CRIADO, nome, "Projeto " + nome + " criado.", r.avisos());
        } catch (FalhaDoProjeto f) {
            return switch (f.tipo()) {
                case INCERTO -> reler(criador, nome, f);
                case NAO_AUTORIZADO -> new Desfecho(Tipo.NAO_AUTORIZADO, nome, f.mensagem(), List.of());
                case CANCELADO -> new Desfecho(Tipo.CANCELADO, nome, f.mensagem(), List.of());
                default -> new Desfecho(Tipo.FALHOU, nome, f.mensagem(), List.of());
            };
        }
    }

    private static Desfecho reler(CriadorDeProjeto criador, String nome, FalhaDoProjeto falha) {
        Optional<Boolean> existe = criador.existe(nome);
        List<String> avisos = new ArrayList<>();
        if (existe.isPresent() && existe.get()) {
            avisos.add("O servidor demorou para responder, mas o projeto aparece na lista: está criado.");
            return new Desfecho(Tipo.CRIADO, nome, "Projeto " + nome + " criado.", avisos);
        }
        String frase = existe.isPresent()
            ? "O servidor não respondeu a tempo e o projeto " + nome + " ainda não aparece na lista. Talvez já tenha sido criado: confira a lista antes de tentar de novo."
            : "O servidor não respondeu a tempo e não deu para conferir a lista. Talvez o projeto " + nome + " já tenha sido criado: confira a lista antes de tentar de novo.";
        return new Desfecho(Tipo.TALVEZ, nome, frase, avisos);
    }
}
