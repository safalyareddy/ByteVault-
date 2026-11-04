package dev.bytevault.controlplane.object;

import dev.bytevault.controlplane.object.ObjectDtos.ObjectDetail;
import dev.bytevault.controlplane.object.ObjectDtos.ObjectSummary;
import dev.bytevault.controlplane.object.ObjectDtos.Page;
import dev.bytevault.controlplane.security.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/objects")
public class ObjectController {

    private final ObjectQueryService queries;
    private final DownloadService downloads;
    private final DeletionService deletions;
    private final CurrentUser owner;

    public ObjectController(ObjectQueryService queries, DownloadService downloads, DeletionService deletions,
                            CurrentUser owner) {
        this.queries = queries;
        this.downloads = downloads;
        this.deletions = deletions;
        this.owner = owner;
    }

    @GetMapping
    public Page<ObjectSummary> list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        return queries.list(owner.username(), page, size);
    }

    @GetMapping("/{id}")
    public ObjectDetail get(@PathVariable UUID id) {
        return queries.detail(owner.username(), id);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable UUID id) {
        ObjectSummary object = queries.require(owner.username(), id);
        downloads.requireDownloadable(object);
        MediaType type;
        try {
            type = object.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(object.contentType());
        } catch (IllegalArgumentException e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        StreamingResponseBody body = out -> downloads.stream(id, out);
        return ResponseEntity.ok()
                .contentType(type)
                .contentLength(object.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(object.name(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("X-BV-Object-Id", id.toString())
                .body(body);
    }

    /** Idempotent soft delete; physical removal is asynchronous. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> delete(@PathVariable UUID id) {
        String status = deletions.requestDelete(owner.username(), id, owner.username());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("objectId", id.toString(), "status", status));
    }
}
