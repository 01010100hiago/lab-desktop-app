package br.com.labdesktop.local;

import br.com.labdesktop.ssh.PontaDoAgente;
import br.com.labdesktop.ssh.SondaDoAgente;

/**
 * O teste de saúde do agente que a {@link ConexaoLocal} pede: um {@code GET /api/versao} em 127.0.0.1. É a versão sem túnel de
 * {@link SondaDoAgente}. Chamada numa thread virtual; pode bloquear.
 */
@FunctionalInterface
public interface SondaDaPonta {

    SondaDoAgente.Resultado sondar(PontaDoAgente ponta) throws InterruptedException;
}
