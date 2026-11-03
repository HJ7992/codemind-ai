package com.codemind.source;

import com.codemind.common.ApiException;
import com.codemind.config.SourceProperties;
import com.codemind.project.ProjectEntity;
import com.codemind.project.ProjectRepository;
import com.codemind.project.ProjectStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

@Service
public class SourceViewService {
    private static final int MAX_LINES = 500;
    private static final int MAX_CHARACTERS = 100 * 1024;

    private final SourceVersionRepository sourceVersionRepository;
    private final SourceFileRepository sourceFileRepository;
    private final ProjectRepository projectRepository;
    private final SourceProperties sourceProperties;

    public SourceViewService(
            SourceVersionRepository sourceVersionRepository,
            SourceFileRepository sourceFileRepository,
            ProjectRepository projectRepository,
            SourceProperties sourceProperties) {
        this.sourceVersionRepository = sourceVersionRepository;
        this.sourceFileRepository = sourceFileRepository;
        this.projectRepository = projectRepository;
        this.sourceProperties = sourceProperties;
    }

    @Transactional(readOnly = true)
    public SourceViewResponse read(
            String versionId, String relativePath, int startLine, int endLine) {
        SourceVersionEntity version = sourceVersionRepository.findById(versionId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "SOURCE_VERSION_NOT_FOUND", "源码版本不存在"));
        ProjectEntity project = projectRepository.findById(version.getProjectId())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或已删除"));
        if (project.getStatus() == ProjectStatus.DELETING) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_DELETING", "项目正在删除");
        }
        SourceFileEntity file = sourceFileRepository
                .findBySourceVersionIdAndRelativePath(versionId, relativePath)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "SOURCE_FILE_NOT_FOUND", "源码文件不存在"));
        if (startLine < 1 || endLine < startLine
                || endLine > file.getLineCount() || endLine - startLine + 1 > MAX_LINES) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "SOURCE_LINE_RANGE_INVALID", "源码行号范围无效或超过读取限制");
        }
        Path root = Path.of(sourceProperties.sourceRoot()).toAbsolutePath().normalize();
        Path target = root.resolve(version.getStorageKey())
                .resolve(relativePath).normalize();
        if (!target.startsWith(root.resolve(version.getStorageKey()).normalize())
                || !Files.isRegularFile(target)) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND, "SOURCE_FILE_NOT_FOUND", "源码文件不存在");
        }
        try {
            String content = Files.readString(target, StandardCharsets.UTF_8);
            List<String> lines = Arrays.asList(content.split("\\R", -1));
            String selected = String.join(
                    "\n", lines.subList(startLine - 1, Math.min(endLine, lines.size())));
            if (selected.length() > MAX_CHARACTERS) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "SOURCE_LINE_RANGE_INVALID",
                        "源码行号范围无效或超过读取限制");
            }
            return new SourceViewResponse(
                    versionId, file.getRelativePath(), startLine, endLine, selected);
        } catch (IOException exception) {
            throw new ApiException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "SOURCE_READ_ERROR", "源码读取失败");
        }
    }

    public record SourceViewResponse(
            String sourceVersionId, String path, int startLine, int endLine, String content) {}
}
