package com.codemind.indexing;

import com.codemind.project.ProjectQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/index-jobs")
public class IndexJobController {
    private final ProjectQueryService projectQueryService;

    public IndexJobController(ProjectQueryService projectQueryService) {
        this.projectQueryService = projectQueryService;
    }

    @GetMapping("/{jobId}")
    public IndexJobResponse getJob(@PathVariable String jobId) {
        return projectQueryService.getIndexJob(jobId);
    }
}
