package me.basiqueevangelist.jemplate.core.api;

import java.lang.annotation.*;

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.CLASS)
@Documented
public @interface InlineParam {
}
