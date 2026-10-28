package br.com.labdesktop.ui.tema;

import java.awt.Color;
import java.util.List;
import javax.swing.UIManager;

/**
 * Os tokens de cor como o código das telas os enxerga (paleta neutra e sóbria; o "destaque" é grafite no claro
 * e quase-branco no escuro, sem matiz próprio). A fonte da verdade são os
 * arquivos {@code FlatMacLightLaf.properties} e {@code FlatMacDarkLaf.properties} (chaves {@code Lab.xxx}); aqui
 * só há os NOMES e o acesso. As cores são lidas na hora de pintar, nunca guardadas: a troca de tema (claro,
 * escuro, sistema) vale sem reiniciar e sem refazer componente.
 *
 * <p>Estado nunca só pela cor (DESIGN.md): quem usa {@link #OK}, {@link #ATENCAO} e {@link #PERIGO} sempre
 * acompanha de ícone e texto.
 */
public final class Tokens {

    public static final String BG = "Lab.bg";
    public static final String BG2 = "Lab.bg2";
    public static final String SUPERFICIE = "Lab.superficie";
    public static final String SUPERFICIE2 = "Lab.superficie2";
    public static final String SUPERFICIE3 = "Lab.superficie3";
    public static final String BORDA = "Lab.borda";
    public static final String BORDA_FORTE = "Lab.bordaForte";
    public static final String BORDA_SUTIL = "Lab.bordaSutil";
    public static final String TEXTO = "Lab.texto";
    public static final String TEXTO2 = "Lab.texto2";
    public static final String TEXTO3 = "Lab.texto3";
    public static final String TEXTO_INVERSO = "Lab.textoInverso";
    public static final String DESTAQUE = "Lab.destaque";
    public static final String DESTAQUE_FORTE = "Lab.destaqueForte";
    public static final String DESTAQUE_TEXTO = "Lab.destaqueTexto";
    public static final String DESTAQUE_SUAVE = "Lab.destaqueSuave";
    public static final String DESTAQUE_BORDA = "Lab.destaqueBorda";
    public static final String OK = "Lab.ok";
    public static final String OK_SUAVE = "Lab.okSuave";
    public static final String ATENCAO = "Lab.atencao";
    public static final String ATENCAO_SUAVE = "Lab.atencaoSuave";
    public static final String PERIGO = "Lab.perigo";
    public static final String PERIGO_SUAVE = "Lab.perigoSuave";
    public static final String PERIGO_FORTE = "Lab.perigoForte";
    public static final String FOCO = "Lab.foco";
    public static final String TRILHO = "Lab.trilho";
    public static final String SERIE5 = "Lab.serie5";
    public static final String SERIE6 = "Lab.serie6";
    public static final String SERIE7 = "Lab.serie7";
    public static final String SERIE8 = "Lab.serie8";
    public static final String LOGS_BG = "Lab.logsBg";
    public static final String LOGS_TEXTO = "Lab.logsTexto";
    public static final String LOGS_TEXTO2 = "Lab.logsTexto2";
    public static final String LOGS_BORDA = "Lab.logsBorda";
    public static final String MODAL_FUNDO = "Lab.modalFundo";
    public static final String ESQUELETO_A = "Lab.esqueletoA";
    public static final String ESQUELETO_B = "Lab.esqueletoB";
    public static final String QR_TINTA = "Lab.qrTinta";
    public static final String QR_FUNDO = "Lab.qrFundo";

    /** Todas as chaves: um teste confere que os dois temas definem cada uma. */
    public static final List<String> TODAS = List.of(
        BG, BG2, SUPERFICIE, SUPERFICIE2, SUPERFICIE3, BORDA, BORDA_FORTE, BORDA_SUTIL,
        TEXTO, TEXTO2, TEXTO3, TEXTO_INVERSO,
        DESTAQUE, DESTAQUE_FORTE, DESTAQUE_TEXTO, DESTAQUE_SUAVE, DESTAQUE_BORDA,
        OK, OK_SUAVE, ATENCAO, ATENCAO_SUAVE, PERIGO, PERIGO_SUAVE, PERIGO_FORTE, FOCO, TRILHO,
        SERIE5, SERIE6, SERIE7, SERIE8, LOGS_BG, LOGS_TEXTO, LOGS_TEXTO2, LOGS_BORDA,
        MODAL_FUNDO, ESQUELETO_A, ESQUELETO_B, QR_TINTA, QR_FUNDO);

    /** Só aparece se o tema não foi instalado (um teste de componente avulso): cinza, para o erro saltar à vista. */
    private static final Color SEM_TEMA = new Color(0x808080);

    private Tokens() { }

    /** A cor do token no tema atual. */
    public static Color cor(String chave) {
        Color c = UIManager.getColor(chave);
        return c != null ? c : SEM_TEMA;
    }

    /** A mesma cor com a opacidade dada (0 a 1), para realces e sombras. */
    public static Color comAlfa(String chave, float alfa) {
        Color c = cor(chave);
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(Math.max(0f, Math.min(1f, alfa)) * 255));
    }
}
