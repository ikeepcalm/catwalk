package dev.ua.uaproject.catwalk.library;

import dev.ua.uaproject.catwalk.library.annotations.BridgeEventHandler;
import io.javalin.Javalin;
import io.javalin.http.HandlerType;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import org.slf4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class BridgeEventHandlerProcessor {

    private final Logger logger;
    private final Javalin javalin;

    public BridgeEventHandlerProcessor(Logger logger, Javalin javalin) {
        this.logger = logger;
        this.javalin = javalin;
    }

    public void registerHandlers(Object handlerInstance) {
        Class<?> clazz = handlerInstance.getClass();
        for (Method method : clazz.getDeclaredMethods()) {
            OpenApi openApiAnnotation = method.getAnnotation(OpenApi.class);
            BridgeEventHandler bridgeEventHandlerAnnotation = method.getAnnotation(BridgeEventHandler.class);

            if (openApiAnnotation == null) {
                if (bridgeEventHandlerAnnotation != null) {
                    logger.warn("Bridge event handler annotation found but OpenApi is missing.");
                }
                continue;
            }

            for (HttpMethod httpMethod : openApiAnnotation.methods()) {
                try {
                    HandlerType handlerType = HandlerType.valueOf(httpMethod.name());
                    if (handlerType.isHttpMethod()) {
                        javalin.addHandler(handlerType, openApiAnnotation.path(), context -> {
                            try {
                                method.invoke(handlerInstance, context, httpMethod);
                            } catch (IllegalAccessException | InvocationTargetException e) {
                                logger.error("Failed to invoke handler method: {}", method.getName(), e);
                            }
                        });
                    } else {
                        logger.warn("Unsupported HTTP method: {}", httpMethod.name());
                    }
                } catch (IllegalArgumentException e) {
                    logger.error("Invalid HTTP method: {}", httpMethod.name(), e);
                }
            }
        }
    }

}
