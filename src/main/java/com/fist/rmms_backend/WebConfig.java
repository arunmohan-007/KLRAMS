package com.fist.rmms_backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * Serves retained survey videos from the on-disk video folder at the URL /videos/**.
 * e.g. a stored file "road17.mp4" becomes reachable at http://localhost:8090/videos/road17.mp4
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.video-dir:video-store}")
    private String videoDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String abs = Paths.get(videoDir).toAbsolutePath().toString().replace("\\", "/");
        String location = "file:" + abs + "/";
        registry.addResourceHandler("/videos/**").addResourceLocations(location);
    }
}
