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

package no.entur.nisaba.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import no.entur.nisaba.Constants;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * A NeTEx import event identified by the dataset codespace and its initial import date.
 */
public class NetexImportEvent {

    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(Constants.DATE_TIME_FORMAT);

    private final String codespace;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = Constants.DATE_TIME_FORMAT)
    private final LocalDateTime importDateTime;

    public NetexImportEvent(String codespace, LocalDateTime importDateTime) {
        this.codespace = codespace;
        this.importDateTime = importDateTime;
    }


    public String getCodespace() {
        return codespace;
    }

    public LocalDateTime getImportDateTime() {
        return importDateTime;
    }

    @JsonIgnore
    public String getKey() {
        return codespace + '_' + DATE_TIME_FORMATTER.format(importDateTime);
    }

}
