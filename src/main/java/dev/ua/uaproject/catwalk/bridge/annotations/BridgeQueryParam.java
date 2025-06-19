package dev.ua.uaproject.catwalk.bridge.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for marking method parameters that should receive values from query parameters.
 * Use on method parameters to indicate they should be populated from URL query parameters.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface BridgeQueryParam {
    /**
     * The name of the query parameter to bind to this method parameter.
     * For example, if the URL is "/api/users?page=1&limit=10", 
     * the query parameter names would be "page" and "limit".
     */
    String value();

    /**
     * Whether this query parameter is required.
     * If true, the request will fail if the parameter is missing.
     * Default is false (optional parameter).
     */
    boolean required() default false;

    /**
     * Default value to use if the parameter is not provided.
     * Only applicable when required = false.
     */
    String defaultValue() default "";
}