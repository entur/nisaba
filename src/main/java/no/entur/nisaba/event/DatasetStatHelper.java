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

import org.apache.camel.Header;
import org.apache.camel.Headers;

import java.util.Map;

import static no.entur.nisaba.Constants.DATASET_STAT;

public class DatasetStatHelper {

    /**
     * Init the ServiceJourneys and common files counters for the current exchange.
     *
     * @param headers all headers in the Camel Exchange.
     */
    public void init(@Headers Map<String, Object> headers) {
        headers.computeIfAbsent(DATASET_STAT, k -> new DatasetStat());
    }

    public void addServiceJourneys(int nbServiceJourneys, @Header(DATASET_STAT) DatasetStat datasetStat) {
        datasetStat.setNbServiceJourneys(datasetStat.getNbServiceJourneys() + nbServiceJourneys);
    }

    public void addCommonFiles(int nbCommonFiles, @Header(DATASET_STAT) DatasetStat datasetStat) {
        datasetStat.setNbCommonFiles(datasetStat.getNbCommonFiles() + nbCommonFiles);
    }


}
