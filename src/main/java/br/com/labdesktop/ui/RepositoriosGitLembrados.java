package br.com.labdesktop.ui;

/**
 * O repositório Git que o usuário escolheu ler em cada projeto, quando a pasta principal do projeto não tem {@code .git} mas uma SUBPASTA tem ({@code app/}): por servidor e projeto, guardado em
 * {@code preferencias.json}. A janela liga isto às preferências; os testes simples usam {@link #NENHUM}.
 */
public interface RepositoriosGitLembrados {

    /** A subpasta lembrada ({@code app}), ou {@code null}. */
    String lembrado(String servidorId, String projeto);

    /** Lembra a subpasta; {@code null} esquece. */
    void lembrar(String servidorId, String projeto, String subpasta);

    RepositoriosGitLembrados NENHUM = new RepositoriosGitLembrados() {
        @Override
        public String lembrado(String servidorId, String projeto) {
            return null;
        }

        @Override
        public void lembrar(String servidorId, String projeto, String subpasta) { }
    };

    /** Sobre as preferências em uso. */
    static RepositoriosGitLembrados dasPreferencias(PreferenciasAtuais preferencias) {
        return new RepositoriosGitLembrados() {
            @Override
            public String lembrado(String servidorId, String projeto) {
                return preferencias.get().repositorioGitDe(servidorId, projeto);
            }

            @Override
            public void lembrar(String servidorId, String projeto, String subpasta) {
                preferencias.atualizar(p -> p.comRepositorioGit(servidorId, projeto, subpasta));
            }
        };
    }
}
