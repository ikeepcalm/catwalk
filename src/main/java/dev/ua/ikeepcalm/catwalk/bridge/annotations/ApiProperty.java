package dev.ua.ikeepcalm.catwalk.bridge.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface ApiProperty {
    String name();
    String type() default "string";
    String description() default "";
    boolean required() default false;
    String example() default "";
}