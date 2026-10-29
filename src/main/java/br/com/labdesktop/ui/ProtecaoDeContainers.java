package br.com.labdesktop.ui;

import java.util.Set;

/**
 * Os containers que o usuário marcou como "nunca encerrar nem reiniciar por este app", por servidor (guardados em {@code preferencias.json}). Além dos que o app já protege pelo nome
 * (túnel, proxy, o Docker, o agente do painel). A janela liga isto às preferências; os testes simples usam {@link #NENHUMA}.
 *
 * <p>Não confundir com "Servidor protegido" ({@code servidor.ProtecaoDoServidor}), a marca do servidor inteiro contra instalar e mudar acesso: aqui é por container, só contra encerrar e reiniciar.
 */
public interface ProtecaoDeContainers {

    /** Os containers marcados neste servidor (em minúsculas). */
    Set<String> marcados(String servidorId);

    /** Marca ou desmarca um container neste servidor. */
    void marcar(String servidorId, String container, boolean protegido);

    ProtecaoDeContainers NENHUMA = new ProtecaoDeContainers() {
        @Override
        public Set<String> marcados(String servidorId) {
            return Set.of();
        }

        @Override
        public void marcar(String servidorId, String container, boolean protegido) { }
    };

    /** Sobre as preferências em uso. */
    static ProtecaoDeContainers dasPreferencias(PreferenciasAtuais preferencias) {
        return new ProtecaoDeContainers() {
            @Override
            public Set<String> marcados(String servidorId) {
                return preferencias.get().containersProtegidosDe(servidorId);
            }

            @Override
            public void marcar(String servidorId, String container, boolean protegido) {
                preferencias.atualizar(p -> p.comContainerProtegido(servidorId, container, protegido));
            }
        };
    }
}
