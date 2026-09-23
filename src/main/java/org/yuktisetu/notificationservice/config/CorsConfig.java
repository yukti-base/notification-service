package org.yuktisetu.notificationservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.yuktisetu.core.web.YuktiSetuCorsSource;

/**
 * Delegates to the shared policy in core rather than hand-copying the origin /
 * method / header lists a fifth time -- see YuktiSetuCorsSource's javadoc. The
 * portals call the bell endpoints straight from the browser, so this service
 * needs the policy like any other.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        return YuktiSetuCorsSource.defaultSource();
    }
}
