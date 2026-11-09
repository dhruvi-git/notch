package dev.notch.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Lets the web app (and later the extensions) call the API from their own origins. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final NotchProperties props;

    public WebConfig(NotchProperties props) {
        this.props = props;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(props.cors().allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
