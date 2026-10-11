package com.lingxi.start;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * 业务域启动入口。
 * M1 骨架：扫描 common 与各域，验证统一响应与全局异常处理链路。
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.lingxi")
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
