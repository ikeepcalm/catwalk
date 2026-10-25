package dev.ua.ikeepcalm.catwalk.bridge.source;

import dev.ua.ikeepcalm.catwalk.bridge.annotations.ApiProperty;
import dev.ua.ikeepcalm.catwalk.bridge.annotations.ApiSchema;
import io.javalin.http.HttpStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ApiSchema(
        description = "Standard API response wrapper for consistent response formatting",
        properties = {
                @ApiProperty(name = "success", type = "boolean", description = "Whether the operation was successful", required = true, example = "true"),
                @ApiProperty(name = "message", type = "string", description = "Human-readable message describing the result", example = "Operation completed successfully"),
                @ApiProperty(name = "data", type = "object", description = "Response data payload")
        }
)
public class BridgeApiResponse<T> {

    public boolean success;
    public String message;
    public T data;
    public transient HttpStatus httpStatus;

    public static <T> BridgeApiResponse<T> success(T data) {
        return success(data, "Operation completed successfully");
    }

    public static <T> BridgeApiResponse<T> success(T data, String message) {
        return new BridgeApiResponse<>(true, message, data, HttpStatus.OK);
    }

    public static <T> BridgeApiResponse<T> success(String message) {
        return new BridgeApiResponse<>(true, message, null, HttpStatus.OK);
    }

    public static <T> BridgeApiResponse<T> error(String message) {
        return error(message, HttpStatus.BAD_REQUEST);
    }

    public static <T> BridgeApiResponse<T> error(String message, HttpStatus status) {
        return new BridgeApiResponse<>(false, message, null, status);
    }

    public static <T> BridgeApiResponse<T> error(String message, T data, HttpStatus status) {
        return new BridgeApiResponse<>(false, message, data, status);
    }
}
