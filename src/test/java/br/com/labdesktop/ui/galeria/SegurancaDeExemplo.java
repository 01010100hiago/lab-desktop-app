package br.com.labdesktop.ui.galeria;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * As saídas dos comandos de segurança do servidor de exemplo, INVENTADAS para a imagem da galeria: só endereços de documentação (203.0.113.x, 198.51.100.x e
 * 192.0.2.x), usuários ana e beto, impressões digitais sintéticas. As datas são relativas ao relógio do teste (o registro conta as últimas 24 horas e 7 dias).
 */
final class SegurancaDeExemplo {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxx").withZone(ZoneOffset.UTC);

    private SegurancaDeExemplo() { }

    static final String SSHD_COM_SENHA = """
        port 2222
        addressfamily any
        permitrootlogin no
        maxauthtries 3
        pubkeyauthentication yes
        passwordauthentication yes
        kbdinteractiveauthentication no
        """;

    static final String SSHD_SO_CHAVE = SSHD_COM_SENHA.replace("passwordauthentication yes", "passwordauthentication no");

    static final String PORTAS = """
        LISTEN 0      128          0.0.0.0:2222       0.0.0.0:*    users:(("sshd",pid=701,fd=3))
        LISTEN 0      128             [::]:2222          [::]:*    users:(("sshd",pid=701,fd=4))
        LISTEN 0      4096     192.0.2.5:8080       0.0.0.0:*    users:(("docker-proxy",pid=900,fd=7))
        LISTEN 0      511                *:80                *:*
        LISTEN 0      511                *:443               *:*
        """;

    static final String NFT = """
        table inet filter {
        \tchain input {
        \t\ttype filter hook input priority filter; policy drop;
        \t\tct state established,related accept
        \t\tiif "lo" accept
        \t\ttcp dport 2222 ip saddr 192.0.2.0/24 accept
        \t\ttcp dport { 80, 443 } accept
        \t\ticmp type echo-request accept
        \t\tcounter packets 10 bytes 600 drop
        \t}
        }
        """;

    static final String FAIL2BAN = """
        Status for the jail: sshd
        |- Filter
        |  |- Currently failed:\t3
        |  |- Total failed:\t214
        |  `- Journal matches:\t_SYSTEMD_UNIT=ssh.service
        `- Actions
           |- Currently banned:\t2
           |- Total banned:\t37
           `- Banned IP list:\t203.0.113.17 198.51.100.44
        """;

    private static String linha(Instant t, String mensagem) {
        return ISO.format(t) + " servidor-demo sshd-session[" + (1000 + Math.abs(mensagem.hashCode() % 8000)) + "]: " + mensagem + "\n";
    }

    /** Uma semana de tentativas de entrada vindas de endereços de documentação e alguns logins de ana e beto. */
    static String registro(Instant agora, boolean comLogins, boolean comLoginPorSenha) {
        StringBuilder sb = new StringBuilder();
        Duration h = Duration.ofHours(1);
        String[][] tentativas = {
            {"1", "203.0.113.17", "Invalid user admin from 203.0.113.17 port 40001"},
            {"1", "203.0.113.17", "Failed password for invalid user admin from 203.0.113.17 port 40001 ssh2"},
            {"2", "203.0.113.17", "Failed password for invalid user oracle from 203.0.113.17 port 40002 ssh2"},
            {"3", "198.51.100.44", "Failed password for invalid user test from 198.51.100.44 port 41000 ssh2"},
            {"3", "198.51.100.44", "Failed password for invalid user ubuntu from 198.51.100.45 port 41001 ssh2"},
            {"5", "203.0.113.90", "Failed password for ana from 203.0.113.90 port 40010 ssh2"},
            {"6", "198.51.100.77", "Unable to negotiate with 198.51.100.77 port 50000: no matching key exchange method found. Their offer: diffie-hellman-group1-sha1 [preauth]"},
            {"9", "203.0.113.17", "Failed password for invalid user root from 203.0.113.17 port 40030 ssh2"},
            {"20", "198.51.100.12", "Failed password for invalid user admin from 198.51.100.12 port 41100 ssh2"},
            {"31", "203.0.113.90", "Failed password for invalid user guest from 203.0.113.90 port 42000 ssh2"},
            {"52", "198.51.100.44", "Failed password for invalid user postgres from 198.51.100.44 port 43000 ssh2"},
            {"75", "203.0.113.17", "Invalid user git from 203.0.113.17 port 44000"},
        };
        for (String[] t : tentativas) {
            sb.append(linha(agora.minus(h.multipliedBy(Long.parseLong(t[0])).plusSeconds(Long.parseLong(t[0]) * 7)), t[2]));
        }
        if (comLogins) {
            sb.append(linha(agora.minus(Duration.ofMinutes(25)), "Accepted publickey for ana from 192.0.2.30 port 51234 ssh2: ED25519 " + CenaDoServidor.impressao(1)));
            sb.append(linha(agora.minus(Duration.ofHours(4)), "Accepted publickey for beto from 192.0.2.44 port 52311 ssh2: ED25519 " + CenaDoServidor.impressao(2)));
            if (comLoginPorSenha) {
                sb.append(linha(agora.minus(Duration.ofHours(7)), "Accepted password for beto from 198.51.100.23 port 53100 ssh2"));
            }
            sb.append(linha(agora.minus(Duration.ofHours(30)), "Accepted publickey for ana from 192.0.2.30 port 50999 ssh2: ED25519 " + CenaDoServidor.impressao(1)));
            sb.append(linha(agora.minus(Duration.ofDays(2)), "Accepted publickey for ana from 192.0.2.31 port 50111 ssh2: ED25519 " + CenaDoServidor.impressao(3)));
        }
        return sb.toString();
    }

    /** A saída que o servidor de exemplo dá a este comando de segurança, ou {@code null} se o comando não é um deles. */
    static String responder(String comando, String sshd, boolean comLogins, boolean comLoginPorSenha) {
        if (comando.startsWith("sudo -n journalctl -u ssh")) {
            return registro(Instant.now(), comLogins, comLoginPorSenha);
        }
        if (comando.startsWith("ss -tlnpH")) {
            return PORTAS;
        }
        if (comando.startsWith("sudo -n nft list chain")) {
            return NFT;
        }
        if (comando.startsWith("sudo -n sshd -T")) {
            return sshd;
        }
        if (comando.startsWith("systemctl is-active fail2ban")) {
            return "active\n";
        }
        if (comando.startsWith("sudo -n fail2ban-client status sshd")) {
            return FAIL2BAN;
        }
        return null;
    }
}
