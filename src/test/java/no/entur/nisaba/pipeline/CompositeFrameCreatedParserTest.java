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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeFrameCreatedParserTest {

    private static final String NETEX = "http://www.netex.org.uk/netex";

    private static InputStream xml(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String delivery(String dataObjectsContent) {
        return "<?xml version='1.0' encoding='UTF-8'?><PublicationDelivery xmlns=\"" + NETEX + "\" version=\"1.0\">"
                + "<PublicationTimestamp>2021-04-13T11:56:03.128</PublicationTimestamp><ParticipantRef>RB</ParticipantRef>"
                + "<dataObjects>" + dataObjectsContent + "</dataObjects></PublicationDelivery>";
    }

    @Test
    void readsTheCreatedAttributeOfTheSharedDataFile() {
        InputStream in = getClass().getResourceAsStream("/no/entur/nisaba/netex/import/_AVI_shared_data.xml");
        assertEquals(Optional.of("2021-04-13T09:09:45.409"), CompositeFrameCreatedParser.parse(in));
    }

    @Test
    void stopsReadingAtTheFrameSoTrailingGarbageIsNotParsed() {
        String truncated = "<?xml version='1.0'?><PublicationDelivery xmlns=\"" + NETEX + "\"><dataObjects>"
                + "<CompositeFrame created=\"2021-04-13T09:09:45.409\" id=\"x\"><validityConditions>garbage <<< not xml";
        assertEquals(Optional.of("2021-04-13T09:09:45.409"), CompositeFrameCreatedParser.parse(xml(truncated)));
    }

    @Test
    void missingAttributeIsEmpty() {
        assertEquals(Optional.empty(), CompositeFrameCreatedParser.parse(xml(delivery("<CompositeFrame id=\"x\"/>"))));
    }

    @Test
    void blankAttributeIsReturnedAsIs() {
        assertEquals(Optional.of(""), CompositeFrameCreatedParser.parse(xml(delivery("<CompositeFrame created=\"\" id=\"x\"/>"))));
    }

    @Test
    void firstFrameWithTheAttributeWinsLikeTheXPathDid() {
        String two = "<CompositeFrame id=\"a\"/><CompositeFrame created=\"2021-04-01T00:00:00\" id=\"b\"/>"
                + "<CompositeFrame created=\"2021-04-02T00:00:00\" id=\"c\"/>";
        assertEquals(Optional.of("2021-04-01T00:00:00"), CompositeFrameCreatedParser.parse(xml(delivery(two))));
    }

    @Test
    void frameUnderAnotherParentOrDeeperIsIgnored() {
        String nested = "<Wrapper><CompositeFrame created=\"2021-04-01T00:00:00\" id=\"x\"/></Wrapper>";
        assertEquals(Optional.empty(), CompositeFrameCreatedParser.parse(xml(delivery(nested))));
        String otherParent = "<?xml version='1.0'?><PublicationDelivery xmlns=\"" + NETEX + "\"><other>"
                + "<CompositeFrame created=\"2021-04-01T00:00:00\"/></other></PublicationDelivery>";
        assertEquals(Optional.empty(), CompositeFrameCreatedParser.parse(xml(otherParent)));
    }

    @Test
    void wrongRootOrNamespaceIsEmpty() {
        assertEquals(Optional.empty(), CompositeFrameCreatedParser.parse(xml("<Other xmlns=\"" + NETEX + "\"><dataObjects><CompositeFrame created=\"x\"/></dataObjects></Other>")));
        assertEquals(Optional.empty(), CompositeFrameCreatedParser.parse(xml("<PublicationDelivery><dataObjects><CompositeFrame created=\"x\"/></dataObjects></PublicationDelivery>")));
    }

    @Test
    void nanosecondPrecisionPassesThroughUntouched() {
        assertEquals(Optional.of("2026-09-14T04:00:11.657595972"),
                CompositeFrameCreatedParser.parse(xml(delivery("<CompositeFrame created=\"2026-09-14T04:00:11.657595972\"/>"))));
    }

    @Test
    void doctypeAndExternalEntitiesAreRefused() {
        String dtd = "<?xml version='1.0'?><!DOCTYPE PublicationDelivery [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + delivery("<CompositeFrame created=\"&xxe;\"/>").substring("<?xml version='1.0' encoding='UTF-8'?>".length());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CompositeFrameCreatedParser.parse(xml(dtd)));
        assertTrue(e.getMessage().startsWith("Cannot parse the NeTEx file"), e.getMessage());
    }

    @Test
    void malformedXmlBeforeTheFrameFails() {
        assertThrows(IllegalStateException.class, () -> CompositeFrameCreatedParser.parse(xml("<PublicationDelivery xmlns=\"" + NETEX + "\"><dataObjects><<<")));
    }
}
