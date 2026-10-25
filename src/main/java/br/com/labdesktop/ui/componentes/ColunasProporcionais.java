package br.com.labdesktop.ui.componentes;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;

/**
 * Colunas lado a lado cuja LARGURA é uma fração fixa do espaço (40% e 60%, por exemplo), qualquer que seja o conteúdo, e cuja
 * altura é a da mais alta. O {@code GridBagLayout} com pesos só divide a SOBRA depois de dar a cada coluna a largura que ela
 * pede, e uma coluna com uma fileira de botões largos roubava espaço da outra; aqui a proporção manda e o conteúdo se adapta
 * (o texto que não cabe termina em reticências). Todas as colunas têm a mesma altura, para as caixas ficarem alinhadas embaixo.
 */
public final class ColunasProporcionais implements LayoutManager {

    private final int espaco;
    private final double[] pesos;

    /**
     * @param espaco px entre as colunas
     * @param pesos  a fração de cada coluna (a soma não precisa dar 1: cada peso é dividido pela soma)
     */
    public ColunasProporcionais(int espaco, double... pesos) {
        this.espaco = espaco;
        this.pesos = pesos.clone();
    }

    @Override
    public void addLayoutComponent(String nome, Component c) { }

    @Override
    public void removeLayoutComponent(Component c) { }

    @Override
    public Dimension preferredLayoutSize(Container pai) {
        return medir(pai, false);
    }

    @Override
    public Dimension minimumLayoutSize(Container pai) {
        return medir(pai, true);
    }

    private Dimension medir(Container pai, boolean minimo) {
        synchronized (pai.getTreeLock()) {
            Insets in = pai.getInsets();
            int largura = 0;
            int altura = 0;
            int n = 0;
            for (Component c : pai.getComponents()) {
                if (!c.isVisible()) {
                    continue;
                }
                Dimension d = minimo ? c.getMinimumSize() : c.getPreferredSize();
                largura += d.width;
                altura = Math.max(altura, d.height);
                n++;
            }
            largura += Math.max(0, n - 1) * espaco;
            return new Dimension(largura + in.left + in.right, altura + in.top + in.bottom);
        }
    }

    @Override
    public void layoutContainer(Container pai) {
        synchronized (pai.getTreeLock()) {
            Insets in = pai.getInsets();
            Component[] filhos = pai.getComponents();
            int visiveis = 0;
            double soma = 0;
            for (int i = 0; i < filhos.length; i++) {
                if (filhos[i].isVisible()) {
                    visiveis++;
                    soma += peso(i);
                }
            }
            if (visiveis == 0) {
                return;
            }
            int util = pai.getWidth() - in.left - in.right - (visiveis - 1) * espaco;
            int alto = pai.getHeight() - in.top - in.bottom;
            int x = in.left;
            int feitos = 0;
            for (int i = 0; i < filhos.length; i++) {
                if (!filhos[i].isVisible()) {
                    continue;
                }
                feitos++;
                int w = feitos == visiveis ? in.left + (pai.getWidth() - in.left - in.right) - x
                    : (int) Math.round(util * peso(i) / soma);
                filhos[i].setBounds(x, in.top, Math.max(0, w), Math.max(0, alto));
                x += w + espaco;
            }
        }
    }

    private double peso(int i) {
        return i < pesos.length ? pesos[i] : 1;
    }
}
