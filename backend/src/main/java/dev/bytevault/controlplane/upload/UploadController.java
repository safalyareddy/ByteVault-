package dev.bytevault.controlplane.upload;

import dev.bytevault.controlplane.upload.UploadDtos.ChunkUploadResult;
import dev.bytevault.controlplane.upload.UploadDtos.CompleteResult;
import dev.bytevault.controlplane.upload.UploadDtos.CreateUploadRequest;
import dev.bytevault.controlplane.upload.UploadDtos.UploadView;
import dev.bytevault.controlplane.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/uploads")
public class UploadController {

    private final UploadService uploads;
    private final CurrentUser owner;

    public UploadController(UploadService uploads, CurrentUser owner) {
        this.uploads = uploads;
        this.owner = owner;
    }

    /** Creates an upload session; replaying the same Idempotency-Key returns the same session (200). */
    @PostMapping
    public ResponseEntity<UploadView> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                             @Valid @RequestBody CreateUploadRequest request) {
        UploadView view = uploads.create(owner.username(), idempotencyKey, request);
        return ResponseEntity.status(view.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(view);
    }

    @GetMapping("/{uploadId}")
    public UploadView get(@PathVariable UUID uploadId) {
        return uploads.get(owner.username(), uploadId);
    }

    /**
     * Streams one chunk. Returns 200 when the chunk has all required verified replicas, 502 when
     * some replica writes failed (safe to retry the same request).
     */
    @PutMapping("/{uploadId}/chunks/{index}")
    public ResponseEntity<ChunkUploadResult> putChunk(@PathVariable UUID uploadId, @PathVariable int index,
                                                      @RequestHeader(value = "X-Chunk-SHA256", required = false) String sha256,
                                                      HttpServletRequest request) throws IOException {
        try (InputStream body = request.getInputStream()) {
            ChunkUploadResult result = uploads.putChunk(owner.username(), uploadId, index, request.getContentLengthLong(),
                    sha256, body);
            return ResponseEntity.status(result.stored() ? HttpStatus.OK : HttpStatus.BAD_GATEWAY).body(result);
        }
    }

    @PostMapping("/{uploadId}/complete")
    public CompleteResult complete(@PathVariable UUID uploadId) {
        return uploads.complete(owner.username(), uploadId);
    }
}
