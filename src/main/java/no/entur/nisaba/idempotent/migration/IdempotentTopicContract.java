/*
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 *   https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 *
 */

package no.entur.nisaba.idempotent.migration;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

import java.nio.charset.StandardCharsets;

/**
 * The record format of the Kafka idempotent topic of the previous versions, which this version only reads,
 * once, to migrate it: {@code key = <import key>}, {@code value = "add" | "remove" | "clear"}, String
 * serializers. Camel's {@code KafkaIdempotentRepository} wrote it without headers; the Kafka claim repository
 * that followed added the {@link #CLAIM_ID_HEADER} record header.
 */
final class IdempotentTopicContract {

    static final String ACTION_ADD = "add";
    static final String ACTION_REMOVE = "remove";
    static final String ACTION_CLEAR = "clear";

    /**
     * Record header carrying the id of the claim that produced the record.
     */
    static final String CLAIM_ID_HEADER = "nisaba-claim-id";

    private IdempotentTopicContract() {
    }

    /**
     * @return the claim id carried by the record headers, or null for a record written without one
     * (by the Camel version).
     */
    static String claimId(Headers headers) {
        Header header = headers.lastHeader(CLAIM_ID_HEADER);
        if (header == null || header.value() == null) {
            return null;
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
