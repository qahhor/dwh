package com.smartup24.cms.instance.search.service;

import com.smartup24.cms.instance.search.api.SearchManagementDtos.SearchExecutionSnapshot;
import com.smartup24.cms.instance.search.repository.SearchIndexStateRepository;
import org.springframework.stereotype.Service;

/** One SQL statement, with its connection released before the caller performs network I/O. */
@Service
public class SearchExecutionSnapshotReader {
    private final SearchIndexStateRepository repository;

    public SearchExecutionSnapshotReader(SearchIndexStateRepository repository) {
        this.repository = repository;
    }

    public SearchExecutionSnapshot read() {
        return repository.executionSnapshot();
    }
}
