package com.codemind.source;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/source-versions")
public class SourceViewController {
    private final SourceViewService sourceViewService;

    public SourceViewController(SourceViewService sourceViewService) {
        this.sourceViewService = sourceViewService;
    }

    @GetMapping("/{versionId}/file")
    public SourceViewService.SourceViewResponse read(
            @PathVariable String versionId,
            @RequestParam String path,
            @RequestParam int startLine,
            @RequestParam int endLine) {
        return sourceViewService.read(versionId, path, startLine, endLine);
    }
}
