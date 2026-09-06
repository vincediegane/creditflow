package com.creditflow.sale.service;

import com.creditflow.common.dto.PageResponse;
import com.creditflow.common.exception.ResourceNotFoundException;
import com.creditflow.common.repository.Specs;
import com.creditflow.common.security.CurrentShopContext;
import com.creditflow.penalty.domain.PenaltySettings;
import com.creditflow.penalty.service.PenaltySettingsService;
import com.creditflow.sale.domain.CreditSale;
import com.creditflow.sale.domain.Installment;
import com.creditflow.sale.domain.InstallmentStatus;
import com.creditflow.sale.dto.InstallmentResponse;
import com.creditflow.sale.mapper.SaleMapper;
import com.creditflow.sale.repository.CreditSaleRepository;
import com.creditflow.sale.repository.InstallmentRepository;
import com.creditflow.sale.repository.InstallmentSpecifications;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class InstallmentService {

    private final InstallmentRepository installmentRepository;
    private final CreditSaleRepository saleRepository;
    private final SaleMapper saleMapper;
    private final PenaltySettingsService penaltySettingsService;
    private final CurrentShopContext currentShopContext;

    @Transactional(readOnly = true)
    public PageResponse<InstallmentResponse> search(String search, InstallmentStatus status,
                                                    LocalDate from, LocalDate to, boolean onlyLate,
                                                    Pageable pageable) {
        LocalDate today = LocalDate.now();
        PenaltySettings settings = penaltySettingsService.current();
        Page<Installment> page = installmentRepository.findAll(
                Specs.combine(
                        InstallmentSpecifications.matches(search),
                        InstallmentSpecifications.hasStatus(status),
                        InstallmentSpecifications.dueFrom(from),
                        InstallmentSpecifications.dueTo(to),
                        InstallmentSpecifications.lateOn(onlyLate, today),
                        InstallmentSpecifications.inShops(currentShopContext.accessibleShopIds()),
                        InstallmentSpecifications.inOrganization(currentShopContext.currentOrganizationId())),
                pageable);
        return PageResponse.of(page, installment -> saleMapper.toResponse(installment, today, settings));
    }

    @Transactional(readOnly = true)
    public List<InstallmentResponse> upcoming(int days) {
        LocalDate today = LocalDate.now();
        PenaltySettings settings = penaltySettingsService.current();
        return installmentRepository.findUpcomingForShops(today, today.plusDays(days),
                        currentShopContext.accessibleShopIds(), currentShopContext.currentOrganizationId())
                .stream()
                .map(installment -> saleMapper.toResponse(installment, today, settings))
                .toList();
    }

    /** Reservee au tableau de bord, qui resout ses propres boutiques via resolveReadFilter(). */
    @Transactional(readOnly = true)
    public List<InstallmentResponse> upcomingForShops(int days, List<Long> shopIds) {
        LocalDate today = LocalDate.now();
        PenaltySettings settings = penaltySettingsService.current();
        return installmentRepository.findUpcomingForShops(today, today.plusDays(days), shopIds,
                        currentShopContext.currentOrganizationId()).stream()
                .map(installment -> saleMapper.toResponse(installment, today, settings))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InstallmentResponse> late() {
        LocalDate today = LocalDate.now();
        PenaltySettings settings = penaltySettingsService.current();
        return installmentRepository.findLateForShops(today, currentShopContext.accessibleShopIds(),
                        currentShopContext.currentOrganizationId()).stream()
                .map(installment -> saleMapper.toResponse(installment, today, settings))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InstallmentResponse> bySale(Long saleId) {
        CreditSale sale = saleRepository.findDetailById(saleId)
                .orElseThrow(() -> ResourceNotFoundException.of("Contrat", saleId));
        currentShopContext.assertAccessible(sale.getShop().getId());

        LocalDate today = LocalDate.now();
        PenaltySettings settings = penaltySettingsService.current();
        return installmentRepository.findBySaleIdOrderByNumberAsc(saleId).stream()
                .map(installment -> saleMapper.toResponse(installment, today, settings))
                .toList();
    }
}
