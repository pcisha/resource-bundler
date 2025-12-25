# Resource Bundler

A system for managing resource bundles consisting collections of files and directories associated with a build.
The system consists of:
- A server that stores files locally, deduplicated by content.
- A CLI that allows users to create, list, and download bundles.

The implementation prioritizes clarity, correctness, and extensibility over complexity.

### Features:
- Create bundles from files or directories.
- Deduplicate files across bundles using SHA-256.
- Persist bundle metadata locally.
- Download bundles as tar.gz archives.
- Minimal dependencies and configuration.
- Project Structure:
```
src/main/java/com/pcisha/resourcebundler
├── server   # Spring Boot server
└── cli      # Command-line client
```
- Runtime data (created automatically):
```
data/
├── files/        # Deduplicated file blobs (by hash)
└── bundles.json  # Bundle metadata
```
- Build:
Requires Java 21.
```
./mvnw clean package
```
- Run Server:
```
java -jar target/resourcebundler-1.0.0.jar server
```
- The server listens on:
```
http://localhost:8080
```

### CLI Usage: 
- Create a bundle:
```
java -jar target/resourcebundler-1.0.0.jar bundle create <path_to_files_or_directories>
```
- `<path_to_files_or_directories>` may be a file or directory.
- Directories are traversed recursively.
- Files already uploaded in previous bundles are not re-stored.

Example:
```
java -jar target/resourcebundler-1.0.0.jar bundle create src/main/resources/small

java -jar target/resourcebundler-1.0.0.jar bundle create src/main/resources/nested

java -jar target/resourcebundler-1.0.0.jar bundle create src/main/resources/duplicate
```
- List bundles:
```
java -jar target/resourcebundler-1.0.0.jar bundle list
```
Outputs bundle metadata including: bundle ID, number of files, total size, creation timestamp.

- Download a bundle:
```
java -jar target/resourcebundler-1.0.0.jar bundle download <bundle_id>
```
Example:
```
java -jar target/resourcebundler-1.0.0.jar bundle download 1404819b-2fd
```
Downloads a tar.gz archive containing the bundled files.

- Un-archive to confirm files or directories were bundled correctly:
```
tar -tzf output/1404819b-2fd.tar.gz
```

### Design:
- Deduplication is implemented via SHA-256 content hashing.
- Multipart uploads are streamed to avoid loading files into memory.
- Archives are generated on demand without temporary files.
- Persistence uses local disk storage for simplicity.
- No unit tests are included per the challenge instructions.
- Error and exception handling is implemented.

### Tradeoffs
- Multipart requests are constructed manually to minimize dependencies.
- Archives are buffered in memory for simplicity.
- Concurrency control and authentication are out of scope.

### Extensibility:
The codebase is structured to support easy additions such as:
- Bundle deletion.
- Metadata enrichment.
- Pagination.
- Batching and Streaming downloads.
- Alternative storage backends: Database or caching.

#
##### Author: Prachi Shah @ https://pcisha.my.canva.site/

##### Date: December 24, 2025

P.S. The default copyright laws apply.