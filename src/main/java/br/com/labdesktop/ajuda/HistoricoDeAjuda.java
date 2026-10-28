package br.com.labdesktop.ajuda;

import java.util.ArrayList;
import java.util.List;

/**
 * Os tópicos que o usuário já leu, para os botões Voltar e Avançar (como num navegador): abrir um tópico novo apaga o que estava "à frente"; abrir o mesmo tópico de
 * novo não duplica. Modelo puro.
 */
public final class HistoricoDeAjuda {

    private final List<String> lidos = new ArrayList<>();
    private int posicao = -1;

    /** O usuário abriu este tópico (pelo clique, pela busca ou por um "Veja também"). */
    public void abrir(String id) {
        if (id == null || id.equals(atual())) {
            return;
        }
        while (lidos.size() > posicao + 1) {
            lidos.remove(lidos.size() - 1);
        }
        lidos.add(id);
        posicao = lidos.size() - 1;
    }

    /** O tópico aberto agora, ou {@code null}. */
    public String atual() {
        return posicao < 0 ? null : lidos.get(posicao);
    }

    public boolean podeVoltar() {
        return posicao > 0;
    }

    public boolean podeAvancar() {
        return posicao >= 0 && posicao < lidos.size() - 1;
    }

    /** Volta um tópico; devolve o novo atual (ou o mesmo, se não há para onde voltar). */
    public String voltar() {
        if (podeVoltar()) {
            posicao--;
        }
        return atual();
    }

    public String avancar() {
        if (podeAvancar()) {
            posicao++;
        }
        return atual();
    }

    /** O tópico lido logo antes do atual (o que "Comece aqui" oferece para o usuário continuar de onde parou), ou {@code null}. */
    public String anterior() {
        return posicao > 0 ? lidos.get(posicao - 1) : null;
    }

    /** O último tópico lido que NÃO é o atual, mesmo que o usuário tenha voltado no histórico (o mais recente da lista inteira), ou {@code null}. */
    public String maisRecenteQueNaoEOAtual() {
        for (int i = lidos.size() - 1; i >= 0; i--) {
            if (!lidos.get(i).equals(atual())) {
                return lidos.get(i);
            }
        }
        return null;
    }

    /** Todos os lidos, do mais antigo ao mais novo (para os testes). */
    public List<String> lidos() {
        return List.copyOf(lidos);
    }
}
