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

package no.entur.nisaba.kafka;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

import java.nio.charset.StandardCharsets;

/**
 * The record format of the idempotent topic and the Kafka client ids.
 * <p>
 * The idempotent topic was written by Camel's {@code KafkaIdempotentRepository} until the Camel removal and
 * is still read by that version during a rolling update or a rollback: {@code key = <import key>},
 * {@code value = "add" | "remove" | "clear"}, String serializers. The only addition is the
 * {@link #CLAIM_ID_HEADER} record header, which Camel ignores. A record with a null value must never be
 * written: Camel's reader NPEs on it during its startup replay.
 */
public final class IdempotentTopicContract {

    public static final String ACTION_ADD = "add";
    public static final String ACTION_REMOVE = "remove";
    public static final String ACTION_CLEAR = "clear";

    /**
     * Record header carrying the id of the claim that produced the record.
     */
    public static final String CLAIM_ID_HEADER = "nisaba-claim-id";

    public static final String CLIENT_ID_REPOSITORY = "nisaba-idempotent-repo";
    public static final String CLIENT_ID_EVENT_PRODUCER = "nisaba-event";
    public static final String CLIENT_ID_EVENT_READER = "nisaba-event-reader";

    private IdempotentTopicContract() {
    }

    /**
     * @return the claim id carried by the record headers, or null for a record written without one
     * (by the Camel version).
     */
    public static String claimId(Headers headers) {
        Header header = headers.lastHeader(CLAIM_ID_HEADER);
        if (header == null || header.value() == null) {
            return null;
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
