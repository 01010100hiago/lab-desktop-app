package br.com.labdesktop.cloudflare;

import br.com.labdesktop.arquivos.Cancelamento;
import br.com.labdesktop.cofre.ConfiguracaoCloudflare;
import br.com.labdesktop.projetos.Diferenca;
import br.com.labdesktop.projetos.FalhaDoProjeto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * O túnel gerido pela API da Cloudflare ({@code config_src = cloudflare}): as regras moram na configuração remota do túnel. O app LÊ a configuração inteira,
 * mexe SÓ na lista {@code ingress} (a regra entra antes da regra final) e a grava de volta ({@code PUT .../configurations}). A Cloudflare entrega as regras
 * novas ao cloudflared sozinha, em alguns segundos: não há reinício nem container a conferir daqui.
 *
 * <p>Se a configuração mudou entre o clique de ver e o de confirmar (outra pessoa mexeu), o app para e manda conferir de novo. Se algo falha depois de gravar,
 * a configuração anterior é gravada de volta.
 *
 * <p>Premissa (ARQUITETURA 7.11): o cloudflared deste túnel enxerga a rede Docker {@code edge} do servidor, para alcançar o container pelo nome. O app não tem
 * como conferir isso pela API; o teste do endereço, no fim, mostra o motivo se o túnel não chega no container.
 */
public final class TunelPelaApi implements Tunel {

    private final ApiCloudflare api;
    private final ConfiguracaoCloudflare cfg;

    public TunelPelaApi(ApiCloudflare api, ConfiguracaoCloudflare cfg) {
        if (cfg.porArquivo()) {
            throw new IllegalArgumentException("Este túnel é gerido por arquivo.");
        }
        this.api = api;
        this.cfg = cfg;
    }

    @Override
    public String onde() {
        return "a configuração do túnel \"" + cfg.tunelNome() + "\" na Cloudflare";
    }

    @Override
    public boolean reiniciaOCloudflared() {
        return false;
    }

    @Override
    public boolean regraTemMarca() {
        return false;
    }

    private ObjectNode lerConfiguracao() throws FalhaDoProjeto {
        try {
            JsonNode c = api.configuracaoDoTunel(cfg.contaId(), cfg.tunelId()).config();
            return (ObjectNode) c;
        } catch (ErroCloudflare e) {
            throw FalhasDaCloudflare.de(e);
        }
    }

    @Override
    public Mudanca prepararAcrescimo(String hostname, String servico, String observacao) throws FalhaDoProjeto {
        ObjectNode antes = lerConfiguracao();
        try {
            RegraDaApi.Edicao e = RegraDaApi.acrescentar(antes, hostname, servico);
            return new MudancaPelaApi(false, antes, e.config(), Diferenca.legivel(String.join("\n", RegraDaApi.linhas(antes)), String.join("\n", RegraDaApi.linhas(e.config())), 2), e.avisos(),
                e.jaEstava(), null);
        } catch (RegraDaApi.Problema p) {
            return new MudancaPelaApi(false, antes, antes, "", List.of(), false, p.getMessage());
        }
    }

    @Override
    public Mudanca prepararRemocao(String hostname, String servicoEsperado) throws FalhaDoProjeto {
        ObjectNode antes = lerConfiguracao();
        try {
            ObjectNode depois = RegraDaApi.remover(antes, hostname, servicoEsperado);
            if (depois == null) {
                return new MudancaPelaApi(true, antes, antes, "", List.of(), true, null); // já não há regra
            }
            return new MudancaPelaApi(true, antes, depois, Diferenca.legivel(String.join("\n", RegraDaApi.linhas(antes)), String.join("\n", RegraDaApi.linhas(depois)), 2), List.of(), false, null);
        } catch (RegraDaApi.Problema p) {
            return new MudancaPelaApi(true, antes, antes, "", List.of(), false, p.getMessage());
        }
    }

    private final class MudancaPelaApi implements Mudanca {
        private final boolean remover;
        private final ObjectNode antes;
        private final ObjectNode depois;
        private final String diferenca;
        private final List<String> avisos;
        private final boolean semEfeito;
        private final String bloqueio;

        MudancaPelaApi(boolean remover, ObjectNode antes, ObjectNode depois, String diferenca, List<String> avisos, boolean semEfeito, String bloqueio) {
            this.remover = remover;
            this.antes = antes;
            this.depois = depois;
            this.diferenca = diferenca;
            this.avisos = new ArrayList<>(avisos);
            this.semEfeito = semEfeito;
            this.bloqueio = bloqueio;
            if (bloqueio == null && !semEfeito) {
                this.avisos.add("A Cloudflare entrega as regras novas ao cloudflared sozinha, em alguns segundos (sem reiniciar nada). O cloudflared deste túnel precisa estar na rede "
                    + "edge do servidor para achar o container pelo nome.");
            }
        }

