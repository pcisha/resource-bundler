package com.pcisha.resourcebundler.cli;

import picocli.CommandLine;
import picocli.CommandLine.*;

import java.net.http.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

import org.json.*;

@Command(name = "bundle", description = "Resource bundle CLI to create, list, and download bundles",
        subcommands = {ResourceBundlerCli.Create.class, ResourceBundlerCli.ListCmd.class,
                ResourceBundlerCli.Download.class},
        mixinStandardHelpOptions = false)
public class ResourceBundlerCli {

    private static final String SERVER_ADDRESS = "http://localhost:8080/bundles";

    public static void main(String[] args) {
        new CommandLine(new ResourceBundlerCli()).setCaseInsensitiveEnumValuesAllowed(true)
                .execute(args);
    }

    // "bundle create" command.
    @Command(name = "create", description = "Upload files or directories and create a bundle")
    public static class Create implements Runnable {
        @Parameters(arity = "1..*", description = "Files or directories to upload")
        private List<String> paths;

        @Override
        public void run() {
            try {
                Multipart multipartRequest = new Multipart();

                for (String input : paths) {
                    Path path = Paths.get(input);

                    if (!Files.exists(path)) {
                        System.out.println("Path not found: " + path);
                        return;
                    }
                    if (Files.isDirectory(path)) {
                        Files.walk(path).filter(Files::isRegularFile)
                                .forEach(multipartRequest::addFile);
                    } else {
                        multipartRequest.addFile(path);
                    }
                }

                HttpRequest request = HttpRequest.newBuilder().uri(URI.create(SERVER_ADDRESS))
                        .header("Content-Type", multipartRequest.contentType())
                        .POST(multipartRequest.body()).build();

                HttpResponse<String> response = HttpClient.newHttpClient().send(request,
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    System.err.println("Failed to create bundle.");
                    System.err.println(response.body());
                    return;
                }

                System.out.println("Bundle created successfully.\n"
                        + new JSONObject(response.body()).toString(2));

            } catch (Exception e) {
                System.err.println("Error creating a bundle: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // "bundle list" command.
    @Command(name = "list", description = "List all existing bundles")
    public static class ListCmd implements Runnable {
        @Override
        public void run() {
            try {
                HttpRequest request =
                        HttpRequest.newBuilder().uri(URI.create(SERVER_ADDRESS)).GET().build();

                HttpResponse<String> response = HttpClient.newHttpClient().send(request,
                        HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() != 200) {
                    System.err.println("Failed to list bundles.");
                    System.err.println(response.body());
                    return;
                }

                System.out.println(
                        "Existing bundles:\n" + new JSONArray(response.body()).toString(2));

            } catch (Exception e) {
                System.err.println("Error listing bundles: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // "bundle download" command.
    @Command(name = "download", description = "Download a bundle archive (tar.gz) by bundle_id")
    public static class Download implements Runnable {
        @Parameters(index = "0", description = "Bundle ID to download")
        private String id;

        @Option(names = "--out", defaultValue = "output",
                description = "Output directory for the downloaded archive")
        private String out;

        @Override
        public void run() {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(SERVER_ADDRESS + "/" + id + "/download")).GET().build();

                HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request,
                        HttpResponse.BodyHandlers.ofByteArray());


                if (response.statusCode() != 200) {
                    System.err.println("Failed to download bundle " + id);
                    System.err.println(new String(response.body()));
                    return;
                }

                Path outputDir = Paths.get(out);
                Files.createDirectories(outputDir);

                Path outputPath = outputDir.resolve(id + ".tar.gz");
                Files.write(outputPath, response.body());

                System.out.println("Saved bundle archive " + id + " at " + outputPath);
            } catch (Exception e) {
                System.err.println("Error downloading bundle " + id + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // Multipart Builder: streaming, copy for file data
    public static class Multipart {
        private static final String FORM_FIELD_NAME = "files";
        private final String boundary = UUID.randomUUID().toString();
        private final List<HttpRequest.BodyPublisher> parts = new ArrayList<>();

        // Adds a file part to the multipart request. This streams file content without loading it
        // entirely into memory.
        // NOTE: Multipart requires CRLF (\r\n). Do not use text blocks here.
        private void addFile(Path file) {
            try {
                String header = "--" + boundary + "\r\n" + "Content-Disposition: form-data; name=\""
                        + FORM_FIELD_NAME + "\"; filename=\"" + file.getFileName() + "\"\r\n"
                        + "Content-Type: application/octet-stream\r\n" + "\r\n";

                parts.add(HttpRequest.BodyPublishers.ofString(header));
                parts.add(HttpRequest.BodyPublishers.ofFile(file));
                parts.add(HttpRequest.BodyPublishers.ofString("\r\n"));

            } catch (Exception e) {
                throw new RuntimeException("Error adding file to multipart request: " + file, e);
            }
        }

        // Builds the multipart request body by concatenating all parts and appending the closing
        // boundary
        private HttpRequest.BodyPublisher body() {
            parts.add(HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));

            return HttpRequest.BodyPublishers
                    .concat(parts.toArray(new HttpRequest.BodyPublisher[0]));
        }

        // Returns the Content-Type header value for multipart/form-data
        private String contentType() {
            return "multipart/form-data; boundary=" + boundary;
        }
    }
}
