package br.com.labdesktop.cofre;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * O conteúdo do cofre aberto, como o app o enxerga: listas imutáveis (cópia na escrita), então um leitor
 * nunca vê o cofre pela metade. Cada mudança cria um objeto novo, que o {@link CofreDeChaves} grava no disco
 * ANTES de passar a valer: se a gravação falhar, nada mudou.
 *
 * <p>Classe comum, não {@code record}: segura {@link ChaveSsh} e {@link TokenAgente}.
 */
final class ConteudoEmMemoria {

    /** Um servidor e o token dele (que pode não existir ainda). Não é record: segura segredo. */
    static final class ServidorGuardado {
        final ConfigServidor config;
        final TokenAgente token;
        /** Como falar com o agente que o app instalou neste servidor; {@code null} se nunca instalou. Não é segredo. */
        final AgenteInstalado agente;

        ServidorGuardado(ConfigServidor config, TokenAgente token) {
            this(config, token, null);
        }

        ServidorGuardado(ConfigServidor config, TokenAgente token, AgenteInstalado agente) {
            this.config = config;
            this.token = token;
            this.agente = agente;
        }

        ServidorGuardado comConfig(ConfigServidor nova) {
            return new ServidorGuardado(nova, token, agente);
        }

        ServidorGuardado comToken(TokenAgente novo) {
            return new ServidorGuardado(config, novo, agente);
        }

        ServidorGuardado comAgente(AgenteInstalado novo) {
            return new ServidorGuardado(config, token, novo);
        }

        @Override
        public String toString() {
            return "ServidorGuardado[" + config.id() + ", token=redigido]";
        }
    }

    final List<ChaveSsh> chaves;
    final List<ServidorGuardado> servidores;

    ConteudoEmMemoria(List<ChaveSsh> chaves, List<ServidorGuardado> servidores) {
        this.chaves = List.copyOf(chaves);
        this.servidores = List.copyOf(servidores);
    }

    static ConteudoEmMemoria vazio() {
        return new ConteudoEmMemoria(List.of(), List.of());
    }

    Optional<ChaveSsh> chave(String id) {
        return chaves.stream().filter(c -> c.id().equals(id)).findFirst();
    }

    Optional<ServidorGuardado> servidor(String id) {
        return servidores.stream().filter(s -> s.config.id().equals(id)).findFirst();
    }

    ConteudoEmMemoria comChave(ChaveSsh nova) {
        List<ChaveSsh> lista = new ArrayList<>(chaves);
        lista.add(nova);
        return new ConteudoEmMemoria(lista, servidores);
    }

    ConteudoEmMemoria semChave(String id) {
        List<ChaveSsh> lista = new ArrayList<>(chaves);
        lista.removeIf(c -> c.id().equals(id));
        return new ConteudoEmMemoria(lista, servidores);
    }

    ConteudoEmMemoria comServidor(ServidorGuardado novo) {
        List<ServidorGuardado> lista = new ArrayList<>(servidores);
        lista.add(novo);
        return new ConteudoEmMemoria(chaves, lista);
    }

    ConteudoEmMemoria semServidor(String id) {
        List<ServidorGuardado> lista = new ArrayList<>(servidores);
        lista.removeIf(s -> s.config.id().equals(id));
        return new ConteudoEmMemoria(chaves, lista);
    }

    /** Troca o servidor de mesmo id, mantendo a posição. */
    ConteudoEmMemoria substituindoServidor(ServidorGuardado novo) {
        List<ServidorGuardado> lista = new ArrayList<>(servidores.size());
        for (ServidorGuardado s : servidores) {
            lista.add(s.config.id().equals(novo.config.id()) ? novo : s);
        }
        return new ConteudoEmMemoria(chaves, lista);
    }

    /** Zera todos os segredos. */
    void zerar() {
        for (ChaveSsh c : chaves) {
            c.zerar();
        }
        for (ServidorGuardado s : servidores) {
            if (s.token != null) {
                s.token.zerar();
            }
        }
    }

    boolean temChaveInstancia(ChaveSsh c) {
        for (ChaveSsh x : chaves) {
            if (x == c) {
                return true;
            }
        }
        return false;
    }

    boolean temTokenInstancia(TokenAgente t) {
        for (ServidorGuardado s : servidores) {
            if (s.token == t) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "ConteudoEmMemoria[chaves=" + chaves.size() + ", servidores=" + servidores.size() + "]";
    }
}
