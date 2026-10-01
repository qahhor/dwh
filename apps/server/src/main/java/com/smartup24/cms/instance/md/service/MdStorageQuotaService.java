package com.smartup24.cms.instance.md.service;

import com.smartup24.cms.instance.md.repository.MdStorageQuotaRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Storage limits for the file module (ADR-0026): md owns the instance, user and role limits and the rule that
 * combines them; the file module asks here instead of reading md tables.
 */
@Service
public class MdStorageQuotaService {

    /** 50 GB for an instance without a limit of its own. */
    public static final long DEFAULT_INSTANCE_QUOTA_BYTES = 53_687_091_200L;
    /** 1 GB for a user whom neither the user record nor a role gives a limit. */
    public static final long DEFAULT_USER_QUOTA_BYTES = 1_073_741_824L;

    private final MdStorageQuotaRepository quotas;

    public MdStorageQuotaService(MdStorageQuotaRepository quotas) {
        this.quotas = quotas;
    }

    @Transactional(readOnly = true)
    public long instanceQuotaBytes() {
        return quotas.instanceQuotaBytes().orElse(DEFAULT_INSTANCE_QUOTA_BYTES);
    }

    /** The user's own limit, else the largest limit of the user's roles, else the default. */
    @Transactional(readOnly = true)
    public long userQuotaBytes(@Nullable Long userId) {
        if (userId == null) {
            return DEFAULT_USER_QUOTA_BYTES;
        }
        return quotas.userQuotaBytes(userId).orElse(DEFAULT_USER_QUOTA_BYTES);
    }
}
