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
    /** Uma gravação de cada vez, e sempre a mais nova: duas mudanças seguidas (as setas da tela Ajustes) não podem brigar pelo mesmo arquivo temporário. */
    private final Object travaDaGravacao = new Object();
    private Preferencias ultimaGravada;

    public PreferenciasAtuais(CaminhosApp caminhos, Preferencias inicial) {
        this.caminhos = caminhos;
        this.atual = inicial;
    }

    /** As pastas do app (a tela Sobre mostra a pasta de dados). */
    public CaminhosApp caminhos() {
        return caminhos;
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
        Fundo.emFundo(this::gravarAgora);
    }

    /**
     * Grava AGORA o que estiver na memória e só volta quando acabou (espera uma gravação em andamento). É para o fim do app: {@code Fundo.encerrar} interrompe as threads de fundo e
     * uma gravação no meio perderia a última mudança. Um arquivo pequeno, uma vez, na saída.
     */
    public void descarregar() {
        gravarAgora();
    }

    /** Grava a preferência MAIS NOVA, uma gravação por vez (quem chegar depois grava o que já estiver na memória e quem já não tem o que gravar sai). */
    private void gravarAgora() {
        synchronized (travaDaGravacao) {
            Preferencias p = atual;
            if (p.equals(ultimaGravada)) {
                return;
            }
            try {
                p.salvar(caminhos);
                ultimaGravada = p;
            } catch (java.io.IOException e) {
                LOG.warn("Não consegui gravar as preferências: {}", e.toString());
            }
        }
    }
}
