package org.cardanofoundation.lob.app.support.spring_web;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class RequestCachingFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpServletRequest) {
            String contentType = httpServletRequest.getContentType();

            // Don't wrap multipart requests
            if (contentType == null || !contentType.toLowerCase().startsWith("multipart/")) {
                CustomHttpServletRequestWrapper wrappedRequest = new CustomHttpServletRequestWrapper(httpServletRequest);
                chain.doFilter(wrappedRequest, response);
                return;
            }
        }

        // Proceed without wrapping
        chain.doFilter(request, response);
    }
}
