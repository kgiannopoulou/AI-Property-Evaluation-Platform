package com.propertyapp.inspection;

import com.propertyapp.privacy.PrivacyStore;
import com.propertyapp.subscriptions.Access.Caller;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stores uploaded photographs and floor plans, and returns their storage keys. This implementation
 * writes to a local directory that the ai-service reads with its local store; a Supabase Storage
 * implementation can replace it without changing the controllers or the ai-service contract.
 *
 * <p>Files are private. Each key starts with the owning account ({@code accounts/<id>/...}), every
 * upload is recorded with its owner and deletion date (stored_files), and the backend serves a file
 * only to that account (see {@code privacy.FileController}). Uploads are deleted after the
 * retention period, when the user deletes them, or with the account.
 */
@Component
public class PhotoStorage {

	private static final Pattern PROPERTY = Pattern.compile("[A-Za-z0-9_-]{1,64}");

	private final Path root;
	private final PrivacyStore files;
	private final Clock clock;
	private final Duration retention;

	public PhotoStorage(InspectionProperties properties, PrivacyStore files, Clock clock,
			@Value("${app.retention.uploaded-file-days:180}") int retentionDays) {
		this.root = Path.of(properties.photoDir()).toAbsolutePath().normalize();
		this.files = files;
		this.clock = clock;
		this.retention = Duration.ofDays(retentionDays);
	}

	/** Image type from the file's first bytes; the browser's content type and file name are not trusted. */
	public static Optional<String> sniffExtension(byte[] data) {
		if (data.length >= 3 && (data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0xd8 && (data[2] & 0xff) == 0xff) {
			return Optional.of("jpg");
		}
		if (data.length >= 8 && (data[0] & 0xff) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
			return Optional.of("png");
		}
		if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
				&& data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
			return Optional.of("webp");
		}
		return Optional.empty();
	}

	public static String contentType(String extension) {
		return switch (extension) {
			case "jpg" -> "image/jpeg";
			case "png" -> "image/png";
			case "webp" -> "image/webp";
			default -> throw new IllegalArgumentException("Unsupported type " + extension);
		};
	}

	/**
	 * Saves the bytes under {@code accounts/<account>/properties/<propertyId>/<random>.<ext>}, records
	 * the owner and deletion date, and returns the key.
	 *
	 * @param kind inspection-photo or floor-plan
	 */
	public String save(Caller caller, String propertyId, byte[] data, String extension, String kind) {
		if (!PROPERTY.matcher(propertyId).matches()) throw new IllegalArgumentException("Invalid property id");
		String key = "accounts/" + caller.accountId() + "/properties/" + propertyId + "/" + UUID.randomUUID() + "." + extension;
		Path target = resolve(key);
		try {
			Files.createDirectories(target.getParent());
			Files.write(target, data);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not store file", e);
		}
		Instant now = clock.instant();
		files.saveFile(new PrivacyStore.StoredFile(key, caller.accountId(), caller.user().id(), propertyId, kind,
				contentType(extension), data.length, now, now.plus(retention)));
		return key;
	}

	/** The bytes of a stored file; empty when it does not exist. Ownership is checked by the caller. */
	public Optional<byte[]> read(String key) {
		Path file = resolve(key);
		try {
			return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
		} catch (IOException e) {
			throw new UncheckedIOException("Could not read file", e);
		}
	}

	/** Deletes the bytes, then the record. */
	public void delete(String key) {
		try {
			Files.deleteIfExists(resolve(key));
		} catch (IOException e) {
			throw new UncheckedIOException("Could not delete file", e);
		}
		files.deleteFileRecord(key);
	}

	private Path resolve(String key) {
		Path target = root.resolve(key).normalize();
		if (!target.startsWith(root) || target.equals(root)) throw new IllegalArgumentException("Invalid storage key");
		return target;
	}

	Path root() {
		return root;
	}
}
