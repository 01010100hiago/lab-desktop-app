package br.com.labdesktop.ui.telas;

import br.com.labdesktop.servidor.ResumoDeSeguranca.ConfiguracaoDoSsh;
import br.com.labdesktop.servidor.ResumoDeSeguranca.EstadoFail2ban;
import br.com.labdesktop.servidor.ResumoDeSeguranca.Leitura;
import br.com.labdesktop.servidor.ResumoDeSeguranca.PortaAberta;
import br.com.labdesktop.servidor.ResumoDeSeguranca.ResumoDoFirewall;
import br.com.labdesktop.ui.Gravidade;
import br.com.labdesktop.ui.componentes.Caixa;
import br.com.labdesktop.ui.componentes.Icones;
import br.com.labdesktop.ui.componentes.Painel;
import br.com.labdesktop.ui.componentes.Rotulo;
import br.com.labdesktop.ui.telas.CamposEmGrade.Campo;
import br.com.labdesktop.ui.tema.Tokens;
import br.com.labdesktop.util.Formato;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * As peças de texto da aba Segurança que não crescem: a configuração do SSH, o bloqueio automático, as portas abertas e o
 * firewall em frases simples, mais os atalhos de ícone e seção. Cada seção que falha diz isso em uma frase (sem permissão
 * ou sem resposta), nunca um erro feio.
 */
final class PecasDeSeguranca {

    static final String SEM_PERMISSAO = "Não consegui ler o registro de segurança (precisa de sudo sem senha para o usuário).";
    static final String INDISPONIVEL = "Não consegui ler o registro de segurança agora. O app tenta de novo em 1 minuto.";
    private static final int MAX_REGRAS = 8;
    private static final int MAX_PORTAS = 10;

    private PecasDeSeguranca() { }

    static JComponent ssh(Leitura<ConfiguracaoDoSsh> l) {
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

    static JComponent fail2ban(Leitura<EstadoFail2ban> l) {
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

    static JComponent portas(Leitura<List<PortaAberta>> l) {
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

    static JComponent firewall(Leitura<ResumoDoFirewall> l) {
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

    static JComponent aviso(Leitura<?> l) {
        return comIcone("alerta", Gravidade.ATENCAO, l.situacao() == Leitura.Situacao.SEM_PERMISSAO
            ? "Sem permissão para ler esta parte (precisa de sudo sem senha para o usuário)." : "Não consegui ler esta parte agora.",
            false);
    }

    // ---------------------------------------------------------------------------------------------
    // Montagem
    // ---------------------------------------------------------------------------------------------

    static Rotulo texto(String texto, String token) {
        return new Rotulo(texto).corDoToken(token).tamanho(12.5f);
    }

    static Rotulo comIcone(String icone, Gravidade g, String texto, boolean negrito) {
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

    static JPanel secao(String titulo, JComponent corpo) {
        Rotulo t = new Rotulo(titulo.toUpperCase(java.util.Locale.forLanguageTag("pt-BR"))).corDoToken(Tokens.TEXTO3).negrito()
            .tamanho(10.5f);
        JPanel p = Caixa.coluna(6, t, corpo);
        p.setName("secao-" + titulo);
        return p;
    }

    static JPanel duasColunas(JComponent a, JComponent b) {
        return new DuasColunas(a, b);
    }

    /** Duas seções lado a lado, de larguras iguais, coladas no topo. */
    static final class DuasColunas extends Painel {
        static final long serialVersionUID = 1L;

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
}
