package br.com.labdesktop.registro;

import br.com.labdesktop.ssh.ProvisionadorRemoto;
import br.com.labdesktop.ssh.ResultadoExec;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * O registro de um servidor LINUX por SSH {@code exec}, com a conexão que o app já tem: funciona com ou sem agente. SOMENTE LEITURA, três comandos FIXOS (as constantes abaixo
 * são o texto inteiro: nada digitado e nada que o servidor devolveu entra na linha), nunca {@code sudo}: o app NÃO eleva privilégio. A ordem: o diário do sistema
 * ({@code journalctl}); se ele não existe ou o usuário só enxerga as próprias mensagens, o arquivo {@code /var/log/syslog}; depois {@code /var/log/messages}. Sem permissão em todos:
 * {@link ResultadoDoRegistro.SemPermissao} com o comando exato {@code sudo usermod -aG systemd-journal <usuario>} para o usuário rodar no servidor.
 *
 * <p>Quando o usuário não está no grupo {@code systemd-journal} (nem {@code adm}) o {@code journalctl} sai com 0 e mostra só as mensagens do próprio usuário, com uma dica no erro
 * padrão ("not seeing messages from other users"): o leitor reconhece essa dica e tenta os arquivos, em vez de mostrar um registro quase vazio como se fosse tudo.
 */
public final class LeitorDeRegistroPorSsh implements LeitorDeRegistro {

    /**
     * Quanto os arquivos de texto devolvem no máximo: os ÚLTIMOS bytes do arquivo, sempre abaixo do teto do {@code ExecRemoto} (64 KB). Com {@code tail -n 200} uma linha longa estourava o
     * teto e o que se perdia eram as linhas MAIS NOVAS (o teto guarda o começo da saída); com {@code tail -c} o que pode faltar é só o começo, as mais antigas.
     */
    public static final int BYTES_DO_ARQUIVO = 60_000;
    /** O diário do sistema, as últimas 200 linhas, com data ISO, da MAIS NOVA para a mais antiga ({@code -r}): se o teto cortar a saída, o que sai é o fim, as mais antigas. */
    public static final String COMANDO_JOURNAL = "journalctl -r -n 200 --no-pager -o short-iso";
    public static final String COMANDO_SYSLOG = "tail -c " + BYTES_DO_ARQUIVO + " /var/log/syslog";
    public static final String COMANDO_MESSAGES = "tail -c " + BYTES_DO_ARQUIVO + " /var/log/messages";

    private static final Duration PRAZO = Duration.ofSeconds(20);
    private static final byte[] SEM_ENTRADA = new byte[0];
    private static final String SO_REGEX_DE_USUARIO = "^[a-z_][a-z0-9_-]{0,31}$";

    private final ProvisionadorRemoto.Executor executor;
    private final Supplier<String> usuario;
    private final Supplier<Instant> relogio;
    private final ZoneId fuso;

    /**
     * @param usuario o usuário do SSH, lido a cada leitura (o usuário pode editar o servidor); só entra no TEXTO do comando que o usuário copia, e se não tem a forma de um usuário do
     *                Linux vira {@code <seu-usuario>}
     */
    public LeitorDeRegistroPorSsh(ProvisionadorRemoto.Executor executor, Supplier<String> usuario, Supplier<Instant> relogio, ZoneId fuso) {
        this.executor = executor;
        this.usuario = usuario;
        this.relogio = relogio;
        this.fuso = fuso;
    }

    /** O comando que o usuário roda NO SERVIDOR para o usuário dele poder ler o diário (texto para copiar; o app nunca o executa). */
    public static String comandoParaDarAcesso(String usuario) {
        String u = usuario != null && usuario.matches(SO_REGEX_DE_USUARIO) ? usuario : "<seu-usuario>";
        return "sudo usermod -aG systemd-journal " + u;
    }

    private static final String EXPLICACAO = "Este comando põe o seu usuário no grupo que lê o registro do sistema. Rode-o você mesmo no servidor (o app nunca pede nem usa senha de administrador). "
        + "Depois feche a conexão com o servidor e conecte de novo (ou saia e entre no servidor): o grupo novo só vale numa conexão nova.";

    @Override
    public ResultadoDoRegistro ler() throws IOException, InterruptedException {
        boolean semPermissao = false;
        ResultadoExec j = executor.executar(COMANDO_JOURNAL, SEM_ENTRADA, PRAZO);
        if (j.status() == 0) {
            if (pareceFaltaDePermissao(j.stderr())) {
                semPermissao = true; // o journalctl só mostrou as mensagens do próprio usuário: tenta os arquivos antes de desistir
            } else {
                // newest first: se o teto cortou a saída, a última linha pode estar pela metade e sai; o que faltou são as mais antigas
                String texto = InterpretadorDeRegistro.semUltimaLinhaCortada(j.stdout(), j.truncado());
                return lido(InterpretadorDeRegistro.doSistema(texto, relogio.get(), fuso), "do diário do sistema (journalctl)", j.truncado());
            }
        } else if (pareceFaltaDePermissao(j.stderr())) {
            semPermissao = true;
        }
        for (String[] arquivo : List.of(new String[] {COMANDO_SYSLOG, "do arquivo /var/log/syslog"}, new String[] {COMANDO_MESSAGES, "do arquivo /var/log/messages"})) {
            ResultadoExec r = executor.executar(arquivo[0], SEM_ENTRADA, PRAZO);
            if (r.status() == 0) {
                // tail -c: com o arquivo maior que o pedido a PRIMEIRA linha vem pela metade e sai; o que falta são as mais antigas
                String saida = r.stdout();
                boolean parcial = saida.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= BYTES_DO_ARQUIVO - 16;
                String texto = parcial ? InterpretadorDeRegistro.semPrimeiraLinhaCortada(saida) : saida;
                return lido(InterpretadorDeRegistro.doSistema(texto, relogio.get(), fuso), arquivo[1], parcial || r.truncado());
            }
            if (pareceFaltaDePermissao(r.stderr())) {
                semPermissao = true;
            }
        }
        if (semPermissao) {
            return new ResultadoDoRegistro.SemPermissao("Seu usuário não tem permissão para ler o registro do sistema.", comandoParaDarAcesso(usuario.get()), EXPLICACAO);
        }
        return new ResultadoDoRegistro.Indisponivel("Este servidor não tem o registro do sistema nos lugares que o app conhece (journalctl, /var/log/syslog e /var/log/messages).");
    }

    private static ResultadoDoRegistro lido(InterpretadorDeRegistro.Leitura l, String fonte, boolean faltamAsMaisAntigas) {
        return new ResultadoDoRegistro.Lido(InterpretadorDeRegistro.maisNovasEmCima(l.linhas()), fonte, l.cortada() || faltamAsMaisAntigas);
    }

    /** O erro padrão fala de falta de permissão (de arquivo, de grupo do diário)? */
    static boolean pareceFaltaDePermissao(String erro) {
        String e = erro == null ? "" : erro.toLowerCase(Locale.ROOT);
        return e.contains("permission denied") || e.contains("not seeing messages from other users") || e.contains("users in groups") || e.contains("operation not permitted")
            || e.contains("insufficient permissions") || e.contains("access denied") || e.contains("permissão negada");
    }
}
