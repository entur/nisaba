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

package no.entur.nisaba.event;

import no.entur.nisaba.Constants;
import no.entur.nisaba.avro.NetexImportEvent;
import org.apache.camel.Header;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class NetexImportEventFactory {

    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(Constants.DATE_TIME_FORMAT);

    private NetexImportEventFactory() {

    }


    public static NetexImportEvent createNetexImportEvent(@Header(value = Constants.DATASET_CODESPACE) String codespace,
                                                              @Header(value = Constants.DATASET_CREATION_TIME) LocalDateTime creationDate) {
        return new NetexImportEvent(codespace, DATE_TIME_FORMATTER.format(creationDate));
    }
}
