package br.com.labdesktop.cofre;

import br.com.labdesktop.util.CaminhosApp;
import java.nio.file.Path;

/** Apoio dos testes do cofre. Tudo com o KDF mínimo para a suíte não gastar um segundo por abertura. */
final class Fixtures {

    static final String SENHA = "uma-senha-mestra-de-teste";
    static final String OUTRA_SENHA = "outra-senha-mestra-teste";

    private Fixtures() { }

    static CaminhosApp caminhos(Path tmp) {
        return CaminhosApp.de(tmp.resolve("dados"), tmp.resolve("logs"));
    }

    static char[] senha() {
        return SENHA.toCharArray();
    }

    static CofreDeChaves criar(CaminhosApp c) throws CofreException {
        return CofreDeChaves.criar(c, senha(), ParametrosKdf.MINIMO);
    }

    static CofreDeChaves abrir(CaminhosApp c) throws CofreException {
        return CofreDeChaves.abrir(c, senha(), ParametrosKdf.MINIMO);
    }

    /** Cofre com uma chave, um servidor e um token, já fechado (só o arquivo fica). */
    static ConfigServidor popular(CaminhosApp c) throws CofreException {
        try (CofreDeChaves cofre = criar(c)) {
            ChaveSsh chave = GeradorDeChaves.gerar("lab", "mac-de-teste");
            cofre.adicionarChave(chave);
            ConfigServidor cfg = ConfigServidor.novo("lab", "192.0.2.5", 2222, "labuser", chave.id());
            cofre.adicionarServidor(cfg, null);
            cofre.gravarPin(cfg.id(), new Pin("ssh-ed25519", "SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"));
            cofre.gravarToken(cfg.id(), TokenAgente.gerar(), "mac-de-teste");
            cofre.gravarEstado2fa(cfg.id(), Estado2fa.ATIVO);
            return cofre.servidor(cfg.id()).orElseThrow();
        }
    }
}
