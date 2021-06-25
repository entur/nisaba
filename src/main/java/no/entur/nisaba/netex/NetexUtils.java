package no.entur.nisaba.netex;

import org.rutebanken.netex.model.Common_VersionFrameStructure;
import org.rutebanken.netex.model.CompositeFrame;
import org.rutebanken.netex.model.PublicationDeliveryStructure;
import org.rutebanken.netex.model.VersionOfObjectRefStructure;

import javax.xml.bind.JAXBElement;
import javax.xml.namespace.QName;
import java.util.List;

public final class NetexUtils {

    private NetexUtils() {

    }


    public static CompositeFrame getCompositeFrame(PublicationDeliveryStructure publicationDeliveryStructure) {
        Common_VersionFrameStructure commonVersionFrameStructure = publicationDeliveryStructure.getDataObjects().getCompositeFrameOrCommonFrame().get(0).getValue();
        return ((CompositeFrame) commonVersionFrameStructure);
    }

    public static List<JAXBElement<? extends Common_VersionFrameStructure>> getFrames(PublicationDeliveryStructure publicationDeliveryStructure) {
        return getCompositeFrame(publicationDeliveryStructure).getFrames().getCommonFrame();
    }


    public static <E> JAXBElement<E> wrapAsJAXBElement(E entity) {
        if (entity == null) {
            return null;
        }
        return new JAXBElement(new QName("http://www.netex.org.uk/netex", getEntityName(entity)), entity.getClass(), null, entity);
    }

    private static <E> String getEntityName(E entity) {
        String localPart = entity.getClass().getSimpleName();

        if (entity instanceof VersionOfObjectRefStructure) {
            // Assuming all VersionOfObjectRefStructure subclasses is named as correct element + suffix ("RefStructure""))
            if (localPart.endsWith("Structure")) {
                localPart = localPart.substring(0, localPart.lastIndexOf("Structure"));
            }
        }
        return localPart;
    }

}
