package br.com.labdesktop.local;

import java.util.ArrayList;
import java.util.List;
import br.com.labdesktop.plataforma.SistemaOperacional;

/**
 * O aviso de segurança que o usuário lê ANTES de criar o laboratório local, em palavras simples e dizendo o que o container do agente recebe de
 * fato (é o que o {@link ComandosLocais#subirContainer} monta): o soquete do Docker, a visão dos processos, a raiz do disco só para leitura
 * (Mac e Linux) e a pasta dos projetos com escrita. O que muda de um sistema para outro é o alcance do soquete do Docker.
 */
public final class AvisoDeSeguranca {

    private AvisoDeSeguranca() { }

    /**
     * @param sistema o sistema deste computador
     * @param pasta   a pasta dos projetos escolhida
     * @return os parágrafos, na ordem da tela
     */
    public static List<String> paragrafos(SistemaOperacional sistema, String pasta) {
        List<String> p = new ArrayList<>();
        p.add("Segurança, sem rodeio. O agente que roda no container recebe de verdade: (1) o controle do Docker deste computador (o soquete do "
            + "Docker) e a visão dos processos do computador (--pid host); "
            + (sistema == SistemaOperacional.WINDOWS
            ? "(2) acesso às métricas da máquina do Docker (no Windows o disco não é montado inteiro); "
            : "(2) o disco inteiro (a raiz /, montada em /host), só para leitura, para medir espaço e consumo; ")
            + "(3) a pasta dos projetos (" + pasta + "), com permissão de escrita.");
        p.add("O que isso significa: quem controla o Docker consegue criar um container que monta QUALQUER pasta e mexer nela. "
            + switch (sistema) {
                case MAC -> "No Mac isso alcança tudo o que está em /Users (todos os seus arquivos).";
                case WINDOWS -> "No Windows isso alcança o disco C: inteiro.";
                case LINUX -> "No Linux, ter o Docker equivale a ser administrador (root) do computador.";
            });
        p.add("Por isso a porta é publicada SÓ em 127.0.0.1 (nada entra pela rede) e o agente exige o token deste app desde o primeiro minuto. O app "
            + "não mexe no firewall. Se não quer dar este poder ao agente, não crie o laboratório local: o app funciona do mesmo jeito com um servidor por SSH.");
        return p;
    }
}
