package dev.ua.ikeepcalm.catwalk.bridge.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for marking method parameters that should receive the request body.
 * Use on method parameters to indicate they should be populated from the HTTP request body.
 * The request body will be automatically deserialized based on the Content-Type header.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface BridgeRequestBody {
    /**
     * Whether the request body is required.
     * If true, the request will fail if the body is missing or empty.
     * Default is true (required).
     */
    boolean required() default true;

    /**
     * Expected content type for the request body.
     * If specified, the request will be validated against this content type.
     * Common values: "application/json", "application/xml", "text/plain"
     * Empty string means any content type is accepted.
     */
    String contentType() default "";
}