package br.com.labdesktop.instalador;

import br.com.labdesktop.seguranca.TextoSeguro;

/**
 * O resultado de UMA verificação prévia, em palavras simples: a marca (ok, aviso ou problema), a frase e, quando falta algo que o
 * app não pode (nem deve) fazer sozinho, o comando EXATO que o usuário, ou quem administra o servidor, precisa rodar. A frase pode
 * citar texto que veio do servidor (nome da distribuição), então é limpa na construção.
 *
 * @param item             o que foi verificado
 * @param estado           ok, aviso (não impede) ou problema (impede a instalação)
 * @param frase            o que foi encontrado e o que significa, em português simples
 * @param comandoSugerido  o comando exato para resolver, ou {@code null}
 */
public record ResultadoDaVerificacao(Item item, Estado estado, String frase, String comandoSugerido) {

    /** O que se verifica, na ordem em que a tela mostra. */
    public enum Item {
        SISTEMA("Sistema"),
        ARQUITETURA("Arquitetura"),
        PERMISSAO("Permissão do usuário"),
        SHELL("Shell do usuário"),
        TMUX("tmux"),
        DOCKER("Docker"),
        COMPOSE("Docker Compose"),
        CONTAINER("Agente já instalado"),
        PORTA("Porta do agente"),
        DISCO("Espaço em disco"),
        INTERNET("Saída para a internet");

        private final String rotulo;

        Item(String rotulo) {
            this.rotulo = rotulo;
        }

        public String rotulo() {
            return rotulo;
        }
    }

    /** A marca: ok, aviso ou problema. Estado nunca só pela cor: a tela põe ícone e palavra. */
    public enum Estado {
        OK, AVISO, PROBLEMA
    }

    public ResultadoDaVerificacao {
        if (item == null || estado == null) {
            throw new IllegalArgumentException("item e estado");
        }
        frase = TextoSeguro.limparLinha(frase == null ? "" : frase);
    }

    public static ResultadoDaVerificacao ok(Item item, String frase) {
        return new ResultadoDaVerificacao(item, Estado.OK, frase, null);
    }

    public static ResultadoDaVerificacao aviso(Item item, String frase, String comando) {
        return new ResultadoDaVerificacao(item, Estado.AVISO, frase, comando);
    }

    public static ResultadoDaVerificacao problema(Item item, String frase, String comando) {
        return new ResultadoDaVerificacao(item, Estado.PROBLEMA, frase, comando);
    }

    /** Impede a instalação. */
    public boolean bloqueia() {
        return estado == Estado.PROBLEMA;
    }
}
