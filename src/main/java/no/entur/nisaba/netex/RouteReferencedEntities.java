package no.entur.nisaba.netex;

import org.entur.netex.index.api.NetexEntitiesIndex;
import org.rutebanken.netex.model.PointRefStructure;
import org.rutebanken.netex.model.Route;
import org.rutebanken.netex.model.RoutePoint;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * NeTEx entities referenced by a Route.
 * The entities are looked up either in the current PublicationDelivery or in in the common files.
 * When found in the common files, the NeTEx version is added back to the reference in the current PublicationDelivery.
 */
public class RouteReferencedEntities {

    private Route route;
    private Collection<RoutePoint> routePoints;

    private RouteReferencedEntities() {
    }

    public Route getRoute() {
        return route;
    }

    public Collection<RoutePoint> getRoutePoints() {
        return routePoints;
    }

    public static class RouteReferencedEntitiesBuilder {

        private Route route;
        private NetexEntitiesIndex netexCommonEntitiesIndex;

        public RouteReferencedEntitiesBuilder withRoute(Route route) {
            this.route = route;
            return this;
        }

        public RouteReferencedEntitiesBuilder withNetexCommonEntitiesIndex(NetexEntitiesIndex netexCommonEntitiesIndex) {
            this.netexCommonEntitiesIndex = netexCommonEntitiesIndex;
            return this;
        }

        public RouteReferencedEntities build() {

            RouteReferencedEntities routeReferencedEntities = new RouteReferencedEntities();

            routeReferencedEntities.route = RouteReferencedEntitiesBuilder.this.route;

            routeReferencedEntities.routePoints = route.getPointsInSequence().getPointOnRoute().stream()
                    .map(pointOnRoute -> pointOnRoute.getPointRef().getValue())
                    .map(routePointRef -> getRoutePointAndUpdateVersion(netexCommonEntitiesIndex, routePointRef))
                    .collect(Collectors.toSet());

            return routeReferencedEntities;
        }

        private static RoutePoint getRoutePointAndUpdateVersion(NetexEntitiesIndex netexEntitiesIndex, PointRefStructure routePointRef) {
            RoutePoint routePoint = netexEntitiesIndex.getRoutePointIndex().get(routePointRef.getRef());
            routePointRef.setVersion(routePoint.getVersion());
            return routePoint;
        }
    }
}

