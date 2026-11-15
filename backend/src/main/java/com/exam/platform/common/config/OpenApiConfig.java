package com.exam.platform.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI 文档：启动后访问 /api/swagger-ui.html。 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI examPlatformOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("综合考试平台 API")
                        .version("v1")
                        .description("单体后台服务接口文档，Base URL：/api/v1"))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
