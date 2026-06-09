package com.example.shopbox.common.lock

import io.github.oshai.kotlinlogging.KotlinLogging
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.redisson.api.RedissonClient
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Aspect
@Component
@Order(DistributedLockAspect.ORDER)
class DistributedLockAspect(
    private val redissonClient: RedissonClient,
) {
    private val log = KotlinLogging.logger {}

    @Around("@annotation(distributedLock)")
    fun around(
        joinPoint: ProceedingJoinPoint,
        distributedLock: DistributedLock,
    ): Any? {
        val key = resolveKey(joinPoint, distributedLock.key)

        val lockKey = "lock:$key"
        val lock = redissonClient.getLock(lockKey)

        val acquired = try {
            lock.tryLock(distributedLock.waitTime, distributedLock.leaseTime, distributedLock.timeUnit)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while acquiring distributed lock: $lockKey", e)
        }

        if (!acquired) {
            throw DistributedLockAcquireException(lockKey)
        }

        log.debug { "Acquired distributed lock: $lockKey" }

        return try {
            joinPoint.proceed()
        } finally {
            try {
                if (lock.isHeldByCurrentThread) {
                    lock.unlock()
                    log.debug { "Released distributed lock: $lockKey" }
                }
            } catch (e: Exception) {
                log.warn(e) { "Failed to release distributed lock (ignored): $lockKey" }
            }
        }
    }

    private fun resolveKey(
        joinPoint: ProceedingJoinPoint,
        template: String,
    ): String {
        val signature = joinPoint.signature as MethodSignature
        val paramNames = signature.parameterNames
            ?: throw IllegalStateException("Cannot resolve parameter names.")
        val args = joinPoint.args

        if (paramNames.size != args.size) {
            throw IllegalArgumentException(
                "Parameter name count (${paramNames.size}) does not match argument count (${args.size})",
            )
        }

        val resolved = paramNames.foldIndexed(template) { index, key, name ->
            val value = args[index]
                ?: throw IllegalArgumentException("Lock key parameter '$name' is null: template=$template")
            key.replace("{$name}", value.toString())
        }

        if (resolved.contains(UNRESOLVED_PLACEHOLDER)) {
            throw IllegalArgumentException(
                "Unresolved placeholder in lock key template: template=$template, resolved=$resolved, available parameters: ${paramNames.toList()}",
            )
        }

        return resolved
    }

    companion object {
        const val ORDER = 1 // // TransactionInterceptor(Integer.MAX_VALUE)보다 먼저 실행되어 락이 트랜잭션을 감싸도록 보장
        private val UNRESOLVED_PLACEHOLDER = Regex("""\{[^}]+}""")
    }
}
