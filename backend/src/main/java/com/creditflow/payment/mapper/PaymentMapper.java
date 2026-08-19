package com.creditflow.payment.mapper;

import com.creditflow.payment.domain.Payment;
import com.creditflow.payment.dto.PaymentResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface PaymentMapper {

    @Mapping(target = "saleId", source = "sale.id")
    @Mapping(target = "saleReference", source = "sale.reference")
    @Mapping(target = "customerId", source = "sale.customer.id")
    @Mapping(target = "customerName", expression = "java(payment.getSale().getCustomer().getFullName())")
    @Mapping(target = "customerPhone", source = "sale.customer.phone")
    @Mapping(target = "productName", source = "sale.product.name")
    @Mapping(target = "saleRemainingAmount", source = "sale.remainingAmount")
    PaymentResponse toResponse(Payment payment);
}
