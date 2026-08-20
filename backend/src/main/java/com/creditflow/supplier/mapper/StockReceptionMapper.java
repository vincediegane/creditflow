package com.creditflow.supplier.mapper;

import com.creditflow.supplier.domain.StockReception;
import com.creditflow.supplier.domain.StockReceptionLine;
import com.creditflow.supplier.dto.StockReceptionResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface StockReceptionMapper {

    @Mapping(target = "supplierId", source = "supplier.id")
    @Mapping(target = "supplierName", source = "supplier.name")
    StockReceptionResponse toResponse(StockReception reception);

    @Mapping(target = "productId", source = "product.id")
    @Mapping(target = "productName", source = "product.name")
    StockReceptionResponse.StockReceptionLineResponse toResponse(StockReceptionLine line);
}
