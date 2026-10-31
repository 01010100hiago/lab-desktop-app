package br.com.labdesktop.ssh;

import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.transporte.DestinoSsh;
import br.com.labdesktop.transporte.RotaDoSsh;

/** As frases do usuário quando o caminho até o SSH tem um túnel externo no meio: separam "o túnel não subiu" de "o túnel subiu e o SSH não respondeu por ele". */
public final class FrasesDoCaminho {

    private FrasesDoCaminho() { }

    /**
     * O túnel abriu (ou morreu) e o SSH não deu certo por ele. Se o programa do túnel já saiu, a causa dele (login da AWS, instância sem SSM...) é a frase; senão o túnel está de pé e o
     * que falhou foi o SSH do servidor.
     */
    public static String semSshPeloTunel(Transporte transporte, DestinoSsh destino) {
        var doTunel = destino == null ? java.util.Optional.<br.com.labdesktop.transporte.FalhaDoTunel>empty() : destino.falhaDoTunel();
        if (doTunel.isPresent()) {
            return doTunel.get().mensagem();
        }
        return "O túnel do modo " + transporte.rotulo() + " abriu, mas o SSH do servidor não respondeu por ele. Confira se o SSH está ligado no servidor"
            + (transporte.tipo() == Transporte.Tipo.SSM ? " e se a porta SSH da instância está certa" : "") + ".";
    }

    /** A frase de uma falha qualquer pela rota: direta (endereço e porta) ou por túnel. Sem saber a fase, a regra da {@code IOException} comum do Windows não vale ({@link CausaDeRede}). */
    public static String traduzir(RotaDoSsh rota, DestinoSsh destino, Throwable causa) {
        return traduzir(rota, destino, causa, false);
    }

    /** Como {@link #traduzir(RotaDoSsh, DestinoSsh, Throwable)}, dizendo se a falha veio de ABRIR a conexão (ainda sem sessão): só então uma {@code IOException} comum (a recusa do Windows) vale "recusou". */
    public static String traduzir(RotaDoSsh rota, DestinoSsh destino, Throwable causa, boolean aoConectar) {
        return rota.ehTunel() ? semSshPeloTunel(rota.transporte(), destino) : SondagemDeHost.traduzirFalha(rota.host(), rota.porta(), causa, aoConectar);
    }
}
