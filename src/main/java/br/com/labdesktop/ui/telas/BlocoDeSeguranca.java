package br.com.labdesktop.ui.telas;

import br.com.labdesktop.servidor.ResumoDeSeguranca;
import br.com.labdesktop.servidor.ResumoDeSeguranca.ConfiguracaoDoSsh;
import br.com.labdesktop.servidor.ResumoDeSeguranca.EstadoFail2ban;
import br.com.labdesktop.servidor.ResumoDeSeguranca.Leitura;
import br.com.labdesktop.servidor.ResumoDeSeguranca.LoginAceito;
import br.com.labdesktop.servidor.ResumoDeSeguranca.Origem;
import br.com.labdesktop.servidor.ResumoDeSeguranca.PortaAberta;
import br.com.labdesktop.servidor.ResumoDeSeguranca.ResumoDoFirewall;
import br.com.labdesktop.servidor.ResumoDeSeguranca.Tentativas;
import br.com.labdesktop.servidor.ResumoDeSeguranca.TipoDeTentativa;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.telas.CamposEmGrade.Campo;
import br.com.labdesktop.ui.telas.TabelaDeLinhas.Coluna;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Formato;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * O bloco "Segurança" da Visão geral: informação sobre possíveis ataques ao servidor, SOMENTE LEITURA (não bloqueia IP nem
 * muda firewall; isso é uma etapa futura). Tentativas de entrada em 24 h e 7 dias, de que tipo foram, os cinco IPs que
 * mais tentaram, os logins aceitos (destacando os de fora da rede local ou por senha), o estado do SSH, o bloqueio
 * automático, as portas abertas e o firewall em frases simples. Cada seção degrada sozinha: sem permissão ou sem
 * resposta, a frase diz isso em vez de um erro feio. Com zero tentativas o tom é de tranquilidade.
 */
final class BlocoDeSeguranca extends CaixaDoServidor {

    private static final long serialVersionUID = 1L;

    static final String SEM_PERMISSAO = "Não consegui ler o registro de segurança (precisa de sudo sem senha para o usuário).";
    static final String INDISPONIVEL = "Não consegui ler o registro de segurança agora. O app tenta de novo em 1 minuto.";
    private static final int MAX_REGRAS = 8;
    private static final int MAX_PORTAS = 10;

    private final Rotulo lido = new Rotulo().corDoToken(Tokens.TEXTO3).tamanho(12f);

    BlocoDeSeguranca() {
        super("Segurança", "chave");
        apoio(lido);
        atualizar(null, ZoneId.systemDefault());
    }

    void atualizar(ResumoDeSeguranca r, ZoneId fuso) {
        limpar();
        if (r == null) {
            lido.setText("somente leitura");
            linha(texto("Lendo o registro de segurança do servidor…", Tokens.TEXTO2), 0);
            terminar();
            return;
        }
        lido.setText("somente leitura · lido às " + FormatosDoServidor.horaMinuto(r.lidoEm(), fuso));

        // 1) a manchete: tentativas de entrada
        Leitura<Tentativas> lt = r.tentativas();
        if (lt.lida()) {
            Tentativas t = lt.dados();
            linha(manchete(t), 0);
            if (t.foraDaRedeEm24h() > 0) {
                linha(comIcone("erro", Gravidade.PERIGO, "Alguém entrou de fora da rede local nas últimas 24 h. Veja os logins aceitos abaixo.",
                    true), 6);
            }
            if (t.em7d() > 0) {
                linha(duasColunas(secao("Como tentaram (7 dias)", tipos(t)), secao("Quem mais tentou (7 dias)", origens(t))), 14);
            }
        } else {
            linha(comIcone("alerta", Gravidade.ATENCAO, lt.situacao() == Leitura.Situacao.SEM_PERMISSAO ? SEM_PERMISSAO : INDISPONIVEL,
                false), 0);
        }

        // 2) logins aceitos
        if (lt.lida()) {
            linha(secao("Logins aceitos recentemente (7 dias)", logins(lt.dados(), fuso)), 14);
        }

        // 3) SSH e bloqueio automático | portas e firewall
        linha(duasColunas(secao("Configuração do SSH", ssh(r.ssh())), secao("Bloqueio automático de IPs", fail2ban(r.fail2ban()))), 14);
        linha(duasColunas(secao("Portas abertas para fora da máquina", portas(r.portas())),
            secao("Firewall", firewall(r.firewall()))), 14);
        terminar();
    }

    // ---------------------------------------------------------------------------------------------
    // Peças
    // ---------------------------------------------------------------------------------------------

    private static JComponent manchete(Tentativas t) {
        String mais = t.parcial() ? " (pelo menos)" : "";
        String semana = "Nos últimos 7 dias: " + Formato.fmtNum(t.em7d(), 0) + mais + ".";
        JPanel p;
        if (t.em24h() == 0) {
            p = Caixa.linha(10, comIcone("ok-circulo", Gravidade.OK, "Nenhuma tentativa de entrada nas últimas 24 h.", true),
                Rotulo.dica(semana));
        } else {
            String n = Formato.fmtNum(t.em24h(), 0) + mais;
            p = Caixa.linha(10, comIcone("alerta", Gravidade.ATENCAO,
                n + (t.em24h() == 1 ? " tentativa" : " tentativas") + " de entrada nas últimas 24 h.", true), Rotulo.dica(semana));
        }
        p.setName("manchete-de-seguranca");
        return p;
    }

