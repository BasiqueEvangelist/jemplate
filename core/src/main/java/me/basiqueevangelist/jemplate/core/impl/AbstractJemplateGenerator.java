package me.basiqueevangelist.jemplate.core.impl;

import me.basiqueevangelist.jemplate.core.api.ClassDefiner;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <p>Used by the Jemplate AP to reduce boilerplate.
 *
 * <p><b>For internal use only!</b>
 */
public abstract class AbstractJemplateGenerator {
    protected final ClassDefiner definer;
    private final AtomicInteger maxId = new AtomicInteger(0);
    private final String generatorPrefix;

    public AbstractJemplateGenerator(ClassDefiner definer) {
        this.definer = definer;
        this.generatorPrefix = getClass().getPackageName() + ".generated." + getClass().getSimpleName() + "Instance";
    }

    protected final String mintInstanceName() {
        return generatorPrefix + maxId.incrementAndGet();
    }

    protected abstract Class<?>[] getInlinedFieldTypes();

    protected final Object finalizeInstanceSetup(Class<?> instance, Object... inlinedParams) {
        try {
            Method m = instance.getDeclaredMethod("_finishSetup", getInlinedFieldTypes());
            return m.invoke(null, inlinedParams);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException("Could not finish setup for instance", e);
        }
    }
}