        @Override
        public String diferenca() {
            return diferenca;
        }

        @Override
        public List<String> avisos() {
            return avisos;
        }

        @Override
        public String bloqueio() {
            return bloqueio;
        }

        @Override
        public boolean semEfeito() {
            return semEfeito;
        }

        @Override
        public Aplicada aplicar(ObservadorDaEtapa obs, Cancelamento cancelamento) throws FalhaDoProjeto {
            Etapa etapa = remover ? Etapa.REMOVER_REGRA : Etapa.REGRA;
            if (bloqueio != null) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.RECUSADO, bloqueio);
            }
            if (semEfeito) {
                obs.etapa(etapa, ObservadorDaEtapa.Estado.PULADO, "As regras do túnel já estão como deveriam.");
                return new AplicadaPelaApi(false);
            }
            if (cancelamento != null && cancelamento.cancelado()) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.CANCELADO, "Cancelado.");
            }
            obs.etapa(etapa, ObservadorDaEtapa.Estado.ANDANDO, "Gravando as regras do túnel na Cloudflare…");
            ObjectNode agora = lerConfiguracao();
            if (!agora.equals(antes)) {
                throw new FalhaDoProjeto(FalhaDoProjeto.Tipo.OUTRO, "A configuração do túnel mudou na Cloudflare depois que você viu a diferença. Nada foi gravado: confira de novo.");
            }
            try {
                api.gravarConfiguracaoDoTunel(cfg.contaId(), cfg.tunelId(), depois);
            } catch (ErroCloudflare e) {
                FalhaDoProjeto f = FalhasDaCloudflare.de(e);
                if (e.incerto()) {
                    // pode ter gravado: confere e, se gravou, devolve a anterior
                    AplicadaPelaApi talvez = new AplicadaPelaApi(true);
                    List<String> sobrou = talvez.desfazerSeGravou();
                    obs.etapa(etapa, ObservadorDaEtapa.Estado.FALHOU, f.mensagem());
                    throw new FalhaDoProjeto(f.tipo(), f.mensagem() + (sobrou.isEmpty() ? " Conferi: a configuração anterior está de volta ou nunca saiu do lugar." : " Atenção: " + String.join(" ", sobrou)), null, e);
                }
                obs.etapa(etapa, ObservadorDaEtapa.Estado.FALHOU, f.mensagem());
                throw f;
            }
            obs.etapa(etapa, ObservadorDaEtapa.Estado.FEITO, "Regras gravadas na Cloudflare.");
            return new AplicadaPelaApi(true);
        }

        private final class AplicadaPelaApi implements Aplicada {
            private final boolean gravou;

            AplicadaPelaApi(boolean gravou) {
                this.gravou = gravou;
            }

            @Override
            public String copia() {
                return null;
            }

            @Override
            public List<String> desfazer() {
                List<String> naoDesfez = new ArrayList<>();
                if (!gravou) {
                    return naoDesfez;
                }
                try {
                    ObjectNode atual = (ObjectNode) api.configuracaoDoTunel(cfg.contaId(), cfg.tunelId()).config();
                    if (!atual.equals(depois)) {
                        if (!atual.equals(antes)) {
                            naoDesfez.add("A configuração do túnel \"" + cfg.tunelNome() + "\" mudou na Cloudflare depois que o app a gravou (outra pessoa mexeu): NÃO a sobrescrevi com a anterior. "
                                + "Confira as regras no painel da Cloudflare.");
                        }
                        return naoDesfez; // já é a de antes (nada a fazer), ou é de outra pessoa (não mexe)
                    }
                    api.gravarConfiguracaoDoTunel(cfg.contaId(), cfg.tunelId(), antes);
                } catch (ErroCloudflare e) {
                    naoDesfez.add("Não consegui devolver a configuração anterior do túnel na Cloudflare (" + e.mensagem() + "): confira as regras do túnel \"" + cfg.tunelNome() + "\" no painel da Cloudflare.");
                }
                return naoDesfez;
            }

            /** Depois de uma gravação com resultado incerto: se a configuração gravada está lá, devolve a anterior. */
            List<String> desfazerSeGravou() {
                try {
                    ObjectNode atual = (ObjectNode) api.configuracaoDoTunel(cfg.contaId(), cfg.tunelId()).config();
                    if (atual.equals(depois)) {
                        return desfazer();
                    }
                    return List.of();
                } catch (ErroCloudflare e) {
                    return List.of("Não consegui conferir se a configuração do túnel foi gravada (" + e.mensagem() + "): confira as regras do túnel \"" + cfg.tunelNome() + "\" no painel da Cloudflare.");
                }
            }
        }
    }
}
