package br.com.labdesktop.transporte;

import java.util.List;
import java.util.Optional;

/**
 * O que "Conferir as ferramentas neste computador" achou: por programa, ONDE ele está (ou que não está) e, se não está, o passo de instalação do sistema em que o app roda, como
 * TEXTO para copiar. Sem segredo.
 */
public record RelatorioDeFerramentas(List<Item> itens) {

    public RelatorioDeFerramentas {
        itens = List.copyOf(itens);
    }

    /**
     * @param ferramenta     o programa
     * @param caminho        onde foi achado, ou vazio
     * @param versao         a primeira linha do {@code --version} (redigida, curta), ou vazio se não rodou ou não respondeu a tempo
     * @param passoDeInstalar as linhas a copiar para instalar (só quando não foi achado)
     */
    public record Item(FerramentaExterna ferramenta, Optional<String> caminho, String versao, List<String> passoDeInstalar) {

        public Item {
            passoDeInstalar = List.copyOf(passoDeInstalar);
        }

        public boolean achada() {
            return caminho.isPresent();
        }

        /** "encontrada em /caminho (versão)" ou "não encontrada". */
        public String situacao() {
            if (caminho.isEmpty()) {
                return "não encontrada";
            }
            return "encontrada em " + caminho.get() + (versao.isEmpty() ? "" : " (" + versao + ")");
        }

        public String textoParaCopiar() {
            return String.join("\n", passoDeInstalar);
        }
    }

    public Optional<Item> de(FerramentaExterna f) {
        return itens.stream().filter(i -> i.ferramenta() == f).findFirst();
    }
}
