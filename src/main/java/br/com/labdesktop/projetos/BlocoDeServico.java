package br.com.labdesktop.projetos;

import java.util.List;

/**
 * O que um modelo de container acrescenta a um projeto: o serviço (as linhas já recuadas para ficar sob {@code services:}), os volumes
 * nomeados (recuadas para ficar sob {@code volumes:}) e as variáveis que vão para o {@code .env} (NUNCA para o compose: o compose só
 * as referencia por {@code ${NOME}}).
 *
 * @param servico         o nome do serviço no compose
 * @param titulo          o nome do modelo, para a tela ("Banco Postgres")
 * @param linhasDoServico as linhas do serviço, com dois espaços de recuo (a primeira é {@code "  nome:"})
 * @param linhasDeVolumes as linhas dos volumes nomeados, com dois espaços de recuo; vazio sem volume
 * @param variaveis       as variáveis que o serviço precisa no {@code .env}
 */
public record BlocoDeServico(String servico, String titulo, List<String> linhasDoServico, List<String> linhasDeVolumes, List<Variavel> variaveis) {

    /** Uma variável do {@code .env}: o app só GERA o valor de uma senha (aleatória) e nunca o mostra. */
    public record Variavel(String nome, Tipo tipo) {
        public enum Tipo { SENHA_ALEATORIA }
    }

    public BlocoDeServico {
        linhasDoServico = List.copyOf(linhasDoServico);
        linhasDeVolumes = List.copyOf(linhasDeVolumes);
        variaveis = List.copyOf(variaveis);
    }

    public boolean temVolumes() {
        return !linhasDeVolumes.isEmpty();
    }

    /**
     * O texto para quem prefere copiar e colar (um compose que o app não reescreve): as três partes, cada uma com a frase de onde vai. As
     * senhas aparecem como o que o usuário deve escrever, nunca como um valor.
     */
    public String textoParaCopiar() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 1) Em \"services:\" do docker-compose.yml, acrescente:\n");
        linhasDoServico.forEach(l -> sb.append(l).append('\n'));
        if (temVolumes()) {
            sb.append("\n# 2) Em \"volumes:\" (no nível de cima, sem recuo; crie a seção se não existir), acrescente:\n");
            sb.append("volumes:\n");
            linhasDeVolumes.forEach(l -> sb.append(l).append('\n'));
        }
        if (!variaveis.isEmpty()) {
            sb.append("\n# ").append(temVolumes() ? "3" : "2").append(") No arquivo .env (nunca no compose), acrescente:\n");
            variaveis.forEach(v -> sb.append(v.nome()).append("=escolha-uma-senha-forte\n"));
        }
        return sb.toString();
    }
}
