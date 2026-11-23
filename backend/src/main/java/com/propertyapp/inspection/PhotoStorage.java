package com.propertyapp.inspection;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Stores uploaded photographs and returns their storage keys. This implementation writes to a
 * local directory that the ai-service reads with its local store; a Supabase Storage
 * implementation can replace it without changing the controller or the ai-service contract.
 */
@Component
public class PhotoStorage {

	private final Path root;

	public PhotoStorage(InspectionProperties properties) {
		this.root = Path.of(properties.photoDir()).toAbsolutePath().normalize();
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

	/** Saves the bytes under {@code properties/<propertyId>/<random>.<ext>} and returns that key. */
	public String save(String propertyId, byte[] data, String extension) {
		String key = "properties/" + propertyId + "/" + UUID.randomUUID() + "." + extension;
		Path target = root.resolve(key).normalize();
		if (!target.startsWith(root)) {
			throw new IllegalArgumentException("Invalid property id");
		}
		try {
			Files.createDirectories(target.getParent());
			Files.write(target, data);
		} catch (IOException e) {
			throw new UncheckedIOException("Could not store photo", e);
		}
		return key;
	}

	Path root() {
		return root;
	}
}
