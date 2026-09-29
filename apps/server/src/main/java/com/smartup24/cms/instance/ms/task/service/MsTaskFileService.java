package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.instance.mf.service.MfFileService;
import com.smartup24.cms.instance.ms.task.api.TaskFileView;
import com.smartup24.cms.instance.ms.task.repository.MsTaskFileRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskFileRepository.TaskFileRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Files attached to a task. The task is checked in the user's scope, and a new file in the files module's scope. */
@Service
public class MsTaskFileService {

    private final MsTaskFileRepository fileRepository;
    private final MsTaskAccess access;
    private final MfFileService fileService;

    public MsTaskFileService(MsTaskFileRepository fileRepository, MsTaskAccess access, MfFileService fileService) {
        this.fileRepository = fileRepository;
        this.access = access;
        this.fileService = fileService;
    }

    @Transactional
    public void attachFile(Long taskId, UUID fileId, Long currentUserId) {
        access.find(taskId, currentUserId);
        fileService.getFileMetadata(fileId, currentUserId);
        fileRepository.attachFile(taskId, fileId);
    }

    @Transactional
    public void detachFile(Long taskId, UUID fileId, Long currentUserId) {
        access.find(taskId, currentUserId);
        fileRepository.detachFile(taskId, fileId);
    }

    @Transactional(readOnly = true)
    public List<TaskFileRecord> listTaskFiles(Long taskId) {
        return fileRepository.listTaskFiles(taskId);
    }

    @Transactional(readOnly = true)
    public List<TaskFileView> listTaskFiles(Long taskId, Long currentUserId) {
        access.find(taskId, currentUserId);
        return MsTaskViews.all(fileRepository.listTaskFiles(taskId), MsTaskViews::file);
    }
}
