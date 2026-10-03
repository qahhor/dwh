package com.smartup24.cms.platform.api.entity.hook;

import com.smartup24.cms.platform.api.PlatformApi;
import com.smartup24.cms.platform.api.Stability;
import java.io.Serial;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A hook or an action's handler refuses the change as a whole (ADR-0032, 6.5; ADR-0033, 3.2): the transaction rolls
 * back and the client gets the problem of its kind with the text of {@code messageKey} in its language. A problem of a
 * field is no refusal: {@link EntitySave#reject} reports it in the one 422 of the save.
 */
@PlatformApi(since = "1.0", stability = Stability.STABLE)
public final class EntityRefusal extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The rule of a catalog key: {@code error.<module>.<name>} (ADR-0021). */
    private static final Pattern MESSAGE_KEY = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+");

    /** What the refusal answers. */
    @PlatformApi(since = "1.0", stability = Stability.STABLE)
    public enum Kind {
        /** 403: the viewer may not do this to this record. */
        FORBIDDEN,
        /** 409: the record's state does not allow it (an item still in use, a closed period). */
        CONFLICT,
        /** 422: the change as a whole is not acceptable. */
        UNPROCESSABLE
    }

    private final Kind kind;
    private final String messageKey;
    private final Map<String, Object> params;

    public EntityRefusal(Kind kind, String messageKey, Map<String, ?> params) {
        super(messageKey, null, false, false);
        this.kind = Objects.requireNonNull(kind, "kind");
        if (messageKey == null || !MESSAGE_KEY.matcher(messageKey).matches()) {
            throw new IllegalArgumentException("A refusal names a catalog key: " + messageKey);
        }
        this.messageKey = messageKey;
        this.params = Map.copyOf(params);
    }

    public static EntityRefusal forbidden(String messageKey) {
        return new EntityRefusal(Kind.FORBIDDEN, messageKey, Map.of());
    }

    public static EntityRefusal conflict(String messageKey, Map<String, ?> params) {
        return new EntityRefusal(Kind.CONFLICT, messageKey, params);
    }

    public static EntityRefusal unprocessable(String messageKey, Map<String, ?> params) {
        return new EntityRefusal(Kind.UNPROCESSABLE, messageKey, params);
    }

    public Kind kind() {
        return kind;
    }

    /** The catalog key of the text the client reads. */
    public String messageKey() {
        return messageKey;
    }

    /** The parameters of that text. */
    public Map<String, Object> params() {
        return params;
    }
}
