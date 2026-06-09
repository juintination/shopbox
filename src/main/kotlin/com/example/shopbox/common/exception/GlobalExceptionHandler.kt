package com.example.shopbox.common.exception

import com.example.shopbox.common.dto.response.ApiResponse
import com.example.shopbox.common.lock.DistributedLockAcquireException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    companion object {
        private const val VALIDATION_ERROR_MESSAGE = "요청 값이 올바르지 않습니다"
        private const val INTERNAL_SERVER_ERROR_MESSAGE = "서버 내부 오류가 발생했습니다"
        private const val LOCK_ACQUIRE_ERROR_MESSAGE = "요청이 너무 많습니다. 잠시 후 다시 시도해주세요"
    }

    @ExceptionHandler(DistributedLockAcquireException::class)
    fun handleDistributedLockAcquireException(
        e: DistributedLockAcquireException,
    ): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ApiResponse.error(LOCK_ACQUIRE_ERROR_MESSAGE))

    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(
        e: BusinessException,
    ): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error(e.message!!))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(
        e: MethodArgumentNotValidException,
    ): ResponseEntity<ApiResponse<Nothing>> {
        val errors = e.bindingResult.fieldErrors.map { it.defaultMessage!! }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error(VALIDATION_ERROR_MESSAGE, errors))
    }

    @ExceptionHandler(Exception::class)
    fun handleException(
        e: Exception,
    ): ResponseEntity<ApiResponse<Nothing>> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error(e.message ?: INTERNAL_SERVER_ERROR_MESSAGE))
}
