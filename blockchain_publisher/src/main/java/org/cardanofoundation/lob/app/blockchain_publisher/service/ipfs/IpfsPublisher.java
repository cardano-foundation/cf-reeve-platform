package org.cardanofoundation.lob.app.blockchain_publisher.service.ipfs;

import org.springframework.http.ProblemDetail;

import io.vavr.control.Either;

public interface IpfsPublisher {

        /**
         * Publish the given content to IPFS and return the resulting CID.
         *
         * @param content the content to publish
         * @return the CID of the published content
         */
        Either<ProblemDetail, String> publish(String content);
}
