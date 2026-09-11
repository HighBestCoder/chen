package org.jumpserver.chen.web.controller;

import org.jumpserver.chen.framework.i18n.MessageUtils;
import org.jumpserver.chen.framework.session.SessionManager;
import org.jumpserver.chen.framework.utils.SessionFiles;
import org.jumpserver.chen.web.entity.UploadResponse;
import org.jumpserver.chen.web.exception.ChenException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/console")
public class ConsoleController {

    @GetMapping("/export/{fileKey}")
    public ResponseEntity<Resource> exportData(@PathVariable String fileKey) {
        if (!SessionManager.getCurrentSession().canDownload()) {
            throw new ChenException(MessageUtils.get("msg.error.no_permission"));
        }
        var path = SessionManager.getCurrentSession().getTempPath();
        Resource resource;
        try {
            resource = new FileSystemResource(SessionFiles.existing(path, fileKey));
        } catch (IOException e) {
            throw new ChenException("Invalid session file");
        }
        var resp = ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-disposition", String.format("attachment; filename=%s", fileKey))
                .body(resource);
        return resp;
    }

    @PostMapping("/upload")
    public UploadResponse uploadData(@RequestParam("file") MultipartFile file) {
        if (!SessionManager.getCurrentSession().canUpload()) {
            throw new ChenException(MessageUtils.get("msg.error.no_permission"));
        }
        try {
            var basePath = SessionManager.getCurrentSession().getTempPath();
            var bytes = file.getBytes();
            Path path = Files.createTempFile(basePath, "sql_", ".sql");
            try {
                Files.write(path, bytes);
                return new UploadResponse(path.getFileName().toString());
            } catch (IOException e) {
                Files.deleteIfExists(path);
                throw e;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
