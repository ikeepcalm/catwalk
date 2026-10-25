package dev.ua.ikeepcalm.catwalk.network;

import dev.ua.ikeepcalm.catwalk.common.database.model.NetworkRequest;
import dev.ua.ikeepcalm.catwalk.common.database.model.NetworkResponse;
import dev.ua.ikeepcalm.catwalk.common.utils.QueueSignature;
import dev.ua.ikeepcalm.catwalk.hub.network.RequestProcessor;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueueSignatureTest {

    private static final String KEY = "shared-network-key-123456";

    private static NetworkRequest request(String path) {
        return NetworkRequest.builder()
                .requestId("id-1")
                .targetServerId("lobby")
                .httpMethod(NetworkRequest.HttpMethod.POST)
                .endpointPath(path)
                .queryParams(Map.of("b", "2", "a", "1"))
                .body("{\"amount\":5}")
                .build();
    }

    @Test
    void tamperedRequestsDoNotMatchTheHubSignature() {
        String signature = RequestProcessor.requestSignature(KEY, request("/shop/buy"), "1000");

        assertTrue(signature.equals(RequestProcessor.requestSignature(KEY, request("/shop/buy"), "1000")));
        assertNotEquals(signature, RequestProcessor.requestSignature(KEY, request("@evil.example/x"), "1000"));
        assertNotEquals(signature, RequestProcessor.requestSignature(KEY, request("/shop/buy"), "2000"));
        assertNotEquals(signature, RequestProcessor.requestSignature("another-key-123456789", request("/shop/buy"), "1000"));
    }

    @Test
    void responsesAreOnlyAcceptedWithAValidSignature() {
        NetworkResponse response = NetworkResponse.builder()
                .requestId("id-1").serverId("lobby").statusCode(200).contentType("application/json").body("{}")
                .headers(new HashMap<>())
                .build();
        assertFalse(RequestProcessor.hasValidSignature(KEY, response));

        response.getHeaders().put(QueueSignature.SIGNATURE_HEADER,
                QueueSignature.sign(KEY, "id-1", "lobby", "200", "application/json", "{}"));
        assertTrue(RequestProcessor.hasValidSignature(KEY, response));

        response.setBody("<script>alert(1)</script>");
        assertFalse(RequestProcessor.hasValidSignature(KEY, response));
    }
}
