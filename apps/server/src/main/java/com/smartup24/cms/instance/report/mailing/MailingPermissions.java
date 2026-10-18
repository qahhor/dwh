package com.smartup24.cms.instance.report.mailing;

import com.smartup24.cms.instance.common.security.PermissionDeclarations;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The rights of report mailings (ADR-0045), checked by {@link MailingAccess} against the report a request names rather
 * than on a handler: {@code own} for the reports a person saved, {@code manage} for any report they may read.
 */
@Component
public class MailingPermissions implements PermissionDeclarations {

    @Override
    public Set<String> pairs() {
        return Set.of(MailingAccess.FORM + "." + MailingAccess.OWN, MailingAccess.FORM + "." + MailingAccess.MANAGE);
    }
}
