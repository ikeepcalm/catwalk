package dev.ua.ikeepcalm.catwalk.bridge.source;

import dev.ua.ikeepcalm.catwalk.bridge.annotations.ApiProperty;
import dev.ua.ikeepcalm.catwalk.bridge.annotations.ApiSchema;
import io.javalin.http.HttpStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Standard API response wrapper for consistent response formatting across all endpoints.
 * This class provides a standardized structure for all API responses, including success status,
 * message, data payload, and HTTP status code.
 *
 * @param <T> The type of data being returned in the response
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiSchema(
        description = "Standard API response wrapper for consistent response formatting",
        properties = {
                @ApiProperty(
                        name = "success",
                        type = "boolean",
                        description = "Whether the operation was successful",
                        required = true,
                        example = "true"
                ),
                @ApiProperty(
                        name = "message",
                        type = "string",
                        description = "Human-readable message describing the result",
                        required = false,
                        example = "Operation completed successfully"
                ),
                @ApiProperty(
                        name = "data",
                        type = "object",
                        description = "Response data payload",
                        required = false
                )
        }
)
public class BridgeApiResponse<T> {

    /**
     * Indicates whether the operation was successful
     * True for successful operations, false for errors
     */
    public boolean success;

    /**
     * Human-readable message describing the result of the operation
     * For successful operations, this typically confirms the action
     * For errors, this explains what went wrong
     */
    public String message;

    /**
     * The payload of the response, containing the requested data or error details
     * May be null for operations that don't return data
     */
    public T data;

    /**
     * Creates a successful response with data and a default message
     */
    public static <T> BridgeApiResponse<T> success(T data) {
        return success(data, "Operation completed successfully");
    }

    /**
     * Creates a successful response with data and a custom message
     */
    public static <T> BridgeApiResponse<T> success(T data, String message) {
        return BridgeApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .build();
    }

    /**
     * Creates a successful response with just a message and no data
     */
    public static <T> BridgeApiResponse<T> success(String message) {
        return BridgeApiResponse.<T>builder()
                .success(true)
                .message(message)
                .build();
    }

    /**
     * Creates an error response with a message and default status code
     */
    public static <T> BridgeApiResponse<T> error(String message) {
        return error(message, HttpStatus.BAD_REQUEST);
    }

    /**
     * Creates an error response with a message and custom status code
     */
    public static <T> BridgeApiResponse<T> error(String message, HttpStatus status) {
        return BridgeApiResponse.<T>builder()
                .success(false)
                .message(message)
                .build();
    }

    /**
     * Creates an error response with a message, data, and custom status code
     */
    public static <T> BridgeApiResponse<T> error(String message, T data, HttpStatus status) {
        return BridgeApiResponse.<T>builder()
                .success(false)
                .message(message)
                .data(data)
                .build();
    }
}
