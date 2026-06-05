package com.swimpulse.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {
	private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
	private static final String REQUEST_ID_HEADER = "X-Request-Id";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String requestId = resolveRequestId(request);
		MDC.put("requestId", requestId);
		response.setHeader(REQUEST_ID_HEADER, requestId);

		long startedAt = System.nanoTime();
		try {
			filterChain.doFilter(request, response);
		} finally {
			long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
			log.info("HTTP {} {} -> {} {}ms", request.getMethod(), request.getRequestURI(), response.getStatus(), elapsedMs);
			MDC.clear();
		}
	}

	private String resolveRequestId(HttpServletRequest request) {
		String headerValue = request.getHeader(REQUEST_ID_HEADER);
		if (headerValue != null && !headerValue.isBlank()) {
			return headerValue.trim();
		}
		return UUID.randomUUID().toString().substring(0, 8);
	}
}
