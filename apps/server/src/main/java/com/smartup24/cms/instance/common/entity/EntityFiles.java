package com.smartup24.cms.instance.common.entity;

import com.smartup24.cms.spi.storage.FileDownloadStream;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The files of the file and image fields of entities (ADR-0032, 4.7): which file a person may put in a field, the
 * attachments of a record to its files and the reading of a file through its record. It lives in {@code common} and
 * the files module implements it ({@code mf.service.MfAttachments}, table {@code mf_record_files}), so {@code common}
 * depends on no module — as the scope contract does.
 */
public interface EntityFiles {

    /** What a field needs to know of a stored file: its name, size and content type. */
    record FileFacts(UUID id, String name, long size, String contentType) {}

    /**
     * The file, when {@code userId} may put it in a field of the record: a file that passed the content and virus
     * checks (only such files are stored) and that the person uploaded, or one already attached to this record.
     * Empty for a file that does not exist and for one of somebody else: the two read alike.
     *
     * @param recordId the record, or null when it is being created
     */
    Optional<FileFacts> attachable(UUID fileId, String entity, @Nullable Long recordId, long userId);

    /** Makes {@code fileId} the attachment of the record's field, replacing the one it had; null leaves none. */
    void attach(String entity, long recordId, String fieldKey, @Nullable UUID fileId);

    /** Removes the attachments of a deleted record; the files without attachments go with the files' cleanup. */
    void detachAll(String entity, long recordId);

    /** The file when it is attached to the record, empty otherwise. */
    Optional<FileFacts> attached(String entity, long recordId, UUID fileId);

    /** The stored content of a file. */
    FileDownloadStream open(UUID fileId);
}
