package dev.ua.ikeepcalm.catwalk.bridge.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for bridge event handlers with enhanced configuration options.
 * Use this annotation along with OpenApi to configure endpoint behavior.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface BridgeEventHandler {
    /**
     * Specifies required scopes/permissions for the endpoint.
     * Empty by default (no specific scopes required).
     */
    String[] scopes() default {};

    /**
     * Whether this endpoint requires authentication.
     * Default is true (authentication required).
     */
    boolean requiresAuth() default true;

    /**
     * Whether to log all requests to this endpoint.
     * Default is false (no logging).
     */
    boolean logRequests() default false;

    /**
     * Custom rate limit for this endpoint (requests per minute).
     * Default is 0 (no rate limiting).
     */
    int rateLimit() default 0;

    /**
     * Description to be displayed in generated documentation.
     */
    String description() default "";
}