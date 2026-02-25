package org.cardanofoundation.lob.app.support.security;

import java.util.Optional;

import org.springframework.http.ProblemDetail;
import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;

public interface AntiVirusScanner {

    boolean isFileSafe(byte[] fileBytes);

    Either<ProblemDetail, byte[]> readFileBytes(Optional<MultipartFile> file);
}
