package no.entur.nisaba.event;


/**
 * Hold the service journeys and common files counters for the current import.
 */
public class DatasetStat {

    private int nbServiceJourneys;
    private int nbCommonFiles;

    public int getNbServiceJourneys() {
        return nbServiceJourneys;
    }

    public void setNbServiceJourneys(int nbServiceJourneys) {
        this.nbServiceJourneys = nbServiceJourneys;
    }

    public int getNbCommonFiles() {
        return nbCommonFiles;
    }

    public void setNbCommonFiles(int nbCommonFiles) {
        this.nbCommonFiles = nbCommonFiles;
    }
}
