package br.com.labdesktop.registro;

import java.util.List;
import java.util.Locale;

/**
 * O filtro rápido da caixa de registro: um texto (que vale para a origem e a mensagem, sem diferenciar maiúscula de minúscula nem acento de... só maiúscula) e uma severidade mínima.
 * {@code TUDO} mostra tudo; {@code AVISOS} mostra avisos E erros ("pelo menos aviso"); {@code ERROS} só os erros. Puro.
 */
public record FiltroDeRegistro(String texto, Nivel nivel) {

    /** Os três botões do filtro de severidade. */
    public enum Nivel {
        TUDO("Tudo", Severidade.INFO),
        AVISOS("Avisos", Severidade.AVISO),
        ERROS("Erros", Severidade.ERRO);

        private final String rotulo;
        private final Severidade minima;

        Nivel(String rotulo, Severidade minima) {
            this.rotulo = rotulo;
            this.minima = minima;
        }

        public String rotulo() {
            return rotulo;
        }

        public Severidade minima() {
            return minima;
        }
    }

    public static final FiltroDeRegistro NENHUM = new FiltroDeRegistro("", Nivel.TUDO);

    public FiltroDeRegistro {
        texto = texto == null ? "" : texto.strip();
        nivel = nivel == null ? Nivel.TUDO : nivel;
    }

    /** A linha passa pelo filtro? */
    public boolean aceita(LinhaDeRegistro l) {
        if (!l.severidade().alemDe(nivel.minima())) {
            return false;
        }
        if (texto.isEmpty()) {
            return true;
        }
        String t = texto.toLowerCase(Locale.ROOT);
        return l.mensagem().toLowerCase(Locale.ROOT).contains(t) || l.origem().toLowerCase(Locale.ROOT).contains(t);
    }

    /** As linhas que passam, na mesma ordem. */
    public List<LinhaDeRegistro> aplicar(List<LinhaDeRegistro> linhas) {
        return linhas.stream().filter(this::aceita).toList();
    }

    public boolean ativo() {
        return !texto.isEmpty() || nivel != Nivel.TUDO;
    }
}
