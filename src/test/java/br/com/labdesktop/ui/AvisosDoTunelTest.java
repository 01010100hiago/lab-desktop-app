package br.com.labdesktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.cofre.Estado2fa;
import br.com.labdesktop.cofre.Transporte;
import br.com.labdesktop.servidor.EstadoServidor;
import br.com.labdesktop.servidor.SituacaoDoAgente;
import br.com.labdesktop.ssh.EstadoConexao;
import br.com.labdesktop.ssh.Falha;
import br.com.labdesktop.transporte.FalhaDoTunel;
import br.com.labdesktop.ui.ConteudoDosAvisos.Acao;
import br.com.labdesktop.ui.ConteudoDosAvisos.AvisoDeConexao;
import br.com.labdesktop.ui.componentes.CopiadorDeTexto;
import br.com.labdesktop.ui.telas.SituacaoDaVisao;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * O que a faixa, a Visão geral e o cartão dizem quando o TÚNEL do SSM ou da Cloudflare não sobe: a causa em palavras simples, o que se copia (o passo de instalar, o comando
 * de login da AWS, o endereço de login da Cloudflare), em grafite, sem âmbar, e nada que abra ou execute sozinho.
 */
@ExtendWith(VerificaEdt.class)
class AvisosDoTunelTest {

    private static final Instant AGORA = Instant.parse("2026-10-29T15:00:00Z");
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final Transporte SSM = Transporte.ssm("i-0123456789abcdef0", "us-east-1", "meu-perfil", 22);

    private static EstadoServidor estado(Falha falha) {
        return new EstadoServidor("s1", "nuvem", EstadoConexao.RECONECTANDO, falha, 2, AGORA.plusSeconds(8), AGORA.minusSeconds(60), 1, Estado2fa.DESLIGADO, "mac", null, null,
            SituacaoDoAgente.DESCONHECIDA, false, false, SSM);
    }

    private static Falha de(FalhaDoTunel f) {
        return Falha.doTunel(f);
    }

    @Test
    void ferramentaAusenteDizOQueFaltaOPassoENadaDeAmbar() {
        FalhaDoTunel f = br.com.labdesktop.transporte.GuiaDeInstalacao.ausente(br.com.labdesktop.transporte.FerramentaExterna.AWS,
            br.com.labdesktop.plataforma.SistemaOperacional.MAC);
        AvisoDeConexao a = ConteudoDosAvisos.deConexao(estado(de(f)), AGORA, FUSO);
        assertEquals("Falta um programa neste computador para conectar em nuvem", a.titulo());
        assertTrue(a.texto().contains("brew install awscli"), a.texto());
        assertTrue(a.texto().contains("O app tenta de novo sozinho (tentativa 2, próxima em 8 s)."), a.texto());
        assertEquals("brew install awscli", a.textoCopiavel());
        assertEquals(Gravidade.NEUTRO, a.gravidade(), "grafite: nada de âmbar");
        assertEquals(List.of(Acao.TENTAR_AGORA), a.acoes());
    }

    @Test
    void loginDaAwsExpiradoDizAFraseExataEOComandoCopiavel() {
        FalhaDoTunel f = new FalhaDoTunel(FalhaDoTunel.Tipo.LOGIN_AWS_EXPIRADO, "Seu login da AWS expirou. No terminal rode: aws sso login --profile meu-perfil e tente de novo.",
            "aws sso login --profile meu-perfil");
        AvisoDeConexao a = ConteudoDosAvisos.deConexao(estado(de(f)), AGORA, FUSO);
        assertEquals("Não consigo entrar em nuvem: falta o login da AWS", a.titulo());
        assertTrue(a.texto().startsWith("Seu login da AWS expirou. No terminal rode: aws sso login --profile meu-perfil e tente de novo."), a.texto());
        assertEquals("aws sso login --profile meu-perfil", a.textoCopiavel());
    }

