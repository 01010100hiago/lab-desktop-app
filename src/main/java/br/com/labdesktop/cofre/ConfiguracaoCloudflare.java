package br.com.labdesktop.cofre;

import br.com.labdesktop.seguranca.TextoSeguro;
import br.com.labdesktop.seguranca.ValidadorDeNomes;

/**
 * O que o usuário escolheu na tela "Cloudflare" de um servidor: a conta, o domínio (zona), o túnel e, se o túnel é gerido por ARQUIVO, onde está o
 * {@code config.yml} e qual container roda o {@code cloudflared}. NÃO é segredo (por isso é {@code record}); o token fica à parte, em
 * {@link TokenCloudflare}. Guardado no cofre por servidor, ao lado do token.
 *
 * <p>Tudo é validado no construtor (ids, domínio, nome do container e caminhos), porque estes valores acabam em chamadas à API e em linhas de comando
 * fixas no servidor.
 *
 * @param contaId                id da conta (32 hexadecimais)
 * @param contaNome              o nome da conta, só para mostrar
 * @param zonaId                 id da zona (32 hexadecimais)
 * @param zonaNome               o domínio, como {@code example.com}
 * @param tunelId                o UUID do túnel (SEMPRE o UUID, nunca o nome: o nome pode ser ignorado em silêncio)
 * @param tunelNome              o nome do túnel, só para mostrar
 * @param tipo                   como a regra do túnel é mantida
 * @param prefixo                o começo sugerido do nome de um endereço ({@code lab} para {@code lab-<projeto>})
 * @param caminhoDoConfig        o {@code config.yml} no servidor (só no tipo {@link Tipo#ARQUIVO})
 * @param containerDoCloudflared o container que roda o cloudflared (só no tipo {@link Tipo#ARQUIVO})
 * @param caminhoNoContainer     onde o container enxerga o {@code config.yml}; {@code null}: o app descobre pelo {@code docker inspect}
 */
public record ConfiguracaoCloudflare(String contaId, String contaNome, String zonaId, String zonaNome, String tunelId, String tunelNome, Tipo tipo,
                                     String prefixo, String caminhoDoConfig, String containerDoCloudflared, String caminhoNoContainer) {

    /** Como as regras do túnel são mantidas. */
    public enum Tipo {
        /** O túnel lê um {@code config.yml} no servidor (o app o edita por SSH e reinicia o container do cloudflared). */
        ARQUIVO,
        /** O túnel é gerido pela API da Cloudflare ({@code config_src} = cloudflare): o app lê e grava a configuração pela API. */
        API
    }

    /** O começo padrão dos nomes de endereço. */
    public static final String PREFIXO_PADRAO = "lab";

    public ConfiguracaoCloudflare {
        if (!ValidadorDeNomes.idCloudflareValido(contaId)) {
            throw new IllegalArgumentException("Id da conta da Cloudflare inválido.");
        }
        if (!ValidadorDeNomes.idCloudflareValido(zonaId)) {
            throw new IllegalArgumentException("Id do domínio na Cloudflare inválido.");
        }
        if (!ValidadorDeNomes.zonaDnsValida(zonaNome)) {
            throw new IllegalArgumentException("Nome do domínio inválido.");
        }
        if (!ValidadorDeNomes.uuidDoTunelValido(tunelId)) {
            throw new IllegalArgumentException("O túnel precisa do UUID (não do nome).");
        }
        if (tipo == null) {
            throw new IllegalArgumentException("Falta dizer como o túnel é gerido.");
        }
        contaNome = nomeParaMostrar(contaNome);
        tunelNome = nomeParaMostrar(tunelNome);
        prefixo = prefixo == null || prefixo.isBlank() ? PREFIXO_PADRAO : prefixo;
        if (!ValidadorDeNomes.rotuloDnsValido(prefixo) || prefixo.length() > 20) {
            throw new IllegalArgumentException("O começo dos nomes usa letras minúsculas, números e hífen (até 20 caracteres).");
        }
        if (tipo == Tipo.ARQUIVO) {
            if (!ValidadorDeNomes.caminhoSeguroValido(caminhoDoConfig)) {
                throw new IllegalArgumentException("Caminho do config.yml inválido (use um caminho absoluto, sem espaço nem símbolos).");
            }
            if (containerDoCloudflared == null || !ValidadorDeNomes.containerValido(containerDoCloudflared)) {
                throw new IllegalArgumentException("Nome do container do cloudflared inválido.");
            }
            if (caminhoNoContainer != null && !ValidadorDeNomes.caminhoSeguroValido(caminhoNoContainer)) {
                throw new IllegalArgumentException("Caminho do config.yml dentro do container inválido.");
            }
        } else {
            caminhoDoConfig = null;
            containerDoCloudflared = null;
            caminhoNoContainer = null;
        }
    }

    private static String nomeParaMostrar(String nome) {
        String n = TextoSeguro.limparLinha(nome == null ? "" : nome);
        return n.length() > 100 ? n.substring(0, 100) : n;
    }

    public boolean porArquivo() {
        return tipo == Tipo.ARQUIVO;
    }
}
