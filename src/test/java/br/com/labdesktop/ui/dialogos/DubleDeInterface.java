package br.com.labdesktop.ui.dialogos;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Um dublê para a interface que um diálogo recebe (o "motor" dele): responde "nada" a tudo (vazio, falso, zero, lista vazia, texto vazio). Serve para montar o diálogo só
 * para MEDIR as cores do que ele desenha, sem servidor nem cofre.
 */
final class DubleDeInterface {

    private DubleDeInterface() { }

    @SuppressWarnings("unchecked")
    static <T> T de(Class<T> tipo) {
        return (T) Proxy.newProxyInstance(tipo.getClassLoader(), new Class<?>[] {tipo}, (proxy, metodo, args) -> {
            Class<?> r = metodo.getReturnType();
            if (metodo.isDefault()) {
                return java.lang.invoke.MethodHandles.privateLookupIn(tipo, java.lang.invoke.MethodHandles.lookup()).unreflectSpecial(metodo, tipo).bindTo(proxy).invokeWithArguments(args == null ? new Object[0] : args);
            }
            if (r == boolean.class) {
                return false;
            }
            if (r == int.class) {
                return 0;
            }
            if (r == long.class) {
                return 0L;
            }
            if (r == double.class) {
                return 0.0;
            }
            if (r == String.class) {
                return "";
            }
            if (r == Optional.class) {
                return Optional.empty();
            }
            if (r == List.class) {
                return List.of();
            }
            if (r == Set.class) {
                return Set.of();
            }
            if (r == Map.class) {
                return Map.of();
            }
            if (r == Duration.class) {
                return Duration.ZERO;
            }
            if (r == CompletableFuture.class) {
                return new CompletableFuture<>();
            }
            if (r.isEnum() && r.getEnumConstants().length > 0) {
                return r.getEnumConstants()[0];
            }
            return null;
        });
    }
}
