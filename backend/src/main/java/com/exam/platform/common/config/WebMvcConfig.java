package com.exam.platform.common.config;

import com.exam.platform.common.ratelimit.TokenBucketRateLimitInterceptor;
import com.exam.platform.common.security.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Web 层配置：登录拦截（含管理端角色校验）+ 限流 + 跨域。 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    /** 无需登录即可访问的接口。 */
    private static final String[] PUBLIC_PATHS = {
            "/v1/auth/**",
            "/v1/payments/wechat/notify",
            "/v1/wx/**",
            "/v1/dev/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/h2-console/**",
            "/error"
    };

    private final AuthInterceptor authInterceptor;
    private final TokenBucketRateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/v1/**")
                .excludePathPatterns(PUBLIC_PATHS)
                .order(0);
        // 限流必须晚于鉴权：先有登录主体，才能按用户维度计数
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/v1/**")
                .excludePathPatterns("/v1/dev/**", "/swagger-ui/**", "/swagger-ui.html",
                        "/v3/api-docs/**", "/h2-console/**", "/error")
                .order(10);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Trace-Id")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