    private static JComponent tipos(Tentativas t) {
        CamposEmGrade g = new CamposEmGrade();
        List<Campo> campos = new ArrayList<>();
        for (TipoDeTentativa tipo : TipoDeTentativa.values()) {
            int n = t.porTipo().getOrDefault(tipo, 0);
            if (n > 0) {
                campos.add(new Campo(tipo.descricao(), Formato.fmtNum(n, 0)));
            }
        }
        g.definir(campos);
        g.setName("tipos-de-tentativa");
        return g;
    }

    private static JComponent origens(Tentativas t) {
        CamposEmGrade g = new CamposEmGrade();
        List<Campo> campos = new ArrayList<>();
        for (Origem o : t.origens()) {
            campos.add(new Campo(o.ip() + (o.redeLocal() ? " (rede local)" : ""),
                Formato.fmtNum(o.tentativas(), 0) + (o.tentativas() == 1 ? " tentativa" : " tentativas")));
        }
        if (t.totalDeOrigens() > t.origens().size()) {
            campos.add(new Campo("e mais " + (t.totalDeOrigens() - t.origens().size()) + " endereços", ""));
        }
        g.definir(campos);
        g.setName("origens-das-tentativas");
        return g;
    }

    private static JComponent logins(Tentativas t, ZoneId fuso) {
        TabelaDeLinhas tabela = new TabelaDeLinhas(new Coluna("Quando", 0.6, false, false), new Coluna("Quem", 0.6, false, false),
            new Coluna("De onde", 1.0, false, true), new Coluna("Como entrou", 1.6, false, false),
            new Coluna("Atenção", 1.2, false, false));
        List<List<String>> linhas = new ArrayList<>();
        for (LoginAceito l : t.logins()) {
            String como = "chave".equals(l.metodo())
                ? "chave" + (l.algoritmo().isEmpty() ? "" : " " + l.algoritmo()) + (l.impressao().isEmpty() ? "" : " " + l.impressao())
                : "senha";
            String aviso = l.foraDaRedeLocal() ? "de fora da rede local" : "senha".equals(l.metodo()) ? "entrou com senha" : "";
            linhas.add(List.of(FormatosDoServidor.diaEHora(l.quando(), fuso), l.usuario(), l.ip() + ":" + l.porta(), como,
                aviso.isEmpty() ? "—" : aviso));
        }
        tabela.definir(linhas, "Nenhum login nos últimos 7 dias.");
        tabela.setName("logins-aceitos");
        return tabela;
    }

    private static JComponent ssh(Leitura<ConfiguracaoDoSsh> l) {
        if (!l.lida()) {
            return aviso(l);
        }
        ConfiguracaoDoSsh c = l.dados();
        List<Campo> campos = new ArrayList<>();
        campos.add(new Campo("Porta", c.porta() == null ? null : String.valueOf(c.porta())));
        if (c.senhaLigada() == null) {
            campos.add(new Campo("Entrada por senha", null));
        } else if (c.senhaLigada()) {
            campos.add(new Campo("Entrada por senha", "ligada: qualquer um pode tentar adivinhar uma senha", Gravidade.PERIGO));
        } else {
            campos.add(new Campo("Entrada por senha", "desligada: só entra quem tem a chave", Gravidade.OK));
        }
        String root = c.loginDoRoot();
        if (root == null) {
            campos.add(new Campo("Login do root", null));
        } else if (c.rootDesligado()) {
            campos.add(new Campo("Login do root", "desligado", Gravidade.OK));
        } else if (root.equals("yes")) {
            campos.add(new Campo("Login do root", "permitido, inclusive com senha", Gravidade.PERIGO));
        } else {
            campos.add(new Campo("Login do root", "só com chave (" + root + ")", Gravidade.ATENCAO));
        }
        campos.add(new Campo("Tentativas por conexão", c.maxTentativas() == null ? null : String.valueOf(c.maxTentativas())));
        CamposEmGrade g = new CamposEmGrade();
        g.definir(campos);
        g.setName("configuracao-do-ssh");
        return g;
    }

    private static JComponent fail2ban(Leitura<EstadoFail2ban> l) {
        if (!l.lida()) {
            return aviso(l);
        }
        EstadoFail2ban f = l.dados();
        if (!f.ativo()) {
            return comIcone("alerta", Gravidade.ATENCAO, "Sem bloqueio automático: o fail2ban não está ativo neste servidor.", false);
        }
        String detalhe = f.banidosAgora() == null ? "ativo."
            : "ativo: " + f.banidosAgora() + (f.banidosAgora() == 1 ? " IP bloqueado agora" : " IPs bloqueados agora")
                + (f.totalBanidos() == null ? "." : " (" + Formato.fmtNum(f.totalBanidos(), 0) + " desde que começou).");
        JPanel p = Caixa.coluna(4, comIcone("ok-circulo", Gravidade.OK, "fail2ban " + detalhe, false));
        if (!f.ips().isEmpty()) {
            String ips = String.join(", ", f.ips().subList(0, Math.min(5, f.ips().size())))
                + (f.ips().size() > 5 ? " e mais " + (f.ips().size() - 5) : "");
            p.add(new Rotulo("Bloqueados: " + ips).corDoToken(Tokens.TEXTO2).tamanho(12f).truncavel());
        }
        p.setName("fail2ban");
        return p;
    }

