package com.swimpulse.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {
	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(NotFoundException.class)
	public ResponseEntity<ApiError> handleNotFound(NotFoundException exception, HttpServletRequest request) {
		return build(HttpStatus.NOT_FOUND, exception.getMessage(), request, exception);
	}

	@ExceptionHandler(BadRequestException.class)
	public ResponseEntity<ApiError> handleBadRequest(BadRequestException exception, HttpServletRequest request) {
		return build(HttpStatus.BAD_REQUEST, exception.getMessage(), request, exception);
	}

	@ExceptionHandler(TooManyRequestsException.class)
	public ResponseEntity<ApiError> handleTooManyRequests(TooManyRequestsException exception, HttpServletRequest request) {
		return build(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage(), request, exception);
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
		String message = exception.getBindingResult()
				.getFieldErrors()
				.stream()
				.map(this::formatFieldError)
				.collect(Collectors.joining(", "));
		return build(HttpStatus.BAD_REQUEST, message, request, exception);
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ApiError> handleMissingRequestParameter(
			MissingServletRequestParameterException exception,
			HttpServletRequest request
	) {
		return build(HttpStatus.BAD_REQUEST, exception.getParameterName() + " is required", request, exception);
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiError> handleTypeMismatch(
			MethodArgumentTypeMismatchException exception,
			HttpServletRequest request
	) {
		return build(HttpStatus.BAD_REQUEST, exception.getName() + " has invalid value", request, exception);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
		return build(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage(), request, exception);
	}

	private String formatFieldError(FieldError error) {
		return error.getField() + " " + error.getDefaultMessage();
	}

	private ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest request, Exception exception) {
		if (status.is5xxServerError()) {
			log.error("Request failed. method={} uri={} status={} message={}",
					request.getMethod(), request.getRequestURI(), status.value(), message, exception);
		} else {
			log.warn("Request rejected. method={} uri={} status={} message={}",
					request.getMethod(), request.getRequestURI(), status.value(), message);
		}
		return ResponseEntity.status(status)
				.body(ApiError.of(status.value(), status.getReasonPhrase(), message, request.getRequestURI()));
	}
}
