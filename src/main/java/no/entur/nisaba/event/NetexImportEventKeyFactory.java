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
import org.apache.camel.Header;

import java.time.LocalDateTime;

import static no.entur.nisaba.Constants.DATE_TIME_FORMATTER;

/**
 * Create the unique key identifying the current import.
 */
public class NetexImportEventKeyFactory {


    private NetexImportEventKeyFactory() {

    }

    public static String createNetexImportEventKey(@Header(value = Constants.DATASET_CODESPACE) String codespace,
                                                   @Header(value = Constants.DATASET_CREATION_TIME) LocalDateTime creationDate) {
        return codespace + '_' + DATE_TIME_FORMATTER.format(creationDate).replace(':', '_');
    }

}
