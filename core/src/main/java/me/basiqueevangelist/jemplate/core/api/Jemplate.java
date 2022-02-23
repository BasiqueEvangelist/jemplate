package me.basiqueevangelist.jemplate.core.api;

import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
@Documented
public @interface Jemplate {
    String interfaceName() default "";
    String generatorName() default "";
}
