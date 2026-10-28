package br.com.labdesktop.cloudflare;

import br.com.labdesktop.Versao;
import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.util.Fundo;
import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.function.Function;
import javax.net.ssl.SSLException;

/**
 * "Está no ar?": depois de publicar, abre o endereço e diz se o app respondeu, ou o PORQUÊ de não ter respondido (o DNS novo pode levar alguns minutos, o
 * certificado também, o túnel pode não achar o container). Tem prazo total e nunca prende: tenta de novo a cada poucos segundos até o prazo e devolve a última
 * frase. É só uma LEITURA do endereço público (um GET); não muda nada.
 */
public interface TestadorDeUrl {

    /** Como terminou. */
    enum Estado {
        /** O app respondeu (qualquer resposta que não seja erro do túnel). */
        NO_AR,
        /** Respondeu, mas pode não ser o app (um 404 sem conteúdo é também o que o túnel devolve quando a regra não chega): o usuário confere. */
        EM_DUVIDA,
        /** Não respondeu, ou o túnel não chegou no container; a frase diz por quê. */
        FORA_DO_AR,
        /** O usuário cancelou o teste. */
        CANCELADO
    }

    /** O resultado, com a frase para o usuário. */
    record Resultado(Estado estado, int status, String frase) {
        public boolean noAr() {
            return estado == Estado.NO_AR;
        }
    }

    /** Testa o endereço (só o nome do host; o teste monta {@code https://<host>/}). Bloqueia até {@code prazo}: thread de fundo. */
    Resultado testar(String hostname, Cancelamento cancelamento);

    /** O padrão: GET em {@code https://<host>/}, de tempos em tempos, até o prazo. */
    final class Https implements TestadorDeUrl {
        /** Quem espera entre uma tentativa e outra. */
        @FunctionalInterface
        public interface Esperador {
            void esperar(Duration d) throws InterruptedException;
        }

        private final Function<String, URI> endereco;
        private final Duration prazoTotal;
        private final Duration intervalo;
        private final Duration prazoDeCadaTentativa;
        private final Esperador esperador;

        public Https() {
            this(h -> URI.create("https://" + h + "/"), Duration.ofSeconds(60), Duration.ofSeconds(4), Duration.ofSeconds(8), d -> Thread.sleep(d));
        }

        /** Para os testes: o endereço vem de uma função (um servidor falso em loopback) e os tempos são curtos. */
        public Https(Function<String, URI> endereco, Duration prazoTotal, Duration intervalo, Duration prazoDeCadaTentativa, Esperador esperador) {
            this.endereco = endereco;
            this.prazoTotal = prazoTotal;
            this.intervalo = intervalo;
            this.prazoDeCadaTentativa = prazoDeCadaTentativa;
            this.esperador = esperador;
        }

        @Override
        public Resultado testar(String hostname, Cancelamento cancelamento) {
            if (cancelamento == null) {
                cancelamento = Cancelamento.NUNCA;
            }
            long limite = System.nanoTime() + prazoTotal.toNanos();
            Resultado ultimo = new Resultado(Estado.FORA_DO_AR, 0, "Ainda não consegui abrir o endereço.");
            HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
                .proxy(ProxySelector.getDefault()).connectTimeout(prazoDeCadaTentativa).executor(Fundo.ioVirtual()).build();
            try {
                while (true) {
                    if (cancelamento.cancelado()) {
                        return new Resultado(Estado.CANCELADO, 0, "Teste cancelado.");
                    }
                    ultimo = umaTentativa(http, hostname);
                    if (ultimo.estado() == Estado.NO_AR || ultimo.estado() == Estado.EM_DUVIDA) {
                        return ultimo;
                    }
                    if (System.nanoTime() + intervalo.toNanos() > limite) {
                        return ultimo;
                    }
                    try {
                        esperador.esperar(intervalo);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return new Resultado(Estado.CANCELADO, 0, "Teste cancelado.");
                    }
                }
            } finally {
                http.shutdownNow();
            }
        }

        private Resultado umaTentativa(HttpClient http, String hostname) {
            HttpRequest req = HttpRequest.newBuilder(endereco.apply(hostname)).timeout(prazoDeCadaTentativa).header("User-Agent", Versao.userAgent())
                .header("Accept", "*/*").GET().build();
            try {
                HttpResponse<byte[]> r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                int s = r.statusCode();
                if (s == 530 || s == 1033) {
                    return new Resultado(Estado.FORA_DO_AR, s, "A Cloudflare não achou o túnel conectado (erro " + s + "). Confira se o cloudflared está de pé e é o túnel certo.");
                }
                if (s == 502 || s == 503 || s == 504) {
                    return new Resultado(Estado.FORA_DO_AR, s, "A Cloudflare chegou no túnel, mas o seu container não respondeu (erro " + s + "). Confira o nome do container, a porta e se o projeto "
                        + "está de pé e na rede edge.");
                }
                if (s >= 500) {
                    return new Resultado(Estado.FORA_DO_AR, s, "O endereço respondeu com erro " + s + ".");
                }
                if (s == 404 && r.body().length == 0 && r.headers().firstValue("Server").orElse("").equalsIgnoreCase("cloudflare") && r.headers().firstValue("Content-Type").isEmpty()) {
                    return new Resultado(Estado.EM_DUVIDA, s, "Respondeu 404 sem conteúdo: pode ser a regra final do túnel (a regra nova ainda não chegou) ou a página inicial do seu app dar 404. "
                        + "Abra o endereço no navegador para conferir.");
                }
                return new Resultado(Estado.NO_AR, s, "No ar: o endereço respondeu (código " + s + ").");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Resultado(Estado.CANCELADO, 0, "Teste cancelado.");
            } catch (IOException e) {
                return resultadoDe(e);
            }
        }

        /** A frase de uma falha ao abrir o endereço (o DNS novo, o certificado novo, o prazo, a conexão). */
        static Resultado resultadoDe(IOException e) {
            String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
            boolean semDns = e instanceof UnknownHostException || e.getCause() instanceof UnknownHostException || m.contains("unresolved") || m.contains("nodename")
                || m.contains("name or service not known");
            if (e instanceof HttpTimeoutException) {
                return new Resultado(Estado.FORA_DO_AR, 0, "O endereço não respondeu a tempo.");
            }
            if (semDns) {
                return new Resultado(Estado.FORA_DO_AR, 0, "O endereço ainda não existe na internet: o DNS novo pode levar alguns minutos para chegar.");
            }
            if (e instanceof SSLException) {
                return new Resultado(Estado.FORA_DO_AR, 0, "O certificado do endereço ainda não está pronto (a Cloudflare emite sozinha; pode levar alguns minutos).");
            }
            return new Resultado(Estado.FORA_DO_AR, 0, "Não consegui abrir o endereço (sem conexão com a internet, ou o endereço ainda não chega).");
        }
    }
}
