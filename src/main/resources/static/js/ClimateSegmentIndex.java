package com.fist.rmms_backend.climate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/**
 * One row per (section_label, start_chainage, end_chainage) — aligned 1:1 with
 * the condition_segments grid so climate, condition and PCI share a spatial unit.
 *
 * raw_* columns are filled ONCE by the PostGIS extraction (build 77).
 * idx_*, cvi and cvi_category are re-derived cheaply by ClimateService.recalc().
 */
@Entity
@Table(
    name = "climate_segment_index",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_climate_seg",
        columnNames = {"section_label", "start_chainage", "end_chainage"}))
public class ClimateSegmentIndex {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "section_label", length = 128, nullable = false)
    private String sectionLabel;

    @Column(name = "start_chainage", nullable = false)
    private Double startChainage;

    @Column(name = "end_chainage", nullable = false)
    private Double endChainage;

    // ---- raw extracted values (build 77 / PostGIS) ----
    @Column(name = "rfi_raw")   private Double rfiRaw;
    @Column(name = "rfi_class", length = 32) private String rfiClass;
    @Column(name = "sli_raw")   private Double sliRaw;
    @Column(name = "fsi_raw")   private Double fsiRaw;
    @Column(name = "fsi_class", length = 32) private String fsiClass;

    // ---- derived indexes (recalc) ----
    @Column(name = "idx_rfi") private Double idxRfi;
    @Column(name = "idx_sli") private Double idxSli;
    @Column(name = "idx_fsi") private Double idxFsi;
    @Column(name = "cvi")     private Double cvi;
    @Column(name = "cvi_category", length = 32) private String cviCategory;

    @Column(name = "source_info", columnDefinition = "text")
    private String sourceInfo;

    @Column(name = "last_updated")
    private LocalDateTime lastUpdated;

    public ClimateSegmentIndex() { }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSectionLabel() { return sectionLabel; }
    public void setSectionLabel(String v) { this.sectionLabel = v; }

    public Double getStartChainage() { return startChainage; }
    public void setStartChainage(Double v) { this.startChainage = v; }

    public Double getEndChainage() { return endChainage; }
    public void setEndChainage(Double v) { this.endChainage = v; }

    public Double getRfiRaw() { return rfiRaw; }
    public void setRfiRaw(Double v) { this.rfiRaw = v; }

    public String getRfiClass() { return rfiClass; }
    public void setRfiClass(String v) { this.rfiClass = v; }

    public Double getSliRaw() { return sliRaw; }
    public void setSliRaw(Double v) { this.sliRaw = v; }

    public Double getFsiRaw() { return fsiRaw; }
    public void setFsiRaw(Double v) { this.fsiRaw = v; }

    public String getFsiClass() { return fsiClass; }
    public void setFsiClass(String v) { this.fsiClass = v; }

    public Double getIdxRfi() { return idxRfi; }
    public void setIdxRfi(Double v) { this.idxRfi = v; }

    public Double getIdxSli() { return idxSli; }
    public void setIdxSli(Double v) { this.idxSli = v; }

    public Double getIdxFsi() { return idxFsi; }
    public void setIdxFsi(Double v) { this.idxFsi = v; }

    public Double getCvi() { return cvi; }
    public void setCvi(Double v) { this.cvi = v; }

    public String getCviCategory() { return cviCategory; }
    public void setCviCategory(String v) { this.cviCategory = v; }

    public String getSourceInfo() { return sourceInfo; }
    public void setSourceInfo(String v) { this.sourceInfo = v; }

    public LocalDateTime getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(LocalDateTime v) { this.lastUpdated = v; }
}
