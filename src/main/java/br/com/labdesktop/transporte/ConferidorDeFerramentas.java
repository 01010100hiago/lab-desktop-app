package br.com.labdesktop.transporte;

import br.com.labdesktop.plataforma.SistemaOperacional;
import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.seguranca.TextoSeguro;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * "Conferir as ferramentas neste computador": para cada programa, procura onde ele está e, se está, roda só {@code <programa> --version} (por lista de argumentos, sem shell, com
 * prazo) para mostrar a versão. Nada além disso: não instala, não faz login, não abre sessão. O resultado traz o passo de instalação do sistema para o que faltar, como texto.
 *
 * <p>Bloqueia (procura em disco e roda processo): sempre numa thread de fundo, nunca na EDT.
 */
public final class ConferidorDeFerramentas {

    /** O prazo de cada {@code --version}. */
    public static final Duration PRAZO_DA_VERSAO = Duration.ofSeconds(5);
    private static final int TAMANHO_DA_VERSAO = 80;

    private final LocalizadorDeFerramenta localizador;
    private final LancadorDeProcesso lancador;
    private final SistemaOperacional sistema;
    private final Duration prazo;

    /** O de produção: este computador (o PATH e as pastas conhecidas do sistema) e o lançador de processos de verdade. */
    public static ConferidorDeFerramentas doSistema() {
        DescobertaDeFerramentas descoberta = DescobertaDeFerramentas.doSistema();
        return new ConferidorDeFerramentas(descoberta, new LancadorReal(), descoberta.ambiente().sistema());
    }

    public ConferidorDeFerramentas(LocalizadorDeFerramenta localizador, LancadorDeProcesso lancador, SistemaOperacional sistema) {
        this(localizador, lancador, sistema, PRAZO_DA_VERSAO);
    }

    public ConferidorDeFerramentas(LocalizadorDeFerramenta localizador, LancadorDeProcesso lancador, SistemaOperacional sistema, Duration prazo) {
        this.localizador = localizador;
        this.lancador = lancador;
        this.sistema = sistema;
        this.prazo = prazo;
    }

    /** Confere os três programas (o aws, o plugin do Session Manager e o cloudflared), nesta ordem. */
    public RelatorioDeFerramentas conferirTodas() throws InterruptedException {
        List<RelatorioDeFerramentas.Item> itens = new ArrayList<>();
        for (FerramentaExterna f : FerramentaExterna.values()) {
            itens.add(conferir(f));
        }
        return new RelatorioDeFerramentas(itens);
    }

    public RelatorioDeFerramentas.Item conferir(FerramentaExterna ferramenta) throws InterruptedException {
        Optional<String> caminho = localizador.localizar(ferramenta);
        if (caminho.isEmpty()) {
            return new RelatorioDeFerramentas.Item(ferramenta, Optional.empty(), "", GuiaDeInstalacao.passos(ferramenta, sistema));
        }
        return new RelatorioDeFerramentas.Item(ferramenta, caminho, versaoDe(caminho.get()), List.of());
    }

    /** A primeira linha do {@code --version}, ou vazio se não rodou ou estourou o prazo (o programa é encerrado). */
    private String versaoDe(String executavel) throws InterruptedException {
        ProcessoExterno p;
        try {
            p = lancador.lancar(ArgumentosDoTunel.versao(executavel), Map.of());
        } catch (IOException e) {
            return "";
        }
        try {
            p.aoSair().get(prazo.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return "";
        } catch (ExecutionException e) {
            return "";
        } finally {
            p.encerrar();
        }
        String primeira = p.saida().lines().map(String::strip).filter(l -> !l.isEmpty()).findFirst().orElse("");
        return TextoSeguro.truncar(TextoSeguro.limparLinha(Redator.redigir(primeira)), TAMANHO_DA_VERSAO);
    }
}
