package br.com.labdesktop.servidor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * O retrato de segurança do servidor (somente leitura), lido por SSH {@code exec} com comandos fixos
 * ({@link LeitorDeSegurancaPorSsh}). Cada seção é independente: a que falha vira {@link Leitura#indisponivel} ou
 * {@link Leitura#semPermissao} e as outras continuam.
 *
 * @param lidoEm quando a leitura terminou
 */
public record ResumoDeSeguranca(
    Instant lidoEm,
    Leitura<Tentativas> tentativas,
    Leitura<List<PortaAberta>> portas,
    Leitura<ResumoDoFirewall> firewall,
    Leitura<ConfiguracaoDoSsh> ssh,
    Leitura<EstadoFail2ban> fail2ban) {

    /** Uma seção: o que deu para ler, com a razão quando não deu. */
    public record Leitura<T>(Situacao situacao, T dados) {

        /** Por que a seção tem ou não tem dados. */
        public enum Situacao { LIDA, SEM_PERMISSAO, INDISPONIVEL }

        public static <T> Leitura<T> lida(T dados) {
            return new Leitura<>(Situacao.LIDA, dados);
        }

        public static <T> Leitura<T> semPermissao() {
            return new Leitura<>(Situacao.SEM_PERMISSAO, null);
        }

        public static <T> Leitura<T> indisponivel() {
            return new Leitura<>(Situacao.INDISPONIVEL, null);
        }

        public boolean lida() {
            return situacao == Situacao.LIDA && dados != null;
        }
    }

    /** Como uma tentativa de entrada falhou, em palavras que o usuário entende. */
    public enum TipoDeTentativa {
        ADIVINHAR_SENHA("Tentou adivinhar a senha de um usuário que existe"),
        USUARIO_INVENTADO("Tentou usuários que não existem (admin, oracle...)"),
        VARREDURA("Varredura ou protocolo estranho (só procurando portas abertas)"),
        OUTROS("Outras falhas");

        private final String descricao;

        TipoDeTentativa(String descricao) {
            this.descricao = descricao;
        }

        public String descricao() {
            return descricao;
        }
    }

    /** Um IP que tentou entrar. */
    public record Origem(String ip, int tentativas, boolean redeLocal) { }

    /** Um login que deu certo. {@code impressao} e {@code algoritmo} vêm vazios quando entrou com senha. */
    public record LoginAceito(Instant quando, String usuario, String ip, int porta, String metodo, String algoritmo,
                              String impressao, boolean foraDaRedeLocal) { }

    /**
     * As tentativas de entrada por SSH. Uma "tentativa" é uma conexão que falhou (o mesmo IP e a mesma porta de origem
     * contam uma vez, mesmo com várias senhas erradas). Tipos e origens valem para os 7 dias.
     *
     * @param parcial            a saída do servidor passou do teto: os números são "pelo menos"
     * @param foraDaRedeEm24h    logins aceitos nas últimas 24 h vindos de fora da rede local
     */
    public record Tentativas(int em24h, int em7d, boolean parcial, Map<TipoDeTentativa, Integer> porTipo,
                             List<Origem> origens, int totalDeOrigens, List<LoginAceito> logins, int foraDaRedeEm24h) { }

    /** Uma porta que escuta fora do loopback. {@code escopo}: "todas as interfaces" ou o endereço específico. */
    public record PortaAberta(int porta, String escopo, String processo) { }

    /**
     * O firewall em linguagem simples. {@code identificado} falso = nenhum dos motores conhecidos respondeu.
     *
     * @param politica "bloqueia" ou "aceita" o que não tem regra, ou {@code null} se não deu para saber
     * @param regras   uma frase por regra
     */
    public record ResumoDoFirewall(boolean identificado, String motor, String politica, List<String> regras) { }

    /** O que importa do {@code sshd -T}. Campos {@code null} = a diretiva não apareceu. */
    public record ConfiguracaoDoSsh(Integer porta, Boolean senhaLigada, String loginDoRoot, Boolean chaveLigada,
                                    Integer maxTentativas) {

        /** Só entra quem tem chave. */
        public boolean soChave() {
            return Boolean.FALSE.equals(senhaLigada) && !Boolean.FALSE.equals(chaveLigada);
        }

        /** O root não pode entrar por SSH. */
        public boolean rootDesligado() {
            return "no".equals(loginDoRoot);
        }
    }

    /** O bloqueio automático de IPs. {@code ativo} falso = ausente ou parado. */
    public record EstadoFail2ban(boolean ativo, Integer banidosAgora, Integer totalBanidos, List<String> ips) { }
}
