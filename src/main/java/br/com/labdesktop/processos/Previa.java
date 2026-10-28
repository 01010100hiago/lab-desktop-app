package br.com.labdesktop.processos;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * O que o app leu (só leu) ao abrir a confirmação: a oferta, a identidade real do processo (hora de início incluída) e, se o processo é de um serviço do
 * systemd do usuário, a unidade. É o que a tela mostra ANTES do clique e o que a ação confere na hora de agir. Também guarda o que já aconteceu COM ESTA
 * prévia: o pedido educado só vale como "enviado" se saiu por ela, e é só assim que o à força é aceito ({@link #pedidoEducadoEnviado()}).
 *
 * @param identidade a identidade lida; {@code null} para container (a ação é no container, e o Docker diz se ele existe)
 * @param unidade    o serviço do systemd do usuário a que o processo pertence, ou {@code null}
 * @param handle     só em "Este computador" (Mac e Linux): o {@link ProcessHandle} aberto ao ler o processo. O Java o amarra à hora de início, e o
 *                   {@code destroy()} dele recusa agir se o número já for de outro processo ({@code null} em servidor e nos testes com sistema falso)
 * @param estado     o que já foi enviado com esta prévia (mutável: é o único estado)
 */
public record Previa(ProcessoAlvo alvo, Oferta oferta, IdentidadeDoProcesso identidade, String unidade, ProcessHandle handle, AtomicBoolean estado) {

    private static final DateTimeFormatter DO_PS = DateTimeFormatter.ofPattern("MMM d HH:mm:ss yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter PARA_A_TELA = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm:ss", Locale.forLanguageTag("pt-BR"));

    public Previa {
        estado = estado == null ? new AtomicBoolean() : estado;
    }

    /** Sem o handle (servidor e testes). */
    public Previa(ProcessoAlvo alvo, Oferta oferta, IdentidadeDoProcesso identidade, String unidade) {
        this(alvo, oferta, identidade, unidade, null, new AtomicBoolean());
    }

    /** A hora em que o programa começou como o {@code ps} a escreve ({@code Tue Oct 28 12:00:00 2026}), ou vazio. */
    public String iniciouEm() {
        return identidade == null ? "" : identidade.inicio();
    }

    /** A hora de início para a tela, em português ({@code 28/10/2026 às 12:00:00}); se não der para ler, o texto como veio. */
    public String iniciouEmParaATela() {
        String bruto = iniciouEm();
        if (bruto.isBlank()) {
            return "";
        }
        try {
            // o dia da semana do ps é só enfeite: lê da data para a frente (assim um dia da semana que não bate com a data não derruba a leitura)
            int espaco = bruto.indexOf(' ');
            return java.time.LocalDateTime.parse(espaco > 0 ? bruto.substring(espaco + 1) : bruto, DO_PS).format(PARA_A_TELA);
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(bruto).atZone(ZoneId.systemDefault()).format(PARA_A_TELA);
            } catch (DateTimeParseException ignorado) {
                return bruto;
            }
        }
    }

    public boolean temServico() {
        return unidade != null;
    }

    /** O pedido educado (SIGTERM) já saiu por esta prévia? Só então o à força é aceito. */
    public boolean pedidoEducadoEnviado() {
        return estado.get();
    }

    /** Registra que o pedido educado saiu por esta prévia. */
    void registrarPedidoEducado() {
        estado.set(true);
    }
}
