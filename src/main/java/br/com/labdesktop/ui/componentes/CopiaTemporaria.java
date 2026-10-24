package br.com.labdesktop.ui.componentes;

import br.com.labdesktop.util.Agendador;
import br.com.labdesktop.util.Fundo;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copia um texto SECRETO (a chave do 2FA, por exemplo) para a área de transferência e o tira de lá depois: ao fim do
 * prazo ({@link #VALIDADE_PADRAO}) ou quando o usuário manda ({@link #limparAgora()}, ao fechar o diálogo).
 *
 * <p>Por quê: o que fica na área de transferência do macOS sobrevive ao diálogo e sai do app. Gerenciadores de histórico
 * (Raycast, Alfred, Maccy) e a Área de Transferência Universal (que sincroniza com o iPhone) guardam o texto, e quem ler
 * esse histórico tem a semente do TOTP e gera códigos válidos para sempre, o que anula o 2FA. Ao limpar logo, a janela
 * de exposição cai de "indefinida" para alguns segundos (limite aceito: um gerenciador que registra cada mudança já terá
 * visto o texto uma vez).
 *
 * <p><b>Nunca apaga o que o usuário copiou depois.</b> A limpeza só acontece se a área de transferência ainda é a
 * NOSSA: o {@link ClipboardOwner} avisa quando outro programa (ou outra cópia do app) assumiu, e, como o aviso do macOS
 * pode atrasar, o conteúdo atual também é conferido antes de apagar.
 *
 * <p>Chamar na EDT. A área de transferência do sistema só é tocada no primeiro {@link #copiar}: construir e
 * {@link #limparAgora()} sem nada copiado não exigem ambiente gráfico (testes sem cabeça).
 */
public final class CopiaTemporaria implements ClipboardOwner {

    private static final Logger LOG = LoggerFactory.getLogger(CopiaTemporaria.class);

    /** Quanto tempo o texto fica na área de transferência. */
    public static final Duration VALIDADE_PADRAO = Duration.ofSeconds(30);

    private final Supplier<Clipboard> area;
    private final Agendador agendador;
    private final Duration validade;

    // Em produção tudo roda na EDT. volatile porque o aviso de posse do AWT pode chegar de outra thread e porque, nos
    // testes sem EDT ligada ao Fundo, o despacho é na própria thread de quem chama.
    private volatile String texto;
    private volatile Transferable nossoConteudo;
    private volatile boolean nossa;
    private volatile Agendador.Cancelavel limpezaAgendada;

    /**
     * @param area      de onde tirar a área de transferência, na hora da primeira cópia (a do sistema em produção)
     * @param agendador relógio e timers (o do fundo em produção; falso nos testes)
     * @param validade  quanto o texto fica lá
     */
    public CopiaTemporaria(Supplier<Clipboard> area, Agendador agendador, Duration validade) {
        this.area = area;
        this.agendador = agendador;
        this.validade = validade;
    }

    /** A cópia de produção: área de transferência do sistema, timer do fundo, {@link #VALIDADE_PADRAO}. */
    public static CopiaTemporaria doSistema() {
        return new CopiaTemporaria(() -> Toolkit.getDefaultToolkit().getSystemClipboard(), Agendador.doFundo(), VALIDADE_PADRAO);
    }

    /** Quanto o texto fica na área de transferência (para a mensagem "a cópia expira em N s"). */
    public Duration validade() {
        return validade;
    }

    /**
     * Copia {@code segredo} e agenda a limpeza. Uma segunda cópia cancela a limpeza da primeira e começa o prazo de novo.
     *
     * @return {@code false} se a área de transferência não deixou (nada ficou lá)
     */
    public boolean copiar(String segredo) {
        cancelarLimpeza();
        StringSelection conteudo = new StringSelection(segredo);
        try {
            area.get().setContents(conteudo, this);
        } catch (IllegalStateException | java.awt.HeadlessException e) {
            LOG.warn("Não consegui usar a área de transferência: {}", e.toString());
            texto = null;
            nossoConteudo = null;
            nossa = false;
            return false;
        }
        texto = segredo;
        nossoConteudo = conteudo;
        nossa = true;
        limpezaAgendada = agendador.agendar(validade, () -> Fundo.naEdt(this::limparSeAindaForNossa));
        return true;
    }

    /**
     * Tira o texto da área de transferência AGORA, se ainda for o nosso, e esquece o segredo. Idempotente: sem nada
     * copiado, não faz nada (e não toca no sistema).
     */
    public void limparAgora() {
        limparSeAindaForNossa();
    }

    /** {@code true} se há um texto copiado por esta instância ainda na área de transferência (para a tela e os testes). */
    public boolean temCopiaPendente() {
        return texto != null && nossa;
    }

    private void limparSeAindaForNossa() {
        cancelarLimpeza();
        String nosso = texto;
        boolean continuaNossa = nossa;
        texto = null;
        nossoConteudo = null;
        nossa = false;
        if (nosso == null || !continuaNossa) {
            return; // nada copiado, ou o usuário copiou outra coisa depois: não é nosso para apagar
        }
        try {
            Clipboard c = area.get();
            if (conteudoAtual(c, nosso)) {
                c.setContents(new StringSelection(""), null);
            }
        } catch (IllegalStateException | java.awt.HeadlessException e) {
            LOG.warn("Não consegui limpar a área de transferência: {}", e.toString());
        }
    }

    /**
     * {@code true} se o conteúdo atual é o texto copiado por nós, ou se não deu para ler (aí vale o aviso de posse:
     * ninguém tirou a área de nós, e deixar o segredo lá é o pior dos dois erros).
     */
    private static boolean conteudoAtual(Clipboard c, String esperado) {
        try {
            Transferable t = c.getContents(null);
            if (t == null || !t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                return false; // a área de transferência já tem outra coisa (imagem, arquivo...)
            }
            return esperado.equals(t.getTransferData(DataFlavor.stringFlavor));
        } catch (java.awt.datatransfer.UnsupportedFlavorException | java.io.IOException | IllegalStateException e) {
            return true;
        }
    }

    private void cancelarLimpeza() {
        Agendador.Cancelavel c = limpezaAgendada;
        limpezaAgendada = null;
        if (c != null) {
            c.cancelar();
        }
    }

    /**
     * Outro programa assumiu a área de transferência: o que há lá agora não é nosso e nunca será apagado por nós. O aviso
     * traz o conteúdo que PERDEU a posse: um aviso atrasado de uma cópia antiga (por exemplo, o da nossa própria limpeza)
     * não pode tirar a posse de uma cópia nova.
     */
    @Override
    public void lostOwnership(Clipboard clipboard, Transferable contents) {
        Fundo.naEdt(() -> {
            if (contents != null && contents == nossoConteudo) {
                nossa = false;
            }
        });
    }
}
