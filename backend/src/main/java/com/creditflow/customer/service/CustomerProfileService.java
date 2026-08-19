package com.creditflow.customer.service;

import com.creditflow.customer.dto.CustomerDetailResponse;
import com.creditflow.customer.dto.CustomerResponse;
import com.creditflow.payment.dto.PaymentResponse;
import com.creditflow.payment.repository.PaymentRepository;
import com.creditflow.payment.service.PaymentService;
import com.creditflow.sale.dto.SaleResponse;
import com.creditflow.sale.repository.CreditSaleRepository;
import com.creditflow.sale.repository.InstallmentRepository;
import com.creditflow.sale.service.CreditSaleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Vue 360 d'un client : fiche, historique d'achats et historique de paiements.
 */
@Service
@RequiredArgsConstructor
public class CustomerProfileService {

    private final CustomerService customerService;
    private final CreditSaleService creditSaleService;
    private final PaymentService paymentService;
    private final CreditSaleRepository saleRepository;
    private final PaymentRepository paymentRepository;
    private final InstallmentRepository installmentRepository;

    @Transactional(readOnly = true)
    public CustomerDetailResponse profile(Long customerId) {
        CustomerResponse customer = customerService.findById(customerId);
        List<SaleResponse> sales = creditSaleService.findByCustomer(customerId);
        List<PaymentResponse> payments = paymentService.findByCustomer(customerId);

        BigDecimal totalPurchased = saleRepository.sumTotalPriceByCustomer(customerId);
        BigDecimal totalRemaining = saleRepository.sumRemainingByCustomer(customerId);
        BigDecimal totalPaid = paymentRepository.sumByCustomer(customerId);
        long lateInstallments = installmentRepository.countLateByCustomer(customerId, LocalDate.now());

        return new CustomerDetailResponse(
                customer, sales, payments, totalPurchased, totalPaid, totalRemaining, lateInstallments);
    }
}
