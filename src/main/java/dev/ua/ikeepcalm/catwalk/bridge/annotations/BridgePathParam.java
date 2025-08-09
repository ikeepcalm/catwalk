package dev.ua.ikeepcalm.catwalk.bridge.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for marking method parameters that should receive values from path parameters.
 * Use on method parameters to indicate they should be populated from URL path parameters.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface BridgePathParam {
    /**
     * The name of the path parameter to bind to this method parameter.
     * This should match the path parameter name in the URL pattern.
     * For example, if the URL is "/users/{userId}", the path parameter name would be "userId".
     */
    String value();
}
