package br.com.labdesktop.ui;

import br.com.labdesktop.plataforma.SistemaOperacional;

/**
 * O que fechar a janela principal faz, e se o menu leva "Sair" (ARQUITETURA 11.4). No <b>macOS</b> fechar a janela só a ESCONDE e o app continua (o ícone do Dock a traz de volta,
 * Cmd+Q sai). No <b>Windows e no Linux</b> não há Dock nem bandeja: uma janela escondida deixaria o app vivo e invisível, com o cofre travado em outra instância ("já está aberto") e sem
 * como reabrir. Por isso lá fechar a janela SAI do app (o mesmo caminho do Cmd+Q: bloqueia o cofre, fecha as conexões, solta a trava), e o menu ganha "Sair".
 *
 * @param sistema o sistema (decide tudo; os testes provam os três)
 * @param sair    o que "sair do app" faz; {@code null} (testes e o uso antigo) mantém o comportamento do Mac em qualquer sistema
 */
public record ComportamentoDaJanela(SistemaOperacional sistema, Runnable sair) {

    /** O de ESTE computador, sem "sair" (a janela só esconde). */
    public static ComportamentoDaJanela padrao() {
        return new ComportamentoDaJanela(SistemaOperacional.detectar(), null);
    }

    /** O de ESTE computador, com a saída do app. */
    public static ComportamentoDaJanela doSistema(Runnable sair) {
        return new ComportamentoDaJanela(SistemaOperacional.detectar(), sair);
    }

    /** Fechar a janela só a esconde (Mac, ou sem saída configurada). */
    public boolean fecharEscondeAJanela() {
        return sistema == SistemaOperacional.MAC || sair == null;
    }

    /** O menu tem o item "Sair" (Windows e Linux; no Mac é o Cmd+Q do menu do app). */
    public boolean temItemSair() {
        return sistema != SistemaOperacional.MAC && sair != null;
    }
}
