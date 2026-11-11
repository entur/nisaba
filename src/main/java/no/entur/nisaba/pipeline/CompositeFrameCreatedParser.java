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

package no.entur.nisaba.pipeline;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.util.Optional;

import static no.entur.nisaba.Constants.NETEX_NAMESPACE;

/**
 * Reads the {@code created} attribute of the CompositeFrame of a NeTEx file:
 * {@code /netex:PublicationDelivery/netex:dataObjects/netex:CompositeFrame/@created}.
 * <p>
 * Replaces Camel's XPath, which built a DOM of the whole file. This streams the file and stops at the first
 * CompositeFrame that carries the attribute, like the XPath did (the string value of the first matching
 * attribute node in document order). DTD processing is off and entity references are refused.
 */
public final class CompositeFrameCreatedParser {

    static final String PUBLICATION_DELIVERY = "PublicationDelivery";
    static final String DATA_OBJECTS = "dataObjects";
    static final String COMPOSITE_FRAME = "CompositeFrame";
    static final String CREATED = "created";

    private static final XMLInputFactory FACTORY = createFactory();

    private CompositeFrameCreatedParser() {
    }

    private static XMLInputFactory createFactory() {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        return factory;
    }

    /**
     * @return the value of the {@code created} attribute, possibly blank, or empty when no CompositeFrame
     * directly under {@code dataObjects} carries it. The stream is not closed.
     * @throws IllegalStateException when the XML cannot be parsed up to that point.
     */
    public static Optional<String> parse(InputStream xml) {
        XMLStreamReader reader = null;
        try {
            reader = FACTORY.createXMLStreamReader(xml);
            int depth = 0;
            boolean inDataObjects = false;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    depth++;
                    if (depth == 1 && !isNetex(reader, PUBLICATION_DELIVERY)) {
                        return Optional.empty();
                    }
                    if (depth == 2) {
                        inDataObjects = isNetex(reader, DATA_OBJECTS);
                    }
                    if (depth == 3 && inDataObjects && isNetex(reader, COMPOSITE_FRAME)) {
                        // "" = the attribute without a namespace, as the XPath @created; null would also match p:created
                        String created = reader.getAttributeValue("", CREATED);
                        if (created != null) {
                            return Optional.of(created);
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (depth == 2) {
                        inDataObjects = false;
                    }
                    depth--;
                }
            }
            return Optional.empty();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Cannot parse the NeTEx file: " + e.getMessage(), e);
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // closing the reader does not close the stream; nothing to recover
                }
            }
        }
    }

    private static boolean isNetex(XMLStreamReader reader, String localName) {
        return localName.equals(reader.getLocalName()) && NETEX_NAMESPACE.equals(reader.getNamespaceURI());
    }
}
