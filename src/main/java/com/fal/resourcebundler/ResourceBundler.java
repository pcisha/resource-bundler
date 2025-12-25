package com.fal.resourcebundler;

import java.util.Arrays;
import com.fal.resourcebundler.cli.ResourceBundlerCli;
import com.fal.resourcebundler.server.ResourceBundlerServer;
import picocli.CommandLine;

public class ResourceBundler {

    // Entry point for both server and CLI modes
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("""
                    Usage:
                      java -jar resourcebundler.jar server
                      java -jar resourcebundler.jar bundle create <path_to_file_or_directory>
                      java -jar resourcebundler.jar bundle list
                      java -jar resourcebundler.jar bundle download <bundle_id>
                    """);
            return;
        }

        // Start server mode
        if (args[0].equals("server")) {
            ResourceBundlerServer.main(new String[] {});
            return;
        } else { // CLI mode
            new CommandLine(new ResourceBundlerCli())
                    .execute(Arrays.copyOfRange(args, 1, args.length));
        }
    }
}
