package com.corebank.customer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdminSeeder {

    @Bean
    ApplicationRunner seedAdmin(CustomerRepository customers, CustomerService service,
                                @Value("${corebank.admin.email}") String email,
                                @Value("${corebank.admin.password}") String password) {
        return args -> {
            if (!customers.existsByEmail(email)) {
                service.register("CoreBank Admin", email, null, password, "ADMIN");
            }
        };
    }
}
