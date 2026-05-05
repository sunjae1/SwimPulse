package com.swimpulse.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
	@ExceptionHandler(NotFoundException.class)
	public ResponseEntity<ApiError> handleNotFound(NotFoundException exception, HttpServletRequest request) {
		return build(HttpStatus.NOT_FOUND, exception.getMessage(), request);
	}

	@ExceptionHandler(BadRequestException.class)
	public ResponseEntity<ApiError> handleBadRequest(BadRequestException exception, HttpServletRequest request) {
		return build(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
		String message = exception.getBindingResult()
				.getFieldErrors()
				.stream()
				.map(this::formatFieldError)
				.collect(Collectors.joining(", "));
		return build(HttpStatus.BAD_REQUEST, message, request);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
		return build(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage(), request);
	}

	private String formatFieldError(FieldError error) {
		return error.getField() + " " + error.getDefaultMessage();
	}

	private ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest request) {
		return ResponseEntity.status(status)
				.body(ApiError.of(status.value(), status.getReasonPhrase(), message, request.getRequestURI()));
	}
}
