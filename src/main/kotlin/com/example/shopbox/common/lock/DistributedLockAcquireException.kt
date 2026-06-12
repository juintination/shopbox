package com.example.shopbox.common.lock

class DistributedLockAcquireException(
    lockKey: String,
) : RuntimeException("Failed to acquire distributed lock: $lockKey")
