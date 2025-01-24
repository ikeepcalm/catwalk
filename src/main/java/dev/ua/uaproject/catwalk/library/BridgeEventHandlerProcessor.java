package dev.ua.uaproject.catwalk.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ua.uaproject.catwalk.CatWalkMain;
import dev.ua.uaproject.catwalk.WebServer;
import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import org.slf4j.Logger;

import java.lang.reflect.Method;

public class BridgeEventHandlerProcessor {

    private final Logger logger;

    public BridgeEventHandlerProcessor(Logger logger) {
        this.logger = logger;
    }

    public void registerHandler(Object handlerInstance) {
        Class<?> clazz = handlerInstance.getClass();
        for (Method method : clazz.getDeclaredMethods()) {
            OpenApi openApiAnnotation = method.getAnnotation(OpenApi.class);
            if (openApiAnnotation == null) continue;

            WebServer webServer = CatWalkMain.instance.getWebServer();

            for (HttpMethod httpMethod : openApiAnnotation.methods()) {
                switch (httpMethod.name()) {
                    case "POST" -> webServer.post(openApiAnnotation.path(), context -> {
                        try {
                            Class<?> paramType = getMethodParamType(method);
                            Object param = deserializeRequestBody(context, paramType);
                            method.invoke(handlerInstance, param);
                        } catch (Exception e) {
                            logger.error("Failed to invoke handler method: {}", method.getName(), e);
                        }
                    });
                    case "GET" -> webServer.get(openApiAnnotation.path(), context -> {
                        try {
                            method.invoke(handlerInstance);
                        } catch (Exception e) {
                            logger.error("Failed to invoke handler method: {}", method.getName(), e);
                        }
                    });
                }
            }
        }
    }


    private Class<?> getMethodParamType(Method method) {
        if (method.getParameterCount() != 1) {
            throw new IllegalArgumentException("Method must have exactly one parameter: " + method.getName());
        }
        return method.getParameterTypes()[0];
    }

    private Object deserializeRequestBody(Context context, Class<?> paramType) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.readValue(context.body(), paramType);
    }

}
