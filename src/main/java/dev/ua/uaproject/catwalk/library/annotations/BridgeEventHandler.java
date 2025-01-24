package dev.ua.uaproject.catwalk.library.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface BridgeEventHandler {
    String[] scopes() default {};
}
