package br.com.labdesktop.teste;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Saídas SINTÉTICAS dos comandos de segurança (nada vem de um servidor real): IPs de documentação (203.0.113.x e
 * 198.51.100.x, RFC 5737), usuários "admin" e "oracle", uma varredura sem usuário e o login legítimo da rede local.
 */
public final class SaidasDeSeguranca {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxx").withZone(ZoneOffset.UTC);

    private SaidasDeSeguranca() { }

    public static String ts(Instant t) {
        return ISO.format(t);
    }

    private static String l(Instant t, String mensagem) {
        return ts(t) + " servidor-exemplo sshd-session[" + (1000 + Math.abs(mensagem.hashCode() % 8000)) + "]: " + mensagem + "\n";
    }

    private static final java.time.Duration H = java.time.Duration.ofHours(1);

    /** O registro de uma semana com ataque. Contagens esperadas: 7 em 24 h, 8 em 7 dias (veja o teste dos parsers). */
    public static String registroComAtaque(Instant agora) {
        StringBuilder sb = new StringBuilder();
        // A: usuário inventado, três linhas da MESMA conexão (203.0.113.5:40001)
        Instant a = agora.minus(H.multipliedBy(2));
        sb.append(l(a, "Invalid user admin from 203.0.113.5 port 40001"));
        sb.append(l(a, "Failed password for invalid user admin from 203.0.113.5 port 40001 ssh2"));
        sb.append(l(a, "Connection closed by invalid user admin 203.0.113.5 port 40001 [preauth]"));
        // B: "oracle", outra conexão do mesmo IP
        sb.append(l(a.plusSeconds(5), "Failed password for invalid user oracle from 203.0.113.5 port 40002 ssh2"));
        // C: outro IP
        sb.append(l(agora.minus(H.multipliedBy(5)), "Failed password for invalid user admin from 198.51.100.7 port 41000 ssh2"));
        // D: usuário que existe, senha errada; a linha do PAM (sem porta) acompanha no mesmo segundo
        Instant d = agora.minus(H.multipliedBy(3));
        sb.append(l(d, "pam_unix(sshd:auth): authentication failure; logname= uid=0 euid=0 tty=ssh ruser= rhost=203.0.113.5  user=labuser"));
        sb.append(l(d, "Failed password for labuser from 203.0.113.5 port 40010 ssh2"));
        sb.append(l(d.plusSeconds(1), "Connection closed by authenticating user labuser 203.0.113.5 port 40010 [preauth]"));
        // E: varredura / protocolo estranho, sem usuário
        Instant e = agora.minus(H);
        sb.append(l(e, "Unable to negotiate with 198.51.100.9 port 50000: no matching key exchange method found. Their offer: diffie-hellman-group1-sha1 [preauth]"));
        sb.append(l(e.plusSeconds(10), "Connection closed by 198.51.100.9 port 50001 [preauth]"));
        // F: três dias atrás (só entra na conta de 7 dias)
        sb.append(l(agora.minus(java.time.Duration.ofDays(3)), "Failed password for invalid user admin from 203.0.113.5 port 42000 ssh2"));
        // G: oito dias atrás (fora da janela)
        sb.append(l(agora.minus(java.time.Duration.ofDays(8)), "Failed password for invalid user admin from 203.0.113.5 port 43000 ssh2"));
        // H: só a linha do PAM, sem "Failed password" (conta sozinha)
        sb.append(l(agora.minus(H.multipliedBy(4)), "pam_unix(sshd:auth): authentication failure; logname= uid=0 euid=0 tty=ssh ruser= rhost=198.51.100.20"));
        // logins aceitos: o legítimo da rede local e um de fora, por senha
        sb.append(l(agora.minus(H), "Accepted publickey for labuser from 10.0.0.10 port 54321 ssh2: ED25519 SHA256:AbCdEfGhIjKlMnOpQrStUvWxYz0123456789abcdefg"));
        sb.append(l(agora.minus(java.time.Duration.ofMinutes(30)), "Accepted password for deploy from 203.0.113.77 port 61000 ssh2"));
        // ruído que o parser tem de ignorar
        sb.append(l(agora.minus(H), "Connection closed by 10.0.0.10 port 54321"));
        sb.append("linha-sem-data qualquer coisa\n");
        return sb.toString();
    }

