package no.entur.nisaba.netex;

import org.entur.netex.index.api.NetexEntitiesIndex;
import org.rutebanken.netex.model.Authority;
import org.rutebanken.netex.model.Branding;
import org.rutebanken.netex.model.BrandingRefStructure;
import org.rutebanken.netex.model.FlexibleLine;
import org.rutebanken.netex.model.Line;
import org.rutebanken.netex.model.Network;
import org.rutebanken.netex.model.Operator;

import java.util.Optional;

/**
 * NeTEx entities referenced by a Line or FlexibleLine.
 */
public class LineReferencedEntities {

    private Operator operator;


    private Branding branding;
    private Network network;
    private Authority authority;


    private LineReferencedEntities() {
    }

    public Operator getOperator() {
        return operator;
    }

    public Network getNetwork() {
        return network;
    }

    public Authority getAuthority() {
        return authority;
    }

    public Branding getBranding() {
        return branding;
    }


    public static class LineReferencedEntitiesBuilder {

        private NetexEntitiesIndex netexCommonEntitiesIndex;
        private NetexEntitiesIndex netexLineEntitiesIndex;

        public LineReferencedEntitiesBuilder withNetexCommonEntitiesIndex(NetexEntitiesIndex netexCommonEntitiesIndex) {
            this.netexCommonEntitiesIndex = netexCommonEntitiesIndex;
            return this;
        }

        public LineReferencedEntitiesBuilder withNetexLineEntitiesIndex(NetexEntitiesIndex netexLineEntitiesIndex) {
            this.netexLineEntitiesIndex = netexLineEntitiesIndex;
            return this;
        }


        public LineReferencedEntities build() {

            LineReferencedEntities lineReferencedEntities = new LineReferencedEntities();

            Optional<Line> optionalLine = netexLineEntitiesIndex.getLineIndex().getAll().stream().findFirst();
            if (optionalLine.isPresent()) {
                lineReferencedEntities.operator = optionalLine.map(line -> line.getOperatorRef().getRef()).map(operatorRef -> netexCommonEntitiesIndex.getOperatorIndex().get(operatorRef)).orElseThrow();
                lineReferencedEntities.network = optionalLine.map(line -> line.getRepresentedByGroupRef().getRef())
                        .map(networkOrGroupOfLinesRef -> findNetwork(networkOrGroupOfLinesRef, netexCommonEntitiesIndex)).orElseThrow();
            } else {
                Optional<FlexibleLine> optionalFlexibleLine = netexLineEntitiesIndex.getFlexibleLineIndex()
                        .getAll()
                        .stream()
                        .findFirst();
                lineReferencedEntities.operator = optionalFlexibleLine
                        .map(flexibleLine -> flexibleLine.getOperatorRef().getRef())
                        .map(operatorRef -> netexCommonEntitiesIndex.getOperatorIndex().get(operatorRef)).orElseThrow();

                lineReferencedEntities.network = optionalFlexibleLine.map(flexibleLine -> flexibleLine.getRepresentedByGroupRef().getRef())
                        .map(networkOrGroupOfLinesRef -> findNetwork(networkOrGroupOfLinesRef, netexCommonEntitiesIndex)).orElseThrow();

            }

            lineReferencedEntities.authority = netexCommonEntitiesIndex.getAuthorityIndex().get(lineReferencedEntities.network.getTransportOrganisationRef().getValue().getRef());

            BrandingRefStructure brandingRef = lineReferencedEntities.operator.getBrandingRef();
            if(brandingRef != null) {
                lineReferencedEntities.branding = netexCommonEntitiesIndex.getBrandingIndex().get(brandingRef.getRef());
            }



            return lineReferencedEntities;
        }

        /**
         * Return the network referenced by the <RepresentedByGroupRef>.
         * RepresentedByGroupRef can reference a network either directly or indirectly (through a group of lines)
         * @param networkOrGroupOfLinesRef
         * @param netexCommonEntitiesIndex
         * @return
         */
        private static Network findNetwork(String networkOrGroupOfLinesRef, NetexEntitiesIndex netexCommonEntitiesIndex) {
            Network network = netexCommonEntitiesIndex.getNetworkIndex().get(networkOrGroupOfLinesRef);
            if (network != null) {
                return network;
            } else {
                return netexCommonEntitiesIndex.getNetworkIndex()
                        .getAll()
                        .stream()
                        .filter(n -> n.getGroupsOfLines()
                                .getGroupOfLines()
                                .stream()
                                .anyMatch(groupOfLine -> groupOfLine.getId().equals(networkOrGroupOfLinesRef)))
                        .findFirst()
                        .orElseThrow();
            }

    }


    }


}

