package org.cardanofoundation.lob.app.organisation.service.csv;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.opencsv.bean.CsvToBeanBuilder;
import com.opencsv.enums.CSVReaderNullFieldIndicator;
import io.vavr.control.Either;
import org.zalando.problem.Problem;

@Service
@Slf4j
@RequiredArgsConstructor
public class CsvParser<T> {

    @Value("${lob.csv.delimiter}")
    private final String delimiter = ";";

    public Either<Problem, List<T>> parseCsv(MultipartFile file, Class<T> type){
        if(Objects.isNull(file) || file.isEmpty()){
            String fileIsNullLog = "File is null";
            log.error(fileIsNullLog);
            return Either.left(Problem.builder()
                    .withTitle(fileIsNullLog)
                    .withDetail(fileIsNullLog)
                    .build());
        }
        try {
            return Either.right(new CsvToBeanBuilder<T>(new InputStreamReader(new ByteArrayInputStream(file.getBytes())))
                    .withIgnoreLeadingWhiteSpace(true)
                    .withType(type)
                    .withSeparator(delimiter.charAt(0))
                    .withIgnoreEmptyLine(true)
                    .withFieldAsNull(CSVReaderNullFieldIndicator.BOTH)
                    .build().parse());
        } catch (Exception e) {
            log.info("Error parsing CSV file");
            return Either.left(Problem.builder()
                    .withTitle("CSV_PARSING_ERROR")
                    .withDetail(e.getMessage())
                    .build());
        }
    }
}
