package com.jitsimeet.api.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 应用基础配置，启用服务端 JWT 签发所需的配置绑定。
 */
@Configuration
@EnableConfigurationProperties(JitsiTokenProperties.class)
public class AppConfig {
}
