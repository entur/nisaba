package no.entur.nisaba.stax;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import java.time.LocalDateTime;

public class CreatedAttributeInCompositeFrameHandler extends DefaultHandler {

    private static final String NETEX_ELEMENT_COMPOSITE_FRAME = "CompositeFrame";
    private static final String NETEX_ATTRIBUTE_CREATED = "created";

    private LocalDateTime createdTime;

    public LocalDateTime getCreatedTime() {
        return createdTime;
    }


    @Override
    public void startElement(String uri, String lName, String qName, Attributes attr) {
        if (NETEX_ELEMENT_COMPOSITE_FRAME.equals(qName)) {
            String created = attr.getValue(NETEX_ATTRIBUTE_CREATED);
            createdTime = LocalDateTime.parse(created);

        }
    }


}
