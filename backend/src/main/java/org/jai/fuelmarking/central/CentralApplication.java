package org.jai.fuelmarking.central;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class CentralApplication {
    public static void main(String[] args) {
        SpringApplication.run(CentralApplication.class, args);
    }
}
