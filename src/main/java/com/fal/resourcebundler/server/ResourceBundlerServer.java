package com.fal.resourcebundler.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import org.apache.commons.compress.archivers.tar.*;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.json.*;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;

@SpringBootApplication
@RestController
public class ResourceBundlerServer {

    // Directories for bundle metadata + deduplicated files
    private final Path dataDir = Paths.get("data");
    private final Path filesDir = dataDir.resolve("files");
    private final Path bundlesFile = dataDir.resolve("bundles.json");

    // In-memory storage of all bundle metadata
    private final Map<String, Bundle> bundles = new HashMap<>();

    public static void main(String[] args) throws Exception {
        SpringApplication.run(ResourceBundlerServer.class, args);
    }

    // Metadata for a single stored file
    private record FileMetadata(String hash, String name, long size) {
    }

    // Represents a bundle entry with associated file references
    private record Bundle(String id, List<FileMetadata> fileMetadataList, Instant createdAt) {
    }

    // Initializes the server. Ensures directories exist. Loads existing bundle metadata
    public ResourceBundlerServer() throws Exception {
        Files.createDirectories(filesDir);
        if (Files.exists(bundlesFile)) {
            String text = Files.readString(bundlesFile);
            if (!text.isBlank())
                bundles.putAll(Codec.readBundles(text)); // Load existing bundles
        }
    }

    // Saves current bundle metadata to persistent storage
    @PostMapping("/bundles")
    public Map<String, String> createBundle(@RequestPart("files") MultipartFile[] files)
            throws Exception {
        List<FileMetadata> fileMetadataList = getFileMetadataList(files);
        // Create bundle metadata
        String bundleId = UUID.randomUUID().toString().substring(0, 12);
        bundles.put(bundleId, new Bundle(bundleId, fileMetadataList, Instant.now()));
        saveBundle(); // Persist updated bundle metadata

        System.out.println(
                "Created bundle " + bundleId + " with " + fileMetadataList.size() + " files.");
        return Map.of("bundle_id", bundleId);
    }

    // Lists all existing bundles with metadata
    @GetMapping("/bundles")
    public List<Map<String, Object>> listBundles() {
        List<Map<String, Object>> listOfBundles = getListOfBundles();

        System.out.println("Listed " + listOfBundles.size() + " bundles.");
        return listOfBundles;
    }

    // Downloads a bundle as a tar.gz archive by bundle_id
    @GetMapping("/bundles/{id}/download")
    public ResponseEntity<byte[]> downloadBundleById(@PathVariable("id") String bundle_id)
            throws Exception {
        Bundle bundle = bundles.get(bundle_id);
        if (bundle == null || bundle.fileMetadataList.isEmpty()) {
            System.out.println("Bundle not found: " + bundle_id);
            return ResponseEntity.notFound().build();
        }
        // TarArchiveOutputStream -> GzipCompressorOutputStream -> ByteArrayOutputStream
        ByteArrayOutputStream baos = getBaos(bundle);

        System.out.println("Prepared download for bundle " + bundle_id);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=" + bundle_id + ".tar.gz").body(baos.toByteArray());
    }

    // Persists bundle metadata to disk
    private void saveBundle() throws IOException {
        Files.writeString(bundlesFile, Codec.writeBundles(bundles)); // Serialize to JSON
    }

