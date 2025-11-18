package org.cardanofoundation.lob.app.support.security;

import java.util.Optional;

import org.springframework.web.multipart.MultipartFile;

import io.vavr.control.Either;
import org.zalando.problem.Problem;

public interface AntiVirusScanner {

    boolean isFileSafe(byte[] fileBytes);

    Either<Problem, byte[]> readFileBytes(Optional<MultipartFile> file);
}
