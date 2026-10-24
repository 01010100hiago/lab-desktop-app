package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Extensão JUnit: depois de cada teste, nenhum componente Swing pode ter sido tocado fora da EDT. */
public final class VerificaEdt implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext contexto) {
        Visual.preparar();
        VerificadorDeEdt.limpar();
    }

    @Override
    public void afterEach(ExtensionContext contexto) {
        var violacoes = VerificadorDeEdt.violacoes();
        VerificadorDeEdt.limpar();
        assertTrue(violacoes.isEmpty(), "Swing tocado fora da EDT:\n" + String.join("\n", violacoes));
    }
}
