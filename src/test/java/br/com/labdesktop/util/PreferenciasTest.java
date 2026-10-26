package br.com.labdesktop.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.util.Preferencias.Aviso;
import br.com.labdesktop.util.Preferencias.Janela;
import br.com.labdesktop.util.Preferencias.Tema;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreferenciasTest {

    private static CaminhosApp caminhos(Path tmp) {
        return CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
    }

    @Test
    void padroes() {
        Preferencias p = Preferencias.padrao();
        assertEquals(Tema.SISTEMA, p.tema());
        assertNull(p.janela());
        assertEquals(13, p.tamanhoFonteTerminal());
        assertFalse(p.opcaoComoMeta());
        assertFalse(p.copiarAoSelecionar());
        assertEquals(EnumSet.allOf(Aviso.class), p.avisosAtivos());
        assertEquals(0, p.autoBloqueioMinutos());
    }

    @Test
    void semArquivoDevolvePadraoSemCriarNada(@TempDir Path tmp) {
        CaminhosApp c = caminhos(tmp);
        assertEquals(Preferencias.padrao(), Preferencias.carregar(c));
        assertFalse(Files.exists(c.pastaDados()), "ler não cria a pasta");
    }

    @Test
    void idaEVoltaComTudoMudado(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Preferencias p = Preferencias.padrao()
            .comTema(Tema.ESCURO)
            .comJanela(new Janela(120, 80, 1280, 800, true))
            .comTamanhoFonteTerminal(15)
            .comOpcaoComoMeta(true)
            .comCopiarAoSelecionar(true)
            .comAviso(Aviso.CREDITO_75, false)
            .comAutoBloqueioMinutos(30);
        p.salvar(c);

        Preferencias lida = Preferencias.carregar(c);
        assertEquals(p, lida);
        assertEquals(EnumSet.of(Aviso.PAUSA_NOVA, Aviso.CREDITO_90), lida.avisosAtivos());
        assertFalse(lida.avisoAtivo(Aviso.CREDITO_75));
    }

    @Test
    void arquivoFicaComZeroSeisZeroZeroEPastaComZeroSeteZeroZeroESemTemporario(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Preferencias.padrao().salvar(c);
        assertEquals("rw-------", Permissoes.comoTexto(c.arquivoPreferencias()));
        assertEquals("rwx------", Permissoes.comoTexto(c.pastaDados()));
        assertFalse(Files.exists(c.pastaDados().resolve("preferencias.json.tmp")));
    }

    @Test
    void salvarDeNovoSubstituiOConteudo(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Preferencias.padrao().comTema(Tema.CLARO).salvar(c);
        Preferencias.padrao().comTema(Tema.ESCURO).salvar(c);
        assertEquals(Tema.ESCURO, Preferencias.carregar(c).tema());
    }

    @Test
    void arquivoNaoTemNadaSecretoNemCaminhoDoDono(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Preferencias.padrao().comJanela(new Janela(0, 0, 1000, 700, false)).salvar(c);
        String texto = Files.readString(c.arquivoPreferencias());
        assertFalse(texto.contains("BEGIN"));
        assertFalse(texto.contains("lab_"));
        assertFalse(texto.contains(System.getProperty("user.home")));
    }

    @Test
    void jsonCorrompidoVoltaAosPadroes(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        c.prepararPastas();
        Files.writeString(c.arquivoPreferencias(), "{ isto nao e json", StandardCharsets.UTF_8);
        assertEquals(Preferencias.padrao(), Preferencias.carregar(c));
        Files.write(c.arquivoPreferencias(), new byte[] {0, 1, 2, (byte) 0xFF});
        assertEquals(Preferencias.padrao(), Preferencias.carregar(c));
        Files.writeString(c.arquivoPreferencias(), "[1,2,3]");
        assertEquals(Preferencias.padrao(), Preferencias.carregar(c));
        Files.writeString(c.arquivoPreferencias(), "null");
        assertEquals(Preferencias.padrao(), Preferencias.carregar(c));
    }

    @Test
    void arquivoGigantescoEhIgnoradoSemSerLido(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        c.prepararPastas();
        byte[] grande = new byte[(int) Preferencias.TAMANHO_MAXIMO + 1];
        java.util.Arrays.fill(grande, (byte) ' ');
        Files.write(c.arquivoPreferencias(), grande);
        assertEquals(Preferencias.padrao(), Preferencias.carregar(c));
    }

    @Test
    void cadaCampoRuimVoltaAoPadraoSemDerrubarOsOutros(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        c.prepararPastas();
        Files.writeString(c.arquivoPreferencias(), """
            { "versao": 7,
              "tema": "roxo",
              "janela": { "x": 5, "y": 6, "largura": 10, "altura": 10 },
              "fonteTerminal": 500,
              "opcaoComoMeta": true,
              "avisos": ["pausa_nova", "nao-existe", null, "CREDITO_90"],
              "autoBloqueioMinutos": -4,
              "campoDoFuturo": { "qualquer": "coisa" } }
            """);
        Preferencias p = Preferencias.carregar(c);
        assertEquals(Tema.SISTEMA, p.tema(), "tema desconhecido volta ao padrão");
        assertNull(p.janela(), "janela minúscula é descartada");
        assertEquals(Preferencias.FONTE_MAX, p.tamanhoFonteTerminal(), "fonte é limitada à faixa");
        assertTrue(p.opcaoComoMeta(), "o campo bom é mantido");
        assertFalse(p.copiarAoSelecionar(), "campo ausente = padrão");
        assertEquals(EnumSet.of(Aviso.PAUSA_NOVA, Aviso.CREDITO_90), p.avisosAtivos());
        assertEquals(0, p.autoBloqueioMinutos());
    }

    @Test
    void listaDeAvisosVaziaDesligaTodosEAusenteLigaTodos(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        c.prepararPastas();
        Files.writeString(c.arquivoPreferencias(), "{\"avisos\": []}");
        assertTrue(Preferencias.carregar(c).avisosAtivos().isEmpty());
        Files.writeString(c.arquivoPreferencias(), "{}");
        assertEquals(EnumSet.allOf(Aviso.class), Preferencias.carregar(c).avisosAtivos());
    }

    @Test
    void valoresSaoLimitadosNaConstrucao() {
        assertEquals(Preferencias.FONTE_MIN, Preferencias.padrao().comTamanhoFonteTerminal(1).tamanhoFonteTerminal());
        assertEquals(Preferencias.AUTO_BLOQUEIO_MAX_MINUTOS,
            Preferencias.padrao().comAutoBloqueioMinutos(999_999).autoBloqueioMinutos());
        assertEquals(Tema.SISTEMA, Preferencias.padrao().comTema(null).tema());
    }

    @Test
    void tamanhoDoTextoComecaEm100EViajaPeloArquivo(@TempDir Path tmp) throws Exception {
        assertEquals(100, Preferencias.padrao().escalaTexto());
        CaminhosApp c = caminhos(tmp);
        Preferencias.padrao().comEscalaTexto(130).salvar(c);
        assertEquals(130, Preferencias.carregar(c).escalaTexto());
        assertTrue(Files.readString(c.arquivoPreferencias()).contains("\"escalaTexto\" : 130"));
    }

    @Test
    void tamanhoDoTextoEstranhoVaiParaOFatorMaisPerto(@TempDir Path tmp) throws Exception {
        assertEquals(100, Preferencias.padrao().comEscalaTexto(5).escalaTexto());
        assertEquals(130, Preferencias.padrao().comEscalaTexto(999).escalaTexto());
        assertEquals(115, Preferencias.padrao().comEscalaTexto(112).escalaTexto());
        CaminhosApp c = caminhos(tmp);
        Files.createDirectories(c.pastaDados());
        Files.writeString(c.arquivoPreferencias(), "{\"escalaTexto\": 200}");
        assertEquals(130, Preferencias.carregar(c).escalaTexto(), "valor fora da escala nunca derruba a leitura");
        Files.writeString(c.arquivoPreferencias(), "{\"escalaTexto\": \"grande\"}");
        assertEquals(100, Preferencias.carregar(c).escalaTexto(), "texto no lugar do número volta ao padrão");
    }

    @Test
    void arquivoDeAntesDoTamanhoDoTextoContinuaValendo(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Files.createDirectories(c.pastaDados());
        Files.writeString(c.arquivoPreferencias(), "{\"versao\": 1, \"tema\": \"escuro\"}");
        Preferencias p = Preferencias.carregar(c);
        assertEquals(Tema.ESCURO, p.tema());
        assertEquals(100, p.escalaTexto());
    }

    @Test
    void mudarOutraPreferenciaNaoPerdeOTamanhoDoTexto() {
        Preferencias p = Preferencias.padrao().comEscalaTexto(115).comTema(Tema.ESCURO).comAutoBloqueioMinutos(5).comOpcaoComoMeta(true);
        assertEquals(115, p.escalaTexto());
        assertEquals(115, p.comJanela(new Janela(0, 0, 1000, 700, false)).escalaTexto());
    }

    @Test
    void osPadroesDoTerminalSaoOsDoDesenho() {
        Preferencias p = Preferencias.padrao();
        assertFalse(p.opcaoComoMeta(), "o ABNT2 gera caracteres com Option");
        assertFalse(p.copiarAoSelecionar(), "copiar sozinho sobrescreve a área de transferência");
        assertFalse(p.donoExclusivo(), "sem attach -d por padrão");
        assertEquals(13, p.tamanhoFonteTerminal());
    }

    @Test
    void donoExclusivoViajaPeloArquivoEArquivoAntigoContinuaValendo(@TempDir Path tmp) throws Exception {
        CaminhosApp c = caminhos(tmp);
        Preferencias.padrao().comDonoExclusivo(true).comOpcaoComoMeta(true).salvar(c);
        Preferencias lida = Preferencias.carregar(c);
        assertTrue(lida.donoExclusivo());
        assertTrue(lida.opcaoComoMeta());
        assertTrue(Files.readString(c.arquivoPreferencias()).contains("\"donoExclusivo\" : true"));

        Files.writeString(c.arquivoPreferencias(), "{\"versao\": 1, \"tema\": \"claro\", \"opcaoComoMeta\": true}");
        Preferencias antiga = Preferencias.carregar(c);
        assertFalse(antiga.donoExclusivo(), "arquivo de antes do campo: desligado");
        assertTrue(antiga.opcaoComoMeta());
        Files.writeString(c.arquivoPreferencias(), "{\"donoExclusivo\": \"sim\"}");
        assertEquals(Preferencias.padrao(), Preferencias.carregar(c), "valor que não é booleano nunca derruba a leitura");
    }

    @Test
    void mudarOutraPreferenciaNaoPerdeODonoExclusivo() {
        Preferencias p = Preferencias.padrao().comDonoExclusivo(true).comTema(Tema.ESCURO).comEscalaTexto(115).comAutoBloqueioMinutos(3)
            .comAviso(Aviso.CREDITO_75, false).comCopiarAoSelecionar(true).comTamanhoFonteTerminal(15)
            .comJanela(new Janela(0, 0, 1000, 700, false)).comOpcaoComoMeta(true);
        assertTrue(p.donoExclusivo());
        assertEquals(115, p.escalaTexto());
        assertTrue(p.copiarAoSelecionar());
    }
}
