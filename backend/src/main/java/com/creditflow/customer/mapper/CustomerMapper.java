package com.creditflow.customer.mapper;

import com.creditflow.customer.domain.Customer;
import com.creditflow.customer.dto.CustomerRequest;
import com.creditflow.customer.dto.CustomerResponse;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;

import java.math.BigDecimal;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface CustomerMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", expression = "java(request.active() == null || request.active())")
    Customer toEntity(CustomerRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    void updateEntity(CustomerRequest request, @MappingTarget Customer customer);

    @Mapping(target = "fullName", expression = "java(customer.getFullName())")
    @Mapping(target = "salesCount", source = "salesCount")
    @Mapping(target = "totalRemaining", source = "totalRemaining")
    @Mapping(target = "late", source = "late")
    CustomerResponse toResponse(Customer customer, Long salesCount, BigDecimal totalRemaining, boolean late);

    default CustomerResponse toResponse(Customer customer) {
        return toResponse(customer, null, null, false);
    }
}
