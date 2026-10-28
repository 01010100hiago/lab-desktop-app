package br.com.labdesktop.cloudflare;

import br.com.labdesktop.arquivos.ClienteSftp;
import br.com.labdesktop.arquivos.ErroSftp;
import br.com.labdesktop.cofre.ConfiguracaoCloudflare;
import br.com.labdesktop.projetos.AmbienteDeProjetos;
import br.com.labdesktop.projetos.FalhaDoProjeto;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Os endereços públicos que o app criou: os registros de DNS da zona cujo comentário começa com {@code lab-desktop:} e que apontam para ESTE túnel, e, para cada um, se a regra
 * do túnel existe (no config.yml, ou na configuração remota). Só LEITURA: pode rodar ao abrir a tela. Nunca lista (nem apaga) o que não leva a marca do app.
 */
public final class ListadorDeEnderecos {

    private final ApiCloudflare api;
    private final ConfiguracaoCloudflare cfg;
    private final AmbienteDeProjetos ambiente;

    /** @param ambiente para ler o config.yml no túnel por arquivo (pode ser {@code null} no túnel pela API) */
    public ListadorDeEnderecos(ApiCloudflare api, ConfiguracaoCloudflare cfg, AmbienteDeProjetos ambiente) {
        this.api = api;
        this.cfg = cfg;
        this.ambiente = ambiente;
    }

    /**
     * @param projeto só os deste projeto, ou {@code null} para todos
     * @throws FalhaDoProjeto não deu para falar com a Cloudflare
     */
    public List<EnderecoDoApp> listar(String projeto) throws FalhaDoProjeto {
        String alvo = NomeDoEndereco.alvoDoTunel(cfg.tunelId());
        List<RegistroDnsCf> registros;
        try {
            registros = api.registrosDnsComComentarioQueComecaCom(cfg.zonaId(), NomeDoEndereco.MARCA);
        } catch (ErroCloudflare e) {
            throw FalhasDaCloudflare.de(e);
        }
        Map<String, ConfigYmlDoCloudflared.Regra> doArquivo = new HashMap<>();
        Map<String, String> daApi = new HashMap<>();
        boolean conferiu = false;
        if (cfg.porArquivo()) {
            if (ambiente != null) {
                try (ClienteSftp c = ambiente.abrirArquivos()) {
                    byte[] bytes = TunelPorArquivo.lerBytes(c, cfg.caminhoDoConfig());
                    String texto = TunelPorArquivo.decodificar(bytes);
                    if (texto != null) {
                        for (ConfigYmlDoCloudflared.Regra r : ConfigYmlDoCloudflared.analisar(texto).regras()) {
                            if (!r.ehFinal()) {
                                doArquivo.put(r.hostname(), r);
                            }
                        }
                        conferiu = true;
                    }
                } catch (ErroSftp | FalhaDoProjeto | ConfigYmlDoCloudflared.Problema e) {
                    conferiu = false; // as regras ficam "não conferidas": o DNS ainda é mostrado
                }
            }
        } else {
            try {
                ObjectNode config = (ObjectNode) api.configuracaoDoTunel(cfg.contaId(), cfg.tunelId()).config();
                for (String linha : RegraDaApi.linhas(config)) {
                    int i = linha.indexOf(" -> ");
                    if (i > 0 && !linha.startsWith("(todo o resto)")) {
                        daApi.put(linha.substring(0, i).toLowerCase(java.util.Locale.ROOT), linha.substring(i + 4));
                    }
                }
                conferiu = true;
            } catch (ErroCloudflare e) {
                conferiu = false;
            }
        }
        List<EnderecoDoApp> lista = new ArrayList<>();
        for (RegistroDnsCf r : registros) {
            if (!r.tipo().equals("CNAME") || !r.conteudo().equalsIgnoreCase(alvo)) {
                continue; // é do app, mas de outro túnel
            }
            Optional<String> doProjeto = NomeDoEndereco.projetoDoComentario(r.comentario());
            if (doProjeto.isEmpty() || (projeto != null && !projeto.equals(doProjeto.get()))) {
                continue;
            }
            String servico = "";
            EnderecoDoApp.Regra regra = EnderecoDoApp.Regra.NAO_CONFERIDA;
            if (conferiu) {
                if (cfg.porArquivo()) {
                    ConfigYmlDoCloudflared.Regra rg = doArquivo.get(r.nome());
                    regra = rg != null && rg.marcadaPeloApp() ? EnderecoDoApp.Regra.SIM : EnderecoDoApp.Regra.NAO;
                    servico = rg == null ? "" : rg.servico();
                } else {
                    String s = daApi.get(r.nome());
                    regra = s != null ? EnderecoDoApp.Regra.SIM : EnderecoDoApp.Regra.NAO;
                    servico = s == null ? "" : s;
                }
            }
            lista.add(new EnderecoDoApp(r.nome(), doProjeto.get(), servico, r.id(), regra));
        }
        // Uma regra do config.yml com a marca do app mas SEM registro de DNS (o DNS foi apagado à mão, ou a publicação parou no meio): sem isto ela ficaria invisível e sem
        // como sair. Só no túnel por arquivo, porque só ali a regra leva marca; na API a regra não diz de quem é.
        if (conferiu && cfg.porArquivo()) {
            Set<String> jaListados = new HashSet<>();
            lista.forEach(e -> jaListados.add(e.hostname()));
            for (ConfigYmlDoCloudflared.Regra rg : doArquivo.values()) {
                if (!rg.marcadaPeloApp() || jaListados.contains(rg.hostname()) || !rg.hostname().endsWith("." + cfg.zonaNome())) {
                    continue;
                }
                if (projeto != null && !projeto.equals(rg.projetoDaMarca())) {
                    continue;
                }
                lista.add(new EnderecoDoApp(rg.hostname(), rg.projetoDaMarca(), rg.servico(), null, EnderecoDoApp.Regra.SIM));
            }
        }
        lista.sort(Comparator.comparing(EnderecoDoApp::hostname));
        return lista;
    }
}
