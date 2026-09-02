package com.greenwhite.dwh.instance.md.service;

/**
 * Порт md-модуля для инвалидации доступа пользователя (инвариант I-U1, FR-USR-4):
 * блокировка обязана в ТОЙ ЖЕ транзакции закрыть сессии и отозвать API-токены.
 * Реализация — в kauth (kauth зависит от md; обратная зависимость запрещена
 * ArchUnit-правилом отсутствия циклов, поэтому — инверсия зависимости).
 */
public interface UserSessionInvalidator {

    void invalidateAllAccess(Long userId);
}
