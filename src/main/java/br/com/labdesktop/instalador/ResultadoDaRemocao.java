package br.com.labdesktop.instalador;

import java.util.List;

/**
 * Como terminou "Remover agente".
 *
 * @param removido  o container do agente saiu
 * @param feito     o que foi removido, em frases
 * @param avisos    o que não deu certo ou não se aplicou (a remoção segue e conta)
 * @param recusada  o container existe e não é do app: nada foi tocado
 */
public record ResultadoDaRemocao(boolean removido, List<String> feito, List<String> avisos, boolean recusada) {

    public ResultadoDaRemocao {
        feito = List.copyOf(feito);
        avisos = List.copyOf(avisos);
    }
}
