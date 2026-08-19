package com.creditflow.customer.domain;

import com.creditflow.common.domain.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "customers")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Customer extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "first_name", nullable = false, length = 80)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 80)
    private String lastName;

    @Column(nullable = false, unique = true, length = 30)
    private String phone;

    @Column(length = 255)
    private String address;

    @Column(name = "cni_number", unique = true, length = 50)
    private String cniNumber;

    @Column(length = 120)
    private String profession;

    @Column(name = "photo_url", length = 255)
    private String photoUrl;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    public String getFullName() {
        return "%s %s".formatted(firstName, lastName);
    }
}
