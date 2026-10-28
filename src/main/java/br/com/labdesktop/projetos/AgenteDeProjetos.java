package br.com.labdesktop.projetos;

import br.com.labdesktop.api.ErroApi;
import br.com.labdesktop.api.modelo.RespostaNovoProjeto;
import java.util.List;

/**
 * O que o app pede ao agente do lab para criar projeto. Uma tomada fina, para este pacote não depender de {@code servidor} (que o monta): quem
 * liga é a interface, com as {@code AcoesDoServidor}. Tudo bloqueia.
 */
public interface AgenteDeProjetos {

    /** Há conexão com o agente agora? ({@code false}: nenhum pedido sairia). */
    boolean conectado();

    /** {@code POST /api/projetos/novo}. */
    RespostaNovoProjeto criar(String nome, Boolean guiaIa) throws ErroApi;

    /** Os nomes que a lista de projetos do agente traz agora. */
    List<String> nomes() throws ErroApi;
}
