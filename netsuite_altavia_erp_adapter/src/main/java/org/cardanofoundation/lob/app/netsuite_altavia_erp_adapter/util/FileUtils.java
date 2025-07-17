package org.cardanofoundation.lob.app.netsuite_altavia_erp_adapter.util;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FileUtils {

    private FileUtils() {
        // Utility class, no instantiation allowed
    }

    public static void writeTmpFile(String prefix, String content, String suffix) {
        String filePath = "/tmp/%s-%s.%s".formatted(prefix, System.currentTimeMillis(), suffix);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            writer.write(content);

        } catch (IOException e) {
            log.error("An error occurred while writing to the file: {}", e.getMessage());
        }
    }

}
