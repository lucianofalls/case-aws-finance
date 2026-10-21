package br.com.itau.financas.contabilizacao;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ContabilizacaoApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContabilizacaoApplication.class, args);
    }
}
