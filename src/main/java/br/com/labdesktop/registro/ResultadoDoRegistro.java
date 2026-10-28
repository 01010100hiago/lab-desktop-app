package br.com.labdesktop.registro;

import java.util.List;

/**
 * O que uma leitura do registro devolveu. Selada: a tela trata cada caso. As linhas vêm da MAIS NOVA para a mais antiga. NÃO guarda a hora da leitura (a tela a anota quando a
 * resposta chega): assim duas leituras iguais são iguais e a tela não se redesenha à toa.
 */
public sealed interface ResultadoDoRegistro {

    /**
     * Deu para ler.
     *
     * @param linhas  da mais nova para a mais antiga
     * @param fonte   de onde elas vêm, em texto simples para o usuário ("do diário do sistema (journalctl)", "do arquivo /var/log/syslog", "do laboratório local: o que o agente escreve")
     * @param cortada havia mais linhas do que o app lê
     */
    record Lido(List<LinhaDeRegistro> linhas, String fonte, boolean cortada) implements ResultadoDoRegistro {
        public Lido {
            linhas = List.copyOf(linhas);
        }
    }

    /**
     * O usuário não pode ler o registro do sistema. O app NUNCA eleva privilégio sozinho: mostra a frase e o comando exato para o USUÁRIO rodar no servidor.
     *
     * @param frase              "Seu usuário não tem permissão para ler o registro do sistema."
     * @param comandoParaCopiar  {@code sudo usermod -aG systemd-journal <usuario>}
     * @param explicacao         o que o comando faz e o que fazer depois
     */
    record SemPermissao(String frase, String comandoParaCopiar, String explicacao) implements ResultadoDoRegistro { }

    /** Não há de onde ler (o servidor não tem registro nos lugares conhecidos, o Docker está fechado, o laboratório local ainda não existe...), com a frase simples do motivo. */
    record Indisponivel(String frase) implements ResultadoDoRegistro { }
}
