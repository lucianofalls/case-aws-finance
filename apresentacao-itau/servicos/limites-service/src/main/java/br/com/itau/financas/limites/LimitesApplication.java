package br.com.itau.financas.limites;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LimitesApplication {

    public static void main(String[] args) {
        SpringApplication.run(LimitesApplication.class, args);
    }
}
