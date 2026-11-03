package com.codemind.project;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {
    private final ProjectUploadService projectUploadService;
    private final ProjectQueryService projectQueryService;
    private final ProjectDeleteService projectDeleteService;

    public ProjectController(
            ProjectUploadService projectUploadService,
            ProjectQueryService projectQueryService,
            ProjectDeleteService projectDeleteService) {
        this.projectUploadService = projectUploadService;
        this.projectQueryService = projectQueryService;
        this.projectDeleteService = projectDeleteService;
    }

    @PostMapping("/upload")
    public ResponseEntity<UploadAcceptedResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam("noticeVersion") String noticeVersion,
            @RequestParam("externalTransferAccepted") boolean externalTransferAccepted) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(projectUploadService.upload(file, noticeVersion, externalTransferAccepted));
    }

    @GetMapping("/{projectId}")
    public ProjectResponse getProject(@PathVariable String projectId) {
        return projectQueryService.getProject(projectId);
    }

    @GetMapping
    public java.util.List<ProjectResponse> listProjects() {
        return projectQueryService.listProjects();
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{projectId}")
    public ResponseEntity<Void> deleteProject(@PathVariable String projectId) {
        projectDeleteService.delete(projectId);
        return ResponseEntity.noContent().build();
    }
}
