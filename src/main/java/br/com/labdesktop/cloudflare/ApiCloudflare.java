package br.com.labdesktop.cloudflare;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * O que o app pede à API da Cloudflare (v4). Uma interface para o domínio não conhecer HTTP e para os testes poderem trocar o servidor. TODO método
 * bloqueia (rede): thread de fundo, nunca a EDT. Toda falha sai como {@link ErroCloudflare}, já com a frase para o usuário.
 *
 * <p>As LEITURAS (os métodos sem verbo de escrita) podem rodar sem o usuário pedir (abrir a tela, atualizar a lista); as ESCRITAS
 * ({@code criarCname}, {@code removerDns}, {@code gravarConfiguracaoDoTunel}) só nascem do clique de confirmação (a varredura de código dos testes
 * confere).
 */
public interface ApiCloudflare extends AutoCloseable {

    // ---------------------------------------------------------------------------------------------
    // Leituras
    // ---------------------------------------------------------------------------------------------

    /** {@code GET /user/tokens/verify}: o token está ativo? */
    TokenVerificado verificarToken() throws ErroCloudflare;

    /** {@code GET /accounts}: as contas que o token enxerga (pode ser vazio: um token só de zona não enxerga a conta). */
    List<ContaCf> contas() throws ErroCloudflare;

    /** {@code GET /zones}: os domínios que o token enxerga (todas as páginas, até um teto). */
    List<ZonaCf> zonas() throws ErroCloudflare;

    /** {@code GET /accounts/:id/cfd_tunnel}: os túneis da conta (sem os apagados). */
    List<TunelCf> tuneis(String contaId) throws ErroCloudflare;

    /** {@code GET /accounts/:id/cfd_tunnel/:id/configurations}. */
    ConfigDoTunel configuracaoDoTunel(String contaId, String tunelId) throws ErroCloudflare;

    /** {@code GET /zones/:id/dns_records?name=}: os registros com este nome completo (qualquer tipo). */
    List<RegistroDnsCf> registrosDnsComONome(String zonaId, String nomeCompleto) throws ErroCloudflare;

    /** {@code GET /zones/:id/dns_records?comment.startswith=}: os registros cujo comentário começa assim (o app filtra de novo por segurança). */
    List<RegistroDnsCf> registrosDnsComComentarioQueComecaCom(String zonaId, String prefixoDoComentario) throws ErroCloudflare;

    // ---------------------------------------------------------------------------------------------
    // Escritas (só depois do clique de confirmação)
    // ---------------------------------------------------------------------------------------------

    /** {@code PUT /accounts/:id/cfd_tunnel/:id/configurations}: grava a configuração inteira ({@code {"config": ...}}). */
    void gravarConfiguracaoDoTunel(String contaId, String tunelId, JsonNode config) throws ErroCloudflare;

    /** {@code POST /zones/:id/dns_records}: cria um CNAME. {@code nomeCompleto} é o endereço inteiro; {@code proxied} liga o proxy da Cloudflare. */
    RegistroDnsCf criarCname(String zonaId, String nomeCompleto, String alvo, boolean proxied, String comentario) throws ErroCloudflare;

    /** {@code DELETE /zones/:id/dns_records/:id}. */
    void removerDns(String zonaId, String registroId) throws ErroCloudflare;

    /**
     * Quem diz se o usuário cancelou: a espera por causa de um 429 (até 30 s, três vezes) passa a ser feita em fatias curtas e para na hora do cancelamento, em vez de prender o
     * botão Cancelar por minutos. Sem isto (o padrão) a espera é inteira.
     */
    default void cancelarQuando(java.util.function.BooleanSupplier cancelado) { }

    @Override
    void close();
}
