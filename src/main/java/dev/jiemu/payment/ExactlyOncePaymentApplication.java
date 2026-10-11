package dev.jiemu.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ExactlyOncePaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExactlyOncePaymentApplication.class, args);
    }
}
