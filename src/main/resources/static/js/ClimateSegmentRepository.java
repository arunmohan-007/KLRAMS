package com.fist.rmms_backend.climate;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ClimateSegmentRepository extends JpaRepository<ClimateSegmentIndex, Long> {
    List<ClimateSegmentIndex> findBySectionLabelOrderByStartChainage(String sectionLabel);
}
