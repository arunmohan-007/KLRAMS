package com.fist.rmms_backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Pre-builds the in-memory GeoJSON caches (roads, condition/PCI segments, FWD
 * segments, full road network) right after startup, in a background thread.
 *
 * Without this, those caches sit empty until the first user opens the map,
 * so whoever logs in first after a restart pays the full DB-rebuild cost
 * (roads + segments + FWD + full-network) instead of the app eating it
 * once in the background while nobody is waiting on it.
 */
@Component
public class CacheWarmupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CacheWarmupRunner.class);

    private final RoadController roads;
    private final SegmentService segments;
    private final FwdSegmentService fwdSegments;
    private final IriSegmentService iriSegments;
    private final FullNetworkController fullNetwork;
    private final AssetController assets;

    public CacheWarmupRunner(RoadController roads, SegmentService segments,
                              FwdSegmentService fwdSegments, IriSegmentService iriSegments,
                              FullNetworkController fullNetwork, AssetController assets) {
        this.roads = roads;
        this.segments = segments;
        this.fwdSegments = fwdSegments;
        this.iriSegments = iriSegments;
        this.fullNetwork = fullNetwork;
        this.assets = assets;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread t = new Thread(this::warmAll, "cache-warmup");
        t.setDaemon(true);
        t.start();
    }

    private void warmAll() {
        long start = System.currentTimeMillis();
        try { roads.warm(); } catch (Exception e) { log.warn("Road cache warm-up failed", e); }
        try { segments.ensureDefaultPci(); } catch (Exception e) { log.warn("Stored PCI backfill failed", e); }
        try { segments.segmentsGeoJson(); } catch (Exception e) { log.warn("Condition segment cache warm-up failed", e); }
        /* Promote legacy FWD point geoms to From..To line stretches before tiles are hit. */
        try { assets.warm(); } catch (Exception e) { log.warn("FWD line-geom warm-up failed", e); }
        try { fwdSegments.segmentsGeoJson(); } catch (Exception e) { log.warn("FWD segment cache warm-up failed", e); }
        try { iriSegments.segmentsGeoJson(); } catch (Exception e) { log.warn("2 km IRI cache warm-up failed", e); }
        try { fullNetwork.warm(); } catch (Exception e) { log.warn("Full network cache warm-up failed", e); }
        log.info("Map caches warmed in {} ms", System.currentTimeMillis() - start);
    }
}
