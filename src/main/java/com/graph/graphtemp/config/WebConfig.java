package com.graph.graphtemp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Advertise {@code application/json;charset=UTF-8}. The payload was always
     * UTF-8, but a bare {@code application/json} makes Windows PowerShell 5.1's
     * Invoke-RestMethod fall back to ISO-8859-1 and mangle non-ASCII text.
     */
    @Override
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        MediaType jsonUtf8 = new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);
        builder.configureMessageConverters(converter -> {
            if (converter instanceof JacksonJsonHttpMessageConverter jackson) {
                jackson.setSupportedMediaTypes(List.of(jsonUtf8, MediaType.APPLICATION_JSON));
            }
        });
    }

    /**
     * The Next.js dev server calls the API and SSE endpoint straight from the browser.
     * The code endpoint answers in headers rather than a JSON envelope, and a browser
     * hides every response header that is not on the exposed list.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:3000")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Code-Edited", "X-Suggested-Filename");
    }
}
