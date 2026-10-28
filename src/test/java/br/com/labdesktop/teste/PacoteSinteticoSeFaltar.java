package br.com.labdesktop.teste;

import br.com.labdesktop.instalador.PacoteDoAgente;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Registrada sozinha (junit-platform.properties + META-INF/services): se o app NÃO leva o pacote do agente de verdade
 * ({@code /agente/agente.json}, montado por {@code ./gradlew empacotarAgente}), aponta o {@link PacoteDoAgente} para o pacote SINTÉTICO de
 * teste ({@code /agente-teste/agente.json}), para os testes do instalador e das janelas terem um pacote íntegro para enviar. Se o pacote de
 * verdade existe, nada muda: os testes usam o de verdade.
 */
public final class PacoteSinteticoSeFaltar implements BeforeAllCallback {

    private static final String SINTETICO = "/agente-teste/agente.json";

    @Override
    public void beforeAll(ExtensionContext contexto) {
        if (!PacoteDeTeste.temPacoteDeVerdade() && PacoteDoAgente.class.getResource(SINTETICO) != null) {
            PacoteDoAgente.trocarDescritorParaTeste(SINTETICO);
        }
    }
}
