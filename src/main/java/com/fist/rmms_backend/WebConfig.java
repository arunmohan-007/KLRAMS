package com.fist.rmms_backend;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
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

        /* HTML pages must always revalidate: they are the entry points that carry
           the ?v= cache-busting numbers for JS/CSS. The global 30d max-age in
           application.properties would otherwise let a browser keep an old
           map.html (pointing at old ?v= files) for a month, so users could keep
           seeing stale modules even after a deploy. This more-specific pattern
           wins over the default /** handler; JS/CSS/images stay on the 30d rule. */
        registry.addResourceHandler("/*.html")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache());
    }
}
