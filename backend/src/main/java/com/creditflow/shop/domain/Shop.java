package com.creditflow.shop.domain;

import com.creditflow.common.domain.Auditable;
import com.creditflow.organization.domain.Organization;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "shops")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Shop extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 120)
    private String name;

    @Column(length = 255)
    private String address;

    @Column(length = 30)
    private String phone;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;
}
