package br.com.labdesktop.ui.tema;

import java.awt.Font;
import java.awt.Window;
import java.util.Map;
import java.util.WeakHashMap;
import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;

/**
 * A escala central de fontes do app (ARQUITETURA 8.2): o ÚNICO lugar que decide quantos pontos tem cada texto. Os
 * componentes ({@code Rotulo}, {@code Botoes}, {@code Abas}, {@code Selo}...) pedem um tamanho NOMINAL (o número que o código
 * sempre usou, 12,5 para tabela, 10,5 para rótulo de coluna...) e este mapa o traduz para a escala de leitura confortável:
 *
 * <ul>
 *   <li>texto base: 14 (o padrão de todo componente padrão do Swing, pelo {@code defaultFont} do FlatLaf);</li>
 *   <li>secundário: no mínimo 13; tabelas: 13 a 14; rótulos de coluna e legendas: no mínimo 12 (nunca menos);</li>
 *   <li>valor grande dos indicadores: pelo menos 24.</li>
 * </ul>
 *
 * <p>Por cima vem o <b>fator do usuário</b> (Visualizar, "Aumentar texto": 100, 115 ou 130 %), lembrado nas preferências. Todo
 * componente cuja fonte sai daqui fica REGISTRADO (fraco, só a EDT mexe), e {@link #definirPercentual} refaz a fonte de todos
 * na hora, sem reiniciar. Nada de {@code deriveFont(12f)} solto pelas telas.
 */
public final class Fontes {

    /** O texto base em 100 %. */
    public static final int BASE = 14;
    /** O menor tamanho que qualquer texto do app tem em 100 % (o teste {@code TesteFontesMinimas} o vigia). */
    public static final int MINIMO = 12;
    /** O nominal do texto sem tamanho pedido (vira {@link #BASE}). */
    public static final float NOMINAL_PADRAO = 13f;
    /** O nominal do valor grande dos indicadores (vira 24 em 100 %). */
    public static final float NOMINAL_DO_VALOR_GRANDE = 22f;
    /** Os fatores que o menu oferece. */
    public static final int[] ESCALAS = {100, 115, 130};

    /** Família do texto: o {@code defaultFont} do FlatLaf. */
    public static final String TEXTO = "defaultFont";
    /** Família monoespaçada do app. */
    public static final String MONO = "monospaced.font";
    /** Família dos títulos de página. */
    public static final String TITULO = "h3.font";

    private record Pedido(String familia, int estilo, float nominal) { }

    private static final Map<JComponent, Pedido> REGISTRO = new WeakHashMap<>();
    private static int percentual = 100;

    private Fontes() { }

    /** O fator de hoje (100, 115 ou 130). */
    public static int percentual() {
        return percentual;
    }

    /** O fator válido mais perto do pedido. */
    public static int normalizar(int pedido) {
        int melhor = ESCALAS[0];
        for (int e : ESCALAS) {
            if (Math.abs(e - pedido) < Math.abs(melhor - pedido)) {
                melhor = e;
            }
        }
        return melhor;
    }

    /** O próximo fator maior (o último continua o último). */
    public static int maior(int atual) {
        for (int e : ESCALAS) {
            if (e > atual) {
                return e;
            }
        }
        return ESCALAS[ESCALAS.length - 1];
    }

    /** O próximo fator menor (o primeiro continua o primeiro). */
    public static int menor(int atual) {
        for (int i = ESCALAS.length - 1; i >= 0; i--) {
            if (ESCALAS[i] < atual) {
                return ESCALAS[i];
            }
        }
        return ESCALAS[0];
    }

    /**
     * Traduz o tamanho NOMINAL do código para o da escala (já com o fator): 10,5 e 11 viram 12; 11,5 vira 12,5; 12 vira 13;
     * 12,5 vira 13,5; 13 vira 14; 14 vira 15; os maiores sobem 2.
     */
    public static float escalar(float nominal) {
        float base;
        if (nominal <= 11f) {
            base = 12f;
        } else if (nominal <= 11.5f) {
            base = 12.5f;
        } else if (nominal <= 12f) {
            base = 13f;
        } else if (nominal <= 12.5f) {
            base = 13.5f;
        } else if (nominal <= 13f) {
            base = 14f;
        } else if (nominal <= 14f) {
            base = 15f;
        } else if (nominal <= 15f) {
            base = 16f;
        } else {
            base = nominal + 2f;
        }
        return Math.round(base * percentual / 100f * 2f) / 2f;
    }

    /** O tamanho do texto base com o fator (o do {@code defaultFont}). */
    public static float base() {
        return Math.round(BASE * percentual / 100f * 2f) / 2f;
    }

    /** Põe a fonte no componente e o registra: quando o fator mudar, ela é refeita. */
    public static void aplicar(JComponent c, String familia, int estilo, float nominal) {
        REGISTRO.put(c, new Pedido(familia, estilo, nominal));
        c.setFont(fonte(familia, estilo, nominal));
    }

    /** A fonte da família, no estilo e no tamanho nominal pedidos (com o fator). */
    public static Font fonte(String familia, int estilo, float nominal) {
        Font f = UIManager.getFont(familia);
        if (f == null) {
            f = UIManager.getFont(TEXTO);
        }
        if (f == null) {
            f = new Font(MONO.equals(familia) ? Font.MONOSPACED : Font.SANS_SERIF, Font.PLAIN, BASE);
        }
        return f.deriveFont(estilo, escalar(nominal));
    }

    /**
     * Instala o texto base ({@code defaultFont}) no tamanho de hoje: todo componente padrão do Swing (botão, campo, caixa de
     * seleção, menu, dica) o herda no próximo {@code FlatLaf.updateUI()}. Chame na EDT, depois de o tema existir.
     */
    public static void instalarBase() {
        Font atual = UIManager.getFont(TEXTO);
        if (atual != null) {
            UIManager.put(TEXTO, new FontUIResource(atual.deriveFont(base())));
        }
    }

    /**
     * Muda o fator (100, 115 ou 130) e refaz a fonte de todo componente registrado. Quem chama faz depois o
     * {@code FlatLaf.updateUI()} (que refaz os componentes padrão) e pede um novo layout. Na EDT.
     */
    public static void definirPercentual(int novo) {
        int n = normalizar(novo);
        if (n == percentual) {
            return;
        }
        percentual = n;
        instalarBase();
        for (JComponent c : new java.util.ArrayList<>(REGISTRO.keySet())) {
            Pedido p = REGISTRO.get(c);
            if (c != null && p != null) {
                c.setFont(fonte(p.familia(), p.estilo(), p.nominal()));
                c.revalidate();
                c.repaint();
            }
        }
        for (Window w : Window.getWindows()) {
            w.revalidate();
            w.repaint();
        }
    }

    /** Só para os testes: volta a 100 % sem refazer nada. */
    public static void reiniciarParaTeste() {
        percentual = 100;
        REGISTRO.clear();
    }
}
