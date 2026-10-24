package br.com.labdesktop.servidor;

import br.com.labdesktop.seguranca.Redator;
import br.com.labdesktop.servidor.ResumoDeSeguranca.ConfiguracaoDoSsh;
import br.com.labdesktop.servidor.ResumoDeSeguranca.Leitura;
import br.com.labdesktop.servidor.ResumoDeSeguranca.PortaAberta;
import br.com.labdesktop.servidor.ResumoDeSeguranca.ResumoDoFirewall;
import br.com.labdesktop.servidor.ResumoDeSeguranca.Tentativas;
import br.com.labdesktop.ssh.ProvisionadorRemoto;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * O {@link LeitorDeSeguranca} por SSH {@code exec}, com a conexão que o app já tem. SOMENTE LEITURA e com comandos
 * FIXOS: nada que venha da interface ou do servidor entra na linha de comando (as constantes abaixo são o texto
 * inteiro). {@code sudo -n} nunca pergunta senha: sem sudo sem senha o comando falha na hora e a seção mostra
 * "sem permissão" em vez de travar. A saída é limitada pelo teto do {@code ExecRemoto} e, no registro, filtrada
 * já no servidor ({@code grep}), para uma semana de ataque caber no teto.
 *
 * <p>Cada seção roda sozinha: a falha de um comando não derruba as outras. Bloqueia (SSH): thread de fundo.
 */
public final class LeitorDeSegurancaPorSsh implements LeitorDeSeguranca {

    private static final Logger LOG = LoggerFactory.getLogger(LeitorDeSegurancaPorSsh.class);

    // Os comandos. FIXOS (ver a Javadoc da classe). O "grep" no fim do registro só tira o ruído; o parser filtra de novo.
    public static final String COMANDO_REGISTRO = "sudo -n journalctl -u ssh --since \"7 days ago\" --no-pager -o short-iso "
        + "| grep -E \"Failed |Invalid user|authentication failure|Connection closed by|Connection reset by|"
        + "Unable to negotiate|kex_exchange_identification|no matching|banner exchange|Did not receive identification|"
        + "Bad protocol version|maximum authentication attempts|Accepted \"";
    public static final String COMANDO_PORTAS = "ss -tlnpH";
    public static final String COMANDO_NFT = "sudo -n nft list chain inet filter input";
    public static final String COMANDO_IPTABLES = "sudo -n iptables -S";
    public static final String COMANDO_SSHD = "sudo -n sshd -T";
    public static final String COMANDO_FAIL2BAN_ATIVO = "systemctl is-active fail2ban";
    public static final String COMANDO_FAIL2BAN_STATUS = "sudo -n fail2ban-client status sshd";

    private static final Duration PRAZO = Duration.ofSeconds(20);
    private static final byte[] SEM_ENTRADA = new byte[0];

    private final ProvisionadorRemoto.Executor executor;
    private final Supplier<Instant> relogio;

    public LeitorDeSegurancaPorSsh(ProvisionadorRemoto.Executor executor, Supplier<Instant> relogio) {
        this.executor = executor;
        this.relogio = relogio;
    }

    /** O que um comando devolveu, ou por que não deu. */
    private record Saida(ResultadoExec exec, boolean semPermissao) {
        boolean falhou() {
            return exec == null;
        }
    }

