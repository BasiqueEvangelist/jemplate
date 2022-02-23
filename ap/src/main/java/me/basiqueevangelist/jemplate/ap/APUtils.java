package me.basiqueevangelist.jemplate.ap;

import com.squareup.javapoet.ClassName;

public final class APUtils {
    private APUtils() {

    }

    public static ClassName resolveName(ClassName from, String name) {
        return name.contains(".") ? ClassName.bestGuess(name) : from.peerClass(name);
    }
}
