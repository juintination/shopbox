package com.example.shopbox.delivery.repository

import com.example.shopbox.delivery.entity.Delivery
import org.springframework.data.jpa.repository.JpaRepository

interface DeliveryRepository : JpaRepository<Delivery, Long>
