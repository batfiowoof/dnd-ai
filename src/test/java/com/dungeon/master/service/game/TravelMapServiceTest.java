package com.dungeon.master.service.game;

import com.dungeon.master.model.dto.RegionNode;
import com.dungeon.master.model.dto.WorldRegion;
import com.dungeon.master.model.dto.WorldSubregion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the location graph resolution, including the nested subregion mini-map. */
class TravelMapServiceTest {

    @Test
    void resolvesSubregionsIntoANestedLocalGraph() {
        WorldRegion saltmarsh = new WorldRegion("Saltmarsh", "City", "A port town",
                20.0, 30.0, List.of("Firewatch"),
                List.of(new WorldSubregion("The Docks", "District", "wharves", 10.0, 10.0, List.of("Town Square")),
                        new WorldSubregion("Town Square", "District", "market", 40.0, 40.0, List.of("The Docks"))));
        WorldRegion firewatch = new WorldRegion("Firewatch", "Keep", "A watchtower",
                70.0, 60.0, List.of("Saltmarsh"), null);

        List<RegionNode> nodes = TravelMapService.resolveNodes(List.of(saltmarsh, firewatch));

        RegionNode salt = nodes.stream().filter(n -> n.name().equals("Saltmarsh")).findFirst().orElseThrow();
        assertEquals(2, salt.subregions().size(), "both subregions resolve as nested nodes");
        RegionNode docks = salt.subregions().stream()
                .filter(n -> n.name().equals("The Docks")).findFirst().orElseThrow();
        // The local connection is symmetrized just like the overland graph.
        assertTrue(docks.connections().stream().anyMatch(c -> c.equalsIgnoreCase("Town Square")));

        RegionNode fire = nodes.stream().filter(n -> n.name().equals("Firewatch")).findFirst().orElseThrow();
        assertNotNull(fire.subregions());
        assertTrue(fire.subregions().isEmpty(), "a region without subregions gets an empty local graph");
    }

    @Test
    void synthesizesLocalRoadsWhenNoSubregionConnectionsAuthored() {
        WorldRegion region = new WorldRegion("Wilds", "Wilds", "untamed",
                50.0, 50.0, null,
                List.of(new WorldSubregion("Glade", "Site", "clearing"),
                        new WorldSubregion("Cave", "Site", "dark hollow"),
                        new WorldSubregion("Falls", "Site", "waterfall")));

        List<RegionNode> nodes = TravelMapService.resolveNodes(List.of(region));
        RegionNode wilds = nodes.get(0);

        // With no authored local routes, an MST connects every subregion (each has >= 1 edge).
        assertEquals(3, wilds.subregions().size());
        assertTrue(wilds.subregions().stream().allMatch(s -> !s.connections().isEmpty()),
                "MST fallback makes every subregion reachable");
    }
}
