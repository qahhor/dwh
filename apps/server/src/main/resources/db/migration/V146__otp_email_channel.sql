set lock_timeout = '2s';
set statement_timeout = '60s';
-- destructive: approved
-- reason: the old channel check is dropped only to be replaced at once by a wider one (email added); no row is
--         changed and every stored value stays valid.
-- approved_by: product owner (plan 10/10 debt review)
-- Plan 10/10, item 0.8: a code sent by email (confirming an email channel, two-factor sign-in by email) could not be
-- stored, because the column allowed only Telegram and SMS since V001. The e2e mail test found it.
alter table kauth_otp_codes drop constraint kauth_otp_codes_channel_check;
alter table kauth_otp_codes
    add constraint kauth_otp_codes_ck_channel check (channel in ('telegram', 'sms', 'email'));