    // Computes the SHA-256 digest of a file, returning a lowercase hex string
    private static String calculateSha256Hash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path);
                DigestOutputStream dout =
                        new DigestOutputStream(OutputStream.nullOutputStream(), digest)) {
            in.transferTo(dout);
        }
        return Codec.toHex(digest.digest()); // Convert bytes to hex string
    }

    private List<FileMetadata> getFileMetadataList(MultipartFile[] files)
            throws IOException, Exception {
        List<FileMetadata> fileMetadataList = new ArrayList<>();

        for (MultipartFile file : files) {
            // Write incoming file to a temporary location
            Path tempPath = Files.createTempFile("upload-", file.getOriginalFilename());
            Files.write(tempPath, file.getBytes());

            // Compute SHA-256 hash of the file
            String hash = calculateSha256Hash(tempPath);
            Path stored = filesDir.resolve(hash);

            // Store file only if it's not already present (deduplication check)
            if (!Files.exists(stored))
                Files.copy(tempPath, stored);

            fileMetadataList
                    .add(new FileMetadata(hash, file.getOriginalFilename(), file.getSize()));
        }
        return fileMetadataList;
    }

    private List<Map<String, Object>> getListOfBundles() {
        List<Map<String, Object>> listOfBundles = new ArrayList<>();

        for (Bundle bundle : bundles.values()) {
            long totalSize =
                    bundle.fileMetadataList.stream().mapToLong(metadata -> metadata.size).sum();
            listOfBundles
                    .add(Map.of("bundle_id", bundle.id, "num_files", bundle.fileMetadataList.size(),
                            "total_size", totalSize, "created_at", bundle.createdAt.toString()));
        }
        return listOfBundles;
    }

    private ByteArrayOutputStream getBaos(Bundle bundle) throws FileNotFoundException, IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try (GzipCompressorOutputStream gz = new GzipCompressorOutputStream(baos);
                TarArchiveOutputStream tar = new TarArchiveOutputStream(gz)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            tar.setAddPaxHeadersForNonAsciiNames(true);

            for (FileMetadata metadata : bundle.fileMetadataList) {
                Path file = filesDir.resolve(metadata.hash());

                if (!Files.exists(file)) {
                    throw new FileNotFoundException("File not found: " + file);
                }

                TarArchiveEntry entry = new TarArchiveEntry(metadata.name());
                entry.setSize(Files.size(file));

                tar.putArchiveEntry(entry);
                Files.copy(file, tar);
                tar.closeArchiveEntry();
            }
            tar.finish();
        }
        return baos;
    }

    // Minimal JSON Codec for persisting bundle metadata: bundle_id, bundle entries, file refs
    private static class Codec {
        static Map<String, Bundle> readBundles(String text) {
            // Reads bundle metadata from JSON text
            JSONObject root = new JSONObject(text);
            Map<String, Bundle> bundleEntries = new HashMap<>();

            for (String id : root.keySet()) {
                JSONObject bundleObj = root.getJSONObject(id);

                // Parse file references
                JSONArray fileMetadataListArr = bundleObj.getJSONArray("fileMetadataList");
                List<FileMetadata> fileMetadataList = new ArrayList<>();

                for (int i = 0; i < fileMetadataListArr.length(); i++) {
                    JSONObject metadataObj = fileMetadataListArr.getJSONObject(i);
                    fileMetadataList.add(new FileMetadata(metadataObj.getString("hash"),
                            metadataObj.getString("name"), metadataObj.getLong("size")));
                }

                // Parse bundle entry
                bundleEntries.put(id, new Bundle(id, fileMetadataList,
                        Instant.parse(bundleObj.getString("createdAt"))));
            }
            return bundleEntries;
        }

        // Serializes bundle metadata to compact JSON
        static String writeBundles(Map<String, Bundle> bundles) {
            JSONObject root = new JSONObject();

            for (Map.Entry<String, Bundle> entry : bundles.entrySet()) {
                Bundle bundle = entry.getValue();

                JSONObject obj = new JSONObject();
                obj.put("createdAt", bundle.createdAt().toString());

                JSONArray fileMetadataListArr = new JSONArray();
                for (FileMetadata metadata : bundle.fileMetadataList) {
                    fileMetadataListArr.put(new JSONObject().put("hash", metadata.hash())
                            .put("name", metadata.name()).put("size", metadata.size()));
                }

                obj.put("fileMetadataList", fileMetadataListArr);
                root.put(entry.getKey(), obj);
            }
            return root.toString();
        }

        // Converts raw bytes to lowercase hexadecimal string (SHA-256 helper)
        static String toHex(byte[] bytes) {
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
    }
}
