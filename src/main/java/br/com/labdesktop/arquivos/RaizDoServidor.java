package br.com.labdesktop.arquivos;

/** As raízes que o seletor do modo "servidor inteiro" oferece. */
public enum RaizDoServidor {
    /** A pasta onde ficam os projetos ({@code /srv/lab/projects}, configurável por servidor). */
    PROJETOS("Projetos"),
    /** A pasta pessoal do usuário que o app usa no servidor. */
    MINHA_PASTA("Minha pasta"),
    /** {@code /}: o servidor todo. Em Avançado, e só depois de uma confirmação. */
    TUDO("Tudo (/)");

    private final String rotulo;

    RaizDoServidor(String rotulo) {
        this.rotulo = rotulo;
    }

    public String rotulo() {
        return rotulo;
    }
}
