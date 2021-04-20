package no.entur.nisaba.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import no.entur.nisaba.Constants;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class NetexImportEvent {

    public static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(Constants.DATE_TIME_FORMAT);

    private String codespace;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = Constants.DATE_TIME_FORMAT)
    private LocalDateTime importDateTime;


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

    public static NetexImportEvent from(String codespace, LocalDateTime importDateTime) {
        return new NetexImportEvent(codespace, importDateTime);
    }


}
