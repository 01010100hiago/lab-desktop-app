package br.com.labdesktop.ui;

import br.com.labdesktop.plataforma.SistemaOperacional;

/**
 * Qual Look and Feel do FlatLaf o app usa: {@link #MAC} ({@code FlatMacLightLaf} e {@code FlatMacDarkLaf}, com a barra de menus do macOS e o jeito dos controles do Mac) no Mac, e
 * {@link #GENERICO} ({@code FlatLightLaf} e {@code FlatDarkLaf}) no Windows e no Linux. Os TOKENS são os mesmos nos dois (ARQUITETURA 8.2 e 11.4): vivem em {@code FlatLightLaf.properties}
 * e {@code FlatDarkLaf.properties}, que o LaF do Mac também carrega (ele herda do genérico), e só o que é do Mac fica em {@code FlatMac*Laf.properties}. {@code TesteSemAzul},
 * {@code TesteContraste} e o contraste dos gráficos rodam nos dois estilos.
 *
 * <p>Fora do uso normal, a propriedade {@code labdesktop.laf} ({@code mac} ou {@code generico}) força um estilo (é como a suíte de testes roda "como no Windows" num Mac).
 */
public enum EstiloDeLaf {
    MAC,
    GENERICO;

    public static final String PROPRIEDADE = "labdesktop.laf";

    /** O estilo de um sistema: o do Mac só no Mac. */
    public static EstiloDeLaf para(SistemaOperacional sistema) {
        return sistema == SistemaOperacional.MAC ? MAC : GENERICO;
    }

    /** O de ESTE computador, a menos que {@code -Dlabdesktop.laf=mac|generico} diga outro. */
    public static EstiloDeLaf padrao() {
        String forcado = System.getProperty(PROPRIEDADE);
        if ("mac".equalsIgnoreCase(forcado)) {
            return MAC;
        }
        if ("generico".equalsIgnoreCase(forcado)) {
            return GENERICO;
        }
        return para(SistemaOperacional.detectar());
    }
}
