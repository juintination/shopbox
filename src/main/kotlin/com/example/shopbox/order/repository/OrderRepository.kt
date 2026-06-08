package com.example.shopbox.order.repository

import com.example.shopbox.order.entity.Order
import org.springframework.data.jpa.repository.JpaRepository

interface OrderRepository : JpaRepository<Order, Long>
