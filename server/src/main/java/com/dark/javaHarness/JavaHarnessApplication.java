package com.dark.javaHarness;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.dark.javaHarness.mapper")
@EnableScheduling
public class JavaHarnessApplication {

    public static void main(String[] args) {
        SpringApplication.run(JavaHarnessApplication.class, args);
    }

}
