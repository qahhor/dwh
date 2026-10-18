package com.smartup24.cms.instance.md.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The file fields of a live form, from simple-form format 7 on (ADR-0043). */
public final class MdSimpleFormFileDtos {
    private MdSimpleFormFileDtos() {}

    /** Most files of a field that holds several. */
    public static final int MAX_FILES = 10;

    /** The largest file a field may name, in megabytes: the upload limit of the files module. */
    public static final int MAX_SIZE_MB = 50;

    /**
     * The rules of a file field, from format 7 on (ADR-0043): one file, or several up to {@code maxFiles}; the content
     * types a file may have (none: any the files module takes) and its largest size. The files themselves go through the
     * files module only — its checks, virus scan and quotas — and the record keeps their ids.
     */
    @Schema(name = "SimpleFormFiles", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Files(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Several files rather than one")
            boolean multiple,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "With multiple: the most files a record holds", minimum = "2", maximum = "10")
            @Nullable
            Integer maxFiles,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @ArraySchema(
                    maxItems = 20,
                    arraySchema = @Schema(description = "The content types a file may have; absent takes any"),
                    schema = @Schema(pattern = "^[a-z]+/[a-z0-9][a-z0-9.+-]{0,99}$"))
            @Nullable
            List<String> contentTypes,

            @JsonInclude(JsonInclude.Include.NON_NULL)
            @Schema(description = "The largest file, in megabytes", minimum = "1", maximum = "50")
            @Nullable
            Integer maxSizeMb) {}
}
