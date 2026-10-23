package com.skevenxu.humanoid;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@MapperScan("com.skevenxu.humanoid.mapper")
public class HumanoidApplication {

    public static void main(String[] args) {
        SpringApplication.run(HumanoidApplication.class, args);
    }

}
