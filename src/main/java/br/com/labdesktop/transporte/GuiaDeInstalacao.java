package br.com.labdesktop.transporte;

import br.com.labdesktop.plataforma.SistemaOperacional;
import java.util.List;

/**
 * Os passos para instalar cada programa, no sistema onde o app roda, como TEXTO para o usuário copiar. O app NUNCA instala nem executa instalação destes programas: mostra o
 * passo e quem instala é o usuário, no terminal dele. Onde não há um comando único e estável (o plugin do Session Manager no Windows, os instaladores no Linux) o texto cita o nome
 * do instalador oficial e não inventa endereço.
 */
public final class GuiaDeInstalacao {

    private GuiaDeInstalacao() { }

    /** As linhas a copiar (comandos, ou uma frase onde não há comando). */
    public static List<String> passos(FerramentaExterna ferramenta, SistemaOperacional sistema) {
        return switch (sistema) {
            case MAC -> switch (ferramenta) {
                case AWS -> List.of("brew install awscli");
                case PLUGIN_SSM -> List.of("brew install --cask session-manager-plugin");
                case CLOUDFLARED -> List.of("brew install cloudflared");
            };
            case WINDOWS -> switch (ferramenta) {
                case AWS -> List.of("winget install Amazon.AWSCLI");
                case PLUGIN_SSM -> List.of("Instale o Session Manager plugin pelo instalador oficial da AWS (procure por \"Session Manager plugin\" na documentação da AWS).");
                case CLOUDFLARED -> List.of("winget install Cloudflare.cloudflared");
            };
            case LINUX -> switch (ferramenta) {
                case AWS -> List.of("Instale o AWS CLI v2 pelo instalador oficial da AWS ou pelo pacote da sua distribuição.");
                case PLUGIN_SSM -> List.of("Instale o Session Manager plugin pelo pacote oficial da AWS (.deb ou .rpm, conforme a sua distribuição).");
                case CLOUDFLARED -> List.of("Instale o cloudflared pelo pacote da sua distribuição ou pelo repositório oficial da Cloudflare.");
            };
        };
    }

    /** Os passos como um texto só, uma linha por passo (o que o botão Copiar leva). */
    public static String textoDosPassos(FerramentaExterna ferramenta, SistemaOperacional sistema) {
        return String.join("\n", passos(ferramenta, sistema));
    }

    /** O aviso de que o programa não está neste computador, com o que fazer. */
    public static FalhaDoTunel ausente(FerramentaExterna ferramenta, SistemaOperacional sistema) {
        return new FalhaDoTunel(FalhaDoTunel.Tipo.FERRAMENTA_AUSENTE,
            "Não encontrei o programa " + ferramenta.nomeBase() + " (" + ferramenta.rotulo() + ") neste computador. O app não instala nada: instale o programa "
                + "(" + sistema.rotulo() + ") e clique em Tentar agora. Para instalar: " + String.join(" ", passos(ferramenta, sistema)),
            textoDosPassos(ferramenta, sistema));
    }
}
