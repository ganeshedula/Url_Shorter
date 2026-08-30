package com.url.shortener.exception;

import com.url.shortener.dtos.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidation(MethodArgumentNotValidException exception) {
        List<String> errors = exception.getBindingResult()
            .getFieldErrors()
            .stream()
            .map(this::formatFieldError)
            .toList();
        return ResponseEntity.badRequest().body(ApiResponse.failure("Validation failed", errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Object>> handleConstraintViolation(ConstraintViolationException exception) {
        List<String> errors = exception.getConstraintViolations()
            .stream()
            .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
            .toList();
        return ResponseEntity.badRequest().body(ApiResponse.failure("Validation failed", errors));
    }

    @ExceptionHandler({
        BadRequestException.class,
        DuplicateResourceException.class
    })
    public ResponseEntity<ApiResponse<Object>> handleBadRequest(RuntimeException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.failure(exception.getMessage(), List.of()));
    }

    @ExceptionHandler({UnauthorizedException.class, BadCredentialsException.class, InvalidTokenException.class})
    public ResponseEntity<ApiResponse<Object>> handleUnauthorized(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ApiResponse.failure(exception.getMessage(), List.of()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Object>> handleForbidden(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ApiResponse.failure("Access denied", List.of()));
    }

    @ExceptionHandler({UserNotFoundException.class, UrlNotFoundException.class})
    public ResponseEntity<ApiResponse<Object>> handleNotFound(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiResponse.failure(exception.getMessage(), List.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUnexpected(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.failure("Unexpected server error", List.of(exception.getMessage())));
    }

    private String formatFieldError(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }
}
