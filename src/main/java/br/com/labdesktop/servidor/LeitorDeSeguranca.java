package br.com.labdesktop.servidor;

/**
 * Lê o retrato de segurança de UM servidor. A implementação de produção ({@link LeitorDeSegurancaPorSsh}) usa a
 * conexão SSH que o app já tem; o agente Node não expõe o registro de autenticação. Somente leitura: nada aqui
 * bloqueia IP nem muda firewall (isso o app não faz). Bloqueia: nunca chamar da EDT.
 */
@FunctionalInterface
public interface LeitorDeSeguranca {

    /** Nunca lança: cada seção que falha vira indisponível ou sem permissão. */
    ResumoDeSeguranca ler();
}
