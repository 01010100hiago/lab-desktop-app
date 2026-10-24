package br.com.labdesktop.ui;

import br.com.labdesktop.util.CaminhosApp;
import br.com.labdesktop.util.Fundo;
import br.com.labdesktop.util.Preferencias;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * As preferências em uso, com a gravação em disco FORA da EDT: a interface chama {@link #atualizar} (por exemplo, ao
 * trocar o tema ou mover a janela) e a gravação atômica acontece numa thread de fundo. Nada de segredo aqui
 * ({@code preferencias.json} fica fora do cofre de propósito, ARQUITETURA 5.1).
 */
public final class PreferenciasAtuais {

    private static final Logger LOG = LoggerFactory.getLogger(PreferenciasAtuais.class);

    private final CaminhosApp caminhos;
    private volatile Preferencias atual;

    public PreferenciasAtuais(CaminhosApp caminhos, Preferencias inicial) {
        this.caminhos = caminhos;
        this.atual = inicial;
    }

    public Preferencias get() {
        return atual;
    }

    /** Aplica a mudança na memória (na hora) e grava em segundo plano. Uma falha de gravação só vai para o log. */
    public synchronized void atualizar(UnaryOperator<Preferencias> mudanca) {
        Preferencias nova = mudanca.apply(atual);
        if (nova.equals(atual)) {
            return;
        }
        atual = nova;
        Fundo.emFundo(() -> {
            try {
                nova.salvar(caminhos);
            } catch (java.io.IOException e) {
                LOG.warn("Não consegui gravar as preferências: {}", e.toString());
            }
        });
    }
}
