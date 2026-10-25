package dev.ua.ikeepcalm.catwalk.bridge.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface BridgeEventHandler {

    String[] scopes() default {};

    boolean requiresAuth() default true;

    boolean logRequests() default false;

    int rateLimit() default 0;

    boolean mainThread() default false;

    String description() default "";
}
