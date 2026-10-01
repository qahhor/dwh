package com.smartup24.cms.instance.mf.service;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.entity.EntityFiles;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.mf.repository.MfFileRepository;
import com.smartup24.cms.instance.mf.repository.MfRecordFileRepository;
import com.smartup24.cms.spi.storage.FileDownloadStream;
import com.smartup24.cms.spi.storage.StorageProvider;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The files of the file and image fields of entities (ADR-0032, 4.7): a person may put in a field a file they
 * uploaded — stored only after the content and virus checks of {@link MfFileService} — or one already attached to the
 * record; the attachment is a row of {@code mf_record_files}, and the file is read through its record. An attached
 * file is not deleted ({@link MfFileMetadataService#delete}).
 */
@Service
public class MfAttachments implements EntityFiles {

    private final MfRecordFileRepository attachments;
    private final MfFileRepository files;
    private final StorageProvider storage;

    public MfAttachments(MfRecordFileRepository attachments, MfFileRepository files, StorageProvider storage) {
        this.attachments = attachments;
        this.files = files;
        this.storage = storage;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FileFacts> attachable(UUID fileId, String entity, @Nullable Long recordId, long userId) {
        return attachments.attachable(fileId, entity, recordId, userId);
    }

    @Override
    @Transactional
    public void attach(String entity, long recordId, String fieldKey, @Nullable UUID fileId) {
        attachments.replace(entity, recordId, fieldKey, fileId);
    }

    @Override
    @Transactional
    public void detachAll(String entity, long recordId) {
        attachments.deleteRecord(entity, recordId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FileFacts> attached(String entity, long recordId, UUID fileId) {
        return attachments.attached(entity, recordId, fileId);
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