    private static JComponent portas(Leitura<List<PortaAberta>> l) {
        if (!l.lida()) {
            return aviso(l);
        }
        List<PortaAberta> lista = l.dados();
        if (lista.isEmpty()) {
            return texto("Nenhuma porta escutando para fora da máquina.", Tokens.TEXTO2);
        }
        CamposEmGrade g = new CamposEmGrade();
        List<Campo> campos = new ArrayList<>();
        for (PortaAberta p : lista.subList(0, Math.min(MAX_PORTAS, lista.size()))) {
            campos.add(new Campo("Porta " + p.porta(), (p.processo().isEmpty() ? "" : p.processo() + " · ") + p.escopo()));
        }
        if (lista.size() > MAX_PORTAS) {
            campos.add(new Campo("e mais " + (lista.size() - MAX_PORTAS) + " portas", ""));
        }
        g.definir(campos);
        g.setName("portas-abertas");
        return g;
    }

    private static JComponent firewall(Leitura<ResumoDoFirewall> l) {
        if (!l.lida()) {
            return aviso(l);
        }
        ResumoDoFirewall f = l.dados();
        if (!f.identificado()) {
            return comIcone("alerta", Gravidade.ATENCAO, "Firewall não identificado: nem o nftables nem o iptables responderam.", false);
        }
        List<JComponent> itens = new ArrayList<>();
        boolean aceitaTudo = "aceita".equals(f.politica());
        String politica = f.politica() == null ? f.motor() + ": política padrão desconhecida"
            : f.motor() + ": " + (aceitaTudo ? "aceita" : "bloqueia") + " o que não tem regra";
        itens.add(aceitaTudo ? comIcone("alerta", Gravidade.ATENCAO, politica, true) : comIcone("ok-circulo", Gravidade.OK, politica, true));
        for (String regra : f.regras().subList(0, Math.min(MAX_REGRAS, f.regras().size()))) {
            itens.add(new Rotulo(regra).corDoToken(Tokens.TEXTO2).tamanho(12f).truncavel());
        }
        if (f.regras().size() > MAX_REGRAS) {
            itens.add(Rotulo.dica("e mais " + (f.regras().size() - MAX_REGRAS) + " regras"));
        }
        JPanel p = Caixa.coluna(3, itens.toArray(JComponent[]::new));
        p.setName("firewall");
        return p;
    }

    private static JComponent aviso(Leitura<?> l) {
        return comIcone("alerta", Gravidade.ATENCAO, l.situacao() == Leitura.Situacao.SEM_PERMISSAO
            ? "Sem permissão para ler esta parte (precisa de sudo sem senha para o usuário)." : "Não consegui ler esta parte agora.",
            false);
    }

    // ---------------------------------------------------------------------------------------------
    // Montagem
    // ---------------------------------------------------------------------------------------------

    private static Rotulo texto(String texto, String token) {
        return new Rotulo(texto).corDoToken(token).tamanho(12.5f);
    }

    private static Rotulo comIcone(String icone, Gravidade g, String texto, boolean negrito) {
        Rotulo r = new Rotulo(texto).tamanho(12.5f).truncavel();
        r.corDoToken(g == Gravidade.OK ? Tokens.TEXTO : g.tokenDoTexto());
        if (negrito) {
            r.negrito();
        }
        r.setIcon(Icones.colorido(icone, 16, g.tokenDoTexto()));
        r.setIconTextGap(8);
        r.setToolTipText(texto);
        return r;
    }

    private static JPanel secao(String titulo, JComponent corpo) {
        Rotulo t = new Rotulo(titulo.toUpperCase(java.util.Locale.forLanguageTag("pt-BR"))).corDoToken(Tokens.TEXTO3).negrito()
            .tamanho(10.5f);
        JPanel p = Caixa.coluna(6, t, corpo);
        p.setName("secao-" + titulo);
        return p;
    }

    private static JPanel duasColunas(JComponent a, JComponent b) {
        return new DuasColunas(a, b);
    }

    /** Duas seções lado a lado, de larguras iguais, coladas no topo. */
    private static final class DuasColunas extends Painel {
        private static final long serialVersionUID = 1L;

        DuasColunas(JComponent a, JComponent b) {
            super(new java.awt.GridLayout(1, 2, 28, 0));
            add(a);
            add(b);
        }

        @Override
        public java.awt.Dimension getMaximumSize() {
            return new java.awt.Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Para os testes
    // ---------------------------------------------------------------------------------------------

    /** Todo o texto do bloco, uma linha por rótulo, na ordem da tela. */
    String textoCompleto() {
        return Textos.de(this);
    }
}
