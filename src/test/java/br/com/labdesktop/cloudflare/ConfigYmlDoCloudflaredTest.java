package br.com.labdesktop.cloudflare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.cloudflare.ConfigYmlDoCloudflared.Problema;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A edição do config.yml do cloudflared como texto: a regra entra ANTES da regra final, e o resto do arquivo fica como estava (valores conhecidos). */
class ConfigYmlDoCloudflaredTest {

    private static final String UUID = "11111111-2222-3333-4444-555555555555";

    private static final String DO_DONO = "tunnel: " + UUID + "\n"
        + "credentials-file: /etc/cloudflared/" + UUID + ".json\n"
        + "\n"
        + "ingress:\n"
        + "  - hostname: meu-painel.example.com\n"
        + "    service: http://app:3000\n"
        + "  # tudo o mais\n"
        + "  - service: http_status:404\n";

    private static ConfigYmlDoCloudflared.Edicao com(String texto) throws Problema {
        return ConfigYmlDoCloudflared.acrescentar(texto, "lab-demo.example.com", "http://demo-web:3000", "projeto demo");
    }

    @Test
    void aRegraEntraAntesDaRegraFinalComAMarcaEOComentarioDaFinalFicaComElaValorConhecido() throws Problema {
        ConfigYmlDoCloudflared.Edicao e = com(DO_DONO);
        assertEquals("tunnel: " + UUID + "\n"
            + "credentials-file: /etc/cloudflared/" + UUID + ".json\n"
            + "\n"
            + "ingress:\n"
            + "  - hostname: meu-painel.example.com\n"
            + "    service: http://app:3000\n"
            + "  # lab-desktop: lab-demo.example.com (projeto demo)\n"
            + "  - hostname: lab-demo.example.com\n"
            + "    service: http://demo-web:3000\n"
            + "  # tudo o mais\n"
            + "  - service: http_status:404\n", e.texto());
        assertFalse(e.jaEstava());
        assertEquals(List.of(), e.avisos());
    }

    @Test
    void oRestoDoArquivoNaoMudaUmByte() throws Problema {
        String novo = com(DO_DONO).texto();
        // só linhas a mais: tirando as três novas, volta exatamente ao que era
        String semAsNovas = novo.replace("  # lab-desktop: lab-demo.example.com (projeto demo)\n  - hostname: lab-demo.example.com\n    service: http://demo-web:3000\n", "");
        assertEquals(DO_DONO, semAsNovas);
    }

    @Test
    void analisaAsRegrasOTunelEARegraFinal() throws Problema {
        ConfigYmlDoCloudflared.Analise a = ConfigYmlDoCloudflared.analisar(DO_DONO);
        assertTrue(a.temIngress());
        assertTrue(a.temRegraFinal());
        assertEquals(UUID, a.tunel());
        assertFalse(a.fimDeLinhaCrlf());
        assertEquals(2, a.regras().size());
        assertEquals("meu-painel.example.com", a.regras().get(0).hostname());
        assertEquals("http://app:3000", a.regras().get(0).servico());
        assertFalse(a.regras().get(0).marcadaPeloApp(), "a regra do usuário não leva a marca");
        assertTrue(a.regras().get(1).ehFinal());
        assertEquals("http_status:404", a.regras().get(1).servico());
    }

    @Test
    void arquivoSemARegraFinalGanhaUmaDepoisDaNovaComAviso() throws Problema {
        String sem = "tunnel: " + UUID + "\ningress:\n  - hostname: a.example.com\n    service: http://a:80\n";
        ConfigYmlDoCloudflared.Edicao e = com(sem);
        assertEquals("tunnel: " + UUID + "\ningress:\n  - hostname: a.example.com\n    service: http://a:80\n"
            + "  # lab-desktop: lab-demo.example.com (projeto demo)\n  - hostname: lab-demo.example.com\n    service: http://demo-web:3000\n  - service: http_status:404\n", e.texto());
        assertEquals(1, e.avisos().size());
        assertTrue(e.avisos().get(0).contains("não tinha a regra final"), e.avisos().get(0));
        assertFalse(ConfigYmlDoCloudflared.analisar(sem).temRegraFinal());
        assertTrue(ConfigYmlDoCloudflared.analisar(e.texto()).temRegraFinal());
    }

