package br.com.labdesktop.cloudflare;

/**
 * Um endereço público que o app criou: o registro de DNS com a marca {@code lab-desktop:} (e, no túnel por arquivo, a regra com o comentário do app).
 *
 * @param projeto  o projeto da marca ({@code ""} para uma regra só do config.yml, que não diz o projeto)
 * @param servico  para onde o túnel manda ({@code http://demo-web:3000}), ou {@code ""} se não deu para conferir ou não há regra
 * @param dnsId    o id do registro de DNS ({@code null} se só a regra existe)
 * @param regra    se a regra do túnel existe
 */
public record EnderecoDoApp(String hostname, String projeto, String servico, String dnsId, Regra regra) {

    /** A regra do túnel para este endereço. */
    public enum Regra {
        /** Está no túnel. */
        SIM,
        /** Não está (a regra foi tirada à mão, ou nunca chegou). */
        NAO,
        /** Não deu para conferir agora (o config.yml não abriu, por exemplo). */
        NAO_CONFERIDA
    }

    public boolean temDns() {
        return dnsId != null;
    }

    public String url() {
        return NomeDoEndereco.urlPublica(hostname);
    }
}
