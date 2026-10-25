package br.com.labdesktop.ui.telas;

import static org.junit.jupiter.api.Assertions.assertEquals;

import br.com.labdesktop.api.modelo.Container;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Os textos da página do projeto em palavras simples. */
class FormatosDoProjetoTest {

    private static Container com(String estado, String portas) {
        return new Container("x-1", estado, "Up", portas, "img", null, null, null, null, null, null, null);
    }

    @Test
    void estadoDoContainerEmPortugues() {
        assertEquals("rodando", FormatosDoProjeto.estadoDoContainer(com("running", "")));
        assertEquals("parado", FormatosDoProjeto.estadoDoContainer(com("exited", "")));
        assertEquals("parado", FormatosDoProjeto.estadoDoContainer(com("dead", "")));
        assertEquals("em pausa", FormatosDoProjeto.estadoDoContainer(com("paused", "")));
        assertEquals("reiniciando", FormatosDoProjeto.estadoDoContainer(com("restarting", "")));
        assertEquals("criado", FormatosDoProjeto.estadoDoContainer(com("created", "")));
        assertEquals("novidade", FormatosDoProjeto.estadoDoContainer(com("novidade", "")), "estado desconhecido volta como veio");
        assertEquals("—", FormatosDoProjeto.estadoDoContainer(com(null, "")));
    }

    @Test
    void portasSemOJargaoDoDocker() {
        assertEquals("5440 → 5432", FormatosDoProjeto.portas("127.0.0.1:5440->5432/tcp"));
        assertEquals("80", FormatosDoProjeto.portas("0.0.0.0:80->80/tcp, :::80->80/tcp"), "IPv4 e IPv6 repetem a mesma porta");
        assertEquals("3000, 9229", FormatosDoProjeto.portas("0.0.0.0:3000->3000/tcp, 0.0.0.0:9229->9229/tcp"),
            "porta igual dos dois lados vira só o número");
        assertEquals("1 → 10, 2 → 20 +1", FormatosDoProjeto.portas("127.0.0.1:1->10/tcp, 127.0.0.1:2->20/tcp, 127.0.0.1:3->30/tcp"),
            "no máximo duas, o resto vira +n");
    }

    @Test
    void portasSemPublicacaoOuForaDoFormato() {
        assertEquals("—", FormatosDoProjeto.portas(""));
        assertEquals("—", FormatosDoProjeto.portas(null));
        assertEquals("5432/tcp", FormatosDoProjeto.portas("5432/tcp"), "só a porta interna: volta como veio");
    }

    @Test
    void criadoTamanhoETipo() {
        Instant agora = Instant.parse("2026-10-24T21:00:00Z");
        assertEquals("há 3 d", FormatosDoProjeto.criado(Instant.parse("2026-10-21T21:00:00Z"), agora));
        assertEquals("—", FormatosDoProjeto.criado(null, agora));
        assertEquals("—", FormatosDoProjeto.tamanho(null));
        assertEquals("2 KB", FormatosDoProjeto.tamanho(2048L));
        assertEquals("Pasta", FormatosDoProjeto.tipoDeItem("pasta"));
        assertEquals("Arquivo", FormatosDoProjeto.tipoDeItem("arquivo"));
        assertEquals("link", FormatosDoProjeto.tipoDeItem("link"));
    }

    @Test
    void volumeEmUmaLinha() {
        assertEquals("135,1 mil de saída · 251 respostas", FormatosDoProjeto.volume(135098L, 251L));
        assertEquals("0 de saída · 1 resposta", FormatosDoProjeto.volume(null, 1L));
    }
}