    @Test
    void ingressSemNenhumaRegraGanhaAregraEAFinal() throws Problema {
        ConfigYmlDoCloudflared.Edicao e = com("tunnel: " + UUID + "\ningress:\n");
        assertEquals("tunnel: " + UUID + "\ningress:\n  # lab-desktop: lab-demo.example.com (projeto demo)\n  - hostname: lab-demo.example.com\n    service: http://demo-web:3000\n  - service: http_status:404\n",
            e.texto());
        assertEquals(1, e.avisos().size());
    }

    @Test
    void arquivoVazioOuSoDeComentariosGanhaASecaoIngressComAviso() throws Problema {
        for (String vazio : List.of("", "\n", "# nada ainda\n")) {
            ConfigYmlDoCloudflared.Edicao e = com(vazio);
            assertTrue(e.texto().startsWith(vazio) && e.texto().endsWith("ingress:\n  # lab-desktop: lab-demo.example.com (projeto demo)\n  - hostname: lab-demo.example.com\n"
                + "    service: http://demo-web:3000\n  - service: http_status:404\n"), "[" + vazio + "] -> " + e.texto());
            assertTrue(e.avisos().get(0).contains("estava vazio"), e.avisos().toString());
            assertFalse(ConfigYmlDoCloudflared.analisar(vazio).temIngress());
        }
    }

    @Test
    void fimDeLinhaDoWindowsEMantidoEmTodasAsLinhasNovas() throws Problema {
        String crlf = DO_DONO.replace("\n", "\r\n");
        ConfigYmlDoCloudflared.Edicao e = com(crlf);
        assertEquals(com(DO_DONO).texto().replace("\n", "\r\n"), e.texto());
        assertTrue(ConfigYmlDoCloudflared.analisar(crlf).fimDeLinhaCrlf());
        assertFalse(e.texto().replace("\r\n", "").contains("\n"), "nenhum \\n solto");
        assertFalse(e.texto().replace("\r\n", "").contains("\r"));
    }

    @Test
    void fimDeLinhaMisturadoETabulacaoEIngressDuasVezesNaoSaoEditados() {
        Problema mistura = assertThrows(Problema.class, () -> com("ingress:\r\n  - service: http_status:404\n"));
        assertEquals(Problema.Tipo.FORMATO_NAO_SUPORTADO, mistura.tipo());
        assertTrue(mistura.getMessage().contains("mistura fins de linha"));
        assertThrows(Problema.class, () -> com("ingress:\n\t- service: http_status:404\n"));
        assertThrows(Problema.class, () -> com("ingress:\n  - service: a\ningress:\n  - service: b\n"));
        assertThrows(Problema.class, () -> com("ingress: [{hostname: a, service: b}]\n"));
        assertThrows(Problema.class, () -> com("ingress:\n  - {hostname: a, service: http://a:1}\n  - service: http_status:404\n"));
    }

    @Test
    void formatoAntigoComUrlSemIngressNaoEReescritoEADicaFalaDoFormato() {
        Problema p = assertThrows(Problema.class, () -> com("tunnel: " + UUID + "\nurl: http://localhost:8080\n"));
        assertEquals(Problema.Tipo.FORMATO_NAO_SUPORTADO, p.tipo());
        assertTrue(p.getMessage().contains("formato antigo, com url:"), p.getMessage());
    }

    @Test
    void recuoDiferenteDeDoisEspacosEMantidoEComentariosEntreAsRegrasFicamOndeEstao() throws Problema {
        String quatro = "ingress:\n    # as do lab\n    - hostname: a.example.com\n      service: http://a:1\n\n    - service: http_status:404\n";
        ConfigYmlDoCloudflared.Edicao e = com(quatro);
        assertEquals("ingress:\n    # as do lab\n    - hostname: a.example.com\n      service: http://a:1\n\n"
            + "    # lab-desktop: lab-demo.example.com (projeto demo)\n    - hostname: lab-demo.example.com\n      service: http://demo-web:3000\n    - service: http_status:404\n", e.texto());
    }

    @Test
    void regraComChavesEmOutraOrdemEOriginRequestSaoLidasDireito() throws Problema {
        String t = "ingress:\n  - service: http://b:2\n    hostname: B.example.com\n    originRequest:\n      noTLSVerify: true\n      hostname: ignorado\n  - service: http_status:404\n";
        ConfigYmlDoCloudflared.Analise a = ConfigYmlDoCloudflared.analisar(t);
        assertEquals("b.example.com", a.regras().get(0).hostname(), "minúsculas, e o hostname do originRequest é mais fundo: não vale");
        assertEquals("http://b:2", a.regras().get(0).servico());
        assertEquals(5, a.regras().get(0).fim() - a.regras().get(0).inicio());
    }

