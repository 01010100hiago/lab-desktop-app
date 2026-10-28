package br.com.labdesktop.ui.dialogos;

import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.labdesktop.api.modelo.Limite;
import br.com.labdesktop.ssh.SondagemDeHost;
import br.com.labdesktop.ui.AuditoriaDeCores;
import br.com.labdesktop.ui.AuditoriaDeCores.Medida;
import br.com.labdesktop.ui.AvisosGravados;
import br.com.labdesktop.ui.VerificaEdt;
import br.com.labdesktop.ui.Visual;
import br.com.labdesktop.util.Preferencias.Tema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * As cores dos botões de cada diálogo que se monta sozinho, medidas nos pixels ({@link AuditoriaDeCores}), COM o botão do Enter ligado a um painel de
 * janela (é assim que o FlatLaf o pinta como "padrão", e foi aí que o botão de confirmação saía preto), nos dois temas e trocando o tema com o
 * diálogo já montado.
 */
@ExtendWith(VerificaEdt.class)
class CoresDosDialogosTest {

    private static final Instant AGORA = Instant.parse("2026-10-24T15:00:00Z");
    private final AvisosGravados avisos = new AvisosGravados();

    private static SondagemDeHost.Resultado sonda() {
        try {
            KeyPair par = KeyUtils.generateKeyPair("ssh-ed25519", 256);
            return new SondagemDeHost.Resultado("ssh-ed25519", KeyUtils.getFingerPrint(par.getPublic()), par.getPublic(),
                "ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Supplier<Dialogo>> catalogo() {
        Map<String, Supplier<Dialogo>> m = new LinkedHashMap<>();
        m.put("confirmar", () -> new DialogoConfirmar("Reabrir sessão", "atualizar", "Texto da pergunta.", "Reabrir", false, ok -> { }));
        m.put("confirmar perigoso", () -> new DialogoConfirmar("Remover", "alerta", "Texto da pergunta.", "Remover", true, ok -> { }));
        m.put("confirmar com Cancelar no Enter", () -> new DialogoConfirmar("Sair?", "alerta", "Texto da pergunta.", "Sair mesmo assim", true, true, ok -> { }));
        m.put("confirmar sem perigo e Cancelar no Enter", () -> new DialogoConfirmar("Salvar", "chave", "Texto da pergunta.", "Salvar", false, true, ok -> { }));
        m.put("sobre", DialogoSobre::new);
        m.put("nome", () -> new DialogoNome("Nova pasta", "pasta-mais", "Nome", "Nome", "x", "Criar pasta", t -> null, t -> { }));
        m.put("apagar arquivos", () -> new DialogoApagarArquivos(List.of(new DialogoApagarArquivos.Item("src", true, false)),
            new br.com.labdesktop.arquivos.ResumoDeTamanho(10, 2, 5000, true), ok -> { }));
        m.put("conflito de salvar", () -> new DialogoConflitoDeSalvar("a.txt", false, r -> { }));
        m.put("fechar editor", () -> new DialogoFecharEditor("a.txt", true, r -> { }));
        m.put("limite", () -> new DialogoLimite("app-loja", new Limite(20, 14.0, "ok", false, null, null, null), teto -> { }, t -> { }));
        m.put("confiar no host", () -> new DialogoConfiarHost("lab", "192.0.2.5:2222", sonda(), avisos, x -> { }));
        m.put("registrar token", () -> new DialogoRegistrarToken("lab", "mac-de-teste", false, nome -> { }, nome -> { }, () -> { }, avisos));
        m.put("codigo 2fa", () -> new DialogoCodigo2fa("lab", null, 0, r -> { }));
        m.put("escolher servidor", () -> new DialogoEscolherServidor(false, () -> { }, () -> { }));
        m.put("escolher servidor (este computador ja existe)", () -> new DialogoEscolherServidor(true, () -> { }, () -> { }));
        return m;
    }

    private static JRootPane naJanela(Dialogo d) {
        JRootPane raiz = new JRootPane();
        raiz.setContentPane(d.raiz());
        if (d.botaoDoEnter() != null) {
            raiz.setDefaultButton(d.botaoDoEnter());
        }
        return raiz;
    }

    private static int altura(Dialogo d) {
        return Visual.alturaPreferida(d.raiz(), 560) + 20;
    }

    @Test
    void todoBotaoDeDialogoEnxergaSeNosDoisTemasMontadoETrocado() throws Exception {
        List<String> todas = new ArrayList<>();
        List<String> ruins = new ArrayList<>();
        for (Tema inicial : new Tema[] {Tema.CLARO, Tema.ESCURO}) {
            for (var e : catalogo().entrySet()) {
                Visual.tema(inicial);
                Dialogo d = Visual.naEdt(() -> e.getValue().get());
                JRootPane raiz = Visual.naEdt(() -> naJanela(d));
                int h = altura(d);
                for (Tema agora : new Tema[] {inicial, inicial == Tema.CLARO ? Tema.ESCURO : Tema.CLARO}) {
                    if (agora != inicial) {
                        Visual.tema(agora);
                        Visual.naEdt(() -> SwingUtilities.updateComponentTreeUI(raiz));
                    }
                    List<Medida> medidas = Visual.naEdt(() -> AuditoriaDeCores.auditar(raiz, 560, h, true));
                    String ctx = agora + (agora == inicial ? " montado" : " trocado de " + inicial) + " | " + e.getKey();
                    for (Medida med : medidas) {
                        todas.add(ctx + " | " + med);
                        if (med.falhou()) {
                            ruins.add(ctx + " | " + med);
                        }
                    }
                }
            }
        }
        Files.write(Path.of("build/auditoria-de-cores-dialogos.txt"), todas);
        Files.write(Path.of("build/auditoria-de-cores-dialogos-ruins.txt"), ruins);
        assertTrue(todas.size() > 700, "poucas medidas: " + todas.size());
        assertTrue(ruins.isEmpty(), ruins.size() + " medidas abaixo do limite (as 15 primeiras):\n" + String.join("\n", ruins.stream().limit(15).toList()));
    }
}
