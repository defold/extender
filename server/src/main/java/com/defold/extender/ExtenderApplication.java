package com.defold.extender;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.core.env.Environment;

@SpringBootApplication
public class ExtenderApplication {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExtenderApplication.class);

    private final Environment environment;

    @Autowired
    public ExtenderApplication(Environment environment) {
        this.environment = environment;
    }

    public static void main(String[] args) {
        SpringApplication.run(ExtenderApplication.class, args);
    }
}
