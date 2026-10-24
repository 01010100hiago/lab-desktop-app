package br.com.labdesktop.ssh;

import br.com.labdesktop.cofre.Pin;
import java.net.SocketAddress;
import java.security.PublicKey;
import java.util.List;
import java.util.function.Supplier;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.AttributeRepository.AttributeKey;
import org.apache.sshd.common.config.keys.KeyUtils;

/**
 * O verificador definitivo da chave do host (ARQUITETURA 6.2): SÓ compara a impressão da chave negociada com o
 * pin do cofre, para o algoritmo negociado. Nunca abre diálogo e nunca grava pin.
 *
 * <p>Por que nunca abre diálogo: o verificador roda dentro do {@code auth().verify(15 s)}; um diálogo ali
 * consome o prazo da autenticação (reproduzido: verificador bloqueado 20 s estoura a autenticação aos 15 s).
 * A confiança é dada ANTES, pela {@link SondagemDeHost} mais o {@code DialogoConfiarHost}.
 *
 * <p>Pin divergente, ou algoritmo negociado sem pin, devolve {@code false}: o MINA encerra a sessão antes de
 * qualquer credencial ir ao servidor. Não existe "aceitar mesmo assim".
 *
 * <p>O veredito é gravado também NA SESSÃO que perguntou ({@link #VEREDITO_DA_SESSAO}): o cliente SSH é um só
 * por servidor, e uma tentativa velha ainda em andamento (o Mac dormiu no meio de um connect) não pode sujar a
 * leitura da tentativa nova. {@link #ultimo()} e {@link #ultimaDivergencia()} guardam só "o último de todos".
 */
public final class VerificadorDeChaveDoServidor implements ServerKeyVerifier {

    /** O que aconteceu na última verificação (a {@link ConexaoSsh} usa para classificar a falha). */
    public enum Resultado { NAO_VERIFICADO, OK, SEM_PIN_PARA_O_ALGORITMO, DIVERGENTE }

    /** Detalhe de uma divergência, para a mensagem da tela (impressões são públicas). */
    public record Divergencia(String algoritmo, String esperada, String recebida) { }

    /** O que uma verificação concluiu; {@code divergencia} só existe quando não foi {@link Resultado#OK}. */
    public record Veredito(Resultado resultado, Divergencia divergencia) { }

    /** Onde o veredito fica guardado, em cada {@link ClientSession} que passou pelo verificador. */
    public static final AttributeKey<Veredito> VEREDITO_DA_SESSAO = new AttributeKey<>();

    private final Supplier<List<Pin>> pins;
    private volatile Resultado ultimo = Resultado.NAO_VERIFICADO;
    private volatile Divergencia ultimaDivergencia;

    /**
     * @param pins lido a CADA verificação: depois de o usuário confiar no host (nova gravação no cofre), a próxima
     *             conexão já enxerga o pin sem recriar o cliente
     */
    public VerificadorDeChaveDoServidor(Supplier<List<Pin>> pins) {
        this.pins = pins;
    }

    @Override
    public boolean verifyServerKey(ClientSession sessao, SocketAddress remoto, PublicKey chaveDoHost) {
        String algoritmo = KeyUtils.getKeyType(chaveDoHost);
        String recebida = KeyUtils.getFingerPrint(chaveDoHost);
        Pin pin = pins.get().stream().filter(p -> p.algoritmo().equals(algoritmo)).findFirst().orElse(null);
        Veredito veredito;
        if (pin == null) {
            veredito = new Veredito(Resultado.SEM_PIN_PARA_O_ALGORITMO, new Divergencia(algoritmo, null, recebida));
        } else if (!pin.confere(chaveDoHost)) {
            veredito = new Veredito(Resultado.DIVERGENTE, new Divergencia(algoritmo, pin.impressao(), recebida));
        } else {
            veredito = new Veredito(Resultado.OK, null);
        }
        ultimaDivergencia = veredito.divergencia();
        ultimo = veredito.resultado();
        if (sessao != null) {
            sessao.setAttribute(VEREDITO_DA_SESSAO, veredito);
        }
        return veredito.resultado() == Resultado.OK;
    }

    public Resultado ultimo() {
        return ultimo;
    }

    public Divergencia ultimaDivergencia() {
        return ultimaDivergencia;
    }

    /** Esquece o "último de todos" (o veredito de cada sessão não é afetado). */
    public void reiniciar() {
        ultimo = Resultado.NAO_VERIFICADO;
        ultimaDivergencia = null;
    }
}
