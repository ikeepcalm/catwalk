package dev.ua.ikeepcalm.catwalk.hub.network.source;

import dev.ua.ikeepcalm.catwalk.bridge.annotations.BridgeEventHandler;
import io.javalin.openapi.OpenApi;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

@Data
@Slf4j
public class AddonInfo {
    private String name;
    private List<EndpointInfo> endpoints;
    private String openApiSpec;
    private long registeredAt;
    
    public AddonInfo(String name) {
        this.name = name;
        this.endpoints = new ArrayList<>();
        this.registeredAt = System.currentTimeMillis();
    }
    
    public static AddonInfo fromHandlerInstance(String addonName, Object handlerInstance) {
        AddonInfo addonInfo = new AddonInfo(addonName);
        
        Class<?> clazz = handlerInstance.getClass();
        for (Method method : clazz.getDeclaredMethods()) {
            OpenApi openApiAnnotation = method.getAnnotation(OpenApi.class);
            if (openApiAnnotation == null) continue;
            
            BridgeEventHandler bridgeAnnotation = method.getAnnotation(BridgeEventHandler.class);
            
            EndpointInfo endpoint = new EndpointInfo();
            endpoint.setPath(openApiAnnotation.path());
            endpoint.setMethods(openApiAnnotation.methods());
            endpoint.setSummary(openApiAnnotation.summary());
            endpoint.setTags(openApiAnnotation.tags());
            endpoint.setRequiresAuth(bridgeAnnotation == null || bridgeAnnotation.requiresAuth());
            
            addonInfo.endpoints.add(endpoint);
            
            log.debug("[AddonInfo] Found endpoint: {} {} for addon '{}'", 
                     endpoint.getMethods(), endpoint.getPath(), addonName);
        }
        
        return addonInfo;
    }
}