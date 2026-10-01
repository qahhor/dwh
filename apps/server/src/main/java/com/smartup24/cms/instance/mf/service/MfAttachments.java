package com.smartup24.cms.instance.mf.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.audit.service.AuditLogService;
import com.smartup24.cms.instance.common.entity.EntityFiles;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.mf.repository.MfFileRepository;
import com.smartup24.cms.instance.mf.repository.MfRecordFileRepository;
import com.smartup24.cms.spi.storage.FileDownloadStream;
import com.smartup24.cms.spi.storage.StorageProvider;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The files of the file and image fields of entities (ADR-0032, 4.7): a person may put in a field a file they
 * uploaded — stored only after the content and virus checks of {@link MfFileService} — or one already attached to the
 * record; the attachment is a row of {@code mf_record_files}, and the file is read through its record. An attached
 * file is not deleted ({@link MfFileMetadataService#delete}). Each change of an attachment is written to the audit
 * log under {@code mf_record_files} (FR-AUD-1).
 */
@Service
public class MfAttachments implements EntityFiles {

    private static final String TABLE = "mf_record_files";
    private static final String FILE_ID = "file_id";

    private final MfRecordFileRepository attachments;
    private final MfFileRepository files;
    private final StorageProvider storage;
    private final AuditLogService audit;

    public MfAttachments(
            MfRecordFileRepository attachments,
            MfFileRepository files,
            StorageProvider storage,
            AuditLogService audit) {
        this.attachments = attachments;
        this.files = files;
        this.storage = storage;
        this.audit = audit;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FileFacts> attachable(UUID fileId, String entity, @Nullable Long recordId, long userId) {
        return attachments.attachable(fileId, entity, recordId, userId);
    }

    @Override
    @Transactional
    public void attach(String entity, long recordId, String fieldKey, @Nullable UUID fileId) {
        UUID previous = attachments.current(entity, recordId, fieldKey).orElse(null);
        if (Objects.equals(previous, fileId)) return;
        attachments.replace(entity, recordId, fieldKey, fileId);
        String event = previous == null ? "I" : fileId == null ? "D" : "U";
        audit.logChange(
                TABLE, entity + ":" + recordId + ":" + fieldKey, event, List.of(FILE_ID), row(previous), row(fileId));
    }

    @Override
    @Transactional
    public void detachAll(String entity, long recordId) {
        if (attachments.deleteRecord(entity, recordId) > 0) {
            audit.logChange(TABLE, entity + ":" + recordId, "D", List.of(FILE_ID), Map.of(), Map.of());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FileFacts> attached(String entity, long recordId, UUID fileId) {
        return attachments.attached(entity, recordId, fileId);
    }

    private static Map<String, Object> row(@Nullable UUID fileId) {
        return fileId == null ? Map.of() : Map.of(FILE_ID, fileId.toString());
    }

    @Override
    public FileDownloadStream open(UUID fileId) {
        MfFileRepository.FileRecord file =
                files.findById(fileId).orElseThrow(() -> new ApiException(ErrorCode.FILE_NOT_FOUND));
        FileDownloadStream stream = storage.download(file.storageBucket(), file.storageKey());
        if (stream == null) {
            throw ApiException.notFound(ErrorCode.FILE_NOT_FOUND, "error.file.object_missing");
        }
        return stream;
    }
}
