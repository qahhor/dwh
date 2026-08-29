package com.greenwhite.dwh.instance.config.idempotency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class IdempotencyServiceTest {

    private final IdempotencyRepository repository = Mockito.mock(IdempotencyRepository.class);
    private final IdempotencyService service = new IdempotencyService(repository);

    @Test
    @DisplayName("Вычисление хэша запроса должно быть детерминированным для одинаковых параметров")
    void shouldComputeDeterministicRequestHash() {
        byte[] body = "{\"title\":\"Sample task\"}".getBytes(StandardCharsets.UTF_8);

        String hash1 = service.computeRequestHash("POST", "/api/v1/tasks/items", null, body);
        String hash2 = service.computeRequestHash("POST", "/api/v1/tasks/items", null, body);

        assertThat(hash1).isNotNull().isEqualTo(hash2);

        byte[] differentBody = "{\"title\":\"Different task\"}".getBytes(StandardCharsets.UTF_8);
        String hash3 = service.computeRequestHash("POST", "/api/v1/tasks/items", null, differentBody);

        assertThat(hash1).isNotEqualTo(hash3);
    }

    @Test
    @DisplayName("Поиск существующего ключа должен возвращать запись из репозитория")
    void shouldFindExistingKey() {
        UUID key = UUID.randomUUID();
        var record = new IdempotencyRepository.IdempotencyRecord(
                key, 1L, "abc123hash", 200, "{\"id\":42}", Instant.now()
        );

        when(repository.findByKey(key)).thenReturn(Optional.of(record));

        var result = service.findByKey(key);

        assertThat(result).isPresent();
        assertThat(result.get().key()).isEqualTo(key);
        assertThat(result.get().responseStatus()).isEqualTo(200);
        assertThat(result.get().responseBody()).isEqualTo("{\"id\":42}");
    }

    @Test
    @DisplayName("Сохранение закэшированного ответа должно делегироваться в репозиторий")
    void shouldSaveIdempotencyRecord() {
        UUID key = UUID.randomUUID();
        service.save(key, 5L, "hash_xyz", 201, "{\"id\":100}");

        verify(repository, times(1)).save(key, 5L, "hash_xyz", 201, "{\"id\":100}");
    }
}
