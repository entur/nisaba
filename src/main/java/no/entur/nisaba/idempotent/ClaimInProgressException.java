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

package no.entur.nisaba.idempotent;

/**
 * The key is claimed by somebody else who has neither confirmed nor released it yet: whether the event will
 * be published is not known. The message must be retried or nacked, never acknowledged as a duplicate: if the
 * claimer died, a redelivery takes its claim over once it is stale.
 */
public class ClaimInProgressException extends IdempotentRepositoryException {

    public ClaimInProgressException(String message) {
        super(message);
    }
}
