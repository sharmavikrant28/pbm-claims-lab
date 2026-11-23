package dev.pbmlab.claims;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PbmClaimsLabApplication {
    public static void main(String[] args) {
        SpringApplication.run(PbmClaimsLabApplication.class, args);
    }
}