    @Test
    void regraQueJaExisteNaoEDuplicadaSeEDoAppEIgualEERecusadaSeNao() throws Problema {
        String uma = com(DO_DONO).texto();
        ConfigYmlDoCloudflared.Edicao outra = com(uma);
        assertTrue(outra.jaEstava(), "mesma regra, com a marca do app: não faz nada");
        assertEquals(uma, outra.texto());
        Problema doDono = assertThrows(Problema.class, () -> ConfigYmlDoCloudflared.acrescentar(DO_DONO, "meu-painel.example.com", "http://app:3000", "x"));
        assertEquals(Problema.Tipo.REGRA_JA_EXISTE, doDono.tipo());
        assertTrue(doDono.getMessage().contains("não foi criada por este app"), doDono.getMessage());
        Problema outroServico = assertThrows(Problema.class, () -> ConfigYmlDoCloudflared.acrescentar(uma, "lab-demo.example.com", "http://outro:9", "x"));
        assertEquals(Problema.Tipo.REGRA_JA_EXISTE, outroServico.tipo());
        assertTrue(outroServico.getMessage().contains("http://demo-web:3000"));
    }

    @Test
    void removerTiraSoARegraComAMarcaDoAppDeixandoOResto() throws Problema {
        String uma = com(DO_DONO).texto();
        assertEquals(DO_DONO, ConfigYmlDoCloudflared.remover(uma, "lab-demo.example.com"));
        assertEquals(DO_DONO.replace("\n", "\r\n"), ConfigYmlDoCloudflared.remover(uma.replace("\n", "\r\n"), "LAB-demo.example.com"));
    }

    @Test
    void removerNuncaTiraRegraDoDonoNemAFinalNemAQueNaoExiste() {
        Problema doDono = assertThrows(Problema.class, () -> ConfigYmlDoCloudflared.remover(DO_DONO, "meu-painel.example.com"));
        assertEquals(Problema.Tipo.NAO_E_DO_APP, doDono.tipo());
        Problema naoAchou = assertThrows(Problema.class, () -> ConfigYmlDoCloudflared.remover(DO_DONO, "nao-existe.example.com"));
        assertEquals(Problema.Tipo.NAO_ENCONTRADA, naoAchou.tipo());
        assertThrows(Problema.class, () -> ConfigYmlDoCloudflared.remover(DO_DONO, ""), "a regra final não tem hostname: nunca casa");
        assertThrows(Problema.class, () -> ConfigYmlDoCloudflared.remover("tunnel: x\n", "a.example.com"));
    }

    @Test
    void aMarcaDeOutroHostnameNaoDaPosseDaRegra() throws Problema {
        String t = "ingress:\n  # lab-desktop: outro.example.com (x)\n  - hostname: a.example.com\n    service: http://a:1\n  - service: http_status:404\n";
        assertFalse(ConfigYmlDoCloudflared.analisar(t).regras().get(0).marcadaPeloApp(), "a marca tem de ser do MESMO hostname");
        assertThrows(Problema.class, () -> ConfigYmlDoCloudflared.remover(t, "a.example.com"));
    }

    @Test
    void arquivoSemQuebraDeLinhaNoFimContinuaSem() throws Problema {
        String sem = "ingress:\n  - hostname: a.example.com\n    service: http://a:1\n  - service: http_status:404";
        ConfigYmlDoCloudflared.Edicao e = com(sem);
        assertTrue(e.texto().endsWith("  - service: http_status:404"), e.texto());
        assertEquals(sem, ConfigYmlDoCloudflared.remover(e.texto(), "lab-demo.example.com"));
    }

    @Test
    void duasRegrasDoAppSeguidasEntramNaOrdemEVoltamUmaDeCadaVez() throws Problema {
        String um = com(DO_DONO).texto();
        String dois = ConfigYmlDoCloudflared.acrescentar(um, "lab-outro.example.com", "http://outro-web:8080", "projeto outro").texto();
        List<String> hosts = ConfigYmlDoCloudflared.analisar(dois).regras().stream().map(ConfigYmlDoCloudflared.Regra::hostname).toList();
        assertEquals(List.of("meu-painel.example.com", "lab-demo.example.com", "lab-outro.example.com", ""), hosts);
        assertEquals(um, ConfigYmlDoCloudflared.remover(dois, "lab-outro.example.com"));
    }
}
