package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.Falha;
import br.com.labdesktop.ssh.InfoConexao;
import br.com.labdesktop.ui.ConteudoDosAvisos.Acao;
import br.com.labdesktop.ui.ConteudoDosAvisos.AvisoDeConexao;
import br.com.labdesktop.util.Preferencias.Tema;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A faixa de um servidor que NÃO VOLTA: depois de muitas falhas seguidas a conexão passa ao ritmo lento, e a tela diz "Sem conexão com X desde HH:MM. Tentando de novo a cada 5 min.", com a
 * causa em palavras simples e os botões Tentar agora, Editar servidor e Remover servidor. O número da tentativa não aparece (cresceria sem fim). Antes disso (ritmo normal) nada mudou.
 */
@ExtendWith(VerificaEdt.class)
class FaixaDoRitmoLentoTest {

    private static final Instant AGORA = Instant.parse("2026-10-29T15:00:00Z");
    private static final Instant DESDE = Instant.parse("2026-10-29T14:05:00Z");
    private static final ZoneId UTC = ZoneId.of("UTC");

    private static EstadoServidor estado(Falha falha, int tentativa, boolean lento, Instant proxima) {
        return new EstadoServidor("s1", "lab", EstadoConexao.RECONECTANDO, falha, tentativa, proxima, DESDE, 1, Estado2fa.DESLIGADO, "mac", null, null,
            br.com.labdesktop.servidor.SituacaoDoAgente.DESCONHECIDA, false, false, br.com.labdesktop.cofre.Transporte.DIRETO, lento);
    }

    @Test
    void oRetratoDaConexaoCarregaOEstadoLentoAteAFaixa() {
        InfoConexao info = new InfoConexao(EstadoConexao.RECONECTANDO, new Falha(Falha.Tipo.REDE, "Não consegui conectar: conexão recusada."), 14, AGORA.plusSeconds(280), DESDE, 0, null, true);
        assertTrue(info.ritmoLento());
        assertFalse(new InfoConexao(EstadoConexao.RECONECTANDO, null, 3, null, null, 0, null).ritmoLento(), "o construtor de antes é o ritmo normal");
    }

    @Test
    void servidorDesligadoDizDesdeQuandoACausaEOsTresBotoes() {
        AvisoDeConexao a = ConteudoDosAvisos.deConexao(estado(new Falha(Falha.Tipo.REDE, "Não consegui conectar: conexão recusada."), 31, true, AGORA.plusSeconds(200)), AGORA, UTC);
        assertEquals("Sem conexão com lab desde 14:05", a.titulo());
        assertTrue((a.titulo() + ". " + a.texto()).startsWith("Sem conexão com lab desde 14:05. Tentando de novo a cada 5 min"), "a frase do pedido, título e texto juntos: " + a.texto());
        assertTrue(a.texto().startsWith("Tentando de novo a cada 5 min (a próxima em cerca de 4 min)."), a.texto());
        assertTrue(a.texto().contains("Não consegui conectar: conexão recusada."), "a causa que o app já classifica: " + a.texto());
        assertTrue(a.texto().contains("desligado") && a.texto().contains("endereço ou a porta"), a.texto());
        assertTrue(a.texto().contains("Remover servidor") && a.texto().contains("Editar servidor"));
        assertEquals(List.of(Acao.TENTAR_AGORA, Acao.EDITAR_SERVIDOR, Acao.REMOVER_SERVIDOR), a.acoes());
        assertFalse((a.titulo() + a.texto()).contains("tentativa 31") || (a.titulo() + a.texto()).contains("(31)"), "o número da tentativa não cresce na tela: " + a.texto());
        assertEquals(Gravidade.ATENCAO, a.gravidade());
    }

    @Test
    void cadaCausaDoTunelAparecePelasPalavrasDelaNoRitmoLento() {
        for (Falha.Tipo tipo : List.of(Falha.Tipo.TRANSPORTE_LOGIN_AWS, Falha.Tipo.TRANSPORTE_FERRAMENTA_AUSENTE, Falha.Tipo.TRANSPORTE_INSTANCIA_SEM_SSM, Falha.Tipo.TRANSPORTE_LOGIN_CLOUDFLARE,
            Falha.Tipo.TRANSPORTE_OUTRO)) {
            String frase = switch (tipo) {
                case TRANSPORTE_LOGIN_AWS -> "O login da AWS expirou neste computador.";
                case TRANSPORTE_FERRAMENTA_AUSENTE -> "Falta o programa aws neste computador.";
                case TRANSPORTE_INSTANCIA_SEM_SSM -> "A instância não responde ao Session Manager.";
                case TRANSPORTE_LOGIN_CLOUDFLARE -> "A Cloudflare pede login no navegador.";
                default -> "O túnel não subiu a tempo.";
            };
            AvisoDeConexao a = ConteudoDosAvisos.deConexao(estado(new Falha(tipo, frase, "aws sso login"), 80, true, AGORA.plusSeconds(10)), AGORA, UTC);
            assertEquals("Sem conexão com lab desde 14:05", a.titulo(), tipo.name());
            assertTrue(a.texto().contains(frase), tipo + ": " + a.texto());
            assertEquals("aws sso login", a.textoCopiavel(), "o texto para copiar continua (o app nunca o executa)");
            assertEquals(List.of(Acao.TENTAR_AGORA, Acao.EDITAR_SERVIDOR, Acao.REMOVER_SERVIDOR), a.acoes());
            assertEquals(Gravidade.NEUTRO, a.gravidade(), "causa do túnel é do dia a dia: grafite");
            assertTrue(a.texto().contains("(a próxima em menos de 1 min)"), a.texto());
        }
        AvisoDeConexao tunelSemResposta = ConteudoDosAvisos.deConexao(estado(new Falha(Falha.Tipo.TRANSPORTE_OUTRO, "O túnel abriu, mas não consegui falar SSH por ele."), 20, true, AGORA.plusSeconds(100)), AGORA, UTC);
        assertTrue(tunelSemResposta.texto().contains("A instância pode estar parada ou apagada"), tunelSemResposta.texto());
    }

