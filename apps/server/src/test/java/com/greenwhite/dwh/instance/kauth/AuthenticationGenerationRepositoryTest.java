package com.greenwhite.dwh.instance.kauth;

import com.greenwhite.dwh.instance.config.db.FlywayUtcConfiguration;
import com.greenwhite.dwh.instance.kauth.repository.*;
import com.greenwhite.dwh.instance.md.repository.MdUserRepository;
import com.greenwhite.dwh.instance.common.error.ApiException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class AuthenticationGenerationRepositoryTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");
    static JdbcClient jdbc;
    static KauthSessionRepository sessions;
    static KauthApiTokenRepository tokens;
    static KauthOtpCodeRepository otps;

    @BeforeAll static void setup() {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        FlywayUtcConfiguration.configure(Flyway.configure()).dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(ds);
        sessions = new KauthSessionRepository(jdbc);
        tokens = new KauthApiTokenRepository(jdbc);
        otps = new KauthOtpCodeRepository(jdbc);
    }

    @Test void generationChangeRejectsPhysicallyActiveCredentials() {
        String key = UUID.randomUUID().toString();
        long user = jdbc.sql("insert into md_users(name,login,email) values (:k,:k,:k) returning id").param("k",key).query(Long.class).single();
        sessions.create(user,0,key,"127.0.0.1","test","test");
        tokens.create(user,0,"test","test",key,null);
        otps.create(user,0,"telegram","synthetic",key,"login",Instant.now().plusSeconds(300));
        assertThat(sessions.findActiveByTokenHash(key).isPresent()).isTrue();
        assertThat(tokens.findActiveByTokenHash(key).isPresent()).isTrue();
        assertThat(otps.findActiveByTokenHash(key,"login").isPresent()).isTrue();
        jdbc.sql("update md_users set auth_version=1 where id=:id").param("id",user).update();
        assertThat(sessions.findActiveByTokenHash(key).isEmpty()).isTrue();
        assertThat(tokens.findActiveByTokenHash(key).isEmpty()).isTrue();
        assertThat(otps.findActiveByTokenHash(key,"login").isEmpty()).isTrue();
        assertThat(sessions.findActiveByUserId(user).isEmpty()).isTrue();
        assertThatThrownBy(() -> sessions.create(user,0,key+"old","127.0.0.1","test","test")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> tokens.create(user,0,"test","test",key+"old",null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> otps.create(user,0,"telegram","synthetic",key+"old","login",Instant.now().plusSeconds(300))).isInstanceOf(ApiException.class);
        var session=sessions.create(user,1,key+"new","127.0.0.1","test","test");
        var token=tokens.create(user,1,"test","test",key+"new",null);
        var otp=otps.create(user,1,"telegram","synthetic",key+"new","login",Instant.now().plusSeconds(300));
        assertThat(sessions.findActiveByTokenHash(key+"new").orElseThrow().authenticationVersion()).isEqualTo(1);
        assertThat(tokens.findActiveByTokenHash(key+"new").orElseThrow().authenticationVersion()).isEqualTo(1);
        assertThat(otps.findActiveByTokenHash(key+"new","login").orElseThrow().authenticationVersion()).isEqualTo(1);
        assertThat(sessions.findActiveById(session.id()).isPresent()).isTrue();
        assertThat(tokens.findActiveById(token.id()).isPresent()).isTrue();
        assertThat(sessions.findActiveByUserId(user).size()).isEqualTo(1);
        var mapper=new ObjectMapper();
        for(Object record:List.of(session,token,otp,new MdUserRepository(jdbc,mapper).findById(user).orElseThrow())) {
            var json=mapper.valueToTree(record);
            assertThat(json.has("authenticationVersion")).isFalse();
            assertThat(json.has("id")).isTrue();
        }
        assertThat(mapper.valueToTree(session).has("lastSeenAt")).isTrue();
        assertThat(mapper.valueToTree(token).has("tokenPrefix")).isTrue();
    }

    @Test void passiveUserCannotAuthenticateOrIssueAndExistingValidityFlagsRemainEffective() {
        String key=UUID.randomUUID().toString();
        long user=jdbc.sql("insert into md_users(name,login,email) values (:k,:k,:k) returning id").param("k",key).query(Long.class).single();
        var session=sessions.create(user,0,key,"127.0.0.1","test","test");
        var token=tokens.create(user,0,"test","test",key,null);
        otps.create(user,0,"telegram","synthetic",key,"login",Instant.now().plusSeconds(300));
        jdbc.sql("update md_users set state='P' where id=:id").param("id",user).update();
        assertThat(sessions.findActiveByTokenHash(key).isEmpty()).isTrue();
        assertThat(tokens.findActiveByTokenHash(key).isEmpty()).isTrue();
        assertThat(otps.findActiveByTokenHash(key,"login").isEmpty()).isTrue();
        assertThat(sessions.findActiveById(session.id()).isEmpty()).isTrue();
        assertThat(tokens.findActiveById(token.id()).isEmpty()).isTrue();
        assertThat(sessions.findActiveByUserId(user).isEmpty()).isTrue();
        assertThatThrownBy(() -> sessions.create(user,0,key+"p","127.0.0.1","test","test")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> tokens.create(user,0,"test","test",key+"p",null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> otps.create(user,0,"telegram","synthetic",key+"p","login",Instant.now().plusSeconds(300))).isInstanceOf(ApiException.class);
        jdbc.sql("update md_users set state='A' where id=:id").param("id",user).update();
        sessions.close(session.id());tokens.revoke(token.id(),user);
        assertThat(sessions.findActiveById(session.id()).isEmpty()).isTrue();
        assertThat(tokens.findActiveById(token.id()).isEmpty()).isTrue();
        tokens.create(user,0,"test","test",key+"expired",Instant.now().minusSeconds(60));
        assertThat(tokens.findActiveByTokenHash(key+"expired").isEmpty()).isTrue();
        for(String condition:List.of("is_used=true","attempts_left=0","expires_at=now()-interval '1 second'")){
            String hash=UUID.randomUUID().toString();
            var otp=otps.create(user,0,"telegram","synthetic",hash,"channel_verify",Instant.now().plusSeconds(300));
            assertThat(otps.findActiveByTokenHash(hash,"login").isEmpty()).isTrue();
            jdbc.sql("update kauth_otp_codes set "+condition+" where id=:id").param("id",otp.id()).update();
            assertThat(otps.findActiveByTokenHash(hash,"channel_verify").isEmpty()).isTrue();
        }
    }

    @Test void passwordCasRequiresTheExactHashVersionAndActiveUserAndDoesNotBump(){
        String key=UUID.randomUUID().toString();
        long user=jdbc.sql("insert into md_users(name,login,email,password_hash,force_password_change) values (:k,:k,:k,'original',true) returning id")
                .param("k",key).query(Long.class).single();
        var users=new MdUserRepository(jdbc,new ObjectMapper());
        assertThat(users.compareAndSetPassword(user,0,"wrong","replacement")).isFalse();
        assertThat(users.compareAndSetPassword(user,1,"original","replacement")).isFalse();
        jdbc.sql("update md_users set state='P' where id=:id").param("id",user).update();
        assertThat(users.compareAndSetPassword(user,0,"original","replacement")).isFalse();
        jdbc.sql("update md_users set state='A' where id=:id").param("id",user).update();
        assertThat(users.compareAndSetPassword(user,0,"original","replacement")).isTrue();
        assertThat(users.compareAndSetPassword(user,0,"original","loser")).isFalse();
        var updated=users.findById(user).orElseThrow();
        assertThat(updated.passwordHash().equals("replacement")).isTrue();
        assertThat(updated.authenticationVersion()).isZero();
        assertThat(updated.forcePasswordChange()).isFalse();
        assertThat(updated.passwordChangedAt()).isNotNull();
        jdbc.sql("update md_users set password_hash=null where id=:id").param("id",user).update();
        assertThat(users.compareAndSetPassword(user,0,null,"replacement")).isTrue();
        assertThat(users.compareAndSetPassword(user,0,null,"loser")).isFalse();
        assertThat(users.compareAndSetPassword(Long.MAX_VALUE,0,null,"loser")).isFalse();
    }

    @Test void conditionalOtpClaimRequiresOwnerPurposeGenerationAndEveryValidityCondition(){
        String key=UUID.randomUUID().toString();
        long user=jdbc.sql("insert into md_users(name,login,email) values (:k,:k,:k) returning id")
                .param("k",key).query(Long.class).single();
        var otp=otps.create(user,0,"telegram","synthetic",key,"login",Instant.now().plusSeconds(300));
        assertThat(otps.consume(otp.id(),Long.MAX_VALUE,0,"login")).isFalse();
        assertThat(otps.consume(otp.id(),user,0,"channel_verify")).isFalse();
        assertThat(otps.consume(otp.id(),user,1,"login")).isFalse();
        jdbc.sql("update md_users set state='P' where id=:id").param("id",user).update();
        assertThat(otps.consume(otp.id(),user,0,"login")).isFalse();
        jdbc.sql("update md_users set state='A',auth_version=1 where id=:id").param("id",user).update();
        assertThat(otps.consume(otp.id(),user,0,"login")).isFalse();
        for(String condition:List.of("is_used=true","attempts_left=0","expires_at=now()-interval '1 second'")){
            var invalid=otps.create(user,1,"telegram","synthetic",UUID.randomUUID().toString(),"login",Instant.now().plusSeconds(300));
            jdbc.sql("update kauth_otp_codes set "+condition+" where id=:id").param("id",invalid.id()).update();
            assertThat(otps.consume(invalid.id(),user,1,"login")).isFalse();
        }
        var fresh=otps.create(user,1,"telegram","synthetic",UUID.randomUUID().toString(),"channel_verify",Instant.now().plusSeconds(300));
        assertThat(otps.consume(fresh.id(),user,1,"channel_verify")).isTrue();
        assertThat(otps.consume(fresh.id(),user,1,"channel_verify")).isFalse();
    }
}
