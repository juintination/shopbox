package com.example.shopbox.payment.repository

import com.example.shopbox.payment.entity.Payment
import org.springframework.data.jpa.repository.JpaRepository

interface PaymentRepository : JpaRepository<Payment, Long>
