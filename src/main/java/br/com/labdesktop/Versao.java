package br.com.labdesktop;

/**
 * Nome e versão do app. A versão vem do manifesto do jar ({@code Implementation-Version}, definido no
 * {@code build.gradle.kts}); rodando fora do jar (testes, IDE) vale {@code "dev"}.
 */
public final class Versao {

    public static final String NOME = "Lab Desktop";

    private Versao() { }

    /** Por exemplo {@code 0.1.0}, ou {@code dev}. */
    public static String atual() {
        String v = Versao.class.getPackage() == null ? null : Versao.class.getPackage().getImplementationVersion();
        return v == null || v.isBlank() ? "dev" : v;
    }

    /** Valor do cabeçalho {@code User-Agent} das chamadas ao agente (CONTRATO-API 1.1). */
    public static String userAgent() {
        return "LabDesktop/" + atual();
    }
}
