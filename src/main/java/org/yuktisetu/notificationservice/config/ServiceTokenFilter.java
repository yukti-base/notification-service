package org.yuktisetu.notificationservice.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Authenticates a calling service on POST /dispatch.
 *
 * Dispatch is not a user action. auth-service sending a role invite, or
 * drive-service announcing a published drive, has no single student or admin
 * whose JWT naturally authorises "mail 2000 people" -- and forwarding an
 * admin's token would mean any role able to publish a drive is also able to
 * mail arbitrary recipients directly. A shared service credential keeps the
 * blast door shut to end users entirely.
 *
 * Compared with constant time, so a wrong token cannot be recovered a byte at a
 * time from response timing.
 */
@Slf4j
public class ServiceTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Service-Token";
    public static final String SERVICE_AUTHORITY = "ROLE_SERVICE";

    private final byte[] expected;

    public ServiceTokenFilter(String expectedToken) {
        this.expected = expectedToken == null ? new byte[0] : expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String presented = request.getHeader(HEADER);

        if (presented != null && expected.length > 0
                && MessageDigest.isEqual(expected, presented.getBytes(StandardCharsets.UTF_8))) {

            var auth = new UsernamePasswordAuthenticationToken(
                    "service", null, List.of(new SimpleGrantedAuthority(SERVICE_AUTHORITY)));
            SecurityContextHolder.getContext().setAuthentication(auth);

        } else if (presented != null) {
            log.warn("Rejected service token on {} {}", request.getMethod(), request.getRequestURI());
        }

        chain.doFilter(request, response);
    }
}
