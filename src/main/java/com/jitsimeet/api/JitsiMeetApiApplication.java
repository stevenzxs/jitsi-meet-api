package com.jitsimeet.api;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Jitsi Meet 桌面端数据服务启动入口。
 */
@SpringBootApplication
@MapperScan("com.jitsimeet.api.mapper")
public class JitsiMeetApiApplication {

    /**
     * 启动 Spring Boot 应用。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(JitsiMeetApiApplication.class, args);
    }
}
