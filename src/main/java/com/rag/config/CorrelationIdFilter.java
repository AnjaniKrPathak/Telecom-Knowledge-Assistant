package com.rag.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Centralized request logging support.
 *
 * <p>Every inbound HTTP request is stamped with a short correlation id
 * (reused from the caller's "X-Request-ID" header if present, otherwise
 * generated). The id is placed in SLF4J's MDC under "requestId" for the
 * lifetime of the request, so every log line written anywhere during that
 * request — controller, service, repository, async task on the same
 * thread — carries "[reqId=xxxxxxxx]" (see logback-spring.xml pattern),
 * making it trivial to grep a single request's full trail out of
 * ./logs/rag-project.log.
 *
 * <p>The id is also echoed back on the response as "X-Request-ID" so
 * clients (or Webex/Postman/etc.) can quote it back when reporting issues.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().substring(0, 8);
        }

        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Always clear MDC — thread gets reused by the servlet container's pool,
            // and a leaked value here would mislabel the next unrelated request.
            MDC.remove(MDC_KEY);
        }
    }
}
