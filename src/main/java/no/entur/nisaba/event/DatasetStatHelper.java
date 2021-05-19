package no.entur.nisaba.event;

import org.apache.camel.Header;
import org.apache.camel.Headers;

import java.util.Map;

import static no.entur.nisaba.Constants.DATASET_STAT;

public class DatasetStatHelper {

    /**
     * Init the ServiceJourneys and common files counters for the current exchange.
     * @param headers
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
