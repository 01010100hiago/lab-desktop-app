package br.com.labdesktop.ajuda;

import java.util.Arrays;
import java.util.Optional;

/**
 * As telas que um link da Ajuda pode ABRIR ({@code [Ir para Projetos](ir:projetos)}). Só telas: nenhum destino faz uma ação de escrita nem abre um diálogo que escreve.
 * A lista é fechada: um link que cita outro id é erro do texto, e o teste da Ajuda o pega.
 */
public enum Destino {
    SERVIDORES("servidores", "Servidores"),
    RESUMO("resumo", "Resumo"),
    SEGURANCA("seguranca", "Segurança"),
    CONTAINERS("containers", "Containers"),
    PROCESSOS("processos", "Processos"),
    MAQUINA("maquina", "Máquina"),
    PROJETOS("projetos", "Projetos"),
    ARQUIVOS("arquivos", "Arquivos"),
    TERMINAL("terminal", "Terminal"),
    CLOUDFLARE("cloudflare", "Cloudflare"),
    AJUSTES_APARENCIA("ajustes-aparencia", "Aparência"),
    AJUSTES_NAVEGACAO("ajustes-navegacao", "Navegação"),
    AJUSTES_TERMINAL("ajustes-terminal", "Terminal (Ajustes)"),
    AJUSTES_SOBRE("ajustes-sobre", "Sobre");

    private final String id;
    private final String nomeDaTela;

    Destino(String id, String nomeDaTela) {
        this.id = id;
        this.nomeDaTela = nomeDaTela;
    }

    public String id() {
        return id;
    }

    /** O nome da tela como o usuário a lê. */
    public String nomeDaTela() {
        return nomeDaTela;
    }

    public static Optional<Destino> de(String id) {
        return Arrays.stream(values()).filter(d -> d.id.equals(id)).findFirst();
    }
}
