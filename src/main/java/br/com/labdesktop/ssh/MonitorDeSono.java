package br.com.labdesktop.ssh;

import java.awt.desktop.SystemSleepEvent;
import java.awt.desktop.SystemSleepListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Avisa as {@link ConexaoSsh} quando o computador dorme e acorda (ARQUITETURA 6.3): pelo evento do macOS ou, onde o sistema não avisa (Windows, Linux), pelo {@link VigiaDeRelogio}. Sono derruba o TCP sem avisar: ao
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
    private final List<Runnable> aoMudarARede = new CopyOnWriteArrayList<>();

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

    /** Chamado quando a rede do computador mudou (a {@link VigiaDeRede} viu). Devolve o que desfaz o registro. */
    public Runnable aoMudarARede(Runnable acao) {
        aoMudarARede.add(acao);
        return () -> aoMudarARede.remove(acao);
    }

    /** Liga uma conexão ao monitor. Devolve o que desfaz a ligação. */
    public Runnable acompanhar(ConexaoSsh conexao) {
        Runnable a = aoDormir(conexao::aoDormir);
        Runnable b = aoAcordar(conexao::aoAcordar);
        Runnable c = aoMudarARede(conexao::aoMudarARede);
        return () -> {
            a.run();
            b.run();
            c.run();
        };
    }

    /**
     * O computador esteve parado e já voltou (o {@link VigiaDeRelogio} viu o relógio saltar, no Windows e no Linux, que não avisam): vale como "dormiu" e "acordou" de uma vez. A
     * conexão vai a {@code SUSPENSO} e reconecta depois da espera de sempre.
     */
    public void suspensaoDetectada() {
        disparar(aoDormir);
        disparar(aoAcordar);
    }

    /** A rede do computador mudou: quem está esperando para reconectar tenta agora (ver {@link ConexaoSsh#aoMudarARede}). */
    public void redeMudou() {
        disparar(aoMudarARede);
    }

    @Override
    public void systemAboutToSleep(SystemSleepEvent evento) {
        LOG.info("O computador vai dormir.");
        disparar(aoDormir);
    }

    @Override
    public void systemAwoke(SystemSleepEvent evento) {
        LOG.info("O computador acordou.");
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
