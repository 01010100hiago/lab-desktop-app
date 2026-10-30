package br.com.labdesktop.local;

import java.util.ArrayList;
import java.util.List;
import br.com.labdesktop.plataforma.SistemaOperacional;

/**
 * O aviso de segurança que o usuário lê ANTES de criar o laboratório local, em palavras simples e dizendo o que o container do agente recebe de
 * fato (é o que o {@link ComandosLocais#subirContainer} monta): o soquete do Docker, a visão dos processos e, no Mac e no Linux, três arquivos
 * da máquina só para leitura. Nem o disco inteiro nem a pasta dos projetos entram no container. O que muda de um sistema para outro é o alcance
 * do soquete do Docker.
 */
public final class AvisoDeSeguranca {

    private AvisoDeSeguranca() { }

    /**
     * @param sistema o sistema deste computador
     * @param pasta   a pasta dos projetos escolhida (o aviso diz que ela NÃO vai para o container)
     * @return os parágrafos, na ordem da tela
     */
    public static List<String> paragrafos(SistemaOperacional sistema, String pasta) {
        List<String> p = new ArrayList<>();
        p.add("Segurança, sem rodeio. O agente que roda no container recebe de verdade: (1) o acesso ao Docker deste computador (o soquete do "
            + "Docker; o agente só faz três leituras com ele, mas o acesso que ele tem é o do Docker inteiro) e a visão dos processos do "
            + "computador (--pid host); "
            + (sistema == SistemaOperacional.WINDOWS
            ? "(2) nenhum arquivo do computador: no Windows o app não monta arquivo nenhum, e o agente mostra as métricas da máquina do Docker. "
            : "(2) três arquivos do computador, só para leitura: o nome da máquina, o sistema e os nomes de usuário (/etc/hostname, "
            + "/etc/os-release e /etc/passwd). ")
            + "Ele NÃO recebe o disco, nem a pasta dos projetos (" + pasta + ").");
        p.add("O que isso significa: quem controla o Docker consegue criar um container que monta QUALQUER pasta e mexer nela. "
            + switch (sistema) {
                case MAC -> "No Mac isso alcança tudo o que está em /Users (todos os seus arquivos).";
                case WINDOWS -> "No Windows isso alcança o disco C: inteiro.";
                case LINUX -> "No Linux, ter o Docker equivale a ser administrador (root) do computador.";
            });
        p.add("Por isso o agente é pequeno, só lê (não executa comando nenhum), roda desde o início como um usuário comum, sem capacidades extras, sem "
            + "poder ganhar privilégio e com o sistema de arquivos só para leitura, a "
            + "porta é publicada SÓ em 127.0.0.1 (nada entra pela rede) e ele exige o token deste app desde o primeiro minuto. O app "
            + "não mexe no firewall. Se não quer dar este poder ao agente, não crie o laboratório local: o app funciona do mesmo jeito com um servidor por SSH.");
        return p;
    }
}
