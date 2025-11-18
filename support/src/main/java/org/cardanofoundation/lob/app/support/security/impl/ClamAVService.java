package org.cardanofoundation.lob.app.support.security.impl;

import static org.zalando.problem.Status.BAD_REQUEST;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.zalando.problem.Problem;

import org.cardanofoundation.lob.app.support.security.AntiVirusScanner;

@Service
@Slf4j
public class ClamAVService implements AntiVirusScanner {

    @Value("${lob.security.clamav.host:localhost}")
    private String clamavHost;
    @Value("${lob.security.clamav.port:3310}")
    private int clamavPort;
    @Value("${lob.security.clamav.enabled:false}")
    private boolean clamavEnabled;

    public boolean isFileSafe(byte[] fileBytes) {
        if(clamavEnabled) {
            try (Socket socket = new Socket(clamavHost, clamavPort);
                 OutputStream os = socket.getOutputStream();
                 InputStream is = socket.getInputStream()) {

                os.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII)); // FIXED: no leading 'z'

                int chunkSize = 2048;
                for (int i = 0; i < fileBytes.length; i += chunkSize) {
                    int len = Math.min(chunkSize, fileBytes.length - i);
                    os.write(ByteBuffer.allocate(4).putInt(len).array());
                    os.write(fileBytes, i, len);
                }

                os.write(ByteBuffer.allocate(4).putInt(0).array()); // EOF

                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                String response = reader.readLine();

                return response != null && response.contains("OK");
            } catch (IOException e) {
                log.error("Error communicating with AntiVirus: {}", e.getMessage());
                return false;
            }
        } else {
            return true; // Defaulting to true if ClamAV is not enabled
        }
    }

    public Either<Problem, byte[]> readFileBytes(Optional<MultipartFile> file) {
        byte[] fileBytes;
        try {
            if(file.isPresent() && file.map(f -> !f.isEmpty()).orElse(false)) {
                fileBytes = file.get().getBytes();
                if (!isFileSafe(fileBytes)) {
                    return Either.left(Problem.builder()
                            .withTitle("FILE_VIRUS_DETECTED")
                            .withDetail("The uploaded file contains a virus and cannot be processed.")
                            .withStatus(BAD_REQUEST)
                            .build());
                } else {
                    return Either.right(fileBytes);
                }
            } else {
                return Either.right(null);
            }
        } catch (IOException e) {
            return Either.left(Problem.builder()
                    .withTitle("FILE_READ_ERROR")
                    .withDetail("Error reading file")
                    .withStatus(BAD_REQUEST)
                    .build());
        }
    }

}
