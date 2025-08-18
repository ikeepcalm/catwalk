package dev.ua.ikeepcalm.catwalk.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for CatwalkAPI functionality.
 * Note: These tests make real HTTP requests to httpbin.org for testing purposes.
 */
class CatwalkAPITest {
    
    private static final String TEST_URL = "https://httpbin.org";
    private static final String TEST_BEARER_TOKEN = "test-token-12345";
    
    @BeforeEach
    void setUp() {
        // Set a test Bearer token
        CatwalkAPI.setBearerToken(TEST_BEARER_TOKEN);
    }
    
    @Test
    void testSetAndGetBearerToken() {
        String testToken = "my-test-token";
        CatwalkAPI.setBearerToken(testToken);
        assertEquals(testToken, CatwalkAPI.getBearerToken());
    }
    
    @Test
    void testSendGet() {
        ApiResponse response = CatwalkAPI.sendGet(TEST_URL + "/get");
        
        assertTrue(response.isSuccess());
        assertEquals(200, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("Bearer " + TEST_BEARER_TOKEN));
    }
    
    @Test
    void testSendGetWithHeaders() {
        Map<String, String> headers = Map.of(
            "Custom-Header", "test-value",
            "Another-Header", "another-value"
        );
        
        ApiResponse response = CatwalkAPI.sendGet(TEST_URL + "/get", headers);
        
        assertTrue(response.isSuccess());
        assertEquals(200, response.getStatusCode());
        assertNotNull(response.getBody());
        
        // Check that our custom headers were sent
        assertTrue(response.getBody().contains("Custom-Header"));
        assertTrue(response.getBody().contains("test-value"));
        assertTrue(response.getBody().contains("Another-Header"));
        assertTrue(response.getBody().contains("another-value"));
    }
    
    @Test
    void testSendPost() {
        String jsonBody = "{\"message\":\"Hello World\",\"number\":42}";
        
        ApiResponse response = CatwalkAPI.sendPost(TEST_URL + "/post", jsonBody);
        
        assertTrue(response.isSuccess());
        assertEquals(200, response.getStatusCode());
        assertNotNull(response.getBody());
        
        // Check that the body was sent correctly
        assertTrue(response.getBody().contains("Hello World"));
        assertTrue(response.getBody().contains("42"));
        assertTrue(response.getBody().contains("Bearer " + TEST_BEARER_TOKEN));
    }
    
    @Test
    void testSendPut() {
        String jsonBody = "{\"updated\":true}";
        
        ApiResponse response = CatwalkAPI.sendPut(TEST_URL + "/put", jsonBody);
        
        assertTrue(response.isSuccess());
        assertEquals(200, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("updated"));
    }
    
    @Test
    void testSendDelete() {
        ApiResponse response = CatwalkAPI.sendDelete(TEST_URL + "/delete");
        
        assertTrue(response.isSuccess());
        assertEquals(200, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("Bearer " + TEST_BEARER_TOKEN));
    }
    
    @Test
    void testSendRequestWithCustomMethod() {
        ApiResponse response = CatwalkAPI.sendRequest(
            TEST_URL + "/patch", 
            "PATCH", 
            "{\"patched\":true}", 
            Map.of("Custom-Header", "patch-test")
        );
        
        assertTrue(response.isSuccess());
        assertEquals(200, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("patched"));
    }
    
    @Test
    void testAsyncGet() throws Exception {
        CompletableFuture<ApiResponse> future = CatwalkAPI.sendGetAsync(TEST_URL + "/get");
        
        ApiResponse response = future.get(10, TimeUnit.SECONDS);
        
        assertTrue(response.isSuccess());
        assertEquals(200, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("Bearer " + TEST_BEARER_TOKEN));
    }
    
    @Test
    void testAsyncPost() throws Exception {
        String jsonBody = "{\"async\":true}";
        CompletableFuture<ApiResponse> future = CatwalkAPI.sendPostAsync(TEST_URL + "/post", jsonBody);
        
        ApiResponse response = future.get(10, TimeUnit.SECONDS);
        
        assertTrue(response.isSuccess());
        assertEquals(200, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("async"));
    }
    
    @Test
    void testInvalidUrl() {
        ApiResponse response = CatwalkAPI.sendGet("invalid-url");
        
        assertEquals(-1, response.getStatusCode());
        assertTrue(response.getBody().contains("Invalid URL"));
        assertFalse(response.isSuccess());
    }
    
    @Test
    void testNetworkError() {
        // Use a non-existent domain to trigger a network error
        ApiResponse response = CatwalkAPI.sendGet("https://this-domain-definitely-does-not-exist-12345.com");
        
        assertEquals(-1, response.getStatusCode());
        assertTrue(response.getBody().contains("Network error"));
        assertFalse(response.isSuccess());
    }
    
    @Test
    void testApiResponseMethods() {
        // Test success response
        ApiResponse successResponse = new ApiResponse(200, "OK", Map.of("Content-Type", java.util.List.of("application/json")));
        assertTrue(successResponse.isSuccess());
        assertFalse(successResponse.isClientError());
        assertFalse(successResponse.isServerError());
        assertEquals("application/json", successResponse.getHeader("Content-Type"));
        assertEquals("application/json", successResponse.getHeader("content-type")); // Case insensitive
        
        // Test client error response
        ApiResponse clientErrorResponse = new ApiResponse(404, "Not Found", Map.of());
        assertFalse(clientErrorResponse.isSuccess());
        assertTrue(clientErrorResponse.isClientError());
        assertFalse(clientErrorResponse.isServerError());
        
        // Test server error response
        ApiResponse serverErrorResponse = new ApiResponse(500, "Internal Server Error", Map.of());
        assertFalse(serverErrorResponse.isSuccess());
        assertFalse(serverErrorResponse.isClientError());
        assertTrue(serverErrorResponse.isServerError());
    }
    
    @Test
    void testWithoutBearerToken() {
        // Test without Bearer token
        CatwalkAPI.setBearerToken(null);
        
        ApiResponse response = CatwalkAPI.sendGet(TEST_URL + "/get");
        
        assertTrue(response.isSuccess());
        assertEquals(200, response.getStatusCode());
        assertNotNull(response.getBody());
        
        // Should not contain any Authorization header in the echoed headers
        assertFalse(response.getBody().contains("Authorization"));
        
        // Reset the token for other tests
        CatwalkAPI.setBearerToken(TEST_BEARER_TOKEN);
    }
}