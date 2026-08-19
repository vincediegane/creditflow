package com.creditflow.common.domain;

import com.creditflow.common.security.CurrentUser;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Colonnes techniques communes a toutes les entites metier.
 */
@Getter
@Setter
@MappedSuperclass
public abstract class Auditable {

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "created_by", length = 80)
    private String createdBy;

    @Column(name = "updated_by", length = 80)
    private String updatedBy;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        createdBy = CurrentUser.username();
        updatedBy = createdBy;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
        updatedBy = CurrentUser.username();
    }
}
