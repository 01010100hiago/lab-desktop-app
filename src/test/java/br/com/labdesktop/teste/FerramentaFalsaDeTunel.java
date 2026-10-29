package br.com.labdesktop.teste;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Um programa de MENTIRA, de verdade (um processo Java): faz o papel do {@code aws ssm start-session} e do {@code cloudflared access tcp}. Escuta na porta local
 * que os argumentos pedem ({@code --url 127.0.0.1:PORTA} ou {@code localPortNumber=PORTA}) SÓ em 127.0.0.1 e repassa cada conexão para a porta de um servidor (por exemplo o SSHD embutido
 * do teste), dada na variável {@code LABTEST_ALVO}. Não fala com a internet, não conhece AWS nem Cloudflare.
 *
 * <p>Variáveis para o teste programar o comportamento: {@code LABTEST_MORRER_APOS_MS} (sai sozinho depois de escutar), {@code LABTEST_SAIR_LOGO_TEXTO} (escreve o texto e sai na hora,
 * sem escutar), {@code LABTEST_NAO_ESCUTAR} (fica de pé e nunca escuta), {@code LABTEST_NETO_ARQUIVO} (cria um processo filho que grava o próprio pid nesse arquivo e fica dormindo: prova que os descendentes também morrem) e
 * {@code LABTEST_PATH_ARQUIVO} (grava o PATH que o app pediu para o filho).
 */
public final class FerramentaFalsaDeTunel {

    private FerramentaFalsaDeTunel() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--version")) {
            System.out.println("ferramenta-falsa 1.2.3 (para teste)");
            return;
        }
        if (args.length > 1 && args[0].equals("--neto")) {
            Files.writeString(Path.of(args[1]), Long.toString(ProcessHandle.current().pid()), StandardCharsets.UTF_8);
            Thread.sleep(Long.MAX_VALUE);
            return;
        }
        String saidaLogo = System.getenv("LABTEST_SAIR_LOGO_TEXTO");
        if (saidaLogo != null) {
            System.out.println(saidaLogo);
            System.exit(255);
        }
        String arquivoDoPath = System.getenv("LABTEST_PATH_ARQUIVO");
        if (arquivoDoPath != null) {
            Files.writeString(Path.of(arquivoDoPath), String.valueOf(System.getenv("LABTEST_PATH_PEDIDO")), StandardCharsets.UTF_8);
        }
        if (System.getenv("LABTEST_NAO_ESCUTAR") != null) {
            Thread.sleep(Long.MAX_VALUE); // o túnel "demora": fica de pé e nunca escuta
        }
        int local = portaLocal(args);
        int alvo = Integer.parseInt(System.getenv("LABTEST_ALVO"));
        String neto = System.getenv("LABTEST_NETO_ARQUIVO");
        if (neto != null) {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), FerramentaFalsaDeTunel.class.getName(), "--neto", neto).inheritIO().start();
        }
        ServerSocket escuta = new ServerSocket(local, 50, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
        System.out.println("Port " + local + " opened for sessionId teste.\nWaiting for connections...");
        System.out.flush();
        String morrer = System.getenv("LABTEST_MORRER_APOS_MS");
        if (morrer != null) {
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(Long.parseLong(morrer));
                } catch (InterruptedException e) {
                    return;
                }
                System.out.println("An error occurred (ExpiredToken) when calling the StartSession operation");
                System.out.flush();
                System.exit(7);
            });
            t.setDaemon(true);
            t.start();
        }
        while (true) {
            Socket cliente = escuta.accept();
            Thread t = new Thread(() -> repassar(cliente, alvo));
            t.setDaemon(true);
            t.start();
        }
    }

    private static int portaLocal(String[] args) {
        for (String a : args) {
            Matcher m = Pattern.compile("(?:localPortNumber=|127\\.0\\.0\\.1:)(\\d+)").matcher(a);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        }
        throw new IllegalArgumentException("sem porta local nos argumentos");
    }

    private static void repassar(Socket cliente, int alvo) {
        try (Socket servidor = new Socket(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), alvo); cliente) {
            Thread volta = new Thread(() -> copiar(servidor, cliente));
            volta.setDaemon(true);
            volta.start();
            copiar(cliente, servidor);
        } catch (IOException e) {
            // a conexão acabou
        }
    }

    private static void copiar(Socket de, Socket para) {
        try {
            InputStream in = de.getInputStream();
            OutputStream out = para.getOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
                out.flush();
            }
        } catch (IOException e) {
            // fechou
        } finally {
            try {
                para.shutdownOutput();
            } catch (IOException ignorado) {
                // já fechado
            }
        }
    }
}