    @Test
    void instanciaSemSsmELoginDaCloudflareTambem() {
        AvisoDeConexao semSsm = ConteudoDosAvisos.deConexao(estado(de(new FalhaDoTunel(FalhaDoTunel.Tipo.INSTANCIA_SEM_SSM, "A instância não está conectada ao Session Manager (TargetNotConnected)."))), AGORA, FUSO);
        assertEquals("O Session Manager não alcança nuvem", semSsm.titulo());
        assertNull(semSsm.textoCopiavel());
        AvisoDeConexao login = ConteudoDosAvisos.deConexao(estado(de(new FalhaDoTunel(FalhaDoTunel.Tipo.CLOUDFLARE_PEDE_LOGIN, "A Cloudflare pede o seu login no navegador.",
            "https://dash.cloudflare.com/argotunnel?aud=x"))), AGORA, FUSO);
        assertEquals("Falta entrar na Cloudflare para chegar a nuvem", login.titulo());
        assertEquals("https://dash.cloudflare.com/argotunnel?aud=x", login.textoCopiavel());
        assertFalse(login.texto().contains("https://"), "o endereço só vai no texto copiável");
    }

    @Test
    void umaFalhaDeRedeComumNaoViraAvisoDoTunel() {
        AvisoDeConexao a = ConteudoDosAvisos.deConexao(estado(new Falha(Falha.Tipo.REDE, "sem rota")), AGORA, FUSO);
        assertTrue(a.titulo().startsWith("Sem conexão com nuvem"), a.titulo());
        assertNull(a.textoCopiavel());
    }

    @Test
    void aVisaoGeralTrazOTextoCopiavelEACausa() {
        FalhaDoTunel f = new FalhaDoTunel(FalhaDoTunel.Tipo.LOGIN_AWS_EXPIRADO, "Seu login da AWS expirou. No terminal rode: aws sso login e tente de novo.", "aws sso login");
        SituacaoDaVisao s = SituacaoDaVisao.de(estado(de(f)), false, null, AGORA, FUSO);
        assertEquals(SituacaoDaVisao.Modo.SEM_CONEXAO_SEM_DADOS, s.modo());
        assertEquals("aws sso login", s.textoCopiavel());
        assertTrue(s.texto().startsWith("Seu login da AWS expirou"), s.texto());
        assertEquals(br.com.labdesktop.ui.Gravidade.NEUTRO, s.gravidade(), "grafite, sem âmbar");
        SituacaoDaVisao comDados = SituacaoDaVisao.de(estado(de(f)), true, AGORA.minusSeconds(120), AGORA, FUSO);
        assertEquals("aws sso login", comDados.textoCopiavel());
        // sem o túnel no meio, nada para copiar
        SituacaoDaVisao deRede = SituacaoDaVisao.de(estado(new Falha(Falha.Tipo.REDE, "x")), false, null, AGORA, FUSO);
        assertNull(deRede.textoCopiavel());
        assertEquals(br.com.labdesktop.ui.Gravidade.ATENCAO, deRede.gravidade(), "a falha de rede comum continua como era");
    }

    @Test
    void aFaixaDeAvisoTemOBotaoCopiarQueLevaOTextoParaAAreaDeTransferenciaESoIsso() throws Exception {
        Clipboard area = new Clipboard("teste");
        FaixaAviso faixa = Visual.naEdt(() -> new FaixaAviso(new FaixaAviso.Ouvinte() {
            @Override
            public void aoAcionar(Acao acao) { }

            @Override
            public void aoRetomar(String projeto) { }
        }));
        Visual.naEdt(() -> faixa.usarCopiador(new CopiadorDeTexto(() -> area)));
        FalhaDoTunel f = new FalhaDoTunel(FalhaDoTunel.Tipo.LOGIN_AWS_EXPIRADO, "Seu login da AWS expirou. No terminal rode: aws sso login e tente de novo.", "aws sso login");
        Visual.naEdt(() -> faixa.atualizar(estado(de(f))));
        javax.swing.AbstractButton copiar = Visual.naEdt(() -> achar(faixa, "copiar-do-aviso"));
        assertNotNull(copiar, "o botão Copiar está na faixa");
        Visual.naEdt(() -> copiar.doClick());
        assertEquals("aws sso login", area.getContents(null).getTransferData(DataFlavor.stringFlavor));

        Visual.naEdt(() -> faixa.atualizar(estado(new Falha(Falha.Tipo.REDE, "sem rota"))));
        assertNull(Visual.naEdt(() -> achar(faixa, "copiar-do-aviso")), "sem texto a copiar, sem botão");
    }

    private static javax.swing.AbstractButton achar(java.awt.Container raiz, String nome) {
        for (java.awt.Component c : raiz.getComponents()) {
            if (nome.equals(c.getName()) && c instanceof javax.swing.AbstractButton b) {
                return b;
            }
            if (c instanceof java.awt.Container k) {
                javax.swing.AbstractButton r = achar(k, nome);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }
}
