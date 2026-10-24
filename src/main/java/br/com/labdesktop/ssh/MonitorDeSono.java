package br.com.labdesktop.ssh;

import java.awt.desktop.SystemSleepEvent;
import java.awt.desktop.SystemSleepListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Avisa as {@link ConexaoSsh} quando o Mac dorme e acorda (ARQUITETURA 6.3). Sono derruba o TCP sem avisar: ao
 * acordar a sessão está certamente morta, e esperar o heartbeat custaria quase um minuto.
 *
 * <p>Este pacote é de domínio e não importa {@code java.awt} (regra de camadas), com UMA exceção deliberada:
 * {@code java.awt.desktop.SystemSleepListener}, que é só uma interface de integração com o sistema, sem
 * componente gráfico. O REGISTRO no sistema ({@code Desktop.getDesktop().addAppEventListener(monitor)}) usa
 * {@code java.awt.Desktop} e por isso é feito pela casca ({@code Aplicacao}), não aqui.
 */
public final class MonitorDeSono implements SystemSleepListener {

    private static final Logger LOG = LoggerFactory.getLogger(MonitorDeSono.class);

    private final List<Runnable> aoDormir = new CopyOnWriteArrayList<>();
    private final List<Runnable> aoAcordar = new CopyOnWriteArrayList<>();

    /** Chamado quando o sistema está para dormir. Devolve o que desfaz o registro. */
    public Runnable aoDormir(Runnable acao) {
        aoDormir.add(acao);
        return () -> aoDormir.remove(acao);
    }

    /** Chamado quando o sistema acordou. Devolve o que desfaz o registro. */
    public Runnable aoAcordar(Runnable acao) {
        aoAcordar.add(acao);
        return () -> aoAcordar.remove(acao);
    }

    /** Liga uma conexão ao monitor. Devolve o que desfaz a ligação. */
    public Runnable acompanhar(ConexaoSsh conexao) {
        Runnable a = aoDormir(conexao::aoDormir);
        Runnable b = aoAcordar(conexao::aoAcordar);
        return () -> {
            a.run();
            b.run();
        };
    }

    @Override
    public void systemAboutToSleep(SystemSleepEvent evento) {
        LOG.info("O Mac vai dormir.");
        disparar(aoDormir);
    }

    @Override
    public void systemAwoke(SystemSleepEvent evento) {
        LOG.info("O Mac acordou.");
        disparar(aoAcordar);
    }

    private static void disparar(List<Runnable> acoes) {
        for (Runnable a : acoes) {
            try {
                a.run();
            } catch (RuntimeException e) {
                LOG.warn("Ouvinte de sono falhou: {}", e.toString());
            }
        }
    }
}