    private Saida rodar(String comando) {
        try {
            ResultadoExec r = executor.executar(comando, SEM_ENTRADA, PRAZO);
            return new Saida(r, pareceFaltaDePermissao(r.stderr()));
        } catch (IOException e) {
            LOG.debug("Comando de segurança sem resposta: {}", Redator.redigir(e));
            return new Saida(null, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Saida(null, false);
        } catch (RuntimeException e) {
            LOG.warn("Comando de segurança falhou por erro inesperado: {}", Redator.redigir(e));
            return new Saida(null, false);
        }
    }

    static boolean pareceFaltaDePermissao(String erro) {
        String e = erro == null ? "" : erro.toLowerCase(java.util.Locale.ROOT);
        return e.contains("a password is required") || e.contains("a terminal is required") || e.contains("not in the sudoers")
            || e.contains("may not run sudo") || e.contains("insufficient permissions") || e.contains("permission denied")
            || e.contains("operation not permitted") || e.contains("not allowed to execute");
    }

    @Override
    public ResumoDeSeguranca ler() {
        Instant agora = relogio.get();
        Leitura<ConfiguracaoDoSsh> ssh = lerSsh();
        Integer portaSsh = ssh.lida() ? ssh.dados().porta() : null;
        return new ResumoDeSeguranca(agora, lerTentativas(agora), lerPortas(), lerFirewall(portaSsh), ssh, lerFail2ban());
    }

    private Leitura<Tentativas> lerTentativas(Instant agora) {
        Saida s = rodar(COMANDO_REGISTRO);
        if (s.falhou()) {
            return Leitura.indisponivel();
        }
        if (s.semPermissao()) {
            return Leitura.semPermissao();
        }
        int status = s.exec().status();
        if (status != 0 && status != 1) { // 1 = o grep não achou nenhuma linha: zero tentativas
            return Leitura.indisponivel();
        }
        return Leitura.lida(ParsersDeSeguranca.lerRegistroDoSsh(s.exec().stdout(), agora, s.exec().truncado()));
    }

    private Leitura<List<PortaAberta>> lerPortas() {
        Saida s = rodar(COMANDO_PORTAS);
        if (s.falhou() || s.exec().status() != 0) {
            return Leitura.indisponivel();
        }
        return Leitura.lida(ParsersDeSeguranca.lerPortas(s.exec().stdout()));
    }

    private Leitura<ResumoDoFirewall> lerFirewall(Integer portaSsh) {
        Saida nft = rodar(COMANDO_NFT);
        if (!nft.falhou() && nft.semPermissao()) {
            return Leitura.semPermissao();
        }
        if (!nft.falhou() && nft.exec().status() == 0) {
            ResumoDoFirewall r = ParsersDeSeguranca.lerNft(nft.exec().stdout(), portaSsh);
            if (r.identificado()) {
                return Leitura.lida(r);
            }
        }
        Saida ipt = rodar(COMANDO_IPTABLES);
        if (!ipt.falhou() && ipt.semPermissao()) {
            return Leitura.semPermissao();
        }
        if (!ipt.falhou() && ipt.exec().status() == 0) {
            ResumoDoFirewall r = ParsersDeSeguranca.lerIptables(ipt.exec().stdout(), portaSsh);
            if (r.identificado()) {
                return Leitura.lida(r);
            }
        }
        if (nft.falhou() && ipt.falhou()) {
            return Leitura.indisponivel();
        }
        return Leitura.lida(new ResumoDoFirewall(false, null, null, List.of()));
    }

    private Leitura<ConfiguracaoDoSsh> lerSsh() {
        Saida s = rodar(COMANDO_SSHD);
        if (s.falhou()) {
            return Leitura.indisponivel();
        }
        if (s.semPermissao()) {
            return Leitura.semPermissao();
        }
        ConfiguracaoDoSsh c = s.exec().status() == 0 ? ParsersDeSeguranca.lerSshd(s.exec().stdout()) : null;
        return c == null ? Leitura.indisponivel() : Leitura.lida(c);
    }

    private Leitura<ResumoDeSeguranca.EstadoFail2ban> lerFail2ban() {
        Saida ativo = rodar(COMANDO_FAIL2BAN_ATIVO);
        if (ativo.falhou()) {
            return Leitura.indisponivel();
        }
        boolean rodando = "active".equals(ativo.exec().stdout().strip());
        if (!rodando) {
            return Leitura.lida(ParsersDeSeguranca.lerFail2ban(false, null));
        }
        Saida status = rodar(COMANDO_FAIL2BAN_STATUS);
        if (!status.falhou() && status.semPermissao()) {
            // Ativo, mas sem poder contar os IPs: ainda é uma boa notícia saber que ele roda.
            return Leitura.lida(new ResumoDeSeguranca.EstadoFail2ban(true, null, null, List.of()));
        }
        String texto = status.falhou() || status.exec().status() != 0 ? null
            : new String(status.exec().stdout().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        return Leitura.lida(ParsersDeSeguranca.lerFail2ban(true, texto));
    }
}
