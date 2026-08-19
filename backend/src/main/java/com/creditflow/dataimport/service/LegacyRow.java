package com.creditflow.dataimport.service;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Une ligne de reprise, apres analyse et controle. */
public record LegacyRow(
        int line,
        String firstName,
        String lastName,
        String phone,
        String address,
        String cniNumber,
        String profession,
        String productName,
        String category,
        BigDecimal totalPrice,
        BigDecimal downPayment,
        int installmentCount,
        LocalDate startDate,
        BigDecimal alreadyPaid
) {

    public BigDecimal financedAmount() {
        return totalPrice.subtract(downPayment);
    }

    public BigDecimal remaining() {
        return financedAmount().subtract(alreadyPaid);
    }

    public String customerFullName() {
        return firstName + " " + lastName;
    }
}
