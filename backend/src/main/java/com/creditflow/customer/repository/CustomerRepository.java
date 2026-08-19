package com.creditflow.customer.repository;

import com.creditflow.customer.domain.Customer;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long>, JpaSpecificationExecutor<Customer> {

    @Query("""
            SELECT c FROM Customer c
            WHERE LOWER(CONCAT(c.firstName, ' ', c.lastName)) LIKE LOWER(CONCAT('%', :search, '%'))
               OR c.phone LIKE CONCAT('%', :search, '%')
            ORDER BY c.lastName ASC
            """)
    List<Customer> quickSearch(@Param("search") String search, Pageable pageable);

    Optional<Customer> findByPhone(String phone);

    boolean existsByPhone(String phone);

    boolean existsByPhoneAndIdNot(String phone, Long id);

    boolean existsByCniNumber(String cniNumber);

    boolean existsByCniNumberAndIdNot(String cniNumber, Long id);
}
