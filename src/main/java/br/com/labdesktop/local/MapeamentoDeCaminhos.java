package br.com.labdesktop.local;

import java.util.regex.Pattern;
import br.com.labdesktop.plataforma.SistemaOperacional;

/**
 * O caminho de uma pasta do computador (o ANFITRIÃO) como o container do agente a enxerga. É o que faz os {@code volumes} dos projetos (o
 * {@code ./dados} de um compose) valerem: quem resolve um {@code -v} é o daemon do Docker, que só conhece caminhos do anfitrião, e o
 * {@code docker compose} que roda DENTRO do container do agente monta esses caminhos a partir de onde ele vê o arquivo. Então a pasta precisa
 * aparecer no container num caminho que o daemon entenda também:
 *
 * <ul>
 *   <li><b>Mac e Linux:</b> o MESMO caminho ({@code /Users/ana/LabLocal/projects} nos dois lados). Conferido no Docker Desktop do Mac:
 *       um {@code docker run -v} feito de dentro do container pelo socket resolve o caminho do anfitrião.</li>
 *   <li><b>Windows (Docker Desktop com WSL 2):</b> o daemon roda na máquina virtual, onde o disco do Windows aparece em
 *       {@code /run/desktop/mnt/host/<letra>/...}; um pedido que chega pelo socket de dentro do container NÃO passa pela tradução de caminhos
 *       do Windows, então o container vê a pasta nesse caminho da VM ({@code C:\Users\ana\LabLocal\projects} vira
 *       {@code /run/desktop/mnt/host/c/Users/ana/LabLocal/projects}). A regra está escrita e testada como texto; <b>não foi conferida num Windows
 *       de verdade</b>. Se o agente em container ficar frágil lá, a alternativa documentada é o agente Node como processo local.</li>
 * </ul>
 */
public final class MapeamentoDeCaminhos {

    /** Letras, números, espaço e os sinais comuns de pasta; nada de vírgula, dois-pontos (fora da letra do disco), aspas ou controle. */
    private static final Pattern SEGURO = Pattern.compile("^[\\p{L}\\p{N} ._()+@#%&=~-]+$");
    private static final Pattern DISCO_DO_WINDOWS = Pattern.compile("^([A-Za-z]):[\\\\/](.*)$");

    private MapeamentoDeCaminhos() { }

    /**
     * @param caminhoDoAnfitriao o caminho absoluto no computador ({@code /Users/ana/LabLocal/projects} ou {@code C:\Users\ana\LabLocal\projects})
     * @return o caminho dentro do container
     * @throws IllegalArgumentException o caminho não é absoluto, tem {@code ..} ou um caractere que quebraria o {@code -v} do Docker
     */
    public static String doContainer(SistemaOperacional sistema, String caminhoDoAnfitriao) {
        if (caminhoDoAnfitriao == null || caminhoDoAnfitriao.isBlank()) {
            throw new IllegalArgumentException("Caminho vazio.");
        }
        if (sistema == SistemaOperacional.WINDOWS) {
            var m = DISCO_DO_WINDOWS.matcher(caminhoDoAnfitriao);
            if (!m.matches()) {
                throw new IllegalArgumentException("No Windows o caminho começa pela letra do disco (por exemplo C:\\Users\\voce\\LabLocal).");
            }
            String resto = conferirPartes(m.group(2).replace('\\', '/'));
            String base = "/run/desktop/mnt/host/" + Character.toLowerCase(m.group(1).charAt(0));
            return resto.isEmpty() ? base : base + "/" + resto;
        }
        if (!caminhoDoAnfitriao.startsWith("/")) {
            throw new IllegalArgumentException("O caminho da pasta deve ser absoluto.");
        }
        String resto = conferirPartes(caminhoDoAnfitriao.substring(1));
        return "/" + resto;
    }

    private static String conferirPartes(String caminho) {
        StringBuilder sb = new StringBuilder();
        for (String parte : caminho.split("/")) {
            if (parte.isEmpty()) {
                continue;
            }
            if (parte.equals("..") || parte.equals(".") || !SEGURO.matcher(parte).matches()) {
                throw new IllegalArgumentException("O caminho da pasta tem um trecho que o Docker não aceita: " + visivel(parte) + ".");
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(parte);
        }
        return sb.toString();
    }

    private static String visivel(String parte) {
        String limpo = parte.replaceAll("\\p{Cntrl}", "?");
        return limpo.length() > 40 ? limpo.substring(0, 40) + "…" : limpo;
    }
}
