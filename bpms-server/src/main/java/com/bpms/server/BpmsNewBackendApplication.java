package com.bpms.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.bpms")
public class BpmsNewBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BpmsNewBackendApplication.class, args);
    }
}