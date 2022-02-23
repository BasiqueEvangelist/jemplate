package me.basiqueevangelist.jemplate.core.impl;

/**
 * <p>Annotation used to mark generator classes for further processing.
 *
 * <p><b>For internal use only!</b>
 */
public @interface JemplateGenerator {
    String implName();
    String interfaceName();
}