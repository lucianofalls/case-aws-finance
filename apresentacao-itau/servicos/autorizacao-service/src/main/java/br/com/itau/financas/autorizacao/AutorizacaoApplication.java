package br.com.itau.financas.autorizacao;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AutorizacaoApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutorizacaoApplication.class, args);
    }
}
