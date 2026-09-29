package com.smartup24.cms.instance.ms.task.service;

import com.smartup24.cms.instance.md.service.MdScopeService;
import com.smartup24.cms.instance.ms.task.api.ProjectTaskStatsView;
import com.smartup24.cms.instance.ms.task.api.TaskDetail;
import com.smartup24.cms.instance.ms.task.api.TaskView;
import com.smartup24.cms.instance.ms.task.repository.MsTaskRepository.TaskRecord;
import com.smartup24.cms.instance.ms.task.repository.MsTaskStatsRepository;
import com.smartup24.cms.instance.ms.task.repository.MsTaskStatsRepository.ProjectTaskStats;
import com.smartup24.cms.instance.ms.task.repository.MsTaskTreeRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The task card and the reads around it, every part in the viewer's data scope. Nothing here writes: opening a card
 * is a GET, and marking it viewed is a separate command ({@link MsTaskMemberService#markViewed}).
 */
@Service
public class MsTaskReadService {

    private final MsTaskAccess access;
    private final MsTaskTreeRepository treeRepository;
    private final MsTaskStatsRepository statsRepository;
    private final MdScopeService scopeService;
    private final MsTaskMemberService memberService;
    private final MsTaskFileService fileService;

    public MsTaskReadService(
            MsTaskAccess access,
            MsTaskTreeRepository treeRepository,
            MsTaskStatsRepository statsRepository,
            MdScopeService scopeService,
            MsTaskMemberService memberService,
            MsTaskFileService fileService) {
        this.access = access;
        this.treeRepository = treeRepository;
        this.statsRepository = statsRepository;
        this.scopeService = scopeService;
        this.memberService = memberService;
        this.fileService = fileService;
    }

    /** The task card: the task, its participants, subtasks, ancestor chain and files. */
    @Transactional(readOnly = true)
    public TaskDetail getTaskDetail(Long taskId, Long currentUserId) {
        var task = MsTaskViews.task(access.find(taskId, currentUserId));
        var members = memberService.getTaskMembers(taskId, currentUserId);
        var subtasks = getSubtasks(taskId, currentUserId);
        var ancestors = getAncestorChain(taskId, currentUserId);
        var files = fileService.listTaskFiles(taskId, currentUserId);
        return new TaskDetail(task, members, subtasks, ancestors, files);
    }

    @Transactional(readOnly = true)
    public List<TaskRecord> getSubtasks(Long parentTaskId) {
        return treeRepository.findSubtasks(parentTaskId);
    }

    @Transactional(readOnly = true)
    public List<TaskView> getSubtasks(Long parentTaskId, Long currentUserId) {
        access.find(parentTaskId, currentUserId);
        return MsTaskViews.all(
                treeRepository.findSubtasks(parentTaskId, scopeService.filterForTasks(currentUserId)),
                MsTaskViews::task);
    }

    @Transactional(readOnly = true)
    public List<TaskRecord> getAncestorChain(Long taskId) {
        return treeRepository.findAncestorChain(taskId);
    }

    @Transactional(readOnly = true)
    public List<TaskView> getAncestorChain(Long taskId, Long currentUserId) {
        access.find(taskId, currentUserId);
        return MsTaskViews.all(
                treeRepository.findAncestorChain(taskId, scopeService.filterForTasks(currentUserId)),
                MsTaskViews::task);
    }

    @Transactional(readOnly = true)
    public List<ProjectTaskStats> getProjectTaskStats() {
        return statsRepository.getProjectTaskStats();
    }

    @Transactional(readOnly = true)
    public List<ProjectTaskStatsView> getProjectTaskStats(Long currentUserId) {
        return MsTaskViews.all(
                statsRepository.getProjectTaskStats(scopeService.filterForTasks(currentUserId)), MsTaskViews::stats);
    }
}