    /**
     * Uma semana de força bruta com {@code n} conexões que falharam, de 9 IPs de documentação, nos quatro tipos, uma a cada 10
     * minutos para trás, mais {@code logins} logins aceitos (um de fora da rede local por senha a cada 4, o resto da LAN).
     */
    public static String registroGrande(Instant agora, int n, int logins) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            Instant t = agora.minus(java.time.Duration.ofMinutes(10L * i + 1));
            String ip = (i % 2 == 0 ? "203.0.113." : "198.51.100.") + (i % 9 + 1);
            int porta = 30000 + i;
            switch (i % 4) {
                case 0 -> sb.append(l(t, "Failed password for invalid user admin from " + ip + " port " + porta + " ssh2"));
                case 1 -> sb.append(l(t, "Failed password for labuser from " + ip + " port " + porta + " ssh2"));
                case 2 -> sb.append(l(t, "Connection closed by " + ip + " port " + porta + " [preauth]"));
                default -> sb.append(l(t, "Invalid user oracle from " + ip + " port " + porta));
            }
        }
        for (int i = 0; i < logins; i++) {
            Instant t = agora.minus(java.time.Duration.ofMinutes(30L * i + 20));
            if (i % 4 == 0) {
                sb.append(l(t, "Accepted password for deploy from 203.0.113.77 port " + (50000 + i) + " ssh2"));
            } else {
                sb.append(l(t, "Accepted publickey for labuser from 10.0.0." + (10 + i % 5) + " port " + (50000 + i)
                    + " ssh2: ED25519 SHA256:AbCdEfGhIjKlMnOpQrStUvWxYz0123456789abcdefg"));
            }
        }
        return sb.toString();
    }

    /** Só o login legítimo da rede local (zero tentativas). */
    public static String registroCalmo(Instant agora) {
        return l(agora.minus(H), "Accepted publickey for labuser from 10.0.0.10 port 54321 ssh2: ED25519 SHA256:AbCdEfGhIjKlMnOpQrStUvWxYz0123456789abcdefg");
    }

    public static final String SS = """
        LISTEN 0      128          0.0.0.0:2222       0.0.0.0:*    users:(("sshd",pid=701,fd=3))
        LISTEN 0      128             [::]:2222          [::]:*    users:(("sshd",pid=701,fd=4))
        LISTEN 0      4096       127.0.0.1:7000       0.0.0.0:*
        LISTEN 0      4096     10.0.0.10:8080       0.0.0.0:*    users:(("docker-proxy",pid=900,fd=7))
        LISTEN 0      511                *:80                *:*
        LISTEN 0      128            [::1]:631           [::]:*
        LISTEN 0      4096   127.0.0.53%lo:53        0.0.0.0:*
        """;

    public static final String NFT = """
        table inet filter {
        \tchain input {
        \t\ttype filter hook input priority filter; policy drop;
        \t\tct state established,related accept
        \t\tiif "lo" accept
        \t\ttcp dport 2222 ip saddr 10.0.0.0/24 accept
        \t\ttcp dport { 80, 443 } accept
        \t\ticmp type echo-request accept
        \t\tcounter packets 10 bytes 600 drop
        \t}
        }
        """;

    public static final String IPTABLES = """
        -P INPUT DROP
        -P FORWARD DROP
        -P OUTPUT ACCEPT
        -A INPUT -i lo -j ACCEPT
        -A INPUT -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
        -A INPUT -s 10.0.0.0/24 -p tcp -m tcp --dport 2222 -j ACCEPT
        -A INPUT -p tcp -m tcp --dport 443 -j ACCEPT
        -A INPUT -s 203.0.113.0/24 -j DROP
        """;

    public static final String SSHD_SEGURO = """
        port 2222
        addressfamily any
        permitrootlogin no
        maxauthtries 3
        pubkeyauthentication yes
        passwordauthentication no
        kbdinteractiveauthentication no
        """;

    public static final String SSHD_COM_SENHA = SSHD_SEGURO.replace("passwordauthentication no", "passwordauthentication yes");

    public static final String FAIL2BAN = """
        Status for the jail: sshd
        |- Filter
        |  |- Currently failed:\t1
        |  |- Total failed:\t88
        |  `- Journal matches:\t_SYSTEMD_UNIT=ssh.service
        `- Actions
           |- Currently banned:\t2
           |- Total banned:\t40
           `- Banned IP list:\t203.0.113.7 198.51.100.4
        """;
}
