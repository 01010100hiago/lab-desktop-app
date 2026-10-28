package br.com.labdesktop.processos;

/**
 * O que o app leu (só leu) ao abrir a confirmação: a oferta, a identidade real do processo (hora de início incluída) e, se o processo é de um serviço do
 * systemd do usuário, a unidade. É o que a tela mostra ANTES do clique e o que a ação confere na hora de agir.
 *
 * @param identidade a identidade lida; {@code null} para container (a ação é no container, e o Docker diz se ele existe)
 * @param unidade    o serviço do systemd do usuário a que o processo pertence, ou {@code null}
 */
public record Previa(ProcessoAlvo alvo, Oferta oferta, IdentidadeDoProcesso identidade, String unidade) {

    /** A hora em que o programa começou, para a confirmação ("Tue Oct 28 12:00:00 2026"), ou vazio. */
    public String iniciouEm() {
        return identidade == null ? "" : identidade.inicio();
    }

    public boolean temServico() {
        return unidade != null;
    }
}
