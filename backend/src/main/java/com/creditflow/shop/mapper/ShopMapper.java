package com.creditflow.shop.mapper;

import com.creditflow.shop.domain.Shop;
import com.creditflow.shop.dto.ShopRequest;
import com.creditflow.shop.dto.ShopResponse;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ShopMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "active", expression = "java(request.active() == null || request.active())")
    Shop toEntity(ShopRequest request);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    void updateEntity(ShopRequest request, @MappingTarget Shop shop);

    ShopResponse toResponse(Shop shop);
}
