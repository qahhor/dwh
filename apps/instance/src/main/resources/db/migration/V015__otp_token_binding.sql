-- ============================================================================
-- V015: одноразовый код привязывается к своему токену (FR-AUTH-5)
--
-- Дефект, найденный при пересмотре M3 30.08. Проверка второго фактора искала
-- код так:
--
--     Long userId = extractUserIdFromOtpToken(otpToken);   // return 1L;
--     var otp = otpCodeRepository.findLatestActiveByUserId(userId);
--
-- То есть `otp_token`, выданный на первом шаге входа, не был связан ни с чем:
-- метод возвращал захардкоженную единицу — идентификатор администратора.
-- Любой непустой токен приводил к поиску кода администратора, и при совпадении
-- шестизначного кода выдавалась сессия администратора. Одновременно настоящий
-- пользователь со включённой 2FA войти не мог никогда: его код искали не там.
--
-- Здесь токен становится тем, чем должен был быть: секретом, который хранится
-- хешем рядом с кодом и по которому код и находится.
--
-- purpose разделяет два применения одной таблицы: вход по второму фактору и
-- подтверждение владения каналом при его привязке.
-- ============================================================================

alter table kauth_otp_codes add column otp_token_hash text;
alter table kauth_otp_codes add column purpose text not null default 'login'
    check (purpose in ('login', 'channel_verify'));

comment on column kauth_otp_codes.otp_token_hash is
    'SHA-256 токена, выданного клиенту на первом шаге: по нему и только по нему ищется код';

-- Частичный уникальный индекс: старые строки без токена остаются, но найти
-- по ним ничего нельзя — они и не должны быть пригодны.
create unique index kauth_otp_codes_token_idx
    on kauth_otp_codes (otp_token_hash) where otp_token_hash is not null;

create index kauth_otp_codes_active_idx
    on kauth_otp_codes (user_id, purpose) where not is_used;

-- Коды, выпущенные до этой миграции, использовать нельзя: они не привязаны
-- к токену, и найти их правильным способом невозможно.
update kauth_otp_codes set is_used = true where otp_token_hash is null and not is_used;
