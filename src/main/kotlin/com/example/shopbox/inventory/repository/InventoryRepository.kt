package com.example.shopbox.inventory.repository

import com.example.shopbox.inventory.entity.Inventory
import org.springframework.data.jpa.repository.JpaRepository

interface InventoryRepository : JpaRepository<Inventory, Long>
