package com.propertyapp.privacy;

import com.propertyapp.audit.Audit;
import com.propertyapp.inspection.PhotoStorage;
import com.propertyapp.subscriptions.Access.Caller;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Secure access to uploaded photos and floor plans. A file is served only to the account that owns
 * it (team members share the owner's account); anyone else gets the same 404 as for a key that does
 * not exist, so keys cannot be probed. Every read, deletion and refusal is audited.
 */
@RestController
@RequestMapping("/api/v1/files")
class FileController {

	private final PrivacyStore files;
	private final PhotoStorage storage;
	private final Audit audit;

	FileController(PrivacyStore files, PhotoStorage storage, Audit audit) {
		this.files = files;
		this.storage = storage;
		this.audit = audit;
	}

	record FileView(String key, String kind, String propertyRef, String contentType, long sizeBytes, String uploadedAt, String deleteAfter) {}

	@GetMapping(params = "!key")
	List<FileView> list(Caller caller) {
		return files.filesOf(caller.accountId()).stream().map(f -> new FileView(f.key(), f.kind(), f.propertyRef(), f.contentType(),
				f.sizeBytes(), f.createdAt().toString(), f.deleteAfter().toString())).toList();
	}

	@GetMapping(params = "key")
	ResponseEntity<byte[]> read(Caller caller, @RequestParam String key) {
		PrivacyStore.StoredFile f = owned(caller, key, "file.read");
		byte[] data = storage.read(f.key()).orElseThrow(() -> notFound());
		audit.success(caller, "file.read", f.key(), Map.of("kind", f.kind()));
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(f.contentType()))
				.cacheControl(CacheControl.noStore().cachePrivate())
				.header(HttpHeaders.CONTENT_DISPOSITION, "inline")
				.header("X-Content-Type-Options", "nosniff")
				.body(data);
	}

	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(Caller caller, @RequestParam String key) {
		PrivacyStore.StoredFile f = owned(caller, key, "file.delete");
		storage.delete(f.key());
		audit.success(caller, "file.delete", f.key(), Map.of("kind", f.kind()));
	}

	private PrivacyStore.StoredFile owned(Caller caller, String key, String action) {
		Optional<PrivacyStore.StoredFile> f = key.length() > 300 ? Optional.empty() : files.file(key);
		if (f.isEmpty() || !f.get().ownerId().equals(caller.accountId())) {
			audit.denied(caller, action, f.isPresent() ? "another account's file" : "unknown key", Map.of());
			throw notFound();
		}
		return f.get();
	}

	private static ResponseStatusException notFound() {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "No such file.");
	}
}
