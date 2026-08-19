package com.creditflow.sale.service;

import com.creditflow.common.dto.PageResponse;
import com.creditflow.common.repository.Specs;
import com.creditflow.sale.domain.Installment;
import com.creditflow.sale.domain.InstallmentStatus;
import com.creditflow.sale.dto.InstallmentResponse;
import com.creditflow.sale.mapper.SaleMapper;
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
    private final SaleMapper saleMapper;

    @Transactional(readOnly = true)
    public PageResponse<InstallmentResponse> search(String search, InstallmentStatus status,
                                                    LocalDate from, LocalDate to, boolean onlyLate,
                                                    Pageable pageable) {
        LocalDate today = LocalDate.now();
        Page<Installment> page = installmentRepository.findAll(
                Specs.combine(
                        InstallmentSpecifications.matches(search),
                        InstallmentSpecifications.hasStatus(status),
                        InstallmentSpecifications.dueFrom(from),
                        InstallmentSpecifications.dueTo(to),
                        InstallmentSpecifications.lateOn(onlyLate, today)),
                pageable);
        return PageResponse.of(page, installment -> saleMapper.toResponse(installment, today));
    }

    @Transactional(readOnly = true)
    public List<InstallmentResponse> upcoming(int days) {
        LocalDate today = LocalDate.now();
        return installmentRepository.findUpcoming(today, today.plusDays(days)).stream()
                .map(installment -> saleMapper.toResponse(installment, today))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InstallmentResponse> late() {
        LocalDate today = LocalDate.now();
        return installmentRepository.findLate(today).stream()
                .map(installment -> saleMapper.toResponse(installment, today))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InstallmentResponse> bySale(Long saleId) {
        LocalDate today = LocalDate.now();
        return installmentRepository.findBySaleIdOrderByNumberAsc(saleId).stream()
                .map(installment -> saleMapper.toResponse(installment, today))
                .toList();
    }
}
