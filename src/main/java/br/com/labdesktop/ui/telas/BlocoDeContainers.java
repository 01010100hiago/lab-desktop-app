package br.com.labdesktop.ui.telas;

import br.com.labdesktop.api.modelo.InfoServidor;
import br.com.labdesktop.api.modelo.InfoServidor.DockerInfo;
import br.com.labdesktop.api.modelo.InfoServidor.SessaoServidor;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.telas.TabelaDeLinhas.Coluna;
import br.com.labdesktop.ui.tema.Tokens;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "Containers e sessões do Claude": quantos containers rodam, estão parados ou em pausa, o espaço que o Docker ocupa e,
 * numa tabela, cada sessão do Claude aberta (projeto, container, desde quando, CPU e memória). Container parado é normal
 * (projeto desligado), por isso aqui é só informação, sem alarme.
 */
final class BlocoDeContainers extends CaixaDoServidor {

    private static final long serialVersionUID = 1L;

    private final Rotulo resumo = new Rotulo().negrito().tamanho(13f);
    private final Rotulo espaco = new Rotulo().corDoToken(Tokens.TEXTO2).tamanho(12f).truncavel();
    private final Rotulo apoio = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(12f);
    private final TabelaDeLinhas sessoes = new TabelaDeLinhas(new Coluna("Projeto", 1.0, false, false),
        new Coluna("Container", 1.4, false, true), new Coluna("Aberta desde", 1.3, false, false),
        new Coluna("CPU", 0.4, true, false), new Coluna("Memória", 0.5, true, false));

    BlocoDeContainers() {
        super("Containers e sessões do Claude", "docker");
        apoio(apoio);
        linha(resumo, 0);
        linha(espaco, 4);
        linha(new Rotulo("SESSÕES DO CLAUDE ABERTAS").corDoToken(Tokens.TEXTO3).negrito().tamanho(10.5f), 14);
        linha(sessoes, 6);
        atualizar(null, Instant.now(), ZoneId.systemDefault());
    }

    void atualizar(InfoServidor info, Instant agora, ZoneId fuso) {
        DockerInfo d = info == null ? null : info.docker();
        if (d == null || d.rodando() == null) {
            resumo.setText(FormatosDoServidor.SEM_DADO);
            apoio.setText("");
            espaco.setText("O Docker não informou os containers.");
        } else {
            int parados = d.parados() == null ? 0 : d.parados();
            int pausados = d.pausados() == null ? 0 : d.pausados();
            resumo.setText(d.rodando() + " rodando · " + parados + (parados == 1 ? " parado" : " parados") + " · " + pausados + " em pausa");
            apoio.setText(d.containers() == null ? "" : "no total, " + d.containers() + " containers");
            espaco.setText(textoDoEspaco(d));
        }
        List<List<String>> linhas = new ArrayList<>();
        List<SessaoServidor> abertas = info == null ? List.of() : info.sessoes();
        for (SessaoServidor s : abertas) {
            linhas.add(List.of(s.projeto() == null ? FormatosDoServidor.SEM_DADO : s.projeto(),
                s.container() == null ? FormatosDoServidor.SEM_DADO : s.container(),
                FormatosDoServidor.desde(s.desde(), agora, fuso), FormatosDoServidor.percentual(s.cpu()),
                FormatosDoServidor.bytes(s.mem())));
        }
        sessoes.definir(linhas, "Nenhuma sessão do Claude aberta agora.");
        terminar();
    }

    /** "318 imagens e 21 volumes. Espaço no disco: imagens 36,3 GB, ..." */
    static String textoDoEspaco(DockerInfo d) {
        String contagem = (d.imagens() == null ? FormatosDoServidor.SEM_DADO : d.imagens()) + " imagens e "
            + (d.volumes() == null ? FormatosDoServidor.SEM_DADO : d.volumes()) + " volumes.";
        Map<String, Long> t = d.tamanhos();
        if (t.isEmpty()) {
            return contagem;
        }
        List<String> partes = new ArrayList<>();
        for (String[] par : new String[][] {{"imagens", "imagens"}, {"volumes", "volumes"}, {"cache", "cache de build"},
            {"containers", "containers"}}) {
            if (t.containsKey(par[0])) {
                partes.add(par[1] + " " + FormatosDoServidor.bytes(t.get(par[0])));
            }
        }
        return contagem + " Espaço no disco: " + String.join(", ", partes) + ".";
    }

    List<List<String>> linhasDeSessoes() {
        return sessoes.linhas();
    }

    String textoDoResumo() {
        return resumo.getText();
    }
}