    @Test
    void foraDoRitmoLentoOTextoDeAntesContinuaComONumeroDaTentativa() {
        AvisoDeConexao a = ConteudoDosAvisos.deConexao(estado(new Falha(Falha.Tipo.REDE, "sem rota"), 3, false, AGORA.plusSeconds(8)), AGORA, UTC);
        assertEquals("Sem conexão com lab desde 14:05", a.titulo());
        assertTrue(a.texto().startsWith("Reconectando (tentativa 3, próxima em 8 s)."), a.texto());
        assertEquals(List.of(Acao.TENTAR_AGORA), a.acoes());
    }

    @Test
    void aBarraLateralDizSemConexaoEmVezDoNumero() {
        ApresentacaoDoEstado.Apresentacao lenta = ApresentacaoDoEstado.de(estado(new Falha(Falha.Tipo.REDE, "x"), 111, true, null));
        assertEquals("Sem conexão", lenta.texto());
        assertEquals("Reconectando (3)", ApresentacaoDoEstado.de(estado(new Falha(Falha.Tipo.REDE, "x"), 3, false, null)).texto());
        assertFalse(lenta.texto().matches(".*\\d.*"), "nenhum número que cresça sem fim: " + lenta.texto());
    }

    @Test
    void aFaixaTemOsBotoesQueChamamOOuvinteESaiEmPngClaroEEscuro() {
        List<Acao> acoes = new ArrayList<>();
        for (Tema tema : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            Visual.tema(tema);
            acoes.clear();
            FaixaAviso f = Visual.naEdt(() -> new FaixaAviso(new FaixaAviso.Ouvinte() {
                @Override
                public void aoAcionar(Acao acao) {
                    acoes.add(acao);
                }

                @Override
                public void aoRetomar(String projeto) { }
            }, () -> AGORA));
            Visual.naEdt(() -> f.atualizar(estado(new Falha(Falha.Tipo.TRANSPORTE_LOGIN_AWS, "O login da AWS expirou neste computador. Entre de novo na AWS e use Tentar agora.", "aws sso login --profile meu-perfil"),
                111, true, AGORA.plusSeconds(180))));
            assertTrue(Visual.naEdt(f::isVisible));
            for (Acao a : List.of(Acao.TENTAR_AGORA, Acao.EDITAR_SERVIDOR, Acao.REMOVER_SERVIDOR)) {
                assertNotNull(Visual.naEdt(() -> f.botaoDaAcao(a)), a.name());
            }
            assertEquals("Editar servidor", Visual.naEdt(() -> f.botaoDaAcao(Acao.EDITAR_SERVIDOR).getText()));
            assertTrue(acoes.isEmpty());
            Visual.naEdt(() -> f.botaoDaAcao(Acao.EDITAR_SERVIDOR).doClick());
            Visual.naEdt(() -> f.botaoDaAcao(Acao.REMOVER_SERVIDOR).doClick());
            Visual.naEdt(() -> f.botaoDaAcao(Acao.TENTAR_AGORA).doClick());
            assertEquals(List.of(Acao.EDITAR_SERVIDOR, Acao.REMOVER_SERVIDOR, Acao.TENTAR_AGORA), acoes, "os botões só avisam o ouvinte: quem abre o diálogo pede a confirmação");
            String texto = Visual.naEdt(() -> TextosDeTela.de(f));
            assertFalse(Pattern.compile("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}]").matcher(texto).find(), "sem emoji: " + texto);
            assertFalse(texto.contains("tentativa 111"), texto);
            int largura = 884;
            Visual.salvar(Visual.renderizar(f, largura, Visual.alturaPreferida(f, largura)), "faixa-ritmo-lento-" + tema.name().toLowerCase());

            // a mesma faixa para uma queda comum de rede (causa que o app classifica como rede)
            Visual.naEdt(() -> f.atualizar(estado(new Falha(Falha.Tipo.REDE, "Não consegui conectar: conexão recusada."), 111, true, AGORA.plusSeconds(180))));
            Visual.salvar(Visual.renderizar(f, largura, Visual.alturaPreferida(f, largura)), "faixa-ritmo-lento-rede-" + tema.name().toLowerCase());
        }
        Visual.tema(Tema.CLARO);
    }
}
