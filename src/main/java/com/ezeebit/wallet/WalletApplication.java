package com.ezeebit.wallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.util.TimeZone;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WalletApplication {

    public static void main(String[] args) {
        // All timestamps are UTC end to end (JVM, JDBC session, Hibernate).
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(WalletApplication.class, args);
    }
}
