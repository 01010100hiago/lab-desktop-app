package br.com.labdesktop.ui.telas;

import br.com.labdesktop.arquivos.MemoriaDeCaminhos;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.componentes.Notificacoes;
import br.com.labdesktop.ui.dialogos.Apresentador;
import br.com.labdesktop.ui.dialogos.DialogoRelatorio;
import br.com.labdesktop.ui.telas.arquivos.EscolhedorLocal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.function.Supplier;

/**
 * O que o Resumo precisa de FORA para o relatório, o "Ver tudo" do registro e a memória do período escolhido: quem mostra diálogos, avisos e a área de transferência, o seletor de
 * arquivos, a memória por servidor e como gravar o arquivo. Um pacote só para a janela entregar sem aumentar a lista de parâmetros; os testes entregam versões falsas.
 *
 * @param fonteDoRelatorio de onde o relatório tira os dados de cada período; {@code null} = do servidor vivo (o padrão), ou do que a página já tem
 */
record ApoioDoResumo(Apresentador apresentador, Notificacoes avisos, CopiadorDeTexto copiador, EscolhedorLocal escolhedor, MemoriaDeCaminhos memoria,
                     DialogoRelatorio.Gravador gravador, Supplier<ZoneId> fuso, Supplier<Instant> relogio, DialogoRelatorio.Fonte fonteDoRelatorio) {

    private static final Notificacoes MUDAS = new Notificacoes() {
        @Override
        public void ok(String texto) { }

        @Override
        public void info(String texto) { }

        @Override
        public void atencao(String texto) { }

        @Override
        public void erro(String texto) { }

        @Override
        public void comAcao(String texto, String rotuloDaAcao, Runnable acao) { }
    };

    /** O apoio de uma tela montada sem o app inteiro (testes, telas soltas): nenhum diálogo aparece, nada é lembrado em disco. */
    static ApoioDoResumo padrao() {
        return new ApoioDoResumo(d -> { }, MUDAS, CopiadorDeTexto.doSistema(), EscolhedorLocal.doSistema(() -> null), MemoriaDeCaminhos.emMemoria(),
            DialogoRelatorio.gravadorDoSistema(), ZoneId::systemDefault, Instant::now, null);
    }

    /** O apoio de produção: o que a {@code TelaServidor} já tem. */
    static ApoioDoResumo de(Apresentador apresentador, Notificacoes avisos, EscolhedorLocal escolhedor, MemoriaDeCaminhos memoria) {
        return new ApoioDoResumo(apresentador, avisos, CopiadorDeTexto.doSistema(), escolhedor, memoria, DialogoRelatorio.gravadorDoSistema(), ZoneId::systemDefault, Instant::now, null);
    }
}
