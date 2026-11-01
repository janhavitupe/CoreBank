package com.corebank.customer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

final class Repositories {

    private Repositories() {
    }

    interface CustomerRepository extends JpaRepository<Customer, UUID> {
        Optional<Customer> findByEmail(String email);

        boolean existsByEmail(String email);
    }

    interface CredentialRepository extends JpaRepository<Credential, UUID> {
    }
}
