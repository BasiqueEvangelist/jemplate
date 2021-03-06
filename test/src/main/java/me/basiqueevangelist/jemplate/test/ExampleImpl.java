package me.basiqueevangelist.jemplate.test;

import me.basiqueevangelist.jemplate.core.api.InlineParam;
import me.basiqueevangelist.jemplate.core.api.Jemplate;

/**
 * It's the class!
 */
@Jemplate
public class ExampleImpl {
    private final String a;
    private final int b;

    /**
     * ConstructorÂ©
     * @param a non-inlined param
     * @param b inlined param
     */
    public ExampleImpl(String a, @InlineParam int b) {
        this.a = a;
        this.b = b;
    }

    /**
     * The javadocâ„¢
     * @param a an int
     * @return something
     */
    public String something(int a) {
        if (a > 0) {
            System.out.println("e");
        }

        return a + this.a + this.b;
    }
}
