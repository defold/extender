package com.defold.extender;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;

@SpringBootApplication
public class ExtenderApplication {
    public static void main(String[] args) throws IOException, InterruptedException {
        SpringApplication.run(ExtenderApplication.class, args);
    }
}

