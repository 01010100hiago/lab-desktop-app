package br.com.labdesktop.teste;

import br.com.labdesktop.acesso.ComandosDoAcesso;
import br.com.labdesktop.ssh.ProvisionadorRemoto;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Um servidor com sshd de mentira para o desligar a senha: guarda o {@code sshd_config}, a pasta {@code sshd_config.d} e as permissões do usuário
 * e responde SÓ às linhas de comando fixas de {@link ComandosDoAcesso} (qualquer outra linha derruba o teste: o app não pode mandar comando
 * que não está na lista). O {@code sshd -T} dele reflete o que está no disco naquele momento, como o de verdade.
 */
public final class SshdFalso implements ProvisionadorRemoto.Executor {

    public static final String NOSSO = ComandosDoAcesso.NOME_DO_ARQUIVO_PROPRIO;

    public boolean root;
    public boolean sudo = true;
    public boolean pastaExiste = true;
    public String principal = "Include /etc/ssh/sshd_config.d/*.conf\n\nPort 22\nUsePAM yes\nPasswordAuthentication yes\nKbdInteractiveAuthentication no\n";
    public final TreeMap<String, String> pasta = new TreeMap<>();
    public String metodos = "any";
    public boolean usaPam = true;
    public boolean chavePermitida = true;
    public boolean senhaBase = true;
    public boolean tecladoBase = false;
    /** O {@code sshd -t} reprova quando o arquivo do app existe. */
    public boolean validacaoFalha;
    public boolean recarregarFalha;
    /** Algo que o app não previu manda mais: o {@code sshd -T} continua mostrando a senha ligada mesmo com o arquivo do app. */
    public boolean ignoraONosso;
    /** Depois da primeira recarga, o desfazer não consegue apagar o arquivo. */
    public boolean apagarFalha;
    /** Lança IOException quando o comando é igual a este (a conexão que cai). */
    public String cairNoComando;

    public final List<String> comandos = new CopyOnWriteArrayList<>();
    public final List<String> escritas = new CopyOnWriteArrayList<>();
    public final List<String> eventos = new CopyOnWriteArrayList<>();
    public final TreeMap<String, String> copias = new TreeMap<>();
    public int recargas;
    private final String carimbo;

    public SshdFalso(String carimbo) {
        this.carimbo = carimbo;
    }

    public boolean temArquivoNosso() {
        return pasta.containsKey(NOSSO);
    }

    @Override
    public ResultadoExec executar(String comando, byte[] stdin, Duration prazo) throws IOException {
        comandos.add(comando);
        if (comando.equals(cairNoComando)) {
            throw new IOException("A conexão caiu.");
        }
        if (comando.equals(ComandosDoAcesso.lerPermissoes())) {
            return ok("uid=" + (root ? 0 : 1000) + "\nsudo=" + (sudo ? "sim" : "nao") + "\n");
        }
        for (String p : new String[] {ComandosDoAcesso.SEM_PREFIXO, ComandosDoAcesso.COM_SUDO}) {
            boolean permitido = p.isEmpty() ? root : (root || sudo);
            if (comando.equals(ComandosDoAcesso.lerConfiguracaoEfetiva(p))) {
                return permitido ? ok(sshdT()) : negado();
            }
            if (comando.equals(ComandosDoAcesso.lerConfigPrincipal(p))) {
                return permitido ? ok(principal) : negado();
            }
            if (comando.equals(ComandosDoAcesso.lerPastaDaConfig(p))) {
                return ok(textoDaPasta());
            }
            if (comando.equals(ComandosDoAcesso.validarConfiguracao(p))) {
                return validacaoFalha && temArquivoNosso()
                    ? new ResultadoExec(255, new byte[0], (ComandosDoAcesso.ARQUIVO_PROPRIO + " line 2: Bad configuration option\n").getBytes(StandardCharsets.UTF_8), false)
                    : ok("");
            }
            if (comando.equals(ComandosDoAcesso.copiaDoArquivoPrincipal(p, carimbo))) {
                escritas.add(comando);
                eventos.add("copia-principal");
                copias.put(ComandosDoAcesso.nomeDaCopiaDoPrincipal(carimbo), principal);
                return ok("");
            }
            if (comando.equals(ComandosDoAcesso.copiaDoArquivoProprio(p, carimbo))) {
                escritas.add(comando);
                eventos.add("copia-propria");
                if (temArquivoNosso()) {
                    copias.put(ComandosDoAcesso.nomeDaCopiaDoProprio(carimbo), pasta.get(NOSSO));
                }
                return ok("");
            }
            if (comando.equals(ComandosDoAcesso.gravarArquivoProprio(p))) {
                escritas.add(comando);
                eventos.add("gravar");
                pasta.put(NOSSO, new String(stdin, StandardCharsets.UTF_8));
                return ok("");
            }
            if (comando.equals(ComandosDoAcesso.recarregarSshd(p))) {
                escritas.add(comando);
                if (recarregarFalha) {
                    return new ResultadoExec(5, new byte[0], "Failed to reload ssh.service: Unit ssh.service not found.\n".getBytes(StandardCharsets.UTF_8), false);
                }
                recargas++;
                eventos.add("recarregar");
                return ok("");
            }
            if (comando.equals(ComandosDoAcesso.restaurarArquivoProprio(p, carimbo))) {
                escritas.add(comando);
                eventos.add("restaurar");
                pasta.put(NOSSO, copias.get(ComandosDoAcesso.nomeDaCopiaDoProprio(carimbo)));
                return ok("");
            }
            if (comando.equals(ComandosDoAcesso.apagarArquivoProprio(p))) {
                escritas.add(comando);
                if (apagarFalha) {
                    return new ResultadoExec(1, new byte[0], "rm: cannot remove: Permission denied\n".getBytes(StandardCharsets.UTF_8), false);
                }
                eventos.add("apagar");
                pasta.remove(NOSSO);
                return ok("");
            }
        }
        throw new AssertionError("O app mandou um comando que não é de nenhuma lista fixa: " + comando);
    }

    private String sshdT() {
        boolean nossoValendo = temArquivoNosso() && !ignoraONosso && pasta.get(NOSSO).contains("PasswordAuthentication no");
        boolean senha = nossoValendo ? false : senhaBase;
        boolean teclado = temArquivoNosso() && !ignoraONosso ? false : tecladoBase;
        return "port 22\nusepam " + (usaPam ? "yes" : "no") + "\npasswordauthentication " + (senha ? "yes" : "no") + "\nkbdinteractiveauthentication "
            + (teclado ? "yes" : "no") + "\npubkeyauthentication " + (chavePermitida ? "yes" : "no") + "\nauthenticationmethods " + metodos + "\n";
    }

    private String textoDaPasta() {
        StringBuilder sb = new StringBuilder("pasta=" + (pastaExiste ? "sim" : "nao") + "\n");
        for (var e : new ArrayList<>(pasta.entrySet())) {
            sb.append("#### ").append(ComandosDoAcesso.PASTA_DA_CONFIG).append('/').append(e.getKey()).append('\n').append(e.getValue());
        }
        return sb.toString();
    }

    private static ResultadoExec ok(String saida) {
        return new ResultadoExec(0, saida.getBytes(StandardCharsets.UTF_8), new byte[0], false);
    }

    private static ResultadoExec negado() {
        return new ResultadoExec(1, new byte[0], "sudo: a password is required\n".getBytes(StandardCharsets.UTF_8), false);
    }
}
