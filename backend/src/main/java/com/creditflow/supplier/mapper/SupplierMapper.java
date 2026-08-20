package com.creditflow.supplier.mapper;

import com.creditflow.supplier.domain.Supplier;
import com.creditflow.supplier.dto.SupplierRequest;
import com.creditflow.supplier.dto.SupplierResponse;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface SupplierMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", expression = "java(request.active() == null || request.active())")
    Supplier toEntity(SupplierRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    void updateEntity(SupplierRequest request, @MappingTarget Supplier supplier);

    SupplierResponse toResponse(Supplier supplier);
}
